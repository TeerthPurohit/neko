import type { Env } from './types';
const encode=(bytes: Uint8Array):string=>btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
const base64=(value:unknown):string=>encode(new TextEncoder().encode(JSON.stringify(value)));
let cached:{value:string;expires:number}|undefined;
export async function push(env: Env, deviceToken: string|null, activityId: string): Promise<boolean> {
  if(!deviceToken||!env.FCM_PROJECT_ID||!env.FCM_CLIENT_EMAIL||!env.FCM_PRIVATE_KEY) return false;
  if(!cached||cached.expires<Date.now()) {
    const pem=env.FCM_PRIVATE_KEY.replaceAll('\\n','\n').replace(/-----[^-]+-----/g,'').replace(/\s/g,'');
    const key=await crypto.subtle.importKey('pkcs8',Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
    const now=Math.floor(Date.now()/1000);
    const content=base64({alg:'RS256',typ:'JWT'})+'.'+base64({iss:env.FCM_CLIENT_EMAIL,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
    const signature=encode(new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(content))));
    const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:content+'.'+signature}),signal:AbortSignal.timeout(7000)});
    if(!response.ok) return false;
    const data=await response.json() as {access_token:string;expires_in:number};
    cached={value:data.access_token,expires:Date.now()+Math.max(60,data.expires_in-120)*1000};
  }
  const response=await fetch(`https://fcm.googleapis.com/v1/projects/${env.FCM_PROJECT_ID}/messages:send`,{method:'POST',headers:{Authorization:'Bearer '+cached.value,'Content-Type':'application/json'},body:JSON.stringify({message:{token:deviceToken,notification:{title:'Neko has an update',body:'Open Neko to see your private update.'},data:{activity_id:activityId},android:{priority:'normal',notification:{channel_id:'neko_agent',click_action:'android.intent.action.MAIN'}}}}),signal:AbortSignal.timeout(7000)});
  return response.ok;
}
