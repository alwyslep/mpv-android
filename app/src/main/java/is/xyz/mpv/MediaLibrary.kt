package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// P2 (media library home): NextPlayer식 폴더/비디오 홈을 위한 MediaStore 라이브 쿼리.
//   Room/sync 인프라 없이 매 진입 시 MediaStore 를 직접 읽어 폴더(bucket)별로 묶는다.

data class Vid(
    val id: Long,
    val uri: Uri,
    val path: String,
    val name: String,      // 확장자 제외
    val nameExt: String,   // 확장자 포함
    val durationMs: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val folderName: String,
    val folderPath: String,
    val dateModified: Long
)

data class Fold(
    val name: String,
    val path: String,
    val count: Int,
    val rep: Vid?          // 대표 비디오(썸네일 + 길이 오버레이용, 최신순 첫번째)
)

// 트리 모드 항목 — dirPath != null 이면 하위폴더, vid != null 이면 영상.
data class TreeEntry(
    val name: String,
    val dirPath: String?,
    val vid: Vid?
)

object MediaLibrary {
    fun fmtDur(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "%.1f GB".format(gb)
            mb >= 1 -> "%.0f MB".format(mb)
            else -> "%.0f KB".format(kb)
        }
    }

    fun queryVideos(ctx: Context): List<Vid> {
        val out = ArrayList<Vid>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        val coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val sort = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        ctx.contentResolver.query(coll, proj, null, null, sort)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val data = c.getString(iData) ?: ""
                val nameExt = c.getString(iName) ?: (if (data.isNotEmpty()) File(data).name else "video")
                val name = nameExt.substringBeforeLast(".")
                val parent = if (data.isNotEmpty()) (File(data).parent ?: "") else ""
                out.add(
                    Vid(
                        id = id,
                        uri = ContentUris.withAppendedId(coll, id),
                        path = data,
                        name = name,
                        nameExt = nameExt,
                        durationMs = c.getLong(iDur),
                        size = c.getLong(iSize),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        folderName = if (parent.isNotEmpty()) File(parent).name else "(기타)",
                        folderPath = parent,
                        dateModified = c.getLong(iDate)
                    )
                )
            }
        }
        return out
    }

    fun folders(vids: List<Vid>): List<Fold> {
        val map = LinkedHashMap<String, MutableList<Vid>>()
        for (v in vids) map.getOrPut(v.folderPath) { ArrayList() }.add(v)
        return map.map { (path, list) ->
            Fold(
                name = if (path.isNotEmpty()) File(path).name else "(기타)",
                path = path,
                count = list.size,
                rep = list.firstOrNull()   // 이미 DATE_MODIFIED DESC → 최신 영상이 대표
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun videosIn(vids: List<Vid>, folderPath: String): List<Vid> =
        vids.filter { it.folderPath == folderPath }

    // 모든 영상 폴더경로의 최장 공통 조상(트리 루트).
    fun treeRoot(vids: List<Vid>): String {
        val paths = vids.map { it.folderPath }.filter { it.isNotEmpty() }.distinct()
        if (paths.isEmpty()) return ""
        var prefix = paths[0].split("/")
        for (p in paths.drop(1)) {
            val segs = p.split("/")
            var i = 0
            while (i < prefix.size && i < segs.size && prefix[i] == segs[i]) i++
            prefix = prefix.subList(0, i)
        }
        return prefix.joinToString("/")
    }

    // dir 직속 하위폴더(영상 보유) + dir 직속 영상.
    fun treeChildren(vids: List<Vid>, dir: String): List<TreeEntry> {
        val prefix = if (dir.isEmpty() || dir == "/") "/" else "$dir/"
        val dirs = sortedSetOf<String>()
        val files = ArrayList<Vid>()
        for (v in vids) {
            val fp = v.folderPath
            if (fp == dir) {
                files.add(v)
            } else if (fp.startsWith(prefix)) {
                val seg = fp.substring(prefix.length).substringBefore("/")
                if (seg.isNotEmpty()) dirs.add(prefix + seg)
            }
        }
        val out = ArrayList<TreeEntry>()
        for (d in dirs) out.add(TreeEntry(File(d).name, d, null))
        for (v in files.sortedBy { it.name.lowercase() }) out.add(TreeEntry(v.name, null, v))
        return out
    }
}

// 최근 재생 목록 — Room 없이 SharedPreferences(JSON)로 간단 유지. 재생 진입 시 기록.
object Recents {
    private const val PREFS = "media_library"
    private const val KEY = "recent_played_v1"
    private const val MAX = 30

    fun add(ctx: Context, uri: String, title: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(p.getString(KEY, "[]"))
        val out = JSONArray()
        out.put(JSONObject().put("uri", uri).put("title", title))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("uri") == uri) continue
            out.put(o)
            if (out.length() >= MAX) break
        }
        p.edit().putString(KEY, out.toString()).apply()
    }

    fun list(ctx: Context): List<Pair<String, String>> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(p.getString(KEY, "[]"))
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(o.optString("uri") to o.optString("title"))
        }
        return out
    }
}

