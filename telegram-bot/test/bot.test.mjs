import test from 'node:test';
import assert from 'node:assert/strict';
import { authorized, defaults, evolve, fresh, metrics, parseDevice, render } from '../worker/core.mjs';
import { Deye, Telegram, ApiError } from '../worker/api.mjs';
import { Bot } from '../worker/bot.mjs';
import { initial, CloudStore } from '../worker/store.mjs';
const NOW=1800000000000;
const sample=(grid=230,soc=73,load=1000,at=NOW)=>({sn:'test',at,online:true,points:[
  {key:'SOC',value:String(soc),unit:'%'},{key:'BatteryVoltage',value:'53.2',unit:'V'},
  {key:'TotalConsumptionPower',value:String(load),unit:'W'},{key:'GridVoltageL1L2',value:String(grid),unit:'V'}]});
const update=(id=1,data='home',owner=123)=>({update_id:id,callback_query:{id:'callback',data,from:{id:owner,is_bot:false},message:{message_id:42,chat:{id:owner,type:'private'}}}});
function harness(s=sample()) {
  let now=NOW;const calls=[];const store={value:initial(),writes:[],async save(v){this.value=structuredClone(v);this.writes.push(structuredClone(v));}};
  const telegram={async call(method,body){calls.push({method,body});return {message_id:42};}};
  const deye={async read(){return s;}};
  const bot=new Bot({store,telegram,deye,chat:'123',now:()=>now});
  return {bot,store,calls,deye,advance(ms){now+=ms;},now:()=>now};
}
test('strict owner authorization checks private chat and sender',()=>{
  assert.equal(authorized(update(),'123'),true);
  assert.equal(authorized(update(1,'home',999),'123'),false);
  const u=update();u.callback_query.from.id=999;assert.equal(authorized(u,'123'),false);
  u.callback_query.from.id=123;u.callback_query.message.chat.type='group';assert.equal(authorized(u,'123'),false);
});
test('missing measurement never becomes zero or a grid outage',()=>{
  const s=sample();s.points=s.points.filter(p=>p.key!=='GridVoltageL1L2');
  assert.equal(metrics(s).grid,null);assert.equal(metrics(s).pv,null);
  assert.equal(evolve({},s,{...defaults,confirmSeconds:0},NOW).events.length,0);
});
test('unit conversion and invalid SOC are explicit',()=>{
  const s=sample(230,130);s.points.find(p=>p.key==='TotalConsumptionPower').unit='kW';
  s.points.find(p=>p.key==='TotalConsumptionPower').value='3,2';
  assert.equal(metrics(s).load,3200);assert.equal(metrics(s).soc,null);
});
test('offline, stale and future data cannot establish grid state',()=>{
  for(const s of [{...sample(0),online:false},sample(0,73,1000,NOW-901000),sample(0,73,1000,NOW+61000)]) {
    assert.equal(fresh(s,NOW),false);assert.equal(evolve({},s,defaults,NOW).events.length,0);
  }
});
test('outage confirms after 30 seconds without waiting for a new logger timestamp',()=>{
  let s=evolve({},sample(),defaults,NOW).state;
  s=evolve(s,sample(0),defaults,NOW+15000).state;
  assert.equal(evolve(s,sample(0),defaults,NOW+44000).events.length,0);
  const result=evolve(s,sample(0),defaults,NOW+45000);
  assert.equal(result.events[0].kind,'grid');
  assert.equal(evolve(result.state,sample(0),defaults,NOW+60000).events.length,0);
});
test('unknown readings break outage confirmation and gaps are not outages',()=>{
  const state=evolve({stable:true},sample(0),defaults,NOW).state;
  const gap=evolve(state,undefined,defaults,NOW+15000).state;
  assert.equal(evolve(gap,sample(0),defaults,NOW+45000).events.length,0);
});
test('low battery and high load use independent hysteresis latches',()=>{
  const a=evolve({stable:false},sample(0,19,3200),defaults,NOW);
  assert.deepEqual(a.events.map(e=>e.kind),['battery','load']);
  assert.equal(evolve(a.state,sample(0,19,3200),defaults,NOW+15000).events.length,0);
  const recovered=evolve(a.state,sample(0,25,2000),defaults,NOW+15000);
  assert.equal(evolve(recovered.state,sample(0,19,3200),defaults,NOW+30000).events.length,2);
});
test('connection warning and recovery fire once per episode',()=>{
  const a=evolve({},undefined,defaults,NOW);
  const b=evolve(a.state,undefined,defaults,NOW+60000);assert.equal(b.events[0].kind,'connection');
  const c=evolve(b.state,undefined,defaults,NOW+90000);assert.equal(c.events.length,0);
  assert.equal(evolve(c.state,sample(),defaults,NOW+90000).events.length,1);
});
test('dashboard escapes API content and parameter pages stay bounded',()=>{
  const s=sample();s.points.push({key:'<bad>',value:'<script>&',unit:'<>'});
  const content=render('all:0',s,initial(),NOW);
  assert.ok(content.text.includes('&lt;script&gt;&amp;'));assert.ok(!content.text.includes('<script>'));
  for(let i=0;i<200;i++) s.points.push({key:'extra'+i,value:'x'.repeat(1000),unit:'W'});
  assert.ok(render('all:0',s,initial(),NOW).text.length<4096);
  s.points=Array.from({length:12},()=>({key:'&'.repeat(100),value:'<'.repeat(1000),unit:'&'.repeat(100)}));
  assert.ok(render('all:0',s,initial(),NOW).text.length<4096);
  assert.ok(!render('all:0',s,initial(),NOW).text.includes('&am…'));
  assert.match(render('home',{...s,online:false},initial(),NOW).text,/Стан невідомий/);
});
test('catalog preserves unavailable points and selects exact serial',()=>{
  const s=parseDevice({deviceDataList:[{deviceSn:'other'},{deviceSn:'test',collectionTime:NOW/1000,deviceState:1,dataList:[{key:'SOC',value:73,unit:'%'}]}]},'test',['SOC','FutureField']);
  assert.equal(s.points.length,2);assert.equal(s.points[1].value,null);
  assert.throws(()=>parseDevice({deviceDataList:[]},'test'));
});
test('Telegram transport preserves rate limit without treating it as success',async()=>{
  const tg=new Telegram('fake',async()=>new Response(JSON.stringify({ok:false,error_code:429,description:'retry',parameters:{retry_after:80}}),{status:429}));
  await assert.rejects(tg.call('sendMessage'),e=>e.code===429 && e.retryAfter===80);
});
test('Deye uses hashed password and read-only endpoints with isolated requests',async()=>{
  const calls=[];const d=new Deye({base:'https://eu1-developer.deyecloud.com',appId:'a',secret:'s',email:'e',password:'password',sn:'test'},async(url,options)=>{
    calls.push({url,body:JSON.parse(options.body)});
    const body=url.includes('/account/token') ? {success:true,accessToken:'t',expiresIn:3600} : url.includes('/device/latest') ? {success:true,deviceDataList:[{deviceSn:'test',deviceState:1,collectionTime:NOW/1000,dataList:[]}]} : {success:true,measurePoints:['SOC']};
    return new Response(JSON.stringify(body),{status:200});
  });
  await d.read();await d.read();
  assert.match(calls[0].body.password,/^[a-f0-9]{64}$/);assert.notEqual(calls[0].body.password,'password');
  assert.equal(calls.filter(c=>c.url.includes('/account/token')).length,1);
  assert.ok(calls.every(c=>!c.url.includes('/order/')));
});
test('public viewer cannot change global configuration',async()=>{
  const h=harness();await h.bot.handle(update(1,'alerts:toggle',999));
  assert.equal(h.bot.record.settings.alerts,true);
  assert.ok(h.calls.some(c=>c.method==='sendMessage' && c.body.chat_id==='999'));
  assert.ok(!h.calls.find(c=>c.method==='sendMessage').body.reply_markup.inline_keyboard.flat().some(b=>b.callback_data==='soc:up'));
});
test('duplicate callback does not apply threshold changes twice',async()=>{
  const h=harness();h.bot.record.messageId=42;
  await h.bot.handle(update(1,'soc:up'));await h.bot.handle(update(1,'soc:up'));
  assert.equal(h.bot.record.settings.lowSoc,25);assert.equal(h.bot.record.cursor,1);
});
test('failed Telegram delivery keeps durable alarm, retry then removes it',async()=>{
  const h=harness(sample(0,19,3200));let failing=true;
  h.bot.telegram.call=async(method,body)=>{h.calls.push({method,body});if(failing) throw new ApiError('Offline',503);return {};};
  await h.bot.tick();assert.equal(h.bot.record.queue.length,2);
  assert.equal(h.store.value.queue.length,2);
  failing=false;h.advance(30000);await h.bot.deliver();assert.equal(h.bot.record.queue.length,0);
});
test('repeated same timestamp does not append history or spam writes',async()=>{
  const h=harness();await h.bot.tick();const writes=h.store.writes.length;
  h.advance(15000);await h.bot.tick();
  assert.equal(h.bot.record.history.length,1);assert.equal(h.store.writes.length,writes);
});
test('deleted dashboard is recreated only on explicit command',async()=>{
  const h=harness();h.bot.record.messageId=42;
  h.bot.telegram.call=async(method,body)=>{h.calls.push({method,body});if(method==='editMessageText') throw new ApiError('message to edit not found',400);return {message_id:43};};
  await h.bot.show('home',{automatic:true});assert.ok(!h.calls.some(c=>c.method==='sendMessage'));
  await h.bot.show('home');assert.equal(h.bot.record.messageId,43);
});
test('cloud storage uses canonical ETag when a gateway weakens the GET tag',async()=>{
  let options;
  const s=new CloudStore('test',{
    async get(){return {stream:new Response(JSON.stringify(initial())).body,blob:{etag:'W/"revision-one"'}};},
    async head(){return {etag:'"revision-one"'};},
    async put(path,data,o){options=o;return {etag:'"revision-two"'};}
  });
  await s.load();await s.save(s.value);
  assert.equal(options.ifMatch,'"revision-one"');assert.equal(s.etag,'"revision-two"');
});
test('cloud storage rereads content if HEAD and GET describe different versions',async()=>{
  let reads=0;
  const s=new CloudStore('test',{
    async get(){reads++;return {stream:new Response(JSON.stringify(initial())).body,blob:{etag:reads===1 ? '"one"' : '"two"'}};},
    async head(){return {etag:'"two"'};},async put(){throw new Error('not used');}
  });
  await s.load();assert.equal(reads,2);assert.equal(s.etag,'"two"');
});

