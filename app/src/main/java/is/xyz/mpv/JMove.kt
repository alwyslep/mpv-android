package `is`.xyz.mpv

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 파일 이동(3) 엔진 — 선택 영상들을 대상 폴더로 이동.
 * move(): 대상=내부저장소 폴더. 같은 볼륨=renameTo(즉시), 다른 볼륨/SAF src=copy+delete.
 * moveToSaf(): 대상=외부 SAF 폴더. 같은 드라이브 SAF src=moveDocument(즉시), 그 외=copy+delete.
 * 이동 후 MediaScannerConnection.scanFile + clearSafCache 로 양측 반영. (목적지 SAF 지원 완료 — 14번에서 검증)
 */
object JMove {
    private fun ui(cb: () -> Unit) = Handler(Looper.getMainLooper()).post(cb)

    fun move(
        ctx: Context,
        items: List<Pair<Uri, String>>,   // (uri, displayName)
        destDir: String,                  // 내부저장소 절대경로 폴더
        onProgress: (idx: Int, total: Int, name: String) -> Unit,
        onDone: (ok: Int, fail: Int, fails: List<String>) -> Unit,
        onItemMoved: (uri: Uri) -> Unit = {},
        cancel: () -> Boolean = { false }
    ) {
        val app = ctx.applicationContext
        Thread {
            var ok = 0
            val fails = ArrayList<String>()
            val dir = File(destDir)
            if (!dir.exists()) dir.mkdirs()
            val scan = ArrayList<String>()
            for ((i, pair) in items.withIndex()) {
                if (cancel()) break   // graceful: 다음 파일 시작 전 중단(진행 중 파일은 끝까지)
                val (uri, name) = pair
                ui { onProgress(i, items.size, name) }
                try {
                    val src = pathFromMediaStore(app, uri)
                    if (src != null) {
                        // 내부저장소 File 이동 (같은 볼륨 renameTo / 다른 볼륨 copy+delete)
                        val sf = File(src)
                        val dst = File(dir, sf.name)
                        if (dst.absolutePath == sf.absolutePath) { fails.add("$name: 같은 위치"); continue }
                        if (dst.exists()) { fails.add("$name: 대상에 동일 파일 존재"); continue }
                        val moved = sf.renameTo(dst) || run { sf.copyTo(dst, overwrite = false); sf.delete() }
                        if (moved) { ok++; scan.add(src); scan.add(dst.absolutePath); ui { onItemMoved(uri) } }
                        else fails.add("$name: 이동 실패")
                    } else {
                        // 외부저장소(SAF) → 내부 대상: 스트림 복사 + 원본 SAF 삭제
                        val dst = File(dir, safDisplayName(app, uri))
                        if (dst.exists()) { fails.add("$name: 대상에 동일 파일 존재"); continue }
                        val copied = try {
                            app.contentResolver.openInputStream(uri)?.use { ins -> dst.outputStream().use { ins.copyTo(it) }; true } ?: false
                        } catch (e: Throwable) { false }
                        if (!copied) { runCatching { dst.delete() }; fails.add("$name: SAF 복사 실패"); continue }
                        val deleted = try { DocumentsContract.deleteDocument(app.contentResolver, uri) } catch (e: Throwable) { false }
                        scan.add(dst.absolutePath)
                        if (deleted) { ok++; ui { onItemMoved(uri) } } else fails.add("$name: 복사됨·원본 SAF 삭제 실패(수동 삭제 필요)")
                    }
                } catch (e: Throwable) { fails.add("$name: ${e.javaClass.simpleName}: ${e.message}") }
            }
            if (scan.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(app, scan.toTypedArray(), null, null) }
            ui { onDone(ok, fails.size, fails) }
        }.start()
    }

    // 대상이 외부저장소(SAF) 폴더인 경우 — 내부/외부 src 를 SAF 폴더로 복사 + 원본 삭제.
    fun moveToSaf(
        ctx: Context, items: List<Pair<Uri, String>>, destDir: DocumentFile,
        onProgress: (idx: Int, total: Int, name: String) -> Unit,
        onDone: (ok: Int, fail: Int, fails: List<String>) -> Unit,
        onItemMoved: (uri: Uri) -> Unit = {},
        cancel: () -> Boolean = { false }
    ) {
        val app = ctx.applicationContext
        Thread {
            var ok = 0
            val fails = ArrayList<String>()
            if (!destDir.canWrite()) { ui { onDone(0, items.size, listOf("대상 폴더 쓰기 불가")) }; return@Thread }
            val scan = ArrayList<String>()
            for ((i, pair) in items.withIndex()) {
                if (cancel()) break   // graceful: 다음 파일 시작 전 중단
                val (uri, name) = pair
                ui { onProgress(i, items.size, name) }
                try {
                    val src = pathFromMediaStore(app, uri)
                    // ① SAF src + 같은 드라이브(authority 동일) → moveDocument 로 즉시 이동(복사 없음) — 속도 핵심
                    if (src == null && Build.VERSION.SDK_INT >= 24 && uri.authority == destDir.uri.authority) {
                        val parent = parentDocUri(uri)
                        if (parent != null) {
                            val moved = try {
                                DocumentsContract.moveDocument(app.contentResolver, uri, parent, destDir.uri) != null
                            } catch (_: Throwable) { false }
                            if (moved) { ok++; ui { onItemMoved(uri) }; continue }
                        }
                    }
                    // ② fallback: 복사 + 원본 삭제 (내부→SAF, 다른 드라이브, moveDocument 불가 시)
                    val dname = if (src != null) File(src).name else safDisplayName(app, uri)
                    if (destDir.findFile(dname) != null) { fails.add("$name: 대상에 동일 파일 존재"); continue }
                    val outDoc = destDir.createFile("video/mp4", dname) ?: run { fails.add("$name: 대상 생성 실패"); continue }
                    val copied = try {
                        val ins = if (src != null) File(src).inputStream() else app.contentResolver.openInputStream(uri)
                        ins?.use { i2 -> app.contentResolver.openOutputStream(outDoc.uri)?.use { o -> i2.copyTo(o) }; true } ?: false
                    } catch (e: Throwable) { false }
                    if (!copied) { runCatching { outDoc.delete() }; fails.add("$name: 복사 실패"); continue }
                    if (src != null) { File(src).delete(); scan.add(src) }
                    else runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                    ok++; ui { onItemMoved(uri) }
                } catch (e: Throwable) { fails.add("$name: ${e.javaClass.simpleName}: ${e.message}") }
            }
            if (scan.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(app, scan.toTypedArray(), null, null) }
            runCatching { MediaLibrary.clearSafCache() }   // SAF 변경 반영 — 다음 load 재스캔
            ui { onDone(ok, fails.size, fails) }
        }.start()
    }

    // SAF document uri 의 부모 document uri (moveDocument sourceParent 용). docId 의 마지막 '/' 이전이 부모.
    private fun parentDocUri(uri: Uri): Uri? = try {
        val docId = DocumentsContract.getDocumentId(uri)
        val cut = docId.lastIndexOf('/')
        if (cut < 0) null else DocumentsContract.buildDocumentUriUsingTree(uri, docId.substring(0, cut))
    } catch (_: Throwable) { null }

    private fun safDisplayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) { val n = it.getString(0); if (!n.isNullOrBlank()) return n }
            }
        } catch (_: Throwable) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "video.mp4"
    }

    private fun pathFromMediaStore(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Throwable) { null }
    }
}