// 빠른설정(⊞) 값 — 레이아웃/정렬/필드 표시. 전 화면 공유(prefs "media_library").
object LibPrefs {
    private const val PREFS = "media_library"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun grid(ctx: Context) = p(ctx).getBoolean("video_grid", true)
    fun setGrid(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("video_grid", v).apply()

    // folder | videos | tree (tree 는 현재 folder 로 폴백)
    fun viewMode(ctx: Context): String = p(ctx).getString("view_mode", "folder") ?: "folder"
    fun setViewMode(ctx: Context, v: String) = p(ctx).edit().putString("view_mode", v).apply()

    // name(제목) | length(길이) | date(날짜) | size(크기) | path(위치)
    fun sortKey(ctx: Context): String = p(ctx).getString("sort_key", "name") ?: "name"
    fun sortAsc(ctx: Context) = p(ctx).getBoolean("sort_asc", true)
    fun setSort(ctx: Context, key: String, asc: Boolean) =
        p(ctx).edit().putString("sort_key", key).putBoolean("sort_asc", asc).apply()

    fun showDur(ctx: Context) = p(ctx).getBoolean("show_dur", true)        // 길이
    fun showExt(ctx: Context) = p(ctx).getBoolean("show_ext", false)       // 파일형식
    fun showPath(ctx: Context) = p(ctx).getBoolean("show_path", true)      // 경로
    fun showProgress(ctx: Context) = p(ctx).getBoolean("show_progress", true) // 재생진행률(placeholder)
    fun showRes(ctx: Context) = p(ctx).getBoolean("show_res", true)        // 해상도
    fun showSize(ctx: Context) = p(ctx).getBoolean("show_size", true)      // 크기
    fun showThumb(ctx: Context) = p(ctx).getBoolean("show_thumb", true)    // 섬네일
    fun setField(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()

    // 시청 상태 필터: all | unwatched | watching | watched
    fun watchFilter(ctx: Context): String = p(ctx).getString("watch_filter", "all") ?: "all"
    fun setWatchFilter(ctx: Context, v: String) = p(ctx).edit().putString("watch_filter", v).apply()

    // 즐겨찾기만 보기
    fun favOnly(ctx: Context) = p(ctx).getBoolean("fav_only", false)
    fun setFavOnly(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("fav_only", v).apply()

    fun showFav(ctx: Context) = p(ctx).getBoolean("show_fav", true)  // ♥/평점 뱃지

    // B-56: 통합 허브(receiver) 주소 — 같은 폰 Termux 의 127.0.0.1:8765.
    fun hubUrl(ctx: Context): String =
        p(ctx).getString("hub_url", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
    fun setHubUrl(ctx: Context, v: String) = p(ctx).edit().putString("hub_url", v).apply()

    // 0 안 봄 · 1 보는 중 · 2 다 봄 (Progress 위치 기준)
    fun watchStatus(ctx: Context, uri: String): Int {
        val pr = Progress.get(ctx, uri) ?: return 0
        val (pos, dur) = pr
        if (dur <= 0) return if (pos > 3000) 1 else 0
        return when {
            pos >= dur - 3000 -> 2
            pos > 3000 -> 1
            else -> 0
        }
    }

    // 행 필터 통합: 시청 상태 + 즐겨찾기. (호출처 다수라 이름 유지)
    fun passWatch(ctx: Context, uri: String): Boolean {
        val watchOk = when (watchFilter(ctx)) {
            "unwatched" -> watchStatus(ctx, uri) == 0
            "watching" -> watchStatus(ctx, uri) == 1
            "watched" -> watchStatus(ctx, uri) == 2
            else -> true
        }
        if (!watchOk) return false
        return !favOnly(ctx) || Favorites.has(ctx, uri)
    }

    private fun <T> sortGeneric(
        ctx: Context, list: List<T>,
        name: (T) -> String, date: (T) -> Long, size: (T) -> Long,
        length: (T) -> Long, path: (T) -> String, rating: (T) -> Int
    ): List<T> {
        val cmp = when (sortKey(ctx)) {
            "date" -> compareBy<T> { date(it) }
            "size" -> compareBy<T> { size(it) }
            "length" -> compareBy<T> { length(it) }
            "path" -> compareBy<T> { path(it).lowercase() }
            "rating" -> compareBy<T> { rating(it) }
            else -> compareBy<T> { name(it).lowercase() }
        }
        val s = list.sortedWith(cmp)
        return if (sortAsc(ctx)) s else s.reversed()
    }

    fun sortVids(ctx: Context, l: List<Vid>): List<Vid> =
        sortGeneric(ctx, l, { it.name }, { it.dateModified }, { it.size }, { it.durationMs }, { it.folderPath },
            { Ratings.get(ctx, it.uri.toString()) })

    fun sortFolds(ctx: Context, l: List<Fold>): List<Fold> =
        sortGeneric(ctx, l, { it.name }, { it.rep?.dateModified ?: 0L }, { it.count.toLong() },
            { it.rep?.durationMs ?: 0L }, { it.path }, { 0 })

    fun sortSaf(ctx: Context, l: List<SafEntry>): List<SafEntry> =
        sortGeneric(ctx, l, { it.name }, { 0L }, { it.size }, { 0L }, { it.name }, { 0 })
}

// 사용자가 '폴더 열기'로 권한 준 SAF 트리 uri 들 — 통합 검색 인덱싱 대상.
object SafTrees {
    private const val PREFS = "media_library"
    private const val KEY = "saf_trees_v1"

    fun add(ctx: Context, treeUri: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = LinkedHashSet(p.getStringSet(KEY, emptySet()) ?: emptySet())
        cur.add(treeUri)
        p.edit().putStringSet(KEY, cur).apply()
    }

    fun all(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
}

// 재생 위치 저장 — 이어보기 + 타일 진행률. uri 별 {pos, dur} (ms).
object Progress {
    private const val PREFS = "media_library"
    private const val KEY = "progress_v1"

    fun save(ctx: Context, uri: String, posMs: Long, durMs: Long) {
        if (durMs <= 0) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject(p.getString(KEY, "{}"))
        o.put(uri, JSONObject().put("p", posMs).put("d", durMs))
        p.edit().putString(KEY, o.toString()).apply()
    }

    // (pos, dur) ms 또는 null
    fun get(ctx: Context, uri: String): Pair<Long, Long>? {
        val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}"))
        if (!o.has(uri)) return null
        val e = o.getJSONObject(uri)
        return e.optLong("p") to e.optLong("d")
    }

    fun percent(ctx: Context, uri: String): Float {
        val (pos, dur) = get(ctx, uri) ?: return 0f
        if (dur <= 0) return 0f
        return (pos.toFloat() / dur).coerceIn(0f, 1f)
    }
}

// MPVActivity 실행/복귀 — 이어보기 위치 전달 + 종료 결과(position/duration) 기록 + 최근재생.
object Playback {
    fun intentFor(ctx: Context, uri: String, title: String, resume: Boolean = true): Intent {
        Recents.add(ctx, uri, title)
        val i = if (uri.startsWith("content://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        } else {
            Intent().putExtra("filepath", uri)
        }
        i.setClass(ctx, MPVActivity::class.java)
        if (resume) Progress.get(ctx, uri)?.let { (pos, dur) ->
            // 시작 직후/거의 끝이면 이어보기 생략
            if (pos > 3000 && pos < dur - 3000) i.putExtra("position", pos.toInt())
        }
        Tracks.get(ctx, uri)?.let { (aid, sid) ->
            if (aid.isNotEmpty()) i.putExtra("saved_aid", aid)
            if (sid.isNotEmpty()) i.putExtra("saved_sid", sid)
        }
        return i
    }

    // 자동 다음재생 조건: 설정 ON + 방금 작품을 끝까지 봄(다 봄).
    fun shouldAdvance(ctx: Context, uri: String): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean("autoplay_next", false) && LibPrefs.watchStatus(ctx, uri) == 2

    fun onResult(ctx: Context, uri: String, data: Intent?) {
        if (data == null) return
        val pos = data.getIntExtra("position", -1)
        val dur = data.getIntExtra("duration", -1)
        if (pos >= 0 && dur > 0) Progress.save(ctx, uri, pos.toLong(), dur.toLong())
        val aid = data.getStringExtra("saved_aid")
        val sid = data.getStringExtra("saved_sid")
        if (aid != null || sid != null) Tracks.save(ctx, uri, aid ?: "", sid ?: "")
        // B-56: 통합 허브 push (code 키). 위치/시청상태 + 트랙 한 번에.
        val f = HashMap<String, Any?>()
        if (pos >= 0 && dur > 0) {
            f["pos_ms"] = pos; f["dur_ms"] = dur
            f["watch"] = ReviewSync.watchOf(pos.toLong(), dur.toLong())
        }
        aid?.toIntOrNull()?.let { f["aid"] = it }
        sid?.toIntOrNull()?.let { f["sid"] = it }
        if (f.isNotEmpty()) ReviewSync.push(ctx, uri, f)
    }
}

// 오디오/자막 트랙 선택 기억 — uri 별 {aid, sid}(mpv 트랙 id 문자열).
object Tracks {
    private const val PREFS = "media_library"
    private const val KEY = "tracks_v1"

    fun save(ctx: Context, uri: String, aid: String, sid: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject(p.getString(KEY, "{}"))
        o.put(uri, JSONObject().put("a", aid).put("s", sid))
        p.edit().putString(KEY, o.toString()).apply()
    }

    fun get(ctx: Context, uri: String): Pair<String, String>? {
        val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}"))
        if (!o.has(uri)) return null
        val e = o.getJSONObject(uri)
        return e.optString("a") to e.optString("s")
    }
}

// 즐겨찾기(찜) — uri 집합. prefs StringSet.
object Favorites {
    private const val PREFS = "media_library"
    private const val KEY = "favorites_v1"

    fun has(ctx: Context, uri: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.contains(uri) ?: false

    fun toggle(ctx: Context, uri: String): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = LinkedHashSet(p.getStringSet(KEY, emptySet()) ?: emptySet())
        val now = if (cur.contains(uri)) { cur.remove(uri); false } else { cur.add(uri); true }
        p.edit().putStringSet(KEY, cur).apply()
        ReviewSync.push(ctx, uri, mapOf("fav" to if (now) 1 else 0))  // B-56
        return now
    }
}

// 평점 — uri 별 1~5 (0 = 없음). prefs JSON.
object Ratings {
    private const val PREFS = "media_library"
    private const val KEY = "ratings_v1"

    fun get(ctx: Context, uri: String): Int {
        val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}"))
        return o.optInt(uri, 0)
    }

    fun set(ctx: Context, uri: String, stars: Int) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject(p.getString(KEY, "{}"))
        if (stars <= 0) o.remove(uri) else o.put(uri, stars.coerceIn(1, 5))
        p.edit().putString(KEY, o.toString()).apply()
        ReviewSync.push(ctx, uri, mapOf("rating" to stars.coerceIn(0, 5)))  // B-56
    }
}

// B-56(통합 라이브러리 2단계): 시청/평가(review)를 통합 허브(receiver /review)로 push.
//   로컬 저장은 uri 키 유지(동작 중 UI 무손상), 허브엔 품번(code) 키로 전송(경계 변환).
//   code 못 구하면 skip. 백그라운드 스레드 fire-and-forget — 오프라인/허브다운 무시
//   (로컬엔 이미 저장됨). SSOT: docs/context_unified_library.md.
object ReviewSync {
    // 시청상태 0/1/2 — Progress/LibPrefs.watchStatus 와 동일 규칙.
    fun watchOf(posMs: Long, durMs: Long): Int =
        if (durMs > 0 && posMs >= durMs - 3000) 2 else if (posMs > 3000) 1 else 0

    fun push(ctx: Context, uri: String, fields: Map<String, Any?>) {
        val app = ctx.applicationContext
        Thread {
            try {
                val code = ThumbLoader.codeOf(app, uri) ?: return@Thread
                val body = JSONObject().put("code", code)
                for ((k, v) in fields) if (v != null) body.put(k, v)
                val url = URL(LibPrefs.hubUrl(app).trimEnd('/') + "/review")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 1500
                con.readTimeout = 1500
                con.requestMethod = "POST"
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                con.responseCode  // 전송 트리거 — 응답 본문은 무시
                con.disconnect()
            } catch (_: Throwable) {
                // 오프라인/허브 다운 — 로컬 저장은 이미 완료, 다음 상호작용에 재시도됨.
            }
        }.start()
    }
}

// B-56 pull-display: 통합 허브(receiver GET /library)에서 품번 단건 레코드를 당김.
//   상세화면이 임베드 메타와 겹치지 않는 cross-system 상태(cat_status·dl_status)를 표시.
//   백그라운드 스레드에서 콜백(콜백 내 UI 접근은 호출측이 runOnUiThread). 실패=null.
object Library {
    fun fetch(ctx: Context, code: String, cb: (JSONObject?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var rec: JSONObject? = null
            try {
                val url = URL(LibPrefs.hubUrl(app).trimEnd('/') +
                    "/library?limit=1&code=" + URLEncoder.encode(code, "UTF-8"))
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 1500
                con.readTimeout = 1500
                val text = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                con.disconnect()
                val rows = JSONObject(text).optJSONArray("rows")
                if (rows != null && rows.length() > 0) rec = rows.getJSONObject(0)
            } catch (_: Throwable) {
                // 허브 오프라인 — 조용히 null
            }
            cb(rec)
        }.start()
    }
}
