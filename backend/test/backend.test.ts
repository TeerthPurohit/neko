import { beforeAll, afterAll, afterEach, describe, it, expect, vi } from 'vitest';
import { Database, postgresSql } from '../src/database';
import { migrate } from '../src/migrate';
import api from '../src/index';
import { modelKey } from '../src/security';
import { processTask, enqueue, nextDailyRun } from '../src/tasks';
import { reserve } from '../src/ai';
import { equalShares } from '../src/validation';
import type { Env, User } from '../src/types';

if(!process.env.DATABASE_URL)throw new Error('Run against the isolated validation branch using node --env-file=.env.test node_modules/vitest/vitest.mjs run');
const db=new Database(process.env.DATABASE_URL),prefix='test-'+crypto.randomUUID();
const env:Env={DB:db,NEKO_PAIRING_SECRET:'r'.repeat(64),MODEL_KEY_ENCRYPTION_KEY:'ab'.repeat(32),CHAT_MODELS:'xiaomi/mimo-v2.6-pro',CLASSIFIER_MODEL:'typesafe/jev-1.13',ALLOW_PAID_AI:'true',MONTHLY_AI_BUDGET_USD:'2',DAILY_AI_REQUESTS:'100'};
const background:Promise<unknown>[]=[];
async function request(path:string,method='GET',body?:unknown,bearer?:string){
  return api.fetch(new Request('https://neko.test'+path,{method,headers:{'Content-Type':'application/json',...(bearer?{Authorization:'Bearer '+bearer}: {})},...(body?{body:JSON.stringify(body)}:{})}),env,{waitUntil:p=>background.push(p)});
}
async function account(suffix:string){
  const response=await request('/v1/auth/register','POST',{email:prefix+suffix+'@example.com',password:'a valid test password',name:suffix,registration_code:env.NEKO_PAIRING_SECRET});
  expect(response.status).toBe(200);return await response.json() as {user_id:string;device_token:string};
}
const tx={id:'sample',occurred_at:Date.now()-1000,amount_paise:12345,direction:'DEBIT',category:'OTHER',merchant:'Lunch',status:'POSTED',review:'DRAFT',account_alias:'ICICI',transfer_id:null,updated_at:Date.now()};
let one:{user_id:string;device_token:string},two:{user_id:string;device_token:string};
beforeAll(async()=>{await migrate(process.env.DATABASE_URL!);one=await account('one');two=await account('two');});
afterEach(async()=>{await Promise.allSettled(background.splice(0));vi.restoreAllMocks();});
afterAll(async()=>{await db.close();});

