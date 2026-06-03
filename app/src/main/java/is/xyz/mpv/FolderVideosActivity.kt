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
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            rebuild()
            // 자동 다음 재생: 방금 작품을 끝까지 봤고(다 봄) 다음이 있으면
            if (u != null && Playback.shouldAdvance(this, u) && playIndex + 1 in vids.indices) {
                play(vids[playIndex + 1])
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)
        AuroraDrawable.apply(this)

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
        toolbar.menu.add(0, 2, 1, getString(R.string.qs_title)).apply {
            setIcon(R.drawable.ic_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 3, 2, "선택(임베드)").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        toolbar.menu.add(0, 4, 3, "필터").apply { setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER) }
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
                3 -> selCtl.enter()
                4 -> FilterSheet.show(this) { reload() }
            }
            true
        }

        recycler = findViewById(R.id.recycler)
        selCtl = SelectionController(this, findViewById(R.id.sel_bar), findViewById<TextView>(R.id.sel_count)) { reload() }
        findViewById<View>(R.id.sel_all).setOnClickListener { selCtl.selectAll() }
        findViewById<View>(R.id.sel_cancel).setOnClickListener { selCtl.exit() }
        findViewById<View>(R.id.sel_embed).setOnClickListener { selCtl.embedBatch() }
        findViewById<View>(R.id.sel_move).setOnClickListener { selCtl.moveBatch() }
        rebuild()
        reload()
    }

    private fun reload() {
        Thread {
            val list = LibPrefs.sortVids(this, MediaLibrary.videosIn(MediaLibrary.queryVideos(this), folderPath))
                .filter { FilterEngine.passes(this, it) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                vids = list
                rebuild()
            }
        }.start()
    }

    private fun updateToggleIcon() {
        // 현재 그리드면 '목록 전환' 아이콘, 목록이면 '그리드 전환' 아이콘
        toggleItem?.setIcon(if (grid) R.drawable.ic_list_24 else R.drawable.ic_grid_24)
    }

    private fun spanCount(): Int = LibPrefs.spanCount(this)

    private fun rebuild() {
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
        val va = VideoAdapter(vids, grid) { v -> play(v) }
        selCtl.bind(va)
        recycler.adapter = va
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
