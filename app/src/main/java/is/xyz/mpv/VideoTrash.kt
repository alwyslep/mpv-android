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

    // ⚠️ 다이얼로그는 반드시 Material 오버레이 테마를 명시해야 한다 — MPVActivity 테마가
    //   Theme.AppCompat.Light(비 Material)라 MaterialAlertDialogBuilder(ctx) 무인자 호출 시 예외→
    //   확인창 미표시(B-68 휴지통 무동작 근본원인). DLG = ThemeOverlay.Material3.MaterialAlertDialog.
    private val DLG = R.style.AppTheme_Preference_AlertDialog

    fun confirmAndTrash(ctx: Context, uri: String, name: String, onRemoved: () -> Unit, onCancel: () -> Unit = {}) {
        val u = Uri.parse(uri)
        JavDiag.log("trash", "confirmAndTrash auth=${u.authority}")
        if (u.authority == MediaStore.AUTHORITY) mediaStoreTrash(ctx, u, name, onRemoved)
        else safTrashConfirm(ctx, u, name, onRemoved, onCancel)
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
                MaterialAlertDialogBuilder(ctx, DLG)
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
        JavDiag.log("trash", "safTrashConfirm 진입 name=$name")
        try {
            MaterialAlertDialogBuilder(ctx, DLG)
                .setTitle(ctx.getString(R.string.action_trash))
                .setMessage("$name\n드라이브의 $TRASH_DIR 폴더로 이동합니다(복구 가능).")
                .setNegativeButton(ctx.getString(R.string.dialog_cancel)) { _, _ -> JavDiag.log("trash", "confirm 취소"); onCancel() }
                .setOnCancelListener { JavDiag.log("trash", "confirm dismiss"); onCancel() }   // 바깥 탭/뒤로 취소도 재개 콜백
                .setPositiveButton(ctx.getString(R.string.action_trash)) { _, _ -> JavDiag.log("trash", "confirm OK→safTrash"); safTrash(ctx, u, name, onRemoved) }
                .show()
            JavDiag.log("trash", "safTrashConfirm 표시 성공")
        } catch (e: Throwable) { JavDiag.ex("safTrashConfirm", e); onCancel() }
    }

    private fun safTrash(ctx: Context, u: Uri, name: String, onRemoved: () -> Unit) {
        Thread {
            var diag = "?"
            JavDiag.log("safTrash", "BEGIN name=$name")
            JavDiag.log("safTrash", "uri=$u")
            JavDiag.log("safTrash", "scheme=${u.scheme} authority=${u.authority}")
            // 보유 persisted 권한 목록(트리/쓰기 여부 확인)
            try {
                ctx.contentResolver.persistedUriPermissions.forEach {
                    JavDiag.log("perm", "uri=${it.uri} r=${it.isReadPermission} w=${it.isWritePermission}")
                }
            } catch (e: Throwable) { JavDiag.ex("perm", e) }
            val ok = try {
                val authority = u.authority
                if (authority == null) { diag = "authority=null"; JavDiag.log("safTrash", diag); false }
                else {
                    val treeId = try { val t = DocumentsContract.getTreeDocumentId(u); JavDiag.log("safTrash", "treeId=$t"); t }
                                 catch (e: Throwable) { diag = "getTreeDocumentId 실패"; JavDiag.ex("safTrash.treeId", e); null }
                    if (treeId == null) false
                    else {
                        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeId)
                        JavDiag.log("safTrash", "treeUri=$treeUri")
                        val root = DocumentFile.fromTreeUri(ctx, treeUri)
                        JavDiag.log("safTrash", "root=${root?.uri} name=${root?.name} canWrite=${root?.canWrite()} exists=${root?.exists()}")
                        if (root == null) { diag = "root null"; false }
                        else {
                            val existing = root.findFile(TRASH_DIR)
                            JavDiag.log("safTrash", "findFile($TRASH_DIR)=${existing?.uri} isDir=${existing?.isDirectory}")
                            val trash = existing?.takeIf { it.isDirectory } ?: root.createDirectory(TRASH_DIR)
                            JavDiag.log("safTrash", "trashDir=${trash?.uri} canWrite=${trash?.canWrite()}")
                            val parent = parentDocUri(u)
                            JavDiag.log("safTrash", "docId=${DocumentsContract.getDocumentId(u)} parent=$parent")
                            when {
                                trash == null -> { diag = "trash dir 생성실패(canWrite=${root.canWrite()})"; false }
                                parent == null -> { diag = "parent null"; false }
                                else -> {
                                    val moved = try {
                                        val r = DocumentsContract.moveDocument(ctx.contentResolver, u, parent, trash.uri)
                                        JavDiag.log("safTrash", "moveDocument result=$r"); r
                                    } catch (e: Throwable) { diag = "moveDocument 예외 ${e.javaClass.simpleName}"; JavDiag.ex("safTrash.move", e); null }
                                    if (moved == null && diag == "?") diag = "moveDocument null(미지원/권한)"
                                    moved != null
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) { diag = "예외 ${e.javaClass.simpleName}"; JavDiag.ex("safTrash", e); false }
            JavDiag.log("safTrash", "END ok=$ok diag=$diag")
            (ctx as? Activity)?.runOnUiThread {
                if (ok) { ThumbLoader.invalidate(ctx, u, name); onRemoved(); toast(ctx, "휴지통으로 이동: $name") }
                else Toast.makeText(ctx, "삭제 실패: $diag (로그 기록됨)", Toast.LENGTH_LONG).show()
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
