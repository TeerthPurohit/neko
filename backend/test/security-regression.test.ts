import { describe, expect, it } from 'vitest';
import api from '../src/index';
import type { Env } from '../src/types';

class PairedDeviceDatabase {
  private user: Record<string, unknown> | null = null;

  prepare(sql: string) {
    const values: unknown[] = [];
    const db = this;
    const statement = {
      bind(...args: unknown[]) { values.push(...args); return statement; },
      async first<T>() {
        if (sql.startsWith('SELECT u.* FROM sessions')) return null;
        if (sql.startsWith('SELECT * FROM users WHERE token_hash=')) {
          return db.user?.token_hash === values[0] ? db.user as T : null;
        }
        return null;
      },
      async run() {
        if (sql.startsWith('INSERT INTO users(')) {
          const [id,name,token_hash,model,created_at] = values;
          db.user = { id,name,token_hash,model,created_at,email:null,ai_enabled:0,last_sync:0 };
        }
        if (sql.startsWith('UPDATE users SET token_hash=') && db.user?.token_hash === values[1] && db.user?.email === null) {
          db.user.token_hash = values[0];
          return { meta: { changes: 1 } };
        }
        return { meta: { changes: 0 } };
      },
    };
    return statement;
  }
}

async function request(path: string, env: Env, method = 'GET', body?: unknown, token?: string) {
  return api.fetch(new Request('https://neko.test'+path, {
    method,
    headers: { 'Content-Type':'application/json', ...(token ? { Authorization:'Bearer '+token } : {}) },
    ...(body === undefined ? {} : { body:JSON.stringify(body) }),
  }),env,{ waitUntil:()=>{} });
}

describe('paired device logout regression', () => {
  it('invalidates the paired token instead of accepting it through the anonymous-user fallback', async () => {
    const env = {
      DB: new PairedDeviceDatabase(),
      NEKO_PAIRING_SECRET:'p'.repeat(64),
      CHAT_MODELS:'xiaomi/mimo-v2.6-pro',
    } as unknown as Env;
    const pairedResponse = await request('/v1/pair',env,'POST',{secret:env.NEKO_PAIRING_SECRET,name:'Test device'});
    const paired = await pairedResponse.json() as { device_token:string };

    expect((await request('/v1/auth/logout',env,'POST',{},paired.device_token)).status).toBe(200);
    expect((await request('/v1/settings',env,'GET',undefined,paired.device_token)).status).toBe(401);
  });
});
