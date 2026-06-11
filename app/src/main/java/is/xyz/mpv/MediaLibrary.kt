package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
    val dateModified: Long,
    val volume: String = ""   // 드라이브 식별 — MediaStore VOLUME_NAME / SAF 루트(USB)명. 동일폴더명 충돌 구분용.
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

    // 드라이브 라벨 — 동일 폴더명 충돌 시 표시. 내장=external_primary, USB/SD=시리얼 첫 세그먼트, SAF=루트명.
    fun volLabel(vol: String): String = when {
        vol.isEmpty() -> "?"
        vol == "external_primary" || vol == "external" -> "내장"
        else -> vol.substringBefore('-').uppercase()
    }

    // 중복탐지/장면비교 제외 대상: 휴지통(.mpv-trash) · remux 임시파일 · 크기0(미완성/임시).
    //   휴지통 보낸 중복이 다시 중복으로 잡히는 것 방지(B-68).
    fun isTrashOrTemp(v: Vid): Boolean =
        v.folderPath.contains(".mpv-trash") || v.folderName.contains(".mpv-trash") ||
        v.nameExt.contains(".remux") || v.size <= 0L

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
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,      // 스코프 스토리지: DATA 비어도 채워짐
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, // 직속 폴더명
            MediaStore.Video.Media.VOLUME_NAME          // 드라이브(내장=external_primary, USB/SD=시리얼)
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
            val iRel = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            val iBucket = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val iVol = c.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val data = c.getString(iData) ?: ""
                val nameExt = c.getString(iName) ?: (if (data.isNotEmpty()) File(data).name else "video")
                val name = nameExt.substringBeforeLast(".")
                val rel = (if (iRel >= 0) c.getString(iRel) else null)?.trimEnd('/') ?: ""
                val bucket = (if (iBucket >= 0) c.getString(iBucket) else null) ?: ""
                // DATA 있으면 실경로, 없으면(스코프 스토리지) RELATIVE_PATH/BUCKET 으로 폴더 표기
                val parent = if (data.isNotEmpty()) (File(data).parent ?: "") else rel
                val fname = when {
                    parent.isNotEmpty() && data.isNotEmpty() -> File(parent).name
                    bucket.isNotEmpty() -> bucket
                    rel.isNotEmpty() -> rel.substringAfterLast('/')
                    else -> "(기타)"
                }
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
                        folderName = fname,
                        folderPath = parent,
                        dateModified = c.getLong(iDate),
                        volume = (if (iVol >= 0) c.getString(iVol) else null) ?: ""
                    )
                )
            }
        }
        // v5: 외부저장소(USB/SD) — SAF 등록 트리 영상 병합. MediaStore 는 내부(primary)만 인덱싱.
        out.addAll(safVids(ctx))
        return out
    }

    // v5: SAF 트리 스캔은 무거우니 세션 캐시. SafTrees add/clear 시 무효화.
    @Volatile private var safCache: List<Vid>? = null
    fun clearSafCache() { safCache = null }

    private fun safVids(ctx: Context): List<Vid> {
        safCache?.let { return it }
        val out = ArrayList<Vid>()
        for (t in SafTrees.all(ctx)) {
            try { walkSafVids(ctx, Uri.parse(t), out) } catch (_: Throwable) {}
            if (out.size > 50000) break
        }
        safCache = out
        return out
    }

    private fun walkSafVids(ctx: Context, treeUri: Uri, out: ArrayList<Vid>) {
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        // 드라이브 식별자(볼륨) = tree URI storage id. "5FD3-CB64:" → "5FD3-CB64".
        //  ※ 이전 substringAfterLast(":") 는 콜론이 끝이라 빈문자열 버그 → 두 USB 구분 불가했음(B-68).
        val volId = (treeUri.lastPathSegment ?: "USB").substringBefore(":").substringAfterLast("/").ifEmpty { "USB" }
        // 폴더경로 base: 병합ON(기본)=드라이브 무관("") → 같은 폴더명이 여러 드라이브라도 한 폴더 타일로 합침(앱 강점).
        //   병합OFF=드라이브 id 접두 → 드라이브별 폴더 분리. volume 은 항상 드라이브 id(중복탐지·💾태그용, 병합과 무관).
        val pathRoot = if (LibPrefs.mergeDrives(ctx)) "" else volId
        val stack = ArrayDeque<Pair<String, String>>()  // docId, 가상 폴더경로(드라이브/하위…)
        stack.addLast(DocumentsContract.getTreeDocumentId(treeUri) to pathRoot)
        while (stack.isNotEmpty()) {
            if (out.size > 50000) return
            val (doc, dpath) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, doc)
            try {
                ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val nm = c.getString(1) ?: ""
                        val mime = c.getString(2) ?: ""
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val lm = if (c.isNull(4)) 0L else c.getLong(4)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(id to "$dpath/$nm")
                        } else if (mime.startsWith("video/") || SafBrowserActivity.isVideoName(nm)) {
                            out.add(
                                Vid(
                                    id = 0L,
                                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                    path = "",
                                    name = nm.substringBeforeLast("."),
                                    nameExt = nm,
                                    durationMs = 0L,
                                    size = size,
                                    width = 0,
                                    height = 0,
                                    folderName = dpath.substringAfterLast("/"),
                                    folderPath = dpath,
                                    dateModified = lm / 1000,
                                    volume = volId,   // SAF 드라이브 id(병합과 무관 — 중복탐지·💾태그용)
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
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
    fun treeChildren(ctx: Context, vids: List<Vid>, dir: String, scope: String = "home_tree"): List<TreeEntry> {
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
        for (v in LibPrefs.sortVids(ctx, scope, files)) out.add(TreeEntry(v.name, null, v))  // 26: 트리 영상도 정렬 적용
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

    // 26: 정렬은 화면(scope)별 독립. key=name|length|date|size|path|rating|progress|favorite
    //   scope: home_folders / home_videos / home_tree / folder / saf / search
    fun sortKey(ctx: Context, scope: String): String = p(ctx).getString("sort_key_$scope", "name") ?: "name"
    fun sortAsc(ctx: Context, scope: String) = p(ctx).getBoolean("sort_asc_$scope", true)
    fun setSort(ctx: Context, scope: String, key: String, asc: Boolean) =
        p(ctx).edit().putString("sort_key_$scope", key).putBoolean("sort_asc_$scope", asc).apply()

    // 54: 툴바 아이콘 active 틴트 판정 — 해당 시트에 기본값과 다른 설정이 걸렸는지.
    fun sortActive(ctx: Context): Boolean {
        val scope = when (viewMode(ctx)) { "videos" -> "home_videos"; "tree" -> "home_tree"; else -> "home_folders" }
        return sortKey(ctx, scope) != "name" || !sortAsc(ctx, scope)
    }
    fun filterActive(ctx: Context): Boolean {
        val pr = p(ctx)
        fun setOn(k: String) = (pr.getStringSet(k, emptySet())?.isNotEmpty() == true)
        return pr.getInt("filter_res_max", 0) > 0 ||
            pr.getBoolean("embed_filter", false) ||
            pr.getBoolean("filter_mismatch_res", false) ||
            pr.getBoolean("filter_mismatch_dur", false) ||
            setOn("filter_ext") || setOn("filter_actress") || setOn("filter_studio") ||
            setOn("filter_series") || setOn("filter_genre")
    }
    fun quickActive(ctx: Context): Boolean =
        viewMode(ctx) != "folder" || !grid(ctx) ||
        watchFilter(ctx) != "all" || favOnly(ctx) || embedFilter(ctx) ||
        !showFav(ctx) || !showDur(ctx) || showExt(ctx) || !showPath(ctx) ||
        !showProgress(ctx) || !showRes(ctx) || !showSize(ctx) || !showThumb(ctx) ||
        coverScale(ctx) != 1f || coverAlign(ctx) != "center"

    fun showDur(ctx: Context) = p(ctx).getBoolean("show_dur", true)        // 길이
    fun showExt(ctx: Context) = p(ctx).getBoolean("show_ext", false)       // 파일형식
    fun showPath(ctx: Context) = p(ctx).getBoolean("show_path", true)      // 경로
    fun showProgress(ctx: Context) = p(ctx).getBoolean("show_progress", true) // 재생진행률(placeholder)
    fun showRes(ctx: Context) = p(ctx).getBoolean("show_res", true)        // 해상도
    fun showSize(ctx: Context) = p(ctx).getBoolean("show_size", true)      // 크기
    fun showThumb(ctx: Context) = p(ctx).getBoolean("show_thumb", true)    // 썸네일
    fun showTooltips(ctx: Context) = p(ctx).getBoolean("show_tooltips", true)  // B-64(47): 버튼 툴팁 on/off
    fun rtl(ctx: Context) = p(ctx).getBoolean("lib_rtl", false)  // 58: 라이브러리 RTL 레이아웃
    fun mergeDrives(ctx: Context) = p(ctx).getBoolean("merge_drives", true)  // 54: 다른 드라이브 동일 폴더명 병합(기본 ON=앱 강점)
    fun setField(ctx: Context, key: String, v: Boolean) = p(ctx).edit().putBoolean(key, v).apply()

    // 시청 상태 필터: all | unwatched | watching | watched
    fun watchFilter(ctx: Context): String = p(ctx).getString("watch_filter", "all") ?: "all"
    fun setWatchFilter(ctx: Context, v: String) = p(ctx).edit().putString("watch_filter", v).apply()

    // 즐겨찾기만 보기
    fun favOnly(ctx: Context) = p(ctx).getBoolean("fav_only", false)
    fun setFavOnly(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("fav_only", v).apply()

    fun showFav(ctx: Context) = p(ctx).getBoolean("show_fav", true)  // ♥/평점 뱃지
    fun embedFilter(ctx: Context) = p(ctx).getBoolean("embed_filter", false)  // jembed: 임베드없음만 표시
    fun coverAlign(ctx: Context): String = p(ctx).getString("cover_align", "center") ?: "center"  // 커버 정렬 left/center/right (임베드 커버만)
    fun setCoverAlign(ctx: Context, v: String) = p(ctx).edit().putString("cover_align", v).apply()
    // 5: 사용자가 시청 중 지정한 썸네일 위치(ms). -1=미지정(커버/3초 프레임). 키는 uri.
    fun customThumbPos(ctx: Context, uri: String): Long = p(ctx).getLong("cthumb_$uri", -1L)
    fun setCustomThumbPos(ctx: Context, uri: String, ms: Long) = p(ctx).edit().putLong("cthumb_$uri", ms).apply()
    fun clearCustomThumbPos(ctx: Context, uri: String) = p(ctx).edit().remove("cthumb_$uri").apply()  // 장면 썸네일 해제(재지정)
    // 6: 상세보기 커버 높이(dp) — 슬라이더/마우스휠로 조절.
    fun detailCoverHeight(ctx: Context): Int = p(ctx).getInt("detail_cover_h", 300)
    fun setDetailCoverHeight(ctx: Context, dp: Int) = p(ctx).edit().putInt("detail_cover_h", dp).apply()

    // B-57 v3: 커버 타일 크기 — cover_scale(%) 클수록 큰 타일(열 적게). 50~200%.
    fun coverScale(ctx: Context): Float =
        p(ctx).getInt("cover_scale", 100).coerceIn(50, 200) / 100f
    fun setCoverScale(ctx: Context, v: Int) =
        p(ctx).edit().putInt("cover_scale", v.coerceIn(50, 200)).apply()

    /** grid 열 개수 — 화면폭 / (170dp × scale). scale 클수록 열 적음 = 타일 큼. */
    fun spanCount(ctx: Context): Int {
        val w = ctx.resources.configuration.screenWidthDp
        return maxOf(1, (w / (170f * coverScale(ctx))).toInt())
    }

    /** grid 커버 컨테이너 높이(px) — 셀 폭 × 비율(열+높이 비례, 포스터 느낌 유지). */
    fun gridCoverHeightPx(ctx: Context): Int {
        val cellW = ctx.resources.displayMetrics.widthPixels.toFloat() / spanCount(ctx)
        return (cellW * 1.05f).toInt().coerceAtLeast(1)
    }

    // B-56: 통합 허브(receiver) 주소 — 같은 폰 Termux 의 127.0.0.1:8765.
    fun hubUrl(ctx: Context): String =
        p(ctx).getString("hub_url", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
    fun setHubUrl(ctx: Context, v: String) = p(ctx).edit().putString("hub_url", v).apply()

    // 0 안 봄 · 1 보는 중 · 2 다 봄 (Progress 위치 기준)
    fun watchStatus(ctx: Context, uri: String, name: String?): Int {
        val pr = Progress.get(ctx, uri, name) ?: return 0
        val (pos, dur) = pr
        if (dur <= 0) return if (pos > 3000) 1 else 0
        return when {
            pos >= dur - 3000 -> 2
            pos > 3000 -> 1
            else -> 0
        }
    }

    // 행 필터 통합: 시청 상태 + 즐겨찾기. (호출처 다수라 이름 유지)
    fun passWatch(ctx: Context, uri: String, name: String?): Boolean {
        val watchOk = when (watchFilter(ctx)) {
            "unwatched" -> watchStatus(ctx, uri, name) == 0
            "watching" -> watchStatus(ctx, uri, name) == 1
            "watched" -> watchStatus(ctx, uri, name) == 2
            else -> true
        }
        if (!watchOk) return false
        return !favOnly(ctx) || Favorites.has(ctx, uri, name)
    }

    private fun <T> sortGeneric(
        ctx: Context, scope: String, list: List<T>,
        name: (T) -> String, date: (T) -> Long, size: (T) -> Long,
        length: (T) -> Long, path: (T) -> String, rating: (T) -> Int,
        progress: (T) -> Float, fav: (T) -> Boolean
    ): List<T> {
        val cmp = when (sortKey(ctx, scope)) {
            "date" -> compareBy<T> { date(it) }
            "size" -> compareBy<T> { size(it) }
            "length" -> compareBy<T> { length(it) }
            "path" -> compareBy<T> { path(it).lowercase() }
            "rating" -> compareBy<T> { rating(it) }
            "progress" -> compareBy<T> { progress(it) }
            "favorite" -> compareBy<T> { if (fav(it)) 1 else 0 }
            else -> compareBy<T> { name(it).lowercase() }
        }
        val s = list.sortedWith(cmp)
        return if (sortAsc(ctx, scope)) s else s.reversed()
    }

    fun sortVids(ctx: Context, scope: String, l: List<Vid>): List<Vid> =
        sortGeneric(ctx, scope, l, { it.name }, { it.dateModified }, { it.size }, { it.durationMs }, { it.folderPath },
            { Ratings.get(ctx, it.uri.toString(), it.name) },
            { Progress.percent(ctx, it.uri.toString(), it.name) },
            { Favorites.has(ctx, it.uri.toString(), it.name) })

    fun sortFolds(ctx: Context, scope: String, l: List<Fold>): List<Fold> =
        sortGeneric(ctx, scope, l, { it.name }, { it.rep?.dateModified ?: 0L }, { it.count.toLong() },
            { it.rep?.durationMs ?: 0L }, { it.path }, { 0 }, { 0f }, { false })

    fun sortSaf(ctx: Context, scope: String, l: List<SafEntry>): List<SafEntry> =
        sortGeneric(ctx, scope, l, { it.name }, { 0L }, { it.size }, { 0L }, { it.name }, { 0 }, { 0f }, { false })

    fun sortSearch(ctx: Context, l: List<SearchItem>): List<SearchItem> =
        sortGeneric(ctx, "search", l, { it.name }, { 0L }, { it.size }, { it.durationMs }, { it.folder },
            { Ratings.get(ctx, it.uri.toString(), it.name) },
            { Progress.percent(ctx, it.uri.toString(), it.name) },
            { Favorites.has(ctx, it.uri.toString(), it.name) })
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
        MediaLibrary.clearSafCache()   // v5: 새 트리 등록 → 홈/검색 재스캔
        SearchIndex.clear()
    }

    fun all(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        MediaLibrary.clearSafCache()
        SearchIndex.clear()
    }
}

// B-57 v6: 길이 불일치 표시용 — receiver GET /durations(code→duration_sec) 1회 fetch 캐시.
object DurationHub {
    @Volatile private var map: Map<String, Long>? = null
    fun get(code: String): Long? = map?.get(code.uppercase())
    fun clear() { map = null }
    fun fetchAsync(ctx: Context) {
        if (map != null) return
        val app = ctx.applicationContext
        Thread {
            try {
                val url = URL(LibPrefs.hubUrl(app).trimEnd('/') + "/durations")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 2000
                con.readTimeout = 4000
                val text = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                con.disconnect()
                val d = JSONObject(text).optJSONObject("durations") ?: JSONObject()
                val m = HashMap<String, Long>(d.length())
                val keys = d.keys()
                while (keys.hasNext()) { val k = keys.next(); m[k.uppercase()] = d.optLong(k) }
                map = m
            } catch (_: Throwable) {}
        }.start()
    }
}

// 4 해상도: receiver GET /resolutions(code→resolution_h, jav_dl 가 m3u8 최고 variant 적재) 1회 캐시.
// 실제 파일 height(v.height, MediaStore)가 기대보다 낮으면 저화질/부분 마커. SAF(height=0)는 비교 안 함.
object ResolutionHub {
    @Volatile private var map: Map<String, Int>? = null
    fun get(code: String): Int? = map?.get(code.uppercase())
    fun clear() { map = null }
    fun fetchAsync(ctx: Context) {
        if (map != null) return
        val app = ctx.applicationContext
        Thread {
            try {
                val url = URL(LibPrefs.hubUrl(app).trimEnd('/') + "/resolutions")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 2000
                con.readTimeout = 4000
                val text = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                con.disconnect()
                val d = JSONObject(text).optJSONObject("resolutions") ?: JSONObject()
                val m = HashMap<String, Int>(d.length())
                val keys = d.keys()
                while (keys.hasNext()) { val k = keys.next(); m[k.uppercase()] = d.optInt(k) }
                map = m
            } catch (_: Throwable) {}
        }.start()
    }
}

// 필터 2차: receiver GET /meta-index(code→배우/스튜디오/시리즈/장르) 1회 캐시 + distinct 목록.
// 품번은 파일명 정규식으로 추출(MMR 회피, 빠름) → hub 메타 조회. hub 없는 파일은 메타 매칭 제외.
object MetaHub {
    data class Meta(val actress: List<String>, val studio: String, val series: String, val genres: List<String>)
    @Volatile private var map: Map<String, Meta>? = null
    @Volatile var actresses: List<String> = emptyList(); private set
    @Volatile var studios: List<String> = emptyList(); private set
    @Volatile var seriesList: List<String> = emptyList(); private set
    @Volatile var genres: List<String> = emptyList(); private set

    fun get(code: String): Meta? = map?.get(code.uppercase())
    fun ready(): Boolean = map != null
    fun clear() { map = null }

    fun fetchAsync(ctx: Context) {
        if (map != null) return
        val app = ctx.applicationContext
        Thread {
            try {
                val url = URL(LibPrefs.hubUrl(app).trimEnd('/') + "/meta-index")
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 2000
                con.readTimeout = 8000
                val text = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                con.disconnect()
                val mObj = JSONObject(text).optJSONObject("meta") ?: JSONObject()
                val m = HashMap<String, Meta>(mObj.length())
                val aSet = sortedSetOf<String>(); val sSet = sortedSetOf<String>()
                val seSet = sortedSetOf<String>(); val gSet = sortedSetOf<String>()
                val keys = mObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next(); val o = mObj.optJSONObject(k) ?: continue
                    val act = parseArr(o.optString("a")); val stu = o.optString("s").trim()
                    val ser = o.optString("se").trim(); val gen = parseArr(o.optString("g"))
                    m[k.uppercase()] = Meta(act, stu, ser, gen)
                    aSet.addAll(act); if (stu.isNotEmpty()) sSet.add(stu)
                    if (ser.isNotEmpty()) seSet.add(ser); gSet.addAll(gen)
                }
                map = m
                actresses = aSet.toList(); studios = sSet.toList(); seriesList = seSet.toList(); genres = gSet.toList()
            } catch (_: Throwable) {}
        }.start()
    }

    private fun parseArr(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        } catch (_: Throwable) {
            s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

// 통합 필터 엔진 — 시청/임베드/해상도/확장자/불일치/메타(배우·스튜디오·시리즈·장르)를 AND 로 판정.
// 세 화면(videos/folder/tree)이 .filter { FilterEngine.passes(ctx, vid) } 하나로 적용.
object FilterEngine {
    private const val PREFS = "media_library"
    // B-53: 품번 인식 공용 JavCode 파서로 통일(docs/code_patterns.md)

    fun anyActive(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean("embed_filter", false) || p.getInt("filter_res_max", 0) > 0 ||
            (p.getStringSet("filter_ext", emptySet())?.isNotEmpty() == true) ||
            p.getBoolean("filter_mismatch_res", false) || p.getBoolean("filter_mismatch_dur", false) ||
            (p.getStringSet("filter_actress", emptySet())?.isNotEmpty() == true) ||
            (p.getStringSet("filter_studio", emptySet())?.isNotEmpty() == true) ||
            (p.getStringSet("filter_series", emptySet())?.isNotEmpty() == true) ||
            (p.getStringSet("filter_genre", emptySet())?.isNotEmpty() == true)
    }

    fun passes(ctx: Context, v: Vid): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!LibPrefs.passWatch(ctx, v.uri.toString(), v.name)) return false
        if (p.getBoolean("embed_filter", false) && !JEmbed.isUnembedded(ctx, v.uri, v.path.ifEmpty { null })) return false
        val resMax = p.getInt("filter_res_max", 0)
        if (resMax > 0 && (v.height <= 0 || v.height > resMax)) return false
        val exts = p.getStringSet("filter_ext", emptySet()) ?: emptySet()
        if (exts.isNotEmpty() && v.nameExt.substringAfterLast('.', "").uppercase() !in exts) return false
        val code = JavCode.extract(v.name)
        if (p.getBoolean("filter_mismatch_res", false)) {
            val hubH = code?.let { ResolutionHub.get(it) } ?: return false
            if (!(v.height in 1 until (hubH - hubH / 10))) return false
        }
        if (p.getBoolean("filter_mismatch_dur", false)) {
            val hubSec = code?.let { DurationHub.get(it) } ?: return false
            val tol = maxOf(10L, hubSec / 50)
            if (kotlin.math.abs(v.durationMs / 1000 - hubSec) <= tol) return false
        }
        val fa = p.getStringSet("filter_actress", emptySet()) ?: emptySet()
        val fs = p.getStringSet("filter_studio", emptySet()) ?: emptySet()
        val fse = p.getStringSet("filter_series", emptySet()) ?: emptySet()
        val fg = p.getStringSet("filter_genre", emptySet()) ?: emptySet()
        if (fa.isNotEmpty() || fs.isNotEmpty() || fse.isNotEmpty() || fg.isNotEmpty()) {
            val meta = code?.let { MetaHub.get(it) } ?: return false
            if (fa.isNotEmpty() && meta.actress.none { it in fa }) return false
            if (fs.isNotEmpty() && meta.studio !in fs) return false
            if (fse.isNotEmpty() && meta.series !in fse) return false
            if (fg.isNotEmpty() && meta.genres.none { it in fg }) return false
        }
        return true
    }
}

// 재생 위치 저장 — 이어보기 + 타일 진행률. uri 별 {pos, dur} (ms).
object Progress {
    private const val PREFS = "media_library"
    private const val KEY = "progress_v1"

    // 키 = MediaKey.of(uri, name) — 품번/파일명 기준(경로/이동 무관). name 은 파일명(확장자 포함).
    fun save(ctx: Context, uri: String, name: String?, posMs: Long, durMs: Long) {
        if (durMs <= 0) return
        val key = MediaKey.of(uri, name)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject(p.getString(KEY, "{}"))
        o.put(key, JSONObject().put("p", posMs).put("d", durMs))
        p.edit().putString(KEY, o.toString()).apply()
    }

    // (pos, dur) ms 또는 null
    fun get(ctx: Context, uri: String, name: String?): Pair<Long, Long>? {
        val key = MediaKey.of(uri, name)
        val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}"))
        if (!o.has(key)) return null
        val e = o.getJSONObject(key)
        return e.optLong("p") to e.optLong("d")
    }

    fun percent(ctx: Context, uri: String, name: String?): Float {
        val (pos, dur) = get(ctx, uri, name) ?: return 0f
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
        i.putExtra("media_name", title)   // 1-2차: 품번/파일명 키 산출용(이어보기/진행저장 정합)
        if (resume) Progress.get(ctx, uri, title)?.let { (pos, dur) ->
            // 시작 직후/거의 끝이면 이어보기 생략
            if (pos > 3000 && pos < dur - 3000) i.putExtra("position", pos.toInt())
        }
        Tracks.get(ctx, uri, title)?.let { (aid, sid) ->
            if (aid.isNotEmpty()) i.putExtra("saved_aid", aid)
            if (sid.isNotEmpty()) i.putExtra("saved_sid", sid)
        }
        return i
    }

    // B-68: 플레이어가 현재 파일을 휴지통/이동으로 제거했는지(결과 removed 플래그). true 면
    //   shouldAdvance(끝까지봄) 무관하게 다음 영상으로 즉시 advance. 런처는 리스트 갱신 *전*에
    //   다음 항목을 캡처해야 정확(제거로 인덱스가 당겨지므로).
    fun wasRemoved(data: Intent?): Boolean = data?.getBooleanExtra("removed", false) ?: false

    // PgDn(+1)/PgUp(-1) 수동 넘김 신호. 0=없음. 런처가 폴더에서 해당 방향 영상을 fresh 실행.
    fun advanceDir(data: Intent?): Int = data?.getIntExtra("advance", 0) ?: 0

    // 자동 다음재생 조건: 설정 ON + 방금 작품을 끝까지 봄(다 봄).
    fun shouldAdvance(ctx: Context, uri: String, name: String?): Boolean {
        val auto = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("autoplay_next", false)
        val ws = LibPrefs.watchStatus(ctx, uri, name)
        JavDiag.log("autonext", "auto=$auto watch=$ws key=${MediaKey.of(uri, name)} prog=${Progress.get(ctx, uri, name)} name=$name uri=${uri.take(48)}")
        return auto && ws == 2
    }

    fun onResult(ctx: Context, uri: String, data: Intent?) {
        if (data == null) return
        val pos = data.getIntExtra("position", -1)
        val dur = data.getIntExtra("duration", -1)
        // 진행위치/트랙 로컬 저장은 MPVActivity 가 직접(품번/파일명 키, 외부 실행 통일). 여기선 hub push 만.
        val aid = data.getStringExtra("saved_aid")
        val sid = data.getStringExtra("saved_sid")
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

    fun save(ctx: Context, uri: String, name: String?, aid: String, sid: String) {
        val key = MediaKey.of(uri, name)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject(p.getString(KEY, "{}"))
        o.put(key, JSONObject().put("a", aid).put("s", sid))
        p.edit().putString(KEY, o.toString()).apply()
    }

    fun get(ctx: Context, uri: String, name: String?): Pair<String, String>? {
        val key = MediaKey.of(uri, name)
        val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}"))
        if (!o.has(key)) return null
        val e = o.getJSONObject(key)
        return e.optString("a") to e.optString("s")
    }
}

// 즐겨찾기(찜) — uri 집합. prefs StringSet.
object Favorites {
    private const val PREFS = "media_library"
    private const val KEY = "favorites_v1"

    fun has(ctx: Context, uri: String, name: String?): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.contains(MediaKey.of(uri, name)) ?: false

    fun toggle(ctx: Context, uri: String, name: String?): Boolean {
        val key = MediaKey.of(uri, name)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = LinkedHashSet(p.getStringSet(KEY, emptySet()) ?: emptySet())
        val now = if (cur.contains(key)) { cur.remove(key); false } else { cur.add(key); true }
        p.edit().putStringSet(KEY, cur).apply()
        ReviewSync.push(ctx, uri, mapOf("fav" to if (now) 1 else 0))  // B-56 (hub 는 uri/code 키)
        return now
    }
}

// 평점 — uri 별 1~5 (0 = 없음). prefs JSON.
object Ratings {
    private const val PREFS = "media_library"
    private const val KEY = "ratings_v1"

    fun get(ctx: Context, uri: String, name: String?): Int {
        val o = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}"))
        return o.optInt(MediaKey.of(uri, name), 0)
    }

    fun set(ctx: Context, uri: String, name: String?, stars: Int) {
        val key = MediaKey.of(uri, name)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject(p.getString(KEY, "{}"))
        if (stars <= 0) o.remove(key) else o.put(key, stars.coerceIn(1, 5))
        p.edit().putString(KEY, o.toString()).apply()
        ReviewSync.push(ctx, uri, mapOf("rating" to stars.coerceIn(0, 5)))  // B-56 (hub 는 uri/code 키)
    }
}

// 3: 라이브러리 데이터(재생위치/트랙/평점/찜/장면지정) 백업·복원·병합 — 품번/파일명 키라 기기·이동 무관.
object CacheBackup {
    private const val PREFS = "media_library"
    private val MAPS = listOf("progress_v1", "tracks_v1", "ratings_v1")  // String JSON object

    fun export(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = JSONObject()
        for (k in MAPS) o.put(k, p.getString(k, "{}"))
        o.put("favorites_v1", org.json.JSONArray((p.getStringSet("favorites_v1", emptySet()) ?: emptySet()).toList()))
        val ct = JSONObject()
        p.all.forEach { (k, v) -> if (k.startsWith("cthumb_") && v is Long) ct.put(k, v) }
        o.put("cthumb", ct)
        return o.toString(2)
    }

    // merge=false: 덮어쓰기(복원). merge=true: 기존과 합치기(겹치면 가져온 값 우선).
    fun import(ctx: Context, json: String, merge: Boolean): Boolean = try {
        val o = JSONObject(json)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        for (k in MAPS) {
            val inc = JSONObject(o.optString(k, "{}"))
            if (merge) {
                val cur = JSONObject(p.getString(k, "{}"))
                val it = inc.keys(); while (it.hasNext()) { val kk = it.next(); cur.put(kk, inc.get(kk)) }
                e.putString(k, cur.toString())
            } else e.putString(k, inc.toString())
        }
        val arr = o.optJSONArray("favorites_v1")
        val incFav = if (arr != null) (0 until arr.length()).map { arr.getString(it) }.toSet() else emptySet()
        e.putStringSet("favorites_v1", if (merge) (p.getStringSet("favorites_v1", emptySet()) ?: emptySet()) + incFav else incFav)
        o.optJSONObject("cthumb")?.let { ct -> val it = ct.keys(); while (it.hasNext()) { val kk = it.next(); e.putLong(kk, ct.getLong(kk)) } }
        e.apply()
        true
    } catch (_: Throwable) { false }
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

    // 배치(jembed) 용 동기 조회 sentinel — 조회는 성공했으나 그 code 의 메타가 없음(rows 빈).
    //   fetchSync 반환: null=일시 실패(예외·재시도 소진), EMPTY_REC=진짜 메타 없음, 그 외=메타 rec.
    //   JEmbed force(v74): 일시실패 vs 진짜없음 구분 → TS 면 remux 만이라도 진행(통째 포기 방지).
    val EMPTY_REC: JSONObject = JSONObject()

    // 배치(jembed) 용 동기 조회 — 이미 백그라운드 스레드에서 호출 가정.
    // connect 3s/read 8s + 백오프 재시도 3회: 임베드 트리거 순간 receiver 가 첫 호출을 흘리는
    // 간헐(증상: 첫 임베드 '메타 없음'→remux 도 skip, 손으로 다시 하면 성공)을 흡수.
    // receiver v0.43 푸시다운으로 응답 sub-ms 라 평소 1회면 충분(근본수정), 재시도는 보험.
    fun fetchSync(ctx: Context, code: String): JSONObject? {
        val url = URL(LibPrefs.hubUrl(ctx).trimEnd('/') +
            "/library?limit=1&code=" + URLEncoder.encode(code, "UTF-8"))
        repeat(3) { attempt ->
            try {
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 3000; con.readTimeout = 8000
                val text = con.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                con.disconnect()
                val arr = JSONObject(text).optJSONArray("rows")
                return if (arr != null && arr.length() > 0) arr.getJSONObject(0) else EMPTY_REC
            } catch (_: Throwable) {
                if (attempt < 2) try { Thread.sleep(600L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        return null   // 예외 소진 = 일시 실패(receiver 닿지 않음)
    }
}
