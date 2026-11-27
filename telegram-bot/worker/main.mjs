import { createServer } from 'node:http';
import { setTimeout as pause } from 'node:timers/promises';
import { config, Telegram, Deye } from './api.mjs';
import { CloudStore, BlobPreconditionFailedError } from './store.mjs';
import { Bot } from './bot.mjs';
import { tuyaConfig, TuyaClient } from './tuya.mjs';
import { BoilerGuard } from './boiler.mjs';
import { ScheduleClient } from './schedule.mjs';
const settings=config();
const stop=new AbortController();
process.on('SIGTERM',()=>stop.abort());process.on('SIGINT',()=>stop.abort());
let heartbeat=Date.now();
const server=createServer((req,res)=>{res.writeHead(req.url==='/health' && Date.now()-heartbeat<180000 ? 200 : 503);res.end('Deye bot');}).listen(1234,'0.0.0.0');
const sleep=ms=>pause(ms,undefined,{signal:stop.signal}).catch(()=>{});
// Give the old daemon time to drain during a production rollout.
await sleep(Number(process.env.STARTUP_DELAY_SECONDS ?? 35)*1000);
if(stop.signal.aborted) {server.close();process.exit(0);}
const telegram=new Telegram(settings.token),deye=new Deye(settings),store=new CloudStore();
await store.load();
const webhook=await telegram.call('getWebhookInfo');
if(webhook.url) throw new Error('Бот уже має webhook. Спочатку узгодь перенесення на цей процес.');
const schedule=new ScheduleClient();
const bot=new Bot({store,deye,telegram,chat:settings.chat,pollSeconds:settings.poll,schedule});
let boiler;
try {const c=tuyaConfig();if(c) boiler=new BoilerGuard(new TuyaClient(c),()=>Date.now(),schedule);}
catch {bot.record.boiler={mode:'unknown',message:'Помилка конфігурації Tuya; автовимкнення не запущене.'};console.error('Boiler configuration invalid');}
const inbox=[];
// One poller, sequential handling, durable offset. No parallel getUpdates consumer.
const input=(async()=>{
  while(!stop.signal.aborted) {
    try {
      const updates=await telegram.call('getUpdates',{offset:bot.record.cursor+1,timeout:25,limit:20,allowed_updates:['message','callback_query']},35000);
      for(const update of updates) {
        if(stop.signal.aborted) break;
        await new Promise(resolve=>inbox.push({update,resolve}));
      }
    } catch(e) {console.error('Telegram polling unavailable',e.code || 'NETWORK');await sleep(Math.max(5,e.retryAfter || 0)*1000);}
  }
})();
// Tuya checks must not wait for Deye requests or Telegram deliveries.
// CloudStore serializes writes from both loops using the current ETag.
let boilerFailure;
const scheduleLoop=(async()=>{
  while(!stop.signal.aborted){
    await schedule.tick();
    await sleep(1000);
  }
})();
const boilerLoop=(async()=>{
  while(!stop.signal.aborted) {
    try {if(boiler) await boiler.tick(bot);}
    catch(e) {
      if(e instanceof BlobPreconditionFailedError) {boilerFailure=e;stop.abort();return;}
      console.error('Boiler temporarily unavailable',e.code || 'NETWORK');
    }
    await sleep(1000);
  }
})();
console.log('Deye Telegram worker started');
try {
  while(!stop.signal.aborted) {
    heartbeat=Date.now();
    const entry=inbox.shift();
    if(entry) {
      try {await bot.handle(entry.update);} catch(e) {
        if(e instanceof BlobPreconditionFailedError) throw e;
        console.error('Command failed',e.code || 'NETWORK');
      } finally {entry.resolve();}
    }
    try {await bot.tick();} catch(e) {
      if(e instanceof BlobPreconditionFailedError) throw e;
      console.error('Monitor temporarily unavailable',e.code || 'NETWORK');
    }
    await sleep(1000);
  }
} finally {
  stop.abort();for(const entry of inbox) entry.resolve();
  await Promise.all([boilerLoop,scheduleLoop]);
  if(!boilerFailure) await store.save(bot.record).catch(()=>{});server.close();
}
// Pending bounded long-poll is safe to abandon on shutdown.
if(boilerFailure) throw boilerFailure;
void input;process.exit(0);
