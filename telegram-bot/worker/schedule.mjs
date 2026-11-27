export const SCHEDULE_GROUP='6.2';
export const SCHEDULE_URL='https://api.loe.lviv.ua/api/menus?page=1&type=photo-grafic';
export const NO_OUTAGE_WAIT_MS=40*60000;
const MAX_GAP=45000;
const zone=new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Kyiv',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
function parts(at){return Object.fromEntries(zone.formatToParts(at).map(p=>[p.type,p.value]));}
export function kyivDate(at){const p=parts(at);return p.year+'-'+p.month+'-'+p.day;}
export function kyivTime(date,hhmm){
  if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!/^(\d{2}):(\d{2})$/.test(hhmm))throw new Error('SCHEDULE_TIME');
  const [y,m,d]=date.split('-').map(Number),[h,min]=hhmm.split(':').map(Number);
  if(h>24||min>59||(h===24&&min!==0))throw new Error('SCHEDULE_TIME');
  const day=Date.UTC(y,m-1,d);
  if(new Date(day).toISOString().slice(0,10)!==date)throw new Error('SCHEDULE_DATE');
  const target=day+h*3600000+min*60000,candidates=[];
  for(const offset of [2,3]){
    const at=target-offset*3600000,p=parts(at);
    const local=Date.UTC(+p.year,+p.month-1,+p.day,+p.hour,+p.minute);
    if(local===target)candidates.push(at);
  }
  // Do not guess during the repeated/skipped DST hour.
  if(candidates.length!==1)throw new Error('SCHEDULE_AMBIGUOUS_TIME');
  return candidates[0];
}
function clean(html){
  return String(html).replace(/\\u([0-9a-f]{4})/gi,(_,v)=>String.fromCharCode(parseInt(v,16)))
    .replace(/<[^>]*>/g,' ').replace(/&nbsp;|&#160;/g,' ').replace(/\s+/g,' ').trim();
}
export function parseSchedule(json,now=Date.now()){
  const menus=Array.isArray(json)?json:json?.['hydra:member'];
  if(!Array.isArray(menus))throw new Error('SCHEDULE_FORMAT');
  const days={};
  // Only current menu items. Never infer today's schedule from archive children.
  for(const menu of menus.filter(m=>m.type==='photo-grafic'))for(const item of menu.menuItems??[]){
    if(!item.rawHtml)continue;
    const text=clean(item.rawHtml),dm=text.match(/Графік погодинних відключень на (\d{2})\.(\d{2})\.(\d{4})/);
    if(!dm)continue;
    const date=dm[3]+'-'+dm[2]+'-'+dm[1];
    if(![kyivDate(now),kyivDate(now+86400000)].includes(date))continue;
    const stamp=text.match(/Інформація станом на (\d{2}:\d{2}) (\d{2})\.(\d{2})\.(\d{4})/);
    if(!stamp)throw new Error('SCHEDULE_STAMP');
    const publishedAt=kyivTime(stamp[4]+'-'+stamp[3]+'-'+stamp[2],stamp[1]);
    if(publishedAt>now+60000||now-publishedAt>72*3600000)throw new Error('SCHEDULE_STALE');
    const lines=[...text.matchAll(/Група\s+(\d+\.\d+)\.\s*([\s\S]*?)(?=Група\s+\d+\.\d+\.|$)/g)].filter(m=>m[1]===SCHEDULE_GROUP);
    if(lines.length!==1)throw new Error('SCHEDULE_GROUP');
    const line=lines[0][2].trim(),intervals=[];
    if(!/^Електроенергія є\.$/.test(line)){
      if(!line.startsWith('Електроенергії немає '))throw new Error('SCHEDULE_WORDING');
      const body=line.slice('Електроенергії немає '.length);
      const matches=[...body.matchAll(/з (\d{2}:\d{2}) до (\d{2}:\d{2})/g)];
      if(!matches.length||body.replace(/з \d{2}:\d{2} до \d{2}:\d{2}/g,'').replace(/[,\s.]/g,''))throw new Error('SCHEDULE_INTERVAL');
      for(const match of matches){
        const start=kyivTime(date,match[1]),end=kyivTime(date,match[2]);
        if(end<=start)throw new Error('SCHEDULE_INTERVAL');
        intervals.push({start,end});
      }
    }
    intervals.sort((a,b)=>a.start-b.start);
    const merged=[];
    for(const i of intervals){const last=merged.at(-1);if(last&&i.start<=last.end)last.end=Math.max(last.end,i.end);else merged.push({...i});}
    if(!days[date]||publishedAt>days[date].publishedAt)days[date]={date,publishedAt,intervals:merged};
    else if(publishedAt===days[date].publishedAt&&JSON.stringify(merged)!==JSON.stringify(days[date].intervals))throw new Error('SCHEDULE_CONFLICT');
  }
  if(!Object.keys(days).length)throw new Error('SCHEDULE_MISSING');
  return {group:SCHEDULE_GROUP,days:Object.values(days).sort((a,b)=>a.date.localeCompare(b.date))};
}
export class ScheduleClient {
  constructor(fetcher=fetch,now=()=>Date.now()){this.fetcher=fetcher;this.now=now;this.next=0;this.data=null;this.fetchedAt=0;this.error=null;this.inFlight=null;}
  async tick(force=false){
    if(this.inFlight)return this.inFlight;
    if(!force && this.now()<this.next)return;
    this.inFlight=this.refresh().finally(()=>{this.inFlight=null;});
    return this.inFlight;
  }
  async confirmActive(){
    // Wait for an older background request, then request the source again.
    if(this.inFlight)await this.inFlight;
    await this.tick(true);
    const now=this.now();
    if(this.error)return false;
    const day=this.data?.days.find(d=>d.date===kyivDate(now));
    return !!day?.intervals.some(i=>i.start<=now && now<i.end);
  }
  async refresh(){
    const now=this.now();
    this.next=now+60000;
    try{
      const r=await this.fetcher(SCHEDULE_URL,{signal:AbortSignal.timeout(15000),redirect:'error',cache:'no-store',headers:{'Cache-Control':'no-cache'}});
      if(!r.ok)throw new Error('SCHEDULE_HTTP');
      const reader=r.body.getReader(),chunks=[];let size=0;
      while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>8*1024*1024){await reader.cancel();throw new Error('SCHEDULE_SIZE');}chunks.push(value);}
      const body=Buffer.concat(chunks).toString('utf8');
      this.data=parseSchedule(JSON.parse(body),this.now());this.fetchedAt=this.now();this.error=null;
    }catch(e){this.error=e.message?.startsWith('SCHEDULE_')?e.message:'SCHEDULE_UNAVAILABLE';}
  }
  current(now=this.now()){
    return {data:this.data,fetchedAt:this.fetchedAt,error:this.error,fresh:!!this.data&&now-this.fetchedAt<300000};
  }
}
// Mutates only the guard's durable schedule state; it never sends commands.
export function scheduleHold(s,source,online,now){
  const view=source?.current(now),day=view?.fresh?view.data.days.find(d=>d.date===kyivDate(now)):null;
  const active=day?.intervals.find(i=>i.start<=now&&now<i.end);
  let state=s.schedule;
  if(active){
    // Preserve the latch if an operator edits the boundaries of the same active interval.
    if(!state||active.start>=state.end||active.end<=state.start){
      state={...active,released:false,sawOffline:false,onlineSince:null,lastCheck:null};s.schedule=state;
    }else state.end=active.end;
  }else if(day&&state&&!state.released){state.released=true;state.reason='schedule_updated';}
  if(!state)return false;
  if(now>=state.end){state.released=true;state.reason??='interval_ended';}
  if(state.released)return false;
  if(online===false){state.sawOffline=true;state.onlineSince=null;}
  else if(online===true){
    if(state.sawOffline){state.released=true;state.reason='grid_returned';}
    else {
      if(state.onlineSince===null||!state.lastCheck||now-state.lastCheck>MAX_GAP)state.onlineSince=now;
      if(now-state.onlineSince>=NO_OUTAGE_WAIT_MS){state.released=true;state.reason='no_outage_40m';}
    }
  }else state.onlineSince=null;
  state.lastCheck=now;
  return !state.released;
}
export function scheduleText(client,now=Date.now()){
  const v=client?.current(now);
  if(!v?.data)return '📅 Львівобленерго · 6.2: графік ще не отримано.';
  const time=at=>new Intl.DateTimeFormat('uk-UA',{timeZone:'Europe/Kyiv',hour:'2-digit',minute:'2-digit'}).format(at);
  return '📅 Львівобленерго · підчерга 6.2\n'+v.data.days.map(d=>d.date+': '+(d.intervals.map(i=>time(i.start)+'–'+(kyivDate(i.end)!==d.date?'24:00':time(i.end))).join(', ')||'відключень немає')).join('\n')+
    (v.error||!v.fresh?'\n⚠️ Не вдалося оновити графік. Нові вимкнення за застарілим графіком не запускаються.':'')+
    '\nПовернення після фактичного відновлення мережі або 40 хв без відключення. Діють перевірки навантаження.';
}

