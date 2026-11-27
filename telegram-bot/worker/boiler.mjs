import { randomUUID } from 'node:crypto';
import { clock } from './core.mjs';
import { TuyaError } from './tuya.mjs';

export const BOILER_POLL_MS=120000;
export const GRID_RETURN_MS=120000;
export function boilerText(record) {
  const s=record.boiler;
  return '♨️ Автоматика бойлера\n\n'+(record.boilerPaused ? '⏸ Перевірки призупинені власником.' : s?.message || 'Tuya ще не налаштована або перша перевірка ще не виконана.')+
    (s?.checkedAt ? '\nОстання перевірка: '+clock(s.checkedAt) : '')+
    '\n\nПеревірка Breaker кожні 2 хвилини. Офлайн — вимикаємо Smart Plug +. Після 2 хв підтвердженого онлайн-стану повертаємо живлення, якщо бойлер вимкнула ця автоматизація.'+
    '\n/boiler_pause — призупинити\n/boiler_resume — відновити';
}
export class BoilerGuard {
  constructor(client,now=()=>Date.now()) {this.client=client;this.now=now;this.next=0;}
  notify(bot,title,detail) {
    const item={id:randomUUID(),kind:'boiler',source:'Tuya',title,detail,at:this.now(),measuredAt:this.now(),recipients:[String(bot.chat)]};
    bot.record.queue=[...bot.record.queue,item].slice(-200);
    bot.record.boilerEvents=[item,...(bot.record.boilerEvents ?? [])].slice(0,50);
  }
  async checkedPlug() {
    const plug=await this.client.details(this.client.c.plugId);
    if(plug.category!=='cz') throw new TuyaError('BOILER_PLUG_TYPE_MISMATCH');
    if(plug.online!==true) throw new TuyaError('PLUG_OFFLINE');
    await this.client.validatePlug();
    return plug;
  }
  async grid() {
    const relay=await this.client.details(this.client.c.relayId);
    if(relay.category!=='dlq' || typeof relay.online!=='boolean') throw new TuyaError('GRID_RELAY_TYPE_MISMATCH');
    return relay;
  }
  async tick(bot) {
    if(!this.client) return;
    if(bot.record.boilerPaused) {
      if(bot.record.boiler?.onlineSince) {
        bot.record.boiler.onlineSince=null;
        await bot.persist();
      }
      return;
    }
    if(this.now()<this.next) return;
    const now=this.now(),before=bot.record.boiler ?? {};
    this.next=now+BOILER_POLL_MS;
    // Migrate an outstanding shutdown from the earlier off-only version.
    // A manually off plug or an unrelated old shutdown is not auto-started.
    const restoreNeeded=typeof before.restoreNeeded==='boolean' ? before.restoreNeeded :
      Boolean(before.commandAt && ['off','pending','unconfirmed'].includes(before.mode));
    const s={...before,restoreNeeded,checkedAt:now,error:null};bot.record.boiler=s;
    try {
      let relay=await this.grid();
      s.relayOnline=relay.online;
      if(relay.online) {
        if(!before.checkedAt) this.notify(bot,'♨️ Автоматику бойлера ввімкнено','Перевірка кожні 2 хвилини. Breaker офлайн — вимкну Smart Plug +. Після 2 хв підтвердженого онлайн-стану поверну живлення після автоматичного вимкнення.');
        if(!s.restoreNeeded) {
          s.onlineSince=null;s.mode='grid_online';s.message='🟢 Breaker онлайн. Немає автоматичного вимкнення, яке потрібно відновити.';
          await bot.persist();return;
        }
        if(before.relayOnline!==true || !s.onlineSince || now-(before.checkedAt || 0)>150000) s.onlineSince=now;
        if(now-s.onlineSince<GRID_RETURN_MS) {
          s.mode='waiting_grid';s.message='⏳ Breaker знову онлайн. Чекаємо 2 хвилини підтвердженого онлайн-стану перед увімкненням бойлера.';
          this.next=s.onlineSince+GRID_RETURN_MS;
          await bot.persist();return;
        }
        await this.checkedPlug();
        if(await this.client.plugState()) {
          s.restoreNeeded=false;s.onlineSince=null;s.mode='on';s.onConfirmedAt=this.now();
          s.message='🟢 Мережа повернулася, розетка бойлера вже ввімкнена.';
          await bot.persist();return;
        }
        relay=await this.grid();
        if(relay.online!==true) {
          s.relayOnline=false;s.onlineSince=null;s.mode='off';
          s.message='🔴 Breaker знову офлайн. Увімкнення бойлера скасовано.';
          await bot.persist();return;
        }
        s.mode='restoring';s.onCommandAt=this.now();s.message='Надсилаємо ввімкнення Smart Plug + після повернення мережі…';
        await bot.persist();
        await this.client.on();
        if(await this.client.plugState()) {
          s.restoreNeeded=false;s.onlineSince=null;s.mode='on';s.onConfirmedAt=this.now();
          s.message='✅ Бойлер автоматично ввімкнено після 2 хв підтвердженого онлайн-стану Breaker.';
          this.notify(bot,'♨️ Бойлер автоматично ввімкнено','Breaker підтверджує онлайн-стан протягом щонайменше 2 хвилин. Smart Plug + підтвердив увімкнення.');
        } else {
          s.mode='on_unconfirmed';s.message='Команду ввімкнення прийнято, але розетка ще не підтвердила стан.';this.next=this.now()+60000;
          if(before.mode!=='on_unconfirmed') this.notify(bot,'⚠️ Бойлер: увімкнення ще не підтверджене',s.message);
        }
        await bot.persist();return;
      }
      s.onlineSince=null;
      const plug=await this.checkedPlug();s.plugOnline=plug.online;
      const on=await this.client.plugState();
      if(!on) {
        s.mode='off';s.confirmedAt=now;s.message='🔴 Breaker офлайн. Розетка бойлера підтверджує: вимкнено.';
        if(!before.checkedAt) this.notify(bot,'♨️ Автоматику бойлера ввімкнено','Breaker офлайн, розетка вже вимкнена. Перевірятиму стан кожні 2 хвилини.');
        await bot.persist();return;
      }
      relay=await this.grid();
      if(relay.online!==false) {
        s.relayOnline=relay.online;s.onlineSince=s.restoreNeeded ? now : null;s.mode=s.restoreNeeded ? 'waiting_grid' : 'grid_online';
        s.message='Breaker уже онлайн. Команду вимкнення не надсилали.';await bot.persist();return;
      }
      // Set intent durably before the idempotent OFF command, including ambiguous timeouts.
      s.restoreNeeded=true;s.mode='pending';s.commandAt=this.now();s.message='Breaker офлайн. Надсилаємо вимкнення Smart Plug +…';
      await bot.persist();
      await this.client.off();
      if(await this.client.plugState()) {
        s.mode='unconfirmed';s.message='Команду вимкнення прийнято, але розетка ще не підтвердила стан. Перевіримо повторно.';this.next=this.now()+60000;
        if(before.mode!=='unconfirmed') this.notify(bot,'⚠️ Бойлер: вимкнення ще не підтверджене',s.message);
      } else {
        s.mode='off';s.confirmedAt=this.now();s.message='✅ Бойлер автоматично вимкнено: Breaker офлайн, Smart Plug + підтвердив вимкнення.';
        this.notify(bot,'♨️ Бойлер автоматично вимкнено','Breaker перед інвертором офлайн. Smart Plug + підтвердив вимкнення. Після повернення мережі та 2 хв підтвердженого онлайн-стану бойлер увімкнеться автоматично.');
      }
    } catch(e) {
      this.next=this.now()+60000;
      const error=e instanceof TuyaError ? e.code : 'CHECK_FAILED';
      s.onlineSince=null;s.error=error;s.mode='unknown';s.message='⚠️ Перевірка бойлера не завершена: '+error+'. Зміну живлення не підтверджено.';
      if(before.error!==error) this.notify(bot,'⚠️ Автоматика бойлера: потрібна увага',s.message);
    }
    await bot.persist();
  }
}
