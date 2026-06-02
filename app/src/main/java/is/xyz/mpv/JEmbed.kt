package `is`.xyz.mpv

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * jembed PoC v4 — 외부 라이브러리 없이 직접 mp4 atom patch. **moov 이동 방식**.
 * jav_dl.embed_metadata(mutagen)과 동일 iTunes ilst atom(©nam/©ART/©alb/©gen/©cmt/covr)을
 * moov>udta>meta>ilst 에 직접 작성 → ThumbLoader(MMR)가 그대로 읽는다.
 *
 * 알고리즘(python 으로 ffprobe 검증 완료):
 *  1. 기존 moov 읽기 → udta 추가한 새 moov 생성(size 갱신)
 *  2. 기존 moov 자리의 type 4바이트를 'free' 로 덮어씀(무력화, 내용은 free payload 로 무시됨)
 *  3. 새 moov 를 파일 끝에 append
 *  → mdat(수 GB) 불변 → stco/co64 chunk offset 불변(보정 불필요). faststart/moov끝 무관 범용.
 *
 * PoC 한계: ① 내부저장소(MediaStore DATA) File "rw" 만(SAF 다음 단계) ② TS 파일은 remux 선행
 * 필요(별도) ③ 32bit moov size·udta 신규(재임베드 아님) 가정.
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
                val msg = try {
                    var pre = ""
                    if (isTs(path)) {
                        // TS(.mp4 확장자) → mp4 remux 선행 (Android MediaExtractor/MediaMuxer, 재인코딩 없음)
                        val tmp = "$path.remux.mp4"
                        val r = remuxToMp4(path, tmp)
                        if (!r.startsWith("✓")) { runCatching { File(tmp).delete() }; throw IOException(r) }
                        if (!File(path).delete()) throw IOException("원본 TS 삭제 실패")
                        if (!File(tmp).renameTo(File(path))) { File(tmp).copyTo(File(path), true); File(tmp).delete() }
                        pre = "TS→mp4 remux + "
                    }
                    pre + embedAtom(app, path, code, rec)
                } catch (e: Throwable) { "실패: ${e.javaClass.simpleName}: ${e.message}" }
                ui { done(msg.contains("✓"), msg) }
            }.start()
        }
    }

    // ─── 박스 바이트 빌더 ───
    private fun box(type: String, payload: ByteArray): ByteArray {
        val t = type.toByteArray(LATIN1)
        require(t.size == 4) { "box type 4바이트 아님: $type" }
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(t).put(payload).array()
    }
    private fun dataAtom(typeCode: Int, value: ByteArray): ByteArray =
        box("data", ByteBuffer.allocate(8 + value.size).putInt(typeCode).putInt(0).put(value).array())
    private fun textItem(type: String, text: String): ByteArray =
        box(type, dataAtom(1, text.toByteArray(Charsets.UTF_8)))
    private fun coverItem(bytes: ByteArray, png: Boolean): ByteArray =
        box("covr", dataAtom(if (png) 14 else 13, bytes))
    private fun hdlrMeta(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(byteArrayOf(0, 0, 0, 0)); p.write(byteArrayOf(0, 0, 0, 0))
        p.write("mdir".toByteArray(LATIN1)); p.write("appl".toByteArray(LATIN1))
        p.write(ByteArray(8)); p.write(0)
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
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + hdlrMeta() + box("ilst", items.toByteArray()))
        return box("udta", meta) to coverMsg
    }

    private fun readType(raf: RandomAccessFile): String {
        val b = ByteArray(4); raf.readFully(b); return String(b, LATIN1)
    }

    private fun embedAtom(app: Context, path: String, code: String, rec: JSONObject): String {
        var coverMsg = ""
        RandomAccessFile(path, "rw").use { raf ->
            val len = raf.length()
            var off = 0L; var moovOff = -1L; var moovSize = 0L; var moov64 = false
            while (off + 8 <= len) {
                raf.seek(off)
                val sz32 = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = readType(raf)
                var bs = sz32; var is64 = false
                if (sz32 == 1L) { bs = raf.readLong(); is64 = true } else if (sz32 == 0L) bs = len - off
                if (type == "moov") { moovOff = off; moovSize = bs; moov64 = is64 }
                if (bs <= 0) break
                off += bs
            }
            if (moovOff < 0) return "moov 없음 — mp4 아님?(TS 면 remux 필요)"
            if (moov64) return "moov 64bit size — PoC 미지원(드묾)"
            if (moovSize > 64 * 1024 * 1024) return "moov 비정상(>64MB)"
            if (scanChild(raf, moovOff, moovSize, "udta")) return "이미 메타(udta) 존재 — PoC 는 신규만"

            raf.seek(moovOff)
            val moovBytes = ByteArray(moovSize.toInt())
            raf.readFully(moovBytes)
            val (udta, cm) = buildUdta(code, rec); coverMsg = cm

            // 새 moov = 기존 moov + udta(payload 끝), size 갱신
            val newSize = moovSize.toInt() + udta.size
            val newMoov = ByteBuffer.allocate(newSize).put(moovBytes).put(udta).array()
            ByteBuffer.wrap(newMoov).putInt(newSize)
            // 기존 moov 자리 → 'free' 무력화 (type 4바이트만; mdat·stco 불변)
            raf.seek(moovOff + 4); raf.write("free".toByteArray(LATIN1))
            // 새 moov 파일 끝에 append
            raf.seek(raf.length()); raf.write(newMoov)
        }
        ThumbLoader.clearCache(app)
        return "✓ 임베드 성공: $code$coverMsg"
    }

    private fun scanChild(raf: RandomAccessFile, parentOff: Long, parentSize: Long, target: String): Boolean {
        var off = parentOff + 8
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

    // 첫바이트 0x47 = MPEG-TS sync (확장자만 .mp4 인 레거시 TS 감지)
    private fun isTs(path: String): Boolean =
        try { RandomAccessFile(path, "r").use { it.read() == 0x47 } } catch (_: Throwable) { false }

    // TS → mp4 remux. Android MediaExtractor/MediaMuxer, 샘플 복사(재인코딩 없음=빠름).
    private fun remuxToMp4(srcPath: String, dstPath: String): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(srcPath)
            val mux = MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            var maxInput = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (!(mime.startsWith("video/") || mime.startsWith("audio/"))) continue
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    maxInput = maxOf(maxInput, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                map[i] = mux.addTrack(fmt)
                ex.selectTrack(i)
            }
            if (map.isEmpty()) { mux.release(); return "remux 실패: 트랙 없음" }
            mux.start()
            val buf = ByteBuffer.allocate(maxInput)
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0) break
                    val tr = map[ex.sampleTrackIndex]
                    val t = ex.sampleTime
                    if (tr != null && t >= 0) {
                        info.offset = 0
                        info.size = sz
                        info.presentationTimeUs = t
                        info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        mux.writeSampleData(tr, buf, info)
                    }
                    ex.advance()
                }
                mux.stop()
            } finally {
                mux.release()
            }
            return "✓remux"
        } catch (e: Throwable) {
            return "remux 실패: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            ex.release()
        }
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
