package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.documentfile.provider.DocumentFile
import androidx.appcompat.app.AlertDialog

// 59: 타일 롱프레스 '삭제(휴지통으로)'.
//  - 내부 MediaStore: 안드로이드 네이티브 휴지통(createTrashRequest, 30일 복구) — 시스템 확인창 자동.
//  - 외부 SAF(USB/SD): 네이티브 휴지통 없음 → 드라이브 루트 .mpv-trash 폴더로 moveDocument(복구가능).
object VideoTrash {
    private const val TRASH_DIR = ".mpv-trash"
    private const val REQ_TRASH = 0x7A5

    // 74: 삭제(휴지통/영구) 후 화면 복귀 시 재조회 정합 신호. MediaStore 휴지통은 시스템 확인창 결과를
    //   안 기다리고 낙관 제거하므로(즉시 반응), 복귀(onResume) 때 reload 로 취소분 복원·확정분 반영 → '엉성' 제거.
    @Volatile var pendingReload = false
    fun consumePendingReload(): Boolean { val r = pendingReload; pendingReload = false; return r }

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

    // 53: 선택 일괄 영구삭제(휴지통 폴더 내). 확인창 1회 → 순차 deleteDocument/contentResolver.delete.
    fun bulkPermanentDeleteConfirm(act: Activity, items: List<Pair<Uri, String>>, onDone: () -> Unit) {
        if (items.isEmpty()) { toast(act, "선택 없음"); return }
        AlertDialog.Builder(act)
            .setTitle("영구 삭제")
            .setMessage("${items.size}개를 영구 삭제합니다. 복구할 수 없습니다.")
            .setNegativeButton(act.getString(R.string.dialog_cancel), null)
            .setPositiveButton("영구 삭제(${items.size})") { _, _ -> doBulkPermanentDelete(act, items, onDone) }
            .show()
    }

    private fun doBulkPermanentDelete(act: Activity, items: List<Pair<Uri, String>>, onDone: () -> Unit) {
        Thread {
            var ok = 0; var fail = 0
            for ((u, name) in items) {
                val r = try {
                    if (u.authority == MediaStore.AUTHORITY) act.contentResolver.delete(u, null, null) > 0
                    else DocumentsContract.deleteDocument(act.contentResolver, u)
                } catch (e: Throwable) { JavDiag.ex("bulkPermDelete", e); false }
                if (r) { ok++; ThumbLoader.invalidate(act, u, name) } else fail++
            }
            act.runOnUiThread { pendingReload = true; toast(act, "영구삭제: ${ok}개" + if (fail > 0) ", 실패 $fail" else ""); onDone() }
        }.start()
    }

    private fun permanentDelete(ctx: Context, u: Uri, name: String, onRemoved: () -> Unit) {
        Thread {
            val ok = try {
                if (u.authority == MediaStore.AUTHORITY) ctx.contentResolver.delete(u, null, null) > 0
                else DocumentsContract.deleteDocument(ctx.contentResolver, u)
            } catch (e: Throwable) { JavDiag.ex("permDelete", e); false }
            (ctx as? Activity)?.runOnUiThread {
                if (ok) { ThumbLoader.invalidate(ctx, u, name); onRemoved(); pendingReload = true; toast(ctx, "영구 삭제: $name") }
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
                pendingReload = true   // 시스템 확인창 결과를 복귀 시 reload 로 정합(취소면 복원)
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
            val ok = safTrashCore(ctx, u)
            (ctx as? Activity)?.runOnUiThread {
                if (ok) { ThumbLoader.invalidate(ctx, u, name); onRemoved(); pendingReload = true; toast(ctx, "휴지통으로 이동: $name") }
                else toast(ctx, "삭제 실패: $name")
            }
        }.start()
    }

    // SAF 휴지통 이동 동기 코어(UI 없음) — 단건/일괄 공용.
    private fun safTrashCore(ctx: Context, u: Uri): Boolean = try {
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

    // 일괄 휴지통 — 중복 검수 '작은쪽 일괄정리' 등. 확인창 1회 → MediaStore 는 createTrashRequest 일괄(OS 확인창 1회),
    //   SAF 는 .mpv-trash 로 순차 이동. items=(uri,name). launcher 는 IntentSender 결과(→재로드) 받는 런처.
    fun bulkTrashConfirm(act: Activity, items: List<Pair<Uri, String>>,
                         launcher: ActivityResultLauncher<IntentSenderRequest>, onDone: () -> Unit) {
        if (items.isEmpty()) { toast(act, "정리할 항목 없음"); return }
        AlertDialog.Builder(act)
            .setTitle("작은 중복 일괄정리")
            .setMessage("각 품번에서 추천(최대용량)만 남기고 ${items.size}개를 휴지통으로 보냅니다.\n내부=30일 복구 · 외부=.mpv-trash 복구가능")
            .setNegativeButton(act.getString(R.string.dialog_cancel), null)
            .setPositiveButton("휴지통으로(${items.size})") { _, _ -> doBulkTrash(act, items, launcher, onDone) }
            .show()
    }

    private fun doBulkTrash(act: Activity, items: List<Pair<Uri, String>>,
                            launcher: ActivityResultLauncher<IntentSenderRequest>, onDone: () -> Unit) {
        val ms = items.map { it.first }.filter { it.authority == MediaStore.AUTHORITY }
        val saf = items.filter { it.first.authority != MediaStore.AUTHORITY }
        if (saf.isNotEmpty()) Thread {
            var ok = 0; for ((u, _) in saf) if (safTrashCore(act, u)) ok++
            act.runOnUiThread { pendingReload = true; toast(act, "외부 ${ok}/${saf.size} 휴지통 이동"); if (ms.isEmpty()) onDone() }
        }.start()
        if (ms.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pi = MediaStore.createTrashRequest(act.contentResolver, ms, true)
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())   // OS 확인창 → 결과 콜백서 재로드
            } catch (e: Throwable) { JavDiag.ex("bulkTrash.ms", e); toast(act, "일괄 휴지통 실패: ${e.message}") }
        } else if (ms.isEmpty() && saf.isEmpty()) onDone()
    }

    // SAF document uri 의 부모 document uri (moveDocument sourceParent 용). JMove 와 동일 규칙.
    private fun parentDocUri(uri: Uri): Uri? = try {
        val docId = DocumentsContract.getDocumentId(uri)
        val cut = docId.lastIndexOf('/')
        if (cut < 0) null else DocumentsContract.buildDocumentUriUsingTree(uri, docId.substring(0, cut))
    } catch (_: Throwable) { null }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
}
