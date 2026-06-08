package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.appcompat.app.AlertDialog

// 59: 타일 롱프레스 '삭제(휴지통으로)'.
//  - 내부 MediaStore: 안드로이드 네이티브 휴지통(createTrashRequest, 30일 복구) — 시스템 확인창 자동.
//  - 외부 SAF(USB/SD): 네이티브 휴지통 없음 → 드라이브 루트 .mpv-trash 폴더로 moveDocument(복구가능).
object VideoTrash {
    private const val TRASH_DIR = ".mpv-trash"
    private const val REQ_TRASH = 0x7A5

    // ⚠️ 다이얼로그는 AppCompat AlertDialog.Builder 만 쓴다 — MaterialAlertDialogBuilder 는 호스트
    //   액티비티 테마가 Material 이어야 하는데 MPVActivity=Theme.AppCompat.Light(비 Material)라 예외→
    //   확인창 미표시(B-68 휴지통 무동작 근본). ThemeOverlay 오버라이드로도 checkMaterialTheme 통과 못 함.
    //   AppCompat AlertDialog 는 AppCompat·Material3(후손) 양쪽서 동작 → 공유 코드 안전.

    fun confirmAndTrash(ctx: Context, uri: String, name: String, onRemoved: () -> Unit, onCancel: () -> Unit = {}) {
        val u = Uri.parse(uri)
        if (u.authority == MediaStore.AUTHORITY) mediaStoreTrash(ctx, u, name, onRemoved)
        else safTrashConfirm(ctx, u, name, onRemoved, onCancel)
    }

    // 단건 영구 삭제 — 휴지통(.mpv-trash) 폴더 안 타일 롱프레스 '영구 삭제'. 확인창 후 deleteDocument(복구불가).
    fun permanentDeleteConfirm(ctx: Context, uri: String, name: String, onRemoved: () -> Unit) {
        val u = Uri.parse(uri)
        AlertDialog.Builder(ctx)
            .setTitle("영구 삭제")
            .setMessage("$name\n영구 삭제합니다. 복구할 수 없습니다.")
            .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
            .setPositiveButton("영구 삭제") { _, _ -> permanentDelete(ctx, u, name, onRemoved) }
            .show()
    }

    private fun permanentDelete(ctx: Context, u: Uri, name: String, onRemoved: () -> Unit) {
        Thread {
            val ok = try {
                if (u.authority == MediaStore.AUTHORITY) ctx.contentResolver.delete(u, null, null) > 0
                else DocumentsContract.deleteDocument(ctx.contentResolver, u)
            } catch (e: Throwable) { JavDiag.ex("permDelete", e); false }
            (ctx as? Activity)?.runOnUiThread {
                if (ok) { ThumbLoader.invalidate(ctx, u, name); onRemoved(); toast(ctx, "영구 삭제: $name") }
                else toast(ctx, "삭제 실패: $name")
            }
        }.start()
    }

    // 휴지통 비우기 — 트리 루트의 .mpv-trash 내 전체 파일 영구삭제(deleteDocument, 복구 불가). 확인창 후.
    //   삭제는 계속 휴지통 경유(안전), 주기적으로 이걸로 비운다. SafBrowser 메뉴서 현재 드라이브 대상.
    fun emptyTrashConfirm(ctx: Context, treeUri: Uri, onDone: () -> Unit = {}) {
        Thread {
            val files = try {
                val root = DocumentFile.fromTreeUri(ctx, treeUri)
                root?.findFile(TRASH_DIR)?.takeIf { it.isDirectory }?.listFiles()?.toList() ?: emptyList()
            } catch (e: Throwable) { JavDiag.ex("emptyTrash", e); emptyList() }
            (ctx as? Activity)?.runOnUiThread {
                if (files.isEmpty()) { toast(ctx, "$TRASH_DIR 비어 있음"); onDone(); return@runOnUiThread }
                AlertDialog.Builder(ctx)
                    .setTitle("휴지통 비우기")
                    .setMessage("$TRASH_DIR 의 ${files.size}개 파일을 영구 삭제합니다.\n복구할 수 없습니다.")
                    .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
                    .setPositiveButton("영구 삭제") { _, _ -> doEmptyTrash(ctx, files, onDone) }
                    .show()
            }
        }.start()
    }

    private fun doEmptyTrash(ctx: Context, files: List<DocumentFile>, onDone: () -> Unit) {
        Thread {
            var ok = 0; var fail = 0
            for (f in files) {
                val r = try { DocumentsContract.deleteDocument(ctx.contentResolver, f.uri) }
                        catch (e: Throwable) { JavDiag.ex("emptyTrash.del", e); false }
                if (r) ok++ else fail++
            }
            (ctx as? Activity)?.runOnUiThread {
                toast(ctx, "영구삭제 완료: ${ok}개" + if (fail > 0) ", 실패 $fail" else "")
                onDone()
            }
        }.start()
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
                AlertDialog.Builder(ctx)
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

    private fun safTrashConfirm(ctx: Context, u: Uri, name: String, onRemoved: () -> Unit, onCancel: () -> Unit = {}) {
        try {
            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.action_trash))
                .setMessage("$name\n드라이브의 $TRASH_DIR 폴더로 이동합니다(복구 가능).")
                .setNegativeButton(ctx.getString(R.string.dialog_cancel)) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }   // 바깥 탭/뒤로 취소도 재개 콜백
                .setPositiveButton(ctx.getString(R.string.action_trash)) { _, _ -> safTrash(ctx, u, name, onRemoved) }
                .show()
        } catch (e: Throwable) { JavDiag.ex("safTrashConfirm", e); onCancel() }
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
            } catch (e: Throwable) { JavDiag.ex("safTrash", e); false }   // 무음 catch 금지
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
