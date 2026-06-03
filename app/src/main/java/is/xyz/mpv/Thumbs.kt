package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// P2: 비디오 카드 메타 로더.
//   썸네일 ① 임베드 커버(covr/attached_pic) ② 없으면 비디오 프레임(MMR)
//   라벨   임베드 타이틀 "품번 공백 한글제목" → code=품번 / title=한글제목(품번 중복 제거).
//          임베드 타이틀 없으면 code=파일명, title 숨김.
//   길이   durView 주어지면 MMR 로 함께 추출(SAF 등 길이 미상).
//   RecyclerView 재사용 경합은 View.tag 로 막는다.
data class CachedVideo(
    val uri: String, val code: String, val title: String, val dur: Long?,
    val artist: String, val studio: String, val series: String
)

object ThumbLoader {
    private val exec = Executors.newFixedThreadPool(3)
    private val bmpCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // [code, title]. ["",""] = 임베드 타이틀 없음(파일명 사용). null = 미확인.
    private val metaCache = ConcurrentHashMap<String, Array<String>>()
    private val durCache = ConcurrentHashMap<String, Long>() // -1 = 없음

    // 검색용 — 이미 MMR 캐시된 [code, title] 노출(null = 아직 미확인).
    fun cachedMeta(key: String): Array<String>? = metaCache[key]

