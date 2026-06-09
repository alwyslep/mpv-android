package `is`.xyz.mpv

import android.view.MenuItem
import com.google.android.material.appbar.MaterialToolbar

// B-68(52): 모든 라이브러리 화면(플레이어 제외) 공통 툴바. 액션 합집합을 동일 순서로 깔고
//   IF_ROOM(폭 맞춰 자동 overflow ⋮) + 화면별 enabled 외 항목은 isEnabled=false + 아이콘 흐림(회색) + 커스텀 툴팁.
//   각 화면은 enabled 집합과 on(key) 핸들러만 선언. (gridIsGrid: toggle 아이콘 grid/list 표시용, null=비활성)
object LibToolbar {
    data class Spec(val key: String, val icon: Int, val label: String, val tip: String)

    val ALL = listOf(
        Spec("toggle", R.drawable.ic_list_24, "보기 전환", "그리드/목록 보기 전환"),
        Spec("search", R.drawable.ic_search_24, "검색", "품번·제목·배우로 검색"),
        Spec("classify", R.drawable.ic_people_24, "분류", "배우/스튜디오 등으로 분류 보기"),
        Spec("sort", R.drawable.ic_sort_24, "정렬", "정렬 기준·오름/내림차순 변경"),
        Spec("tune", R.drawable.ic_tune_24, "빠른 설정", "표시 항목·커버 크기 등 빠른 설정"),
        Spec("select", R.drawable.ic_check_circle_24, "선택(임베드)", "여러 영상을 직접 골라 일괄 임베드/이동/썸네일"),
        Spec("embedauto", R.drawable.ic_embed_24, "임베드(자동)", "메타·커버 없는 영상만 자동으로 일괄 임베드(선택 불필요)"),
        Spec("filter", R.drawable.ic_filter_alt_24dp, "필터", "해상도·상태·확장자·메타 조건으로 필터"),
        Spec("move", R.drawable.ic_folder_24, "이동", "선택 영상을 다른 폴더로 이동"),
        Spec("thumb", R.drawable.ic_image, "썸네일 지정", "선택 영상의 썸네일 위치를 일괄 지정"),
        Spec("heal", R.drawable.ic_healing_24, "PNG 복구", "PNG 디코이로 깨진 영상을 일괄 복구"),
        Spec("cover", R.drawable.ic_cover_24, "커버 보강", "커버 없는 영상에 hub 커버를 보강"),
        Spec("settings", R.drawable.ic_settings_24, "설정", "앱 설정 열기"),
        Spec("dup", R.drawable.ic_dup_24, "중복 찾기", "품번이 같은 중복 파일 찾기"),
        Spec("refresh", R.drawable.ic_refresh_24, "새로고침", "목록 새로고침"),
    )

    // 재배치-안전: key 로 메뉴 id(100+index) 조회. (applyIconTints 등 고정 id 의존 제거용)
    fun menuId(key: String): Int = ALL.indexOfFirst { it.key == key }.let { if (it >= 0) 100 + it else -1 }

    fun build(toolbar: MaterialToolbar, enabled: Set<String>, gridIsGrid: Boolean?, on: (String) -> Unit) {
        toolbar.menu.clear()
        val tips = HashMap<Int, CharSequence>()
        ALL.forEachIndexed { i, s ->
            val icon = if (s.key == "toggle" && gridIsGrid != null)
                (if (gridIsGrid) R.drawable.ic_list_24 else R.drawable.ic_grid_24) else s.icon
            val mi = toolbar.menu.add(0, 100 + i, i, s.label)
            mi.setIcon(icon)
            mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)   // 전부 아이콘 표시(overflow 접지 않음, 사용자 요구)
            val en = s.key in enabled
            mi.isEnabled = en
            if (!en) runCatching { mi.icon?.mutate()?.alpha = 90 }   // 회색/흐림(못 쓰는 화면)
            tips[100 + i] = s.tip
        }
        runCatching { Utils.tipMenu(toolbar, tips) }   // 커스텀 툴팁(long-press·hover)
        toolbar.setOnMenuItemClickListener { mi ->
            val idx = mi.itemId - 100
            if (idx in ALL.indices) on(ALL[idx].key)
            true
        }
    }
}
