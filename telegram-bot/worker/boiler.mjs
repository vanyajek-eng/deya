import { randomUUID } from 'node:crypto';
import { clock } from './core.mjs';
import { TuyaError } from './tuya.mjs';

export function boilerText(record) {
  const s=record.boiler;
  return '♨️ Автовимкнення бойлера\n\n'+(record.boilerPaused ? '⏸ Перевірки призупинені власником.' : s?.message || 'Tuya ще не налаштована або перша перевірка ще не виконана.')+
    (s?.checkedAt ? '\nОстання перевірка: '+clock(s.checkedAt) : '')+
    '\n\nПеревірка Breaker кожні 5 хвилин. Якщо він офлайн — вимикаємо лише Smart Plug +. Автоматичного ввімкнення немає.'+
    '\n/boiler_pause — призупинити\n/boiler_resume — відновити';
}
export class BoilerGuard {
  constructor(client,now=()=>Date.now()) {this.client=client;this.now=now;this.next=0;}
  notify(bot,title,detail) {
    const item={id:randomUUID(),kind:'boiler',source:'Tuya',title,detail,at:this.now(),measuredAt:this.now(),recipients:[String(bot.chat)]};
    bot.record.queue=[...bot.record.queue,item].slice(-200);
    bot.record.boilerEvents=[item,...(bot.record.boilerEvents ?? [])].slice(0,50);
  }
  async tick(bot) {
    if(!this.client || bot.record.boilerPaused || this.now()<this.next) return;
    const now=this.now(),before=bot.record.boiler ?? {};
    this.next=now+300000;
    const s={...before,checkedAt:now,error:null};bot.record.boiler=s;
    try {
      let relay=await this.client.details(this.client.c.relayId);
      if(relay.category!=='dlq') throw new TuyaError('GRID_RELAY_TYPE_MISMATCH');
      s.relayOnline=relay.online;
      if(relay.online) {
        s.mode='grid_online';s.message='🟢 Breaker онлайн. Бойлер автоматично не вмикаємо.';
        if(!before.checkedAt) this.notify(bot,'♨️ Автоматизацію бойлера ввімкнено','Перевірятиму Breaker кожні 5 хвилин. Якщо він офлайн — вимкну лише Smart Plug +. Автоматичного ввімкнення немає.');
        if(before.relayOnline===false) this.notify(bot,'💡 Breaker знову онлайн','Автовимкнення більше не потрібне. Бойлер залишено в його поточному стані.');
        await bot.persist();return;
      }
      const plug=await this.client.details(this.client.c.plugId);
      if(plug.category!=='cz') throw new TuyaError('BOILER_PLUG_TYPE_MISMATCH');
      s.plugOnline=plug.online;
      if(!plug.online) throw new TuyaError('PLUG_OFFLINE');
      await this.client.validatePlug();
      const on=await this.client.plugState();
      if(!on) {
        s.mode='off';s.confirmedAt=now;s.message='🔴 Breaker офлайн. Розетка бойлера підтверджує: вимкнено.';
        if(!before.checkedAt) this.notify(bot,'♨️ Автоматизацію бойлера ввімкнено','Breaker офлайн, але розетка бойлера вже вимкнена. Продовжую перевірки кожні 5 хвилин.');
        await bot.persist();return;
      }
      // Recheck the grid device immediately before sending a physical command.
      relay=await this.client.details(this.client.c.relayId);
      if(relay.online!==false) {s.relayOnline=relay.online;s.mode='grid_online';s.message='Breaker уже онлайн. Команду вимкнення не надсилали.';await bot.persist();return;}
      s.mode='pending';s.commandAt=this.now();s.message='Breaker офлайн. Надсилаємо вимкнення Smart Plug +…';
      await bot.persist();
      await this.client.off();
      if(await this.client.plugState()) {
        s.mode='unconfirmed';s.message='Команду вимкнення прийнято, але розетка ще не підтвердила стан. Перевіримо повторно.';this.next=this.now()+60000;
        if(before.mode!=='unconfirmed') this.notify(bot,'⚠️ Бойлер: вимкнення ще не підтверджене',s.message);
      } else {
        s.mode='off';s.confirmedAt=this.now();s.message='✅ Бойлер автоматично вимкнено: Breaker офлайн, Smart Plug + підтвердив вимкнення.';
        this.notify(bot,'♨️ Бойлер автоматично вимкнено','Breaker перед інвертором офлайн. Smart Plug + підтвердив стан «вимкнено». Після повернення мережі бойлер сам не ввімкнеться.');
      }
    } catch(e) {
      this.next=this.now()+60000;
      const error=e instanceof TuyaError ? e.code : 'CHECK_FAILED';
      s.error=error;s.mode='unknown';s.message='⚠️ Перевірка бойлера не завершена: '+error+'. Вимкнення не підтверджене.';
      if(before.error!==error) this.notify(bot,'⚠️ Автовимкнення бойлера: потрібна увага',s.message);
    }
    await bot.persist();
  }
}
