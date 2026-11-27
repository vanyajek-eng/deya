import { randomUUID } from 'node:crypto';
import { clock } from './core.mjs';
import { TuyaError } from './tuya.mjs';
import { BlobPreconditionFailedError } from './store.mjs';

export const BOILER_POLL_MS=120000;
export const GRID_RETURN_MS=120000;
export const LOAD_POLL_MS=15000;
export const LOAD_OFF_W=3700;
export const LOAD_ON_W=1500;
export function boilerText(record) {
  const s=record.boiler;
  return '⚡ Автоматика бойлера\n\n'+(record.boilerPaused ? 'Перевірки призупинені власником.' : s?.message || 'Очікування перевірки Tuya.')+
    (s?.checkedAt ? '\nОстання перевірка: '+clock(s.checkedAt) : '')+
    (Number.isFinite(s?.watts) ? '\nОстанній вимір Breaker: '+(s.watts/1000).toFixed(2)+' кВт ('+clock(s.powerAt)+')' : '')+
    '\n\nПонад 3,7 кВт або Breaker офлайн — вимкнути Smart Plug +. Повернення: мережа онлайн і навантаження нижче 1,5 кВт протягом 2 хвилин. Перевірка кожні 15 с; є затримка хмари Tuya. Вмикається лише бойлер, який вимкнула автоматика.'+
    '\n/boiler_pause — призупинити\n/boiler_resume — відновити';
}
export class BoilerGuard {
  constructor(client,now=()=>Date.now()) {this.client=client;this.now=now;this.next=0;}
  notify(bot,title,detail) {
    const item={id:randomUUID(),kind:'boiler',source:'Tuya',title,detail,at:this.now(),measuredAt:this.now(),recipients:[String(bot.chat)]};
    bot.record.queue=[...bot.record.queue,item].slice(-200);
    bot.record.boilerEvents=[item,...(bot.record.boilerEvents ?? [])].slice(0,50);
  }
  async save(bot,force=false) {
    const s=bot.record.boiler;
    const key=JSON.stringify([s?.mode,s?.restoreNeeded,s?.error,s?.powerError,s?.offReason,s?.onlineSince,s?.lowSince,bot.record.queue.length]);
    if(!this.client.c.loadControl || force || key!==this.savedKey || this.now()-(this.savedAt || 0)>=120000) {
      await bot.persist();this.savedKey=key;this.savedAt=this.now();
    }
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
  async power(s) {
    try {
      const p=await this.client.relayPower(this.now());
      s.watts=p.watts;s.powerAt=p.measuredAt;s.powerError=null;
      return p.watts;
    } catch(e) {
      s.powerError=e instanceof TuyaError ? e.code : 'POWER_CHECK_FAILED';
      s.lowSince=null;return null;
    }
  }
  async tick(bot) {
    if(!this.client) return;
    if(bot.record.boilerPaused) {
      if((bot.record.boiler?.onlineSince || bot.record.boiler?.lowSince)) {
        bot.record.boiler.onlineSince=null;bot.record.boiler.lowSince=null;
        await this.save(bot);
      }
      return;
    }
    if(this.now()<this.next) return;
    const now=this.now(),before=bot.record.boiler ?? {};
    this.next=now+(this.client.c.loadControl ? LOAD_POLL_MS : BOILER_POLL_MS);
    // Migrate an outstanding shutdown from the earlier off-only version.
    // A manually off plug or an unrelated old shutdown is not auto-started.
    const restoreNeeded=typeof before.restoreNeeded==='boolean' ? before.restoreNeeded :
      Boolean(before.commandAt && ['off','pending','unconfirmed'].includes(before.mode));
    const s={...before,restoreNeeded,checkedAt:now,error:null};bot.record.boiler=s;
    try {
      let relay=await this.grid();
      s.relayOnline=relay.online;
      let watts=null;
      if(this.client.c.loadControl && relay.online) watts=await this.power(s);
      const overloaded=watts!==null && watts>LOAD_OFF_W;
      if(!relay.online || overloaded || watts===null || watts>=LOAD_ON_W) s.lowSince=null;
      if(relay.online && !overloaded) {
        if(!before.checkedAt) this.notify(bot,'⚡ Автоматика бойлера активна','Понад 3,7 кВт або Breaker офлайн — вимкнути. Повернення нижче 1,5 кВт протягом 2 хв онлайн-мережі. Перевірки кожні 15 с.');
        if(this.client.c.loadControl && watts===null) {
          s.onlineSince=null;s.mode='power_unknown';
          s.message='⚠️ Немає свіжої потужності Breaker: '+s.powerError+'. Автоматичне ввімкнення заблоковано.';
          await this.save(bot);return;
        }
        if(!s.restoreNeeded) {
          s.onlineSince=null;s.mode='grid_online';s.message='✅ Breaker онлайн. Автоматичних вимкнень для відновлення немає.';
          await this.save(bot);return;
        }
        if(before.relayOnline!==true || !s.onlineSince || now-(before.checkedAt || 0)>(this.client.c.loadControl ? 45000 : 150000)) {s.onlineSince=now;s.lowSince=null;}
        if(this.client.c.loadControl) {
          if(watts<LOAD_ON_W) s.lowSince ??=now;
          else s.lowSince=null;
          if(!s.lowSince || now-s.lowSince<GRID_RETURN_MS) {
            s.mode='waiting_load';s.message='⏳ Бойлер вимкнений автоматикою. Чекаю навантаження нижче 1,5 кВт протягом 2 хвилин.';
            await this.save(bot);return;
          }
        }
        if(now-s.onlineSince<GRID_RETURN_MS) {
          s.mode='waiting_grid';s.message='⏳ Breaker знову онлайн. Чекаємо 2 хвилини підтвердженого онлайн-стану перед увімкненням бойлера.';
          this.next=Math.min(this.next,s.onlineSince+GRID_RETURN_MS);
          await this.save(bot);return;
        }
        await this.checkedPlug();
        if(await this.client.plugState()) {
          s.restoreNeeded=false;s.onlineSince=null;s.mode='on';s.onConfirmedAt=this.now();
          s.message='🟢 Мережа повернулася, розетка бойлера вже ввімкнена.';
          await this.save(bot);return;
        }
        relay=await this.grid();
        if(relay.online!==true) {
          s.relayOnline=false;s.onlineSince=null;s.mode='off';
          s.message='🔴 Breaker знову офлайн. Увімкнення бойлера скасовано.';
          await this.save(bot);return;
        }
        if(this.client.c.loadControl) {
          const latest=await this.power(s);
          if(latest===null || latest>=LOAD_ON_W) {
            s.lowSince=null;s.mode='waiting_load';s.message='⏳ Навантаження змінилося або невідоме. Увімкнення відкладено.';
            await this.save(bot);return;
          }
        }
        s.mode='restoring';s.onCommandAt=this.now();s.message='Надсилаю ввімкнення Smart Plug + після перевірки мережі та навантаження.';
        await this.save(bot,true);
        await this.client.on();
        if(await this.client.plugState()) {
          s.restoreNeeded=false;s.onlineSince=null;s.mode='on';s.onConfirmedAt=this.now();
          s.message='✅ Бойлер автоматично ввімкнено після 2 хв стабільної мережі та допустимого навантаження.';
          s.lowSince=null;
          this.notify(bot,'✅ Бойлер автоматично ввімкнено',s.message);
        } else {
          s.mode='on_unconfirmed';s.message='Команду ввімкнення прийнято, але розетка ще не підтвердила стан.';this.next=this.now()+60000;
          if(before.mode!=='on_unconfirmed') this.notify(bot,'⚠️ Бойлер: увімкнення ще не підтверджене',s.message);
        }
        await this.save(bot);return;
      }
      s.onlineSince=null;s.lowSince=null;
      const plug=await this.checkedPlug();s.plugOnline=plug.online;
      const on=await this.client.plugState();
      if(!on) {
        s.mode='off';s.confirmedAt=now;s.message=overloaded ? '⛔ Навантаження понад 3,7 кВт. Розетка вже вимкнена.' : '⛔ Breaker офлайн. Розетка вже вимкнена.';
        if(!before.checkedAt) this.notify(bot,'⚡ Автоматика бойлера активна','Понад 3,7 кВт або Breaker офлайн — вимкнути. Повернення нижче 1,5 кВт протягом 2 хв онлайн-мережі. Перевірки кожні 15 с.');
        await this.save(bot);return;
      }
      relay=await this.grid();
      if(overloaded && relay.online) {
        const latest=await this.power(s);
        if(latest===null || latest<=LOAD_OFF_W) {
          s.mode='grid_online';s.message='Перевантаження більше не підтверджено. Команду вимкнення не надіслано.';
          await this.save(bot);return;
        }
      }
      if(!overloaded && relay.online!==false) {
        s.relayOnline=relay.online;s.onlineSince=s.restoreNeeded ? now : null;s.mode=s.restoreNeeded ? 'waiting_grid' : 'grid_online';
        s.message='Breaker уже онлайн. Команду вимкнення не надсилали.';await this.save(bot);return;
      }
      // Set intent durably before the idempotent OFF command, including ambiguous timeouts.
      s.restoreNeeded=true;s.offReason=overloaded ? 'overload' : 'grid';s.mode='pending';s.commandAt=this.now();s.message='Надсилаю вимкнення Smart Plug +: '+s.offReason;
      await this.save(bot,true);
      await this.client.off();
      if(await this.client.plugState()) {
        s.mode='unconfirmed';s.message='Команду вимкнення прийнято, але розетка ще не підтвердила стан. Перевіримо повторно.';this.next=this.now()+60000;
        if(before.mode!=='unconfirmed') this.notify(bot,'⚠️ Бойлер: вимкнення ще не підтверджене',s.message);
      } else {
        s.mode='off';s.confirmedAt=this.now();
        s.message=overloaded ? '⛔ Бойлер вимкнено: споживання '+(s.watts/1000).toFixed(2)+' кВт перевищує 3,7 кВт.' : '⛔ Бойлер вимкнено: Breaker офлайн.';
        this.notify(bot,'⛔ Бойлер автоматично вимкнено',s.message+' Повернення після 2 хв онлайн-мережі та навантаження нижче 1,5 кВт.');
      }
    } catch(e) {
      if(e instanceof BlobPreconditionFailedError) throw e;
      this.next=this.now()+(this.client.c.loadControl ? LOAD_POLL_MS : 60000);
      const error=e instanceof TuyaError ? e.code : 'CHECK_FAILED';
      s.onlineSince=null;s.lowSince=null;s.error=error;s.mode='unknown';s.message='⚠️ Перевірка бойлера не завершена: '+error+'. Новий стан не підтверджено.';
      if(before.error!==error) this.notify(bot,'⚠️ Автоматика бойлера: потрібна увага',s.message);
    }
    await this.save(bot);
  }
}
