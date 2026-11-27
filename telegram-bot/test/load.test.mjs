import test from 'node:test';
import assert from 'node:assert/strict';
import { BoilerGuard } from '../worker/boiler.mjs';
import { relayMeasurement, TuyaError } from '../worker/tuya.mjs';
const T=1800000000000;
function frame(watts=1659,time=T) {
  const b=Buffer.alloc(8);b.writeUInt16BE(2263);b.writeUIntBE(7320,2,3);b.writeUIntBE(watts,5,3);
  return [{code:'phase_a',value:b.toString('base64'),time},{code:'power',value:0,time:1}];
}
test('verified raw frame yields watts, volts and amps, ignoring stale numeric power',()=>{
  assert.deepEqual(relayMeasurement(frame(),T),{watts:1659,voltage:226.3,current:7.32,measuredAt:T});
});
test('unknown, malformed, stale, future, implausible or multiphase measurements never become zero',()=>{
  for(const p of [[],[{code:'phase_a',value:'garbage',time:T}],frame(1,T-90001),frame(1,T+10001),frame(10000),[...frame(),{code:'phase_b',value:'nonempty'}]])
    assert.throws(()=>relayMeasurement(p,T),TuyaError);
});
function fixture() {
  let now=T,on=true,online=true,watts=3800,error=false;const calls=[];
  const client={c:{loadControl:true,relayId:'relay',plugId:'plug'},
    async details(id){return {id,online:id==='relay'?online:true,category:id==='relay'?'dlq':'cz'};},
    async relayPower(){if(error)throw new TuyaError('POWER_STALE');return {watts,measuredAt:now};},
    async validatePlug(){},async plugState(){return on;},
    async off(){calls.push('off');on=false;},async on(){calls.push('on');on=true;}};
  const bot={chat:'owner',record:{queue:[]},async persist(){}};
  const guard=new BoilerGuard(client,()=>now);
  return {client,bot,guard,calls,set(x){if('watts'in x)watts=x.watts;if('online'in x)online=x.online;if('on'in x)on=x.on;if('error'in x)error=x.error;},async tick(ms=15000){now+=ms;await guard.tick(bot);}};
}
test('strictly above 3.7kW cuts off only the plug, 3.7kW does not',async()=>{
  const h=fixture();h.set({watts:3700});await h.tick();assert.deepEqual(h.calls,[]);
  h.set({watts:3701});await h.tick();assert.deepEqual(h.calls,['off']);assert.equal(h.bot.record.boiler.offReason,'overload');
});
test('load must stay below 1.5kW for full two minutes before restoration',async()=>{
  const h=fixture();await h.tick();h.set({watts:1499});await h.tick();
  for(let i=0;i<7;i++)await h.tick();assert.deepEqual(h.calls,['off']);
  await h.tick();assert.deepEqual(h.calls,['off','on']);assert.equal(h.bot.record.boiler.restoreNeeded,false);
});
test('1.5kW boundary resets low-load timer and cannot restart boiler',async()=>{
  const h=fixture();await h.tick();h.set({watts:1000});await h.tick();await h.tick(60000);
  h.set({watts:1500});await h.tick();assert.equal(h.bot.record.boiler.lowSince,null);
  h.set({watts:1000});await h.tick();for(let i=0;i<7;i++)await h.tick();assert.deepEqual(h.calls,['off']);
});
test('stale telemetry and observation gaps invalidate the restoration wait',async()=>{
  const h=fixture();await h.tick();h.set({watts:1000});await h.tick();await h.tick(60000);
  h.set({error:true});await h.tick();assert.equal(h.bot.record.boiler.lowSince,null);
  h.set({error:false});await h.tick();await h.tick(120000);assert.deepEqual(h.calls,['off']);
});
test('outage wins over low load, but outage cutoff works with unavailable power',async()=>{
  const h=fixture();h.set({online:false,error:true});await h.tick();assert.deepEqual(h.calls,['off']);
  h.set({online:true,error:false,watts:1000});await h.tick();
  for(let i=0;i<7;i++)await h.tick();h.set({online:false});await h.tick();assert.deepEqual(h.calls,['off']);
});
test('manually off boiler is not claimed or started by an overload',async()=>{
  const h=fixture();h.set({on:false});await h.tick();h.set({watts:1000});
  for(let i=0;i<10;i++)await h.tick();assert.deepEqual(h.calls,[]);assert.equal(h.bot.record.boiler.restoreNeeded,false);
});
test('load is rechecked immediately before ON and changing load cancels it',async()=>{
  const h=fixture();await h.tick();h.set({watts:1000});await h.tick();for(let i=0;i<7;i++)await h.tick();
  let reads=0;h.client.relayPower=async()=>({watts:++reads===1?1000:1700,measuredAt:T});await h.tick();
  assert.deepEqual(h.calls,['off']);assert.equal(h.bot.record.boiler.lowSince,null);
});
test('load is rechecked before OFF to reject a transient cloud peak',async()=>{
  const h=fixture();let reads=0;h.client.relayPower=async()=>({watts:++reads===1?3800:3000,measuredAt:T});
  await h.tick();assert.deepEqual(h.calls,[]);
});
test('new overload is evaluated after five seconds',async()=>{
  const h=fixture();h.set({watts:1000});await h.tick();
  h.set({watts:3800});await h.tick(4999);assert.deepEqual(h.calls,[]);
  await h.tick(1);assert.deepEqual(h.calls,['off']);
});
test('unconfirmed OFF retries after five seconds, not a minute',async()=>{
  const h=fixture();h.client.off=async()=>h.calls.push('off');
  await h.tick();assert.equal(h.bot.record.boiler.mode,'unconfirmed');
  await h.tick(5000);assert.deepEqual(h.calls,['off','off']);
});
