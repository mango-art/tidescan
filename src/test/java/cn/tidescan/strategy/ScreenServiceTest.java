package cn.tidescan.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.tidescan.strategy.Strategy.*;
import cn.tidescan.market.MarketStore;
import cn.tidescan.market.MinuteService;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.Connection;
import static org.mockito.Mockito.*;

class ScreenServiceTest {
    @TempDir Path root;
    @Test void screensAtCloseUsingOnlyLocalDailyBars() throws Exception {
        var store=new MarketStore(root.toString());
        try(Connection c=store.open();var s=c.createStatement()){
            s.execute("INSERT INTO calendar VALUES('2025-01-02'),('2025-01-03')");
            s.execute("INSERT INTO stocks VALUES('000001.SZ','示例')");
            s.execute("INSERT INTO sync_state VALUES('000001.SZ','2025-01-03')");
            s.execute("INSERT INTO capital VALUES('000001.SZ','2024-01-01','2024-01-01',200000000)");
            s.execute("INSERT INTO daily(code,day,o,c,v,a) VALUES('000001.SZ','2024-12-30',10,10.5,10000000,300000000),('000001.SZ','2024-12-31',10.6,10.65,5000000,200000000),('000001.SZ','2025-01-02',10.6,10.8,8000000,250000000)");
        }
        var minute=mock(MinuteService.class);var screen=new ScreenService(store,minute,root.toString());screen.start(LocalDate.parse("2025-01-02"),Parameters.defaults());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while((boolean)screen.status().get("running")&&System.nanoTime()<deadline)Thread.sleep(10);
        var status=screen.status();assertFalse((boolean)status.get("running"));assertEquals("选股完成",status.get("message"));assertEquals(1,((java.util.List<?>)status.get("matches")).size());assertEquals(1,((java.util.List<?>)new ScreenService(store,minute,root.toString()).status().get("matches")).size());verifyNoInteractions(minute);
    }
    @Test void excludesTheDecisionMinuteAndFuturePrices() throws Exception {
        var rows=new ObjectMapper().readTree("""
          [{"t":"2025-01-02 09:30:00","o":10,"c":10.1,"v":20,"a":20000},
           {"t":"2025-01-02 14:49:00","o":10.5,"c":10.6,"v":30,"a":30000},
           {"t":"2025-01-02 14:50:00","o":99,"c":99,"v":99999,"a":999999}]
          """);
        var bar=ScreenService.aggregate(rows,LocalDate.parse("2025-01-02"));
        assertEquals(10,bar.open());assertEquals(10.6,bar.close());
        assertEquals(5000,bar.volume());assertEquals(50000,bar.amount());
    }
    @Test void rejectsIncompleteOrUnorderedMinutes() throws Exception {
        var rows=new ObjectMapper().readTree("[{\"t\":\"2025-01-02 09:30:00\",\"o\":10,\"c\":10,\"v\":1,\"a\":1000}]");
        assertThrows(IllegalStateException.class,()->ScreenService.aggregate(rows,LocalDate.parse("2025-01-02")));
    }
    @Test void previousDayPrefilterIncludesAnActualSignal(){
        var p=Parameters.defaults();var prior=new Bar(10,10.5,10_000_000,300_000_000);var y=new Bar(10.6,10.65,5_000_000,200_000_000);var today=new Bar(10.6,10.8,8_000_000,250_000_000);
        assertTrue(Strategy.matches(prior,y,today,200_000_000,p));
        assertTrue(ScreenService.precondition(prior,y,p));
    }
}
