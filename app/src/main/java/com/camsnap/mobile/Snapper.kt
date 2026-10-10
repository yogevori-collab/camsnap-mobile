package com.camsnap.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Scheduling, image checks, saving and the status JSON the dashboard reads. */
object Snapper {
    private const val TAG = "CamSnap"
    private const val MAX_EVENTS = 300
    const val SNAP_URL = "https://appassets.androidplatform.net/snap/"

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ scheduling
    /** Time (ms) the camera is next due, or 0 if due now. */
    fun nextDue(cam: JSONObject, st: JSONObject?, s: JSONObject): Long {
        if (st == null) return 0
        val lastOk = st.optLong("last_success", 0)
        val lastTry = st.optLong("last_attempt", 0)
        if (st.optString("status") == "offline" && lastTry > 0)
            return lastTry + (s.optDouble("retry_minutes", 5.0) * 60_000).toLong()
        if (lastOk > 0) return lastOk + (cam.optDouble("interval_hours", 2.0) * 3_600_000).toLong()
        return 0
    }

    fun dueCameras(ctx: Context, s: JSONObject): List<JSONObject> {
        val st = Store.state(ctx).getJSONObject("cameras")
        val t = now()
        return Store.cameras(s).filter { c ->
            c.optBoolean("enabled", true) && nextDue(c, st.optJSONObject(c.getString("id")), s) <= t
        }
    }

