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
        JavDiag.log("trash", "confirmAndTrash auth=${u.authority}")
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
        JavDiag.log("permDelete", "BEGIN $name uri=$u")
        Thread {
            val ok = try {
                if (u.authority == MediaStore.AUTHORITY) ctx.contentResolver.delete(u, null, null) > 0
                else DocumentsContract.deleteDocument(ctx.contentResolver, u)
            } catch (e: Throwable) { JavDiag.ex("permDelete", e); false }
            JavDiag.log("permDelete", "END ok=$ok")
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
        JavDiag.log("emptyTrash", "BEGIN ${files.size}개")
        Thread {
            var ok = 0; var fail = 0
            for (f in files) {
                val r = try { DocumentsContract.deleteDocument(ctx.contentResolver, f.uri) }
                        catch (e: Throwable) { JavDiag.ex("emptyTrash.del", e); false }
                if (r) ok++ else fail++
            }
            JavDiag.log("emptyTrash", "END ok=$ok fail=$fail")
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
        JavDiag.log("trash", "safTrashConfirm 진입 name=$name")
        try {
            AlertDialog.Builder(ctx)
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
