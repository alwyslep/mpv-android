package `is`.xyz.mpv

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// 타일 길게누르기 공통 액션 — 상세 보기 / 즐겨찾기(찜) 토글 / 평점 매기기.
// onChanged: 찜·평점이 바뀌면 호출(목록 갱신용).
object VideoActions {
    // permanent=true: 휴지통(.mpv-trash) 폴더 안 — 삭제 메뉴가 '영구 삭제'(휴지통 재이동 무의미)로 바뀐다.
    //   sysTrash=true: 내장 시스템 휴지통(IS_TRASHED) 가상 폴더 — '복원' + '영구 삭제'.
    fun longPress(anchor: View, uri: String, name: String, onChanged: () -> Unit, onRemoved: () -> Unit = {},
                  permanent: Boolean = false, sysTrash: Boolean = false) {
        val ctx = anchor.context
        // 휴지통 안 판정 — 진입경로 무관: 호출측 permanent(폴더 컨텍스트)/sysTrash 또는 uri 에 .mpv-trash 포함.
        val inTrash = permanent || sysTrash || uri.contains(".mpv-trash")
        val corrupt = VideoHeal.peekCorrupt(ctx, android.net.Uri.parse(uri))   // B-68: PNG 디코이 손상?
        val pm = PopupMenu(ctx, anchor)
        val faved = Favorites.has(ctx, uri, name)
        pm.menu.add(0, 1, 0, ctx.getString(R.string.action_details))
        if (sysTrash) pm.menu.add(0, 8, 1, "복원")   // 74: 시스템 휴지통에서 복원(IS_TRASHED=0)
        if (!sysTrash) {
            pm.menu.add(0, 2, 2, if (faved) ctx.getString(R.string.action_unfav) else ctx.getString(R.string.action_fav))
            pm.menu.add(0, 3, 3, ctx.getString(R.string.action_rate))
            pm.menu.add(0, 6, 4, "이름 바꾸기")   // B-68(52): 파일관리 — 이름 변경
            pm.menu.add(0, 7, 5, "장면 비교(중복)")   // B-68(52, GridPlayer A안): 같은 품번 버전 프레임 격자 비교
            if (corrupt) pm.menu.add(0, 5, 6, "복구(PNG디코이)")   // 손상 파일만 노출 → 디코이 제거·원본 교체
        }
        pm.menu.add(0, 4, 7, if (inTrash) "영구 삭제" else ctx.getString(R.string.action_trash))   // 휴지통으로 / 휴지통 안=영구삭제
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> VideoDetailActivity.open(ctx, uri, name)
                2 -> { Favorites.toggle(ctx, uri, name); onChanged() }
                3 -> ratingDialog(ctx, uri, name, onChanged)
                4 -> if (inTrash) VideoTrash.permanentDeleteConfirm(ctx, uri, name, onRemoved)
                     else VideoTrash.confirmAndTrash(ctx, uri, name, onRemoved)
                5 -> VideoHeal.healOneConfirm(ctx, uri, name, onRemoved)
                6 -> VideoRename.confirm(ctx, uri, name, onRemoved)
                7 -> SceneCompare.show(ctx, name)
                8 -> {   // 복원
                    if (MediaLibrary.restoreTrashed(ctx, android.net.Uri.parse(uri))) {
                        android.widget.Toast.makeText(ctx, "복원: $name", android.widget.Toast.LENGTH_SHORT).show(); onRemoved()
                    } else android.widget.Toast.makeText(ctx, "복원 실패: $name", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        pm.show()
    }

    fun ratingDialog(ctx: Context, uri: String, name: String?, onChanged: () -> Unit) {
        val labels = arrayOf("★", "★★", "★★★", "★★★★", "★★★★★", ctx.getString(R.string.rating_none))
        val cur = Ratings.get(ctx, uri, name)
        val checked = if (cur in 1..5) cur - 1 else 5
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.qs_rating))
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                Ratings.set(ctx, uri, name, if (which == 5) 0 else which + 1)
                onChanged()
                dlg.dismiss()
            }
            .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
            .show()
    }
}
