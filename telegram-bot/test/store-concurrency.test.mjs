import test from 'node:test';
import assert from 'node:assert/strict';
import { CloudStore } from '../worker/store.mjs';
test('concurrent saves use sequential ETags and serialize current state',async()=>{
  let release;const gate=new Promise(r=>release=r),writes=[];
  const store=new CloudStore('test',{async put(path,body,options){
    writes.push({body:JSON.parse(body),etag:options.ifMatch});
    if(writes.length===1)await gate;
    return {etag:'v'+writes.length};
  }});
  store.etag='v0';const state={boiler:0};
  const first=store.save(state);await Promise.resolve();
  state.boiler=1;const second=store.save(state);await Promise.resolve();
  assert.equal(writes.length,1);release();await Promise.all([first,second]);
  assert.deepEqual(writes,[{body:{boiler:0},etag:'v0'},{body:{boiler:1},etag:'v1'}]);
});
test('failed persistence prevents queued writes after ownership loss',async()=>{
  let calls=0;const failure=new Error('conflict');
  const store=new CloudStore('test',{async put(){calls++;throw failure;}});
  const results=await Promise.allSettled([store.save({}),store.save({})]);
  assert.equal(calls,1);assert.ok(results.every(r=>r.reason===failure));
});
