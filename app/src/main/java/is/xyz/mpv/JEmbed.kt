package `is`.xyz.mpv

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * jembed PoC v5 — TS→mp4 remux + 직접 atom patch(moov 이동). 내부저장소 + 외부저장소(SAF) 공용.
 *
 * 흐름: 영상 선택 → 첫바이트 감지
 *   ├ 0x47(TS) → MediaExtractor/MediaMuxer remux(재인코딩 없음) → atom patch embed
 *   └ ftyp(mp4) → atom patch embed
 * 메타=hub(Library.fetch /library?code=), atom=jav_dl mutagen 동일(©nam/©ART/©alb/©gen/©cmt/covr).
 *
 * random read/write 추상화 [Rw]: 내부=RandomAccessFile, 외부 SAF=Os.pread/pwrite(fd).
 * SAF remux(외부 TS)는 다음 단계 — 이번 외부는 ftyp mp4 embed 만(TS 면 폴백).
 */
object JEmbed {
    private val LATIN1: Charset = Charsets.ISO_8859_1
    private fun ui(cb: () -> Unit) = Handler(Looper.getMainLooper()).post(cb)

    fun embed(ctx: Context, uri: Uri, code: String, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        if (code.isBlank()) { done(false, "품번 없음 — 파일명/임베드에서 추출 실패"); return }
        Library.fetch(app, code) { rec ->
            if (rec == null) { ui { done(false, "hub 메타 없음: $code") }; return@fetch }
            Thread {
                val msg = try {
                    val m = processOne(app, uri, code, rec)
                    if (m.contains("✓")) ThumbLoader.clearCache(app)
                    m
                } catch (e: Throwable) { "실패: ${e.javaClass.simpleName}: ${e.message}" }
                ui { done(msg.contains("✓"), msg) }
            }.start()
        }
    }

    private fun processOne(ctx: Context, uri: Uri, code: String, rec: JSONObject): String {
        // ── 내부저장소(File path, 쓰기 가능) ──
        val path = if (uri.scheme == "file") uri.path else pathFromMediaStore(ctx, uri)
        if (path != null && File(path).canWrite()) {
            var pre = ""
            if (isTs(path)) {
                val tmp = "$path.remux.mp4"
                val r = remuxToMp4(path, tmp)
                if (!r.startsWith("✓")) { runCatching { File(tmp).delete() }; return r }
                if (!File(path).delete()) return "원본 TS 삭제 실패"
                if (!File(tmp).renameTo(File(path))) { File(tmp).copyTo(File(path), true); File(tmp).delete() }
                pre = "TS→mp4 remux + "
            }
            RandomAccessFile(path, "rw").use { return pre + embedAtomRw(RafRw(it), code, rec) }
        }
        // ── 외부저장소(SAF content uri) ──
        val ts = ctx.contentResolver.openFileDescriptor(uri, "r")?.use {
            val b = ByteArray(1); Os.read(it.fileDescriptor, b, 0, 1); b[0] == 0x47.toByte()
        } ?: return "SAF fd 열기 실패(권한?)"
        if (ts) {
            if (Build.VERSION.SDK_INT < 26) return "외부 TS remux 는 Android 8+ 필요"
            return remuxSaf(ctx, uri, code, rec)
        }
        ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { return embedAtomRw(OsRw(it.fileDescriptor), code, rec) }
        return "SAF fd 쓰기 열기 실패(권한?)"
    }

    // 외부 TS → mp4 remux (SAF): 같은 폴더에 새 mp4 문서 생성→remux(fd)→원본 삭제→원본명 rename→embed.
    @RequiresApi(26)
    private fun remuxSaf(ctx: Context, uri: Uri, code: String, rec: JSONObject): String {
        val resolver = ctx.contentResolver
        val docId = DocumentsContract.getDocumentId(uri)
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val treeUri = DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
        val origName = docId.substringAfterLast('/').ifBlank { "video.mp4" }
        val parentId = if (docId.contains('/')) docId.substringBeforeLast('/') else treeId
        val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val tmpName = origName.substringBeforeLast('.') + "__remux.mp4"
        val newDoc = DocumentsContract.createDocument(resolver, parentDoc, "video/mp4", tmpName)
            ?: return "SAF 새 문서 생성 실패(부모 권한?)"
        var rmsg = "remux fd 실패"
        try {
            resolver.openFileDescriptor(uri, "r")!!.use { inP ->
                resolver.openFileDescriptor(newDoc, "rw")!!.use { outP ->
                    rmsg = remuxFromFd(inP.fileDescriptor, outP.fileDescriptor)
                }
            }
        } catch (e: Throwable) { rmsg = "remux 예외: ${e.message}" }
        if (!rmsg.startsWith("✓")) { runCatching { DocumentsContract.deleteDocument(resolver, newDoc) }; return "remux 실패: $rmsg" }
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        val finalDoc = runCatching { DocumentsContract.renameDocument(resolver, newDoc, origName) }.getOrNull() ?: newDoc
        resolver.openFileDescriptor(finalDoc, "rw")?.use {
            return "TS→mp4 remux(SAF) + " + embedAtomRw(OsRw(it.fileDescriptor), code, rec)
        }
        return "TS→mp4 remux(SAF) 성공했으나 embed fd 실패"
    }

