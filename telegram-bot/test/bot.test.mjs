import test from 'node:test';
import assert from 'node:assert/strict';
import { authorized, defaults, evolve, fresh, metrics, parseDevice, render } from '../worker/core.mjs';
import { Deye, Telegram, ApiError } from '../worker/api.mjs';
import { Bot } from '../worker/bot.mjs';
import { initial } from '../worker/store.mjs';
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
test('unauthorized updates cause no Telegram replies or configuration changes',async()=>{
  const h=harness();await h.bot.handle(update(1,'alerts:toggle',999));
  assert.equal(h.calls.length,0);assert.equal(h.bot.record.settings.alerts,true);
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
  failing=false;h.advance(30000);await h.bot.deliver();assert.equal(h.bot.record.queue.length,1);
  await h.bot.deliver();assert.equal(h.bot.record.queue.length,0);
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
