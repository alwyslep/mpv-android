package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

// B-68 확장: PNG 디코이 오염 영상 복구(타 다운로드 앱 산출물). 세그먼트마다 박힌 1×1 PNG 디코이를
//   정확매칭 제거 → 연속 MPEG-TS 복원(무손실, 코덱 없음). 검증=결과 첫바이트 0x47(TS sync).
//   원본을 교정본으로 대체(원본명 유지). SAF(외부 USB/SD) + 파일경로(내부) 지원.
//   상세/도구 동등: termux-scripts/cli/heal_png_decoy.py. 다이얼로그=AppCompat(테마 교훈).
object VideoHeal {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    // 고정 1×1 PNG 디코이(완전동일). 풀=시그니처 포함 70B, 숏=시그니처 없는 62B.
    private val FULL = hex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360606060000000050001a5f645400000000049454e44ae426082")
    private val SHORT = FULL.copyOfRange(8, FULL.size)
    private val DECOYS = listOf(FULL, SHORT)   // 긴 것 먼저(시그니처 고아 방지)
    private const val KEEP = 69                 // = max(decoy)-1, 청크 경계 carry
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun match(a: ByteArray, off: Int, n: Int): Boolean {
        for (d in DECOYS) {
            if (off + d.size <= n) {
                var ok = true
                for (k in d.indices) if (a[off + k] != d[k]) { ok = false; break }
                if (ok) return true
            }
        }
        return false
    }

    fun isCorrupt(head: ByteArray, n: Int): Boolean =
        n >= 8 && ((head[0] == 0x89.toByte() && head[1]=='P'.code.toByte() && head[2]=='N'.code.toByte() && head[3]=='G'.code.toByte())
                   || (head[0]==0.toByte() && head[1]==0.toByte() && head[2]==0.toByte() && head[3]==0x0d.toByte()
                       && head[4]=='I'.code.toByte() && head[5]=='H'.code.toByte() && head[6]=='D'.code.toByte() && head[7]=='R'.code.toByte()))

    // 타일이 손상인지 — 첫 8B peek(롱프레스 시 1회).
    fun peekCorrupt(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { val h = ByteArray(8); val n = it.read(h); isCorrupt(h, n) } ?: false
    } catch (_: Throwable) { false }

