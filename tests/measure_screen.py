import sqlite3,time
c=sqlite3.connect('file:F:/tidescan/data/market.db?mode=ro',uri=True,timeout=5)
q="""SELECT s.code,s.name,d.day,d.o,d.c,d.v,d.a, (SELECT float_shares FROM capital f WHERE f.code=s.code AND f.change_day<=? AND f.notice_day<=? ORDER BY f.change_day DESC,f.notice_day DESC LIMIT 1) AS shares FROM stocks s JOIN daily d ON d.code=s.code WHERE d.day<? AND d.day >= date(?,'-60 days') ORDER BY s.code,d.day DESC"""
p=('2025-10-09',)*4
t=time.monotonic();n=0;candidate=0;missing=0;last='';y=None;shares=0
for code,name,day,o,close,v,a,floatshares in c.execute(q,p):
    n+=1
    if code!=last: last=code;y=(o,close,v);shares=floatshares or 0;continue
    if y is None:continue
    yo,yc,yv=y; red=yc>=yo and abs(yc-yo)/yo<=.015; green=yc<yo and abs(yc-yo)/yo<=.01
    if (red or green) and close>o and (close-o)/o*100>=1 and yo>o and yc>o and yv<v:
        if shares>0:candidate+=1
        else:missing+=1
    y=None
print('prior rows:',n,'candidates:',candidate,'missing_capital:',missing,'elapsed_seconds:',round(time.monotonic()-t,2))
c.close()
