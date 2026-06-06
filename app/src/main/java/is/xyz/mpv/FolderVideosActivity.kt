package `is`.xyz.mpv

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

// P2: 폴더 진입 → 해당 폴더의 비디오. 목록/타일 토글, 타일은 임베드 커버+제목. 탭 시 곧장 재생.
class FolderVideosActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private var vids: List<Vid> = emptyList()
    private var grid = true
    private var toggleItem: MenuItem? = null
    private var folderPath = ""
    private lateinit var selCtl: SelectionController

    private var pendingUri: String? = null
    private var playIndex = -1
    private var scrollState: android.os.Parcelable? = null   // 31: 스크롤 보존(onPause+Bundle)
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            rebuild()
            // 자동 다음 재생: 방금 작품을 끝까지 봤고(다 봄) 다음이 있으면
            if (u != null && Playback.shouldAdvance(this, u, vids.find { it.uri.toString() == u }?.name) && playIndex + 1 in vids.indices) {
                play(vids[playIndex + 1])
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)
        AuroraDrawable.apply(this)
        @Suppress("DEPRECATION")
        savedInstanceState?.getParcelable<android.os.Parcelable>("scroll")?.let { scrollState = it }  // 31

        folderPath = intent.getStringExtra("path") ?: ""
        val name = intent.getStringExtra("name") ?: getString(R.string.qs_folder)

        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        grid = prefs.getBoolean("video_grid", true)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }
        toggleItem = toolbar.menu.add(0, 1, 0, getString(R.string.toggle_view)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 7, 1, "정렬").apply {
            setIcon(R.drawable.ic_sort_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 2, 2, getString(R.string.qs_title)).apply {
            setIcon(R.drawable.ic_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        // B-64(39): ⋮ 안 4개를 툴바 밖으로(아이콘+ALWAYS)
        toolbar.menu.add(0, 3, 3, "선택(임베드)").apply {
            setIcon(R.drawable.ic_check_circle_24); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 4, 3, "필터").apply { setIcon(R.drawable.ic_filter_alt_24dp); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        toolbar.menu.add(0, 5, 4, "이동").apply { setIcon(R.drawable.ic_folder_24); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        toolbar.menu.add(0, 6, 5, "썸네일 지정").apply { setIcon(R.drawable.ic_image); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        // B-64(47): 메뉴 툴팁 일괄
        mapOf(
            1 to "그리드/목록 보기 전환", 7 to "정렬 기준·오름/내림차순 변경",
            2 to "표시 항목·커버 크기 등 빠른 설정", 3 to "여러 영상을 골라 일괄 임베드/이동/썸네일 지정",
            4 to "해상도·상태·확장자·메타 조건으로 필터", 5 to "선택 영상을 다른 폴더로 이동",
            6 to "선택 영상의 썸네일 위치를 일괄 지정"
        ).forEach { (mid, t) -> toolbar.menu.findItem(mid)?.let { Utils.tipItem(it, this, t) } }
        MetaHub.fetchAsync(this)
        updateToggleIcon()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    grid = !grid
                    prefs.edit().putBoolean("video_grid", grid).apply()
                    updateToggleIcon()
                    rebuild()
                }
                2 -> QuickSettings.show(this) {
                    grid = LibPrefs.grid(this); updateToggleIcon(); reload()
                }
                7 -> SortDialog.show(this, "folder", false) { reload() }
                3 -> selCtl.enter(showEmbed = true, showMove = false)
                4 -> FilterSheet.show(this) { reload() }
                5 -> selCtl.enter(showEmbed = false, showMove = true)
                6 -> selCtl.enter(showEmbed = false, showMove = false, showThumb = true)
            }
            true
        }

        recycler = findViewById(R.id.recycler)
        Utils.mirrorChildrenForLeftScrollbar(recycler)  // 49: 좌측 스크롤바(XML scaleX=-1 보완)
        selCtl = SelectionController(this, findViewById(R.id.sel_bar), findViewById<TextView>(R.id.sel_count)) { reload() }
        findViewById<View>(R.id.sel_all).setOnClickListener { selCtl.selectAll() }
        findViewById<View>(R.id.sel_thumb).setOnClickListener { selCtl.thumbBatch() }
        findViewById<View>(R.id.sel_cancel).setOnClickListener { selCtl.exit() }
        findViewById<View>(R.id.sel_embed).setOnClickListener { selCtl.embedBatch() }
        findViewById<View>(R.id.sel_move).setOnClickListener { selCtl.moveBatch() }
        rebuild()
        reload()
    }

    private fun reload() {
        findViewById<View>(R.id.loading_bar)?.visibility = View.VISIBLE   // B-64(45): 필터/로드 진행 표시
        Thread {
            val list = LibPrefs.sortVids(this, "folder", MediaLibrary.videosIn(MediaLibrary.queryVideos(this), folderPath))
                .filter { FilterEngine.passes(this, it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                vids = list
                rebuild()
                updateTitle()
                findViewById<View>(R.id.loading_bar)?.visibility = View.GONE
            }
        }.start()
    }

    // B-63(34): 폴더명 옆에 총 영상 개수를 작게(회색) 표시. (선택 모드 개수는 SelectionController.update)
    private fun updateTitle() {
        val name = intent.getStringExtra("name") ?: getString(R.string.qs_folder)
        val suffix = "   ${"%,d".format(vids.size)}편"   // B-64(44): 천단위 콤마
        val sp = android.text.SpannableString(name + suffix)
        val s = name.length; val e = name.length + suffix.length
        sp.setSpan(android.text.style.RelativeSizeSpan(0.72f), s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.ForegroundColorSpan(0xFFAAAAAA.toInt()), s, e, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<MaterialToolbar>(R.id.toolbar).title = sp
    }

    private fun updateToggleIcon() {
        // 현재 그리드면 '목록 전환' 아이콘, 목록이면 '그리드 전환' 아이콘
        toggleItem?.setIcon(if (grid) R.drawable.ic_list_24 else R.drawable.ic_grid_24)
    }

    private fun spanCount(): Int = LibPrefs.spanCount(this)

    private fun rebuild() {
        // 31: LM 재생성 전(기존, 아이템 있을 때만) 위치 저장 → 필드. 재생성/복귀 모두 보존.
        if ((recycler.adapter?.itemCount ?: 0) > 0)
            recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
        val va = VideoAdapter(vids.toMutableList(), grid) { v -> play(v) }
        selCtl.bind(va)
        recycler.adapter = va
        recycler.layoutManager?.onRestoreInstanceState(scrollState)
    }

    override fun onPause() {
        super.onPause()
        if (::recycler.isInitialized) recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        scrollState?.let { outState.putParcelable("scroll", it) }
    }

    private fun play(v: Vid) {
        playIndex = vids.indexOfFirst { it.uri == v.uri }
        pendingUri = v.uri.toString()
        playLauncher.launch(Playback.intentFor(this, v.uri.toString(), v.name))
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selCtl.isActive) selCtl.exit() else super.onBackPressed()
    }
}
