package `is`.xyz.mpv

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

// P2: MediaStore 비디오 썸네일 로더. Coil 비디오 프레임 디코딩이 코덱/content-uri 문제로
//   실패하는 경우가 많아, 시스템이 미리 만들어 캐시한 썸네일을 직접 가져온다(코덱 무관·빠름).
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
        exec.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    img.context.contentResolver.loadThumbnail(uri, Size(360, 240), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
                }
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            img.post { if (img.tag == key) img.setImageBitmap(bmp) }
        }
    }
}
