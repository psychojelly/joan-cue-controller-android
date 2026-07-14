package com.psychojelly.joancues

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * The whole UI is the existing cue-controller webpage, loaded from the
 * embedded server. Nothing about the HTML changes — this app is just a
 * reliable place for it to live.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // An operator device: keep the screen on while the controller is front.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start (or confirm) the cue server before loading the page.
        CueServerService.start(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true   // localStorage — the controller's saved settings live here
            webViewClient = RetryingClient()
        }
        setContentView(webView)
        loadController()
    }

    private fun loadController() {
        webView.loadUrl("http://127.0.0.1:${CueHttpServer.PORT}/")
    }

    /** The service can take a beat to open the port on cold start — retry briefly. */
    private inner class RetryingClient : WebViewClient() {
        private var retries = 0
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && retries < 10) {
                retries++
                Handler(Looper.getMainLooper()).postDelayed({ loadController() }, 500)
            }
        }
        override fun onPageFinished(view: WebView, url: String) { retries = 0 }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Keep back-button from killing the controller mid-show; go back in
        // page history if there is any, otherwise do nothing.
        if (webView.canGoBack()) webView.goBack()
    }
}
