package com.camsnap.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs while enabled in Settings > Accessibility.
 * Every 30 s it checks which cameras are due; when some are, it brings Provision CAM2 to the
 * front, waits for the video, takes a screenshot and hands it to [Snapper].
 * It also downloads the pumping Google Sheet on its own schedule.
 */
class SnapService : AccessibilityService() {

    companion object {
        private const val TAG = "CamSnap"
        @Volatile var instance: SnapService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    @Volatile private var foregroundPkg: String? = null
    @Volatile private var busy = false
    @Volatile private var forceAll = false
    @Volatile private var lastSheet = 0L
    private var lastCleanup = 0L
    private var cpuLock: PowerManager.WakeLock? = null

    // ------------------------------------------------------------------ lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camsnap:cpu").apply {
            setReferenceCounted(false); acquire()
        }
        handler.post(tick)
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = event.packageName?.toString() ?: return
            if (p != "com.android.systemui") foregroundPkg = p
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { cpuLock?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ public actions (from the app)
    fun snapAllNow() { forceAll = true; handler.post { runCheck() } }
    fun refreshSheetNow() { lastSheet = 0L; handler.post { runCheck() } }
    fun startCalibration() { handler.post { capture(emptyList(), Store.settings(this), calibrate = true) } }

    // ------------------------------------------------------------------ scheduler
    private val tick = object : Runnable {
        override fun run() {
            try { runCheck() } catch (t: Throwable) { Log.e(TAG, "tick", t) }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun runCheck() {
        val s = Store.settings(this)
        val now = System.currentTimeMillis()

        val url = s.optString("sheet_url")
        if (url.isNotBlank() && now - lastSheet > s.optDouble("sheet_refresh_minutes", 10.0) * 60_000) {
            lastSheet = now
            net.execute { try { Pumping.refresh(this, url, s) } catch (t: Throwable) { Log.e(TAG, "sheet", t) } }
        }
        if (now - lastCleanup > 3_600_000) {
            lastCleanup = now
            worker.execute { Snapper.cleanup(this, s) }
        }
        if (busy) return
        val due = if (forceAll) Store.cameras(s).filter { it.optBoolean("enabled", true) }
                  else Snapper.dueCameras(this, s)
        forceAll = false
        if (due.isNotEmpty()) capture(due, s, calibrate = false)
    }

    // ------------------------------------------------------------------ capture
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "camsnap:screen"
        ).acquire(30_000)
    }

    private fun capture(cams: List<JSONObject>, s: JSONObject, calibrate: Boolean) {
        if (busy) return
        busy = true
        try {
            wakeScreen()
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardLocked) {
                fail(cams, s, calibrate, "the phone is locked - set Screen lock to None (see Setup)")
                return
            }
            val pkg = s.optString("cam2_pkg")
            if (pkg.isBlank()) { fail(cams, s, calibrate, "CAM2 app not chosen - open Setup"); return }
            val previous = foregroundPkg
            val needLaunch = previous != pkg
            if (needLaunch) {
                val i = packageManager.getLaunchIntentForPackage(pkg)
                if (i == null) { fail(cams, s, calibrate, "CAM2 app ($pkg) is not installed"); return }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(i)
            }
            val waitMs = if (needLaunch) s.optLong("settle_seconds", 10) * 1000 else 1500L
            handler.postDelayed({ shoot(cams, s, calibrate, pkg, previous) }, waitMs)
        } catch (t: Throwable) {
            fail(cams, s, calibrate, "error: ${t.message}")
        }
    }

    private fun shoot(cams: List<JSONObject>, s: JSONObject, calibrate: Boolean, pkg: String, previous: String?) {
        if (!calibrate && foregroundPkg != null && foregroundPkg != pkg) {
            fail(cams, s, false, "CAM2 is not on screen (${foregroundPkg} is in front)")
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp: Bitmap? = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { hb.close() }
                    if (bmp == null) { fail(cams, s, calibrate, "screenshot could not be read"); return }
                    worker.execute {
                        try {
                            if (calibrate) saveCalibration(bmp) else Snapper.process(this@SnapService, s, cams, bmp)
                        } catch (t: Throwable) {
                            Log.e(TAG, "process", t)
                        } finally {
                            bmp.recycle()
                            busy = false
                            handler.post { afterCapture(calibrate, previous) }
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    fail(cams, s, calibrate, "Android refused the screenshot (code $errorCode)")
                }
            })
        } catch (t: Throwable) {
            fail(cams, s, calibrate, "screenshot error: ${t.message}")
        }
    }

    private fun afterCapture(calibrate: Boolean, previous: String?) {
        // come back to CamSnap if the user was looking at it (or after marking cameras)
        if (calibrate || previous == packageName) {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (calibrate) i.putExtra("calibrated", true)
            startActivity(i)
        }
    }

    private fun fail(cams: List<JSONObject>, s: JSONObject, calibrate: Boolean, msg: String) {
        if (calibrate) {
            File(filesDir, "calib.json").writeText(JSONObject().put("pending", true).put("error", msg).toString())
            busy = false
            afterCapture(true, null)
        } else {
            worker.execute {
                try { Snapper.recordFailure(this, cams, msg, s) } finally { busy = false }
            }
        }
    }

    private fun saveCalibration(bmp: Bitmap) {
        val f = File(Store.snapDir(this), "_calib.jpg")
        Snapper.saveJpeg(bmp, f, 90)
        File(filesDir, "calib.json").writeText(
            JSONObject().put("pending", true)
                .put("src", Snapper.SNAP_URL + "_calib.jpg?v=" + System.currentTimeMillis())
                .put("w", bmp.width).put("h", bmp.height).toString()
        )
    }
}
