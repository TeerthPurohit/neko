package dev.neko.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

@Composable
fun NekoMascot3D(
    modifier: Modifier = Modifier,
    speaking: Boolean,
    reducedMotion: Boolean,
) {
    val description = if (speaking) {
        "Neko, the white lucky cat with small glasses, is replying"
    } else {
        "Neko, the animated white lucky cat with small glasses, a green bib and a gold coin"
    }

    AndroidView(
        modifier = modifier.semantics { contentDescription = description },
        factory = { context -> NekoMascotWebView(context) },
        update = { it.setAgentState(speaking = speaking, reducedMotion = reducedMotion) },
        onRelease = NekoMascotWebView::release,
    )
}

@SuppressLint("SetJavaScriptEnabled")
private class NekoMascotWebView(context: Context) : WebView(context) {
    private var pageReady = false
    private var speaking = false
    private var reducedMotion = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse = localResponse(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = url == baseUrl
                applyAgentState()
            }

        }
        loadUrl(baseUrl)
    }

    fun setAgentState(speaking: Boolean, reducedMotion: Boolean) {
        this.speaking = speaking
        this.reducedMotion = reducedMotion
        applyAgentState()
    }

    fun release() {
        stopLoading()
        webViewClient = WebViewClient()
        removeAllViews()
        destroy()
    }

    private fun applyAgentState() {
        if (pageReady) {
            evaluateJavascript(
                "window.nekoSetAgentState?.($speaking, $reducedMotion)",
                null,
            )
        }
    }

    private fun localResponse(uri: Uri): WebResourceResponse {
        if (uri.scheme != "https" || uri.host != assetHost) return blockedResponse()
        if (uri.path == "/assets/neko/index.html") {
            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(page.toByteArray(Charsets.UTF_8)))
        }

        val (assetPath, mimeType) = when (uri.path) {
            "/assets/neko/agent.js" -> "neko/agent.js" to "text/javascript"
            "/assets/neko/model-viewer.min.js" -> "neko/model-viewer.min.js" to "text/javascript"
            "/assets/models/neko.glb" -> "models/neko.glb" to "model/gltf-binary"
            else -> return blockedResponse(statusCode = 404, reason = "Not Found")
        }

        return try {
            val encoding = if (mimeType == "model/gltf-binary") null else "UTF-8"
            WebResourceResponse(mimeType, encoding, context.assets.open(assetPath))
        } catch (_: Exception) {
            blockedResponse(statusCode = 404, reason = "Not Found")
        }
    }

    private fun blockedResponse(
        statusCode: Int = 403,
        reason: String = "Blocked",
    ) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        statusCode,
        reason,
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val assetHost = "appassets.androidplatform.net"
        const val baseUrl = "https://appassets.androidplatform.net/assets/neko/index.html"

        val page = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' blob: 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' data: blob:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'">
              <style>
                html, body { width: 100%; height: 100%; margin: 0; overflow: hidden; background: transparent; }
                #halo { position: absolute; inset: 7%; border-radius: 50%; pointer-events: none;
                  background: radial-gradient(ellipse, rgba(247, 179, 91, .24), rgba(247, 179, 91, .07) 46%, transparent 72%);
                  opacity: .72; transition: opacity 420ms ease, transform 420ms ease; }
                #halo.speaking { opacity: 1; transform: scale(1.04); }
                model-viewer { position: absolute; inset: 0; width: 100%; height: 100%;
                  background: transparent; --poster-color: transparent; }
                @media (prefers-reduced-motion: reduce) { #halo { transition: none; } }
              </style>
            </head>
            <body>
              <div id="halo" aria-hidden="true"></div>
              <model-viewer id="neko" src="/assets/models/neko.glb"
                alt="Neko, a smiling white lucky cat with small glasses, a raised paw, green bib and gold coin"
                autoplay animation-name="A warm hello" disable-pan disable-zoom
                interaction-prompt="none" shadow-intensity="0.7" exposure="1.05"
                camera-orbit="0deg 82deg auto" field-of-view="30deg"></model-viewer>
              <script type="module" src="/assets/neko/agent.js"></script>
            </body>
            </html>
        """.trimIndent()
    }
}