test('any private user can start and gets an independent dashboard',async()=>{
  const h=harness();
  await h.bot.handle({update_id:1,message:{text:'/start',from:{id:999,is_bot:false},chat:{id:999,type:'private'}}});
  assert.equal(h.bot.record.viewers['999'].messageId,42);
  assert.equal(h.bot.record.messageId,null);
  assert.equal(h.calls.find(c=>c.method==='sendMessage').body.chat_id,'999');
});
test('a forged sender or group cannot subscribe or view telemetry',async()=>{
  const h=harness();const u=update(1,'home',999);u.callback_query.from.id=123;
  await h.bot.handle(u);assert.equal(h.calls.length,0);assert.equal(Object.keys(h.bot.record.viewers).length,0);
  const g=update(2,'home',999);g.callback_query.message.chat.type='group';
  await h.bot.handle(g);assert.equal(h.calls.length,0);
});
test('parameter browsing for one viewer does not change the owner panel',async()=>{
  const h=harness();h.bot.record.messageId=15;
  await h.bot.handle(update(1,'all:0',999));
  assert.equal(h.bot.record.view,'home');assert.equal(h.bot.record.messageId,15);
  assert.equal(h.bot.record.viewers['999'].view,'all:0');
  const send=h.calls.find(c=>c.method==='sendMessage');assert.equal(send.body.chat_id,'999');
});
test('public viewers cannot forge owner threshold buttons',async()=>{
  const h=harness();h.bot.record.messageId=42;
  await h.bot.handle(update(1,'soc:up',999));
  assert.equal(h.bot.record.settings.lowSoc,20);
  await h.bot.handle(update(2,'load:up',999));
  assert.equal(h.bot.record.settings.highWatts,3000);
});
test('public notification toggle and stop affect only that subscriber',async()=>{
  const h=harness();h.bot.record.messageId=42;
  await h.bot.handle(update(1,'home',999));
  await h.bot.handle(update(2,'myalerts:toggle',999));
  assert.equal(h.bot.record.viewers['999'].alerts,false);assert.equal(h.bot.record.settings.alerts,true);
  await h.bot.handle(update(3,'myalerts:toggle',999));
  assert.equal(h.bot.record.viewers['999'].alerts,true);
  await h.bot.handle({update_id:4,message:{text:'/stop',from:{id:999,is_bot:false},chat:{id:999,type:'private'}}});
  assert.equal(h.bot.record.viewers['999'].alerts,false);assert.equal(h.bot.record.settings.alerts,true);
});
test('alarm fans out to active subscribers and owner without sending to unsubscribed users',async()=>{
  const h=harness(sample(0,19,3200));
  h.bot.record.viewers={'999':{alerts:true},'888':{alerts:false}};
  await h.bot.tick();await h.bot.deliver();
  const sends=h.calls.filter(c=>c.method==='sendMessage').map(c=>c.body.chat_id);
  assert.ok(sends.includes('123'));assert.ok(sends.includes('999'));assert.ok(!sends.includes('888'));
  assert.equal(h.bot.record.queue.length,0);
});
test('a blocked subscriber does not block delivery to the owner or another subscriber',async()=>{
  const h=harness();h.bot.record.viewers={'999':{alerts:true},'888':{alerts:true}};
  h.bot.record.queue=[{id:'a',title:'Alarm',detail:'test',at:NOW,measuredAt:NOW,recipients:['999','123','888']}];
  h.bot.telegram.call=async(method,body)=>{h.calls.push({method,body});if(body.chat_id==='999') throw new ApiError('blocked',403);return {};};
  await h.bot.deliver();
  assert.equal(h.bot.record.viewers['999'].alerts,false);
  assert.equal(h.bot.record.queue.length,0);
  assert.ok(h.calls.some(c=>c.body.chat_id==='888'));
});
test('legacy queued alarms stay addressed to the owner only',async()=>{
  const h=harness();h.bot.record.viewers={'999':{alerts:true}};
  h.bot.record.queue=[{id:'a',title:'Old',detail:'test',at:NOW,measuredAt:NOW}];
  await h.bot.deliver();
  assert.deepEqual(h.calls.filter(c=>c.method==='sendMessage').map(c=>c.body.chat_id),['123']);
});
test('schedule dashboard is independent of Deye freshness and distinguishes missing tomorrow',()=>{
  const now=Date.parse('2026-11-28T10:00:00Z');
  const record={...initial(),outageSchedule:{fresh:true,fetchedAt:now,data:{days:[{date:'2026-11-28',publishedAt:now,intervals:[]}]}}};
  const page=render('schedule',null,record,now);
  assert.match(page.text,/Сьогодні · 28.11.2026/);assert.match(page.text,/Відключень за опублікованим графіком немає/);
  assert.match(page.text,/Завтра · 29.11.2026\nГрафік ще не отримано/);
  assert.doesNotMatch(page.text,/логер офлайн/);
  assert.ok(render('home',null,record,now).reply_markup.inline_keyboard.flat().some(b=>b.callback_data==='schedule'));
});
test('owner and public viewer can open and refresh schedule in their own dashboard',async()=>{
  const calls=[],store={value:initial(),async save(){}},schedule={reads:0,async tick(){this.reads++;},current(){return {fresh:false};}};
  const bot=new Bot({store,chat:123,deye:{async read(){throw new Error('must not query Deye');}},telegram:{async call(method,body){calls.push({method,body});return {message_id:42};}},schedule,now:()=>NOW});
  for(const [id,who,action] of [[1,123,'schedule'],[2,456,'schedule_refresh']])await bot.handle(update(id,action,who));
  assert.equal(schedule.reads,2);assert.equal(bot.record.view,'schedule');assert.equal(bot.record.viewers['456'].view,'schedule');
  assert.equal(calls.filter(c=>c.method==='sendMessage'&&c.body.text.includes('Графік відключень')).length,2);
  assert.equal(bot.record.boilerPaused,undefined);
});
