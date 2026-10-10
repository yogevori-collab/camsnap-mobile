package com.camsnap.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One screen: the dashboard / setup page (assets/index.html) in a WebView, talking to [Bridge]. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bridge: Bridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/snap/", WebViewAssetLoader.InternalStoragePathHandler(this, Store.snapDir(this)))
            .build()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()          // enables prompt()/confirm() dialogs
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        bridge = Bridge(applicationContext, this)
        web.addJavascriptInterface(bridge, "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.onAppResume && onAppResume()", null)
        if (::bridge.isInitialized) bridge.refreshIfStale()
    }

    fun reloadPumping() = runOnUiThread {
        if (::web.isInitialized) web.evaluateJavascript("window.reloadPump && reloadPump()", null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::web.isInitialized) web.evaluateJavascript("window.onAppResume && onAppResume()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack ? onBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
        }
    }

    fun open(i: Intent) = runOnUiThread {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
    }
}

/** Methods the page calls as Android.xxx(). Everything is passed as JSON strings. */
class Bridge(private val ctx: Context, private val act: MainActivity) {

    @JavascriptInterface fun status(): String = Snapper.status(ctx)

    @JavascriptInterface fun pumping(): String {
        val f = Store.pumpingFile(ctx)
        return if (f.exists()) f.readText() else "null"
    }

    @JavascriptInterface fun settings(): String = Store.settings(ctx).toString()

    @JavascriptInterface fun saveSettings(json: String): String = try {
        val patch = JSONObject(json)
        // give every camera a safe, unique id
        patch.optJSONArray("cameras")?.let { a ->
            val seen = HashSet<String>()
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                var id = Store.safeName(c.optString("id").ifEmpty { c.optString("name") })
                var n = 2
                val base = id
                while (!seen.add(id)) id = "$base ${n++}"
                c.put("id", id)
            }
        }
        Store.saveSettings(ctx, patch)
        if (patch.has("sheet_url")) refreshSheet()
        "ok"
    } catch (e: Exception) { "error: ${e.message}" }

    @JavascriptInterface fun serviceOn(): Boolean = SnapService.instance != null

    @JavascriptInterface fun apps(): String {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(i, 0)
        val out = JSONArray()
        list.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName }
            .sortedBy { it.second.lowercase() }
            .forEach { out.put(JSONObject().put("pkg", it.first).put("label", it.second)) }
        return out.toString()
    }

    @JavascriptInterface fun openAccessibility() = act.open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    @JavascriptInterface fun openBattery() =
        act.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))

    @JavascriptInterface fun openSecurity() = act.open(Intent(Settings.ACTION_SECURITY_SETTINGS))

    @JavascriptInterface fun openDeveloper() = act.open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    @JavascriptInterface fun startCalibration(): String {
        val svc = SnapService.instance ?: return "Turn on CamSnap in Accessibility settings first."
        if (Store.settings(ctx).optString("cam2_pkg").isBlank()) return "Choose the CAM2 app first."
        File(ctx.filesDir, "calib.json").delete()
        svc.startCalibration()
        return "ok"
    }

    /** After marking: {pending, src, w, h} or {pending, error}. Cleared once read. */
    @JavascriptInterface fun calibInfo(): String {
        val f = File(ctx.filesDir, "calib.json")
        if (!f.exists()) return "{}"
        val s = f.readText()
        f.delete()
        return s
    }

    @JavascriptInterface fun snapNow(): String {
        val svc = SnapService.instance ?: return "Turn on CamSnap in Accessibility settings first."
        svc.snapAllNow()
        return "ok"
    }

    /** Downloads the pumping sheet now (works even when the snapshot service is off). */
    @Volatile private var downloading = false

    @JavascriptInterface fun refreshSheet(): String {
        val s = Store.settings(ctx)
        val url = s.optString("sheet_url")
        if (url.isBlank()) return "Add the Google Sheet link in Setup first."
        if (downloading) return "ok"
        downloading = true
        Thread {
            try { Pumping.refresh(ctx, url, s) } catch (t: Throwable) {} finally { downloading = false }
            act.reloadPumping()
        }.start()
        return "ok"
    }

    @JavascriptInterface fun isDownloading(): Boolean = downloading

    fun refreshIfStale() {
        val s = Store.settings(ctx)
        val f = Store.pumpingFile(ctx)
        val maxAge = s.optDouble("sheet_refresh_minutes", 10.0) * 60_000
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAge) refreshSheet()
    }
}
