import { ApiError, categories, models, type Env, type User, type BackgroundContext } from './types';
import { authenticate, hash, json, membership, token, passwordHash, encryptModelKey } from './security';
import { choice, equalShares, integer, object, plan, text, transaction } from './validation';
import { enqueue, nextDailyRun, processTask, scheduled } from './tasks';
import { corrections, saveCorrection } from './learning';

async function body(req:Request):Promise<Record<string,unknown>> {
  if(Number(req.headers.get('Content-Length')||0)>100_000) throw new ApiError(413,'Payload is too large');
  const raw=await req.text();if(raw.length>100_000) throw new ApiError(413,'Payload is too large');
  try{return object(JSON.parse(raw));}catch(error){if(error instanceof ApiError)throw error;throw new ApiError(400,'Invalid JSON');}
}
async function route(req:Request,env:Env,ctx:BackgroundContext):Promise<Response> {
  const url=new URL(req.url),path=url.pathname;
  if(path==='/health'&&req.method==='GET')return json({service:'neko',status:'ok'});
  if((path==='/v1/auth/register'||path==='/v1/auth/login')&&req.method==='POST') {
    const data=await body(req),email=text(data.email,254).toLowerCase(),password=text(data.password,128);
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)||password.length<12)throw new ApiError(400,'Use a valid email and a Neko password of at least 12 characters');
    let user:User|null=null;
    if(path.endsWith('register')) {
      if(!env.NEKO_PAIRING_SECRET||env.NEKO_PAIRING_SECRET.length<32||typeof data.registration_code!=='string'||await hash(data.registration_code)!==await hash(env.NEKO_PAIRING_SECRET))throw new ApiError(401,'Use the private registration code from your .env');
      if(await env.DB.prepare('SELECT id FROM users WHERE email=?').bind(email).first())throw new ApiError(409,'Use login for this email');
      const id=crypto.randomUUID(),salt=token(),name=text(data.name,60),digest=await passwordHash(password,salt);
      await env.DB.prepare('INSERT INTO users(id,name,email,password_hash,password_salt,token_hash,model,created_at) VALUES(?,?,?,?,?,?,?,?)').bind(id,name,email,digest,salt,await hash(token()),models(env)[0]||'xiaomi/mimo-v2.6-pro',Date.now()).run();
      user=await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(id).first<User>();
    } else {
      const row=await env.DB.prepare('SELECT * FROM users WHERE email=?').bind(email).first<User & {password_hash:string;password_salt:string}>();
      // Use a dummy derivation for missing users to reduce email enumeration through timing.
      const digest=await passwordHash(password,row?.password_salt||'0'.repeat(64));
      if(!row||digest!==row.password_hash)throw new ApiError(401,'Email or Neko password is incorrect');
      user=row;
    }
    if(!user)throw new ApiError(500,'Account could not be created');
    const deviceToken=token();
    await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)').bind(await hash(deviceToken),user.id,Date.now()+30*86_400_000).run();
    return json({user_id:user.id,name:user.name,device_token:deviceToken,has_model_key:!!user.model_key_ciphertext,ai_enabled:!!user.ai_enabled});
  }
  if(path==='/v1/pair'&&req.method==='POST') {
    const data=await body(req);
    if(!env.NEKO_PAIRING_SECRET||env.NEKO_PAIRING_SECRET.length<32)throw new ApiError(503,'Pairing is not configured');
    if(typeof data.secret!=='string'||await hash(data.secret)!==await hash(env.NEKO_PAIRING_SECRET))throw new ApiError(401,'Invalid pairing secret');
    const id=crypto.randomUUID(),deviceToken=token(),name=text(data.name,60);
    await env.DB.prepare('INSERT INTO users(id,name,token_hash,model,created_at) VALUES(?,?,?,?,?)').bind(id,name,await hash(deviceToken),models(env)[0]||'xiaomi/mimo-v2.6-pro',Date.now()).run();
    return json({user_id:id,device_token:deviceToken},201);
  }
  const user=await authenticate(req,env);
  if(path==='/v1/agent/corrections'&&req.method==='GET')return json({corrections:await corrections(env,user.id)});
  if(path==='/v1/agent/corrections'&&req.method==='POST') {
    const data=await body(req);
    if(data.confirmed!==true)throw new ApiError(400,'Confirm the correction to remember');
    return json({id:await saveCorrection(env,user.id,data)},201);
  }
  if(path.startsWith('/v1/agent/corrections/')&&req.method==='DELETE') {
    await env.DB.prepare('DELETE FROM agent_corrections WHERE id=? AND user_id=?').bind(path.slice('/v1/agent/corrections/'.length),user.id).run();
    return json({ok:true});
  }
  if(path==='/v1/auth/logout'&&req.method==='POST'){await env.DB.prepare('DELETE FROM sessions WHERE token_hash=?').bind(await hash(req.headers.get('Authorization')!.slice(7))).run();return json({ok:true});}
  if(path==='/v1/byok'&&req.method==='POST') {
    const data=await body(req),key=text(data.key,512);
    if(!/^sk-or-[A-Za-z0-9_-]{20,}$/.test(key))throw new ApiError(400,'Enter a valid OpenRouter API key');
    const response=await fetch('https://openrouter.ai/api/v1/key',{headers:{Authorization:'Bearer '+key},signal:AbortSignal.timeout(10_000)});
    if(!response.ok)throw new ApiError(400,'OpenRouter did not accept this key');
    await env.DB.prepare('UPDATE users SET model_key_ciphertext=? WHERE id=?').bind(await encryptModelKey(env,user.id,key),user.id).run();return json({ok:true});
  }
  if(path==='/v1/byok'&&req.method==='DELETE'){await env.DB.prepare('UPDATE users SET model_key_ciphertext=NULL,ai_enabled=0 WHERE id=?').bind(user.id).run();return json({ok:true});}
  if(path==='/v1/settings'&&req.method==='GET')return json({user_id:user.id,name:user.name,ai_enabled:!!user.ai_enabled,model:user.model,models:models(env),has_model_key:!!user.model_key_ciphertext,paid_ai_allowed:env.ALLOW_PAID_AI==='true',daily_requests:Number(env.DAILY_AI_REQUESTS),monthly_budget_usd:Number(env.MONTHLY_AI_BUDGET_USD),last_sync:user.last_sync});
  if(path==='/v1/settings'&&req.method==='PATCH') {
    const data=await body(req);
    const enabled=typeof data.ai_enabled==='boolean'?data.ai_enabled:!!user.ai_enabled;
    const model=data.model===undefined?user.model:choice(data.model,models(env));
    const pushToken=data.push_token===undefined?user.push_token:(data.push_token===null?null:text(data.push_token,4096));
    await env.DB.prepare('UPDATE users SET ai_enabled=?,model=?,push_token=? WHERE id=?').bind(enabled?1:0,model,pushToken,user.id).run();
    if(!enabled) await env.DB.prepare("UPDATE tasks SET status='failed',result='Cloud AI disabled by user' WHERE user_id=? AND status='queued'").bind(user.id).run();
    return json({ok:true});
  }
  if(path==='/v1/sync'&&req.method==='POST') {
    if(!user.ai_enabled)throw new ApiError(403,'Enable structured cloud context sharing first');
    const data=await body(req);
    if(!Array.isArray(data.transactions)||data.transactions.length>150)throw new ApiError(400,'Send at most 150 structured transactions');
    const txs=data.transactions.map(transaction);
    const budgets=Array.isArray(data.budgets)?data.budgets.slice(0,20).map(v=>{const b=object(v);return {category:choice(b.category,categories),amount:integer(b.amount_paise,1,1_000_000_000)};}):[];
    const stmts=txs.map(t=>env.DB.prepare(`INSERT INTO transactions(user_id,id,occurred_at,amount_paise,direction,category,merchant,status,review,account_alias,transfer_id,updated_at,spending_treatment,related_transaction_id,principal_paise) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,id) DO UPDATE SET occurred_at=excluded.occurred_at,amount_paise=excluded.amount_paise,direction=excluded.direction,category=excluded.category,merchant=excluded.merchant,status=excluded.status,review=excluded.review,account_alias=excluded.account_alias,transfer_id=excluded.transfer_id,updated_at=excluded.updated_at,spending_treatment=excluded.spending_treatment,related_transaction_id=excluded.related_transaction_id,principal_paise=excluded.principal_paise WHERE excluded.updated_at>=transactions.updated_at`).bind(user.id,t.id,t.occurred_at,t.amount_paise,t.direction,t.category,t.merchant,t.status,t.review,t.account_alias,t.transfer_id,t.updated_at,t.spending_treatment,t.related_transaction_id,t.principal_paise));
    stmts.push(env.DB.prepare('UPDATE users SET last_sync=? WHERE id=?').bind(Date.now(),user.id));
    stmts.push(env.DB.prepare('DELETE FROM budgets WHERE user_id=?').bind(user.id));
    for(const b of budgets)stmts.push(env.DB.prepare('INSERT INTO budgets(user_id,category,amount_paise) VALUES(?,?,?)').bind(user.id,b.category,b.amount));
    await env.DB.batch(stmts);
    // The monthly plan is stored apart from the batch above: an invalid plan or a database without migration 004 must never stop transactions from syncing.
    try {
      const monthPlan=data.plan==null?null:plan(data.plan);
      if(monthPlan)await env.DB.prepare('INSERT INTO budget_plans(user_id,month,budget_paise,income_paise,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET month=excluded.month,budget_paise=excluded.budget_paise,income_paise=excluded.income_paise,updated_at=excluded.updated_at').bind(user.id,monthPlan.month,monthPlan.budget_paise,monthPlan.income_paise,Date.now()).run();
    } catch { /* Plan is optional context for the agent. */ }
    const jobIds:string[]=[];
    if(env.ALLOW_PAID_AI==='true')for(const t of txs.filter(t=>t.review==='DRAFT').slice(0,10))jobIds.push(await enqueue(env,user.id,'classify',{transaction_id:t.id,expected_updated_at:t.updated_at},`classify:${user.id}:${t.id}:${t.updated_at}`));
    if(jobIds.length)ctx.waitUntil(processTask(env,jobIds[0]));
    return json({ok:true,classification_jobs:jobIds,last_sync:Date.now()});
  }
  if(path==='/v1/chat'&&req.method==='POST') {
    if(!user.ai_enabled)throw new ApiError(403,'Cloud AI is disabled');
    const data=await body(req),question=text(data.message,2000);
    // An explicit chat command makes learning available in the existing Android UI.
    const remember=question.match(/^remember for next time:\s*([\s\S]+)$/i);
    if(remember) {
      const correctionId=await saveCorrection(env,user.id,{correction:remember[1]});
      const id=crypto.randomUUID(),reply='I saved this correction for future tasks: '+remember[1]+'. Say "show learned rules" to review it or "forget rule: '+correctionId+'" to remove it.';
      await env.DB.batch([
        env.DB.prepare('INSERT INTO chat(id,user_id,role,content,created_at) VALUES(?,?,?,?,?)').bind('question:'+id,user.id,'user',question,Date.now()),
        env.DB.prepare('INSERT INTO chat(id,user_id,role,content,created_at) VALUES(?,?,?,?,?)').bind(id,user.id,'assistant',reply,Date.now()),
        env.DB.prepare('INSERT INTO activity(id,user_id,kind,title,body,created_at) VALUES(?,?,?,?,?,?)').bind('result:'+id,user.id,'chat','Neko learned a correction',reply,Date.now()),
      ]);
      return json({task_id:id,status:'done'});
    }
    if(/^show learned rules$/i.test(question)||/^forget rule:\s*\S+$/i.test(question)) {
      const forget=question.match(/^forget rule:\s*(\S+)$/i);
      if(forget)await env.DB.prepare('DELETE FROM agent_corrections WHERE id=? AND user_id=?').bind(forget[1],user.id).run();
      const rules=await corrections(env,user.id),id=crypto.randomUUID();
      const reply=forget?'That rule is no longer saved.':rules.length?rules.map(r=>`${r.id} [${r.scope}]: ${r.correction}`).join('\n'):'No learned rules yet. Use "Remember for next time: …" to save a correction.';
      await env.DB.prepare('INSERT INTO activity(id,user_id,kind,title,body,created_at) VALUES(?,?,?,?,?,?)').bind('result:'+id,user.id,'chat','Neko memory',reply,Date.now()).run();
      return json({task_id:id,status:'done'});
    }
    const waiting=await env.DB.prepare("SELECT COUNT(*) count FROM tasks WHERE user_id=? AND kind='chat' AND status IN ('queued','running')").bind(user.id).first<{count:number}>();
    if((waiting?.count||0)>=3)throw new ApiError(429,'Wait for your pending replies');
    const id=await enqueue(env,user.id,'chat',{question});
    await env.DB.prepare('INSERT INTO chat(id,user_id,role,content,created_at) VALUES(?,?,?,?,?)').bind('question:'+id,user.id,'user',question,Date.now()).run();
    ctx.waitUntil(processTask(env,id));return json({task_id:id,status:'queued'},202);
  }
  if(path==='/v1/activity'&&req.method==='GET') {
    const after=Number(url.searchParams.get('after')||0);integer(after);
    const activity=(await env.DB.prepare('SELECT id,kind,title,body,proposal,status,created_at FROM activity WHERE user_id=? AND created_at>=? ORDER BY created_at DESC LIMIT 100').bind(user.id,after).all()).results;
    const tasks=(await env.DB.prepare("SELECT id,kind,status,result,created_at FROM tasks WHERE user_id=? AND status<>'done' ORDER BY created_at DESC LIMIT 20").bind(user.id).all()).results;
    const goals=(await env.DB.prepare('SELECT * FROM goals WHERE user_id=? ORDER BY created_at DESC').bind(user.id).all()).results;
    const usage=(await env.DB.prepare('SELECT day,requests,cost,reserved FROM usage WHERE user_id=? AND month=? ORDER BY day DESC').bind(user.id,new Date().toISOString().slice(0,7)).all()).results;
    return json({activity,tasks,goals,usage});
  }
  if(path==='/v1/goals'&&req.method==='POST') {
    const data=await body(req),title=text(data.title,300),hour=integer(data.hour,0,23),id=crypto.randomUUID();
    const count=await env.DB.prepare('SELECT COUNT(*) count FROM goals WHERE user_id=?').bind(user.id).first<{count:number}>();
    if((count?.count||0)>=10)throw new ApiError(400,'At most ten responsibilities');
    await env.DB.prepare('INSERT INTO goals(id,user_id,title,kind,hour,next_run,created_at) VALUES(?,?,?,?,?,?,?)').bind(id,user.id,title,'checkin',hour,nextDailyRun(hour),Date.now()).run();return json({id},201);
  }
  if(path.startsWith('/v1/goals/')&&req.method==='PATCH') {
    const data=await body(req);if(typeof data.enabled!=='boolean')throw new ApiError(400,'Expected enabled');
    const goal=await env.DB.prepare('SELECT hour FROM goals WHERE id=? AND user_id=?').bind(path.slice(10),user.id).first<{hour:number}>();
    if(!goal)throw new ApiError(404,'Responsibility not found');
    await env.DB.prepare('UPDATE goals SET enabled=?,next_run=? WHERE id=? AND user_id=?').bind(data.enabled?1:0,nextDailyRun(goal.hour),path.slice(10),user.id).run();return json({ok:true});
  }
  if(path==='/v1/cloud-context'&&req.method==='DELETE') {
    await env.DB.batch(['transactions','budgets','tasks','activity','chat','goals','agent_corrections'].map(table=>env.DB.prepare(`DELETE FROM ${table} WHERE user_id=?`).bind(user.id)));
    try { await env.DB.prepare('DELETE FROM budget_plans WHERE user_id=?').bind(user.id).run(); } catch { /* Table exists only after migration 004. */ }
    await env.DB.prepare('UPDATE users SET ai_enabled=0,last_sync=0 WHERE id=?').bind(user.id).run();return json({ok:true});
  }
  if(path==='/v1/groups'&&req.method==='GET') {
    const groups=(await env.DB.prepare('SELECT g.id,g.name,g.owner_id FROM groups g JOIN members m ON m.group_id=g.id WHERE m.user_id=?').bind(user.id).all()).results;
    return json({groups});
  }
  if(path==='/v1/groups'&&req.method==='POST') {
    const data=await body(req),id=crypto.randomUUID(),invite=token();
    await env.DB.batch([
      env.DB.prepare('INSERT INTO groups(id,name,owner_id,invite_hash,invite_expires,created_at) VALUES(?,?,?,?,?,?)').bind(id,text(data.name,80),user.id,await hash(invite),Date.now()+7*86_400_000,Date.now()),
      env.DB.prepare('INSERT INTO members(group_id,user_id) VALUES(?,?)').bind(id,user.id),
    ]);return json({id,invite},201);
  }
  if(path==='/v1/groups/join'&&req.method==='POST') {
    const data=await body(req),invite=text(data.invite,64);
    const group=await env.DB.prepare('SELECT id FROM groups WHERE invite_hash=? AND invite_expires>?').bind(await hash(invite),Date.now()).first<{id:string}>();
    if(!group)throw new ApiError(404,'Invite expired or invalid');
    await env.DB.prepare('INSERT OR IGNORE INTO members(group_id,user_id) VALUES(?,?)').bind(group.id,user.id).run();return json({id:group.id});
  }
  const groupMatch=path.match(/^\/v1\/groups\/([^/]+)(?:\/(expenses))?$/);
  if(groupMatch) {
    const groupId=groupMatch[1];await membership(env,groupId,user.id);
    if(req.method==='GET') {
      const members=(await env.DB.prepare('SELECT u.id,u.name FROM members m JOIN users u ON u.id=m.user_id WHERE m.group_id=?').bind(groupId).all()).results;
      const expenses=(await env.DB.prepare('SELECT e.*,s.user_id,s.amount_paise share_paise FROM expenses e JOIN shares s ON s.expense_id=e.id WHERE e.group_id=? ORDER BY e.created_at DESC LIMIT 500').bind(groupId).all()).results;
      return json({members,expenses});
    }
    if(req.method==='POST'&&groupMatch[2]) {
      const data=await body(req);if(data.confirmed!==true)throw new ApiError(400,'Explicit confirmation is required');
      const amount=integer(data.amount_paise,1,1_000_000_000),key=text(data.idempotency_key,100),title=text(data.title,100);
      const existing=await env.DB.prepare('SELECT id,group_id FROM expenses WHERE creator_id=? AND idempotency_key=?').bind(user.id,key).first<{id:string;group_id:string}>();
      if(existing){if(existing.group_id!==groupId)throw new ApiError(409,'Idempotency key belongs to another group');return json({id:existing.id});}
      const ids=(await env.DB.prepare('SELECT user_id FROM members WHERE group_id=? ORDER BY user_id').bind(groupId).all<{user_id:string}>()).results.map(m=>m.user_id);
      const shares=equalShares(amount,ids),id=crypto.randomUUID();
      await env.DB.batch([
        env.DB.prepare('INSERT INTO expenses(id,group_id,creator_id,payer_id,amount_paise,title,idempotency_key,created_at) VALUES(?,?,?,?,?,?,?,?)').bind(id,groupId,user.id,user.id,amount,title,key,Date.now()),
        ...shares.map(s=>env.DB.prepare('INSERT INTO shares(expense_id,user_id,amount_paise) VALUES(?,?,?)').bind(id,s.user_id,s.amount_paise)),
      ]);return json({id,shares},201);
    }
  }
  throw new ApiError(404,'Route not found');
}
export default {
  async fetch(req:Request,env:Env,ctx:BackgroundContext):Promise<Response> {
    try {return await route(req,env,ctx);}catch(error){
      if(error instanceof ApiError)return json({error:error.message},error.status);
      return json({error:'Neko could not finish this request. Your local ledger is safe.'},500);
    }
  },
  async scheduled(env:Env):Promise<void> {await scheduled(env);},
};
