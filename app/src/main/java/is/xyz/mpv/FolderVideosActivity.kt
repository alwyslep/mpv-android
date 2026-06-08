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
    private lateinit var toolbar: MaterialToolbar
    private var folderPath = ""
    private lateinit var selCtl: SelectionController

    private var pendingUri: String? = null
    private var playIndex = -1
    private var scrollState: android.os.Parcelable? = null   // 31: 스크롤 보존(onPause+Bundle)
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            // B-68: 삭제/이동으로 제거됐으면 리스트 갱신 전에 다음 영상 캡처(인덱스 당겨짐 방지) → advance.
            val rmNext = if (Playback.wasRemoved(res.data) && u != null) {
                val i = vids.indexOfFirst { it.uri.toString() == u }; if (i >= 0) vids.getOrNull(i + 1) else null
            } else null
            rebuild()
            if (rmNext != null) play(rmNext)
            // 자동 다음 재생: 방금 작품을 끝까지 봤고(다 봄) 다음이 있으면
            else if (u != null && Playback.shouldAdvance(this, u, vids.find { it.uri.toString() == u }?.name) && playIndex + 1 in vids.indices) {
                play(vids[playIndex + 1])
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)
        AuroraDrawable.apply(this)
        Utils.applyRtl(this)   // 58: RTL 레이아웃 토글
        @Suppress("DEPRECATION")
        savedInstanceState?.getParcelable<android.os.Parcelable>("scroll")?.let { scrollState = it }  // 31

        folderPath = intent.getStringExtra("path") ?: ""
        val name = intent.getStringExtra("name") ?: getString(R.string.qs_folder)

        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        grid = prefs.getBoolean("video_grid", true)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }
        MetaHub.fetchAsync(this)
        setupToolbar()   // B-68(52): 공통 LibToolbar

        recycler = findViewById(R.id.recycler)
        ResolutionHub.fetchAsync(this)  // 48: hub 해상도맵(폴더 직진입 시도 채움 — fetchHubRes/마커용)
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

    // B-68(52): 공통 12아이콘 툴바. 폴더 화면 활성 = toggle/sort/tune/select/filter/move/thumb/heal (나머지 회색).
    private fun setupToolbar() {
        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        LibToolbar.build(toolbar, setOf("toggle", "sort", "tune", "select", "filter", "move", "thumb", "heal", "dup", "refresh"), grid) { key ->
            fun folderVids() = LibPrefs.sortVids(this, "folder", MediaLibrary.videosIn(MediaLibrary.queryVideos(this), folderPath)).map { it.uri to it.name }
            when (key) {
                "refresh" -> reload()
                "toggle" -> { grid = !grid; prefs.edit().putBoolean("video_grid", grid).apply(); setupToolbar(); rebuild() }
                "sort" -> SortDialog.show(this, "folder", false) { reload() }
                "tune" -> QuickSettings.show(this) { grid = LibPrefs.grid(this); setupToolbar(); reload() }
                "select" -> selCtl.enter(showEmbed = true, showMove = false)
                "filter" -> FilterSheet.show(this) { reload() }
                "move" -> selCtl.enter(showEmbed = false, showMove = true)
                "thumb" -> selCtl.enter(showEmbed = false, showMove = false, showThumb = true)
                "heal" -> VideoHeal.healFolderConfirm(this, folderVids()) { reload() }
                "dup" -> DupFinder.show(this, folderVids())
            }
        }
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
