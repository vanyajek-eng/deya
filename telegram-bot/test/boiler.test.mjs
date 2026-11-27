import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash,createHmac } from 'node:crypto';
import { BoilerGuard } from '../worker/boiler.mjs';
import { TuyaClient,TuyaError,tuyaConfig,signedHeaders } from '../worker/tuya.mjs';
import { Bot } from '../worker/bot.mjs';
import { initial } from '../worker/store.mjs';
const NOW=1800000000000;
const CONFIG={clientId:'client',secret:'secret',base:'https://openapi.tuyaeu.com',relayId:'relay000000',plugId:'plug0000000',code:'switch_1'};
function fixture({relay=true,plugOnline=true,on=true,confirmed=true}={}) {
  let now=NOW,power=on;const calls=[];
  const client={c:CONFIG,async details(id){calls.push(['details',id]);return id===CONFIG.relayId ? {id,online:relay,category:'dlq'} : {id,online:plugOnline,category:'cz'};},
    async validatePlug(){calls.push(['validate']);},async plugState(){calls.push(['state']);return power;},async off(){calls.push(['off',CONFIG.plugId]);if(confirmed) power=false;},async on(){calls.push(['on',CONFIG.plugId]);if(confirmed) power=true;}};
  const bot={chat:'owner',record:{queue:[],boilerEvents:[],viewers:{other:{alerts:true}}},writes:[],async persist(){this.writes.push(structuredClone(this.record));}};
  const guard=new BoilerGuard(client,()=>now);
  return {client,bot,guard,calls,advance(ms){now+=ms;}};
}
test('automation is opt-in and rejects swapped identical target IDs',()=>{
  assert.equal(tuyaConfig({}),null);
  assert.throws(()=>tuyaConfig({TUYA_BOILER_ENABLED:'true',TUYA_CLIENT_ID:'a',TUYA_CLIENT_SECRET:'b',TUYA_GRID_RELAY_ID:'same0000000',TUYA_BOILER_PLUG_ID:'same0000000'}));
});
test('Tuya signature signs exact body, URL, token, timestamp and client ID',()=>{
  const path='/v1.0/token?grant_type=1',now=1700000000000;
  const header=signedHeaders(CONFIG,'GET',path,'','',now);
  const empty='e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
  assert.equal(header.sign,createHmac('sha256','secret').update('client'+now+'GET\n'+empty+'\n\n'+path).digest('hex').toUpperCase());
  assert.notEqual(header.sign,signedHeaders(CONFIG,'GET',path,'','access-token',now).sign);
});
test('online grid device causes no plug query or physical command',async()=>{
  const h=fixture();await h.guard.tick(h.bot);
  assert.deepEqual(h.calls,[['details',CONFIG.relayId]]);assert.equal(h.bot.record.boiler.mode,'grid_online');
});
test('offline grid switches only the verified boiler plug off and reads confirmation',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);
  assert.deepEqual(h.calls.filter(c=>c[0]==='off'),[['off',CONFIG.plugId]]);
  assert.equal(h.calls.filter(c=>c[0]==='details'&&c[1]===CONFIG.relayId).length,2);
  assert.equal(h.bot.record.boiler.mode,'off');
  assert.deepEqual(h.bot.record.queue[0].recipients,['owner']);assert.equal(h.bot.record.events,undefined);
});
test('already-off plug is not switched again',async()=>{
  const h=fixture({relay:false,on:false});await h.guard.tick(h.bot);
  assert.equal(h.calls.some(c=>c[0]==='off'),false);assert.equal(h.bot.record.boiler.mode,'off');
});
test('unknown relay API response or permission error is not an offline trigger',async()=>{
  for(const code of ['NETWORK','1106','INVALID_DEVICE']) {
    const h=fixture();h.client.details=async()=>{throw new TuyaError(code);};
    await h.guard.tick(h.bot);assert.equal(h.calls.some(c=>c[0]==='off'),false);assert.equal(h.bot.record.boiler.mode,'unknown');
  }
});
test('offline smart plug is not sent a command and the owner is warned',async()=>{
  const h=fixture({relay:false,plugOnline:false});await h.guard.tick(h.bot);
  assert.equal(h.calls.some(c=>c[0]==='off'),false);assert.equal(h.bot.record.boiler.error,'PLUG_OFFLINE');
  assert.equal(h.bot.record.queue.length,1);
});
test('API device category mismatch prevents switching either device',async()=>{
  const h=fixture({relay:false});h.client.details=async(id)=>({id,online:false,category:'cz'});
  await h.guard.tick(h.bot);assert.equal(h.calls.some(c=>c[0]==='off'),false);assert.equal(h.bot.record.boiler.error,'GRID_RELAY_TYPE_MISMATCH');
});
test('grid return during checks cancels the physical command',async()=>{
  const h=fixture({relay:false});let relayReads=0;
  h.client.details=async(id)=>id===CONFIG.relayId ? {id,category:'dlq',online:++relayReads>1} : {id,category:'cz',online:true};
  await h.guard.tick(h.bot);assert.equal(h.calls.some(c=>c[0]==='off'),false);
});
test('accepted command without off readback is explicitly unconfirmed',async()=>{
  const h=fixture({relay:false,confirmed:false});await h.guard.tick(h.bot);
  assert.equal(h.bot.record.boiler.mode,'unconfirmed');assert.equal(h.bot.record.boiler.confirmedAt,undefined);
});
test('polling runs every two minutes and pauses persist across restarts',async()=>{
  const h=fixture();await h.guard.tick(h.bot);const count=h.calls.length;
  h.advance(119999);await h.guard.tick(h.bot);assert.equal(h.calls.length,count);
  h.advance(1);await h.guard.tick(h.bot);assert.equal(h.calls.length,count+1);
  h.bot.record.boilerPaused=true;h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.calls.length,count+1);
});
test('grid recovery restores power only after two minutes and confirms ON',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);h.advance(120000);
  h.client.details=async(id)=>({id,online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);assert.equal(h.bot.record.boiler.mode,'waiting_grid');
  assert.equal(h.calls.some(c=>c[0]==='on'),false);
  h.advance(119999);await h.guard.tick(h.bot);assert.equal(h.calls.some(c=>c[0]==='on'),false);
  h.advance(1);await h.guard.tick(h.bot);assert.equal(h.bot.record.boiler.mode,'on');
  assert.equal(h.bot.record.boiler.restoreNeeded,false);
  assert.equal(h.calls.filter(c=>c[0]==='on').length,1);
  assert.deepEqual(h.bot.record.queue.at(-1).recipients,['owner']);
  h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.calls.filter(c=>c[0]==='on').length,1);
});
test('low-level command uses false only for the configured plug and cached token',async()=>{
  const calls=[];
  const client=new TuyaClient(CONFIG,async(url,options)=>{
    calls.push({url,options});
    const result=url.includes('/token?') ? {access_token:'access',expire_time:7200} : url.endsWith('/functions') ? {category:'cz',functions:[{code:'switch_1',type:'Boolean'}]} : true;
    return new Response(JSON.stringify({success:true,result}),{status:200});
  });
  await client.off();
  const command=calls.find(c=>c.options.method==='POST');
  assert.equal(command.url,CONFIG.base+'/v1.0/iot-03/devices/'+CONFIG.plugId+'/commands');
  assert.deepEqual(JSON.parse(command.options.body),{commands:[{code:'switch_1',value:false}]});
  assert.equal(calls.some(c=>c.url.includes(CONFIG.relayId)),false);
});
test('Tuya details reject a mismatching device ID and nonboolean online state',async()=>{
  for(const result of [{id:'wrong',online:false},{id:CONFIG.relayId,online:'false'}]) {
    const client=new TuyaClient(CONFIG,async(url)=>new Response(JSON.stringify({success:true,result:url.includes('/token?')?{access_token:'a'}:result})));
    await assert.rejects(client.details(CONFIG.relayId),e=>e.code==='INVALID_DEVICE');
  }
});
test('public Telegram users cannot pause or resume boiler automation',async()=>{
  const calls=[];const store={value:initial(),async save(v){this.value=v;}};
  const bot=new Bot({store,deye:{},telegram:{async call(...args){calls.push(args);return {}; }},chat:'123'});
  await bot.handle({update_id:1,message:{text:'/boiler_pause',from:{id:999,is_bot:false},chat:{id:999,type:'private'}}});
  assert.equal(bot.record.boilerPaused,undefined);assert.equal(calls.length,0);
  await bot.handle({update_id:2,message:{text:'/boiler_pause',from:{id:123,is_bot:false},chat:{id:123,type:'private'}}});
  assert.equal(bot.record.boilerPaused,true);assert.equal(calls.length,1);
});