    // ------------------------------------------------------------------ image checks
    private fun grayThumb(b: Bitmap): IntArray {
        val sm = Bitmap.createScaledBitmap(b, 160, 90, true)
        val px = IntArray(160 * 90)
        sm.getPixels(px, 0, 160, 0, 0, 160, 90)
        if (sm !== b) sm.recycle()
        return IntArray(px.size) { i ->
            val p = px[i]
            (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        }
    }

    fun blankReason(tile: Bitmap, threshold: Double): String? {
        val g = grayThumb(tile)
        val bins = IntArray(32)
        var sum = 0.0
        g.forEach { bins[it / 8]++; sum += it }
        val mean = sum / g.size
        var v = 0.0
        g.forEach { v += (it - mean) * (it - mean) }
        val std = sqrt(v / g.size)
        val share = (bins.maxOrNull() ?: 0).toDouble() / g.size
        return if (std < 4 || share >= threshold) "tile shows no video (blank / black / 'no video')" else null
    }

    fun isFrozen(tile: Bitmap, previous: File, threshold: Double): Boolean {
        if (threshold <= 0 || !previous.exists()) return false
        val prev = BitmapFactory.decodeFile(previous.path) ?: return false
        val a = grayThumb(tile)
        val b = grayThumb(prev)
        prev.recycle()
        var d = 0.0
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / a.size < threshold
    }

    // ------------------------------------------------------------------ results
    fun recordFailure(ctx: Context, cams: List<JSONObject>, msg: String, s: JSONObject) {
        val t = now()
        Store.withState(ctx) { st ->
            val all = st.getJSONObject("cameras")
            val events = st.getJSONArray("events")
            for (cam in cams) {
                val id = cam.getString("id")
                val c = all.optJSONObject(id) ?: JSONObject().also { all.put(id, it) }
                val first = c.optString("status") != "offline"
                c.put("status", "offline").put("last_attempt", t).put("last_error", msg)
                    .put("fail_count", c.optInt("fail_count", 0) + 1)
                if (first) {
                    c.put("offline_since", t)
                    addEvent(events, cam, "offline",
                        "Unreachable - will retry every ${s.optInt("retry_minutes", 5)} min ($msg)", t, 1)
                } else {
                    for (i in 0 until events.length()) {
                        val ev = events.getJSONObject(i)
                        if (ev.optString("id") == id) {
                            if (ev.optString("kind") == "offline") {
                                val n = ev.optInt("attempts", 1) + 1
                                ev.put("attempts", n).put("last", t).put("text",
                                    "Unreachable - $n attempts so far, retrying every ${s.optInt("retry_minutes", 5)} min ($msg)")
                            }
                            break
                        }
                    }
                }
            }
        }
        Log.w(TAG, "capture failed for ${cams.size} camera(s): $msg")
    }

    private fun recordSuccess(ctx: Context, cam: JSONObject, t: Long) {
        Store.withState(ctx) { st ->
            val all = st.getJSONObject("cameras")
            val id = cam.getString("id")
            val c = all.optJSONObject(id) ?: JSONObject().also { all.put(id, it) }
            val wasOff = c.optString("status") == "offline"
            val since = c.optLong("offline_since", 0)
            c.put("status", "ok").put("last_success", t).put("last_attempt", t)
                .put("fail_count", 0).remove("last_error")
            c.remove("offline_since")
            val events = st.getJSONArray("events")
            if (wasOff) {
                val m = if (since > 0) ((t - since) / 60_000).toInt() else 0
                val took = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
                addEvent(events, cam, "recovered", "Back online after $took offline - snapshot saved", t, 0)
            } else addEvent(events, cam, "ok", "Snapshot saved", t, 0)
        }
    }

    private fun addEvent(events: JSONArray, cam: JSONObject, kind: String, text: String, t: Long, attempts: Int) {
        val ev = JSONObject().put("t", t).put("camera", cam.optString("name")).put("id", cam.getString("id"))
            .put("kind", kind).put("text", text)
        if (attempts > 0) ev.put("attempts", attempts)
        // newest first: rebuild with the new event at index 0, capped
        val copy = ArrayList<Any>()
        for (i in 0 until events.length()) copy.add(events.get(i))
        while (events.length() > 0) events.remove(0)
        events.put(ev)
        copy.take(MAX_EVENTS - 1).forEach { events.put(it) }
    }

    // ------------------------------------------------------------------ processing a screenshot
    fun process(ctx: Context, s: JSONObject, cams: List<JSONObject>, shot: Bitmap) {
        val blankThr = s.optDouble("blank_threshold", 0.93)
        val frozenThr = s.optDouble("frozen_threshold", 0.5)
        for (cam in cams) {
            val id = cam.getString("id")
            try {
                val r = cam.getJSONArray("region")
                val scr = cam.optJSONArray("screen")
                if (scr != null && (scr.getInt(0) != shot.width || scr.getInt(1) != shot.height)) {
                    recordFailure(ctx, listOf(cam),
                        "screen size/rotation changed (${shot.width}x${shot.height}) - turn the phone back or re-mark the cameras", s)
                    continue
                }
                val x = r.getInt(0); val y = r.getInt(1); val w = r.getInt(2); val h = r.getInt(3)
                if (x < 0 || y < 0 || w < 8 || h < 8 || x + w > shot.width || y + h > shot.height) {
                    recordFailure(ctx, listOf(cam), "marked area is outside the screen - re-mark the cameras", s); continue
                }
                val tile = Bitmap.createBitmap(shot, x, y, w, h)
                val camDir = File(Store.snapDir(ctx), id).apply { mkdirs() }
                val latest = File(camDir, "latest.jpg")
                var why = blankReason(tile, blankThr)
                if (why == null && isFrozen(tile, latest, frozenThr)) why = "picture identical to the last snapshot - video looks frozen"
                if (why != null) {
                    saveJpeg(tile, File(camDir, "_rejected.jpg"), 85)
                    tile.recycle()
                    recordFailure(ctx, listOf(cam), why, s)
                    continue
                }
                val t = now()
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(t))
                val dayDir = File(camDir, day).apply { mkdirs() }
                val out = File(dayDir, "${id}_$stamp.jpg")
                saveJpeg(tile, out, 92)
                out.copyTo(latest, overwrite = true)
                File(camDir, "_rejected.jpg").delete()
                tile.recycle()
                if (s.optBoolean("copy_to_gallery", true)) copyToGallery(ctx, out, id)
                recordSuccess(ctx, cam, t)
            } catch (e: Exception) {
                Log.e(TAG, "process $id", e)
                recordFailure(ctx, listOf(cam), "error: ${e.message}", s)
            }
        }
    }

