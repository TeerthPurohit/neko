import { ApiError, type Env, type User } from './types';
export async function hash(value: string): Promise<string> {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)));
  return [...bytes].map(x=>x.toString(16).padStart(2,'0')).join('');
}
export function token(): string { return [...crypto.getRandomValues(new Uint8Array(32))].map(x=>x.toString(16).padStart(2,'0')).join(''); }
export async function authenticate(req: Request, env: Env): Promise<User> {
  const value = req.headers.get('Authorization')?.match(/^Bearer ([a-f0-9]{64})$/)?.[1];
  if (!value) throw new ApiError(401,'Pair this device first');
  const fingerprint=await hash(value);
  const user = await env.DB.prepare('SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>?').bind(fingerprint,Date.now()).first<User>()
    ?? await env.DB.prepare('SELECT * FROM users WHERE token_hash=? AND email IS NULL').bind(fingerprint).first<User>();
  if (!user) throw new ApiError(401,'Invalid device token');
  return user;
}
function bytes(hex:string):Uint8Array<ArrayBuffer> { return Uint8Array.from(hex.match(/.{2}/g)||[],s=>parseInt(s,16)); }
export async function passwordHash(password:string,salt:string):Promise<string> {
  const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(password),'PBKDF2',false,['deriveBits']);
  const result=await crypto.subtle.deriveBits({name:'PBKDF2',hash:'SHA-256',salt:bytes(salt),iterations:600_000},key,256);
  return [...new Uint8Array(result)].map(x=>x.toString(16).padStart(2,'0')).join('');
}
export async function encryptModelKey(env:Env,userId:string,value:string):Promise<string> {
  if(!/^[a-f0-9]{64}$/.test(env.MODEL_KEY_ENCRYPTION_KEY||''))throw new ApiError(503,'Backend key encryption is not configured');
  const key=await crypto.subtle.importKey('raw',bytes(env.MODEL_KEY_ENCRYPTION_KEY!),{name:'AES-GCM'},false,['encrypt']);
  const iv=crypto.getRandomValues(new Uint8Array(12));
  const encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:new TextEncoder().encode(userId)},key,new TextEncoder().encode(value));
  return [...iv,...new Uint8Array(encrypted)].map(x=>x.toString(16).padStart(2,'0')).join('');
}
export async function modelKey(env:Env,user:User):Promise<string> {
  if(!user.model_key_ciphertext)return '';
  if(!env.MODEL_KEY_ENCRYPTION_KEY)throw new ApiError(503,'Backend key encryption is not configured');
  const key=await crypto.subtle.importKey('raw',bytes(env.MODEL_KEY_ENCRYPTION_KEY),{name:'AES-GCM'},false,['decrypt']);
  const encrypted=bytes(user.model_key_ciphertext);
  const plain=await crypto.subtle.decrypt({name:'AES-GCM',iv:encrypted.slice(0,12),additionalData:new TextEncoder().encode(user.id)},key,encrypted.slice(12));
  return new TextDecoder().decode(plain);
}
export async function membership(env: Env, groupId: string, userId: string): Promise<void> {
  if (!await env.DB.prepare('SELECT 1 FROM members WHERE group_id=? AND user_id=?').bind(groupId,userId).first()) throw new ApiError(403,'Not a member of this group');
}
export function json(value: unknown, status = 200): Response {
  return Response.json(value,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
}
