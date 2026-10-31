package cn.tidescan.game;

import java.nio.file.*;
import java.sql.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GameStore {
    private final String url;
    public GameStore(@Value("${tidescan.root}") String root) throws Exception {
        Path dir=Path.of(root,"data");Files.createDirectories(dir);url="jdbc:sqlite:"+dir.resolve("game.db");
        try(Connection c=open();Statement s=c.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS account(id INTEGER PRIMARY KEY CHECK(id=1), cash REAL NOT NULL, game_day TEXT, clock TEXT, active INTEGER NOT NULL DEFAULT 0)");
            boolean selectionDay=false;try(ResultSet r=s.executeQuery("PRAGMA table_info(account)")){while(r.next())if("selection_day".equals(r.getString("name")))selectionDay=true;}if(!selectionDay)s.execute("ALTER TABLE account ADD COLUMN selection_day TEXT");
            s.execute("INSERT OR IGNORE INTO account(id,cash,active) VALUES(1,1000000,0)");
            s.execute("CREATE TABLE IF NOT EXISTS positions(code TEXT PRIMARY KEY, quantity INTEGER NOT NULL, cost REAL NOT NULL, last_buy_day TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS orders(id INTEGER PRIMARY KEY AUTOINCREMENT,code TEXT NOT NULL,side TEXT NOT NULL,quantity INTEGER NOT NULL,status TEXT NOT NULL,day TEXT NOT NULL,time TEXT NOT NULL,price REAL,fee REAL,reason TEXT,realized REAL)");
            boolean realized=false;try(ResultSet r=s.executeQuery("PRAGMA table_info(orders)")){while(r.next())if("realized".equals(r.getString("name")))realized=true;}if(!realized)s.execute("ALTER TABLE orders ADD COLUMN realized REAL");
        }
    }
    public Connection open() throws SQLException {Connection c=DriverManager.getConnection(url);try(Statement s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");}return c;}
}