describe('Neko on real PostgreSQL',()=>{
  it('rejects unauthenticated requests and invalid passwords, and revokes a login session',async()=>{
    expect((await request('/v1/activity')).status).toBe(401);
    expect((await request('/v1/auth/login','POST',{email:prefix+'one@example.com',password:'wrong test password'})).status).toBe(401);
    const login=await request('/v1/auth/login','POST',{email:prefix+'one@example.com',password:'a valid test password'});
    const token=(await login.json() as {device_token:string}).device_token;
    expect((await request('/v1/auth/logout','POST',{},token)).status).toBe(200);
    expect((await request('/v1/settings','GET',undefined,token)).status).toBe(401);
  });
  it('revokes a directly paired device token on logout',async()=>{
    const response=await request('/v1/pair','POST',{secret:env.NEKO_PAIRING_SECRET,name:'Temporary test device'});
    const paired=await response.json() as {user_id:string;device_token:string};
    try {
      expect(response.status).toBe(201);
      expect((await request('/v1/auth/logout','POST',{},paired.device_token)).status).toBe(200);
      expect((await request('/v1/settings','GET',undefined,paired.device_token)).status).toBe(401);
    } finally {
      await db.prepare('DELETE FROM users WHERE id=?').bind(paired.user_id).run();
    }
  });
  it('requires consent and rejects raw SMS, preserving user isolation',async()=>{
    expect((await request('/v1/sync','POST',{transactions:[tx]},two.device_token)).status).toBe(403);
    await request('/v1/settings','PATCH',{ai_enabled:true},one.device_token);
    expect((await request('/v1/sync','POST',{transactions:[{...tx,sms_body:'private OTP'}]},one.device_token)).status).toBe(400);
    const response=await request('/v1/sync','POST',{transactions:[tx]},one.device_token);
    expect(response.status).toBe(200);
    expect(await db.prepare('SELECT id FROM transactions WHERE user_id=?').bind(two.user_id).first()).toBeNull();
  });
  it('rejects an OTP entered in the learning command before it is stored',async()=>{
    await request('/v1/settings','PATCH',{ai_enabled:true},one.device_token);
    const message='Remember for next time: my bank OTP is 123456';
    try {
      const response=await request('/v1/chat','POST',{message},one.device_token);
      expect(response.status).toBe(400);
    } finally {
      await db.prepare("DELETE FROM agent_corrections WHERE user_id=? AND correction LIKE '%123456%'").bind(one.user_id).run();
      await db.prepare('DELETE FROM chat WHERE user_id=? AND content=?').bind(one.user_id,message).run();
      await db.prepare("DELETE FROM activity WHERE user_id=? AND body LIKE '%123456%'").bind(one.user_id).run();
      await request('/v1/settings','PATCH',{ai_enabled:false},one.device_token);
    }
  });
  it('encrypts BYOK with user binding and never returns the key in settings',async()=>{
    const key='sk-or-v1-'+'k'.repeat(40);
    vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({data:{}}));
    expect((await request('/v1/byok','POST',{key},one.device_token)).status).toBe(200);
    const user=await db.prepare('SELECT * FROM users WHERE id=?').bind(one.user_id).first<User>();
    expect(user?.model_key_ciphertext).not.toContain(key);
    expect(await modelKey(env,user!)).toBe(key);
    await expect(modelKey(env,{...user!,id:two.user_id})).rejects.toThrow();
    const settings=await request('/v1/settings','GET',undefined,one.device_token);
    expect(await settings.text()).not.toContain(key);
  });
  it('classifies only into a draft proposal and completes an idempotent task once',async()=>{
    vi.spyOn(globalThis,'fetch').mockResolvedValue(Response.json({answers:{category:{choice:'FOOD',confidence:0.9}},usage:{cost:0.0001}}));
    const id=await enqueue(env,one.user_id,'classify',{transaction_id:tx.id,expected_updated_at:tx.updated_at},prefix+'classify');
    await Promise.all([processTask(env,id),processTask(env,id)]);
    expect((await db.prepare('SELECT category FROM transactions WHERE user_id=? AND id=?').bind(one.user_id,tx.id).first<{category:string}>())?.category).toBe('OTHER');
    const activity=(await db.prepare('SELECT proposal FROM activity WHERE id=?').bind('result:'+id).all<{proposal:string}>()).results;
    expect(activity).toHaveLength(1);expect(JSON.parse(activity[0].proposal).category).toBe('FOOD');
  });
  it('isolates groups, requires confirmation, and splits every paise exactly once',async()=>{
    const response=await request('/v1/groups','POST',{name:'Test friends'},one.device_token);
    const group=await response.json() as {id:string;invite:string};
    expect((await request('/v1/groups/'+group.id,'GET',undefined,two.device_token)).status).toBe(403);
    await request('/v1/groups/join','POST',{invite:group.invite},two.device_token);
    const expense={title:'Lunch',amount_paise:101,idempotency_key:prefix+'expense',confirmed:true};
    expect((await request('/v1/groups/'+group.id+'/expenses','POST',{...expense,confirmed:false},one.device_token)).status).toBe(400);
    const first=await request('/v1/groups/'+group.id+'/expenses','POST',expense,one.device_token);
    const again=await request('/v1/groups/'+group.id+'/expenses','POST',expense,one.device_token);
    expect(first.status).toBe(201);expect(again.status).toBe(200);
    const id=(await again.json() as {id:string}).id;
    const shares=(await db.prepare('SELECT amount_paise FROM shares WHERE expense_id=?').bind(id).all<{amount_paise:number}>()).results;
    expect(shares.reduce((sum,s)=>sum+s.amount_paise,0)).toBe(101);
  });
  it('limits concurrent budget reservations atomically',async()=>{
    const user=(await db.prepare('SELECT * FROM users WHERE id=?').bind(two.user_id).first<User>())!;
    const results=await Promise.allSettled([reserve({...env,MONTHLY_AI_BUDGET_USD:'0.06'},user,0.06),reserve({...env,MONTHLY_AI_BUDGET_USD:'0.06'},user,0.06)]);
    expect(results.filter(r=>r.status==='fulfilled')).toHaveLength(1);
  });
  it('deleting cloud context disables AI and clears only the current user data',async()=>{
    const response=await request('/v1/cloud-context','DELETE',undefined,one.device_token);
    expect(response.status).toBe(200);
    expect(await db.prepare('SELECT id FROM transactions WHERE user_id=?').bind(one.user_id).first()).toBeNull();
    expect((await db.prepare('SELECT ai_enabled FROM users WHERE id=?').bind(one.user_id).first<{ai_enabled:number}>())?.ai_enabled).toBe(0);
    expect(await db.prepare('SELECT id FROM users WHERE id=?').bind(two.user_id).first()).not.toBeNull();
  });
});
it('uses safe parameter placeholders and exact deterministic equal shares',()=>{
  expect(postgresSql("UPDATE users SET name='What? Yes',last_sync=? WHERE id=?")).toBe("UPDATE neko.users SET name='What? Yes',last_sync=$1 WHERE id=$2");
  expect(equalShares(100,['c','b','a']).map(s=>s.amount_paise)).toEqual([34,33,33]);
  expect(new Date(nextDailyRun(20,Date.parse('2026-10-01T13:00:00Z'))).toISOString()).toBe('2026-10-01T14:30:00.000Z');
});
