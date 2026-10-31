package cn.tidescan.market;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MarketStore {
    private final String url;
    public MarketStore(@Value("${tidescan.root}") String root) throws Exception {
        Path dir = Path.of(root, "data"); Files.createDirectories(dir);
        url = "jdbc:sqlite:" + dir.resolve("market.db");
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS stocks(code TEXT PRIMARY KEY,name TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS calendar(day TEXT PRIMARY KEY)");
            s.execute("CREATE TABLE IF NOT EXISTS daily(code TEXT NOT NULL,day TEXT NOT NULL,o REAL NOT NULL,c REAL NOT NULL,v REAL NOT NULL,a REAL NOT NULL,h REAL,l REAL,PRIMARY KEY(code,day))");
            boolean high=false,low=false;try(ResultSet r=s.executeQuery("PRAGMA table_info(daily)")){while(r.next()){if("h".equals(r.getString("name")))high=true;if("l".equals(r.getString("name")))low=true;}}
            if(!high)s.execute("ALTER TABLE daily ADD COLUMN h REAL");if(!low)s.execute("ALTER TABLE daily ADD COLUMN l REAL");
            s.execute("CREATE INDEX IF NOT EXISTS daily_day ON daily(day,code)");
            s.execute("CREATE TABLE IF NOT EXISTS capital(code TEXT NOT NULL,change_day TEXT NOT NULL,notice_day TEXT NOT NULL,float_shares REAL NOT NULL,PRIMARY KEY(code,change_day,notice_day))");
            s.execute("CREATE TABLE IF NOT EXISTS sync_state(code TEXT PRIMARY KEY,through_day TEXT NOT NULL)");
        }
    }
    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    public Map<String,Object> stats() throws SQLException {
        try (Connection c = open(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT (SELECT count(*) FROM stocks),(SELECT count(*) FROM daily),(SELECT max(day) FROM daily),(SELECT count(*) FROM calendar)")) {
            r.next(); Map<String,Object> m = new LinkedHashMap<>(); m.put("stocks",r.getLong(1)); m.put("bars",r.getLong(2)); m.put("latest",r.getString(3)); m.put("days",r.getLong(4)); return m;
        }
    }
}
