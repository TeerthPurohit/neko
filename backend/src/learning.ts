import { ApiError, type Env } from './types';
import { choice, rejectSensitive, text } from './validation';

export const agentScopes = ['all','classification','ledger','budget','summary','followup','chat'] as const;
export interface Correction { id:string; scope:string; behavior:string; correction:string; created_at:number }

export async function corrections(env:Env,userId:string,scope?:string):Promise<Correction[]> {
  const rows=(await env.DB.prepare('SELECT id,scope,behavior,correction,created_at FROM agent_corrections WHERE user_id=? ORDER BY created_at,id').bind(userId).all<Correction>()).results;
  return scope?rows.filter(r=>r.scope==='all'||r.scope===scope):rows;
}

export async function saveCorrection(env:Env,userId:string,data:Record<string,unknown>):Promise<string> {
  const scope=choice(data.scope??'all',agentScopes),behavior=text(data.behavior??'User explicitly corrected future behavior',500),correction=text(data.correction,1000);
  rejectSensitive(behavior+' '+correction);
  const id=crypto.randomUUID();
  // Serialize saves for this account; the next statement gets a fresh snapshot
  // after taking the row lock, so concurrent requests cannot exceed the limit.
  await env.DB.batch([
    env.DB.prepare('UPDATE users SET last_sync=last_sync WHERE id=?').bind(userId),
    env.DB.prepare(`INSERT INTO agent_corrections(id,user_id,scope,behavior,correction,created_at)
    SELECT ?,id,?,?,?,? FROM users WHERE id=? AND (SELECT COUNT(*) FROM agent_corrections WHERE user_id=?)<30`)
      .bind(id,scope,behavior,correction,Date.now(),userId,userId),
  ]);
  if(!await env.DB.prepare('SELECT id FROM agent_corrections WHERE id=? AND user_id=?').bind(id,userId).first())throw new ApiError(400,'At most 30 saved corrections. Remove an old rule first.');
  return id;
}
