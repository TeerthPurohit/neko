import type { Database } from './database';
export interface BackgroundContext { waitUntil(task:Promise<unknown>):void }
export interface Env {
  DB: Database;
  NEKO_PAIRING_SECRET: string;
  MODEL_KEY_ENCRYPTION_KEY?: string;
  AGENT_SERVICE_URL?: string;
  AGENT_SERVICE_TOKEN?: string;
  CHAT_MODELS: string;
  CLASSIFIER_MODEL: string;
  ALLOW_PAID_AI: string;
  MONTHLY_AI_BUDGET_USD: string;
  DAILY_AI_REQUESTS: string;
  AI_TURN_BUDGET_USD?: string;
  FCM_PROJECT_ID?: string;
  FCM_CLIENT_EMAIL?: string;
  FCM_PRIVATE_KEY?: string;
}
export interface User { id: string; name: string; ai_enabled: number; model: string; push_token: string | null; last_sync: number; model_key_ciphertext?: string | null }
export interface Tx {
  id: string; occurred_at: number; amount_paise: number; direction: 'DEBIT' | 'CREDIT'; category: string;
  merchant: string; status: string; review: string; account_alias: string; transfer_id: string | null; updated_at: number;
  spending_treatment?: string; related_transaction_id?: string | null; principal_paise?: number | null;
}
export interface Task { id: string; user_id: string; kind: string; payload: string; status: string; attempts: number; due_at: number; lease_until: number }
export const categories = ['FOOD','GROCERIES','TRANSPORT','SHOPPING','BILLS','HEALTH','ENTERTAINMENT','RENT','INCOME','REFUND','TRANSFER','OTHER'] as const;
export class ApiError extends Error { constructor(public status: number, message: string) { super(message); } }
export const models = (env: Env): string[] => env.CHAT_MODELS.split(',').map(s => s.trim()).filter(Boolean);
