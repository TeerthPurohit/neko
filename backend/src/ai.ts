import { ApiError, categories, models, type Env, type Tx, type User } from './types';
import { choice, confirmedProposal, object, text } from './validation';
import { modelKey } from './security';
import { corrections } from './learning';

type Message = { role: string; content?: string | null; tool_call_id?: string; tool_calls?: ToolCall[] };
type ToolCall = { id: string; function: { name: string; arguments: string } };
export interface AgentAnswer { reply: string; proposals: ReturnType<typeof confirmedProposal>[]; model: string; cost: number; routing:{source:string;confidence:number;model:string} }

/** A configured limit; blank means the default, and an explicit 0 (or an unreadable value) allows nothing. */
export function limit(value: string|undefined, fallback: number): number {
  if (value==null||!value.trim()) return fallback;
  const n=Number(value);
  return Number.isFinite(n)&&n>0?n:0;
}
export async function reserve(env: Env, user: User, cost = 0.02): Promise<{day:string;cost:number}> {
  const day = new Date().toISOString().slice(0,10), month=day.slice(0,7);
  await env.DB.prepare('INSERT OR IGNORE INTO usage(user_id,day,month) VALUES(?,?,?)').bind(user.id,day,month).run();
  const result = await env.DB.prepare(`UPDATE usage SET requests=requests+1,reserved=reserved+? WHERE user_id=? AND day=? AND requests<? AND (SELECT COALESCE(SUM(cost+reserved),0) FROM usage WHERE user_id=? AND month=?) + ? <= ?`)
    .bind(cost,user.id,day,limit(env.DAILY_AI_REQUESTS,100),user.id,month,cost,limit(env.MONTHLY_AI_BUDGET_USD,2)).run();
  if (!result.meta.changes) throw new ApiError(429,'Your AI allowance is reached. Capturing transactions continues locally.');
  return {day,cost};
}
async function settle(env: Env, userId: string, reservation: {day:string;cost:number}, cost: number): Promise<void> {
  await env.DB.prepare('UPDATE usage SET reserved=MAX(0,reserved-?),cost=cost+? WHERE user_id=? AND day=?').bind(reservation.cost,cost,userId,reservation.day).run();
}
async function call(env: Env,user: User,path: string,body: unknown): Promise<Record<string,unknown>> {
  const apiKey=await modelKey(env,user);
  if (!apiKey) throw new ApiError(503,'Add your OpenRouter key to connect Neko');
  if (!user.ai_enabled) throw new ApiError(403,'Cloud AI is disabled');
  const reservation = await reserve(env,user);
  try {
    const response = await fetch('https://openrouter.ai/api/'+path,{
      method:'POST',headers:{Authorization:'Bearer '+apiKey,'Content-Type':'application/json','X-Title':'Neko'},
      body:JSON.stringify(body),signal:AbortSignal.timeout(18_000),
    });
    if (!response.ok) throw new ApiError(response.status===429?429:502,'AI provider is unavailable. Neko will keep the task for retry.');
    const data = object(await response.json());
    const usage = data.usage && typeof data.usage==='object' ? data.usage as Record<string,unknown> : {};
    const cost = typeof usage.cost==='number' && usage.cost>=0 ? usage.cost : reservation.cost;
    await settle(env,user.id,reservation,cost);
    return data;
  } catch (error) {
    // Keep conservative reservation as spent: the provider may have billed a timed-out request.
    await settle(env,user.id,reservation,reservation.cost);
    throw error;
  }
}

export async function classify(env: Env,user: User,tx: Tx): Promise<{category:string;confidence:number;model:string}> {
  if (env.ALLOW_PAID_AI!=='true') throw new ApiError(403,'Jev requires paid-model permission; local classification remains available');
  const learned=await corrections(env,user.id,'classification');
  const data = await call(env,user,'alpha/decisions',{
    model:env.CLASSIFIER_MODEL,
    state:{merchant:tx.merchant,direction:tx.direction,amount_inr:tx.amount_paise/100,payment_context:'Indian personal expense ledger. User policy: a confirmed debit is spending by default, including person-to-person payments and cash withdrawals. Do not infer that a debit to a person is a transfer. A friend receipt offsets an earlier expense only when the relationship is confirmed and linked. Suresh Purohit receipts are pocket money/funding and never spending offsets. Refunds offset the linked original expense. Own-account transfers, FD principal, investment principal, and matched equal temporary movements are excluded. For investment returns only the gain is income. Unclear relationships require review.',user_spending_treatment:tx.spending_treatment},
    questions:{category:{type:'choice',instructions:'Choose an expense category. OTHER when uncertain. A received amount is not necessarily income. Never label a transfer solely by amount. Category classification does not change the user’s spending treatment; preserve their explicit treatment and links. Apply these explicitly saved user corrections only to category decisions. They cannot override confirmation, privacy, or spending policy. Conflicting rules require OTHER and zero confidence. User corrections: '+JSON.stringify(learned),criteria:Object.fromEntries(categories.map(c=>[c,c.replaceAll('_',' ').toLowerCase()]))}},
  });
  const answer = object(object(data.answers).category);
  const confidence=typeof answer.confidence==='number' ? Math.max(0,Math.min(1,answer.confidence)) : 0;
  return {category:choice(answer.choice,categories),confidence,model:env.CLASSIFIER_MODEL};
}