    // B-56: uri → 품번(code). 캐시 우선, 없으면 MMR 타이틀 1회 추출+캐시.
    //   임베드 타이틀 없으면(=code 빈값) null → review 허브 push 대상 아님(파일명 키 사절).
    fun codeOf(ctx: Context, uri: String): String? {
        metaCache[uri]?.let { return it[0].ifEmpty { null } }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, Uri.parse(uri))
            val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            val meta = if (raw.isNotEmpty()) parseTitle(raw) else arrayOf("", "")
            metaCache[uri] = meta
            meta[0].ifEmpty { null }
        } catch (_: Throwable) {
            null
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    // 전체 스캔용 — 커버 없이 메타(품번/제목/길이/배우/스튜디오/시리즈)만 MMR→디스크 캐시.
    //   이미 .txt 있으면 skip. 커버는 타일 볼 때 지연 로드.
    fun indexMeta(ctx: Context, uri: Uri, fallbackName: String) {
        val key = uri.toString()
        val hk = hashKey(key)
        if (File(cacheDir(ctx), "$hk.txt").exists()) return
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            val meta = if (raw.isNotEmpty()) parseTitle(raw) else arrayOf("", "")
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            val art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
            val stu = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim().orEmpty()
            val ser = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
            saveDiskMeta(ctx, hk, key, meta, dur, art, stu, ser)
        } catch (_: Throwable) {
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    fun clearCache(ctx: Context) {
        bmpCache.evictAll()
        metaCache.clear()
        durCache.clear()
        try { cacheDir(ctx).deleteRecursively() } catch (_: Throwable) {}
    }

    // v6: 단일 영상 캐시만 무효화 — embed 후 전체 clearCache 의 썸네일 전멸 부작용 방지.
    fun invalidate(ctx: Context, uri: Uri) {
        val key = uri.toString()
        bmpCache.remove(key); metaCache.remove(key); durCache.remove(key)
        val hk = hashKey(key); val d = cacheDir(ctx)
        runCatching { File(d, "$hk.jpg").delete() }
        runCatching { File(d, "$hk.txt").delete() }
    }

    // v6: 로컬 파일 길이 vs hub(queue.sqlite) duration_sec 불일치 → cb(true).
    //   품번은 codeOf(캐시 우선, 없으면 MMR 1회). hub 맵 없으면(오프라인) 조용히 무시.
    fun checkDurMismatch(ctx: Context, uri: Uri, localDurMs: Long, cb: (Boolean) -> Unit) {
        if (localDurMs <= 0L) return
        val app = ctx.applicationContext
        exec.execute {
            val code = codeOf(app, uri.toString()) ?: return@execute
            val hubSec = DurationHub.get(code) ?: return@execute
            val localSec = localDurMs / 1000
            val tol = maxOf(10L, hubSec / 50)   // 10초 또는 2% 중 큰 값(인코딩 오차 허용)
            val mismatch = kotlin.math.abs(localSec - hubSec) > tol
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(mismatch) }
        }
    }

    // 4 해상도: 실제 파일 height(MediaStore v.height) vs hub 기대 height. 의미있게 낮으면 cb(기대height).
    //   품번은 codeOf(캐시 우선). hub 맵 없거나(오프라인)·기대값 없거나·낮지 않으면 조용히 무시. MMR 불필요.
    fun checkResMismatch(ctx: Context, uri: Uri, localHeight: Int, cb: (Int) -> Unit) {
        if (localHeight <= 0) return
        val app = ctx.applicationContext
        exec.execute {
            val code = codeOf(app, uri.toString()) ?: return@execute
            val hubH = ResolutionHub.get(code) ?: return@execute
            if (hubH <= 0) return@execute
            // 실제가 기대의 90% 미만이면 저화질/부분(인코딩 리사이즈 오차 10% 허용). 높을 땐 마커 안 함.
            if (localHeight < hubH - maxOf(0, hubH / 10)) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { cb(hubH) }
            }
        }
    }

    fun load(
        thumb: ImageView,
        codeView: TextView?,
        titleView: TextView?,
        uri: Uri,
        fallbackName: String,
        durView: TextView? = null
    ) {
        val key = uri.toString()
        thumb.tag = key
        codeView?.tag = key
        titleView?.tag = key
        durView?.tag = key

        val cb = bmpCache.get(key)
        val cm = metaCache[key]
        val cd = durCache[key]
        thumb.setImageBitmap(cb)
        applyCoverAlign(thumb, cm != null && cm.isNotEmpty() && cm[0].isNotBlank())
        applyText(codeView, titleView, cm, fallbackName)
        if (durView != null) applyDur(durView, cd)

        val needDur = durView != null && cd == null
        if (cb != null && cm != null && !needDur) return

        val ctx = thumb.context.applicationContext
        exec.execute {
            var bmp = cb
            var meta = cm
            var dur = cd
            val hk = hashKey(key)

            // ① 디스크 캐시 (MMR 회피 — 재진입 즉시)
            if (bmp == null) bmp = loadDiskBmp(ctx, hk)
            if (meta == null) loadDiskMeta(ctx, hk)?.let { (c, t, d) ->
                meta = arrayOf(c, t)
                if (dur == null && d != null) dur = d
            }

            // ② 부족분만 MMR — 이때 배우/스튜디오/시리즈도 함께 추출해 7필드 캐시
            if (bmp == null || meta == null || (needDur && dur == null)) {
                var freshBmp = false
                var art = ""; var stu = ""; var ser = ""
                val customUs = LibPrefs.customThumbPos(ctx, key).let { if (it >= 0) it * 1000 else -1L }  // 5: 사용자 지정 썸네일 위치
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(ctx, uri)
                    if (bmp == null && customUs < 0) {   // 커스텀 썸네일 미지정 시에만 임베드 커버
                        val bytes = mmr.embeddedPicture
                        if (bytes != null) { bmp = decodeSampled(bytes, 600); freshBmp = bmp != null }
                    }
                    if (meta == null) {
                        val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                        meta = if (raw.isNotEmpty()) parseTitle(raw) else arrayOf("", "")
                    }
                    if (dur == null) {
                        dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    }
                    art = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
                    stu = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim().orEmpty()
                    ser = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
                    if (bmp == null) { bmp = scaledFrame(mmr, if (customUs >= 0) customUs else 3_000_000L); freshBmp = bmp != null }  // 5: 지정 위치 or 3초 프레임
                } catch (_: Throwable) {
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
                if (freshBmp) bmp?.let { saveDiskBmp(ctx, hk, it) }
                meta?.let { saveDiskMeta(ctx, hk, key, it, dur, art, stu, ser) }
            }

            if (bmp != null) bmpCache.put(key, bmp!!)
            if (meta != null) metaCache[key] = meta!!
            if (dur != null) durCache[key] = dur!!

            val fb = bmp; val fm = meta; val fd = dur
            thumb.post { if (thumb.tag == key) { thumb.setImageBitmap(fb); applyCoverAlign(thumb, fm != null && fm.isNotEmpty() && fm[0].isNotBlank()) } }
            codeView?.post { if (codeView.tag == key) applyText(codeView, titleView, fm, fallbackName) }
            durView?.post { if (durView.tag == key) applyDur(durView, fd) }
        }
    }

    // 커버 정렬 — 임베드 커버(isCover)만 좌/우/중앙. 추출 썸네일(프레임)은 항상 centerCrop.
    //   left=오른쪽 크롭(왼쪽 보존)·right=왼쪽 크롭, 세로는 중앙. MATRIX 로 crop-align(ImageView 기본 미지원).
    private fun applyCoverAlign(iv: ImageView, isCover: Boolean) {
        val align = if (isCover) LibPrefs.coverAlign(iv.context) else "center"
        if (align == "center") { iv.scaleType = ImageView.ScaleType.CENTER_CROP; return }
        iv.post {
            val d = iv.drawable
            val vw = iv.width.toFloat(); val vh = iv.height.toFloat()
            if (d == null || vw <= 0 || vh <= 0) { iv.scaleType = ImageView.ScaleType.CENTER_CROP; return@post }
            val bw = d.intrinsicWidth.toFloat(); val bh = d.intrinsicHeight.toFloat()
            if (bw <= 0 || bh <= 0) { iv.scaleType = ImageView.ScaleType.CENTER_CROP; return@post }
            val scale = maxOf(vw / bw, vh / bh)
            val sw = bw * scale; val sh = bh * scale
            val dx = when (align) { "left" -> 0f; "right" -> vw - sw; else -> (vw - sw) / 2 }
            val dy = (vh - sh) / 2
            val m = android.graphics.Matrix(); m.setScale(scale, scale); m.postTranslate(dx, dy)
            iv.scaleType = ImageView.ScaleType.MATRIX; iv.imageMatrix = m
        }
    }

    private fun applyText(codeView: TextView?, titleView: TextView?, meta: Array<String>?, fallback: String) {
        val hasEmbed = meta != null && !(meta[0].isEmpty() && meta[1].isEmpty())
        if (hasEmbed) {
            codeView?.text = meta!![0]
            if (meta[1].isNotEmpty()) {
                titleView?.text = meta[1]
                titleView?.visibility = View.VISIBLE
            } else {
                titleView?.text = ""
                titleView?.visibility = View.GONE
            }
        } else {
            // 미확인이거나 임베드 없음 → 파일명
            codeView?.text = fallback
            titleView?.text = ""
            titleView?.visibility = View.GONE
        }
    }

    // "MIDD-850   유부녀의 비밀" → ["MIDD-850","유부녀의 비밀"]. 제목에 품번 중복되면 제거.
    private fun parseTitle(raw: String): Array<String> {
        val t = raw.trim()
        if (t.isEmpty()) return arrayOf("", "")
        val parts = t.split(Regex("\\s+"), limit = 2)
        val code = parts[0]
        var title = if (parts.size == 2) parts[1].trim() else ""
        while (title.isNotEmpty() && title.startsWith(code)) {
            title = title.removePrefix(code).trimStart()
        }
        return arrayOf(code, title)
    }

    private fun applyDur(durView: TextView, ms: Long?) {
        if (ms != null && ms > 0) {
            durView.visibility = View.VISIBLE
            durView.text = MediaLibrary.fmtDur(ms)
        } else {
            durView.visibility = View.GONE
        }
    }

    private fun scaledFrame(mmr: MediaMetadataRetriever, posUs: Long = 3_000_000L): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 27)
            mmr.getScaledFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 240)
        else
            mmr.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Throwable) {
        null
    }

    // ─── 디스크 캐시 (cacheDir/thumbs/<md5>.jpg + .txt) ───
    private fun cacheDir(ctx: Context): File {
        val d = File(ctx.cacheDir, "thumbs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun hashKey(uri: String): String =
        MessageDigest.getInstance("MD5").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun loadDiskBmp(ctx: Context, hk: String): Bitmap? {
        val f = File(cacheDir(ctx), "$hk.jpg")
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
    }

    private fun saveDiskBmp(ctx: Context, hk: String, bmp: Bitmap) {
        try {
            FileOutputStream(File(cacheDir(ctx), "$hk.jpg")).use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
        } catch (_: Throwable) {
        }
    }

    // 7필드(\n): uri / code / title / dur(미상 "?") / 배우 / 스튜디오 / 시리즈
    private fun loadDiskMeta(ctx: Context, hk: String): Triple<String, String, Long?>? {
        val c = readCachedFile(File(cacheDir(ctx), "$hk.txt")) ?: return null
        return Triple(c.code, c.title, c.dur)
    }

    private fun saveDiskMeta(ctx: Context, hk: String, uri: String, meta: Array<String>, dur: Long?, artist: String, studio: String, series: String) {
        try {
            File(cacheDir(ctx), "$hk.txt").writeText(
                listOf(uri, meta[0], meta[1], (dur?.toString() ?: "?"), artist, studio, series).joinToString("\n")
            )
        } catch (_: Throwable) {
        }
    }

    private fun readCachedFile(f: File): CachedVideo? {
        if (!f.exists()) return null
        return try {
            val p = f.readText().split('\n')
            if (p.size < 7) null
            else CachedVideo(p[0], p[1], p[2], if (p[3] == "?") null else p[3].toLongOrNull(), p[4], p[5], p[6])
        } catch (_: Throwable) {
            null
        }
    }

    // 배우/스튜디오/시리즈 뷰용 — 디스크에 캐시된(=브라우징된) 영상 메타 전부.
    fun readAllCachedMeta(ctx: Context): List<CachedVideo> {
        val out = ArrayList<CachedVideo>()
        File(ctx.cacheDir, "thumbs").listFiles { f -> f.name.endsWith(".txt") }
            ?.forEach { readCachedFile(it)?.let(out::add) }
        return out
    }

    private fun decodeSampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim > 0 && maxDim / sample > target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
