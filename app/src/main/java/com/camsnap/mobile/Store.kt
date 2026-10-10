package com.camsnap.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings (cameras, CAM2 app, sheet link) and run state, kept as small JSON files in app storage. */
object Store {
    private val lock = Any()

    fun snapDir(ctx: Context): File = File(ctx.filesDir, "snapshots").apply { mkdirs() }
    private fun settingsFile(ctx: Context) = File(ctx.filesDir, "settings.json")
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "state.json")
    fun pumpingFile(ctx: Context) = File(ctx.filesDir, "pumping.json")

    private val DEFAULTS = JSONObject()
        .put("cam2_pkg", "")
        .put("settle_seconds", 10)
        .put("retry_minutes", 5)
        .put("keep_days", 90)
        .put("history_per_camera", 60)
        .put("blank_threshold", 0.93)
        .put("frozen_threshold", 0.5)
        .put("copy_to_gallery", true)
        .put("sheet_url", "https://docs.google.com/spreadsheets/d/1my-1-KbAmUkgAtAR5hlMnt2dVVsF4Rf9J7zOcUZwbhw/edit")
        .put("sheet_refresh_minutes", 10)
        .put("cameras", JSONArray())

    private fun read(f: File): JSONObject? =
        try { if (f.exists()) JSONObject(f.readText()) else null } catch (e: Exception) { null }

    private fun write(f: File, o: JSONObject) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun settings(ctx: Context): JSONObject = synchronized(lock) {
        val s = read(settingsFile(ctx)) ?: JSONObject()
        val it = DEFAULTS.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (!s.has(k)) s.put(k, DEFAULTS.get(k))
        }
        if (s.optString("sheet_url").isBlank()) s.put("sheet_url", DEFAULTS.get("sheet_url"))
        s
    }

    /** Merges the given keys into the saved settings. */
    fun saveSettings(ctx: Context, patch: JSONObject) = synchronized(lock) {
        val s = settings(ctx)
        val it = patch.keys()
        while (it.hasNext()) { val k = it.next(); s.put(k, patch.get(k)) }
        write(settingsFile(ctx), s)
    }

    fun cameras(s: JSONObject): List<JSONObject> {
        val a = s.optJSONArray("cameras") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun state(ctx: Context): JSONObject = synchronized(lock) {
        val st = read(stateFile(ctx)) ?: JSONObject()
        if (!st.has("cameras")) st.put("cameras", JSONObject())
        if (!st.has("events")) st.put("events", JSONArray())
        st
    }

    fun saveState(ctx: Context, st: JSONObject) = synchronized(lock) { write(stateFile(ctx), st) }

    /** Run [block] on the state and save it, all under one lock. */
    fun <T> withState(ctx: Context, block: (JSONObject) -> T): T = synchronized(lock) {
        val st = state(ctx)
        val r = block(st)
        write(stateFile(ctx), st)
        r
    }

    fun safeName(s: String): String {
        val c = s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim(' ', '.')
        return if (c.isEmpty()) "camera" else c
    }
}
