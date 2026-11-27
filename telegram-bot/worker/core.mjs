const finite = value => value !== null && value !== undefined && String(value).trim() !== '' && Number.isFinite(Number(String(value).replace(',', '.'))) ? Number(String(value).replace(',', '.')) : null;
export const normal = s => String(s).toLowerCase().replace(/[^a-z0-9]/g, '');
export const escapeHtml = s => String(s ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
function boundedHtml(value, limit) {
  let result='';
  for(const char of String(value ?? '')) {const escaped=escapeHtml(char);if(result.length+escaped.length>limit) return result+'…';result+=escaped;}
  return result;
}
export const defaults = { lowSoc: 20, highWatts: 3000, confirmSeconds: 30, maxAgeSeconds: 900, alerts: true };
export function parseDevice(json, sn, catalog = []) {
  const d = json.deviceDataList?.find(d => d.deviceSn === sn);
  if (!d) throw new Error('Deye не повернув цей інвертор. Перевір SN та доступ API.');
  const points = (d.dataList ?? []).map(p => ({ key: String(p.key), value: p.value == null ? null : String(p.value), unit: p.unit == null ? '' : String(p.unit) }));
  for (const key of catalog) if (!points.some(p => p.key === key)) points.push({key, value:null, unit:''});
  return { sn, at: finite(d.collectionTime) * 1000, online: [1, 2].includes(Number(d.deviceState)), points };
}
export function point(snapshot, ...keys) {
  for (const key of keys) { const p = snapshot?.points?.find(p => normal(p.key) === normal(key) && p.value != null); if (p) return p; }
  return null;
}
export function watts(snapshot, ...keys) {
  const p = point(snapshot, ...keys); const n = finite(p?.value); if (n === null) return null;
  if (['kw','квт'].includes(p.unit.trim().toLowerCase())) return n * 1000;
  if (['w','вт'].includes(p.unit.trim().toLowerCase())) return n;
  if (!p.unit && ['totalconsumptionpower','loadpower','consumptionpower'].includes(normal(p.key))) return n;
  return null;
}
export function metrics(snapshot) {
  const num = (...keys) => finite(point(snapshot, ...keys)?.value);
  const gp = point(snapshot, 'GridVoltageL1L2','GridVoltage','GridVoltageL1','GridVoltageR','GridVoltageL2','GridVoltageS','GridVoltageL3','GridVoltageT');
  let voltage = finite(gp?.value);
  if (voltage !== null && ['kv','кв'].includes(gp.unit.trim().toLowerCase())) voltage *= 1000;
  else if (gp && !['v','в',''].includes(gp.unit.trim().toLowerCase())) voltage = null;
  const soc = num('SOC','BatterySOC','BMSSOC');
  return { soc: soc !== null && soc >= 0 && soc <= 100 ? soc : null,
    volts:num('BatteryVoltage','DCVoltageBattery'), load:watts(snapshot,'TotalConsumptionPower','LoadPower','ConsumptionPower'),
    grid:voltage === null ? null : voltage >= 80, gridVoltage:voltage,
    battery:watts(snapshot,'BatteryPower'), pv:watts(snapshot,'TotalDCInputPower','TotalPVPower','PVPower'),
    gridPower:watts(snapshot,'TotalGridPower','GridTiePower') };
}
export function fresh(s, now, maxAge = 900) { return Boolean(s?.online && s.at > 0 && now - s.at <= maxAge * 1000 && s.at - now <= 60000); }
export function group(key) {
  const k = normal(key);
  if (k.includes('temperature') || k.includes('temp')) return 'temp';
  if (k.includes('battery') || k.startsWith('bms') || k === 'soc' || k.includes('charge')) return 'battery';
  if (k.includes('grid') || k.includes('purchased') || k.includes('externalct')) return 'grid';
  if (k.includes('consumption') || k.startsWith('load')) return 'load';
  if (k.includes('pv') || k.includes('dcinput')) return 'solar';
  return 'other';
}
export function evolve(saved, snapshot, settings, now) {
  const state = structuredClone(saved ?? {}), events = [];
  if (!fresh(snapshot, now, settings.maxAgeSeconds)) {
    state.candidate = null;
    state.badSince ??= now;
    if (!state.connectionAlarm && now - state.badSince >= 60000) {
      state.connectionAlarm = true; events.push({kind:'connection',title:'⚠️ Немає свіжих даних Deye',detail:'Стан мережі невідомий. Перевір логер та інтернет.'});
    }
    return {state, events};
  }
  if (state.connectionAlarm) events.push({kind:'connection',title:'✅ Зв’язок із Deye відновлено',detail:'Знову надходять свіжі дані.'});
  delete state.badSince; state.connectionAlarm = false;
  const m = metrics(snapshot);
  if (state.lastValid && now - state.lastValid > 90000) state.candidate = null;
  state.lastValid = now;
  if (m.grid === null) state.candidate = null;
  else if (state.stable === undefined && m.grid) { state.stable = true; state.candidate = null; }
  else if (state.stable !== m.grid) {
    if (state.candidate?.value !== m.grid) state.candidate = {value:m.grid,since:now};
    if (now - state.candidate.since >= settings.confirmSeconds * 1000) {
      state.stable = m.grid; state.candidate = null;
      events.push({kind:'grid',title:m.grid ? '💡 Світло повернулося' : '🔴 Зовнішню мережу відключено',detail:m.grid ? 'Deye знову бачить зовнішню мережу.' : 'Будинок живиться від АКБ. Перевір споживання.'});
    }
  } else state.candidate = null;
  if (m.soc !== null) {
    if (m.soc > settings.lowSoc + 2) state.low = false;
    if (m.soc <= settings.lowSoc && !state.low) { state.low = true; events.push({kind:'battery',title:'🪫 Низький заряд АКБ',detail:`Залишилося ${m.soc}%. Поріг ${settings.lowSoc}%.`}); }
  }
  if (m.grid === true || (m.load !== null && m.load < settings.highWatts - 200)) state.high = false;
  if (m.grid === false && m.load !== null && m.load >= settings.highWatts && !state.high) {
    state.high = true; events.push({kind:'load',title:'⚡ Високе споживання від АКБ',detail:`Навантаження ${Math.round(m.load)} Вт. Поріг ${settings.highWatts} Вт.`});
  }
  return {state, events};
}
export function privateChatId(update) {
  const message = update.callback_query?.message ?? update.message;
  const user = update.callback_query?.from ?? update.message?.from;
  const id = String(message?.chat?.id ?? '');
  return message?.chat?.type === 'private' && /^[1-9]\d*$/.test(id) &&
    String(user?.id) === id && !user?.is_bot ? id : null;
}
export function authorized(update, owner) {
  const message = update.callback_query?.message ?? update.message;
  const user = update.callback_query?.from ?? update.message?.from;
  return message?.chat?.type === 'private' && String(message.chat.id) === String(owner) && String(user?.id) === String(owner) && !user?.is_bot;
}
export const clock = at => at > 0 ? new Intl.DateTimeFormat('uk-UA',{timeZone:'Europe/Kyiv',day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'}).format(new Date(at)) : 'невідомо';
const fmt = (v, unit, digits=1) => v === null || v === undefined ? '—' : `${Number(v).toLocaleString('uk-UA',{maximumFractionDigits:digits})} ${unit}`;
const power = v => fmt(v == null ? null : v / 1000, 'кВт', 2);
const row = (text, data) => ({text,callback_data:data});
export function render(view, snapshot, record, now = Date.now()) {
  const settings = {...defaults,...record.settings};
  const m = metrics(snapshot), ok = fresh(snapshot,now,settings.maxAgeSeconds);
  const age = snapshot?.at ? Math.max(0,Math.floor((now-snapshot.at)/1000)) : null;
  let text, keyboard;
  const nav = [[row('🔄 Оновити','refresh'),row('🏠 Дашборд','home')],
    [row('🔋 АКБ','battery:0'),row('💡 Мережа','grid:0'),row('☀️ Сонце','solar:0')],
    [row('📋 Усі параметри','all:0'),row('📊 Історія','history')],
    [row('🔔 Події','events'),row('⚙️ Налаштування','settings')]];
  if (view === 'settings') {
    text = `<b>⚙️ Сповіщення Deye</b>\n\n${settings.alerts ? '🔔 Увімкнені' : '🔕 Призупинені'}\nНизький заряд: <b>${settings.lowSoc}%</b>\nВисоке навантаження без мережі: <b>${settings.highWatts} Вт</b>\nПідтвердження мережі: ${settings.confirmSeconds} с\n\nБот працює незалежно від додатка. Швидкість обмежена оновленням Deye Cloud.\nЗміна порогів тут стосується лише бота.`;
    keyboard = [[row(settings.alerts ? '🔕 Призупинити сигнали' : '🔔 Увімкнути сигнали','alerts:toggle')],
      [row('АКБ −5%','soc:down'),row('АКБ +5%','soc:up')],
      [row('Ліміт −500 Вт','load:down'),row('Ліміт +500 Вт','load:up')],...nav.slice(0,1)];
  } else if (view === 'events') {
    const events = record.events ?? [];
    text = '<b>🔔 Останні події</b>\n\n' + (events.slice(0,8).map(e=>`${escapeHtml(e.title)}\n${clock(e.at)}\n${escapeHtml(e.detail)}`).join('\n\n') || 'Подій ще немає.');
    keyboard = nav;
  } else if (view === 'history') {
    const history = record.history ?? [];
    text = '<b>📊 Останні виміри Deye</b>\n\n' + (history.slice(-10).reverse().map(h=>`${clock(h.at)}\n🔋 ${fmt(h.soc,'%')}  ⚡ ${power(h.load)}  ${h.grid === true ? '💡 Є мережа' : h.grid === false ? '🔴 Без мережі' : '⚪ Мережа невідома'}`).join('\n\n') || 'Історія накопичиться після отримання вимірів.');
    keyboard = nav;
  } else if (/^(all|battery|grid|solar|load|temp|other):\d+$/.test(view)) {
    const [category, pageText] = view.split(':');
    const points = (snapshot?.points ?? []).filter(p=>category === 'all' || group(p.key) === category).sort((a,b)=>a.key.localeCompare(b.key));
    const pages = Math.max(1,Math.ceil(points.length/12)), page = Math.min(Number(pageText),pages-1);
    const titles = {all:'Усі параметри',battery:'Акумулятор',grid:'Зовнішня мережа',solar:'Сонячна генерація',load:'Споживання',temp:'Температура',other:'Система та інше'};
    text = `<b>📋 ${titles[category]}</b> · ${page+1}/${pages}\n\n` + (points.slice(page*12,page*12+12).map(p=>`<b>${boundedHtml(p.key,100)}</b>\n${p.value === null ? 'Немає даних' : boundedHtml(p.value,120)+' '+boundedHtml(p.unit,20)}`).join('\n\n') || 'Показники ще не надійшли.');
    keyboard = [[row('◀️',`${category}:${Math.max(0,page-1)}`),row('▶️',`${category}:${Math.min(pages-1,page+1)}`)],...nav];
  } else {
    const filled = m.soc === null ? 0 : Math.round(m.soc/10);
    const grid = !ok || m.grid === null ? '⚪ Стан невідомий' : m.grid ? '🟢 Мережа є' : '🔴 Мережі немає';
    text = `<b>⚡ DEYE · PULSE</b>\nТвоя домашня енергія\n\n<b>${grid}</b>\n\n<b>🔋 Акумулятор  ${fmt(m.soc,'%')}</b>\n${'▰'.repeat(filled)}${'▱'.repeat(10-filled)}\nНапруга: ${fmt(m.volts,'В',2)}\nПотужність АКБ: ${power(m.battery)}\n\n<b>🏠 Будинок  ${power(m.load)}</b>\n☀️ Сонце: ${power(m.pv)}\n💡 Мережа: ${fmt(m.gridVoltage,'В')} · ${power(m.gridPower)}\n\n${ok ? '🟢 Останній доступний вимір' : '🟠 Дані не підтверджують поточний стан'}\n${clock(snapshot?.at)}${age === null ? '' : ' · '+age+' с тому'}\n${record.fetchError ? '\n⚠️ '+escapeHtml(record.fetchError) : ''}\n${settings.alerts ? '🔔 Сигнали увімкнені' : '🔕 Сигнали призупинені'}\n\n<i>Оновлення залежить від логера й Deye Cloud. Потужність АКБ показана зі знаком API.</i>`;
    keyboard = nav;
  }
  if (!ok && view !== 'home' && view !== 'settings') text += '\n\n⚠️ Дані застарілі або логер офлайн. Це не підтвердження відключення світла.';
  return {text,parse_mode:'HTML',reply_markup:{inline_keyboard:keyboard},link_preview_options:{is_disabled:true}};
}
