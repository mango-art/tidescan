package cn.tidescan.market;

import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarketStoreTest {
    @TempDir Path root;
    @Test void createsPersistentIndexedSqliteDatabase() throws Exception {
        MarketStore store=new MarketStore(root.toString());
        try(Connection c=store.open();var s=c.createStatement()){
            s.executeUpdate("INSERT INTO stocks(code,name) VALUES('000001.SZ','平安银行')");
            s.executeUpdate("INSERT INTO daily(code,day,o,c,v,a) VALUES('000001.SZ','2025-01-02',10,11,100,1000)");
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("INSERT INTO daily(code,day,o,c,v,a) VALUES('000001.SZ','2025-01-02',10,11,100,1000)"));
        }
        assertEquals(1L,store.stats().get("stocks"));
        assertEquals("2025-01-02",store.stats().get("latest"));
        assertEquals(1L,new MarketStore(root.toString()).stats().get("bars"));
    }
}
