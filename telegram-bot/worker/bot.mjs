import { randomUUID } from 'node:crypto';
import { authorized, privateChatId, clock, defaults, escapeHtml, evolve, metrics, render } from './core.mjs';
import { ApiError } from './api.mjs';
export class Bot {
  constructor({store,deye,telegram,chat,now=()=>Date.now(),pollSeconds=15}) {
    Object.assign(this,{store,deye,telegram,chat,now});this.record=store.value;this.snapshot=this.record.snapshot ?? null;
    this.record.viewers ??= {};this.record.deliveryCooldowns ??= {};
    this.nextDeye=0;this.nextRender=0;this.nextDelivery=0;this.lastSaved=0;this.pollSeconds=pollSeconds;
  }
  panel(chat) {
    return String(chat)===String(this.chat) ? this.record :
      (this.record.viewers[chat] ??= {messageId:null,view:'home',viewUntil:0,alerts:true});
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
        if(this.record.settings.alerts) {
          item.recipients=[String(this.chat),...Object.entries(this.record.viewers).filter(([,v])=>v.alerts).map(([id])=>id)];
          this.record.queue=[...this.record.queue,item].slice(-200);
        }
      }
      // Do not write every poll merely because lastValid changed.
      const relevant=s=>{const v=JSON.parse(s || '{}');delete v.lastValid;return JSON.stringify(v);};
      if(newReading || result.events.length || relevant(before)!==relevant(JSON.stringify(result.state)) || now-this.lastSaved>=300000) await this.persist();
    }
    await this.deliver();
    if(now>=this.nextRender) {
      this.nextRender=now+60000;
      // Owner panel and every public viewer have separate message IDs and pages.
      for(const chat of [String(this.chat),...Object.keys(this.record.viewers)]) {
        const panel=this.panel(chat);
        if(!panel.messageId) continue;
        if(panel.viewUntil<now) panel.view='home';
        await this.show(panel.view,{automatic:true,chat});
      }
    }
  }
  async deliver() {
    if(this.now()<this.nextDelivery) return;
    for(let count=0;count<5;count++) {
      let found;
      for(const item of [...this.record.queue]) {
        // Messages queued before public access remain addressed only to the owner.
        item.recipients ??= [String(this.chat)];
        if(this.now()-item.at>86400000) {
          this.record.queue=this.record.queue.filter(e=>e.id!==item.id);await this.persist();continue;
        }
        const target=item.recipients.find(id=>(this.record.deliveryCooldowns[id] ?? 0)<=this.now());
        if(target) {found={item,target};break;}
      }
      if(!found) return;
      const {item,target}=found;
      try {
        await this.telegram.call('sendMessage',{chat_id:target,text:item.title+'\n'+item.detail+'\n\nПодія: '+clock(item.at)+'\nВимір Deye: '+clock(item.measuredAt)+(this.now()-item.at>60000 ? '\nДоставлено із затримкою: це стан на момент події.' : '')});
        item.recipients=item.recipients.filter(id=>id!==target);
        this.record.queue=this.record.queue.filter(e=>e.recipients?.length);
        delete this.record.deliveryCooldowns[target];this.record.deliveryError=null;await this.persist();
      } catch(e) {
        if(e.code===403) {
          if(this.record.viewers[target]) this.record.viewers[target].alerts=false;
          item.recipients=item.recipients.filter(id=>id!==target);
          this.record.queue=this.record.queue.filter(e=>e.recipients?.length);
        } else this.record.deliveryCooldowns[target]=this.now()+Math.max(30,e.retryAfter || 0)*1000;
        if(e.code===429) this.nextDelivery=this.now()+Math.max(30,e.retryAfter || 0)*1000;
        this.record.deliveryError=e instanceof ApiError ? e.message : 'Помилка доставки Telegram';
        await this.persist();
        if(e.code===429) return;
      }
    }
  }
  async show(view='home',{automatic=false,chat=this.chat}={}) {
    const panel=this.panel(chat),owner=String(chat)===String(this.chat);
    const content=render(view,this.snapshot,owner ? this.record : {...this.record,settings:{...this.record.settings,alerts:this.record.settings.alerts && panel.alerts}},this.now());
    if(!owner) {
      // Public readers can control only their own notification subscription.
      if(view==='settings') {
        content.text='<b>🔔 Мої сповіщення</b>\n\n'+(panel.alerts ? 'Увімкнені' : 'Призупинені')+
          '\n\nПороги власника: АКБ ≤ '+this.record.settings.lowSoc+'%, навантаження без мережі ≥ '+this.record.settings.highWatts+' Вт.'+
          '\nЗагальні пороги змінює лише власник інвертора.'+(this.record.settings.alerts ? '' : '\nВласник призупинив загальні сигнали.');
        content.reply_markup.inline_keyboard=[
          [{text:panel.alerts ? '🔕 Вимкнути мої сигнали' : '🔔 Увімкнути мої сигнали',callback_data:'myalerts:toggle'}],
          [{text:'🏠 Дашборд',callback_data:'home'}]];
      } else for(const row of content.reply_markup.inline_keyboard) for(const button of row)
        if(button.callback_data==='settings') button.text='🔔 Мої сигнали';
    }
    try {
      if(panel.messageId) {
        try {await this.telegram.call('editMessageText',{chat_id:chat,message_id:panel.messageId,...content});return;}
        catch(e) {
          if(e.code===400 && /message is not modified/i.test(e.message)) return;
          if(e.code===403 && !owner) {panel.messageId=null;panel.alerts=false;await this.persist();return;}
          if(!(e.code===400 && /message to edit not found|message can't be edited/i.test(e.message))) throw e;
          panel.messageId=null;await this.persist();if(automatic) return;
        }
      }
      if(!automatic) {
        const message=await this.telegram.call('sendMessage',{chat_id:chat,...content,disable_notification:true});
        panel.messageId=message.message_id;await this.persist();
      }
    } catch(e) {if(!automatic) throw e;this.nextRender=this.now()+Math.max(60,e.retryAfter || 0)*1000;}
  }
  async handle(update) {
    if(update.update_id<=this.record.cursor) return;
    const chat=privateChatId(update);
    if(!chat) {this.record.cursor=update.update_id;await this.persist();return;}
    const owner=authorized(update,this.chat);
    const cb=update.callback_query;
    // Never interpret arbitrary text as a control command or a Deye setting.
    let action=cb?.data;
    if(cb) await this.telegram.call('answerCallbackQuery',{callback_query_id:cb.id,text:'Оновлюю…'}).catch(()=>{});
    else {
      const command=(update.message.text || '').trim().split(/\s/)[0].split('@')[0];
      action=({'/start':'home','/dashboard':'home','/status':'home','/battery':'battery:0','/grid':'grid:0','/solar':'solar:0','/parameters':'all:0','/events':'events','/settings':'settings','/help':'help','/stop':'stop'})[command];
    }
    if(!action) {this.record.cursor=update.update_id;await this.persist();return;}
    const panel=this.panel(chat);
    if(action==='stop') {
      if(owner) {this.record.settings.alerts=false;this.record.queue=[];}
      else panel.alerts=false;
      // Remove outstanding deliveries only for the person unsubscribing.
      for(const item of this.record.queue) item.recipients=(item.recipients ?? [String(this.chat)]).filter(id=>id!==chat);
      this.record.queue=this.record.queue.filter(item=>item.recipients.length);
      action='settings';
    }
    if(action==='myalerts:toggle' && !owner) {
      if(cb?.message?.message_id===panel.messageId) {
        panel.alerts=!panel.alerts;
        if(!panel.alerts) {
          for(const item of this.record.queue) item.recipients=(item.recipients ?? [String(this.chat)]).filter(id=>id!==chat);
          this.record.queue=this.record.queue.filter(item=>item.recipients.length);
        }
      }
      action='settings';
    }
    // Settings buttons are bound to the active panel and carry no inverter commands.
    if(['alerts:toggle','soc:up','soc:down','load:up','load:down'].includes(action)) {
      if(!owner || !cb || cb.message?.message_id!==this.record.messageId) action='settings';
      else {
        const s=this.record.settings;
        if(action==='alerts:toggle') {s.alerts=!s.alerts;if(!s.alerts) this.record.queue=[];}
        if(action.startsWith('soc:')) s.lowSoc=Math.max(5,Math.min(95,s.lowSoc+(action.endsWith('up') ? 5 : -5)));
        if(action.startsWith('load:')) s.highWatts=Math.max(500,Math.min(20000,s.highWatts+(action.endsWith('up') ? 500 : -500)));
        // Reset threshold latches so a newly selected threshold is evaluated.
        if(action.startsWith('soc:')) this.record.alarm.low=false;
        if(action.startsWith('load:')) this.record.alarm.high=false;
        action='settings';
      }
    }
    if(action==='help') {
      await this.telegram.call('sendMessage',{chat_id:chat,text:'⚡ Deye Pulse у Telegram\n\n/dashboard — дашборд\n/parameters — усі параметри\n/events — події\n/settings — пороги та сповіщення\n\nПанель оновлюється одним повідомленням. Кнопка «Оновити» запитує останній доступний вимір хмари; вона не прискорює завантаження логера. Застарілі дані позначаються окремо.'});
      action='home';
    }
    if(action==='refresh') {if(this.nextDeye-this.now()<=this.pollSeconds*1000-5000) {this.nextDeye=0;await this.tick();}action='home';}
    if(!['home','events','history','settings'].includes(action) && !/^(all|battery|grid|solar|load|temp|other):\d{1,3}$/.test(action)) action='home';
    panel.view=action;panel.viewUntil=this.now()+120000;
    // Persist command effects and cursor together before any fallible panel edit.
    this.record.cursor=update.update_id;await this.persist();
    await this.show(action,{chat});
  }
}
