package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset

// 보강(B-71): in-place 태깅(예: jav_dl mutagen)이 faststart(앞 moov) 4GB+ 파일에 커버/메타를 넣다가
//   stco 오프셋 갱신을 4GB(0xFFFFFFFF) 초과로 실패 → mdat 은 디스크에서 이미 밀렸는데 청크 오프셋은
//   옛 값 그대로 = 모든 청크가 udta 크기만큼 어긋남 → 디코더가 moov 영역을 영상으로 오독 = 검은화면+무음.
//   mdat 은 무손상이므로 moov 의 stco→co64(+delta) 만 재작성해 무손실 복구.
//   in-place: 파일 끝에 보정 co64 moov 를 append + 앞 moov 를 free 로 무력화(mdat 안 옮김·~십수MB 쓰기).
//   delta=현재파일 직접값(결합오차 0), 보정 첫 샘플 NAL 체인 검증으로 확정(추측 배제).
//   도구 동등(PC): termux-scripts/queue/fix_moov_offsets.py (repair_inplace).
object MoovHeal {
    private val LATIN1: Charset = Charsets.ISO_8859_1
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "mvex", "moof", "traf")
    private val VALID_NAL = setOf(1, 5, 6, 7, 8, 9)   // slice/IDR/SEI/SPS/PPS/AUD
    private const val MIN_SIZE = 3_500_000_000L        // 버그는 4GB+ 만 → 그 미만은 검사 생략(비용 회피)

    // ── random read/write 추상화(JEmbed 와 동일 패턴) ──
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

    // ── mp4 box 트리(컨테이너만 재귀, leaf 는 바이트 보존) ──
    private class Box(val type: String, val payload: ByteArray?, val children: MutableList<Box>?)

    private fun u32(b: ByteArray, o: Int): Long = ByteBuffer.wrap(b, o, 4).int.toLong() and 0xFFFFFFFFL

    private fun parse(buf: ByteArray, start: Int, end: Int): MutableList<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(buf, p)
            val type = String(buf, p + 4, 4, LATIN1)
            var hdr = 8
            if (size == 1L) { size = ByteBuffer.wrap(buf, p + 8, 8).long; hdr = 16 }
            else if (size == 0L) size = (end - p).toLong()
            if (size < hdr || p + size > end) break
            val s = size.toInt()
            if (CONTAINERS.contains(type)) out.add(Box(type, null, parse(buf, p + hdr, p + s)))
            else out.add(Box(type, buf.copyOfRange(p + hdr, p + s), null))
            p += s
        }
        return out
    }

    private fun serialize(box: Box): ByteArray {
        val payload = if (box.children != null) {
            val bos = ByteArrayOutputStream()
            for (c in box.children) bos.write(serialize(c))
            bos.toByteArray()
        } else box.payload!!
        val total = 8 + payload.size   // 우리 moov 는 4GB 미만 → 32bit size 충분
        val bb = ByteBuffer.allocate(total)
        bb.putInt(total)
        bb.put(box.type.toByteArray(LATIN1))
        bb.put(payload)
        return bb.array()
    }

    // stco/co64 → co64(+delta) 재귀 변환.
    private fun convertStco(box: Box, delta: Long) {
        val ch = box.children ?: return
        for (i in ch.indices) {
            val c = ch[i]
            if (c.type == "stco" || c.type == "co64") {
                val esz = if (c.type == "stco") 4 else 8
                val pl = c.payload!!
                val count = u32(pl, 4).toInt()
                val bb = ByteBuffer.allocate(8 + count * 8)
                bb.put(pl, 0, 4)        // version/flags
                bb.putInt(count)
                var off = 8
                for (k in 0 until count) {
                    val v = if (esz == 4) u32(pl, off) else ByteBuffer.wrap(pl, off, 8).long
                    bb.putLong(v + delta)
                    off += esz
                }
                ch[i] = Box("co64", bb.array(), null)
            } else convertStco(c, delta)
        }
    }

    private fun collectOffsets(box: Box, out: ArrayList<Long>) {
        val ch = box.children ?: return
        for (c in ch) {
            if (c.type == "stco" || c.type == "co64") {
                val esz = if (c.type == "stco") 4 else 8
                val pl = c.payload!!
                val count = u32(pl, 4).toInt()
                var off = 8
                for (k in 0 until count) {
                    out.add(if (esz == 4) u32(pl, off) else ByteBuffer.wrap(pl, off, 8).long)
                    off += esz
                }
            } else collectOffsets(c, out)
        }
    }

    private class Info(val moovOff: Long, val moovSize: Long, val moovBuf: ByteArray,
                       val mdatDataStart: Long, val minOff: Long, val delta: Long)

    // 최상위 atom 순회 → moov/mdat. moov 읽어 최소 청크오프셋·delta 산출. 대상 아니면 null.
    private fun analyze(rw: Rw): Info? {
        val len = rw.size()
        var off = 0L; var moovOff = -1L; var moovSize = 0L; var mdatDataStart = -1L
        val h = ByteArray(8)
        while (off + 8 <= len) {
            rw.readAt(off, h, 8)
            var size = u32(h, 0); val type = String(h, 4, 4, LATIN1); var hdr = 8
            if (size == 1L) { val h2 = ByteArray(8); rw.readAt(off + 8, h2, 8); size = ByteBuffer.wrap(h2).long; hdr = 16 }
            else if (size == 0L) size = len - off
            if (type == "moov") { moovOff = off; moovSize = size }
            if (type == "mdat") mdatDataStart = off + hdr
            if (size < hdr) break
            off += size
        }
        if (moovOff < 0 || mdatDataStart < 0 || moovSize <= 0 || moovSize > 64L * 1024 * 1024) return null
        val moovBuf = ByteArray(moovSize.toInt()); rw.readAt(moovOff, moovBuf, moovSize.toInt())
        val root = Box("moov", null, parse(moovBuf, 8, moovSize.toInt()))
        val offs = ArrayList<Long>(); collectOffsets(root, offs)
        val minOff = offs.minOrNull() ?: return null
        return Info(moovOff, moovSize, moovBuf, mdatDataStart, minOff, mdatDataStart - minOff)
    }

    // 보정 첫 비디오 샘플이 정상 H264 NAL 체인(AUD/SPS/PPS/SEI/IDR…)인지 — delta 확정.
    private fun nalOk(rw: Rw, off: Long): Boolean {
        var pos = off; var good = 0
        val h = ByteArray(5)
        for (i in 0 until 5) {
            try { rw.readAt(pos, h, 5) } catch (_: Throwable) { break }
            val ln = u32(h, 0)
            if (ln == 0L || ln > 30_000_000L) break
            val t = h[4].toInt() and 0x1f
            if (!VALID_NAL.contains(t)) return false
            good++; pos += 4 + ln
        }
        return good >= 3
    }

    private fun brokenInfo(rw: Rw): Info? {
        if (rw.size() < MIN_SIZE) return null
        val info = analyze(rw) ?: return null
        if (!(info.moovOff < info.minOff && info.minOff < info.moovOff + info.moovSize)) return null  // 오프셋이 moov 밖=정상
        if (info.delta <= 0 || !nalOk(rw, info.minOff + info.delta)) return null                       // 검증 실패=다른 손상
        return info
    }

    private fun repairRw(rw: Rw): Boolean {
        val info = brokenInfo(rw) ?: return false
        val root = Box("moov", null, parse(info.moovBuf, 8, info.moovSize.toInt()))
        convertStco(root, info.delta)
        val newMoov = serialize(root)
        rw.writeAt(rw.size(), newMoov)                              // 끝에 보정 co64 moov append
        rw.writeAt(info.moovOff + 4, "free".toByteArray(LATIN1))   // 앞 moov 무력화(플레이어는 끝 moov 사용)
        return true
    }

    // ── 공개 API ──
    fun isBroken(ctx: Context, uri: Uri): Boolean = try {
        withRw(ctx, uri, false) { brokenInfo(it) != null } ?: false
    } catch (e: Throwable) { JavDiag.ex("moovHeal.check", e); false }

    fun repair(ctx: Context, uri: Uri): Boolean = try {
        withRw(ctx, uri, true) { repairRw(it) } ?: false
    } catch (e: Throwable) { JavDiag.ex("moovHeal.repair", e); false }

    private fun <T> withRw(ctx: Context, uri: Uri, write: Boolean, block: (Rw) -> T): T? {
        val mode = if (write) "rw" else "r"
        val path = if (uri.scheme == "file") uri.path else realPath(ctx, uri)
        if (path != null) {
            val f = File(path)
            if (if (write) f.canWrite() else f.canRead())
                RandomAccessFile(path, mode).use { return block(RafRw(it)) }
        }
        ctx.contentResolver.openFileDescriptor(uri, mode)?.use { return block(OsRw(it.fileDescriptor)) }
        return null
    }

    private fun realPath(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Throwable) { null }
}
