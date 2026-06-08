package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File

// B-68(52): 파일 이름 바꾸기(파일관리 확장). SAF(외부) = renameDocument, 파일경로(내부) = File.renameTo + MediaScan.
//   표시명(확장자 없음)으로 입력받아 원래 확장자 유지. 변경 후 onDone 로 목록 갱신.
object VideoRename {
    fun confirm(ctx: Context, uri: String, displayNameNoExt: String, onDone: () -> Unit) {
        val u = Uri.parse(uri)
        val et = EditText(ctx).apply { setText(displayNameNoExt); setSelection(text.length) }
        AlertDialog.Builder(ctx)
            .setTitle("이름 바꾸기")
            .setView(et)
            .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
            .setPositiveButton("변경") { _, _ ->
                val nn = et.text.toString().trim().replace('/', '_')
                if (nn.isEmpty() || nn == displayNameNoExt) return@setPositiveButton
                Thread {
                    val r = rename(ctx, u, nn)
                    (ctx as? Activity)?.runOnUiThread {
                        Toast.makeText(ctx, r.second, Toast.LENGTH_SHORT).show()
                        if (r.first) onDone()
                    }
                }.start()
            }.show()
    }

    private fun fullName(ctx: Context, u: Uri): String? = try {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Throwable) { null }

    private fun realPath(ctx: Context, u: Uri): String? = try {
        ctx.contentResolver.query(u, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Throwable) { null }

    private fun rename(ctx: Context, u: Uri, newBase: String): Pair<Boolean, String> {
        return try {
            if (u.authority?.endsWith("documents") == true) {
                val ext = (fullName(ctx, u) ?: u.lastPathSegment.orEmpty()).substringAfterLast('.', "")
                val newFull = if (ext.isNotEmpty()) "$newBase.$ext" else newBase
                if (DocumentsContract.renameDocument(ctx.contentResolver, u, newFull) != null)
                    true to "이름 변경: $newFull" else false to "이름 변경 실패"
            } else {
                val path = (if (u.scheme == "file") u.path else realPath(ctx, u)) ?: return false to "경로 못 구함"
                val src = File(path)
                val ext = src.name.substringAfterLast('.', "")
                val newFull = if (ext.isNotEmpty()) "$newBase.$ext" else newBase
                val dst = File(src.parentFile, newFull)
                if (dst.exists()) return false to "같은 이름 존재"
                if (src.renameTo(dst)) {
                    runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(src.path, dst.path), null, null) }
                    true to "이름 변경: $newFull"
                } else false to "이름 변경 실패"
            }
        } catch (e: Throwable) { JavDiag.ex("rename", e); false to "이름 변경 예외: ${e.message}" }
    }
}
