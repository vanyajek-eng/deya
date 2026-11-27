import { createHash } from 'node:crypto';
import { parseDevice } from './core.mjs';
export class ApiError extends Error {
  constructor(message, code = 0, retryAfter = 0) { super(message); this.code = code; this.retryAfter = retryAfter; }
}
export function config(env = process.env) {
  const names = ['TELEGRAM_TOKEN','TELEGRAM_CHAT_ID','DEYE_APP_ID','DEYE_APP_SECRET','DEYE_EMAIL','DEYE_PASSWORD','DEYE_SN'];
  for (const name of names) if (!env[name]?.trim()) throw new Error(`Потрібна змінна ${name}`);
  if (!/^\d+:[A-Za-z0-9_-]+$/.test(env.TELEGRAM_TOKEN.trim())) throw new Error('Некоректний формат TELEGRAM_TOKEN');
  if (!/^[1-9]\d+$/.test(env.TELEGRAM_CHAT_ID.trim())) throw new Error('Потрібен особистий TELEGRAM_CHAT_ID');
  const base = env.DEYE_BASE_URL || 'https://eu1-developer.deyecloud.com';
  if (!['https://eu1-developer.deyecloud.com','https://us1-developer.deyecloud.com','https://india-developer.deyecloud.com'].includes(base)) throw new Error('Невідомий сервер Deye');
  return {token:env.TELEGRAM_TOKEN.trim(),chat:env.TELEGRAM_CHAT_ID.trim(),base,appId:env.DEYE_APP_ID.trim(),secret:env.DEYE_APP_SECRET.trim(),email:env.DEYE_EMAIL.trim(),password:env.DEYE_PASSWORD,sn:env.DEYE_SN.trim(),poll:Math.min(300,Math.max(10,Number(env.POLL_SECONDS)||15))};
}
export class Telegram {
  constructor(token, fetcher = fetch) { this.token = token; this.fetcher = fetcher; }
  async call(method, body = {}, timeout = 15000) {
    let response;
    try { response = await this.fetcher(`https://api.telegram.org/bot${this.token}/${method}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(timeout)}); }
    catch { throw new ApiError('Немає зв’язку з Telegram'); }
    let data; try { data = await response.json(); } catch { throw new ApiError('Некоректна відповідь Telegram',response.status); }
    if (!response.ok || !data.ok) throw new ApiError(`Telegram: ${String(data.description || response.status).slice(0,240)}`,data.error_code || response.status,data.parameters?.retry_after || 0);
    return data.result;
  }
}
export class Deye {
  constructor(settings, fetcher = fetch) { this.s = settings; this.fetcher = fetcher; this.token = ''; this.expires = 0; this.catalog = []; this.catalogAt = 0; this.inFlight = null; }
  async post(path, body, auth = true) {
    let r;
    try { r = await this.fetcher(this.s.base+path,{method:'POST',headers:{'Content-Type':'application/json',...(auth ? {Authorization:'Bearer '+this.token} : {})},body:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(20000)}); }
    catch { throw new ApiError('Немає зв’язку з Deye Cloud'); }
    if (r.status === 401) {this.token = '';this.expires=0;}
    if (!r.ok) throw new ApiError('Deye Cloud HTTP '+r.status,r.status);
    let j; try {j = await r.json();} catch {throw new ApiError('Некоректна відповідь Deye');}
    if (!j.success) { this.token='';this.expires=0;throw new ApiError('Deye API: '+String(j.code ?? 'ERROR').slice(0,100)); }
    return j;
  }
  async authenticate() {
    if (this.token && this.expires > Date.now()) return;
    const j = await this.post('/v1.0/account/token?appId='+encodeURIComponent(this.s.appId),{appSecret:this.s.secret,email:this.s.email,password:createHash('sha256').update(this.s.password).digest('hex')},false);
    if (!j.accessToken) throw new ApiError('Deye не повернув access token');
    this.token=j.accessToken;this.expires=Date.now()+(Math.max(120,Number(j.expiresIn)||3600)-60)*1000;
  }
  async read() {
    if (this.inFlight) return this.inFlight;
    this.inFlight = this.fetchSnapshot();
    try { return await this.inFlight; } finally {this.inFlight=null;}
  }
  async fetchSnapshot() {
    await this.authenticate();
    const j = await this.post('/v1.0/device/latest',{deviceList:[this.s.sn],deviceType:'INVERTER'});
    if (Date.now()-this.catalogAt>3600000) {
      try { const c = await this.post('/v1.0/device/measurePoints',{deviceSn:this.s.sn,deviceType:'INVERTER'}); this.catalog=(c.measurePoints ?? []).filter(x=>typeof x==='string');this.catalogAt=Date.now(); } catch {this.catalogAt=Date.now()-3300000;}
    }
    return parseDevice(j,this.s.sn,this.catalog);
  }
}
