import { timingSafeEqual } from 'node:crypto';
import { config, Telegram, Deye } from '../worker/api.mjs';
import { CloudStore } from '../worker/store.mjs';
import { Bot } from '../worker/bot.mjs';
import { metrics } from '../worker/core.mjs';
import { tuyaConfig, TuyaClient } from '../worker/tuya.mjs';
export default async function handler(req,res) {
  res.setHeader('Cache-Control','no-store');
  const expected=Buffer.from('Bearer '+(process.env.ADMIN_SECRET || ''));
  const actual=Buffer.from(req.headers.authorization || '');
  if(!process.env.ADMIN_SECRET || actual.length!==expected.length || !timingSafeEqual(actual,expected)) return res.status(404).json({error:'Not found'});
  if(req.method!=='POST') return res.status(405).json({error:'POST required'});
  try {
    if(req.body?.action==='tuya') {
      const c=tuyaConfig();if(!c) return res.status(200).json({configured:false});
      const api=new TuyaClient(c),relay=await api.details(c.relayId),plug=await api.details(c.plugId);
      await api.validatePlug();const plugOn=await api.plugState();
      return res.status(200).json({configured:true,relay,plug,plugOn,readOnly:true});
    }
    const c=config();const telegram=new Telegram(c.token),deye=new Deye(c),store=new CloudStore();
    await store.load();
    const me=await telegram.call('getMe');
    const snapshot=await deye.read();
    if(req.body?.action==='open') {
      const bot=new Bot({store,deye,telegram,chat:c.chat});bot.snapshot=snapshot;bot.record.snapshot=snapshot;
      await bot.show('home');
      await telegram.call('setMyCommands',{scope:{type:'chat',chat_id:c.chat},commands:[
        {command:'dashboard',description:'Дашборд Deye'},{command:'parameters',description:'Усі параметри'},
        {command:'battery',description:'Показники АКБ'},{command:'events',description:'Останні події'},
        {command:'settings',description:'Пороги й сповіщення'},{command:'help',description:'Як користуватися'}]});
    }
    return res.status(200).json({ok:true,username:me.username,online:snapshot.online,measuredAt:snapshot.at,parameters:snapshot.points.length,metrics:metrics(snapshot),dashboardMessage:store.value.messageId});
  } catch(e) { return res.status(502).json({ok:false,error:e.code ? `API ${e.code}` : 'Backend connection failed'}); }
}
