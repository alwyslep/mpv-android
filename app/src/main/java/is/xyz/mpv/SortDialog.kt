package `is`.xyz.mpv

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/**
 * 26: 화면(scope)별 독립 정렬 팝업. 각 화면 toolbar 의 정렬 버튼이 호출한다.
 * scope: home_folders / home_videos / home_tree / folder / saf / search — LibPrefs 가 scope 별 저장.
 */
object SortDialog {
    // 영상 화면 정렬 키(키 to 라벨)
    private val VID = listOf(
        "name" to "제목", "date" to "날짜", "size" to "크기", "length" to "길이",
        "rating" to "평점", "progress" to "진행률", "favorite" to "찜"
    )
    // 폴더 화면 정렬 키 (size=폴더 내 개수)
    private val FOLD = listOf(
        "name" to "제목", "date" to "날짜", "size" to "개수", "path" to "위치"
    )

    fun show(act: Activity, scope: String, isFolder: Boolean, onChanged: () -> Unit) {
        val keys = if (isFolder) FOLD else VID
        val dp = act.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(8))
        }

        val curKey = LibPrefs.sortKey(act, scope)
        val rg = RadioGroup(act)
        val idMap = HashMap<Int, String>()
        for ((k, label) in keys) {
            val rb = RadioButton(act).apply {
                text = label
                id = View.generateViewId()
                setPadding(px(6), px(8), px(6), px(8))
            }
            idMap[rb.id] = k
            rg.addView(rb)
            if (k == curKey) rg.check(rb.id)
        }
        if (rg.checkedRadioButtonId == -1 && rg.childCount > 0) rg.check(rg.getChildAt(0).id)
        root.addView(rg)

        root.addView(TextView(act).apply { text = "순서"; setPadding(px(4), px(12), 0, px(4)) })

        val grp = MaterialButtonToggleGroup(act).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val style = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val btnAsc = MaterialButton(act, null, style).apply {
            text = "오름차순"; id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnDesc = MaterialButton(act, null, style).apply {
            text = "내림차순"; id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        grp.addView(btnAsc); grp.addView(btnDesc)
        grp.check(if (LibPrefs.sortAsc(act, scope)) btnAsc.id else btnDesc.id)
        root.addView(grp)

        AlertDialog.Builder(act, R.style.DarkPopupDialog)
            .setTitle("정렬")
            .setView(root)
            .setPositiveButton("적용") { _, _ ->
                val key = idMap[rg.checkedRadioButtonId] ?: "name"
                val asc = grp.checkedButtonId == btnAsc.id
                LibPrefs.setSort(act, scope, key, asc)
                onChanged()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
