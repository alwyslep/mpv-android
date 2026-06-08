package `is`.xyz.mpv

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// 타일 길게누르기 공통 액션 — 상세 보기 / 즐겨찾기(찜) 토글 / 평점 매기기.
// onChanged: 찜·평점이 바뀌면 호출(목록 갱신용).
object VideoActions {
    // permanent=true: 휴지통(.mpv-trash) 폴더 안 — 삭제 메뉴가 '영구 삭제'(휴지통 재이동 무의미)로 바뀐다.
    fun longPress(anchor: View, uri: String, name: String, onChanged: () -> Unit, onRemoved: () -> Unit = {},
                  permanent: Boolean = false) {
        val ctx = anchor.context
        // 휴지통 안 판정 — 진입경로 무관: 호출측 permanent(폴더 컨텍스트) 또는 uri 자체에 .mpv-trash 포함.
        val inTrash = permanent || uri.contains(".mpv-trash")
        val corrupt = VideoHeal.peekCorrupt(ctx, android.net.Uri.parse(uri))   // B-68: PNG 디코이 손상?
        val pm = PopupMenu(ctx, anchor)
        val faved = Favorites.has(ctx, uri, name)
        pm.menu.add(0, 1, 0, ctx.getString(R.string.action_details))
        pm.menu.add(0, 2, 1, if (faved) ctx.getString(R.string.action_unfav) else ctx.getString(R.string.action_fav))
        pm.menu.add(0, 3, 2, ctx.getString(R.string.action_rate))
        if (corrupt) pm.menu.add(0, 5, 3, "복구(PNG디코이)")   // 손상 파일만 노출 → 디코이 제거·원본 교체
        pm.menu.add(0, 4, 4, if (inTrash) "영구 삭제" else ctx.getString(R.string.action_trash))   // 휴지통으로 / 휴지통 안=영구삭제
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> VideoDetailActivity.open(ctx, uri, name)
                2 -> { Favorites.toggle(ctx, uri, name); onChanged() }
                3 -> ratingDialog(ctx, uri, name, onChanged)
                4 -> if (inTrash) VideoTrash.permanentDeleteConfirm(ctx, uri, name, onRemoved)
                     else VideoTrash.confirmAndTrash(ctx, uri, name, onRemoved)
                5 -> VideoHeal.healOneConfirm(ctx, uri, name, onRemoved)
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
