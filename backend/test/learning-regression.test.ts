import { describe, expect, it } from 'vitest';
import { saveCorrection } from '../src/learning';
import type { Env } from '../src/types';

class LearningMemoryDatabase {
  readonly corrections = new Map<string, { id:string;user_id:string;correction:string }>();

  prepare(sql: string) {
    let values: unknown[] = [];
    const db = this;
    return {
      bind(...args: unknown[]) { values=args; return this; },
      async first<T>() {
        if (sql.startsWith('SELECT id FROM agent_corrections')) {
          const row=db.corrections.get(String(values[0]));
          return row?.user_id===values[1] ? row as T : null;
        }
        return null;
      },
      async run() { return { meta:{changes:1} }; },
      sql,
      values:()=>values,
    };
  }

  async batch(statements: Array<{ values:()=>unknown[] }>) {
    const [id,_scope,_behavior,correction,_createdAt,userId]=statements[1].values();
    this.corrections.set(String(id),{id:String(id),user_id:String(userId),correction:String(correction)});
    return [];
  }
}

describe('durable learning privacy regression', () => {
  it('rejects an OTP before saving a correction', async () => {
    const DB=new LearningMemoryDatabase();
    const env={DB} as unknown as Env;
    await expect(saveCorrection(env,'user-1',{correction:'My bank OTP is 123456'})).rejects.toThrow();
    expect(DB.corrections.size).toBe(0);
  });
});