    // ── 디코이 제거 스트림 복사 ──
    private fun stripChunk(buf: ByteArray, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len)
        var i = 0; var run = 0
        while (i < len) {
            if (match(buf, i, len)) {
                if (i > run) out.write(buf, run, i - run)
                // 어느 디코이가 매칭됐는지 길이 산정
                var dl = 0
                for (d in DECOYS) { if (i + d.size <= len) { var ok=true; for (k in d.indices) if (buf[i+k]!=d[k]){ok=false;break}; if (ok){dl=d.size;break} } }
                i += dl; run = i
            } else i++
        }
        if (len > run) out.write(buf, run, len - run)
        return out.toByteArray()
    }

    private fun streamStrip(ins: InputStream, outs: OutputStream, total: Long, onProgress: (Long) -> Unit) {
        val buf = ByteArray(8 * 1024 * 1024)
        var carry = ByteArray(0)
        var read = 0L; var lastP = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            read += n
            if (read - lastP >= 16L * 1024 * 1024) { lastP = read; onProgress(read) }
            val comb = carry + buf.copyOf(n)
            val s = stripChunk(comb, comb.size)
            val w = s.size - KEEP
            if (w > 0) { outs.write(s, 0, w); carry = s.copyOfRange(w, s.size) } else carry = s
        }
        outs.write(carry)
        onProgress(if (total > 0) total else read)
    }

    fun sizeOf(ctx: Context, uri: Uri): Long = try {
        if (uri.scheme == "file") File(uri.path!!).length()
        else ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L
    } catch (_: Throwable) { 0L }


    private fun firstByteTs(ins: InputStream?): Boolean = ins?.use { it.read() == 0x47 } ?: false

    // 단건 복구 — 성공 시 원본을 교정본으로 대체(원본명 유지). 반환 (성공, 메시지).
    fun healUri(ctx: Context, uri: Uri, total: Long = 0L, onProgress: (Long) -> Unit = {}): Pair<Boolean, String> {
        val resolver = ctx.contentResolver
        val auth = uri.authority ?: ""
        try {
            if (auth.endsWith("documents")) {   // SAF (외부)
                val docId = DocumentsContract.getDocumentId(uri)
                val treeId = DocumentsContract.getTreeDocumentId(uri)
                val treeUri = DocumentsContract.buildTreeDocumentUri(auth, treeId)
                val origName = docId.substringAfterLast('/').ifBlank { "video.mp4" }
                val parentId = if (docId.contains('/')) docId.substringBeforeLast('/') else treeId
                val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
                val tmpName = origName.substringBeforeLast('.') + "__heal.tmp"
                val newDoc = DocumentsContract.createDocument(resolver, parentDoc, "video/mp4", tmpName)
                    ?: return false to "새 문서 생성 실패"
                try {
                    resolver.openInputStream(uri)!!.use { i -> resolver.openOutputStream(newDoc, "w")!!.use { o -> streamStrip(i, o, total, onProgress) } }
                } catch (e: Throwable) { runCatching { DocumentsContract.deleteDocument(resolver, newDoc) }; JavDiag.ex("heal.saf", e); return false to "복구 쓰기 실패" }
                if (!firstByteTs(resolver.openInputStream(newDoc))) {
                    runCatching { DocumentsContract.deleteDocument(resolver, newDoc) }; return false to "검증 실패(TS 아님)"
                }
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }            // 원본 삭제
                runCatching { DocumentsContract.renameDocument(resolver, newDoc, origName) } // 원본명 대체
                return true to "복구: $origName"
            }
            // 파일 경로(내부) — file:// 또는 MediaStore DATA
            val path = if (uri.scheme == "file") uri.path else realPath(ctx, uri)
                ?: return false to "경로 못 구함(SAF/파일만 지원)"
            val src = File(path); val tmp = File(path + ".heal.tmp")
            FileInputStream(src).use { i -> FileOutputStream(tmp).use { o -> streamStrip(i, o, total, onProgress) } }
            if (!firstByteTs(FileInputStream(tmp))) { tmp.delete(); return false to "검증 실패(TS 아님)" }
            if (!src.delete()) { tmp.delete(); return false to "원본 삭제 실패" }
            if (!tmp.renameTo(src)) return false to "이름 교체 실패"
            runCatching { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null) }
            return true to "복구: ${src.name}"
        } catch (e: Throwable) { JavDiag.ex("heal", e); return false to "복구 예외: ${e.message}" }
    }

    private fun realPath(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Throwable) { null }

    // 단건 확인창 → 복구
    fun healOneConfirm(ctx: Context, uri: String, name: String, onRemoved: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle("복구")
            .setMessage("$name\nPNG 디코이를 제거하고 원본을 교정본으로 대체합니다(무손실).")
            .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
            .setPositiveButton("복구") { _, _ ->
                val app = ctx.applicationContext
                val u = Uri.parse(uri); val total = sizeOf(app, u)
                JobProgress.start("PNG 복구 ($name)", 1)               // 하단 리치 배너(비모달·지속)
                Thread {
                    val r = healUri(app, u, total) { done ->
                        JobProgress.update(0, name, if (total > 0) (done * 100 / total).toInt() else 0)
                    }
                    JobProgress.done(r.second)
                    if (r.first) mainHandler.post { runCatching { onRemoved() } }
                }.start()
            }.show()
    }

    // 폴더 일괄 — items 중 손상 파일만 복구. 두 종류: PNG 디코이(스트림 strip) + stco 오프셋(4GB+ co64).
    fun healFolderConfirm(ctx: Context, items: List<Pair<Uri, String>>, onDone: () -> Unit = {}) {
        Thread {
            val png = items.filter { peekCorrupt(ctx, it.first) }
            val pngSet = png.mapTo(HashSet()) { it.first }
            // stco 오프셋 손상(4GB+ in-place 태깅 버그) — MoovHeal 이 크기 게이트(3.5GB)로 빠르게 거름.
            val moov = items.filter { it.first !in pngSet && MoovHeal.isBroken(ctx, it.first) }
            (ctx as? Activity)?.runOnUiThread {
                if (png.isEmpty() && moov.isEmpty()) { toast(ctx, "복구할 손상 파일 없음"); return@runOnUiThread }
                val parts = buildList {
                    if (png.isNotEmpty()) add("PNG 디코이 ${png.size}개")
                    if (moov.isNotEmpty()) add("오프셋(4GB) ${moov.size}개")
                }.joinToString(" · ")
                AlertDialog.Builder(ctx)
                    .setTitle("폴더 복구")
                    .setMessage("$parts 복구(원본 교체·무손실).")
                    .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
                    .setPositiveButton("복구") { _, _ -> runBatch(ctx, png, moov, onDone) }
                    .show()
            }
        }.start()
    }

    private fun runBatch(ctx: Context, png: List<Pair<Uri, String>>, moov: List<Pair<Uri, String>>, onDone: () -> Unit) {
        val app = ctx.applicationContext
        val total = png.size + moov.size
        JobProgress.start("손상 복구 (${total}개)", total)   // 하단 리치 배너(비모달·지속·중단)
        Thread {
            var ok = 0; var fail = 0; var idx = 0
            for ((u, nm) in png) {
                if (JobProgress.isCancelled()) break                     // 우아한 종료(현재 항목 완료 후)
                val sz = sizeOf(app, u)
                val r = healUri(app, u, sz) { done ->
                    JobProgress.update(idx, nm, if (sz > 0) (done * 100 / sz).toInt() else 0)
                }
                if (r.first) ok++ else fail++; idx++
            }
            for ((u, nm) in moov) {
                if (JobProgress.isCancelled()) break
                JobProgress.update(idx, nm, 0)
                if (MoovHeal.repair(app, u)) ok++ else fail++; idx++
            }
            val head = if (JobProgress.isCancelled()) "중단됨" else "완료"
            JobProgress.done("복구 $head: ${ok}개" + if (fail > 0) ", 실패 $fail" else "")
            mainHandler.post { runCatching { onDone() } }
        }.start()
    }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
}
