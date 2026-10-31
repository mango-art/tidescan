import json, pathlib, sqlite3, datetime
root = pathlib.Path(__file__).resolve().parent / 'fixture'
(root / 'data').mkdir(parents=True, exist_ok=True)
(root / 'cache' / 'minute').mkdir(parents=True, exist_ok=True)
db=sqlite3.connect(root / 'data' / 'market.db')
db.executescript('''
CREATE TABLE IF NOT EXISTS stocks(code TEXT PRIMARY KEY,name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS calendar(day TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS daily(code TEXT NOT NULL,day TEXT NOT NULL,o REAL NOT NULL,c REAL NOT NULL,v REAL NOT NULL,a REAL NOT NULL,h REAL,l REAL,PRIMARY KEY(code,day));
CREATE TABLE IF NOT EXISTS capital(code TEXT NOT NULL,change_day TEXT NOT NULL,notice_day TEXT NOT NULL,float_shares REAL NOT NULL,PRIMARY KEY(code,change_day,notice_day));
CREATE TABLE IF NOT EXISTS sync_state(code TEXT PRIMARY KEY,through_day TEXT NOT NULL);
DELETE FROM stocks;DELETE FROM calendar;DELETE FROM daily;DELETE FROM capital;DELETE FROM sync_state;
INSERT INTO stocks VALUES('000001.SZ','测试股票');
INSERT INTO calendar VALUES('2025-01-02'),('2025-01-03'),('2025-01-06');
INSERT INTO sync_state VALUES('000001.SZ','2025-01-03');
INSERT INTO capital VALUES('000001.SZ','2024-01-01','2024-01-01',200000000);
INSERT INTO daily VALUES('000001.SZ','2024-12-30',10,10.5,10000000,300000000,10.6,9.9);
INSERT INTO daily VALUES('000001.SZ','2024-12-31',10.6,10.65,5000000,200000000,10.7,10.5);
INSERT INTO daily VALUES('000001.SZ','2025-01-02',10.6,10.8,9000000,300000000,10.9,10.5);
INSERT INTO daily VALUES('000001.SZ','2025-01-03',11,11.2,9000000,300000000,11.3,10.9);
''')
for i in range(80):
    day=(datetime.date(2024,9,1)+datetime.timedelta(days=i)).isoformat()
    db.execute("INSERT OR IGNORE INTO daily VALUES(?,?,?,?,?,?,?,?)",('000001.SZ',day,10,10.1,8000000,200000000,10.2,9.9))
db.execute("INSERT OR REPLACE INTO stocks VALUES('000002.SZ','第二股票')")
db.execute("INSERT OR REPLACE INTO sync_state VALUES('000002.SZ','2025-01-03')")
db.execute("INSERT OR REPLACE INTO capital VALUES('000002.SZ','2024-01-01','2024-01-01',200000000)")
db.execute("INSERT OR REPLACE INTO daily SELECT '000002.SZ',day,o,c,v,a,h,l FROM daily WHERE code='000001.SZ'")
db.commit();db.close()
def bar(day,time,o,c,v,a):return {'t':day+' '+time+':00','o':o,'c':c,'h':max(o,c)+.02,'l':min(o,c)-.02,'v':v,'a':a}
for day,rows in {
 '2025-01-02':[bar('2025-01-02','09:30',10.6,10.7,40000,125000000),bar('2025-01-02','14:49',10.7,10.8,40000,125000000),bar('2025-01-02','14:50',10.8,10.9,500,540000),bar('2025-01-02','15:00',10.9,11,500,550000)],
 '2025-01-03':[bar('2025-01-03','09:30',11,11.1,500,550000),bar('2025-01-03','09:31',11.1,11.2,500,560000)]
}.items():
 (root/'cache'/'minute'/f'000001.SZ-{day}.json').write_text(json.dumps(rows),encoding='utf-8')
 (root/'cache'/'minute'/f'000002.SZ-{day}.json').write_text(json.dumps(rows),encoding='utf-8')
print(root)
