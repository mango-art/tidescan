package cn.tidescan.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MinuteService {
    private final MairuiClient api; private final ObjectMapper mapper; private final Path cache;
    private final Object[] locks=new Object[64]; private final AtomicInteger newFiles=new AtomicInteger(); private final Object trimLock=new Object();
    public MinuteService(MairuiClient api, ObjectMapper mapper, @Value("${tidescan.root}") String root) throws Exception {
        this.api=api; this.mapper=mapper; cache=Path.of(root,"cache","minute"); Files.createDirectories(cache); Arrays.setAll(locks,i->new Object());
    }
    /** This method is internal. Controllers must restrict data to the game clock. */
    public JsonNode day(String code, LocalDate date) throws Exception {
        if (!code.matches("[0-9]{6}\\.(SH|SZ|BJ)")) throw new IllegalArgumentException("股票代码格式错误");
        synchronized(locks[Math.floorMod(Objects.hash(code,date),locks.length)]) { return readDay(code,date); }
    }
    private JsonNode readDay(String code,LocalDate date) throws Exception {
        Path file=cache.resolve(code+"-"+date+".json");
        if (Files.isRegularFile(file)) { Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); return mapper.readTree(file.toFile()); }
        String market=code.endsWith(".BJ") ? "bj" : "hsstock";
        String day=date.format(DateTimeFormatter.BASIC_ISO_DATE);
        JsonNode rows=api.get(market+"/pro/history/"+code+"/1/n", "?st="+day+"&et="+day+"235959",true);
        if (rows.isEmpty()) throw new IllegalStateException("分钟数据为空：可能停牌、缺失或接口未覆盖");
        for (JsonNode r: rows) if (!r.path("t").asText().startsWith(date.toString())) throw new IllegalStateException("接口返回了目标日期以外的数据");
        Path tmp=Files.createTempFile(cache,"minute-",".tmp");
        try { mapper.writeValue(tmp.toFile(),rows); Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(tmp); }
        if(newFiles.incrementAndGet()%32==0)synchronized(trimLock){trim();} return rows;
    }
    private void trim() throws Exception {
        long limit=5L*1024*1024*1024;
        try (var stream=Files.list(cache)) {
            var files=stream.filter(p->p.getFileName().toString().endsWith(".json")).sorted(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return Long.MAX_VALUE;}})).toList();
            long size=0; for (Path p:files) size+=Files.size(p);
            for (Path p:files) { if (size<=limit) break; long n=Files.size(p); Files.deleteIfExists(p); size-=n; }
        }
    }
}
