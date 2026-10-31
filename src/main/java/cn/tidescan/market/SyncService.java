package cn.tidescan.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

@Service
public class SyncService {
    private final MarketStore store; private final MairuiClient api;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{ Thread t=new Thread(r,"market-sync"); t.setDaemon(true); return t; });
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile String status="尚未同步"; private volatile int complete, total, failed;
    @Value("${tidescan.sync-on-start:true}") private boolean automaticSync;
    private static final ZoneId CHINA=ZoneId.of("Asia/Shanghai");
    public SyncService(MarketStore store,MairuiClient api){this.store=store;this.api=api;}
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup(){if(automaticSync)start();}
    @Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void scheduledCheck(){if(automaticSync)start();}
    public Map<String,Object> status(){return Map.of("running",running.get(),"message",status,"complete",complete,"total",total,"failed",failed);}
    public void start(){ if (!running.compareAndSet(false,true)) return; worker.submit(()->{try{sync();}catch(Exception e){status="同步失败："+e.getMessage();}finally{running.set(false);}}); }
    private void sync() throws Exception {
        complete=0; failed=0; total=0; status="更新交易日历和股票列表";
        LocalDate today=LocalDate.now(CHINA);
        for(int year=2025;year<=today.getYear();year++){
            JsonNode days=api.get("tcalendar/list/"+year,"",false);
            try(Connection c=store.open();PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO calendar(day) VALUES(?)")){
                for(JsonNode d:days){p.setString(1,LocalDate.parse(d.asText(),DateTimeFormatter.BASIC_ISO_DATE).toString());p.addBatch();}p.executeBatch();
            }
        }
        for(String endpoint:List.of("hslt/list","bj/list/all")){
            JsonNode stocks=api.get(endpoint,"",false);
            try(Connection c=store.open();PreparedStatement p=c.prepareStatement("INSERT INTO stocks(code,name) VALUES(?,?) ON CONFLICT(code) DO UPDATE SET name=excluded.name")){
                for(JsonNode s:stocks){String code=s.path("dm").asText();if(!code.matches("[0-9]{6}\\.(SH|SZ|BJ)"))continue;p.setString(1,code);p.setString(2,s.path("mc").asText());p.addBatch();}p.executeBatch();
            }
        }
        String cutoff;
        try(Connection c=store.open();PreparedStatement p=c.prepareStatement("SELECT max(day) FROM calendar WHERE day <= ?")){
            LocalDate bound=LocalTime.now(CHINA).isBefore(LocalTime.of(15,0))?today.minusDays(1):today;p.setString(1,bound.toString());try(ResultSet r=p.executeQuery()){r.next();cutoff=r.getString(1);}
        }
        if(cutoff==null)throw new IllegalStateException("交易日历为空");
        List<String> codes=new ArrayList<>();
        try(Connection c=store.open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT code FROM stocks ORDER BY code")){while(r.next())codes.add(r.getString(1));}
        total=codes.size();
        for(String code:codes){status="更新 " + code + " 截至 " + cutoff;try{syncStock(code,cutoff);complete++;}catch(Exception e){failed++;status="同步有缺失，将于下次重试";}}
        status="同步结束；成功 "+complete+"，失败 "+failed+"。上游更新延迟或停牌需再次核验。";
    }
    private void syncStock(String code,String cutoff) throws Exception {
        String from="20241201";
        try(Connection c=store.open();PreparedStatement q=c.prepareStatement("SELECT through_day FROM sync_state WHERE code=?")){
            q.setString(1,code);try(ResultSet r=q.executeQuery()){if(r.next()){String last=r.getString(1);if(last.compareTo(cutoff)>=0)return;from=last.replace("-","");}}
        }
        String type=code.endsWith(".BJ")?"bj":"hsstock";
        JsonNode bars=api.get(type+"/history/"+code+"/d/n","?st="+from+"&et="+cutoff.replace("-",""),false);
        JsonNode capital=api.get(type+"/financial/capital/"+code,"",false);
        if(bars.isEmpty()) throw new IllegalStateException("日线缺失");
        String latest="";
        try(Connection c=store.open()){
            c.setAutoCommit(false);
            try(PreparedStatement b=c.prepareStatement("INSERT OR REPLACE INTO daily(code,day,o,c,v,a,h,l) VALUES(?,?,?,?,?,?,?,?)");PreparedStatement f=c.prepareStatement("INSERT OR REPLACE INTO capital(code,change_day,notice_day,float_shares) VALUES(?,?,?,?)");PreparedStatement u=c.prepareStatement("INSERT OR REPLACE INTO sync_state(code,through_day) VALUES(?,?)")){
                for(JsonNode row:bars){String day=row.path("t").asText();if(day.length()<10)continue;day=day.substring(0,10);if(day.compareTo(cutoff)>0)continue;if(row.path("sf").asInt()!=0 || row.path("v").asDouble()<=0)continue;b.setString(1,code);b.setString(2,day);b.setDouble(3,row.path("o").asDouble());b.setDouble(4,row.path("c").asDouble());b.setDouble(5,row.path("v").asDouble()*100);b.setDouble(6,row.path("a").asDouble());b.setDouble(7,row.path("h").asDouble());b.setDouble(8,row.path("l").asDouble());b.addBatch();if(day.compareTo(latest)>0)latest=day;}
                b.executeBatch();
                for(JsonNode row:capital){String change=normalDate(row.path("bdrq").asText()),notice=normalDate(row.path("plrq").asText());double shares=row.path("ysltag").asDouble();if(change==null||notice==null||shares<=0)continue;f.setString(1,code);f.setString(2,change);f.setString(3,notice);f.setDouble(4,shares);f.addBatch();}f.executeBatch();
                if(!latest.isEmpty()){u.setString(1,code);u.setString(2,latest);u.executeUpdate();}c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    private static String normalDate(String s){try{return LocalDate.parse(s.replace("-",""),DateTimeFormatter.BASIC_ISO_DATE).toString();}catch(Exception e){return null;}}
}
