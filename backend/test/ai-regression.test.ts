import { describe, expect, it } from 'vitest';
import { reserve, summarize } from '../src/ai';
import type { Env, User } from '../src/types';

function fakeEnv(daily: string, monthly: string): Env {
  return {
    DAILY_AI_REQUESTS: daily,
    MONTHLY_AI_BUDGET_USD: monthly,
    DB: {
      prepare(sql: string) {
        return {
          bind(...values: unknown[]) {
            return {
              async run() {
                const blocked = sql.startsWith('UPDATE usage') && (values[3] === 0 || values[7] === 0);
                return { meta: { changes: blocked ? 0 : 1 } };
              },
            };
          },
        };
      },
    },
  } as unknown as Env;
}

describe('AI budget and financial summary regressions', () => {
  it('enforces an explicitly configured zero daily limit', async () => {
    const user = { id: 'unit-test-user' } as User;
    await expect(reserve(fakeEnv('0', '2'), user)).rejects.toThrow('Your AI allowance is reached');
  });

  it('enforces an explicitly configured zero monthly limit', async () => {
    const user = { id: 'unit-test-user' } as User;
    await expect(reserve(fakeEnv('100', '0'), user)).rejects.toThrow('Your AI allowance is reached');
  });

  it('does not net an unlinked incoming refund against personal spending', () => {
    type Tx = Parameters<typeof summarize>[0][number];
    const tx = (overrides: Partial<Tx>): Tx => ({
      id: 'expense', occurred_at: Date.parse('2026-10-02T10:00:00Z'), amount_paise: 10_000,
      direction: 'DEBIT', category: 'FOOD', merchant: 'Cafe', status: 'POSTED', review: 'CONFIRMED',
      account_alias: 'ICICI', transfer_id: null, updated_at: Date.parse('2026-10-02T10:00:00Z'),
      spending_treatment: 'AUTO', related_transaction_id: null, principal_paise: null,
      ...overrides,
    } as Tx);
    const result = summarize([
      tx({}),
      tx({ id: 'credit', direction: 'CREDIT', category: 'REFUND', merchant: 'Cashback' }),
    ]);
    expect(result.net_spending_paise).toBe(10_000);
    expect(result.refunds_paise).toBe(0);
  });

  it('counts only my share of a split payment, and a friend repayment settles the split', () => {
    type Tx = Parameters<typeof summarize>[0][number];
    const base = { occurred_at: Date.parse('2026-10-02T10:00:00Z'), status: 'POSTED', review: 'CONFIRMED', account_alias: 'ICICI', transfer_id: null,
      updated_at: Date.parse('2026-10-02T10:00:00Z'), principal_paise: null } as const;
    const result = summarize([
      { ...base, id: 'dinner', amount_paise: 90_000, direction: 'DEBIT', category: 'FOOD', merchant: 'Barbeque Nation', spending_treatment: 'AUTO', related_transaction_id: null, personal_share_paise: 30_000 } as Tx,
      { ...base, id: 'repaid', amount_paise: 30_000, direction: 'CREDIT', category: 'OTHER', merchant: 'Ravi', spending_treatment: 'FRIEND_REIMBURSEMENT', related_transaction_id: 'dinner' } as Tx,
    ]);
    expect(result.spending_paise).toBe(30_000);
    expect(result.net_spending_paise).toBe(30_000);
    expect(result.by_category.FOOD).toBe(30_000);
  });
});
