package cn.tidescan.game;

import cn.tidescan.market.MarketStore;
import cn.tidescan.market.MinuteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameServiceTest {
    @TempDir Path root;
    @Test void buyUsesCloseAndSellUsesNextMinuteWithTPlusOne() throws Exception {
        var market=new MarketStore(root.toString());var store=new GameStore(root.toString());
        try(Connection c=market.open();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO calendar(day) VALUES('2025-01-02'),('2025-01-03'),('2025-01-06')");
            s.executeUpdate("INSERT INTO stocks(code,name) VALUES('000001.SZ','测试股票')");
            s.executeUpdate("INSERT INTO daily(code,day,o,c,v,a) VALUES('000001.SZ','2025-01-02',10,10,100,1000),('000001.SZ','2025-01-03',10,10,100,1000)");
        }
        var minute=mock(MinuteService.class);var mapper=new ObjectMapper();
        var day1=mapper.readTree("""
         [{"t":"2025-01-02 14:49:00","o":10,"h":10.1,"l":9.9,"c":10,"v":100,"a":100000},
          {"t":"2025-01-02 14:50:00","o":11,"h":11.1,"l":10.9,"c":11,"v":100,"a":110000},
          {"t":"2025-01-02 14:51:00","o":12,"h":12.1,"l":11.9,"c":12,"v":100,"a":120000}]
        """);
        var day2=mapper.readTree("""
         [{"t":"2025-01-03 09:30:00","o":12,"h":12.1,"l":11.9,"c":12,"v":100,"a":120000},
          {"t":"2025-01-03 09:31:00","o":13,"h":13.1,"l":12.9,"c":13,"v":100,"a":130000}]
        """);
        when(minute.day(eq("000001.SZ"),eq(LocalDate.parse("2025-01-02")))).thenReturn(day1);
        when(minute.day(eq("000001.SZ"),eq(LocalDate.parse("2025-01-03")))).thenReturn(day2);
        var g=new GameService(store,market,minute);g.start(LocalDate.parse("2025-01-02"));
        assertEquals("2025-01-02",g.daily("000001.SZ","2025-01-02",true).getLast().get("day"));
        g.order("000001.SZ","BUY",0.25);assertTrue((double)g.state().get("cash")<1_000_000);
        var positions=(java.util.List<java.util.Map<String,Object>>)g.state().get("positions");assertEquals("测试股票",positions.get(0).get("name"));
        verifyNoInteractions(minute);
        var orders=(java.util.List<java.util.Map<String,Object>>)g.state().get("orders");assertEquals(10.0,orders.get(0).get("price"));assertEquals("FILLED",orders.get(0).get("status"));
        assertEquals(3,g.visible("000001.SZ").size());
        assertThrows(IllegalArgumentException.class,()->g.visible("000001.SZ","2025-01-03"));
        assertThrows(IllegalStateException.class,()->g.order("000001.SZ","SELL",1));
        g.step();assertEquals("2025-01-03",g.state().get("day"));
        assertEquals("2025-01-02",g.daily("000001.SZ","2025-01-03",true).getLast().get("day"));
        assertEquals(120000.0,g.visible("000001.SZ").getLast().get("totalAmount"));
        assertThrows(IllegalStateException.class,g::nextSelection);
        assertEquals("2025-01-02",g.state().get("selectionDay"));
        assertEquals(3,g.visible("000001.SZ","2025-01-02").size());
        assertThrows(IllegalArgumentException.class,()->g.daily("000001.SZ","2025-01-04"));
        g.order("000001.SZ","SELL",1);assertThrows(IllegalStateException.class,g::nextSelection);g.step();assertTrue(((java.util.List<?>)g.state().get("positions")).isEmpty());
        assertTrue((double)g.state().get("cash")>1_000_000);
        double cash=(double)g.state().get("cash");g.nextSelection();assertEquals("2025-01-03",g.state().get("selectionDay"));assertEquals("15:00",g.state().get("clock"));assertEquals(cash,g.state().get("cash"));assertEquals(2,((java.util.List<?>)g.state().get("orders")).size());assertThrows(IllegalStateException.class,g::nextSelection);
        assertThrows(IllegalArgumentException.class,()->g.reset("wrong"));
        g.reset("123456");assertEquals(1_000_000.0,g.state().get("cash"));
    }
    @Test void skipToCloseJumpsToCloseWithoutCrossingDay() throws Exception {
        var market=new MarketStore(root.toString());var store=new GameStore(root.toString());
        try(Connection c=market.open();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO calendar(day) VALUES('2025-01-02'),('2025-01-03')");
            s.executeUpdate("INSERT INTO stocks(code,name) VALUES('000001.SZ','测试股票')");
            s.executeUpdate("INSERT INTO daily(code,day,o,c,v,a) VALUES('000001.SZ','2025-01-02',10,10,100,1000),('000001.SZ','2025-01-03',10,10,100,1000)");
        }
        var g=new GameService(store,market,mock(MinuteService.class));
        g.start(LocalDate.parse("2025-01-02"));
        assertEquals("15:00",g.state().get("clock"));
        g.step();assertEquals("2025-01-03",g.state().get("day"));assertEquals("09:30",g.state().get("clock"));
        g.skipToClose();assertEquals("15:00",g.state().get("clock"));assertEquals("2025-01-03",g.state().get("day"));
        g.skipToClose();assertEquals("15:00",g.state().get("clock"));
        assertThrows(IllegalStateException.class,g::step);
    }
}
