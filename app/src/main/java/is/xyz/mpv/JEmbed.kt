package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * jembed PoC v3 — 외부 라이브러리 없이 직접 mp4 atom patch.
 * jav_dl.embed_metadata(mutagen)과 동일한 iTunes ilst atom(©nam/©ART/©alb/©cmt/covr)을
 * moov>udta>meta>ilst 에 바이트로 직접 작성 → ThumbLoader(MMR)가 그대로 읽는다.
 *
 * PoC1 한계 — 가장 단순한 케이스만:
 *  ① 내부저장소(MediaStore DATA) 파일만 (SAF 다음 단계)
 *  ② moov 가 **파일 끝(mdat 뒤)** 일 때만. moov 가 앞(faststart)이면 stco 보정 필요 → 폴백 메시지.
 *  ③ moov 에 udta 가 이미 없을 때만(임베드 신규). 32bit box size 가정.
 * 검증되면 faststart(stco/co64 보정)·SAF·배치로 확장.
 */
object JEmbed {
    private val LATIN1: Charset = Charsets.ISO_8859_1
    private fun ui(cb: () -> Unit) = Handler(Looper.getMainLooper()).post(cb)

    fun embed(ctx: Context, uri: Uri, code: String, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        if (code.isBlank()) { done(false, "품번 없음 — 파일명/임베드에서 추출 실패"); return }
        val path = pathFromUri(app, uri)
        if (path == null) { done(false, "내부저장소 파일만 (SAF/USB 는 PoC 미지원)"); return }
        Library.fetch(app, code) { rec ->
            if (rec == null) { ui { done(false, "hub 메타 없음: $code") }; return@fetch }
            Thread {
                val msg = try { embedAtom(app, path, code, rec) }
                          catch (e: Throwable) { "실패: ${e.javaClass.simpleName}: ${e.message}" }
                ui { done(msg.startsWith("✓"), msg) }
            }.start()
        }
    }

    // ─── 박스 바이트 빌더 ───
    private fun box(type: String, payload: ByteArray): ByteArray {
        val t = type.toByteArray(LATIN1)            // '©'=0xA9 (ISO-8859-1)
        require(t.size == 4) { "box type 4바이트 아님: $type" }
        return ByteBuffer.allocate(8 + payload.size)
            .putInt(8 + payload.size).put(t).put(payload).array()
    }

    // data atom: [typeCode(4)][locale(4)=0][value]  — 1=UTF8, 13=JPEG, 14=PNG
    private fun dataAtom(typeCode: Int, value: ByteArray): ByteArray =
        box("data", ByteBuffer.allocate(8 + value.size).putInt(typeCode).putInt(0).put(value).array())

    private fun textItem(type: String, text: String): ByteArray =
        box(type, dataAtom(1, text.toByteArray(Charsets.UTF_8)))

    private fun coverItem(bytes: ByteArray, png: Boolean): ByteArray =
        box("covr", dataAtom(if (png) 14 else 13, bytes))

    private fun hdlrMeta(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(byteArrayOf(0, 0, 0, 0))                 // version+flags
        p.write(byteArrayOf(0, 0, 0, 0))                 // pre_defined
        p.write("mdir".toByteArray(LATIN1))              // handler_type
        p.write("appl".toByteArray(LATIN1))              // reserved[0] (iTunes 관례)
        p.write(ByteArray(8))                            // reserved[1..2]
        p.write(0)                                       // name "" + null
        return box("hdlr", p.toByteArray())
    }

    private fun buildUdta(code: String, rec: JSONObject): Pair<ByteArray, String> {
        val items = ByteArrayOutputStream()
        val title = rec.optString("title")
        items.write(textItem("©nam", if (title.isNotBlank()) "$code $title" else code))
        joinField(rec, "actress").takeIf { it.isNotBlank() }?.let { items.write(textItem("©ART", it)) }
        (rec.optString("series").ifBlank { rec.optString("studio") }).takeIf { it.isNotBlank() }
            ?.let { items.write(textItem("©alb", it)) }
        joinField(rec, "genres").takeIf { it.isNotBlank() }?.let { items.write(textItem("©gen", it)) }
        items.write(textItem("©cmt", code))

        var coverMsg = ""
        val coverUrl = rec.optString("coverUrl")
        if (coverUrl.isNotBlank()) {
            try {
                val bytes = URL(coverUrl).openStream().use { it.readBytes() }
                if (bytes.size > 100) {
                    items.write(coverItem(bytes, png = bytes[0] == 0x89.toByte()))
                    coverMsg = " +커버(${bytes.size / 1024}KB)"
                }
            } catch (e: Throwable) { coverMsg = " (커버실패:${e.message})" }
        }
        val ilst = box("ilst", items.toByteArray())
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + hdlrMeta() + ilst)  // meta=FullBox
        return box("udta", meta) to coverMsg
    }

    // ─── 박스 스캔 ───
    private fun readType(raf: RandomAccessFile): String {
        val b = ByteArray(4); raf.readFully(b); return String(b, LATIN1)
    }

    private fun embedAtom(app: Context, path: String, code: String, rec: JSONObject): String {
        var coverMsg = ""
        RandomAccessFile(path, "rw").use { raf ->
            val len = raf.length()
            var off = 0L
            var moovOff = -1L; var moovSize = 0L; var moov64 = false
            while (off + 8 <= len) {
                raf.seek(off)
                val sz32 = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = readType(raf)
                var bs = sz32; var is64 = false
                when (sz32) {
                    1L -> { bs = raf.readLong(); is64 = true }
                    0L -> bs = len - off
                }
                if (type == "moov") { moovOff = off; moovSize = bs; moov64 = is64 }
                if (bs <= 0) break
                off += bs
            }
            if (moovOff < 0) return "moov 없음 — mp4 아님?"
            if (moov64) return "moov 64bit size — PoC 미지원(드묾)"
            if (moovOff + moovSize < len) return "faststart mp4(moov 앞) — PoC 다음 단계(stco 보정 필요)"
            if (scanChild(raf, moovOff, moovSize, "udta")) return "이미 메타(udta) 존재 — PoC 는 신규만"

            val (udta, cm) = buildUdta(code, rec)
            coverMsg = cm

            // 기존 moov 전체 읽기 → 끝에 udta 추가 → size 갱신 → 그 자리에 다시 쓰기
            raf.seek(moovOff)
            val moovBytes = ByteArray(moovSize.toInt())
            raf.readFully(moovBytes)
            val newSize = moovSize.toInt() + udta.size
            val out = ByteBuffer.allocate(newSize).put(moovBytes).put(udta).array()
            ByteBuffer.wrap(out).putInt(newSize)        // moov size 헤더 갱신
            raf.seek(moovOff)
            raf.write(out)
            raf.setLength(moovOff + newSize.toLong())
        }
        ThumbLoader.clearCache(app)
        return "✓ 임베드 성공: $code$coverMsg"
    }

    private fun scanChild(raf: RandomAccessFile, parentOff: Long, parentSize: Long, target: String): Boolean {
        var off = parentOff + 8                          // 32bit moov header
        val end = parentOff + parentSize
        while (off + 8 <= end) {
            raf.seek(off)
            val sz = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = readType(raf)
            var bs = sz
            if (sz == 1L) bs = raf.readLong() else if (sz == 0L) bs = end - off
            if (type == target) return true
            if (bs <= 0) break
            off += bs
        }
        return false
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
