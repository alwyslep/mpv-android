package `is`.xyz.mpv

import android.os.Bundle
import android.view.MenuItem
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

    private var pendingUri: String? = null
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            pendingUri?.let { Playback.onResult(this, it, res.data) }
            rebuild()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)

        folderPath = intent.getStringExtra("path") ?: ""
        val name = intent.getStringExtra("name") ?: "폴더"

        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        grid = prefs.getBoolean("video_grid", true)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = name
        toolbar.setNavigationOnClickListener { finish() }
        toggleItem = toolbar.menu.add(0, 1, 0, "보기 전환").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, 2, 1, "빠른 설정").apply {
            setIcon(R.drawable.ic_tune_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
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
            }
            true
        }

        recycler = findViewById(R.id.recycler)
        rebuild()
        reload()
    }

    private fun reload() {
        Thread {
            val list = LibPrefs.sortVids(this, MediaLibrary.videosIn(MediaLibrary.queryVideos(this), folderPath))
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

    private fun spanCount(): Int {
        val dp = resources.configuration.screenWidthDp
        return maxOf(2, dp / 170)
    }

    private fun rebuild() {
        recycler.layoutManager =
            if (grid) GridLayoutManager(this, spanCount()) else LinearLayoutManager(this)
        recycler.adapter = VideoAdapter(vids, grid) { v -> play(v) }
    }

    private fun play(v: Vid) {
        pendingUri = v.uri.toString()
        playLauncher.launch(Playback.intentFor(this, v.uri.toString(), v.name))
    }
}
