import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Database } from './database';
import api from './index';
import type { Env } from './types';

const databaseUrl=process.env.DATABASE_URL;
if(!databaseUrl)throw new Error('DATABASE_URL is not configured');
const env:Env={DB:new Database(databaseUrl),NEKO_PAIRING_SECRET:process.env.NEKO_PAIRING_SECRET||'',
  MODEL_KEY_ENCRYPTION_KEY:process.env.MODEL_KEY_ENCRYPTION_KEY,
  AGENT_SERVICE_URL:process.env.AGENT_SERVICE_URL||'http://127.0.0.1:8081',AGENT_SERVICE_TOKEN:process.env.AGENT_SERVICE_TOKEN,
  CHAT_MODELS:process.env.CHAT_MODELS||'auto,openai/gpt-oss-120b,deepseek/deepseek-v4.1-flash,xiaomi/mimo-v2.6-pro,openai/gpt-6-luna',CLASSIFIER_MODEL:process.env.CLASSIFIER_MODEL||'typesafe/jev-1.13',
  ALLOW_PAID_AI:process.env.ALLOW_PAID_AI||'true',MONTHLY_AI_BUDGET_USD:process.env.MONTHLY_AI_BUDGET_USD||'2',DAILY_AI_REQUESTS:process.env.DAILY_AI_REQUESTS||'100',
  AI_TURN_BUDGET_USD:process.env.AI_TURN_BUDGET_USD||'0.06',
  FCM_PROJECT_ID:process.env.FCM_PROJECT_ID,FCM_CLIENT_EMAIL:process.env.FCM_CLIENT_EMAIL,FCM_PRIVATE_KEY:process.env.FCM_PRIVATE_KEY};
const jobs=new Set<Promise<unknown>>();
const context={waitUntil(task:Promise<unknown>){jobs.add(task);void task.catch(()=>{/* Tasks persist failures for inspection. */}).finally(()=>jobs.delete(task));}};
const staticFiles:Record<string,[string,string]>={'/preview/':['index.html','text/html; charset=utf-8'],'/preview/style.css':['style.css','text/css; charset=utf-8'],'/preview/app.js':['app.js','application/javascript; charset=utf-8']};
const server=createServer(async(req,res)=>{
  try{
    const path=(req.url||'/').split('?')[0];
    if(path==='/'){res.writeHead(302,{Location:'/preview/'});res.end();return;}
    if(staticFiles[path]&&req.method==='GET'){
      const [file,type]=staticFiles[path];res.writeHead(200,{'Content-Type':type,'X-Content-Type-Options':'nosniff'});
      res.end(await readFile(resolve('../preview',file)));return;
    }
    const chunks:Buffer[]=[];let size=0;
    for await(const part of req){size+=part.length;if(size>100_000){res.writeHead(413);res.end('{"error":"Payload is too large"}');return;}chunks.push(part);}
    const headers=new Headers();for(const [name,value] of Object.entries(req.headers))if(value)headers.set(name,Array.isArray(value)?value.join(','):value);
    const response=await api.fetch(new Request('http://localhost'+(req.url||'/'),{method:req.method,headers,...(chunks.length?{body:Buffer.concat(chunks).toString('utf8')}: {})}),env,context);
    res.writeHead(response.status,Object.fromEntries(response.headers.entries()));res.end(Buffer.from(await response.arrayBuffer()));
  }catch{res.writeHead(500,{'Content-Type':'application/json'});res.end('{"error":"Neko could not finish this request"}');}
});
server.requestTimeout=30_000;server.headersTimeout=15_000;
server.listen(Number(process.env.PORT)||8080,'0.0.0.0',()=>console.log('Neko API listening; secrets and ledger data are not logged'));
let ticking=false;
const tick=async()=>{if(ticking)return;ticking=true;try{await api.scheduled(env);}catch{/* Next tick resumes queued work. */}finally{ticking=false;}};
const timer=setInterval(()=>void tick(),60_000);void tick();
const shutdown=()=>{clearInterval(timer);server.close(()=>void env.DB.close());};
process.on('SIGTERM',shutdown);process.on('SIGINT',shutdown);
