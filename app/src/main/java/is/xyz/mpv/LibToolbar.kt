package `is`.xyz.mpv

import android.content.Context
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
        Spec("dup", R.drawable.ic_dup_24, "중복 찾기", "품번이 같은 중복 파일 찾기"),
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
        Spec("refresh", R.drawable.ic_refresh_24, "새로고침", "목록 새로고침"),   // 항상 맨 끝
    )

    // 재배치-안전: key 로 메뉴 id(100+index) 조회. (applyIconTints 등 고정 id 의존 제거용)
    fun menuId(key: String): Int = ALL.indexOfFirst { it.key == key }.let { if (it >= 0) 100 + it else -1 }

    // 55b: 사용자 정의 아이콘 순서(key 목록). 저장순 + 미저장(신규) key 뒤에. id 는 ALL 캐논 인덱스 유지.
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("media_library", Context.MODE_PRIVATE)
    fun order(ctx: Context): List<String> {
        val saved = prefs(ctx).getString("toolbar_order", null)
            ?.split(",")?.filter { k -> ALL.any { it.key == k } } ?: emptyList()
        return saved + ALL.map { it.key }.filter { it !in saved }
    }
    fun setOrder(ctx: Context, keys: List<String>) =
        prefs(ctx).edit().putString("toolbar_order", keys.joinToString(",")).apply()
    fun resetOrder(ctx: Context) = prefs(ctx).edit().remove("toolbar_order").apply()

    fun build(toolbar: MaterialToolbar, enabled: Set<String>, gridIsGrid: Boolean?, on: (String) -> Unit) {
        toolbar.menu.clear()
        val tips = HashMap<Int, CharSequence>()
        // 표시 순서는 사용자 정의(order), id 는 ALL 캐논 인덱스(100+ci) 유지 → 핸들러/menuId 불변.
        val ord = order(toolbar.context)
        JavDiag.log("order", "build apply=[${ord.take(6).joinToString(",")}...]")
        ord.forEachIndexed { pos, key ->
            val ci = ALL.indexOfFirst { it.key == key }
            if (ci < 0) return@forEachIndexed
            val s = ALL[ci]
            val icon = if (s.key == "toggle" && gridIsGrid != null)
                (if (gridIsGrid) R.drawable.ic_list_24 else R.drawable.ic_grid_24) else s.icon
            val mi = toolbar.menu.add(0, 100 + ci, pos, s.label)   // order=pos(표시순), itemId=캐논
            mi.setIcon(icon)
            mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)   // 전부 아이콘 표시(overflow 접지 않음, 사용자 요구)
            val en = s.key in enabled
            mi.isEnabled = en
            if (!en) runCatching { mi.icon?.mutate()?.alpha = 90 }   // 회색/흐림(못 쓰는 화면)
            tips[100 + ci] = s.tip
        }
        runCatching { Utils.tipMenu(toolbar, tips) }   // 커스텀 툴팁(hover). 롱프레스는 아래 드래그가 가져감.
        toolbar.setOnMenuItemClickListener { mi ->
            val idx = mi.itemId - 100
            if (idx in ALL.indices) on(ALL[idx].key)
            true
        }
        // 55b: 툴바에서 직접 드래그 재배치 — 아이콘 길게눌러 끌어 다른 아이콘에 놓으면 순서 교체→저장→재빌드.
        toolbar.post { attachDrag(toolbar, enabled, gridIsGrid, on) }
    }

    @Suppress("DEPRECATION")
    private fun attachDrag(toolbar: MaterialToolbar, enabled: Set<String>, gridIsGrid: Boolean?, on: (String) -> Unit) {
        val ctx = toolbar.context
        order(ctx).forEach { key ->
            val ci = ALL.indexOfFirst { it.key == key }
            val v = if (ci >= 0) toolbar.findViewById<android.view.View>(100 + ci) else null
            v ?: return@forEach
            v.setOnLongClickListener {
                val data = android.content.ClipData.newPlainText("k", key)
                val shadow = android.view.View.DragShadowBuilder(v)
                if (android.os.Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(data, shadow, key, 0)
                else @Suppress("DEPRECATION") v.startDrag(data, shadow, key, 0)
                JavDiag.log("order", "drag start: $key")
                true
            }
            v.setOnDragListener { _, e ->
                when (e.action) {
                    android.view.DragEvent.ACTION_DROP -> {
                        val dragged = e.localState as? String
                        if (dragged != null && dragged != key) {
                            val ks = order(ctx).toMutableList()
                            ks.remove(dragged)
                            val ti = ks.indexOf(key).coerceAtLeast(0)
                            ks.add(ti, dragged)
                            setOrder(ctx, ks)
                            JavDiag.log("order", "drop $dragged → 앞 $key  새순서=[${ks.take(6).joinToString(",")}...]")
                            build(toolbar, enabled, gridIsGrid, on)   // 새 순서로 재빌드
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_STARTED,
                    android.view.DragEvent.ACTION_DRAG_ENTERED,
                    android.view.DragEvent.ACTION_DRAG_LOCATION,
                    android.view.DragEvent.ACTION_DRAG_EXITED,
                    android.view.DragEvent.ACTION_DRAG_ENDED -> true
                    else -> false
                }
            }
        }
    }
}