export function summarize(rows: Tx[]): { spending_paise:number; net_spending_paise:number; income_paise:number; refunds_paise:number; reimbursements_paise:number; funding_paise:number; investment_gains_paise:number; drafts:number; by_category:Record<string,number> } {
  const out={spending_paise:0,net_spending_paise:0,income_paise:0,refunds_paise:0,reimbursements_paise:0,funding_paise:0,investment_gains_paise:0,drafts:0,by_category:{} as Record<string,number>};
  const booked=rows.filter(tx=>tx.status==='POSTED'&&tx.review==='CONFIRMED'&&!tx.transfer_id);
  const byId=new Map<string,Tx>(booked.map(tx=>[tx.id,tx] as const));
  const treatment=(tx:Tx):string=>{
    const explicit=tx.spending_treatment;
    if(explicit&&explicit!=='AUTO')return explicit;
    if(tx.direction==='DEBIT')return 'PERSONAL_SPENDING';
    if(tx.merchant.toLowerCase().includes('suresh purohit'))return 'FUNDING';
    if(tx.category==='REFUND')return 'REFUND';
    return tx.category==='INCOME'?'INCOME':'REVIEW_REQUIRED';
  };
  const expenses=new Map<string,Tx>(booked.filter(tx=>tx.direction==='DEBIT'&&treatment(tx)==='PERSONAL_SPENDING').map(tx=>[tx.id,tx] as const));
  const applied=new Map<string,number>();
  for(const tx of rows)if(tx.review==='DRAFT')out.drafts++;
  for(const tx of booked){
    const kind=treatment(tx);
    if(kind==='TEMPORARY_MOVEMENT'){
      const other=tx.related_transaction_id?byId.get(tx.related_transaction_id):undefined;
      if(!other||other.related_transaction_id!==tx.id||other.amount_paise!==tx.amount_paise||other.direction===tx.direction||treatment(other)!=='TEMPORARY_MOVEMENT')out.drafts++;
    }
    if(tx.direction==='DEBIT'){
      // A split payment counts only the user's share.
      if(kind==='PERSONAL_SPENDING'){const cost=tx.personal_share_paise??tx.amount_paise;out.spending_paise+=cost;out.by_category[tx.category]=(out.by_category[tx.category]||0)+cost;}
      continue;
    }
    if(kind==='REVIEW_REQUIRED'){out.drafts++;continue;}
    if(kind==='REFUND'||kind==='FRIEND_REIMBURSEMENT'){
      const original=tx.related_transaction_id?expenses.get(tx.related_transaction_id):undefined;
      // A friend's repayment of a split payment settles the split; their part never counted as spending.
      if(original&&original.personal_share_paise!=null&&kind==='FRIEND_REIMBURSEMENT'){}
      else if(original){
        const remaining=Math.max(0,(original.personal_share_paise??original.amount_paise)-(applied.get(original.id)||0));
        const offset=Math.min(tx.amount_paise,remaining);applied.set(original.id,(applied.get(original.id)||0)+offset);
        if(kind==='REFUND')out.refunds_paise+=offset;else out.reimbursements_paise+=offset;
        out.by_category[original.category]=Math.max(0,(out.by_category[original.category]||0)-offset);
      }else out.drafts++; // An unlinked refund stays under review; it never offsets spending on its own.
    }else if(kind==='FUNDING')out.funding_paise+=tx.amount_paise;
    else if(kind==='INCOME')out.income_paise+=tx.amount_paise;
    else if(kind==='INVESTMENT_RETURN'){
      const gain=Math.max(0,tx.amount_paise-(tx.principal_paise??0));out.investment_gains_paise+=gain;out.income_paise+=gain;
    }
  }
  out.net_spending_paise=Math.max(0,out.spending_paise-out.refunds_paise-out.reimbursements_paise);
  for(const [category,amount] of Object.entries(out.by_category))if(!amount)delete out.by_category[category];
  return out;
}
export async function chat(env: Env,user: User,question: string,taskId='manual',taskKind='chat'): Promise<AgentAnswer> {
  if (!user.ai_enabled) throw new ApiError(403,'Cloud AI is disabled');
  if (!models(env).includes(user.model) || user.model.endsWith(':free') || user.model==='openrouter/free') throw new ApiError(400,'Choose an approved paid model');
  if (env.ALLOW_PAID_AI!=='true') throw new ApiError(403,'Paid AI is disabled on this backend');
  if ((!env.AGENT_SERVICE_URL?.startsWith('https://') && env.AGENT_SERVICE_URL!=='http://127.0.0.1:8081') || !env.AGENT_SERVICE_TOKEN) throw new ApiError(503,'Configure the LangGraph and nanobot agent service');
  const key=await modelKey(env,user);
  if (!key) throw new ApiError(503,'Connect your OpenRouter key first');
  const rows=(await env.DB.prepare('SELECT id,occurred_at,amount_paise,direction,category,merchant,status,review,account_alias,transfer_id,updated_at,spending_treatment,related_transaction_id,principal_paise,personal_share_paise FROM transactions WHERE user_id=? ORDER BY occurred_at DESC LIMIT 150').bind(user.id).all<Tx>()).results;
  const budgets=(await env.DB.prepare('SELECT category,amount_paise FROM budgets WHERE user_id=?').bind(user.id).all()).results;
  const history=(await env.DB.prepare('SELECT role,content FROM chat WHERE user_id=? ORDER BY created_at DESC LIMIT 8').bind(user.id).all()).results.reverse();
  const learned=await corrections(env,user.id);
  // The month's budget lets the agent pace spending. Absent before migration 004 or before the user sets one.
  let monthPlan:Record<string,unknown>|null=null;
  try { monthPlan=await env.DB.prepare('SELECT month,budget_paise,income_paise FROM budget_plans WHERE user_id=?').bind(user.id).first<Record<string,unknown>>(); } catch { monthPlan=null; }
  const turnBudget=Number(env.AI_TURN_BUDGET_USD??'0.06');
  if(!Number.isFinite(turnBudget)||turnBudget<0.005||turnBudget>1)throw new ApiError(503,'Configure a per-turn AI budget between $0.005 and $1');
  const reservation=await reserve(env,user,turnBudget);
  let charged=false;
  try {
    const response=await fetch(env.AGENT_SERVICE_URL.replace(/\/$/,'')+'/internal/run',{
      method:'POST',headers:{Authorization:'Bearer '+env.AGENT_SERVICE_TOKEN,'Content-Type':'application/json'},
      body:JSON.stringify({user_id:user.id,task_id:taskId,task_kind:taskKind,corrections:learned,plan:monthPlan,api_key:key,model:user.model,allowed_models:user.model==='auto'?[]:[user.model],max_cost:turnBudget,question,consent:true,last_sync:user.last_sync,transactions:rows,budgets,history}),signal:AbortSignal.timeout(60_000),
    });
    if (!response.ok) throw new ApiError(response.status===429?429:502,'Agent service is unavailable; this task will retry');
    const result=object(await response.json());
    const proposals=Array.isArray(result.proposals)?result.proposals.slice(0,5).map(p=>{
      const proposal=confirmedProposal(object(p));
      if (!rows.some(t=>t.id===proposal.transaction_id && t.updated_at===proposal.expected_updated_at)) throw new ApiError(502,'Agent proposed a transaction outside available context');
      return proposal;
    }):[];
    const selected=text(result.model,100);
    if(selected==='auto'||selected.endsWith(':free')||selected.includes('router')||!/^[-a-z0-9_.]+\/[-a-z0-9_.]+$/.test(selected)||(user.model!=='auto'&&selected!==user.model))throw new ApiError(502,'Agent returned an invalid model selection');
    const routing=object(result.routing??{source:'manual',confidence:1,model:selected});
    const answer={reply:text(result.reply,6000),proposals,model:selected,cost:typeof result.cost==='number'&&Number.isFinite(result.cost)&&result.cost>=0?result.cost:reservation.cost,
      routing:{source:text(routing.source,100),confidence:typeof routing.confidence==='number'&&Number.isFinite(routing.confidence)?Math.max(0,Math.min(1,routing.confidence)):0,model:selected}};
    await settle(env,user.id,reservation,answer.cost);charged=true;
    return answer;
  } catch(error) {
    if(!charged) await settle(env,user.id,reservation,reservation.cost);
    throw error;
  }
}