export function schedulePanelText(view,now=Date.now()){
  const time=at=>new Intl.DateTimeFormat('uk-UA',{timeZone:'Europe/Kyiv',hour:'2-digit',minute:'2-digit'}).format(at);
  const dateLabel=date=>date.split('-').reverse().join('.');
  const today=kyivDate(now),tomorrow=new Date(Date.parse(today+'T12:00:00Z')+86400000).toISOString().slice(0,10);
  const lines=['📅 Графік відключень','Львівська область · підчерга 6.2',''];
  for(const [date,label] of [[today,'Сьогодні'],[tomorrow,'Завтра']]){
    const day=view?.data?.days.find(d=>d.date===date);
    lines.push(label+' · '+dateLabel(date));
    if(!day)lines.push('Графік ще не отримано.');
    else {
      lines.push(day.intervals.length?day.intervals.map(i=>'🔴 '+time(i.start)+'–'+(kyivDate(i.end)!==date?'24:00':time(i.end))).join('\n'):'🟢 Відключень за опублікованим графіком немає.');
      lines.push('Оновлення оператора: '+dateLabel(kyivDate(day.publishedAt))+' '+time(day.publishedAt));
    }
    lines.push('');
  }
  if(view?.fetchedAt)lines.push('Остання перевірка: '+dateLabel(kyivDate(view.fetchedAt))+' '+time(view.fetchedAt));
  if(!view?.fresh || view?.error)lines.push('⚠️ Актуальність графіка не підтверджена. Очікуємо успішного оновлення.');
  lines.push('Час київський. Джерело: Львівобленерго.','Графік може змінюватися; фактична наявність світла визначається окремо.');
  return lines.join('\n');
}