test('first online observation does not start a manually off boiler',async()=>{
  const h=fixture({relay:true,on:false});await h.guard.tick(h.bot);h.advance(120000);await h.guard.tick(h.bot);
  assert.equal(h.calls.some(c=>c[0]==='on'),false);
});
test('plug already off before an outage is left off after grid returns',async()=>{
  const h=fixture({relay:false,on:false});await h.guard.tick(h.bot);h.advance(120000);
  h.client.details=async(id)=>({id,online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);h.advance(120000);await h.guard.tick(h.bot);
  assert.equal(h.bot.record.boiler.restoreNeeded,false);assert.equal(h.calls.some(c=>c[0]==='on'),false);
});
test('grid flapping resets the full recovery timer',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);h.advance(120000);
  let online=true;h.client.details=async(id)=>({id,online:id===CONFIG.relayId?online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);
  online=false;h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.bot.record.boiler.onlineSince,null);
  online=true;h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.calls.some(c=>c[0]==='on'),false);
  h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.calls.filter(c=>c[0]==='on').length,1);
});
test('API failure during recovery clears the timer and never enables power',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);h.advance(120000);
  const details=async(id)=>({id,online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  h.client.details=details;await h.guard.tick(h.bot);
  h.client.details=async()=>{throw new TuyaError('NETWORK');};
  h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.bot.record.boiler.onlineSince,null);
  h.client.details=details;h.advance(60000);await h.guard.tick(h.bot);
  assert.equal(h.calls.some(c=>c[0]==='on'),false);
  h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.calls.filter(c=>c[0]==='on').length,1);
});
test('pause during recovery discards the accumulated online time',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);h.advance(120000);
  h.client.details=async(id)=>({id,online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);h.bot.record.boilerPaused=true;
  h.advance(60000);await h.guard.tick(h.bot);assert.equal(h.bot.record.boiler.onlineSince,null);
  h.bot.record.boilerPaused=false;h.advance(60000);await h.guard.tick(h.bot);
  assert.equal(h.calls.some(c=>c[0]==='on'),false);
  h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.calls.filter(c=>c[0]==='on').length,1);
});
test('a long gap in observations cannot count as stable online time',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);h.advance(120000);
  h.client.details=async(id)=>({id,online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);h.advance(240000);await h.guard.tick(h.bot);
  assert.equal(h.calls.some(c=>c[0]==='on'),false);assert.equal(h.bot.record.boiler.mode,'waiting_grid');
});
test('last-second grid loss cancels the ON command',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);h.advance(120000);
  h.client.details=async(id)=>({id,online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);h.advance(120000);
  let reads=0;h.client.details=async(id)=>({id,online:id===CONFIG.relayId?++reads===1:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);assert.equal(h.calls.some(c=>c[0]==='on'),false);
  assert.equal(h.bot.record.boiler.onlineSince,null);
});
test('ON without positive readback remains unconfirmed',async()=>{
  const h=fixture({relay:false});await h.guard.tick(h.bot);h.advance(120000);
  h.client.details=async(id)=>({id,online:true,category:id===CONFIG.relayId?'dlq':'cz'});
  await h.guard.tick(h.bot);h.advance(120000);
  h.client.on=async()=>{h.calls.push(['on',CONFIG.plugId]);};
  await h.guard.tick(h.bot);assert.equal(h.bot.record.boiler.mode,'on_unconfirmed');
  assert.equal(h.bot.record.boiler.onConfirmedAt,undefined);assert.equal(h.bot.record.boiler.restoreNeeded,true);
});
test('on method sends true only to the verified boiler switch',async()=>{
  const calls=[];const client=new TuyaClient(CONFIG,async(url,options)=>{
    calls.push({url,options});const result=url.includes('/token?')?{access_token:'a'}:url.endsWith('/functions')?{category:'cz',functions:[{code:'switch_1',type:'Boolean'}]}:true;
    return new Response(JSON.stringify({success:true,result}));
  });
  await client.on();const command=calls.find(c=>c.options.method==='POST');
  assert.equal(command.url,CONFIG.base+'/v1.0/iot-03/devices/'+CONFIG.plugId+'/commands');
  assert.deepEqual(JSON.parse(command.options.body),{commands:[{code:'switch_1',value:true}]});
});
test('outstanding shutdown from the off-only version resumes through the full timer',async()=>{
  const h=fixture({relay:true,on:false});
  h.bot.record.boiler={mode:'off',relayOnline:false,commandAt:NOW-120000,confirmedAt:NOW-119000,checkedAt:NOW-120000};
  await h.guard.tick(h.bot);
  assert.equal(h.bot.record.boiler.restoreNeeded,true);assert.equal(h.bot.record.boiler.mode,'waiting_grid');
  assert.equal(h.calls.some(c=>c[0]==='on'),false);
  h.advance(120000);await h.guard.tick(h.bot);assert.equal(h.bot.record.boiler.mode,'on');
});
