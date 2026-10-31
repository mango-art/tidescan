package cn.tidescan.game;

import cn.tidescan.market.MarketStore;
import cn.tidescan.market.MinuteService;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** A single server clock gates both chart history and execution of orders. */
@Service
public class GameService {
    private final GameStore db;private final MarketStore market;private final MinuteService minute;
    public GameService(GameStore db,MarketStore market,MinuteService minute){this.db=db;this.market=market;this.minute=minute;}
    public synchronized Map<String,Object> state() throws Exception {
        try(Connection c=db.open();Statement s=c.createStatement()){
            Map<String,Object> out=new LinkedHashMap<>();
            try(ResultSet r=s.executeQuery("SELECT cash,game_day,clock,active,selection_day FROM account WHERE id=1")){r.next();out.put("cash",r.getDouble(1));out.put("day",r.getString(2));out.put("clock",r.getString(3));out.put("active",r.getInt(4)==1);out.put("selectionDay",r.getString(5)==null?r.getString(2):r.getString(5));}
            List<Map<String,Object>> positions=new ArrayList<>();try(ResultSet r=s.executeQuery("SELECT code,quantity,cost,last_buy_day FROM positions ORDER BY code")){while(r.next()){String code=r.getString(1);positions.add(Map.of("code",code,"name",stockName(code),"quantity",r.getInt(2),"cost",r.getDouble(3),"lastBuyDay",r.getString(4)));}}out.put("positions",positions);
            List<Map<String,Object>> orders=new ArrayList<>();try(ResultSet r=s.executeQuery("SELECT id,code,side,quantity,status,day,time,price,fee,reason,realized FROM orders ORDER BY id DESC")){while(r.next()){Map<String,Object> o=new LinkedHashMap<>();o.put("id",r.getInt(1));o.put("code",r.getString(2));o.put("side",r.getString(3));o.put("quantity",r.getInt(4));o.put("status",r.getString(5));o.put("day",r.getString(6));o.put("time",r.getString(7));o.put("price",r.getObject(8));o.put("fee",r.getObject(9));o.put("reason",r.getString(10));o.put("realized",r.getObject(11));orders.add(o);}}out.put("orders",orders);
            try(ResultSet r=s.executeQuery("SELECT count(*),coalesce(sum(realized),0),coalesce(sum(CASE WHEN realized>0 THEN 1 ELSE 0 END),0),coalesce(sum(fee),0) FROM orders WHERE status='FILLED' AND side='SELL' AND realized IS NOT NULL")){r.next();out.put("closedTrades",r.getInt(1));out.put("realized",r.getDouble(2));out.put("wins",r.getInt(3));out.put("sellFees",r.getDouble(4));}return out;
        }
    }
    private String stockName(String code) throws SQLException {
        try(Connection c=market.open();PreparedStatement q=c.prepareStatement("SELECT name FROM stocks WHERE code=?")){q.setString(1,code);try(ResultSet r=q.executeQuery()){return r.next()&&r.getString(1)!=null&&!r.getString(1).isBlank()?r.getString(1):code;}}
    }
    public synchronized void start(LocalDate date) throws Exception {
        if(date.isBefore(LocalDate.of(2025,1,1)))throw new IllegalArgumentException("日期不能早于2025年");
        try(Connection m=market.open();PreparedStatement q=m.prepareStatement("SELECT count(*) FROM calendar WHERE day=? AND EXISTS(SELECT 1 FROM calendar n WHERE n.day>? AND EXISTS(SELECT 1 FROM daily d WHERE d.day=n.day)) AND EXISTS(SELECT 1 FROM daily WHERE day=?)")){
            for(int i=1;i<=3;i++)q.setString(i,date.toString());try(ResultSet r=q.executeQuery()){r.next();if(r.getInt(1)==0)throw new IllegalArgumentException("交易日或行情数据未就绪");}
        }
        try(Connection c=db.open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT active FROM account WHERE id=1")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("游戏已开始，请继续当前日期");}
        try(Connection c=db.open();PreparedStatement q=c.prepareStatement("UPDATE account SET active=1,game_day=?,selection_day=?,clock='15:00' WHERE id=1")){q.setString(1,date.toString());q.setString(2,date.toString());q.executeUpdate();}
    }
    public synchronized void order(String code,String side,double fraction) throws Exception {
        if(!code.matches("[0-9]{6}\\.(SH|SZ|BJ)")||!(side.equals("BUY")||side.equals("SELL"))||!(fraction>0&&fraction<=1))throw new IllegalArgumentException("订单参数错误");
        Map<String,Object> state=state();if(!Boolean.TRUE.equals(state.get("active")))throw new IllegalStateException("请先开始游戏");
        String day=(String)state.get("day"),clock=(String)state.get("clock");
        if(side.equals("BUY")){if(!clock.equals("15:00"))throw new IllegalStateException("收盘价买入仅在当前交易日收盘时可用");buyAtClose(code,day,fraction,(double)state.get("cash"));return;}
        if(clock.equals("15:00"))throw new IllegalStateException("当前已收盘，请推进至下一交易日后卖出");
        JsonNode current=findBar(minute.day(code,LocalDate.parse(day)),day,clock);
        if(current==null)throw new IllegalStateException("当前分钟没有行情，无法提交订单");
        int qty;
        if(side.equals("BUY")){
            double cash=(double)state.get("cash"); double estimated=current.path("c").asDouble();
            int lot=code.startsWith("688")?200:100;
            qty=(int)(Math.max(0,cash*fraction-5)/(estimated*1.00031*lot))*lot;
        }else{
            try(Connection c=db.open();PreparedStatement p=c.prepareStatement("SELECT quantity,last_buy_day FROM positions WHERE code=?")){
                p.setString(1,code);try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalStateException("没有持仓");if(r.getString(2).compareTo(day)>=0)throw new IllegalStateException("T+1 限制：当天买入不可卖出");int held=r.getInt(1);qty=fraction>=1?held:(int)(held*fraction/100)*100;}
            }
        }
        if(qty<=0)throw new IllegalStateException("资金或持仓不足一个交易单位");
        try(Connection c=db.open();PreparedStatement p=c.prepareStatement("INSERT INTO orders(code,side,quantity,status,day,time) VALUES(?,?,?,'PENDING',?,?)")){
            p.setString(1,code);p.setString(2,side);p.setInt(3,qty);p.setString(4,day);p.setString(5,clock);p.executeUpdate();
        }
    }
    private void buyAtClose(String code,String day,double fraction,double cash) throws Exception {
        double price,volume;
        try(Connection c=market.open();PreparedStatement q=c.prepareStatement("SELECT c,v FROM daily WHERE code=? AND day=?")){
            q.setString(1,code);q.setString(2,day);try(ResultSet r=q.executeQuery()){if(!r.next())throw new IllegalStateException("本地无当日有效日线，无法按收盘价买入");price=r.getDouble(1);volume=r.getDouble(2);}
        }
        if(price<=0||volume<=0||!Double.isFinite(price))throw new IllegalStateException("收盘价或成交量无效");
        int lot=code.startsWith("688")?200:100,qty=(int)(Math.max(0,cash*fraction-5)/(price*1.00031*lot))*lot;
        if(qty<=0)throw new IllegalStateException("资金不足一个交易单位");
        try(Connection c=db.open()){
            c.setAutoCommit(false);try{
                try(PreparedStatement p=c.prepareStatement("INSERT INTO orders(code,side,quantity,status,day,time) VALUES(?,'BUY',?,'PENDING',?,'15:00')")){p.setString(1,code);p.setInt(2,qty);p.setString(3,day);p.executeUpdate();}
                int id;try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT last_insert_rowid()")){r.next();id=r.getInt(1);}
                var bar=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();bar.put("o",price);bar.put("v",volume);
                execute(c,Map.of("id",id,"quantity",qty,"code",code,"side","BUY"),bar,day,"15:00",true);
                try(PreparedStatement p=c.prepareStatement("UPDATE orders SET reason='按当日收盘价模拟成交' WHERE id=? AND status='FILLED'")){p.setInt(1,id);p.executeUpdate();}c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    public synchronized void step() throws Exception {
        Map<String,Object> st=state();if(!Boolean.TRUE.equals(st.get("active")))throw new IllegalStateException("请先开始游戏");
        String day=(String)st.get("day"),clock=(String)st.get("clock");String next=nextMinute(clock);
        if(next==null){try(Connection c=market.open();PreparedStatement p=c.prepareStatement("SELECT min(day) FROM calendar WHERE day>?")){p.setString(1,day);try(ResultSet r=p.executeQuery()){r.next();String d=r.getString(1);if(d==null)throw new IllegalStateException("没有下一交易日");day=d;next="09:30";}}}
        List<Map<String,Object>> pending=((List<Map<String,Object>>)st.get("orders")).stream().filter(o->"PENDING".equals(o.get("status"))).sorted(Comparator.comparingInt(o->(int)o.get("id"))).toList();
        Map<Integer,JsonNode> bars=new HashMap<>();
        for(var o:pending){try{bars.put((int)o.get("id"),findBar(minute.day((String)o.get("code"),LocalDate.parse(day)),day,next));}catch(Exception e){bars.put((int)o.get("id"),null);}}
        try(Connection c=db.open()){
            c.setAutoCommit(false);
            try{
                for(var o:pending)execute(c,o,bars.get((int)o.get("id")),day,next);
                try(PreparedStatement p=c.prepareStatement("UPDATE account SET game_day=?,clock=? WHERE id=1")){p.setString(1,day);p.setString(2,next);p.executeUpdate();}c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    /** Jump the replay clock to the close of the current day, keeping pending orders for the next session. */
    public synchronized void skipToClose() throws Exception {
        Map<String,Object> st=state();if(!Boolean.TRUE.equals(st.get("active")))throw new IllegalStateException("请先开始游戏");
        if("15:00".equals(st.get("clock")))return;
        try(Connection c=db.open();PreparedStatement p=c.prepareStatement("UPDATE account SET clock='15:00' WHERE id=1")){p.executeUpdate();}
    }
    /** Skip the remainder of the current replay only after positions and pending orders are cleared. */
    public synchronized void nextSelection() throws Exception {
        Map<String,Object> st=state();
        if(!Boolean.TRUE.equals(st.get("active")))throw new IllegalStateException("请先开始游戏");
        if(!((List<?>)st.get("positions")).isEmpty())throw new IllegalStateException("请先卖出全部持仓，再执行下一次选股");
        for(Map<String,Object> order:(List<Map<String,Object>>)st.get("orders"))if("PENDING".equals(order.get("status")))throw new IllegalStateException("还有未完成的订单，请先推进回放处理订单");
        String day=(String)st.get("day"),selection=(String)st.get("selectionDay");
        if(day.compareTo(selection)<=0)throw new IllegalStateException("请先推进到下一交易日的分时回放");
        try(Connection c=market.open();PreparedStatement q=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM daily WHERE day=?), EXISTS(SELECT 1 FROM calendar WHERE day>?)")){
            q.setString(1,day);q.setString(2,day);try(ResultSet r=q.executeQuery()){r.next();if(r.getInt(1)==0)throw new IllegalStateException("该交易日的日线数据尚未同步");if(r.getInt(2)==0)throw new IllegalStateException("缺少后续交易日，无法继续下一轮");}
        }
        try(Connection c=db.open();PreparedStatement q=c.prepareStatement("UPDATE account SET selection_day=game_day,clock='15:00' WHERE id=1")){q.executeUpdate();}
    }
    /** Skip a selection day: nothing was bought, so jump straight to the next trading day and allow a new screen. */
    public synchronized Map<String,Object> passDay() throws Exception {
        Map<String,Object> st=state();
        if(!Boolean.TRUE.equals(st.get("active")))throw new IllegalStateException("请先开始游戏");
        if(!((List<?>)st.get("positions")).isEmpty())throw new IllegalStateException("当前仍有持仓，请先卖出全部持仓再空仓跳过");
        for(Map<String,Object> order:(List<Map<String,Object>>)st.get("orders"))if("PENDING".equals(order.get("status")))throw new IllegalStateException("还有未完成的订单，请先推进回放处理订单");
        String day=(String)st.get("day"),next=null;
        try(Connection c=market.open();PreparedStatement q=c.prepareStatement("SELECT min(n.day) FROM calendar n WHERE n.day>? AND EXISTS(SELECT 1 FROM daily d WHERE d.day=n.day)")){
            q.setString(1,day);try(ResultSet r=q.executeQuery()){r.next();next=r.getString(1);}
        }
        if(next==null)throw new IllegalStateException("缺少后续交易日，无法空仓跳到下一日");
        try(Connection c=db.open();PreparedStatement q=c.prepareStatement("UPDATE account SET game_day=?,selection_day=?,clock='15:00' WHERE id=1")){q.setString(1,next);q.setString(2,next);q.executeUpdate();}
        Map<String,Object> out=new LinkedHashMap<>(state());out.put("passedDay",day);return out;
    }
    private static String nextMinute(String time){
        LocalTime t=LocalTime.parse(time);if(t.equals(LocalTime.of(11,30)))return "13:01";if(t.equals(LocalTime.of(15,0)))return null;return t.plusMinutes(1).toString();
    }
    private static JsonNode findBar(JsonNode rows,String day,String hm){for(JsonNode r:rows){String t=r.path("t").asText();if(t.startsWith(day+" "+hm))return r;}return null;}
    private static void execute(Connection c,Map<String,Object> order,JsonNode bar,String day,String clock) throws Exception {
        execute(c,order,bar,day,clock,false);
    }
    private static void execute(Connection c,Map<String,Object> order,JsonNode bar,String day,String clock,boolean closePrice) throws Exception {
        int id=(int)order.get("id"),qty=(int)order.get("quantity");String code=(String)order.get("code"),side=(String)order.get("side");
        String reason=null;double price=bar==null?0:bar.path("o").asDouble();
        if(bar==null||price<=0||bar.path("v").asDouble()<=0)reason="下一分钟无有效成交";
        // A flat minute can represent a locked limit; avoid inventing an execution.
        else if(!closePrice&&bar.path("h").asDouble()==bar.path("l").asDouble())reason="该分钟价格锁定，无法确认盘口成交";
        double value=price*qty,fee=Math.max(5,value*0.0003)+value*0.00001+(side.equals("SELL")?value*0.0005:0);
        double cash;try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT cash FROM account WHERE id=1")){r.next();cash=r.getDouble(1);}
        if(reason==null&&side.equals("BUY")&&cash<value+fee)reason="资金不足（包含费用）";
        int held=0;double cost=0;String bought="";try(PreparedStatement p=c.prepareStatement("SELECT quantity,cost,last_buy_day FROM positions WHERE code=?")){p.setString(1,code);try(ResultSet r=p.executeQuery()){if(r.next()){held=r.getInt(1);cost=r.getDouble(2);bought=r.getString(3);}}}
        if(reason==null&&side.equals("SELL")&&(held<qty||bought.compareTo(day)>=0))reason="持仓不足或受T+1限制";
        if(reason==null){
            try(PreparedStatement p=c.prepareStatement("UPDATE account SET cash=? WHERE id=1")){p.setDouble(1,side.equals("BUY")?cash-value-fee:cash+value-fee);p.executeUpdate();}
            if(side.equals("BUY")){try(PreparedStatement p=c.prepareStatement("INSERT INTO positions(code,quantity,cost,last_buy_day) VALUES(?,?,?,?) ON CONFLICT(code) DO UPDATE SET quantity=excluded.quantity,cost=excluded.cost,last_buy_day=excluded.last_buy_day")){p.setString(1,code);p.setInt(2,held+qty);p.setDouble(3,(held*cost+value+fee)/(held+qty));p.setString(4,day);p.executeUpdate();}}
            else if(held==qty){try(PreparedStatement p=c.prepareStatement("DELETE FROM positions WHERE code=?")){p.setString(1,code);p.executeUpdate();}}
            else {try(PreparedStatement p=c.prepareStatement("UPDATE positions SET quantity=? WHERE code=?")){p.setInt(1,held-qty);p.setString(2,code);p.executeUpdate();}}
        }
        try(PreparedStatement p=c.prepareStatement("UPDATE orders SET status=?,day=?,time=?,price=?,fee=?,reason=?,realized=? WHERE id=?")){p.setString(1,reason==null?"FILLED":"REJECTED");p.setString(2,day);p.setString(3,clock);if(reason==null){p.setDouble(4,price);p.setDouble(5,fee);}else{p.setNull(4,Types.REAL);p.setNull(5,Types.REAL);}p.setString(6,reason);if(reason==null&&side.equals("SELL"))p.setDouble(7,value-fee-cost*qty);else p.setNull(7,Types.REAL);p.setInt(8,id);p.executeUpdate();}
    }
    public List<Map<String,Object>> visible(String code) throws Exception {
        return visible(code,null);
    }
    public List<Map<String,Object>> visible(String code,String requestedDay) throws Exception {
        Map<String,Object> st=state();if(!Boolean.TRUE.equals(st.get("active")))throw new IllegalStateException("请先开始游戏");
        String current=(String)st.get("day"),day=requestedDay==null?current:LocalDate.parse(requestedDay).toString();if(day.compareTo(current)>0)throw new IllegalArgumentException("不能查看未来行情");String clock=day.equals(current)?(String)st.get("clock"):"15:00";JsonNode rows=minute.day(code,LocalDate.parse(day));Map<String,Object> latest=state();if(!Boolean.TRUE.equals(latest.get("active"))||day.compareTo((String)latest.get("day"))>0)throw new IllegalStateException("游戏状态已变化，请重新加载");if(day.equals(latest.get("day"))&&clock.compareTo((String)latest.get("clock"))>0)clock=(String)latest.get("clock");List<Map<String,Object>> out=new ArrayList<>();
        double vol=0,amount=0;for(JsonNode r:rows){String t=r.path("t").asText();if(t.length()<16||!t.startsWith(day)||t.substring(11,16).compareTo(clock)>0)continue;double v=r.path("v").asDouble()*100,a=r.path("a").asDouble();vol+=v;amount+=a;out.add(Map.of("time",t.substring(11,16),"price",r.path("c").asDouble(),"open",r.path("o").asDouble(),"high",r.path("h").asDouble(),"low",r.path("l").asDouble(),"volume",v,"totalVolume",vol,"amount",a,"totalAmount",amount,"average",vol>0?amount/vol:0));}return out;
    }
    public synchronized List<Map<String,Object>> daily(String code) throws Exception {
        return daily(code,null);
    }
    public synchronized List<Map<String,Object>> daily(String code,String requestedDay) throws Exception {
        return daily(code,requestedDay,false);
    }
    public synchronized List<Map<String,Object>> daily(String code,String requestedDay,boolean includeClosed) throws Exception {
        if(!code.matches("[0-9]{6}\\.(SH|SZ|BJ)"))throw new IllegalArgumentException("股票代码格式错误");
        Map<String,Object> st=state();if(!Boolean.TRUE.equals(st.get("active")))throw new IllegalStateException("请先开始游戏");
        String current=(String)st.get("day"),day=requestedDay==null?current:LocalDate.parse(requestedDay).toString();if(day.compareTo(current)>0)throw new IllegalArgumentException("不能查看未来行情");
        boolean closed=includeClosed&&(day.compareTo(current)<0||(day.equals(current)&&"15:00".equals(st.get("clock"))));
        try(Connection c=market.open();PreparedStatement p=c.prepareStatement("SELECT day,o,c,v,a,h,l FROM daily WHERE code=? AND day"+(closed?"<=":"<")+"? ORDER BY day DESC LIMIT 90")){
            p.setString(1,code);p.setString(2,day);List<Map<String,Object>> bars=new ArrayList<>();
            try(ResultSet r=p.executeQuery()){while(r.next()){Map<String,Object> b=new LinkedHashMap<>();b.put("day",r.getString(1));b.put("open",r.getDouble(2));b.put("close",r.getDouble(3));b.put("volume",r.getDouble(4));b.put("amount",r.getDouble(5));b.put("high",r.getObject(6));b.put("low",r.getObject(7));bars.add(b);}}Collections.reverse(bars);return bars;
        }
    }
    public Map<String,Object> valuation() throws Exception {
        Map<String,Object> st=state();double value=0,unrealized=0;List<String> missing=new ArrayList<>();
        for(Map<String,Object> p:(List<Map<String,Object>>)st.get("positions")){
            String code=(String)p.get("code");int quantity=(int)p.get("quantity");double cost=(double)p.get("cost");
            try{List<Map<String,Object>> visible=visible(code);if(visible.isEmpty()){missing.add(code);continue;}double price=(double)visible.get(visible.size()-1).get("price");value+=price*quantity;unrealized+=(price-cost)*quantity;}
            catch(Exception e){missing.add(code);}
        }
        Map<String,Object> after=state();if(!Objects.equals(st.get("day"),after.get("day"))||!Objects.equals(st.get("clock"),after.get("clock"))||!Objects.equals(st.get("cash"),after.get("cash"))||!Objects.equals(st.get("positions"),after.get("positions")))throw new IllegalStateException("游戏状态已变化，请重新获取估值");
        return Map.of("cash",st.get("cash"),"marketValue",value,"unrealized",unrealized,"total",(double)st.get("cash")+value,"missing",missing);
    }
    public synchronized void reset(String password) throws Exception {
        if(!"123456".equals(password))throw new IllegalArgumentException("密码错误");
        try(Connection c=db.open()){c.setAutoCommit(false);try(Statement s=c.createStatement()){s.executeUpdate("DELETE FROM orders");s.executeUpdate("DELETE FROM positions");s.executeUpdate("UPDATE account SET cash=1000000,game_day=NULL,selection_day=NULL,clock=NULL,active=0 WHERE id=1");c.commit();}catch(Exception e){c.rollback();throw e;}}
    }
    /** Close the replay, settle every position at its last visible price, and return a settlement summary. */
    public synchronized Map<String,Object> end() throws Exception {
        Map<String,Object> st=state();if(!Boolean.TRUE.equals(st.get("active")))throw new IllegalStateException("游戏尚未开始");
        String day=(String)st.get("day"),clock=(String)st.get("clock");
        List<Map<String,Object>> positions=(List<Map<String,Object>>)st.get("positions");
        Map<String,Double> prices=new HashMap<>();
        for(var p:positions){
            String code=(String)p.get("code");List<Map<String,Object>> visible=visible(code);
            if(visible.isEmpty())throw new IllegalStateException(code+" 当前无价格，不能结算");
            prices.put(code,(double)visible.get(visible.size()-1).get("price"));
        }
        List<Map<String,Object>> settled=new ArrayList<>();double proceeds=0;int cancelled=0;
        for(var o:(List<Map<String,Object>>)st.get("orders"))if("PENDING".equals(o.get("status")))cancelled++;
        try(Connection c=db.open()){
            c.setAutoCommit(false);try{
                for(var p:positions){String code=(String)p.get("code");int qty=(int)p.get("quantity");double price=prices.get(code),value=qty*price,fee=Math.max(5,value*0.0003)+value*0.00051;proceeds+=value-fee;
                    try(PreparedStatement q=c.prepareStatement("INSERT INTO orders(code,side,quantity,status,day,time,price,fee,reason) VALUES(?,'SELL',?,'SETTLEMENT',?,?,?,?,?)")){q.setString(1,code);q.setInt(2,qty);q.setString(3,day);q.setString(4,clock);q.setDouble(5,price);q.setDouble(6,fee);q.setString(7,"结束游戏估值，不代表实际成交");q.executeUpdate();}
                    Map<String,Object> item=new LinkedHashMap<>();item.put("code",code);item.put("name",p.get("name"));item.put("quantity",qty);item.put("price",price);item.put("fee",fee);item.put("amount",value-fee);settled.add(item);}
                try(Statement s=c.createStatement()){s.executeUpdate("UPDATE orders SET status='CANCELLED',reason='游戏结束' WHERE status='PENDING'");s.executeUpdate("DELETE FROM positions");}
                try(PreparedStatement q=c.prepareStatement("UPDATE account SET cash=cash+?,active=0 WHERE id=1")){q.setDouble(1,proceeds);q.executeUpdate();}c.commit();
            }catch(Exception e){c.rollback();throw e;}
        }
        Map<String,Object> out=new LinkedHashMap<>(state());
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("day",day);summary.put("clock",clock);summary.put("proceeds",proceeds);summary.put("cancelled",cancelled);summary.put("items",settled);
        out.put("settlement",summary);
        return out;
    }
}
