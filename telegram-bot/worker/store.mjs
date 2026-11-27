import { get, put, BlobPreconditionFailedError } from '@vercel/blob';
import { defaults } from './core.mjs';
export function initial() { return {version:1,settings:{...defaults},alarm:{},events:[],queue:[],history:[],cursor:0,messageId:null,view:'home',viewUntil:0}; }
export class CloudStore {
  constructor(path = 'deye/bot-state.json') {this.path=path;this.etag=null;this.value=initial();}
  async load() {
    const result=await get(this.path,{access:'private',useCache:false});
    if(result) {const saved=await new Response(result.stream).json();this.value={...initial(),...saved,settings:{...defaults,...saved.settings}};this.etag=result.blob.etag;}
    return this.value;
  }
  async save(value) {
    const blob=await put(this.path,JSON.stringify(value),{access:'private',addRandomSuffix:false,allowOverwrite:this.etag!==null,
      ...(this.etag ? {ifMatch:this.etag} : {}),contentType:'application/json',cacheControlMaxAge:60});
    this.etag=blob.etag;this.value=value;
  }
}
export { BlobPreconditionFailedError };
