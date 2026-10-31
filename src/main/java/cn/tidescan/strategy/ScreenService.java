package cn.tidescan.strategy;

import cn.tidescan.market.MarketStore;
import cn.tidescan.market.MinuteService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static cn.tidescan.strategy.Strategy.*;

/** Screening uses local completed daily bars and historically known share capital. */
@Service
public class ScreenService {
    public record Candidate(String code,String name,Bar prior,Bar yesterday,double floatShares) {}
    public record Match(String code,String name,double price,double turnover,double amount) {}
    private final MarketStore db; private final MinuteService minutes; private final Path savedScreen; private final ObjectMapper mapper=new ObjectMapper();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"strategy-screen");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile List<Match> matches=List.of();
    private volatile String message="尚未选股", day=""; private volatile int total,done,failed,missingCapital,unsynced;
    public ScreenService(MarketStore db,MinuteService minutes,@Value("${tidescan.root}") String root){this.db=db;this.minutes=minutes;this.savedScreen=Path.of(root,"data","screen-state.json");restore();}
    public synchronized Map<String,Object> status(){
        return Map.of("running",running.get(),"day",day,"message",message,"total",total,"done",done,"failed",failed,"missingCapital",missingCapital,"unsynced",unsynced,"matches",matches);
    }
    public void start(LocalDate date,Parameters parameters){
        if(date.isBefore(LocalDate.of(2025,1,1)))throw new IllegalArgumentException("选股日期不能早于 2025-01-01");
        if(!running.compareAndSet(false,true))throw new IllegalStateException("选股正在进行");
        day=date.toString();matches=List.of();total=done=failed=missingCapital=unsynced=0;message="准备历史行情";
        worker.submit(()->{try{screen(date,parameters);}catch(Exception e){message="选股失败："+e.getMessage();}finally{running.set(false);}});
    }
    private void screen(LocalDate date,Parameters p) throws Exception {
        try(Connection c=db.open();PreparedStatement q=c.prepareStatement("SELECT count(*) FROM calendar WHERE day=? AND EXISTS(SELECT 1 FROM calendar WHERE day>?)")){
            q.setString(1,date.toString());q.setString(2,date.toString());try(ResultSet r=q.executeQuery()){r.next();if(r.getInt(1)==0)throw new IllegalArgumentException("日期不是已知交易日或缺少下一交易日");}
        }
        try(Connection c=db.open();PreparedStatement q=c.prepareStatement("SELECT count(*) FROM stocks s WHERE NOT EXISTS (SELECT 1 FROM sync_state x WHERE x.code=s.code AND x.through_day>=?)")){q.setString(1,date.toString());try(ResultSet r=q.executeQuery()){r.next();unsynced=r.getInt(1);}}
        Map<String,Bar> todayBars=new HashMap<>();
        try(Connection c=db.open();PreparedStatement q=c.prepareStatement("SELECT code,o,c,v,a FROM daily WHERE day=?")){
            q.setString(1,date.toString());try(ResultSet r=q.executeQuery()){while(r.next())todayBars.put(r.getString(1),new Bar(r.getDouble(2),r.getDouble(3),r.getDouble(4),r.getDouble(5)));}
        }
        if(todayBars.isEmpty())throw new IllegalStateException("选股日没有本地日线数据，请等待同步完成");
        List<Candidate> candidates=loadCandidates(date,p);total=candidates.size();
        List<Match> found=new ArrayList<>();
        for(Candidate candidate:candidates){
            message="检查 " + candidate.code();
            try{
                Bar today=todayBars.get(candidate.code());
                if(today==null){done++;continue;}
                if(matches(candidate.prior(),candidate.yesterday(),today,candidate.floatShares(),p)){
                    found.add(new Match(candidate.code(),candidate.name(),today.close(),today.volume()*100/candidate.floatShares(),today.amount()));
                    matches=List.copyOf(found);
                }
            }catch(Exception e){failed++;}
            done++;
        }
        message=failed==0&&unsynced==0&&missingCapital==0?"选股完成":"选股完成，数据缺失，结果可能不完整";save();
    }
    private void restore(){
        if(!Files.isRegularFile(savedScreen))return;
        try{JsonNode n=mapper.readTree(savedScreen.toFile());if(n.path("running").asBoolean())return;String d=n.path("day").asText();if(d.isBlank())return;LocalDate.parse(d);List<Match> restored=new ArrayList<>();for(JsonNode m:n.path("matches")){String code=m.path("code").asText();if(!code.matches("[0-9]{6}\\.(SH|SZ|BJ)"))throw new IllegalArgumentException();restored.add(new Match(code,m.path("name").asText(),m.path("price").asDouble(),m.path("turnover").asDouble(),m.path("amount").asDouble()));}day=d;total=n.path("total").asInt();done=n.path("done").asInt();failed=n.path("failed").asInt();missingCapital=n.path("missingCapital").asInt();unsynced=n.path("unsynced").asInt();message=n.path("message").asText("选股完成");matches=List.copyOf(restored);}catch(Exception ignored){message="选股记录无法读取，请重新选股";}
    }
    private void save(){
        try{Files.createDirectories(savedScreen.getParent());Path temp=Files.createTempFile(savedScreen.getParent(),"screen-",".tmp");try{Map<String,Object> snapshot=new LinkedHashMap<>(status());snapshot.put("running",false);mapper.writeValue(temp.toFile(),snapshot);Files.move(temp,savedScreen,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}catch(Exception ignored){message+="；选股结果保存失败";}
    }
    /** SQL only uses bars strictly preceding the decision date. Missing days are surfaced, never fabricated. */
    private List<Candidate> loadCandidates(LocalDate date,Parameters p) throws SQLException {
        List<Candidate> result=new ArrayList<>();
        String sql="SELECT s.code,s.name,d.day,d.o,d.c,d.v,d.a, (SELECT float_shares FROM capital f WHERE f.code=s.code AND f.change_day<=? AND f.notice_day<=? ORDER BY f.change_day DESC,f.notice_day DESC LIMIT 1) AS shares FROM stocks s JOIN daily d ON d.code=s.code WHERE d.day<? AND d.day >= date(?,'-60 days') ORDER BY s.code,d.day DESC";
        try(Connection c=db.open();PreparedStatement q=c.prepareStatement(sql)){
            for(int i=1;i<=4;i++)q.setString(i,date.toString());
            try(ResultSet r=q.executeQuery()){
                String last="";Bar yesterday=null;String name="";double shares=0;
                while(r.next()){
                    String code=r.getString("code");
                    Bar bar=new Bar(r.getDouble("o"),r.getDouble("c"),r.getDouble("v"),r.getDouble("a"));
                    if(!code.equals(last)){last=code;yesterday=bar;name=r.getString("name");shares=r.getDouble("shares");continue;}
                    if(yesterday==null)continue;
                    Bar prior=bar;
                    if(precondition(prior,yesterday,p)){
                        if(shares>0)result.add(new Candidate(code,name,prior,yesterday,shares));else missingCapital++;
                    }
                    yesterday=null;
                }
            }
        }
        return result;
    }
    static boolean precondition(Bar prior,Bar y,Parameters p){
        if(prior.open()<=0||y.open()<=0||prior.volume()<=0)return false;
        boolean red=y.close()>=y.open() && Math.abs(y.close()-y.open())/y.open()<=p.red()/100;
        boolean green=y.close()<y.open() && Math.abs(y.close()-y.open())/y.open()<=p.green()/100;
        return (red||green) && prior.close()>prior.open() && (prior.close()-prior.open())/prior.open()*100>=p.priorBody()
            && y.open()>prior.open() && y.close()>prior.open() && y.volume()<prior.volume();
    }
    static Bar aggregate(JsonNode rows,LocalDate date){
        double open=0,close=0,volume=0,amount=0;String last="";int count=0;
        for(JsonNode r:rows){String t=r.path("t").asText();
            if(!t.startsWith(date.toString())||t.length()<16)throw new IllegalStateException("分钟数据日期错误");
            String hm=t.substring(11,16);if(hm.compareTo("09:30")<0||hm.compareTo("14:50")>=0)continue;
            if(!last.isEmpty()&&hm.compareTo(last)<=0)throw new IllegalStateException("分钟数据顺序异常");
            double o=r.path("o").asDouble(),c=r.path("c").asDouble(),v=r.path("v").asDouble(),a=r.path("a").asDouble();
            if(o<=0||c<=0||v<0||a<0||!r.hasNonNull("v")||!r.hasNonNull("a"))throw new IllegalStateException("分钟数据不完整");
            if(count==0)open=o;close=c;volume+=v*100;amount+=a;last=hm;count++;
        }
        if(count==0||last.compareTo("14:48")<0)throw new IllegalStateException("14:50 前分钟行情不完整");
        return new Bar(open,close,volume,amount);
    }
}
