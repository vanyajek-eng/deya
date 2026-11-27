import { get, head, put, BlobPreconditionFailedError } from '@vercel/blob';
import { defaults } from './core.mjs';
export function initial() { return {version:2,viewers:{},deliveryCooldowns:{},settings:{...defaults},alarm:{},events:[],queue:[],history:[],cursor:0,messageId:null,view:'home',viewUntil:0}; }
export class CloudStore {
  constructor(path = 'deye/bot-state.json', api = {get,head,put}) {this.path=path;this.api=api;this.etag=null;this.value=initial();}
  async load() {
    for(let attempt=0;attempt<3;attempt++) {
      const result=await this.api.get(this.path,{access:'private',useCache:false});
      if(!result) return this.value;
      const saved=await new Response(result.stream).json();
      // Compression gateways may weaken the GET ETag. Use the canonical
      // metadata ETag for conditional writes, after checking the versions agree.
      const metadata=await this.api.head(this.path);
      if(result.blob.etag.replace(/^W\//,'')!==metadata.etag.replace(/^W\//,'')) continue;
      this.value={...initial(),...saved,settings:{...defaults,...saved.settings}};this.etag=metadata.etag;
      return this.value;
    }
    throw new Error('Стан змінюється іншим процесом; повтори запуск.');
  }
  async save(value) {
    const blob=await this.api.put(this.path,JSON.stringify(value),{access:'private',addRandomSuffix:false,allowOverwrite:this.etag!==null,
      ...(this.etag ? {ifMatch:this.etag} : {}),contentType:'application/json',cacheControlMaxAge:60});
    this.etag=blob.etag;this.value=value;
  }
}
export { BlobPreconditionFailedError };
