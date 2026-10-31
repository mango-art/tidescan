import java.sql.*;
public class InspectParquet {
  public static void main(String[] args) throws Exception {
    Class.forName("org.duckdb.DuckDBDriver");
    try(Connection c=DriverManager.getConnection("jdbc:duckdb:");Statement s=c.createStatement()) {
      for(String year:new String[]{"2025","2026"}) {
        String f="F:/tidescan/cat/"+year+".parquet";
        System.out.println("FILE "+year);
        try(ResultSet r=s.executeQuery("DESCRIBE SELECT * FROM read_parquet('"+f+"')")){while(r.next())System.out.println(r.getString(1)+" "+r.getString(2));}
        try(ResultSet r=s.executeQuery("SELECT * FROM read_parquet('"+f+"') LIMIT 2")){var md=r.getMetaData();while(r.next()){for(int i=1;i<=md.getColumnCount();i++)System.out.print(md.getColumnName(i)+"="+r.getString(i)+" ");System.out.println();}}
        try(ResultSet r=s.executeQuery("SELECT count(*) FROM read_parquet('"+f+"')")){r.next();System.out.println("ROWS "+r.getLong(1));}
        try(ResultSet r=s.executeQuery("SELECT min(dt),max(dt),count(distinct code),count(distinct dt),count(*)-count(distinct (code,dt)),min(open),max(close),sum(CASE WHEN volume < 0 OR amount < 0 OR low > high THEN 1 ELSE 0 END) FROM read_parquet('"+f+"')")){r.next();System.out.println("RANGE "+r.getString(1)+".."+r.getString(2)+" STOCKS "+r.getString(3)+" DAYS "+r.getString(4)+" DUPLICATE "+r.getString(5)+" MINOPEN "+r.getString(6)+" MAXCLOSE "+r.getString(7)+" INVALID "+r.getString(8));}
      }
    }
  }
}
