import test from 'node:test';
import assert from 'node:assert/strict';
import {parseSchedule,kyivTime,scheduleHold,ScheduleClient} from '../worker/schedule.mjs';
import {BoilerGuard} from '../worker/boiler.mjs';
const start=kyivTime('2026-11-28','14:30'),end=kyivTime('2026-11-28','18:00');
function payload(line='Електроенергії немає з 14:30 до 18:00.',date='28.11.2026'){
  return {'hydra:member':[{type:'photo-grafic',menuItems:[{rawHtml:'<p>Графік погодинних відключень на '+date+'</p><p>Інформація станом на 21:22 27.11.2026</p><p>Група 6.1. Електроенергія є.</p><p>Група 6.2. '+line+'</p>'}]}]};
}
function source(){return {current(){return {fresh:true,data:{days:[{date:'2026-11-28',intervals:[{start,end}]}]}};}};}
test('official payload: only exact group, Kyiv date and explicit intervals',()=>{
  assert.deepEqual(parseSchedule(payload(),start).days[0].intervals,[{start,end}]);
  const p=payload();p['hydra:member'][0].menuItems[0].rawHtml=p['hydra:member'][0].menuItems[0].rawHtml.replaceAll('<','\\u003C').replaceAll('>','\\u003E');
  assert.deepEqual(parseSchedule(p,start).days[0].intervals,[{start,end}]);
  assert.equal(parseSchedule(payload('Електроенергія є.'),start).days[0].intervals.length,0);
});
test('parser rejects missing group, malformed ranges, unknown wording and old dates',()=>{
  for(const line of ['Електроенергії немає з 18:00 до 14:30.','Електроенергії немає з 25:00 до 26:00.','Можливі відключення.','Електроенергії немає з 14:30 до 18:00 та щось.'])
    assert.throws(()=>parseSchedule(payload(line),start));
  assert.throws(()=>parseSchedule(payload(undefined,'27.11.2026'),start));
  const p=payload();p['hydra:member'][0].menuItems[0].rawHtml=p['hydra:member'][0].menuItems[0].rawHtml.replace('Група 6.2.','Група 6.1.');
  assert.throws(()=>parseSchedule(p,start));
});
test('midnight and summer offsets are explicit; ambiguous DST times rejected',()=>{
  assert.equal(new Date(kyivTime('2026-11-28','24:00')).toISOString(),'2026-11-28T22:00:00.000Z');
  assert.equal(new Date(kyivTime('2026-07-01','14:30')).toISOString(),'2026-07-01T11:30:00.000Z');
  assert.throws(()=>kyivTime('2026-10-25','03:30'));
});
test('archive entries are never used',()=>{
  const p=payload();p['hydra:member'][0].menuItems=[{name:'Archive',children:p['hydra:member'][0].menuItems}];
  assert.throws(()=>parseSchedule(p,start));
});
test('hold begins at start, releases at 40 continuous minutes, latches same interval',()=>{
  const s={},c=source();
  assert.equal(scheduleHold(s,c,true,start-1),false);
  for(let t=start;t<start+2400000;t+=5000)assert.equal(scheduleHold(s,c,true,t),true);
  assert.equal(scheduleHold(s,c,true,start+2400000),false);
  assert.equal(s.schedule.reason,'no_outage_40m');
  assert.equal(scheduleHold(s,c,true,start+2500000),false);
});
test('offline then online releases before scheduled end; late actual outage still records offline',()=>{
  const s={},c=source();scheduleHold(s,c,true,start);scheduleHold(s,c,false,start+5000);
  assert.equal(scheduleHold(s,c,false,start+60000),true);
  assert.equal(scheduleHold(s,c,true,start+65000),false);
  assert.equal(s.schedule.reason,'grid_returned');
});
test('restart/unknown gap does not count as 40 minutes of observed power',()=>{
  const s={},c=source();scheduleHold(s,c,true,start);
  assert.equal(scheduleHold(s,c,true,start+2400000),true);
  assert.equal(s.schedule.onlineSince,start+2400000);
  scheduleHold(s,c,null,start+2405000);assert.equal(s.schedule.onlineSince,null);
});
test('stale source never starts a new hold; existing hold survives source errors',()=>{
  const s={},c=source(),bad={current(){return {fresh:false};}};
  assert.equal(scheduleHold(s,bad,true,start),false);
  assert.equal(scheduleHold(s,c,true,start),true);
  assert.equal(scheduleHold(s,bad,true,start+5000),true);
  assert.equal(scheduleHold(s,bad,true,end),false);
});
test('schedule cancellation releases; editing the same interval does not repeat shutdown',()=>{
  const s={},c=source();scheduleHold(s,c,true,start);scheduleHold(s,c,false,start+5000);scheduleHold(s,c,true,start+10000);
  const changed={current(){return {fresh:true,data:{days:[{date:'2026-11-28',intervals:[{start:start-60000,end:end+60000}]}]}};}};
  assert.equal(scheduleHold(s,changed,true,start+15000),false);
  const s2={};scheduleHold(s2,c,true,start);
  assert.equal(scheduleHold(s2,{current(){return {fresh:true,data:{days:[{date:'2026-11-28',intervals:[]}]}};}},true,start+5000),false);
});
function fixture(){
  let now=start-5000,on=true,online=true,watts=1000;const calls=[];
  const client={c:{loadControl:true,relayId:'relay',plugId:'plug'},async details(id){return {online:id==='relay'?online:true,category:id==='relay'?'dlq':'cz'};},
    async validatePlug(){},async plugState(){return on;},async relayPower(){return {watts,measuredAt:now};},
    async off(){calls.push('off');on=false;},async on(){calls.push('on');on=true;}};
  const bot={chat:'owner',record:{queue:[]},async persist(){}},guard=new BoilerGuard(client,()=>now,source());
  return {client,bot,calls,set(x){if('on'in x)on=x.on;if('online'in x)online=x.online;if('watts'in x)watts=x.watts;},async tick(ms=5000){now+=ms;await guard.tick(bot);}};
}
test('full guard: scheduled OFF, no early ON, ON at 40m, no repeat OFF',async()=>{
  const h=fixture();await h.tick();assert.deepEqual(h.calls,['off']);
  for(let i=1;i<480;i++)await h.tick();
  assert.deepEqual(h.calls,['off']);await h.tick();assert.deepEqual(h.calls,['off','on']);
  for(let i=0;i<30;i++)await h.tick();assert.deepEqual(h.calls,['off','on']);
});
test('full guard: actual outage return needs 2m; later overload still cuts off',async()=>{
  const h=fixture();await h.tick();h.set({online:false});await h.tick();
  h.set({online:true});await h.tick();for(let i=0;i<23;i++)await h.tick();assert.deepEqual(h.calls,['off']);
  await h.tick();assert.deepEqual(h.calls,['off','on']);h.set({watts:3800});await h.tick();assert.deepEqual(h.calls,['off','on','off']);
});
test('full guard: manual OFF remains OFF and high load blocks return at 40m',async()=>{
  const manual=fixture();manual.set({on:false});for(let i=0;i<500;i++)await manual.tick();assert.deepEqual(manual.calls,[]);
  const high=fixture();await high.tick();high.set({watts:1800});for(let i=0;i<500;i++)await high.tick();assert.deepEqual(high.calls,['off']);
});
test('unconfirmed scheduled OFF keeps retrying; pause stops commands',async()=>{
  const h=fixture();h.client.off=async()=>h.calls.push('off');await h.tick();await h.tick();assert.deepEqual(h.calls,['off','off']);
  h.bot.record.boilerPaused=true;await h.tick();assert.deepEqual(h.calls,['off','off']);
});
test('failed source requests are cached only five minutes',async()=>{
  let now=start;const c=new ScheduleClient(async()=>new Response(JSON.stringify(payload())),()=>now);
  await c.tick();assert.equal(c.current().fresh,true);
  c.fetcher=async()=>{throw new Error('offline');};now+=60000;await c.tick();assert.equal(c.current().fresh,true);
  now+=240001;assert.equal(c.current().fresh,false);
});
