import pg from 'pg';

// Neko stores paise and timestamps below JS's exact-integer boundary.
pg.types.setTypeParser(20, Number);
const tables='users|sessions|transactions|budgets|goals|tasks|activity|chat|usage|groups|members|expenses|shares|auth_attempts|agent_corrections';
export function postgresSql(sql:string):string {
  let index=0,quoted=false,out='';
  for(let i=0;i<sql.length;i++){
    const c=sql[i];
    if(c==="'"){if(quoted&&sql[i+1]==="'"){out+="''";i++;continue;}quoted=!quoted;}
    out+=c==='?'&&!quoted?'$'+(++index):c;
  }
  const ignore=/^INSERT OR IGNORE /i.test(out);
  out=out.replace(/^INSERT OR IGNORE /i,'INSERT ');
  out=out.replace(new RegExp('\\b(FROM|JOIN|UPDATE|INTO)\\s+('+tables+')\\b','gi'),(_m,verb,name)=>verb+' neko.'+name);
  out=out.replace(/MAX\(0,reserved-\$/g,'GREATEST(0,reserved-$');
  if(ignore)out+=' ON CONFLICT DO NOTHING';
  return out;
}
export class Statement {
  constructor(readonly db:Database,readonly sql:string,readonly values:unknown[]=[]){ }
  bind(...values:unknown[]):Statement{return new Statement(this.db,this.sql,values);}
  async first<T=Record<string,unknown>>():Promise<T|null>{return (await this.db.pool.query(postgresSql(this.sql),this.values)).rows[0] as T||null;}
  async all<T=Record<string,unknown>>():Promise<{results:T[]}>{return {results:(await this.db.pool.query(postgresSql(this.sql),this.values)).rows as T[]};}
  async run():Promise<{meta:{changes:number}}>{const result=await this.db.pool.query(postgresSql(this.sql),this.values);return {meta:{changes:result.rowCount||0}};}
}
export class Database {
  readonly pool:pg.Pool;
  constructor(url:string){this.pool=new pg.Pool({connectionString:url,max:5,connectionTimeoutMillis:15_000,idleTimeoutMillis:30_000});}
  prepare(sql:string):Statement{return new Statement(this,sql);}
  async batch(statements:Statement[]):Promise<void>{
    const client=await this.pool.connect();
    try{await client.query('BEGIN');for(const s of statements)await client.query(postgresSql(s.sql),s.values);await client.query('COMMIT');}
    catch(error){await client.query('ROLLBACK');throw error;}finally{client.release();}
  }
  async close():Promise<void>{await this.pool.end();}
}
