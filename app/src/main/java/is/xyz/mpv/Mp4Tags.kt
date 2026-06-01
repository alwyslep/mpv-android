package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// P2 (JAV 메타): mp4 ilst atom 직접 파싱 — MMR 이 못 읽는 줄거리(desc/ldes/©cmt) 추출.
//   content uri → openFileDescriptor → FileChannel(랜덤액세스, moov 가 뒤에 있어도 OK).
//   경로: moov → udta → meta(+4 full-atom flags) → ilst → desc|ldes|©cmt → data(+8) → UTF-8.
object Mp4Tags {

    // 줄거리 — ldes(synopsis)/desc(description)/©des. ⚠️©cmt(comment) 제외:
    //   jav_dl 가 comment=품번을 항상 넣어서, 실제 줄거리 없을 때 품번이 줄거리로 오용됨.
    fun description(ctx: Context, uri: Uri): String? =
        read(ctx, uri, listOf("ldes", "desc", "©des"))

    // 릴리스 날짜(보통 연도) — ilst ©day. MMR METADATA_KEY_DATE 는 컨테이너 creation_time
    //   (HLS concat mp4 는 0 → QuickTime 에폭 1904-01-01 표시)이라 안 씀.
    fun releaseDate(ctx: Context, uri: Uri): String? =
        read(ctx, uri, listOf("©day"))

    // moov→udta→meta→ilst 진입 후 keys 순서대로 첫 비어있지 않은 값 반환.
    private fun read(ctx: Context, uri: Uri, keys: List<String>): String? {
        return try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val total = ch.size()
                    val moov = findChild(ch, 0L, total, "moov") ?: return null
                    val udta = findChild(ch, moov.first, moov.second, "udta") ?: return null
                    val meta = findChild(ch, udta.first, udta.second, "meta") ?: return null
                    // meta 는 full-atom — payload 앞 4바이트(version/flags) 건너뜀
                    val ilst = findChild(ch, meta.first + 4, meta.second - 4, "ilst") ?: return null
                    for (key in keys) {
                        val item = findChild(ch, ilst.first, ilst.second, key) ?: continue
                        val data = findChild(ch, item.first, item.second, "data") ?: continue
                        // data payload: 4(version/flags)+4(reserved) 뒤가 값
                        val len = (data.second - 8L)
                        if (len <= 0 || len > 1_000_000L) continue
                        val buf = ByteBuffer.allocate(len.toInt())
                        ch.read(buf, data.first + 8L)
                        val s = String(buf.array(), Charsets.UTF_8).trim()
                        if (s.isNotEmpty()) return s
                    }
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    // [start, start+len) 범위에서 type 자식 atom 의 (payloadStart, payloadLen) 반환.
    private fun findChild(ch: FileChannel, start: Long, len: Long, type: String): Pair<Long, Long>? {
        var off = start
        val end = start + len
        val hdr = ByteBuffer.allocate(16)
        while (off + 8 <= end) {
            hdr.clear()
            hdr.limit(8)
            if (ch.read(hdr, off) < 8) break
            var atomSize = (hdr.getInt(0).toLong() and 0xFFFFFFFFL)
            val t = String(
                byteArrayOf(hdr.get(4), hdr.get(5), hdr.get(6), hdr.get(7)),
                Charsets.ISO_8859_1
            )
            var headerSize = 8L
            if (atomSize == 1L) {
                hdr.clear(); hdr.limit(8)
                if (ch.read(hdr, off + 8) < 8) break
                atomSize = hdr.getLong(0)
                headerSize = 16L
            } else if (atomSize == 0L) {
                atomSize = end - off
            }
            if (atomSize < headerSize || off + atomSize > end) break
            if (t == type) return Pair(off + headerSize, atomSize - headerSize)
            off += atomSize
        }
        return null
    }
}
