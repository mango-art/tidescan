import sqlite3
db=sqlite3.connect('file:F:/tidescan/data/market.db?mode=ro',uri=True)
for row in db.execute("select code,day,o,c,h,l,v,a from daily where code in ('000001.SZ','000002.SZ') and day in ('2025-01-02','2026-01-05') order by code,day"):
    print(row)
db.close()
