package dev.neko.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.neko.app.ui.*

class MainActivity:ComponentActivity() {
    private var requestedTransaction by mutableStateOf<String?>(null)
    private var requestedAgent by mutableStateOf(false)
    private lateinit var model:NekoViewModel
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=NekoViewModel(application as NekoApplication) as T
        })[NekoViewModel::class.java]
        readIntent(intent)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LaunchedEffect(model){model.start()}
            NekoTheme(state.theme){
                if(!state.loading&&!state.paired&&!state.offlineProfile)LoginScreen(state,model::login,model::offline)
                else if(!state.loading&&state.paired&&!state.hasModelKey&&!state.byokDeferred)ByokScreen(state,model::byok,model::deferByok)
                else NekoApp(state,model,requestedTransaction,requestedAgent){requestedTransaction=null;requestedAgent=false}
            }
        }
    }
    // Replies finished while the app was in the background are fetched as soon as it returns.
    override fun onStart(){super.onStart();dev.neko.app.capture.SmsCatchUpWorker.runNow(this);dev.neko.app.agent.AgentWork.syncNow(this)}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);readIntent(intent)}
    private fun readIntent(intent:Intent){requestedTransaction=intent.getStringExtra("transaction_id");requestedAgent=intent.getBooleanExtra("open_agent",false)}
}
