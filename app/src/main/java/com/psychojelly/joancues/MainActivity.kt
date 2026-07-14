package com.psychojelly.joancues

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * The whole UI is the existing cue-controller webpage, loaded from the
 * embedded server. Nothing about the HTML changes — this app is just a
 * reliable place for it to live.
 *
 * Two WebView bridges make the controller's per-Joan settings workflow work:
 *  - Import Settings: <input type=file> needs onShowFileChooser.
 *  - Export Settings: the page "downloads" a Blob; a DownloadListener can't
 *    read blob: URLs, so a JS shim fetches the blob and hands base64 to a
 *    JavascriptInterface, which writes the .json into Downloads.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFileChooser?.onReceiveValue(if (uri != null) arrayOf(uri) else arrayOf())
        pendingFileChooser = null
    }

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
            webChromeClient = FileChooserClient()
            addJavascriptInterface(BlobSaver(), "AndroidBlobSaver")
            setDownloadListener { url, _, contentDisposition, _, _ ->
                if (url.startsWith("blob:")) {
                    // Pull the blob's bytes in JS and hand them to native as base64.
                    val name = fileNameFrom(contentDisposition)
                    evaluateJavascript(
                        """
                        (function() {
                          fetch('$url').then(r => r.blob()).then(b => {
                            const fr = new FileReader();
                            fr.onload = () => AndroidBlobSaver.save('$name', fr.result.split(',')[1]);
                            fr.readAsDataURL(b);
                          });
                        })();
                        """.trimIndent(), null
                    )
                }
            }
        }
        setContentView(webView)
        loadController()
    }

    private fun loadController() {
        webView.loadUrl("http://127.0.0.1:${CueHttpServer.PORT}/")
    }

    private fun fileNameFrom(contentDisposition: String?): String {
        val m = Regex("filename=\"?([^\";]+)").find(contentDisposition ?: "")
        return m?.groupValues?.get(1) ?: "qlab-controller-settings.json"
    }

    /** Receives base64 file content from the blob-download shim and writes it to Downloads. */
    inner class BlobSaver {
        @JavascriptInterface
        fun save(fileName: String, base64: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, safeName).writeBytes(bytes)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads: $safeName", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Makes the controller's Import Settings (<input type=file>) open a real picker. */
    private inner class FileChooserClient : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            pendingFileChooser?.onReceiveValue(arrayOf())   // cancel any stale one
            pendingFileChooser = filePathCallback
            pickFile.launch("*/*")
            return true
        }
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
