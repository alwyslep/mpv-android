package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// 59: 타일 롱프레스 '삭제(휴지통으로)'.
//  - 내부 MediaStore: 안드로이드 네이티브 휴지통(createTrashRequest, 30일 복구) — 시스템 확인창 자동.
//  - 외부 SAF(USB/SD): 네이티브 휴지통 없음 → 드라이브 루트 .mpv-trash 폴더로 moveDocument(복구가능).
object VideoTrash {
    private const val TRASH_DIR = ".mpv-trash"
    private const val REQ_TRASH = 0x7A5

    fun confirmAndTrash(ctx: Context, uri: String, name: String, onRemoved: () -> Unit) {
        val u = Uri.parse(uri)
        if (u.authority == MediaStore.AUTHORITY) mediaStoreTrash(ctx, u, name, onRemoved)
        else safTrashConfirm(ctx, u, name, onRemoved)
    }

    // 내부 MediaStore — 시스템 휴지통 요청(확인창은 OS가 표시). 낙관적 제거 후 취소 시 다음 로드에 복귀.
    private fun mediaStoreTrash(ctx: Context, u: Uri, name: String, onRemoved: () -> Unit) {
        val act = ctx as? Activity ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createTrashRequest(act.contentResolver, listOf(u), true)
                act.startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
                ThumbLoader.invalidate(ctx, u, name)
                onRemoved()
            } else {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(ctx.getString(R.string.action_trash))
                    .setMessage("$name\n복구 불가 — 삭제하시겠습니까?")
                    .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
                    .setPositiveButton("삭제") { _, _ ->
                        try { act.contentResolver.delete(u, null, null); ThumbLoader.invalidate(ctx, u, name); onRemoved() }
                        catch (e: Exception) { toast(ctx, "삭제 실패: ${e.message}") }
                    }.show()
            }
        } catch (e: Exception) {
            toast(ctx, "삭제 실패: ${e.message}")
        }
    }

    private fun safTrashConfirm(ctx: Context, u: Uri, name: String, onRemoved: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.action_trash))
            .setMessage("$name\n드라이브의 $TRASH_DIR 폴더로 이동합니다(복구 가능).")
            .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
            .setPositiveButton(ctx.getString(R.string.action_trash)) { _, _ -> safTrash(ctx, u, name, onRemoved) }
            .show()
    }

    private fun safTrash(ctx: Context, u: Uri, name: String, onRemoved: () -> Unit) {
        Thread {
            val ok = try {
                val authority = u.authority!!
                val treeId = DocumentsContract.getTreeDocumentId(u)
                val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeId)
                val root = DocumentFile.fromTreeUri(ctx, treeUri)
                val trash = root?.findFile(TRASH_DIR)?.takeIf { it.isDirectory } ?: root?.createDirectory(TRASH_DIR)
                val parent = parentDocUri(u)
                if (trash != null && parent != null)
                    DocumentsContract.moveDocument(ctx.contentResolver, u, parent, trash.uri) != null
                else false
            } catch (_: Throwable) { false }
            (ctx as? Activity)?.runOnUiThread {
                if (ok) { ThumbLoader.invalidate(ctx, u, name); onRemoved(); toast(ctx, "휴지통으로 이동: $name") }
                else toast(ctx, "삭제 실패: $name")
            }
        }.start()
    }

    // SAF document uri 의 부모 document uri (moveDocument sourceParent 용). JMove 와 동일 규칙.
    private fun parentDocUri(uri: Uri): Uri? = try {
        val docId = DocumentsContract.getDocumentId(uri)
        val cut = docId.lastIndexOf('/')
        if (cut < 0) null else DocumentsContract.buildDocumentUriUsingTree(uri, docId.substring(0, cut))
    } catch (_: Throwable) { null }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
}
