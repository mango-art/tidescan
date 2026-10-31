'use strict';
const $=s=>document.querySelector(s);let game={},selected='',playing=false,busy=false,timer=null,viewMode='selection';
const params=[['red','红K %',1.5],['green','绿K %',1],['priorBody','前天实体 %',1],['todayBody','当天实体 %',1],['gap','高开 %',5],['volumeRatio','量比',2],['turnover','换手 %',3],['amount','成交额 ≥',250000000]];
$('#params').innerHTML=params.map(([k,l,v])=>`<label>${l}<input id="p-${k}" type="number" min="0" step="any" value="${v}"></label>`).join('');
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const money=n=>Number(n).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
const amountLabel=n=>n>=1e8?(n/1e8).toFixed(2)+' 亿元':n>=1e4?(n/1e4).toFixed(2)+' 万元':money(n)+' 元';
let toastTimer;function toast(s){$('#toast').textContent=s;$('#toast').style.display='block';clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').style.display='none',4500)}
function setMetric(id,value){let el=$('#'+id);if(el.textContent===value)return;el.textContent=value;if(playing&&el.animate&&!matchMedia('(prefers-reduced-motion: reduce)').matches)el.animate([{opacity:.78},{opacity:1}],{duration:Math.min(160,Number($('#speed').value)*.7),easing:'ease-out'})}
let valuationRequest=0,lastValuationKey='',lastValuationAt=0;
function refreshAccountSummary(){
 let seq=++valuationRequest,cash=Number(game.cash)||0,positions=game.positions||[],key=cash+'|'+positions.map(p=>p.code+':'+p.quantity+':'+p.cost).join('|');$('#live-cash').textContent='¥'+money(cash);$('#live-holdings').textContent=positions.length?positions.map(p=>(p.name||p.code)+' '+p.quantity+'股').join(' / '):'暂无持仓';$('#live-holdings').title=positions.map(p=>(p.name||p.code)+' '+p.code+' '+p.quantity+'股').join(' / ');$('#live-valuation-note').textContent=key===lastValuationKey&&Date.now()-lastValuationAt>1500?'估值更新中，显示上次结果':'';
 if(!positions.length){lastValuationKey='';$('#live-total').textContent='¥'+money(cash);$('#live-pnl').textContent='¥0.00';return}
 if(key!==lastValuationKey){$('#live-total').textContent='估值中…';$('#live-pnl').textContent='—'}
 api('/api/game/valuation').then(v=>{if(seq!==valuationRequest)return;if(v.missing.length){lastValuationKey='';$('#live-total').textContent='估值不完整';$('#live-pnl').textContent='—';$('#live-valuation-note').textContent='缺少行情：'+v.missing.join('、');return}lastValuationKey=key;lastValuationAt=Date.now();$('#live-valuation-note').textContent='';setMetric('live-total','¥'+money(v.total));setMetric('live-pnl',(v.unrealized>0?'+':'')+'¥'+money(v.unrealized));$('#live-pnl').className=v.unrealized>0?'positive':v.unrealized<0?'negative':''}).catch(()=>{if(seq!==valuationRequest)return;lastValuationKey='';$('#live-total').textContent='估值失败';$('#live-pnl').textContent='—';$('#live-valuation-note').textContent='行情读取失败，请稍后重试'});
}
async function api(path,body){let res=await fetch(path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});let result=await res.json();if(!res.ok)throw Error(result.message||'请求失败');return result}
async function action(fn){try{await fn()}catch(e){stop();toast(e.message)}}
let playbackRun=0,playbackDay=null;
function stop(){playing=false;playbackRun++;playbackDay=null;clearTimeout(timer);$('#play').textContent='播放'}
let positionsMarkup='',ordersMarkup='';
async function refreshGame(snapshot){game=snapshot||await api('/api/game');$('#cash').textContent='¥'+money(game.cash);
 let positionHtml=`<div class="position">已实现盈亏 ¥${money(game.realized)} · 已卖出 ${game.closedTrades} 笔 · 胜率 ${game.closedTrades?(game.wins/game.closedTrades*100).toFixed(1)+'%':'—'}</div>`+(game.positions.length?game.positions.map(p=>`<div class="position"><button class="secondary" data-code="${esc(p.code)}">查看</button><strong>${esc(p.name||p.code)} · ${esc(p.code)} · ${p.quantity} 股</strong>持仓成本 ¥${money(p.cost)} · 买入日 ${esc(p.lastBuyDay)}</div>`).join(''):'<p>暂无持仓</p>');
 if(positionHtml!==positionsMarkup){positionsMarkup=positionHtml;$('#positions').innerHTML=positionHtml;$('#positions').querySelectorAll('button').forEach(b=>b.onclick=()=>choose(b.dataset.code))}
 const names={BUY:'买入',SELL:'卖出',FILLED:'已成交',PENDING:'等待成交',REJECTED:'未成交',SETTLEMENT:'结束估值',CANCELLED:'已取消'};
 let orderHtml=game.orders.length?game.orders.slice(0,20).map(o=>`<div class="order"><strong>${esc(o.code)} · ${names[o.side]||o.side} ${o.quantity} 股</strong>${esc(o.day)} ${esc(o.time)} · ${names[o.status]||esc(o.status)} ${o.price!=null?'· ¥'+money(o.price):''}${o.realized!=null?' · 盈亏 ¥'+money(o.realized):''}<br>${esc(o.reason||'')}</div>`).join(''):'暂无交易';if(orderHtml!==ordersMarkup){ordersMarkup=orderHtml;$('#orders').innerHTML=orderHtml}refreshAccountSummary();renderTradeCalendar();
}
const WEEKDAYS=['周日','周一','周二','周三','周四','周五','周六'];
function renderTradeCalendar(){
 const el=$('#trade-cal'),day=game.day||'',d=day?new Date(day+'T00:00:00Z'):null;
 const sel=game.selectionDay&&game.day&&game.selectionDay!==game.day;
 el.title=day?(sel?`当前交易日 ${day} · 选股日 ${game.selectionDay}`:`当前交易日 ${day}`):'模拟交易日历，尚未开始';
 if(!d||isNaN(d.getTime())){
  el.classList.add('idle');el.classList.remove('live');
  $('#cal-month').textContent='—';$('#cal-day').textContent='--';$('#cal-week').textContent='未开始';$('#cal-clock').textContent='待选日';$('#cal-phase').textContent='';return;
 }
 el.classList.remove('idle');
 const live=!!game.active&&!!game.clock&&game.clock<'15:00';el.classList.toggle('live',live);
 $('#cal-month').textContent=(d.getUTCMonth()+1)+'月';
 $('#cal-day').textContent=String(d.getUTCDate()).padStart(2,'0');
 $('#cal-week').textContent=WEEKDAYS[d.getUTCDay()];
 $('#cal-clock').textContent=game.active&&game.clock?game.clock:'—';
 $('#cal-phase').textContent=live?'交易中':game.active?'收盘':'已结束';
}
async function refreshSync(){let d=await api('/api/market/status');$('#sync-head').textContent=d.sync.running?'行情同步中':'行情数据 · '+(d.storage.latest||'未就绪');$('#sync-status').textContent=`${d.sync.message} · ${d.sync.complete}/${d.sync.total}，失败 ${d.sync.failed}；日线 ${d.storage.bars} 条`;$('#sync').disabled=d.sync.running}
let screenMatches=[];
async function refreshScreen(){let s=await api('/api/screen');let stale=!!(s.day&&game.selectionDay&&s.day!==game.selectionDay);$('#screen-status').textContent=stale?`选股结果属于 ${s.day}，当前选股日 ${game.selectionDay}，请重新执行选股`:`${s.message} · 已处理 ${s.done}/${s.total} · 分钟异常 ${s.failed} · 缺股本 ${s.missingCapital} · 日线未覆盖 ${s.unsynced}`;$('#screen').disabled=s.running;screenMatches=stale?[]:s.matches;renderMatches()}
function renderMatches(){let query=$('#search').value.trim().toLowerCase(),items=screenMatches.filter(m=>(m.code+' '+m.name).toLowerCase().includes(query));$('#result-count').textContent=`${items.length} 只`;$('#matches').innerHTML=items.length?items.map((m,i)=>`<tr class="stock-row ${m.code===selected?'active':''}" data-code="${esc(m.code)}" tabindex="0"><td>${i+1}</td><td>${esc(m.code)}</td><td>${esc(m.name)}</td><td>${money(m.price)}</td><td>${Number(m.turnover).toFixed(2)}%</td><td>${(m.amount/1e8).toFixed(2)} 亿</td></tr>`).join(''):'<tr><td colspan="6" class="empty">暂无选股结果</td></tr>';$('#matches').querySelectorAll('.stock-row').forEach(row=>{row.onclick=()=>choose(row.dataset.code);row.onkeydown=e=>{if(e.key==='Enter')choose(row.dataset.code)}})}
$('#search').oninput=renderMatches;
async function choose(code){if(selected!==code){chartOffset=0;chartCount=60}selected=code;$('#symbol').value=code;renderMatches();await action(refreshChart)}
function xFor(hm){let [h,m]=hm.split(':').map(Number),n=h*60+m;return 50+(n<=690?n-570:n-780+120)/240*730}
function minuteBase(rows,prev,min,max,bottom,gradient,animateLast=false,duration=120){
 let y=v=>bottom-(v-min)/(max-min)*(bottom-25),point=(items,k)=>items.filter(r=>r[k]>0).map(r=>`${xFor(r.time).toFixed(1)},${y(r[k]).toFixed(1)}`).join(' '),price=rows.filter(r=>r.price>0),first=price[0],last=price.at(-1),prior=price.at(-2);let grid=[0,1,2,3,4].map(i=>{let yy=25+i*(bottom-25)/4,val=max-i*(max-min)/4;return `<line x1="50" x2="780" y1="${yy}" y2="${yy}" stroke="#edf2f8"/><text x="5" y="${yy+4}" font-size="10" fill="#8fa0b5">${val.toFixed(2)}</text>`}).join('');let area=first?`<polygon points="${xFor(first.time)},${bottom} ${point(price,'price')} ${xFor(last.time)},${bottom}" fill="url(#${gradient})"/>`:'';let smooth=animateLast&&prior&&last&&xFor(last.time)>xFor(prior.time),visible=smooth?price.slice(0,-1):price,segment='';if(smooth){let x1=xFor(prior.time),x2=xFor(last.time),y1=y(prior.price),y2=y(last.price),length=Math.hypot(x2-x1,y2-y1);segment=`<line class="minute-new-segment" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="#f05b6a" stroke-width="1.8" style="--segment-length:${length};animation-duration:${duration}ms"/>`}
 return `<defs><linearGradient id="${gradient}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f25b6e" stop-opacity=".14"/><stop offset="1" stop-color="#f25b6e" stop-opacity="0"/></linearGradient></defs>${grid}${area}${prev?`<path d="M50 ${y(prev)}H780" stroke="#a9bfda" stroke-dasharray="4 4"/>`:''}<polyline class="average-line" points="${point(rows,'average')}" fill="none" stroke="#428bff" stroke-width="1.6"/><polyline class="price-line" points="${point(visible,'price')}" fill="none" stroke="#f05b6a" stroke-width="1.8"/>${segment}`;
}
let chartCandles=[],displayedCandles=[],chartOffset=0,chartCount=60,chartRequest=0,dailyBounds=null,minuteHover=null,chartKey='';
const dailyCache=new Map();
function cachedDaily(code,day,suffix){
 // A replay-day closed candle must never be reused while the day is still in progress.
 const closed=day<game.day||(day===game.day&&game.clock==='15:00'),key=code+'|'+day+'|'+closed,now=Date.now();
 let cached=dailyCache.get(key);if(cached&&now-cached.time<30000){dailyCache.delete(key);dailyCache.set(key,cached);return cached.promise}
 const promise=api('/api/game/daily'+suffix+'&includeClosed=true').catch(error=>{if(dailyCache.get(key)?.promise===promise)dailyCache.delete(key);throw error});dailyCache.set(key,{time:now,promise});if(dailyCache.size>80)dailyCache.delete(dailyCache.keys().next().value);return promise;
}
function renderDaily(){
 chartOffset=Math.max(0,Math.min(chartOffset,Math.max(0,chartCandles.length-chartCount)));let end=chartCandles.length-chartOffset,candles=chartCandles.slice(Math.max(0,end-chartCount),end);displayedCandles=candles;$('#daily').textContent=candles.length?`${candles[0].day} 至 ${candles.at(-1).day}`:'暂无历史日线';
 if(!candles.length){$('#daily-chart').innerHTML='<text x="400" y="130" text-anchor="middle" fill="#8b9aab">无完整日K数据</text>';$('#daily-volume').innerHTML='';return}
 let visibleDates=new Set(candles.map(d=>d.day)),trades=(game.orders||[]).filter(o=>o.code===selected&&visibleDates.has(o.day)&&o.status==='FILLED'&&(o.side==='BUY'||o.side==='SELL')&&Number.isFinite(Number(o.price))&&Number(o.price)>0),values=candles.flatMap(d=>[d.low,d.high]).concat(trades.map(o=>Number(o.price)));let lo=Math.min(...values),hi=Math.max(...values);if(hi===lo)hi=lo+.01;dailyBounds={lo,hi};let y=v=>225-(v-lo)/(hi-lo)*200,slot=730/candles.length;
 let grid=[25,75,125,175,225].map(n=>`<line x1="50" x2="780" y1="${n}" y2="${n}" stroke="#edf2f8"/><text x="3" y="${n+4}" fill="#9aabba" font-size="10">${(hi-(n-25)/200*(hi-lo)).toFixed(2)}</text>`).join('');
 $('#daily-chart').innerHTML=grid+candles.map((d,i)=>{let x=50+i*slot+slot/2,col=d.close>=d.open?'#e4626c':'#31b796',top=y(Math.max(d.open,d.close)),height=Math.max(1,Math.abs(y(d.open)-y(d.close))),orders=trades.filter(o=>o.day===d.day);let markers=orders.map((o,j)=>{let dx=(j-(orders.length-1)/2)*Math.min(8,slot/Math.max(orders.length,1)),mx=x+dx,py=y(Number(o.price)),kind=o.side==='BUY'?'buy':'sell',label=o.side==='BUY'?'B':'S',color=o.side==='BUY'?'#e9515b':'#169a78';return `<g class="${kind}-marker" data-day="${esc(d.day)}" data-price="${o.price}"><title>${label==='B'?'买入':'卖出'} ${o.quantity} 股 ¥${money(o.price)} ${esc(o.time)}</title><line x1="${mx}" x2="${mx}" y1="${py}" y2="237" stroke="#454f5a" stroke-dasharray="2 2"/><circle class="execution-point" data-price="${o.price}" cx="${mx}" cy="${py}" r="3" fill="#111"/><rect x="${mx-6}" y="238" width="12" height="14" rx="2" fill="${color}"/><text x="${mx}" y="249" text-anchor="middle" fill="white" font-size="10" font-weight="bold">${label}</text></g>`}).join('');return `<g class="candle" data-day="${esc(d.day)}"><title>${esc(d.day||'')} 开 ${d.open} 收 ${d.close} 高 ${d.high} 低 ${d.low} 量 ${Math.round(d.volume||0)}（双击查看分时）</title><rect x="${x-slot/2}" y="5" width="${slot}" height="225" fill="transparent"/><line x1="${x}" x2="${x}" y1="${y(d.high)}" y2="${y(d.low)}" stroke="${col}"/><rect x="${x-slot*.3}" y="${top}" width="${slot*.6}" height="${height}" fill="${col}"/>${markers}</g>`}).join('');
 let vmax=Math.max(...candles.map(d=>d.volume||0),1);$('#daily-volume').innerHTML=candles.map((d,i)=>{let h=(d.volume||0)/vmax*55,x=50+i*slot+slot*.2,col=d.close>=d.open?'#e88a92':'#72cfb8';return `<rect x="${x}" y="${60-h}" width="${slot*.6}" height="${h}" fill="${col}"/>`}).join('');
}
$('#daily-chart').addEventListener('dblclick',e=>{let box=$('#daily-chart').getBoundingClientRect(),x=(e.clientX-box.left)/box.width*800,index=Math.floor((x-50)/730*displayedCandles.length);if(index>=0&&index<displayedCandles.length)action(()=>showHistoricalMinute(displayedCandles[index].day))});
$('#daily-chart').addEventListener('wheel',e=>{e.preventDefault();chartCount=Math.max(10,Math.min(90,chartCount+(e.deltaY>0?8:-8)));renderDaily()},{passive:false});
let dragX=null;$('#daily-chart').addEventListener('pointerdown',e=>{dragX=e.clientX;$('#daily-chart').setPointerCapture(e.pointerId)});$('#daily-chart').addEventListener('pointermove',e=>{if(dragX===null)return;let delta=e.clientX-dragX,step=Math.max(3,$('#daily-chart').clientWidth/Math.max(chartCount,1));if(Math.abs(delta)>=step){let shift=Math.trunc(delta/step);chartOffset+=shift;dragX+=shift*step;renderDaily()}});$('#daily-chart').addEventListener('pointerup',()=>dragX=null);$('#daily-chart').addEventListener('pointercancel',()=>dragX=null);
let dialogHover=null;
async function showHistoricalMinute(day){
 const dialog=$('#minute-dialog');$('#dialog-title').textContent=selected+' · '+day+' 分时';$('#dialog-status').textContent='正在加载…';$('#dialog-chart').innerHTML='';$('#dialog-volume').innerHTML='';if(!dialog.open)dialog.showModal();
 try{let suffix='?code='+encodeURIComponent(selected)+'&day='+encodeURIComponent(day),[rows,daily]=await Promise.all([api('/api/game/minutes'+suffix),api('/api/game/daily'+suffix)]);if(!rows.length){dialogHover=null;$('#dialog-status').textContent='没有可见的分钟行情';return}let prev=daily.at(-1)?.close,vals=rows.flatMap(r=>[r.price,r.average]).filter(v=>v>0);if(prev)vals.push(prev);let lo=Math.min(...vals),hi=Math.max(...vals);if(lo===hi){lo-=.01;hi+=.01}let pad=(hi-lo)*.1;lo-=pad;hi+=pad;dialogHover={rows,prev,min:lo,max:hi,day};$('#dialog-chart').innerHTML=minuteBase(rows,prev,lo,hi,235,'dialog-fill')+'<text x="50" y="255" fill="#97a8ba" font-size="11">09:30</text><text x="380" y="255" fill="#97a8ba" font-size="11">11:30 / 13:00</text><text x="750" y="255" fill="#97a8ba" font-size="11">15:00</text>';let maxVol=Math.max(...rows.map(r=>r.volume),1);$('#dialog-volume').innerHTML=rows.map(r=>{let h=r.volume/maxVol*60;return `<rect x="${xFor(r.time)-1}" y="${65-h}" width="2" height="${h}" fill="${r.price>=r.open?'#e88a92':'#72cfb8'}"/>`}).join('');let last=rows.at(-1);$('#dialog-status').textContent=`当前价 ${money(last.price)} · 昨收 ${prev?money(prev):'—'} · 累计成交额 ${amountLabel(last.totalAmount)}`;}catch(e){dialogHover=null;$('#dialog-status').textContent='无法加载分时：'+e.message}
}
$('#dialog-close').onclick=()=>$('#minute-dialog').close();$('#minute-dialog').addEventListener('click',e=>{if(e.target===$('#minute-dialog'))$('#minute-dialog').close()});
async function refreshChart(){if(!selected||!game.active)return;let request=++chartRequest,code=selected,day=viewMode==='selection'?game.selectionDay:game.day,suffix='?code='+encodeURIComponent(code)+'&day='+encodeURIComponent(day),key=code+'|'+day,changed=chartKey!==key;let name=screenMatches.find(m=>m.code===code)?.name||'';$('#chart-title').textContent=(name?name+'  ':'')+code;$('#minute-day').textContent=(viewMode==='selection'?'选股日 ':'回放日 ')+day;$('#selection-view').classList.toggle('view-active',viewMode==='selection');$('#replay-view').classList.toggle('view-active',viewMode==='replay');
 if(changed){chartKey=key;chartCandles=[];displayedCandles=[];dailyBounds=null;minuteHover=null;dropHover();$('#daily-chart').innerHTML='<text x="400" y="130" text-anchor="middle" fill="#8b9aab">正在读取日 K…</text>';$('#daily-volume').innerHTML='';$('#chart').innerHTML='<text x="400" y="90" text-anchor="middle" fill="#8b9aab">正在加载分时行情…</text>';$('#volume-chart').innerHTML='';for(let id of ['price','prev','change','volume'])$('#'+id).textContent='—';}
 let dailyPromise=cachedDaily(code,day,suffix);let minutePromise=new Promise(resolve=>setTimeout(resolve,changed?150:0)).then(()=>request===chartRequest?api('/api/game/minutes'+suffix):null).then(rows=>({rows}),error=>({error}));let daily=await dailyPromise;if(request!==chartRequest||code!==selected)return;let prev=daily.filter(d=>d.day<day).at(-1)?.close;$('#prev').textContent=prev?'¥'+money(prev):'—';let baseCandles=daily.filter(d=>d.high>0&&d.low>0);if(changed||!chartCandles.length){chartCandles=baseCandles;renderDaily()}let minuteResult=await minutePromise;if(request!==chartRequest||code!==selected)return;if(minuteResult.error){minuteHover=null;$('#chart').innerHTML='<text x="400" y="90" text-anchor="middle" fill="#b87a48">分时行情暂不可用，请稍后重试</text>';$('#volume-chart').innerHTML='';return}let rows=minuteResult.rows||[],last=rows.at(-1);$('#price').textContent=last?'¥'+money(last.price):'—';$('#change').textContent=last&&prev?((last.price/prev-1)*100).toFixed(2)+'%':'—';$('#volume').textContent=last?amountLabel(last.totalAmount):'—';
 chartCandles=baseCandles;if(rows.length&&!chartCandles.some(d=>d.day===day)){let tail=rows.at(-1);chartCandles.push({day,open:rows[0].open,close:tail.price,high:Math.max(...rows.map(r=>r.high)),low:Math.min(...rows.map(r=>r.low)),volume:tail.totalVolume,amount:tail.totalAmount})}renderDaily();
 if(!rows.length){minuteHover=null;$('#chart').innerHTML='<text x="400" y="90" text-anchor="middle" fill="#819899">当前无可见分钟数据</text>';$('#volume-chart').innerHTML='';return}
 let sells=(game.orders||[]).filter(o=>o.code===code&&o.day===day&&o.side==='SELL'&&o.status==='FILLED'&&o.price!=null&&o.time<=((day===game.day)?game.clock:'15:00'));let vals=rows.flatMap(r=>[r.price,r.average]).concat(sells.map(o=>o.price)).filter(x=>x>0);if(prev)vals.push(prev);let min=Math.min(...vals),max=Math.max(...vals);if(max===min){max+=.01;min-=.01}let pad=(max-min)*.1;min-=pad;max+=pad;let y=n=>155-(n-min)/(max-min)*130;
 let animateLast=playing&&viewMode==='replay'&&!changed&&minuteHover?.day===day&&minuteHover.rows.at(-1)?.time<last.time;minuteHover={rows,prev,min,max,day};
 let marks=sells.map(o=>{let x=xFor(o.time),py=y(o.price);return `<g class="sell-marker" data-time="${esc(o.time)}"><title>卖出 ${o.quantity} 股 ¥${money(o.price)} · ${esc(o.time)}</title><circle cx="${x}" cy="${py}" r="7" fill="#169a78"/><text x="${x}" y="${py+3}" text-anchor="middle" fill="white" font-size="9" font-weight="bold">S</text></g>`}).join('');
 let cross='';if(viewMode==='replay'&&day===game.day){let x=xFor(last.time),py=y(last.price),pct=prev?((last.price/prev-1)*100).toFixed(2)+'%':'—';let labelX=Math.max(50,Math.min(734,x-23));cross=`<g class="replay-crosshair" data-time="${esc(last.time)}" data-price="${last.price}" data-change="${pct}"><line x1="${x}" x2="${x}" y1="20" y2="155" stroke="#657e9b" stroke-dasharray="4 3"/><line x1="50" x2="780" y1="${py}" y2="${py}" stroke="#657e9b" stroke-dasharray="4 3"/><circle cx="${x}" cy="${py}" r="3" fill="#f05b6a"/><rect x="${labelX}" y="157" width="46" height="14" rx="2" fill="#42688f"/><text x="${labelX+23}" y="168" text-anchor="middle" fill="white" font-size="10">${esc(last.time)}</text><rect x="676" y="${py-8}" width="104" height="16" rx="2" fill="#42688f"/><text class="axis-price-change" x="728" y="${py+3}" text-anchor="middle" fill="white" font-size="10">${Number(last.price).toFixed(2)}　${pct}</text></g>`}
 $('#chart').innerHTML=`${minuteBase(rows,prev,min,max,155,'minute-fill',animateLast,Math.min(300,Math.max(50,Number($('#speed').value)*.7)))}<text x="50" y="175" fill="#97a8ba" font-size="11">09:30</text><text x="380" y="175" fill="#97a8ba" font-size="11">11:30 / 13:00</text><text x="750" y="175" fill="#97a8ba" font-size="11">15:00</text>${cross}${marks}`;
 let vmax=Math.max(...rows.map(r=>r.volume),1);$('#volume-chart').innerHTML=rows.map(r=>{let height=r.volume/vmax*48;return `<rect x="${xFor(r.time)-1}" y="${53-height}" width="2" height="${height}" fill="${r.price>=r.open?'#e88a92':'#72cfb8'}"/>`}).join('');
}
const holdingQty=code=>(game.positions||[]).filter(p=>p.code===code).reduce((sum,p)=>sum+p.quantity,0);
async function advance(){if(busy)return;if(playing&&playbackDay&&game.clock==='15:00'){stop();toast('当日分时已播放完毕');return}busy=true;try{let previousDay=game.day,held=selected?holdingQty(selected):0,state=await api('/api/game/step',{});await refreshGame(state);if(previousDay!==game.day){viewMode='replay';chartOffset=0}if(playing&&!playbackDay)playbackDay=game.day;let cleared=playing&&!!selected&&held>0&&holdingQty(selected)===0;if(cleared){await refreshGame(await api('/api/game/skip-to-close',{}));stop()}if(selected)await refreshChart();if(cleared)toast('已清仓 '+selected+'，分时跳到收盘并停止播放')}finally{busy=false}}
async function loop(run){if(!playing||run!==playbackRun)return;let started=performance.now();try{await advance()}catch(e){if(run===playbackRun){stop();toast(e.message)}return}if(playing&&run===playbackRun)timer=setTimeout(()=>loop(run),Math.max(0,Number($('#speed').value)-(performance.now()-started)))}
$('#random').onclick=()=>action(async()=>{$('#date').value=(await api('/api/game/random-day')).day});
$('#start').onclick=()=>action(async()=>{if(!$('#date').value)throw Error('请选择日期');await api('/api/game/start',{day:$('#date').value});await refreshGame();toast('训练已开始')});
function screenParams(){let p={};for(const [k] of params){let input=$('#p-'+k);if(input.value.trim()==='')throw Error('请填写完整的选股参数');p[k]=Number(input.value);if(!Number.isFinite(p[k])||p[k]<0||(k==='volumeRatio'&&p[k]===0))throw Error('选股参数无效')}return p}
let progressRun=0,progressTimer=null;
const progressDialog=()=>$('#progress-dialog');
function setProgress(percent,indeterminate){
 let track=$('#progress-track'),bar=$('#progress-bar');track.classList.toggle('indeterminate',!!indeterminate);
 if(indeterminate)bar.style.width='';else{bar.style.width=Math.max(0,Math.min(100,percent))+'%';$('#progress-percent').textContent=Math.round(percent)+'%'}
}
function openProgress(title){
 progressRun++;clearTimeout(progressTimer);$('#progress-title').textContent=title;$('#progress-percent').textContent='0%';$('#progress-stage').textContent='正在准备…';$('#progress-detail').textContent='';setProgress(0,true);
 if(!progressDialog().open)progressDialog().showModal();return progressRun;
}
function renderScreenProgress(s){
 let percent=s.total>0?Math.min(100,s.done/s.total*100):0,indeterminate=s.total===0;setProgress(percent,indeterminate);
 $('#progress-stage').textContent=(s.message||'正在选股')+(indeterminate?'':'（'+Math.round(percent)+'%）');
 $('#progress-detail').textContent=s.total>0?`已检查 ${s.done}/${s.total} 只 · 命中 ${(s.matches||[]).length} 只 · 失败 ${s.failed} · 缺股本 ${s.missingCapital} · 日线未覆盖 ${s.unsynced}`:'正在准备历史行情与候选股票…';
}
function waitScreen(run){
 return new Promise((resolve,reject)=>{
  const tick=async()=>{
   if(run!==progressRun)return reject(Error('进度已关闭'));
   try{let s=await api('/api/screen');if(run!==progressRun)return reject(Error('进度已关闭'));renderScreenProgress(s);if(s.running){progressTimer=setTimeout(tick,400);return}resolve(s)}catch(e){reject(e)}
  };
  tick();
 });
}
function finishProgress(run,stage){
 if(run!==progressRun)return;setProgress(100,false);$('#progress-stage').textContent=stage;$('#progress-percent').textContent='100%';clearTimeout(progressTimer);
 setTimeout(()=>{if(run===progressRun)closeProgress()},700);
}
function closeProgress(){progressRun++;clearTimeout(progressTimer);if(progressDialog().open)progressDialog().close()}
progressDialog().addEventListener('cancel',e=>e.preventDefault());
$('#screen').onclick=()=>action(async()=>{
 let parameters=screenParams(),run=openProgress('执行选股');
 try{await api('/api/screen',{day:game.day,parameters});let result=await waitScreen(run);await refreshScreen();finishProgress(run,`选股完成，命中 ${result.matches.length} 只`)}catch(e){closeProgress();throw e}
});
let nextScreenBusy=false;
$('#next-screen').onclick=()=>action(async()=>{
 if(nextScreenBusy)return;let parameters=screenParams();nextScreenBusy=true;$('#next-screen').disabled=true;let run=openProgress('执行下一次选股');
 try{
  stop();$('#progress-stage').textContent='正在结束当前回放并结算持仓…';$('#progress-detail').textContent='';setProgress(0,true);
  while(busy||tradeBusy)await new Promise(resolve=>setTimeout(resolve,50));
  let status=await api('/api/screen');if(status.running)throw Error('选股仍在进行，请稍候');
  await api('/api/game/next-selection',{});if(run!==progressRun)return;
  await refreshGame();viewMode='selection';chartOffset=0;
  $('#progress-stage').textContent='正在准备历史行情…';setProgress(0,true);
  await api('/api/screen',{day:game.day,parameters});let result=await waitScreen(run);
  await refreshScreen();if(selected)await refreshChart();
  finishProgress(run,`选股完成，命中 ${result.matches.length} 只`);toast('已进入 '+game.day+' 收盘，开始下一次选股');
 }catch(e){closeProgress();throw e}
 finally{nextScreenBusy=false;$('#next-screen').disabled=false}
});
$('#step').onclick=()=>action(advance);$('#play').onclick=()=>{if(playing)stop();else{if(viewMode!=='replay'){viewMode='replay';chartOffset=0}playbackDay=game.clock==='15:00'?null:game.day;playing=true;$('#play').textContent='暂停';loop(++playbackRun)}};
$('#stop-play').onclick=()=>{stop();toast('回放已停止，可继续播放或执行下一次选股')};
$('#selection-view').onclick=()=>action(async()=>{viewMode='selection';chartOffset=0;await refreshChart()});$('#replay-view').onclick=()=>action(async()=>{viewMode='replay';chartOffset=0;await refreshChart()});
$('#load').onclick=()=>action(async()=>{selected=$('#symbol').value.trim().toUpperCase();await refreshChart()});
let tradeBusy=false;
document.querySelectorAll('.quick-trade button').forEach(button=>button.onclick=()=>action(async()=>{if(tradeBusy)return;tradeBusy=true;document.querySelectorAll('.quick-trade button').forEach(b=>b.disabled=true);try{let code=$('#symbol').value.trim().toUpperCase(),side=button.dataset.side;await api('/api/game/order',{code,side,fraction:Number(button.dataset.fraction)});await refreshGame();if(selected===code)await refreshChart();toast(side==='BUY'?'已按当日收盘价模拟买入':'卖出订单已提交，下一分钟尝试成交')}finally{tradeBusy=false;document.querySelectorAll('.quick-trade button').forEach(b=>b.disabled=false)}}));
let passBusy=false;
$('#pass-day').onclick=()=>action(async()=>{
 if(passBusy)return;
 if(!game.active)throw Error('请先开始游戏');
 if((game.positions||[]).length)throw Error('当前仍有持仓，请先卖出全部持仓再空仓跳过');
 if((game.orders||[]).some(o=>o.status==='PENDING'))throw Error('还有未成交的订单，请先推进回放处理订单');
 if(!confirm('当日空仓？将跳过 '+game.day+'，进入下一个交易日重新选股。'))return;
 passBusy=true;$('#pass-day').disabled=true;
 try{
  stop();while(busy||tradeBusy)await new Promise(resolve=>setTimeout(resolve,50));
  await api('/api/game/pass-day',{});await refreshGame();
  viewMode='selection';chartOffset=0;screenMatches=[];renderMatches();
  $('#screen-status').textContent='当日已空仓，已跳到 '+game.day+'，请执行选股';
  if(selected)await refreshChart();
  toast('已空仓，跳到 '+game.day+'，可以重新选股');
 }finally{passBusy=false;$('#pass-day').disabled=false}
});
const signedMoney=n=>n>0?'+¥'+money(n):n<0?'-¥'+money(-n):'¥0.00';
function showSettlement(result){
 let s=result&&result.settlement;if(!s)return;
 let items=s.items||[],rate=(result.closedTrades||0)>0?((result.wins||0)/result.closedTrades*100).toFixed(1)+'%':'—';
 $('#settle-summary').innerHTML=[
  ['结算时间',esc(s.day||'')+' '+esc(s.clock||'')],
  ['结算卖出',items.length+' 笔'],
  ['结算到账','¥'+money(s.proceeds||0)],
  ['取消未成交',(s.cancelled||0)+' 笔'],
  ['最终现金','¥'+money(result.cash||0)],
  ['已实现盈亏',signedMoney(result.realized||0),(result.realized||0)>0?'positive':(result.realized||0)<0?'negative':''],
  ['完成卖出',(result.closedTrades||0)+' 笔'],
  ['胜率',rate]
 ].map(a=>`<div class="settle-metric"><span>${a[0]}</span><strong class="${a[2]||''}">${a[1]}</strong></div>`).join('');
 $('#settle-items').innerHTML=items.length?items.map(i=>`<tr><td>${esc(i.name||i.code)} · ${esc(i.code)}</td><td>${i.quantity}</td><td>¥${money(i.price)}</td><td>¥${money(i.amount)}</td></tr>`).join(''):'<tr><td colspan="4" class="empty">结束结算时没有持仓，无需卖出</td></tr>';
 $('#settle-note').textContent='结算价按结束时刻可见的最后价格估算，手续费已从到账金额中扣除。';
 let dialog=$('#settle-dialog');if(!dialog.open)dialog.showModal();
}
$('#settle-close').onclick=()=>$('#settle-dialog').close();
$('#end').onclick=()=>action(async()=>{
 stop();
 if(!confirm('结束游戏并按当前可见价格结算持仓？'))return;
 let result=await api('/api/game/end',{});
 await refreshGame(result);
 showSettlement(result);
 toast('游戏已结束');
});
$('#reset').onclick=()=>action(async()=>{stop();let password=prompt('输入重置密码');if(password===null)return;await api('/api/game/reset',{password});selected='';await refreshGame();toast('账户已重置')});
$('#sync').onclick=()=>action(async()=>{await api('/api/market/sync',{});await refreshSync()});
$('#value').onclick=()=>action(async()=>{let v=await api('/api/game/valuation');$('#valuation').textContent=v.missing.length?'以下股票缺行情，估值不完整：'+v.missing.join('、'):`总资产 ¥${money(v.total)} · 浮动盈亏 ¥${money(v.unrealized)}`});
function dropHover(){document.querySelectorAll('.hover-cross,.hover-volume-cross,.chart-tooltip').forEach(e=>e.remove());let auto=$('#chart .replay-crosshair');if(auto)auto.style.display=''}
function addTooltip(svg,e,lines){let box=svg.getBoundingClientRect(),parent=svg.parentElement,pbox=parent.getBoundingClientRect(),tip=document.createElement('div');tip.className='chart-tooltip';tip.innerHTML=lines.join('<br>');parent.appendChild(tip);let x=e.clientX-pbox.left+14,y=e.clientY-pbox.top+14;tip.style.left=Math.max(0,Math.min(x,pbox.width-tip.offsetWidth-5))+'px';tip.style.top=Math.max(0,Math.min(y,pbox.height-tip.offsetHeight-5))+'px'}
$('#daily-chart').addEventListener('pointermove',e=>{
 if(dragX!==null||!dailyBounds||!displayedCandles.length){dropHover();return}let svg=$('#daily-chart'),box=svg.getBoundingClientRect(),x=(e.clientX-box.left)/box.width*800,py=(e.clientY-box.top)/box.height*260,index=Math.floor((x-50)/730*displayedCandles.length);if(index<0||index>=displayedCandles.length||py<5||py>225){dropHover();return}dropHover();let d=displayedCandles[index],cx=50+(index+.5)*730/displayedCandles.length,yy=Math.max(25,Math.min(225,py)),price=dailyBounds.hi-(yy-25)/200*(dailyBounds.hi-dailyBounds.lo),globalIndex=chartCandles.findIndex(c=>c.day===d.day),previous=globalIndex>0?chartCandles[globalIndex-1].close:null,change=previous?((d.close/previous-1)*100).toFixed(2)+'%':'—';
 svg.insertAdjacentHTML('beforeend',`<g class="hover-cross" pointer-events="none"><path d="M${cx} 5V250 M50 ${yy}H780" stroke="#8b9aab" stroke-dasharray="4 3"/><rect x="0" y="${yy-8}" width="48" height="16" rx="2" fill="#2869df"/><text x="24" y="${yy+3}" text-anchor="middle" fill="white" font-size="10">${price.toFixed(2)}</text><rect x="${Math.max(50,Math.min(708,cx-36))}" y="245" width="72" height="15" rx="2" fill="#2869df"/><text x="${Math.max(50,Math.min(708,cx-36))+36}" y="256" text-anchor="middle" fill="white" font-size="10">${esc(d.day)}</text></g>`);$('#daily-volume').insertAdjacentHTML('beforeend',`<line class="hover-volume-cross" x1="${cx}" x2="${cx}" y1="0" y2="65" stroke="#8b9aab" stroke-dasharray="4 3"/>`);addTooltip(svg,e,[`<b>${esc(d.day)}</b>`,`开盘 ${money(d.open)}　最高 ${money(d.high)}`,`最低 ${money(d.low)}　收盘 ${money(d.close)}`,`涨跌 ${change}`,`成交量 ${Math.round(d.volume||0).toLocaleString()} 股`,`成交额 ¥${money(d.amount||0)}`]);
});
$('#daily-chart').addEventListener('pointerleave',dropHover);
$('#chart').addEventListener('pointermove',e=>{
 if(!minuteHover||!minuteHover.rows.length)return;let svg=$('#chart'),box=svg.getBoundingClientRect(),x=(e.clientX-box.left)/box.width*800;if(x<50||x>780){dropHover();return}dropHover();let row=minuteHover.rows.reduce((best,r)=>Math.abs(xFor(r.time)-x)<Math.abs(xFor(best.time)-x)?r:best),cx=xFor(row.time),py=155-(row.price-minuteHover.min)/(minuteHover.max-minuteHover.min)*130,change=minuteHover.prev?((row.price/minuteHover.prev-1)*100).toFixed(2)+'%':'—',labelX=Math.max(50,Math.min(734,cx-23));let auto=svg.querySelector('.replay-crosshair');if(auto)auto.style.display='none';svg.insertAdjacentHTML('beforeend',`<g class="hover-cross" pointer-events="none"><path d="M${cx} 20V155 M50 ${py}H780" stroke="#657e9b" stroke-dasharray="4 3"/><rect x="${labelX}" y="157" width="46" height="14" rx="2" fill="#2869df"/><text x="${labelX+23}" y="168" text-anchor="middle" fill="white" font-size="10">${esc(row.time)}</text><rect x="676" y="${py-8}" width="104" height="16" rx="2" fill="#2869df"/><text class="axis-price-change" x="728" y="${py+3}" text-anchor="middle" fill="white" font-size="10">${row.price.toFixed(2)}　${change}</text></g>`);$('#volume-chart').insertAdjacentHTML('beforeend',`<line class="hover-volume-cross" x1="${cx}" x2="${cx}" y1="0" y2="55" stroke="#8b9aab" stroke-dasharray="4 3"/>`);addTooltip(svg,e,[`<b>${esc(minuteHover.day)} ${esc(row.time)}</b>`,`价格 ${money(row.price)}　涨跌 ${change}`,`均价 ${money(row.average)}　分钟量 ${Math.round(row.volume).toLocaleString()} 股`,`累计量 ${Math.round(row.totalVolume).toLocaleString()} 股`,`累计成交额 ${amountLabel(row.totalAmount)}`,`分钟成交额 ¥${money(row.amount)}`]);
});
$('#chart').addEventListener('pointerleave',dropHover);
$('#dialog-chart').addEventListener('pointermove',e=>{
 if(!dialogHover||!dialogHover.rows.length)return;let svg=$('#dialog-chart'),box=svg.getBoundingClientRect(),x=(e.clientX-box.left)/box.width*800;if(x<50||x>780){dropHover();return}dropHover();let row=dialogHover.rows.reduce((best,r)=>Math.abs(xFor(r.time)-x)<Math.abs(xFor(best.time)-x)?r:best),cx=xFor(row.time),py=235-(row.price-dialogHover.min)/(dialogHover.max-dialogHover.min)*210,pct=dialogHover.prev?((row.price/dialogHover.prev-1)*100).toFixed(2)+'%':'—',lx=Math.max(50,Math.min(734,cx-23));svg.insertAdjacentHTML('beforeend',`<g class="hover-cross" pointer-events="none"><path d="M${cx} 25V235 M50 ${py}H780" stroke="#8898aa" stroke-dasharray="4 3"/><circle cx="${cx}" cy="${py}" r="3" fill="#f05b6a"/><rect x="0" y="${py-8}" width="48" height="16" rx="2" fill="#2869df"/><text x="24" y="${py+3}" text-anchor="middle" fill="white" font-size="10">${row.price.toFixed(2)}</text><rect x="${lx}" y="238" width="46" height="16" rx="2" fill="#2869df"/><text x="${lx+23}" y="250" text-anchor="middle" fill="white" font-size="10">${esc(row.time)}</text></g>`);$('#dialog-volume').insertAdjacentHTML('beforeend',`<line class="hover-volume-cross" x1="${cx}" x2="${cx}" y1="0" y2="70" stroke="#8b9aab" stroke-dasharray="4 3"/>`);addTooltip(svg,e,[`<b>${esc(dialogHover.day)} ${esc(row.time)}</b>`,`价格 ${money(row.price)}　涨跌 ${pct}`,`均价 ${money(row.average)}`,`成交量 ${Math.round(row.volume).toLocaleString()} 股`,`成交额 ${amountLabel(row.amount)}`]);
});
$('#dialog-chart').addEventListener('pointerleave',dropHover);
Promise.all([refreshGame(),refreshSync(),refreshScreen()]).then(()=>{let code=game.positions[0]?.code||game.orders.find(o=>o.status==='FILLED')?.code;if(game.active&&code)choose(code)}).catch(e=>toast(e.message));setInterval(()=>{refreshSync().catch(()=>{});refreshScreen().catch(()=>{})},4000);
