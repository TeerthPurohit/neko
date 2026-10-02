import { ApiError, categories, type Tx } from './types';
export function object(input: unknown): Record<string, unknown> {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new ApiError(400,'Expected an object');
  return input as Record<string, unknown>;
}
export function text(input: unknown, max = 200): string {
  if (typeof input !== 'string' || !input.trim() || input.length > max) throw new ApiError(400,'Invalid text');
  return input.trim();
}
export function integer(input: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  if (typeof input !== 'number' || !Number.isSafeInteger(input) || input < min || input > max) throw new ApiError(400,'Invalid integer');
  return input;
}
export function choice(input: unknown, choices: readonly string[]): string {
  if (typeof input !== 'string' || !choices.includes(input)) throw new ApiError(400,'Invalid choice');
  return input;
}
/** The month's total budget and income (paise) the user set in the app's budget interview. */
export interface Plan { month: string; budget_paise: number; income_paise: number }
export function plan(input: unknown): Plan {
  const p = object(input), month = text(p.month, 7);
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new ApiError(400,'Invalid plan month');
  return { month, budget_paise: integer(p.budget_paise,1,1_000_000_000), income_paise: p.income_paise === undefined ? 0 : integer(p.income_paise,0,1_000_000_000) };
}
export function redact(input: string): string {
  return input.replace(/[\w.+-]+@[\w.-]+/g,'[private counterparty]').replace(/\b\d{4,}\b/g,'[private number]').slice(0,100);
}
export function transaction(input: unknown): Tx {
  const t = object(input);
  if (Object.keys(t).some(k => /sms|body|otp|password|pin|reference|notes/i.test(k))) throw new ApiError(400,'Raw SMS and sensitive fields are not accepted');
  const amount=integer(t.amount_paise,1,1_000_000_000);
  const direction=choice(t.direction,['DEBIT','CREDIT']) as Tx['direction'];
  const spendingTreatment=choice(t.spending_treatment ?? 'AUTO',['AUTO','REVIEW_REQUIRED','PERSONAL_SPENDING','FRIEND_REIMBURSEMENT','FUNDING','INCOME','FD_PRINCIPAL','INVESTMENT_PRINCIPAL','INVESTMENT_RETURN','REFUND','TEMPORARY_MOVEMENT']);
  const principal=t.principal_paise == null ? null : integer(t.principal_paise,0,1_000_000_000);
  const related=t.related_transaction_id == null ? null : text(t.related_transaction_id,100);
  const debitTreatments=['PERSONAL_SPENDING','FD_PRINCIPAL','INVESTMENT_PRINCIPAL'];
  const creditTreatments=['REVIEW_REQUIRED','FRIEND_REIMBURSEMENT','FUNDING','INCOME','INVESTMENT_RETURN','REFUND'];
  if((direction==='DEBIT'&&creditTreatments.includes(spendingTreatment))||(direction==='CREDIT'&&debitTreatments.includes(spendingTreatment)))throw new ApiError(400,'Spending treatment does not match transaction direction');
  if(related!==null&&!['FRIEND_REIMBURSEMENT','REFUND','TEMPORARY_MOVEMENT'].includes(spendingTreatment))throw new ApiError(400,'This spending treatment cannot link another transaction');
  if(['FRIEND_REIMBURSEMENT','REFUND','TEMPORARY_MOVEMENT'].includes(spendingTreatment)&&related===null)throw new ApiError(400,'This spending treatment requires a linked transaction');
  if(spendingTreatment==='INVESTMENT_RETURN'&&(principal===null||principal>amount))throw new ApiError(400,'Investment return must include a principal no larger than its amount');
  if(spendingTreatment!=='INVESTMENT_RETURN'&&principal!==null)throw new ApiError(400,'Principal is only accepted for an investment return');
  return {
    id:text(t.id,100), occurred_at:integer(t.occurred_at,0,Date.now()+86_400_000), amount_paise:amount,
    direction, category:choice(t.category,categories),
    merchant:redact(text(t.merchant,100)), status:choice(t.status,['POSTED','PENDING','FAILED','REVERSED']),
    review:choice(t.review,['DRAFT','CONFIRMED']), account_alias:redact(text(t.account_alias,50)),
    transfer_id:t.transfer_id == null ? null : text(t.transfer_id,100), updated_at:integer(t.updated_at,0,Date.now()+86_400_000),
    spending_treatment:spendingTreatment,
    related_transaction_id:related,
    principal_paise:principal,
  };
}
export function equalShares(amount: number, ids: string[]): { user_id: string; amount_paise: number }[] {
  integer(amount,1,1_000_000_000);
  if (!ids.length || new Set(ids).size !== ids.length) throw new ApiError(400,'Invalid members');
  const sorted = [...ids].sort();
  return sorted.map((user_id,i) => ({ user_id, amount_paise: Math.floor(amount/sorted.length)+(i<amount%sorted.length?1:0) }));
}
export function confirmedProposal(input: unknown): { transaction_id: string; category: string; expected_updated_at: number; reason: string } {
  const p = object(input);
  return { transaction_id:text(p.transaction_id,100), category:choice(p.category,categories), expected_updated_at:integer(p.expected_updated_at), reason:text(p.reason,300) };
}
