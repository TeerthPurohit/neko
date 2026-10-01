import { chat, classify } from './ai';
import { push } from './push';
import { ApiError, type Env, type Task, type Tx, type User } from './types';

export async function enqueue(env:Env,userId:string,kind:string,payload:unknown,id:string=crypto.randomUUID()):Promise<string> {
  await env.DB.prepare('INSERT OR IGNORE INTO tasks(id,user_id,kind,payload,due_at,created_at) VALUES(?,?,?,?,?,?)').bind(id,userId,kind,JSON.stringify(payload),Date.now(),Date.now()).run();
  return id;
}
export async function processTask(env:Env,id:string):Promise<void> {
  const now=Date.now();
  const claimed=await env.DB.prepare(`UPDATE tasks SET status='running',attempts=attempts+1,lease_until=? WHERE id=? AND ((status='queued' AND due_at<=?) OR (status='running' AND lease_until<?)) AND attempts<4`).bind(now+120_000,id,now,now).run();
  if(!claimed.meta.changes) return;
  const task=await env.DB.prepare('SELECT * FROM tasks WHERE id=?').bind(id).first<Task>();
  if(!task) return;
  const user=await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(task.user_id).first<User>();
  if(!user) return;
  try {
    const payload=JSON.parse(task.payload) as {question?:string;transaction_id?:string;expected_updated_at?:number};
    let title='Neko checked in',body='',proposal:unknown=null;
    if(task.kind==='classify') {
      const tx=await env.DB.prepare('SELECT * FROM transactions WHERE user_id=? AND id=?').bind(user.id,payload.transaction_id).first<Tx>();
      if(!tx||tx.updated_at!==payload.expected_updated_at) {
        await env.DB.prepare("UPDATE tasks SET status='done',result='Superseded by a newer transaction' WHERE id=?").bind(id).run();return;
      }
      const result=await classify(env,user,tx);
      title='Category suggestion';body=`Neko suggests ${result.category.toLowerCase()} (${Math.round(result.confidence*100)}% confidence). Review before applying.`;
      proposal={transaction_id:tx.id,category:result.category,expected_updated_at:tx.updated_at,reason:body,confidence:result.confidence,model:result.model};
    } else {
      const result=await chat(env,user,payload.question||'Review the latest synced spending and pending drafts. Offer one useful follow-up; state coverage and last sync.',id,task.kind);
      title=task.kind==='chat'?'Neko replied':'Your scheduled check-in';body=result.reply;proposal=result.proposals.length?result.proposals:null;
    }
    const activityId='result:'+id;
    // Each write checks the original lease and consent inside the same batch.
    // An erased/disabled context or reclaimed task cannot resurrect an old reply.
    const guard="EXISTS(SELECT 1 FROM tasks t JOIN users u ON u.id=t.user_id WHERE t.id=? AND t.created_at=? AND t.attempts=? AND t.lease_until=? AND t.status='running' AND u.ai_enabled=1)";
    const params=[id,(task as Task & {created_at:number}).created_at,task.attempts,task.lease_until];
    await env.DB.batch([
      ...(task.kind==='chat'?[env.DB.prepare(`INSERT OR IGNORE INTO chat(id,user_id,role,content,created_at) SELECT ?,?,?,?,? WHERE ${guard}`).bind(id,user.id,'assistant',body,Date.now(),...params)]:[]),
      env.DB.prepare(`INSERT OR IGNORE INTO activity(id,user_id,kind,title,body,proposal,created_at) SELECT ?,?,?,?,?,?,? WHERE ${guard}`).bind(activityId,user.id,task.kind,title,body,proposal?JSON.stringify(proposal):null,Date.now(),...params),
      env.DB.prepare(`UPDATE tasks SET status='done',result=?,lease_until=0 WHERE id=? AND ${guard}`).bind(JSON.stringify({activity_id:activityId}),id,...params),
    ]);
  } catch(error) {
    const permanent=error instanceof ApiError && [400,403].includes(error.status);
    const retry=!permanent&&task.attempts<4;
    const message=error instanceof ApiError?error.message:'Agent task interrupted. It will retry when available.';
    await env.DB.prepare('UPDATE tasks SET status=?,due_at=?,lease_until=0,result=? WHERE id=?').bind(retry?'queued':'failed',Date.now()+Math.min(3_600_000,60_000*2**task.attempts),message,id).run();
    if(!retry) await env.DB.prepare('INSERT OR IGNORE INTO activity(id,user_id,kind,title,body,created_at) VALUES(?,?,?,?,?,?)').bind('failed:'+id,user.id,'error','Agent task needs attention',message,Date.now()).run();
  }
}
export function nextDailyRun(hour:number,now=Date.now()):number {
  // v1 schedules explicitly use IST (no DST); timezone selection is not advertised.
  const shift=330*60_000,d=new Date(now+shift);d.setUTCHours(hour,0,0,0);
  let result=d.getTime()-shift;if(result<=now) result+=86_400_000;return result;
}
export async function scheduled(env:Env):Promise<void> {
  const now=Date.now();
  const goals=(await env.DB.prepare('SELECT id,user_id,title,hour,next_run FROM goals WHERE enabled=1 AND next_run<=? LIMIT 20').bind(now).all<{id:string;user_id:string;title:string;hour:number;next_run:number}>()).results;
  for(const goal of goals) {
    const id=`goal:${goal.id}:${goal.next_run}`;
    await enqueue(env,goal.user_id,'checkin',{question:`Your authorized standing responsibility: ${goal.title}. Give a short useful check-in from synced data. State any stale data or missing coverage.`},id);
    await env.DB.prepare('UPDATE goals SET next_run=?,last_run=? WHERE id=? AND next_run=?').bind(nextDailyRun(goal.hour,now),now,goal.id,goal.next_run).run();
  }
  const tasks=(await env.DB.prepare("SELECT id FROM tasks WHERE ((status='queued' AND due_at<=?) OR (status='running' AND lease_until<?)) AND attempts<4 ORDER BY due_at LIMIT 10").bind(now,now).all<{id:string}>()).results;
  // Sequential runs bound provider load. Persistent leases protect against overlapping cron/request execution.
  for(const task of tasks) await processTask(env,task.id);
  const notifications=(await env.DB.prepare('SELECT a.id,u.push_token FROM activity a JOIN users u ON u.id=a.user_id WHERE a.push_sent=0 AND a.created_at>? ORDER BY a.created_at LIMIT 10').bind(now-7*86_400_000).all<{id:string;push_token:string|null}>()).results;
  for(const item of notifications) {
    try { if(await push(env,item.push_token,item.id)) await env.DB.prepare('UPDATE activity SET push_sent=1 WHERE id=?').bind(item.id).run(); } catch { /* Durable activity remains available through polling. */ }
  }
}
