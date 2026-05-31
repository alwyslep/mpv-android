package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    private fun <T> sortGeneric(
        ctx: Context, list: List<T>,
        name: (T) -> String, date: (T) -> Long, size: (T) -> Long,
        length: (T) -> Long, path: (T) -> String
    ): List<T> {
        val cmp = when (sortKey(ctx)) {
            "date" -> compareBy<T> { date(it) }
            "size" -> compareBy<T> { size(it) }
            "length" -> compareBy<T> { length(it) }
            "path" -> compareBy<T> { path(it).lowercase() }
            else -> compareBy<T> { name(it).lowercase() }
        }
        val s = list.sortedWith(cmp)
        return if (sortAsc(ctx)) s else s.reversed()
    }

    fun sortVids(ctx: Context, l: List<Vid>): List<Vid> =
        sortGeneric(ctx, l, { it.name }, { it.dateModified }, { it.size }, { it.durationMs }, { it.folderPath })

    fun sortFolds(ctx: Context, l: List<Fold>): List<Fold> =
        sortGeneric(ctx, l, { it.name }, { it.rep?.dateModified ?: 0L }, { it.count.toLong() },
            { it.rep?.durationMs ?: 0L }, { it.path })

    fun sortSaf(ctx: Context, l: List<SafEntry>): List<SafEntry> =
        sortGeneric(ctx, l, { it.name }, { 0L }, { it.size }, { 0L }, { it.name })
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
}
