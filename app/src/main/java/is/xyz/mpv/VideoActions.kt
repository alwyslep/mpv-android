package `is`.xyz.mpv

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// 타일 길게누르기 공통 액션 — 상세 보기 / 즐겨찾기(찜) 토글 / 평점 매기기.
// onChanged: 찜·평점이 바뀌면 호출(목록 갱신용).
object VideoActions {
    fun longPress(anchor: View, uri: String, name: String, onChanged: () -> Unit) {
        val ctx = anchor.context
        val pm = PopupMenu(ctx, anchor)
        val faved = Favorites.has(ctx, uri)
        pm.menu.add(0, 1, 0, ctx.getString(R.string.action_details))
        pm.menu.add(0, 2, 1, if (faved) ctx.getString(R.string.action_unfav) else ctx.getString(R.string.action_fav))
        pm.menu.add(0, 3, 2, ctx.getString(R.string.action_rate))
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> VideoDetailActivity.open(ctx, uri, name)
                2 -> { Favorites.toggle(ctx, uri); onChanged() }
                3 -> ratingDialog(ctx, uri, onChanged)
            }
            true
        }
        pm.show()
    }

    fun ratingDialog(ctx: Context, uri: String, onChanged: () -> Unit) {
        val labels = arrayOf("★", "★★", "★★★", "★★★★", "★★★★★", ctx.getString(R.string.rating_none))
        val cur = Ratings.get(ctx, uri)
        val checked = if (cur in 1..5) cur - 1 else 5
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.qs_rating))
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                Ratings.set(ctx, uri, if (which == 5) 0 else which + 1)
                onChanged()
                dlg.dismiss()
            }
            .setNegativeButton(ctx.getString(R.string.dialog_cancel), null)
            .show()
    }
}