    // ── random read/write 추상 ──
    private interface Rw {
        fun size(): Long
        fun readAt(pos: Long, b: ByteArray, len: Int)
        fun writeAt(pos: Long, b: ByteArray)
    }
    private class RafRw(private val raf: RandomAccessFile) : Rw {
        override fun size() = raf.length()
        override fun readAt(pos: Long, b: ByteArray, len: Int) { raf.seek(pos); raf.readFully(b, 0, len) }
        override fun writeAt(pos: Long, b: ByteArray) { raf.seek(pos); raf.write(b) }
    }
    private class OsRw(private val fd: FileDescriptor) : Rw {
        override fun size(): Long = Os.fstat(fd).st_size
        override fun readAt(pos: Long, b: ByteArray, len: Int) {
            var d = 0
            while (d < len) { val n = Os.pread(fd, b, d, len - d, pos + d); if (n <= 0) throw IOException("pread EOF@$pos"); d += n }
        }
        override fun writeAt(pos: Long, b: ByteArray) {
            var d = 0
            while (d < b.size) { val n = Os.pwrite(fd, b, d, b.size - d, pos + d); if (n <= 0) throw IOException("pwrite 0"); d += n }
        }
    }

    // ── box 헤더 ──
    private data class BoxHdr(val size: Long, val type: String, val is64: Boolean)
    private fun readBox(rw: Rw, off: Long): BoxHdr {
        val h = ByteArray(8); rw.readAt(off, h, 8)
        val raw = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xFFFFFFFFL
        val type = String(h, 4, 4, LATIN1)
        if (raw == 1L) { val h2 = ByteArray(8); rw.readAt(off + 8, h2, 8); return BoxHdr(ByteBuffer.wrap(h2).long, type, true) }
        return BoxHdr(raw, type, false)
    }
    private fun hasChild(rw: Rw, parentOff: Long, parentSize: Long, target: String): Boolean {
        var off = parentOff + 8
        val end = parentOff + parentSize
        while (off + 8 <= end) {
            val h = readBox(rw, off)
            val bs = if (h.size == 0L) end - off else h.size
            if (h.type == target) return true
            if (bs <= 0) break
            off += bs
        }
        return false
    }

    // ── atom patch (moov 이동) ──
    private fun embedAtomRw(rw: Rw, code: String, rec: JSONObject): String {
        val len = rw.size()
        var off = 0L; var moovOff = -1L; var moovSize = 0L
        while (off + 8 <= len) {
            val h = readBox(rw, off)
            val bs = if (h.size == 0L) len - off else h.size
            if (h.type == "moov") { if (h.is64) return "moov 64bit — 미지원"; moovOff = off; moovSize = bs }
            if (bs <= 0) break
            off += bs
        }
        if (moovOff < 0) return "moov 없음 — mp4 아님?"
        if (moovSize > 64L * 1024 * 1024) return "moov 비정상(>64MB)"
        // 주의: MediaMuxer 가 remux 시 자체 udta(SDLN/smrd/smta) 를 만들므로 udta 유무로 skip 하면
        //   우리 메타가 안 들어간다(오판). 항상 우리 udta>meta>ilst 를 추가 — ffprobe/MMR 는 끝 moov 것 읽음.
        //   (재임베드 시 udta 중복 누적은 실사용 '임베드없음 선별 UI' 로 방지 예정.)

        val moovBytes = ByteArray(moovSize.toInt())
        rw.readAt(moovOff, moovBytes, moovSize.toInt())
        val (udta, cm) = buildUdta(code, rec)
        val newSize = moovSize.toInt() + udta.size
        val newMoov = ByteBuffer.allocate(newSize).put(moovBytes).put(udta).array()
        ByteBuffer.wrap(newMoov).putInt(newSize)
        rw.writeAt(moovOff + 4, "free".toByteArray(LATIN1))  // 기존 moov 무력화
        rw.writeAt(rw.size(), newMoov)                        // 새 moov append (mdat 불변)
        return "✓ 임베드 성공: $code$cm"
    }

