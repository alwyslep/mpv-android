package `is`.xyz.mpv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

// P2: NextPlayer식 홈 — MediaStore 비디오 폴더 목록 + SpeedDial FAB. 런처 진입점.
class MediaLibraryActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var fabMenu: View
    private lateinit var selCtl: SelectionController   // jembed/이동 선택모드 (공통 컨트롤러)
    private var selMenuItem: MenuItem? = null
    private var moveMenuItem: MenuItem? = null
    private var thumbMenuItem: MenuItem? = null
    private var seeded = false   // 번들 스크립트/conf 시드 1회 플래그

    // 로컬/USB 폴더 1개 선택 → 내 SAF 타일 브라우저로 진입(OS 선택기 대신).
    private val openTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: Exception) {
                }
                SafTrees.add(this, it.toString())  // 통합 검색 인덱싱 대상에 등록
                val title = (it.lastPathSegment ?: getString(R.string.qs_folder)).substringAfterLast(":").substringAfterLast("/")
                startActivity(
                    Intent(this, SafBrowserActivity::class.java)
                        .putExtra("tree", it.toString())
                        .putExtra("title", title)
                )
            }
        }

    private val reqPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    // 재생 결과(위치/길이) 기록 — 이어보기·진행률
    private var pendingUri: String? = null
    private var scrollState: android.os.Parcelable? = null   // 31: 스크롤 위치 — onPause 저장 + Bundle 영속화(Activity 파괴 대비)
    private var homeVids: List<Pair<String, String>> = emptyList()  // 자동 다음재생용 (uri, name)
    private var playIndex = -1
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            if (u != null && Playback.shouldAdvance(this, u, homeVids.find { it.first == u }?.second) && playIndex + 1 in homeVids.indices) {
                val (nu, nn) = homeVids[playIndex + 1]
                play(nu, nn)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_library)
        AuroraDrawable.apply(this)
        @Suppress("DEPRECATION")
        savedInstanceState?.getParcelable<android.os.Parcelable>("ml_scroll")?.let { scrollState = it }  // 31: 파괴→재생성 복원

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.menu.add(0, 2, 0, getString(R.string.lbl_search)).apply {
            setIcon(R.drawable.ic_search_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 4, 1, getString(R.string.menu_classify)).apply {
            setIcon(R.drawable.ic_people_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 9, 2, "정렬").apply {
            setIcon(R.drawable.ic_sort_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 3, 3, getString(R.string.qs_title)).apply {
            setIcon(R.drawable.ic_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 1, 4, getString(R.string.lbl_settings)).apply {
            setIcon(R.drawable.ic_settings_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        // B-64(39): ⋮ 안 4개를 툴바 밖으로(아이콘+ALWAYS). 선택/이동/썸네일은 videos 모드만 isVisible.
        selMenuItem = toolbar.menu.add(0, 5, 3, "선택(임베드)").apply {
            setIcon(R.drawable.ic_check_circle_24); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 6, 4, "필터").apply { setIcon(R.drawable.ic_filter_alt_24dp); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        moveMenuItem = toolbar.menu.add(0, 7, 5, "이동").apply { setIcon(R.drawable.ic_folder_24); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        thumbMenuItem = toolbar.menu.add(0, 8, 6, "썸네일 지정").apply { setIcon(R.drawable.ic_image); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> startActivity(Intent(this, SearchActivity::class.java))
                4 -> startActivity(Intent(this, BrowseActivity::class.java))
                3 -> QuickSettings.show(this) { load() }
                9 -> {
                    val m = LibPrefs.viewMode(this)
                    val sc = when (m) { "videos" -> "home_videos"; "tree" -> "home_tree"; else -> "home_folders" }
                    SortDialog.show(this, sc, m == "folder") { load() }
                }
                1 -> startActivity(Intent(this, `is`.xyz.mpv.preferences.PreferenceActivity::class.java))
                5 -> selCtl.enter(showEmbed = true, showMove = false)
                6 -> FilterSheet.show(this) { load() }
                7 -> selCtl.enter(showEmbed = false, showMove = true)
                8 -> selCtl.enter(showEmbed = false, showMove = false, showThumb = true)
            }
            true
        }

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        empty = findViewById(R.id.empty)
        selCtl = SelectionController(this, findViewById(R.id.sel_bar), findViewById(R.id.sel_count)) { load() }
        findViewById<View>(R.id.sel_all).setOnClickListener { selCtl.selectAll() }
        findViewById<View>(R.id.sel_cancel).setOnClickListener { selCtl.exit() }
        findViewById<View>(R.id.sel_embed).setOnClickListener { selCtl.embedBatch() }
        findViewById<View>(R.id.sel_move).setOnClickListener { selCtl.moveBatch() }
        findViewById<View>(R.id.sel_thumb).setOnClickListener { selCtl.thumbBatch() }

        setupFab()
        ensurePermissionThenLoad()
    }

    override fun onResume() {
        super.onResume()
        // 재생 후 복귀 시 최근목록/신규영상 반영
        if (hasMediaAccess()) load()
    }

    override fun onPause() {
        super.onPause()   // 31: 재생/이탈 전 스크롤 위치 저장
        recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)   // 31: Activity 파괴 대비 영속화
        scrollState?.let { outState.putParcelable("ml_scroll", it) }
    }

    private fun setupFab() {
        fabMenu = findViewById(R.id.fab_menu)
        findViewById<FloatingActionButton>(R.id.fab_main).setOnClickListener {
            fabMenu.visibility = if (fabMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fab_url).setOnClickListener {
            fabMenu.visibility = View.GONE; showUrlDialog()
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fab_local).setOnClickListener {
            fabMenu.visibility = View.GONE
            try {
                openTree.launch(null)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_no_folder_picker), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<ExtendedFloatingActionButton>(R.id.fab_recent).setOnClickListener {
            fabMenu.visibility = View.GONE; showRecents()
        }
    }

    private fun hasMediaAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
            return true
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionThenLoad() {
        if (hasMediaAccess()) {
            load()
            return
        }
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        reqPerm.launch(perm)
    }

    private fun spanCount(): Int = LibPrefs.spanCount(this)

    private fun load() {
        val mode = LibPrefs.viewMode(this)
        val grid = LibPrefs.grid(this)
        // 31: 어댑터에 아이템이 있을 때만 현재 위치로 갱신(재생성 직후 빈 LM 이 0 으로 덮어쓰는 것 방지).
        //     필드 기반 → onPause 저장 + Bundle 복원 모두 활용. 메뉴 액션(정렬/필터) 시엔 현재 위치 유지.
        if ((recycler.adapter?.itemCount ?: 0) > 0)
            recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
        val savedScroll = scrollState
        val showSel = mode == "videos"   // 선택/이동은 영상 평면 모드만 (폴더/트리는 폴더 진입 후)
        selMenuItem?.isVisible = showSel
        moveMenuItem?.isVisible = showSel
        thumbMenuItem?.isVisible = showSel
        if (mode != "videos") { selCtl.exit(); selCtl.unbind() }
        DurationHub.fetchAsync(this)   // v6: hub 길이맵 1회 채움(길이 불일치 마커용)
        ResolutionHub.fetchAsync(this) // 4: hub 해상도맵 1회 채움(해상도 불일치 마커용)
        MetaHub.fetchAsync(this)       // 필터: hub 메타맵 1회 채움(메타 필터용)
        Thread {
            if (!seeded) { Utils.seedConfig(this); seeded = true }   // 번들 기본 스크립트/conf 복원(없을 때만)
            val allVids = MediaLibrary.queryVideos(this)
            if (mode == "videos") {
                val vids = LibPrefs.sortVids(this, "home_videos", allVids).filter { FilterEngine.passes(this, it) }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    homeVids = vids.map { it.uri.toString() to it.name }
                    empty.visibility = if (vids.isEmpty()) View.VISIBLE else View.GONE
                    recycler.layoutManager =
                        if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
                    val va = VideoAdapter(vids.toMutableList(), grid) { v -> play(v.uri.toString(), v.name) }
                    selCtl.bind(va)
                    recycler.adapter = va
                    recycler.layoutManager?.onRestoreInstanceState(savedScroll)
                }
            } else if (mode == "tree") {
                val root = MediaLibrary.treeRoot(allVids)
                val children = MediaLibrary.treeChildren(this, allVids, root)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    empty.visibility = if (children.isEmpty()) View.VISIBLE else View.GONE
                    if (grid) {
                        val glm = GridLayoutManager(this, spanCount())
                        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                            override fun getSpanSize(position: Int): Int =
                                if (position < children.size && children[position].dirPath != null) spanCount() else 1
                        }
                        recycler.layoutManager = glm
                    } else {
                        recycler.layoutManager = LinearLayoutManager(this)
                    }
                    homeVids = children.mapNotNull { it.vid }.map { it.uri.toString() to it.name }
                    recycler.adapter = TreeAdapter(children.toMutableList(), grid,
                        onDir = { e ->
                            startActivity(
                                Intent(this, TreeActivity::class.java)
                                    .putExtra("dir", e.dirPath)
                                    .putExtra("title", e.name)
                            )
                        },
                        onVideo = { e -> e.vid?.let { play(it.uri.toString(), it.name) } }
                    )
                    recycler.layoutManager?.onRestoreInstanceState(savedScroll)
                }
            } else {
                // folder
                val folds = LibPrefs.sortFolds(this, "home_folders", MediaLibrary.folders(allVids))
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    empty.visibility = if (folds.isEmpty()) View.VISIBLE else View.GONE
                    recycler.layoutManager =
                        if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
                    recycler.adapter = FolderAdapter(folds, grid) { f ->
                        startActivity(
                            Intent(this, FolderVideosActivity::class.java)
                                .putExtra("path", f.path)
                                .putExtra("name", f.name)
                        )
                    }
                    recycler.layoutManager?.onRestoreInstanceState(savedScroll)
                }
            }
        }.start()
    }

    private fun play(uri: String, title: String) {
        playIndex = homeVids.indexOfFirst { it.first == uri }
        pendingUri = uri
        playLauncher.launch(Playback.intentFor(this, uri, title))
    }

    private fun showUrlDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.hint_stream_url)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lbl_open_network_stream))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_play)) { _, _ ->
                val u = input.text.toString().trim()
                if (u.isNotEmpty()) play(u, u)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun showRecents() {
        val recents = Recents.list(this)
        if (recents.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_recent), Toast.LENGTH_SHORT).show()
            return
        }
        val titles = recents.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lbl_recent))
            .setItems(titles) { _, which ->
                val (uri, title) = recents[which]
                play(uri, title)
            }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selCtl.isActive) selCtl.exit() else super.onBackPressed()
    }
}
