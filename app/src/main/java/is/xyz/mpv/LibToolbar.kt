package `is`.xyz.mpv

import android.view.MenuItem
import com.google.android.material.appbar.MaterialToolbar

// B-68(52): 모든 라이브러리 화면(플레이어 제외) 공통 툴바. 12 액션 합집합을 동일 순서로 깔고
//   IFROOM(폭 맞춰 자동 overflow ⋮) + 화면별 enabled 외 항목은 isEnabled=false + 아이콘 흐림(회색).
//   각 화면은 enabled 집합과 on(key) 핸들러만 선언. (gridIsGrid: toggle 아이콘 grid/list 표시용, null=비활성)
object LibToolbar {
    data class Spec(val key: String, val icon: Int, val label: String)

    val ALL = listOf(
        Spec("toggle", R.drawable.ic_list_24, "보기 전환"),
        Spec("search", R.drawable.ic_search_24, "검색"),
        Spec("classify", R.drawable.ic_people_24, "분류"),
        Spec("sort", R.drawable.ic_sort_24, "정렬"),
        Spec("tune", R.drawable.ic_tune_24, "빠른 설정"),
        Spec("select", R.drawable.ic_check_circle_24, "선택(임베드)"),
        Spec("filter", R.drawable.ic_filter_alt_24dp, "필터"),
        Spec("move", R.drawable.ic_folder_24, "이동"),
        Spec("thumb", R.drawable.ic_image, "썸네일 지정"),
        Spec("heal", R.drawable.ic_healing_24, "PNG 복구"),
        Spec("cover", R.drawable.ic_cover_24, "커버 보강"),
        Spec("settings", R.drawable.ic_settings_24, "설정"),
    )

    fun build(toolbar: MaterialToolbar, enabled: Set<String>, gridIsGrid: Boolean?, on: (String) -> Unit) {
        toolbar.menu.clear()
        ALL.forEachIndexed { i, s ->
            val icon = if (s.key == "toggle" && gridIsGrid != null)
                (if (gridIsGrid) R.drawable.ic_list_24 else R.drawable.ic_grid_24) else s.icon
            val mi = toolbar.menu.add(0, 100 + i, i, s.label)
            mi.setIcon(icon)
            mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            val en = s.key in enabled
            mi.isEnabled = en
            if (!en) runCatching { mi.icon?.mutate()?.alpha = 90 }   // 회색/흐림(못 쓰는 화면)
        }
        toolbar.setOnMenuItemClickListener { mi ->
            val idx = mi.itemId - 100
            if (idx in ALL.indices) on(ALL[idx].key)
            true
        }
    }
}
