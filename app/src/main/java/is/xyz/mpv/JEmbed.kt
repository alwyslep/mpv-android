package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import org.mp4parser.IsoFile
import org.mp4parser.boxes.apple.AppleAlbumBox
import org.mp4parser.boxes.apple.AppleArtistBox
import org.mp4parser.boxes.apple.AppleCommentBox
import org.mp4parser.boxes.apple.AppleCoverBox
import org.mp4parser.boxes.apple.AppleItemListBox
import org.mp4parser.boxes.apple.AppleNameBox
import org.mp4parser.boxes.iso14496.part12.HandlerBox
import org.mp4parser.boxes.iso14496.part12.MetaBox
import org.mp4parser.boxes.iso14496.part12.UserDataBox
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * jembed PoC v2 — mp4parser(비디오 mp4 box 파싱/쓰기)로 ilst atom 주입.
 * jaudiotagger(audio m4a 전용 → CannotReadException) 대체. jav_dl.embed_metadata 와
 * 동일 iTunes atom(©nam/©ART/©alb/©cmt/covr) → ThumbLoader 가 그대로 읽는다.
 *
 * PoC 한계: ① 내부저장소(MediaStore DATA) 파일만(SAF 다음) ② mp4parser writeContainer 는
 * 전체 재작성(mdat 복사) — 대용량 시 느림, 동작 확인 후 in-place 최적화 판단. ③ aART/©gen 은
 * 클래스 확정 후 추가(현재 name/artist/album/comment/cover).
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
        val title = rec.optString("title")
        val isoFile = IsoFile(path)
        try {
            val moov = isoFile.movieBox ?: return "moov 없음 — mp4 아님"
            val udta = moov.boxes.filterIsInstance<UserDataBox>().firstOrNull()
                ?: UserDataBox().also { moov.addBox(it) }
            val meta = udta.boxes.filterIsInstance<MetaBox>().firstOrNull()
                ?: MetaBox().also {
                    it.addBox(HandlerBox().apply { handlerType = "mdir" })
                    udta.addBox(it)
                }
            val ilst = meta.boxes.filterIsInstance<AppleItemListBox>().firstOrNull()
                ?: AppleItemListBox().also { meta.addBox(it) }

            ilst.addBox(AppleNameBox().apply { value = if (title.isNotBlank()) "$code $title" else code })
            joinField(rec, "actress").takeIf { it.isNotBlank() }
                ?.let { ilst.addBox(AppleArtistBox().apply { value = it }) }
            (rec.optString("series").ifBlank { rec.optString("studio") }).takeIf { it.isNotBlank() }
                ?.let { ilst.addBox(AppleAlbumBox().apply { value = it }) }
            ilst.addBox(AppleCommentBox().apply { value = code })

            var coverMsg = ""
            val coverUrl = rec.optString("coverUrl")
            if (coverUrl.isNotBlank()) {
                try {
                    val bytes = URL(coverUrl).openStream().use { it.readBytes() }
                    if (bytes.size > 100) {
                        val cb = AppleCoverBox()
                        if (bytes[0] == 0x89.toByte()) cb.setPng(bytes) else cb.setJpg(bytes)
                        ilst.addBox(cb)
                        coverMsg = " +커버(${bytes.size / 1024}KB)"
                    }
                } catch (e: Throwable) { coverMsg = " (커버실패:${e.message})" }
            }

            val tmp = File("$path.embed.tmp")
            FileOutputStream(tmp).use { fos -> isoFile.writeContainer(fos.channel) }
            isoFile.close()
            val orig = File(path)
            if (!tmp.renameTo(orig)) {
                tmp.copyTo(orig, overwrite = true)
                tmp.delete()
            }
            ThumbLoader.clearCache(app)
            return "✓ 임베드 성공: $code$coverMsg"
        } finally {
            try { isoFile.close() } catch (_: Throwable) {}
        }
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
