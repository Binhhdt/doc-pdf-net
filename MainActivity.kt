
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private var web: WebView? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private fun rootDir(): File = Environment.getExternalStorageDirectory()
    private fun isPdf(f: File): Boolean = f.isFile && f.name.lowercase().endsWith(".pdf")

    private fun canRead(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + packageName)))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) {}
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun hasAccess(): Boolean = canRead()

        @JavascriptInterface
        fun requestAccess() { runOnUiThread { askAccess() } }

        @JavascriptInterface
        fun root(): String = rootDir().absolutePath

        @JavascriptInterface
        fun list(path: String): String {
            val arr = JSONArray()
            try {
                val fs = File(path).listFiles() ?: return "[]"
                for (f in fs) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        val o = JSONObject()
                        o.put("n", f.name); o.put("p", f.absolutePath); o.put("d", true)
                        o.put("c", f.listFiles()?.count { isPdf(it) } ?: 0)
                        arr.put(o)
                    } else if (isPdf(f)) {
                        val o = JSONObject()
                        o.put("n", f.name); o.put("p", f.absolutePath); o.put("d", false); o.put("s", f.length())
                        arr.put(o)
                    }
                }
            } catch (e: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface
        fun scan(): String {
            val arr = JSONArray()
            try { walk(rootDir(), 0, arr) } catch (e: Exception) {}
            return arr.toString()
        }

        private fun walk(dir: File, depth: Int, arr: JSONArray) {
            if (depth > 6 || arr.length() >= 3000) return
            val fs = dir.listFiles() ?: return
            for (f in fs) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth == 0 && f.name == "Android") continue
                    walk(f, depth + 1, arr)
                } else if (isPdf(f)) {
                    val o = JSONObject()
                    o.put("n", f.name); o.put("p", f.absolutePath); o.put("s", f.length())
                    arr.put(o)
                }
            }
        }
    }

    private fun serveAsset(name: String): WebResourceResponse {
        val n = if (name.isEmpty()) "index.html" else name
        val mime = if (n.endsWith(".js")) "application/javascript" else "text/html"
        return WebResourceResponse(mime, "utf-8", assets.open(n))
    }

    private fun serveFile(path: String, headers: Map<String, String>): WebResourceResponse {
        val f = File(path)
        if (!f.canonicalPath.startsWith(rootDir().canonicalPath) || !f.isFile) throw Exception("no file")
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        val range = headers["Range"] ?: headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.substring(6).split("-")
            val s = parts[0].trim().toLongOrNull()
            val e = if (parts.size > 1) parts[1].trim().toLongOrNull() else null
            if (s != null && s < len) {
                start = s
                if (e != null && e < len) end = e
                partial = true
            }
        }
        val count = end - start + 1
        val fis = FileInputStream(f)
        fis.channel.position(start)
        val h = HashMap<String, String>()
        h["Accept-Ranges"] = "bytes"
        h["Content-Length"] = count.toString()
        h["Cache-Control"] = "no-store"
        if (partial) h["Content-Range"] = "bytes " + start + "-" + end + "/" + len
        return WebResourceResponse("application/pdf", null, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", h, Bounded(fis, count))
    }

    private fun notifyJs() {
        web?.evaluateJavascript("window.onNativeResume&&window.onNativeResume()", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.builtInZoomControls = true
        w.settings.displayZoomControls = false
        w.addJavascriptInterface(Bridge(), "Native")
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != "app.local") return null
                return try {
                    if (url.path == "/file") serveFile(url.getQueryParameter("p") ?: "", request.requestHeaders ?: HashMap<String, String>())
                    else serveAsset((url.path ?: "/").trimStart('/'))
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", HashMap<String, String>(), ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                cb: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "application/pdf"
                return try {
                    startActivityForResult(i, 1)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }
        val root = FrameLayout(this)
        root.fitsSystemWindows = true
        root.addView(w)
        setContentView(root)
        w.loadUrl("https://app.local/index.html")
    }

    override fun onResume() {
        super.onResume()
        notifyJs()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        notifyJs()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            fileCb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            fileCb = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val w = web
        if (w == null) { finish(); return }
        w.evaluateJavascript("(window.onBack&&window.onBack())?1:0") { v -> if (v != "1") finish() }
    }
}

class Bounded(private val src: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = src.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = src.read(b, off, if (len.toLong() < left) len else left.toInt())
        if (n > 0) left -= n
        return n
    }

    override fun close() { src.close() }
}
