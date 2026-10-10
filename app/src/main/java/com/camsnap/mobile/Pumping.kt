package com.camsnap.mobile

import android.content.Context
import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * Downloads the pumping Google Sheet (as .xlsx) and turns every tab that has the table
 *   שעה ותאריך | סוג חומר | בתוואי/מחוץ לתוואי | קריאה בשעון | ספיקה נוכחית
 * into rows {t, m, loc, r, d, f} where d = meter reading minus the previous filled reading.
 * Same output as the PC version, so the same dashboard code draws it.
 */
object Pumping {
    private const val TAG = "CamSnap"

    fun refresh(ctx: Context, url: String, s: JSONObject) {
        val out = Store.pumpingFile(ctx)
        val prev = try { JSONObject(out.readText()) } catch (e: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        try {
            val sites = parseWorkbook(download(url))
            if (sites.length() == 0) throw IllegalStateException(
                "no tab has the expected table header (שעה ותאריך / סוג חומר / קריאה בשעון)")
            val o = JSONObject().put("ok", true).put("fetched_at", now).put("last_attempt", now)
                .put("error", JSONObject.NULL)
                .put("refresh_minutes", s.optInt("sheet_refresh_minutes", 10))
                .put("sites", sites)
            write(out, o)
        } catch (e: Exception) {
            Log.w(TAG, "sheet download failed", e)
            prev.put("ok", false).put("last_attempt", now).put("error", "${e.javaClass.simpleName}: ${e.message}")
                .put("refresh_minutes", s.optInt("sheet_refresh_minutes", 10))
            if (!prev.has("sites")) prev.put("sites", JSONArray())
            write(out, prev)
        }
    }

    private fun write(f: File, o: JSONObject) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ------------------------------------------------------------------ download
    fun download(sheetUrl: String): ByteArray {
        val id = Regex("/spreadsheets/d/([A-Za-z0-9_-]+)").find(sheetUrl)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("not a Google Sheets link")
        var u = URL("https://docs.google.com/spreadsheets/d/$id/export?format=xlsx")
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 90_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (CamSnap Android)")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("redirect without location")
                c.disconnect()
                u = URL(u, loc)
                return@repeat
            }
            if (code != 200) throw IllegalStateException("Google answered HTTP $code")
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte())
                throw IllegalStateException("Google sent a web page, not the sheet - share it as 'Anyone with the link can view'")
            return bytes
        }
        throw IllegalStateException("too many redirects")
    }

    // ------------------------------------------------------------------ xlsx reading
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val m = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name
                if (n == "xl/workbook.xml" || n == "xl/_rels/workbook.xml.rels" || n == "xl/sharedStrings.xml" ||
                    n.startsWith("xl/worksheets/sheet")) {
                    val bo = ByteArrayOutputStream()
                    z.copyTo(bo)
                    m[n] = bo.toByteArray()
                }
            }
        }
        return m
    }

    private fun parser(b: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(ByteArrayInputStream(b), "UTF-8") }

    private fun sharedStrings(b: ByteArray?): List<String> {
        if (b == null) return emptyList()
        val out = ArrayList<String>()
        val p = parser(b)
        val sb = StringBuilder()
        var inSi = false; var inT = false; var inRph = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.setLength(0) }
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> { out.add(sb.toString()); inSi = false }
                    "t" -> inT = false
                    "rPh" -> inRph = false
                }
                XmlPullParser.TEXT -> if (inSi && inT && !inRph) sb.append(p.text)
            }
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
        return n - 1
    }

    private fun readSheet(b: ByteArray, ss: List<String>): List<List<Any?>> {
        val rows = ArrayList<MutableList<Any?>>()
        val p = parser(b)
        var row: MutableList<Any?>? = null
        var col = 0; var type = ""; var inV = false; var inT = false
        val txt = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        val r = p.getAttributeValue(null, "r")?.toIntOrNull()
                        if (r != null) while (rows.size < r - 1) rows.add(ArrayList())
                        row = ArrayList(); rows.add(row)
                    }
                    "c" -> {
                        col = colIndex(p.getAttributeValue(null, "r") ?: "")
                        if (col < 0) col = row?.size ?: 0
                        type = p.getAttributeValue(null, "t") ?: ""
                        txt.setLength(0)
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) txt.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = txt.toString()
                        val v: Any? = if (raw.isEmpty()) null else when (type) {
                            "s" -> ss.getOrNull(raw.trim().toIntOrNull() ?: -1)
                            "str", "inlineStr", "e" -> raw
                            "b" -> raw == "1"
                            else -> raw.toDoubleOrNull() ?: raw
                        }
                        val r = row
                        if (r != null) {
                            while (r.size < col) r.add(null)
                            if (r.size == col) r.add(v) else r[col] = v
                        }
                    }
                }
            }
        }
        return rows
    }

    private fun parseWorkbook(bytes: ByteArray): JSONArray {
        val files = unzip(bytes)
        val ss = sharedStrings(files["xl/sharedStrings.xml"])
        // relationship id -> worksheet path
        val rels = HashMap<String, String>()
        files["xl/_rels/workbook.xml.rels"]?.let { b ->
            val p = parser(b)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id") ?: continue
                    var t = p.getAttributeValue(null, "Target") ?: continue
                    t = if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"
                    rels[id] = t
                }
            }
        }
        val sites = JSONArray()
        val wb = files["xl/workbook.xml"] ?: return sites
        val p = parser(wb)
        val sheets = ArrayList<Pair<String, String>>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: continue
                var rid: String? = null
                for (i in 0 until p.attributeCount) if (p.getAttributeName(i).endsWith("id") &&
                    p.getAttributeName(i) != "sheetId") rid = p.getAttributeValue(i)
                val path = rels[rid ?: ""] ?: continue
                sheets.add(name to path)
            }
        }
        for ((name, path) in sheets) {
            val b = files[path] ?: continue
            val site = parseSite(name, readSheet(b, ss)) ?: continue
            sites.put(site)
        }
        return sites
    }

    // ------------------------------------------------------------------ table logic (same as PC)
    private fun findHeader(rows: List<List<Any?>>): Pair<Int, Map<String, Int>>? {
        for (i in 0 until minOf(80, rows.size)) {
            val cols = HashMap<String, Int>()
            rows[i].forEachIndexed { j, v ->
                val t = (v as? String)?.trim() ?: return@forEachIndexed
                when {
                    "תאריך" in t || t.startsWith("שעה") -> cols.putIfAbsent("time", j)
                    "סוג" in t && "חומר" in t -> cols.putIfAbsent("material", j)
                    "קריאה" in t || "שעון" in t -> cols.putIfAbsent("reading", j)
                    "ספיקה" in t -> cols.putIfAbsent("flow", j)
                    "תוואי" in t -> cols.putIfAbsent("loc", j)
                }
            }
            if (cols.containsKey("time") && cols.containsKey("material") && cols.containsKey("reading")) return i to cols
        }
        return null
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val OUT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = UTC }
    private val IN_FORMATS = listOf("dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy", "dd.MM.yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
        .map { SimpleDateFormat(it, Locale.US).apply { timeZone = UTC; isLenient = false } }

    /** Wall-clock time as "yyyy-MM-ddTHH:mm:ss" (no time zone, like the sheet), rounded to the minute. */
    private fun toTime(v: Any?): String? {
        var ms: Long? = null
        when (v) {
            is Double -> if (v > 30000 && v < 80000) {
                val base = Calendar.getInstance(UTC).apply { clear(); set(1899, Calendar.DECEMBER, 30) }.timeInMillis
                ms = base + Math.round(v * 86_400_000.0)
            }
            is String -> {
                val s = v.trim()
                for (f in IN_FORMATS) {
                    try { ms = f.parse(s)?.time; if (ms != null) break } catch (e: Exception) {}
                }
            }
        }
        val m = ms ?: return null
        val rounded = (m + 30_000) / 60_000 * 60_000
        return synchronized(OUT) { OUT.format(rounded) }
    }

    private fun toNum(v: Any?): Double? = when (v) {
        is Double -> v
        is String -> v.replace(",", "").trim().toDoubleOrNull()
        else -> null
    }

    private fun parseSite(name: String, rows: List<List<Any?>>): JSONObject? {
        val (hi, cols) = findHeader(rows) ?: return null
        val out = JSONArray()
        var last: Double? = null
        for (k in hi + 1 until rows.size) {
            val row = rows[k]
            fun cell(key: String): Any? = cols[key]?.let { row.getOrNull(it) }
            val t = toTime(cell("time")) ?: continue
            val r = toNum(cell("reading")) ?: continue
            val mat = (cell("material") as? String)?.trim()?.ifEmpty { null } ?: "לא צוין"
            val locTxt = cell("loc") as? String ?: ""
            val loc = if ("מחוץ" in locTxt) "out" else "in"
            var d = if (last == null) 0.0 else r - last!!
            if (d < 0) d = 0.0
            last = r
            val f = toNum(cell("flow"))
            out.put(JSONObject().put("t", t).put("m", mat).put("loc", loc).put("r", r)
                .put("d", Math.round(d * 1000) / 1000.0).put("f", f ?: JSONObject.NULL))
        }
        return JSONObject().put("name", name).put("rows", out)
    }
}
