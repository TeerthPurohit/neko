// Synthetic account verification. Never prints credentials, account IDs, or context.
import pg from '../backend/node_modules/pg/lib/index.js';
import { randomUUID } from 'node:crypto';

const base=(process.env.NEKO_BACKEND_URL||'').replace(/\/$/,'');
if(!base.startsWith('https://')||!process.env.NEKO_PAIRING_SECRET||!process.env.DATABASE_URL)throw new Error('Configure the private deployment environment first');
let userId,token;
const results=[];
async function request(path,method='GET',body){
  const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(60_000)});
  if(!r.ok)throw new Error('Deployment endpoint failed with status '+r.status);
  return await r.json();
}
try {
  const account=await request('/v1/auth/register','POST',{email:'neko-deploy-check-'+randomUUID()+'@example.com',password:randomUUID()+randomUUID(),name:'Deployment verification',registration_code:process.env.NEKO_PAIRING_SECRET});
  userId=account.user_id;token=account.device_token;results.push('authenticated registration');
  const settings=await request('/v1/settings');
  if(!settings.models.includes('auto')||settings.model!=='auto')throw new Error('Automatic routing is not configured');
  results.push('auto routing is default for new accounts');
  const saved=await request('/v1/agent/corrections','POST',{behavior:'Synthetic test behavior',correction:'Use a short reply for this synthetic test',scope:'chat',confirmed:true});
  const memory=await request('/v1/agent/corrections');
  if(!memory.corrections.some(r=>r.id===saved.id))throw new Error('Correction was not persisted');
  await request('/v1/agent/corrections/'+saved.id,'DELETE');
  if((await request('/v1/agent/corrections')).corrections.length)throw new Error('Correction was not removed');
  results.push('correction save, retrieval and deletion');
  console.log(JSON.stringify({passed:results,paid_model_calls:0}));
}catch(error){console.error(error instanceof Error?error.message:'Deployment verification failed');process.exitCode=1;}
finally {
  if(userId){
    const pool=new pg.Pool({connectionString:process.env.DATABASE_URL_UNPOOLED||process.env.DATABASE_URL,connectionTimeoutMillis:15_000,query_timeout:15_000});
    const client=await pool.connect();
    try{
      await client.query('BEGIN');
      await client.query('DELETE FROM neko.agent_corrections WHERE user_id=$1',[userId]);
      await client.query('DELETE FROM neko.sessions WHERE user_id=$1',[userId]);
      await client.query('DELETE FROM neko.users WHERE id=$1',[userId]);
      await client.query('COMMIT');
      console.log('Synthetic verification account removed');
    }catch{await client.query('ROLLBACK');console.error('Synthetic account cleanup needs attention');process.exitCode=1;}
    finally{client.release();await pool.end();}
  }
}
