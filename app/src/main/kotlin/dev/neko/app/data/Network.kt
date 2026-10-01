package dev.neko.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Network {
    private val client=OkHttpClient.Builder().callTimeout(25,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).build()
    suspend fun request(url: String, token: String?=null, method: String="GET", json: JSONObject?=null): JSONObject = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")){"Use an HTTPS backend URL"}
        val builder=Request.Builder().url(url)
        if(token!=null)builder.header("Authorization","Bearer $token")
        if(method!="GET")builder.method(method,(json?:JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val raw=response.body?.string().orEmpty()
            val data=try{JSONObject(raw)}catch(_:Exception){JSONObject()}
            if(!response.isSuccessful)throw java.io.IOException(data.optString("error","Service returned ${response.code}"))
            data
        }
    }
}