    fun saveJpeg(b: Bitmap, f: File, q: Int) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.JPEG, q, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun copyToGallery(ctx: Context, f: File, camId: String) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamSnap/$camId")
            }
            val uri: Uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            Log.w(TAG, "gallery copy failed", e)
        }
    }

    fun cleanup(ctx: Context, s: JSONObject) {
        val keep = s.optInt("keep_days", 90)
        if (keep <= 0) return
        val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now() - keep * 86_400_000L))
        Store.snapDir(ctx).listFiles()?.forEach { cam ->
            cam.listFiles()?.forEach { d ->
                if (d.isDirectory && d.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && d.name < cutoff) d.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------ status for the dashboard
    private fun src(rel: String): String =
        SNAP_URL + rel.split("/").joinToString("/") { Uri.encode(it) }

    private val STAMP = Regex("(\\d{8}_\\d{6})\\.jpg$")

    private fun history(ctx: Context, id: String, limit: Int): JSONArray {
        val out = JSONArray()
        val camDir = File(Store.snapDir(ctx), id)
        val days = camDir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?.sortedByDescending { it.name } ?: return out
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        for (d in days) {
            val files = d.listFiles { f -> STAMP.containsMatchIn(f.name) }?.sortedByDescending { it.name } ?: continue
            for (f in files) {
                val m = STAMP.find(f.name) ?: continue
                val t = try { fmt.parse(m.groupValues[1])?.time ?: 0L } catch (e: Exception) { 0L }
                out.put(JSONObject().put("t", t).put("src", src("$id/${d.name}/${f.name}")))
                if (out.length() >= limit) return out
            }
        }
        return out
    }

    fun status(ctx: Context): String {
        val s = Store.settings(ctx)
        val st = Store.state(ctx)
        val all = st.getJSONObject("cameras")
        val cams = JSONArray()
        for (cam in Store.cameras(s)) {
            val id = cam.getString("id")
            val c = all.optJSONObject(id) ?: JSONObject()
            val hist = history(ctx, id, s.optInt("history_per_camera", 60))
            var status = c.optString("status", "pending").ifEmpty { "pending" }
            val enabled = cam.optBoolean("enabled", true)
            if (!enabled) status = "paused"
            val due = nextDue(cam, all.optJSONObject(id), s)
            val rej = File(File(Store.snapDir(ctx), id), "_rejected.jpg")
            val o = JSONObject()
                .put("id", id).put("name", cam.optString("name", id)).put("enabled", enabled)
                .put("interval_hours", cam.optDouble("interval_hours", 2.0))
                .put("host", "CAM2 screen").put("status", status)
                .put("last_success", if (c.has("last_success")) c.getLong("last_success") else JSONObject.NULL)
                .put("last_attempt", if (c.has("last_attempt")) c.getLong("last_attempt") else JSONObject.NULL)
                .put("last_error", c.optString("last_error").ifEmpty { null } ?: JSONObject.NULL)
                .put("offline_since", if (c.has("offline_since")) c.getLong("offline_since") else JSONObject.NULL)
                .put("fail_count", c.optInt("fail_count", 0))
                .put("next_due", if (enabled) (if (due == 0L) now() else due) else JSONObject.NULL)
                .put("latest_src", if (hist.length() > 0) hist.getJSONObject(0).getString("src") else JSONObject.NULL)
                .put("rejected_src", if (status == "offline" && rej.exists())
                    src("$id/_rejected.jpg") + "?v=" + c.optLong("last_attempt") else JSONObject.NULL)
                .put("count", hist.length()).put("history", hist)
            cams.put(o)
        }
        val events = JSONArray()
        val ev = st.getJSONArray("events")
        for (i in 0 until minOf(100, ev.length())) events.put(ev.get(i))
        return JSONObject().put("generated_at", now())
            .put("check_every_seconds", 30)
            .put("retry_minutes", s.optInt("retry_minutes", 5))
            .put("service_on", SnapService.instance != null)
            .put("cameras", cams).put("events", events).toString()
    }
}
