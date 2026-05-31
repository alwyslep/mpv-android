package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// P2: 비디오 카드 메타 로더 — 컨테이너 임베드 정보를 우선 반영한다.
//   썸네일 우선순위 ① 임베드 커버(covr/attached_pic, reMux+metaTag) ② MediaStore 시스템 썸네일 ③ ThumbnailUtils
//   라벨        ① 임베드 타이틀 "품번 공백들 한글제목" → "품번\n한글제목" ② 없으면 파일명
//   길이        durView 가 주어지면(SAF 등 길이 미상) MMR 로 함께 추출.
//   RecyclerView 재사용 경합은 View.tag 로 막는다.
object ThumbLoader {
    private val exec = Executors.newFixedThreadPool(3)
    private val bmpCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val titleCache = ConcurrentHashMap<String, String>() // "" = 임베드 타이틀 없음
    private val durCache = ConcurrentHashMap<String, Long>()      // -1 = 확인했으나 없음

    fun load(
        thumb: ImageView,
        titleView: TextView?,
        uri: Uri,
        path: String,
        fallbackName: String,
        durView: TextView? = null
    ) {
        val key = uri.toString()
        thumb.tag = key
        titleView?.tag = key
        durView?.tag = key

        val cb = bmpCache.get(key)
        val ct = titleCache[key]
        val cd = durCache[key]
        thumb.setImageBitmap(cb)
        titleView?.text = displayTitle(ct, fallbackName)
        if (durView != null) applyDur(durView, cd)

        val needDur = durView != null && cd == null
        if (cb != null && ct != null && !needDur) return  // 캐시 충분

        val ctx = thumb.context.applicationContext
        exec.execute {
            var bmp = cb
            var title = ct
            var dur = cd
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                if (bmp == null) {
                    val bytes = mmr.embeddedPicture
                    if (bytes != null) bmp = decodeSampled(bytes, 600)
                }
                if (title == null) {
                    val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                    title = if (raw.isNotEmpty()) formatTitle(raw) else ""
                }
                if (needDur && dur == null) {
                    dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                }
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
            if (bmp == null) bmp = systemThumb(ctx, uri, path)

            if (bmp != null) bmpCache.put(key, bmp)
            if (title != null) titleCache[key] = title
            if (dur != null) durCache[key] = dur

            val fb = bmp; val ft = title; val fd = dur
            thumb.post { if (thumb.tag == key) thumb.setImageBitmap(fb) }
            titleView?.post { if (titleView.tag == key) titleView.text = displayTitle(ft, fallbackName) }
            durView?.post { if (durView.tag == key) applyDur(durView, fd) }
        }
    }

    private fun applyDur(durView: TextView, ms: Long?) {
        if (ms != null && ms > 0) {
            durView.visibility = View.VISIBLE
            durView.text = MediaLibrary.fmtDur(ms)
        } else {
            durView.visibility = View.GONE
        }
    }

    private fun displayTitle(cached: String?, fallbackName: String): String =
        if (cached.isNullOrEmpty()) fallbackName else cached

    // "MIDD-850   유부녀의 비밀" → "MIDD-850\n유부녀의 비밀"
    private fun formatTitle(raw: String): String {
        val parts = raw.split(Regex("\\s+"), limit = 2)
        return if (parts.size == 2 && parts[1].isNotBlank()) "${parts[0]}\n${parts[1]}" else raw
    }

    private fun systemThumb(ctx: Context, uri: Uri, path: String): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY)
                return ctx.contentResolver.loadThumbnail(uri, Size(360, 240), null)
        } catch (_: Throwable) {
        }
        return try {
            if (path.isNotEmpty()) {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
            } else null
        } catch (_: Throwable) {
            null
        }
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
