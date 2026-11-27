import { createHash, createHmac } from 'node:crypto';

export class TuyaError extends Error {
  constructor(code) { super(`Tuya API: ${code}`); this.code=String(code); }
}
export function tuyaConfig(env=process.env) {
  if(env.TUYA_BOILER_ENABLED!=='true') return null;
  const c={clientId:env.TUYA_CLIENT_ID,secret:env.TUYA_CLIENT_SECRET,
    base:env.TUYA_BASE_URL || 'https://openapi.tuyaeu.com',
    relayId:env.TUYA_GRID_RELAY_ID,plugId:env.TUYA_BOILER_PLUG_ID,code:env.TUYA_PLUG_SWITCH_CODE || 'switch_1'};
  if(!c.clientId || !c.secret) throw new Error('Немає ключів Tuya');
  if(!['https://openapi.tuyaeu.com','https://openapi-weaz.tuyaeu.com'].includes(c.base)) throw new Error('Невідомий центр Tuya');
  if(![c.relayId,c.plugId].every(id=>/^[A-Za-z0-9]{10,40}$/.test(id || '')) || c.relayId===c.plugId) throw new Error('Перевір ID реле й розетки');
  if(!/^switch(?:_\d+)?$/.test(c.code)) throw new Error('Некоректний код вимикача');
  return c;
}
export function signedHeaders(c,method,path,body='',token='',now=Date.now()) {
  const t=String(now);
  const stringToSign=method+'\n'+createHash('sha256').update(body).digest('hex')+'\n\n'+path;
  const sign=createHmac('sha256',c.secret).update(c.clientId+token+t+stringToSign).digest('hex').toUpperCase();
  return {client_id:c.clientId,t,sign,sign_method:'HMAC-SHA256',...(token ? {access_token:token} : {}),'Content-Type':'application/json'};
}
export class TuyaClient {
  constructor(c,fetcher=fetch) {this.c=c;this.fetcher=fetcher;this.token='';this.expires=0;this.switchValidatedAt=0;}
  async raw(method,path,body,token='') {
    const text=body===undefined ? '' : JSON.stringify(body);
    let r;
    try {r=await this.fetcher(this.c.base+path,{method,headers:signedHeaders(this.c,method,path,text,token),
      ...(text ? {body:text} : {}),signal:AbortSignal.timeout(10000),redirect:'error'});}
    catch {throw new TuyaError('NETWORK');}
    if(!r.ok) throw new TuyaError('HTTP_'+r.status);
    let j;try {j=await r.json();}catch {throw new TuyaError('INVALID_RESPONSE');}
    if(j.success!==true) {
      if(['1010','1011','1012'].includes(String(j.code))) {this.token='';this.expires=0;}
      throw new TuyaError(j.code ?? 'UNKNOWN');
    }
    return j.result;
  }
  async request(method,path,body) {
    if(!this.token || Date.now()>=this.expires) {
      const a=await this.raw('GET','/v1.0/token?grant_type=1');
      if(typeof a?.access_token!=='string' || !a.access_token) throw new TuyaError('NO_TOKEN');
      this.token=a.access_token;this.expires=Date.now()+(Math.max(120,Number(a.expire_time)||7200)-60)*1000;
    }
    return this.raw(method,path,body,this.token);
  }
  async details(id) {
    const d=await this.request('GET','/v1.0/iot-03/devices/'+encodeURIComponent(id));
    if(d?.id!==id || typeof d.online!=='boolean') throw new TuyaError('INVALID_DEVICE');
    // Never return or persist local_key, IP addresses, or unrelated account fields.
    return {id:d.id,name:d.name || '',online:d.online,category:d.category};
  }
  async validatePlug() {
    if(Date.now()-this.switchValidatedAt<3600000) return;
    const j=await this.request('GET','/v1.0/iot-03/devices/'+this.c.plugId+'/functions');
    if(j?.category!=='cz' || !j.functions?.some(f=>f.code===this.c.code && f.type==='Boolean')) throw new TuyaError('NOT_A_VERIFIED_PLUG_SWITCH');
    this.switchValidatedAt=Date.now();
  }
  async plugState() {
    const status=await this.request('GET','/v1.0/iot-03/devices/'+this.c.plugId+'/status');
    const value=Array.isArray(status) ? status.find(p=>p.code===this.c.code)?.value : undefined;
    if(typeof value!=='boolean') throw new TuyaError('UNKNOWN_PLUG_STATE');
    return value;
  }
  async switchPower(value) {
    if(typeof value!=='boolean') throw new TuyaError('INVALID_SWITCH_VALUE');
    await this.validatePlug();
    const result=await this.request('POST','/v1.0/iot-03/devices/'+this.c.plugId+'/commands',
      {commands:[{code:this.c.code,value}]});
    if(result!==true) throw new TuyaError('COMMAND_NOT_ACCEPTED');
  }
  async off() {return this.switchPower(false);}
  async on() {return this.switchPower(true);}
}
