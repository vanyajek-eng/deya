import { randomUUID } from 'node:crypto';
import { authorized, clock, defaults, escapeHtml, evolve, metrics, render } from './core.mjs';
import { ApiError } from './api.mjs';
export class Bot {
  constructor({store,deye,telegram,chat,now=()=>Date.now(),pollSeconds=15}) {
    Object.assign(this,{store,deye,telegram,chat,now});this.record=store.value;this.snapshot=this.record.snapshot ?? null;
    this.nextDeye=0;this.nextRender=0;this.nextDelivery=0;this.lastSaved=0;this.pollSeconds=pollSeconds;
  }
  async persist() { await this.store.save(this.record);this.lastSaved=this.now(); }
  async tick() {
    const now=this.now();
    if(now>=this.nextDeye) {
      this.nextDeye=now+this.pollSeconds*1000;
      const before=JSON.stringify(this.record.alarm);
      let snapshot;
      try {
        snapshot=await this.deye.read();
        if(snapshot.at<(this.snapshot?.at ?? 0)) throw new ApiError('Deye повернув старіший вимір');
        this.snapshot=snapshot;this.record.fetchError=null;
      }
      catch(e) {snapshot=undefined;this.record.fetchError=e instanceof ApiError ? e.message : 'Не вдалося отримати Deye Cloud';}
      const result=evolve(this.record.alarm,snapshot,this.record.settings,now);
      this.record.alarm=result.state;
      const newReading=snapshot && snapshot.online && snapshot.at>0 && snapshot.at<=now+60000 && snapshot.at>(this.record.snapshot?.at ?? 0);
      if(newReading) {
        this.record.snapshot=snapshot;
        const m=metrics(snapshot);
        this.record.history=[...(this.record.history ?? []),{at:snapshot.at,soc:m.soc,load:m.load,grid:m.grid}].slice(-288);
      }
      for(const event of result.events) {
        const item={...event,id:randomUUID(),at:now,measuredAt:snapshot?.at ?? 0};
        this.record.events=[item,...this.record.events].slice(0,100);
        if(this.record.settings.alerts) this.record.queue=[...this.record.queue,item].slice(-200);
      }
      // Do not write every poll merely because lastValid changed.
      const relevant=s=>{const v=JSON.parse(s || '{}');delete v.lastValid;return JSON.stringify(v);};
      if(newReading || result.events.length || relevant(before)!==relevant(JSON.stringify(result.state)) || now-this.lastSaved>=300000) await this.persist();
    }
    await this.deliver();
    if(this.record.messageId && now>=this.nextRender) {
      this.nextRender=now+60000;
      if(this.record.viewUntil<now) this.record.view='home';
      await this.show(this.record.view,{automatic:true});
    }
  }
  async deliver() {
    if(this.now()<this.nextDelivery) return;
    const item=this.record.queue[0];if(!item) return;
    if(this.now()-item.at>86400000) {this.record.queue.shift();await this.persist();return;}
    try {
      await this.telegram.call('sendMessage',{chat_id:this.chat,text:`${item.title}\n${item.detail}\n\nПодія: ${clock(item.at)}\nВимір Deye: ${clock(item.measuredAt)}${this.now()-item.at>60000 ? '\nДоставлено із затримкою: це стан на момент події.' : ''}`});
      this.record.queue.shift();this.record.deliveryError=null;await this.persist();
    } catch(e) {
      this.nextDelivery=this.now()+Math.max(30,e.retryAfter || 0)*1000;
      const error=e instanceof ApiError ? e.message : 'Помилка доставки Telegram';
      if(this.record.deliveryError!==error) {this.record.deliveryError=error;await this.persist();}
    }
  }
  async show(view='home',{automatic=false}={}) {
    const content=render(view,this.snapshot,this.record,this.now());
    try {
      if(this.record.messageId) {
        try {await this.telegram.call('editMessageText',{chat_id:this.chat,message_id:this.record.messageId,...content});return;}
        catch(e) {
          if(e.code===400 && /message is not modified/i.test(e.message)) return;
          if(!(e.code===400 && /message to edit not found|message can't be edited/i.test(e.message))) throw e;
          this.record.messageId=null;
          // Recreate a deleted dashboard only after an explicit user action.
          await this.persist();if(automatic) return;
        }
      }
      if(!automatic) {
        const message=await this.telegram.call('sendMessage',{chat_id:this.chat,...content,disable_notification:true});
        this.record.messageId=message.message_id;await this.persist();
      }
    } catch(e) {if(!automatic) throw e;this.nextRender=this.now()+Math.max(60,e.retryAfter || 0)*1000;}
  }
  async handle(update) {
    if(update.update_id<=this.record.cursor) return;
    if(!authorized(update,this.chat)) {this.record.cursor=update.update_id;await this.persist();return;}
    const cb=update.callback_query;
    // Never interpret arbitrary text as a control command or a Deye setting.
    let action=cb?.data;
    if(cb) await this.telegram.call('answerCallbackQuery',{callback_query_id:cb.id,text:'Оновлюю…'}).catch(()=>{});
    else {
      const command=(update.message.text || '').trim().split(/\s/)[0].split('@')[0];
      action=({'/start':'home','/dashboard':'home','/status':'home','/battery':'battery:0','/grid':'grid:0','/solar':'solar:0','/parameters':'all:0','/events':'events','/settings':'settings','/help':'help'})[command];
    }
    if(!action) {this.record.cursor=update.update_id;await this.persist();return;}
    // Settings buttons are bound to the active panel and carry no inverter commands.
    if(['alerts:toggle','soc:up','soc:down','load:up','load:down'].includes(action)) {
      if(!cb || cb.message?.message_id!==this.record.messageId) action='settings';
      else {
        const s=this.record.settings;
        if(action==='alerts:toggle') s.alerts=!s.alerts;
        if(action.startsWith('soc:')) s.lowSoc=Math.max(5,Math.min(95,s.lowSoc+(action.endsWith('up') ? 5 : -5)));
        if(action.startsWith('load:')) s.highWatts=Math.max(500,Math.min(20000,s.highWatts+(action.endsWith('up') ? 500 : -500)));
        // Reset threshold latches so a newly selected threshold is evaluated.
        if(action.startsWith('soc:')) this.record.alarm.low=false;
        if(action.startsWith('load:')) this.record.alarm.high=false;
        action='settings';
      }
    }
    if(action==='help') {
      await this.telegram.call('sendMessage',{chat_id:this.chat,text:'⚡ Deye Pulse у Telegram\n\n/dashboard — дашборд\n/parameters — усі параметри\n/events — події\n/settings — пороги та сповіщення\n\nПанель оновлюється одним повідомленням. Кнопка «Оновити» запитує останній доступний вимір хмари; вона не прискорює завантаження логера. Застарілі дані позначаються окремо.'});
      action='home';
    }
    if(action==='refresh') {this.nextDeye=0;await this.tick();action='home';}
    if(!['home','events','history','settings'].includes(action) && !/^(all|battery|grid|solar|load|temp|other):\d{1,3}$/.test(action)) action='home';
    this.record.view=action;this.record.viewUntil=this.now()+120000;
    // Persist command effects and cursor together before any fallible panel edit.
    this.record.cursor=update.update_id;await this.persist();
    await this.show(action);
  }
}
