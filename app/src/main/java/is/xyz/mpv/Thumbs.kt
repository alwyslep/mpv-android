package `is`.xyz.mpv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// P2: 비디오 카드 메타 로더.
//   썸네일 ① 임베드 커버(covr/attached_pic) ② 없으면 비디오 프레임(MMR)
//   라벨   임베드 타이틀 "품번 공백 한글제목" → code=품번 / title=한글제목(품번 중복 제거).
//          임베드 타이틀 없으면 code=파일명, title 숨김.
//   길이   durView 주어지면 MMR 로 함께 추출(SAF 등 길이 미상).
//   RecyclerView 재사용 경합은 View.tag 로 막는다.
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

    fun clearCache() {
        bmpCache.evictAll()
        metaCache.clear()
        durCache.clear()
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
        applyText(codeView, titleView, cm, fallbackName)
        if (durView != null) applyDur(durView, cd)

        val needDur = durView != null && cd == null
        if (cb != null && cm != null && !needDur) return

        val ctx = thumb.context.applicationContext
        exec.execute {
            var bmp = cb
            var meta = cm
            var dur = cd
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                if (bmp == null) {
                    val bytes = mmr.embeddedPicture
                    if (bytes != null) bmp = decodeSampled(bytes, 600)
                }
                if (meta == null) {
                    val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                    meta = if (raw.isNotEmpty()) parseTitle(raw) else arrayOf("", "")
                }
                if (needDur && dur == null) {
                    dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                }
                if (bmp == null) bmp = scaledFrame(mmr)  // 커버 없으면 비디오 프레임
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }

            if (bmp != null) bmpCache.put(key, bmp)
            if (meta != null) metaCache[key] = meta
            if (dur != null) durCache[key] = dur

            val fb = bmp; val fm = meta; val fd = dur
            thumb.post { if (thumb.tag == key) thumb.setImageBitmap(fb) }
            codeView?.post { if (codeView.tag == key) applyText(codeView, titleView, fm, fallbackName) }
            durView?.post { if (durView.tag == key) applyDur(durView, fd) }
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

    private fun scaledFrame(mmr: MediaMetadataRetriever): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 27)
            mmr.getScaledFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 240)
        else
            mmr.getFrameAtTime(3_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Throwable) {
        null
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
