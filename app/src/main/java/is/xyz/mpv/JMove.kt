package `is`.xyz.mpv

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * 파일 이동(3) 엔진 — 선택 영상들을 대상 폴더로 이동.
 * PoC: 내부저장소(MediaStore DATA path) File 이동. 같은 볼륨=renameTo(즉시), 다른 볼륨=copy+delete.
 * 이동 후 원본·대상 둘 다 MediaScannerConnection.scanFile 로 MediaStore 갱신. SAF(외부) 이동은 다음 단계.
 */
object JMove {
    private fun ui(cb: () -> Unit) = Handler(Looper.getMainLooper()).post(cb)

    fun move(
        ctx: Context,
        items: List<Pair<Uri, String>>,   // (uri, displayName)
        destDir: String,                  // 내부저장소 절대경로 폴더
        onProgress: (idx: Int, total: Int, name: String) -> Unit,
        onDone: (ok: Int, fail: Int, fails: List<String>) -> Unit
    ) {
        val app = ctx.applicationContext
        Thread {
            var ok = 0
            val fails = ArrayList<String>()
            val dir = File(destDir)
            if (!dir.exists()) dir.mkdirs()
            val scan = ArrayList<String>()
            for ((i, pair) in items.withIndex()) {
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
                        if (moved) { ok++; scan.add(src); scan.add(dst.absolutePath) }
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
                        if (deleted) ok++ else fails.add("$name: 복사됨·원본 SAF 삭제 실패(수동 삭제 필요)")
                    }
                } catch (e: Throwable) { fails.add("$name: ${e.javaClass.simpleName}: ${e.message}") }
            }
            if (scan.isNotEmpty()) runCatching { MediaScannerConnection.scanFile(app, scan.toTypedArray(), null, null) }
            ui { onDone(ok, fails.size, fails) }
        }.start()
    }

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
