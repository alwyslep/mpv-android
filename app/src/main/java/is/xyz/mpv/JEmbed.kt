package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.mp4.Mp4FieldKey
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * jembed PoC — mutagen 방식(ilst atom in-place)을 Android(jaudiotagger Android fork)로 포팅.
 * hub(queue.sqlite) 메타 + 커버를 mp4 에 주입. jav_dl.embed_metadata 와 동일 atom 매핑이라
 * ThumbLoader(©nam/©ART/aART/©alb/©gen/covr)가 그대로 읽는다.
 *
 * PoC 한계: 내부저장소(MediaStore DATA path) 파일만. SAF/USB(content uri rw)는 다음 단계.
 */
object JEmbed {
    private fun ui(cb: () -> Unit) = Handler(Looper.getMainLooper()).post(cb)

    fun embed(ctx: Context, uri: Uri, code: String, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        if (code.isBlank()) { done(false, "품번 없음 — 파일명/임베드에서 추출 실패"); return }
        val path = pathFromUri(app, uri)
        if (path == null) { done(false, "내부저장소 파일만 (SAF/USB 는 PoC 미지원)"); return }
        Library.fetch(app, code) { rec ->
            if (rec == null) { ui { done(false, "hub 메타 없음: $code") }; return@fetch }
            Thread {
                val msg = try { embedSync(app, path, code, rec) }
                          catch (e: Throwable) { "실패: ${e.javaClass.simpleName}: ${e.message}" }
                ui { done(msg.startsWith("✓"), msg) }
            }.start()
        }
    }

    private fun embedSync(app: Context, path: String, code: String, rec: JSONObject): String {
        val f = File(path)
        val af = AudioFileIO.read(f)
        val tag = af.tagOrCreateAndSetDefault as Mp4Tag
        fun set(key: Mp4FieldKey, v: String?) {
            if (!v.isNullOrBlank()) tag.setField(tag.createField(key, v))
        }
        val title = rec.optString("title")
        set(Mp4FieldKey.TITLE, if (title.isNotBlank()) "$code $title" else code)   // ©nam
        set(Mp4FieldKey.ARTIST, joinField(rec, "actress"))                          // ©ART
        set(Mp4FieldKey.ALBUM_ARTIST, rec.optString("studio"))                      // aART
        val series = rec.optString("series")
        set(Mp4FieldKey.ALBUM, if (series.isNotBlank()) series else rec.optString("studio"))  // ©alb
        set(Mp4FieldKey.GENRE_CUSTOM, joinField(rec, "genres"))                     // ©gen
        set(Mp4FieldKey.COMMENT, code)                                              // ©cmt

        var coverMsg = ""
        val coverUrl = rec.optString("coverUrl")
        if (coverUrl.isNotBlank()) {
            try {
                val bytes = URL(coverUrl).openStream().use { it.readBytes() }
                if (bytes.size > 100) {
                    val art = ArtworkFactory.getNew()
                    art.binaryData = bytes
                    art.mimeType = if (bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"
                    tag.deleteArtworkField()
                    tag.setField(art)                                              // covr
                    coverMsg = " +커버(${bytes.size / 1024}KB)"
                }
            } catch (e: Throwable) { coverMsg = " (커버실패:${e.message})" }
        }

        af.commit()                  // in-place save (mutagen.save 대응)
        ThumbLoader.clearCache(app)  // 재추출해 새 메타/커버 표시
        return "✓ 임베드 성공: $code$coverMsg"
    }

    private fun joinField(rec: JSONObject, key: String): String {
        rec.optJSONArray(key)?.let { a -> return (0 until a.length()).joinToString(", ") { a.optString(it) } }
        return rec.optString(key)
    }

    private fun pathFromUri(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Throwable) { null }
    }
}
