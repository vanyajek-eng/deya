import { CloudStore } from '../worker/store.mjs';
export default async function handler(req,res) {
  res.setHeader('Cache-Control','no-store');
  if(req.method!=='GET') return res.status(405).json({error:'GET required'});
  try {
    const record=await new CloudStore().load();
    return res.status(200).json({publicAccess:true,monitorObservedAt:record.alarm.lastValid ?? null,
      snapshotAt:record.snapshot?.at ?? null,subscriberCount:1+Object.keys(record.viewers ?? {}).length,
      queuedEvents:record.queue.length,configured:true});
  } catch {return res.status(503).json({publicAccess:true,configured:false});}
}
