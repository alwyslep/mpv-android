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
import java.util.concurrent.Executors

// P2: 비디오 카드 썸네일 로더.
//   우선순위 ① 컨테이너에 임베드된 커버 이미지(JAV 포스터, reMux+metaTag 가 covr/attached_pic 으로 박음)
//           ② MediaStore 시스템 썸네일(비디오 프레임)
//           ③ 구버전 fallback(ThumbnailUtils)
//   RecyclerView 재사용 경합은 ImageView.tag 로 막는다.
object ThumbLoader {
    private val exec = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(img: ImageView, uri: Uri, path: String) {
        val key = uri.toString()
        img.tag = key
        cache.get(key)?.let { img.setImageBitmap(it); return }
        img.setImageBitmap(null)
        val ctx = img.context.applicationContext
        exec.execute {
            val bmp = decode(ctx, uri, path)
            if (bmp != null) cache.put(key, bmp)
            img.post { if (img.tag == key) img.setImageBitmap(bmp) }
        }
    }

    private fun decode(ctx: Context, uri: Uri, path: String): Bitmap? {
        // ① 임베드 커버 이미지 우선
        embeddedCover(ctx, uri)?.let { return it }
        // ② MediaStore 시스템 썸네일(비디오 프레임)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                return ctx.contentResolver.loadThumbnail(uri, Size(360, 240), null)
        } catch (_: Throwable) {
        }
        // ③ 구버전 fallback
        return try {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
        } catch (_: Throwable) {
            null
        }
    }

    private fun embeddedCover(ctx: Context, uri: Uri): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            val bytes = mmr.embeddedPicture ?: return null
            decodeSampled(bytes, 600)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Throwable) {
            }
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
