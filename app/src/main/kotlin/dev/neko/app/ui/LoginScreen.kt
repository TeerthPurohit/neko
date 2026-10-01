package dev.neko.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import dev.neko.app.BuildConfig

@Composable fun LoginScreen(state:NekoState,onLogin:(String,String,String,String,String,Boolean)->Unit,onOffline:()->Unit){
    var registering by remember{mutableStateOf(false)};var url by remember{mutableStateOf(state.backendUrl.ifBlank{BuildConfig.DEFAULT_BACKEND_URL})}
    var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()){
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){NekoCat(Modifier.size(148.dp,164.dp),animate=!state.reducedMotion)}
            Text(if(registering)"A little company\nfor your money."else"Welcome to Neko.",style=MaterialTheme.typography.headlineLarge)
            Text("Your personal agent. Ready to notice, remember, and follow through.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),label={Text("Your Neko backend HTTPS URL")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),shape=NekoTokens.ControlShape)
            if(registering)OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Your name")},singleLine=true,shape=NekoTokens.ControlShape)
            OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Email")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),shape=NekoTokens.ControlShape)
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Neko password · 12+ characters")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),shape=NekoTokens.ControlShape)
            if(registering)OutlinedTextField(code,{code=it},Modifier.fillMaxWidth(),label={Text("Private registration code")},singleLine=true,visualTransformation=PasswordVisualTransformation(),shape=NekoTokens.ControlShape)
            if(registering)Text("Use NEKO_PAIRING_SECRET from your private .env as the registration code. Choose a unique password for Neko.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.error!=null)Text(state.error,color=MaterialTheme.colorScheme.error)
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            PrimaryButton(if(registering)"Create account"else"Log in",{onLogin(url.trim(),email.trim(),password,name.trim(),code,registering);password="";code=""},Modifier.fillMaxWidth(),enabled=!state.busy&&url.startsWith("https://")&&email.contains('@')&&password.length>=12&&(!registering||(name.isNotBlank()&&code.length>=32)))
            TextButton({registering=!registering},Modifier.fillMaxWidth(),enabled=!state.busy){Text(if(registering)"Already have an account? Log in"else"New here? Create an account")}
            TextButton(onOffline,Modifier.fillMaxWidth(),enabled=!state.busy){Text("Try local features first")}
            Text("Your OpenRouter key comes next. It will be encrypted on your backend, and never saved on this phone.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun ByokScreen(state:NekoState,onConnect:(String)->Unit,onLater:()->Unit){
    var key by remember{mutableStateOf("")};var consent by remember{mutableStateOf(false)}
    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()){
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            NekoCat(Modifier.size(135.dp,149.dp),animate=!state.reducedMotion)
            Text("Give Neko\na little intelligence.",style=MaterialTheme.typography.headlineLarge)
            Text("Bring your own OpenRouter key. Choose models, ask questions, get category suggestions, and let Neko check in for you.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),label={Text("Paste your OpenRouter API key")},placeholder={Text("sk-or-…")},visualTransformation=PasswordVisualTransformation(),singleLine=true,shape=NekoTokens.ControlShape)
            Text("Get a key at openrouter.ai/settings/keys. Set a spending limit on that key. The APK contains no provider key.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.Top){Checkbox(consent,{consent=it});Text("I agree to share structured amounts, dates, redacted merchants, categories, bank aliases, and budgets with my backend and relevant model providers through OpenRouter. My questions are also sent. Raw SMS, account numbers, references, and local notes stay on the phone.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall)}
            if(state.error!=null)Text(state.error,color=MaterialTheme.colorScheme.error)
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            PrimaryButton("Connect OpenRouter",{onConnect(key.trim());key=""},Modifier.fillMaxWidth(),enabled=key.startsWith("sk-or-")&&consent&&!state.busy)
            TextButton(onLater,Modifier.fillMaxWidth(),enabled=!state.busy){Text("Set this up later")}
            Text("AI proposes ledger changes for your approval. You can remove the key and disable cloud AI in Settings.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