    // ── ilst atom 빌더 (jav_dl mutagen 동일) ──
    private fun box(type: String, payload: ByteArray): ByteArray {
        val t = type.toByteArray(LATIN1)
        require(t.size == 4) { "box type 4바이트 아님: $type" }
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(t).put(payload).array()
    }
    private fun dataAtom(tc: Int, v: ByteArray): ByteArray =
        box("data", ByteBuffer.allocate(8 + v.size).putInt(tc).putInt(0).put(v).array())
    private fun textItem(type: String, s: String) = box(type, dataAtom(1, s.toByteArray(Charsets.UTF_8)))
    private fun coverItem(b: ByteArray, png: Boolean) = box("covr", dataAtom(if (png) 14 else 13, b))
    private fun hdlrMeta(): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(byteArrayOf(0, 0, 0, 0)); p.write(byteArrayOf(0, 0, 0, 0))
        p.write("mdir".toByteArray(LATIN1)); p.write("appl".toByteArray(LATIN1)); p.write(ByteArray(8)); p.write(0)
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
                if (bytes.size > 100) { items.write(coverItem(bytes, png = bytes[0] == 0x89.toByte())); coverMsg = " +커버(${bytes.size / 1024}KB)" }
            } catch (e: Throwable) { coverMsg = " (커버실패:${e.message})" }
        }
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + hdlrMeta() + box("ilst", items.toByteArray()))
        return box("udta", meta) to coverMsg
    }
    private fun joinField(rec: JSONObject, key: String): String {
        rec.optJSONArray(key)?.let { a -> return (0 until a.length()).joinToString(", ") { a.optString(it) } }
        return rec.optString(key)
    }

    // ── TS 감지 / remux (내부저장소 path) ──
    private fun isTs(path: String): Boolean =
        try { RandomAccessFile(path, "r").use { it.read() == 0x47 } } catch (_: Throwable) { false }

    // remux 코어: MediaExtractor → MediaMuxer 샘플 복사(재인코딩 없음). release 는 caller.
    private fun remuxFrom(ex: MediaExtractor, mux: MediaMuxer): String {
        val map = HashMap<Int, Int>()
        var maxInput = 1 shl 20
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (!(mime.startsWith("video/") || mime.startsWith("audio/"))) continue
            if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            map[i] = mux.addTrack(fmt); ex.selectTrack(i)
        }
        if (map.isEmpty()) return "remux 실패: 트랙 없음"
        mux.start()
        val buf = ByteBuffer.allocate(maxInput); val info = MediaCodec.BufferInfo()
        while (true) {
            val sz = ex.readSampleData(buf, 0); if (sz < 0) break
            val tr = map[ex.sampleTrackIndex]; val t = ex.sampleTime
            if (tr != null && t >= 0) {
                info.offset = 0; info.size = sz; info.presentationTimeUs = t
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(tr, buf, info)
            }
            ex.advance()
        }
        mux.stop()
        return "✓remux"
    }

    private fun remuxToMp4(srcPath: String, dstPath: String): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(srcPath)
            val mux = MediaMuxer(dstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try { return remuxFrom(ex, mux) } finally { mux.release() }
        } catch (e: Throwable) { return "remux 실패: ${e.javaClass.simpleName}: ${e.message}" }
        finally { ex.release() }
    }

    @RequiresApi(26)
    private fun remuxFromFd(inFd: FileDescriptor, outFd: FileDescriptor): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(inFd)
            val mux = MediaMuxer(outFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try { return remuxFrom(ex, mux) } finally { mux.release() }
        } catch (e: Throwable) { return "remux 실패: ${e.javaClass.simpleName}: ${e.message}" }
        finally { ex.release() }
    }

    private fun pathFromMediaStore(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Throwable) { null }
}
