import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import pg from 'pg';

export async function migrate(url:string):Promise<void>{
  const pool=new pg.Pool({connectionString:url,max:1,connectionTimeoutMillis:15_000,statement_timeout:30_000});
  const client=await pool.connect();
  try{
    await client.query('BEGIN');
    await client.query("SELECT pg_advisory_xact_lock(78261106)");
    await client.query(await readFile(resolve('migrations/001_neko.sql'),'utf8'));
    await client.query("INSERT INTO neko.schema_migrations(version) VALUES('001_neko') ON CONFLICT DO NOTHING");
    await client.query(await readFile(resolve('migrations/002_spending_policy.sql'),'utf8'));
    await client.query("INSERT INTO neko.schema_migrations(version) VALUES('002_spending_policy') ON CONFLICT DO NOTHING");
    await client.query(await readFile(resolve('migrations/003_agent_learning.sql'),'utf8'));
    await client.query("INSERT INTO neko.schema_migrations(version) VALUES('003_agent_learning') ON CONFLICT DO NOTHING");
    await client.query(await readFile(resolve('migrations/004_budget_plan.sql'),'utf8'));
    await client.query("INSERT INTO neko.schema_migrations(version) VALUES('004_budget_plan') ON CONFLICT DO NOTHING");
    await client.query('COMMIT');
  }catch(error){await client.query('ROLLBACK');throw error;}finally{client.release();await pool.end();}
}
if(process.argv[1]?.endsWith('migrate.js')){
  const url=process.env.DATABASE_URL_UNPOOLED||process.env.DATABASE_URL;
  if(!url)throw new Error('Fill DATABASE_URL first');
  migrate(url).then(()=>console.log('Neko schema migration completed')).catch(()=>{console.error('Schema migration failed; credentials and SQL data are not logged');process.exitCode=1;});
}
