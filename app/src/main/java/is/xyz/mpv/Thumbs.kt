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
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// P2: 비디오 카드 메타 로더 — 컨테이너 임베드 정보를 우선 반영한다.
//   썸네일 우선순위 ① 임베드 커버(covr/attached_pic, reMux+metaTag) ② MediaStore 시스템 썸네일 ③ ThumbnailUtils
//   라벨        ① 임베드 타이틀 "품번 공백들 한글제목" → "품번\n한글제목" ② 없으면 파일명
//   RecyclerView 재사용 경합은 View.tag 로 막는다. 커버 비트맵은 LruCache, 타이틀은 작아 영구 캐시.
object ThumbLoader {
    private val exec = Executors.newFixedThreadPool(3)
    private val bmpCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // 값 "" = MMR 확인했으나 임베드 타이틀 없음(파일명 사용). null = 아직 미확인.
    private val titleCache = ConcurrentHashMap<String, String>()

    fun load(thumb: ImageView, titleView: TextView?, uri: Uri, path: String, fallbackName: String) {
        val key = uri.toString()
        thumb.tag = key
        titleView?.tag = key

        val cb = bmpCache.get(key)
        val ct = titleCache[key]
        thumb.setImageBitmap(cb)
        titleView?.text = displayTitle(ct, fallbackName)

        if (cb != null && ct != null) return  // 둘 다 캐시 → MMR 불필요

        val ctx = thumb.context.applicationContext
        exec.execute {
            var bmp = cb
            var title = ct
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
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) {}
            }
            // 임베드 커버 없으면 시스템 썸네일 fallback
            if (bmp == null) bmp = systemThumb(ctx, uri, path)

            if (bmp != null) bmpCache.put(key, bmp)
            if (title != null) titleCache[key] = title

            val fb = bmp
            val ft = title
            thumb.post { if (thumb.tag == key) thumb.setImageBitmap(fb) }
            titleView?.post { if (titleView.tag == key) titleView.text = displayTitle(ft, fallbackName) }
        }
    }

    private fun displayTitle(cached: String?, fallbackName: String): String =
        if (cached.isNullOrEmpty()) fallbackName else cached

    // "MIDD-850   유부녀의 비밀" → "MIDD-850\n유부녀의 비밀" (첫 공백run 기준 품번/제목 분리)
    private fun formatTitle(raw: String): String {
        val parts = raw.split(Regex("\\s+"), limit = 2)
        return if (parts.size == 2 && parts[1].isNotBlank()) "${parts[0]}\n${parts[1]}" else raw
    }

    private fun systemThumb(ctx: Context, uri: Uri, path: String): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                return ctx.contentResolver.loadThumbnail(uri, Size(360, 240), null)
        } catch (_: Throwable) {
        }
        return try {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
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
