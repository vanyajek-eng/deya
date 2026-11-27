import { ScheduleClient } from '../worker/schedule.mjs';
const source=new ScheduleClient();
export default async function handler(req,res){res.setHeader('Cache-Control','no-store');if(req.method!=='GET')return res.status(405).end();await source.tick();const v=source.current();res.status(v.data?200:503).json({source:'Львівобленерго',...v});}
