package `is`.xyz.mpv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

// P2: 트리 모드 — MediaStore 영상 경로로 만든 실제 디렉터리 계층. 폴더=풀폭 행, 영상=타일.
class TreeActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private var entries: List<TreeEntry> = emptyList()
    private var grid = true
    private var toggleItem: MenuItem? = null
    private var pendingUri: String? = null
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            pendingUri?.let { Playback.onResult(this, it, res.data) }
            rebuild()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)

        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        grid = prefs.getBoolean("video_grid", true)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra("title") ?: "트리"
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
            val vids = MediaLibrary.queryVideos(this)
            val dir = intent.getStringExtra("dir") ?: MediaLibrary.treeRoot(vids)
            val list = MediaLibrary.treeChildren(vids, dir)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                entries = list
                rebuild()
            }
        }.start()
    }

    private fun updateToggleIcon() {
        toggleItem?.setIcon(if (grid) R.drawable.ic_list_24 else R.drawable.ic_grid_24)
    }

    private fun spanCount(): Int = maxOf(2, resources.configuration.screenWidthDp / 170)

    private fun rebuild() {
        val span = spanCount()
        if (grid) {
            val glm = GridLayoutManager(this, span)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (position < entries.size && entries[position].dirPath != null) span else 1
            }
            recycler.layoutManager = glm
        } else {
            recycler.layoutManager = LinearLayoutManager(this)
        }
        recycler.adapter = TreeAdapter(
            entries, grid,
            onDir = { e ->
                startActivity(
                    android.content.Intent(this, TreeActivity::class.java)
                        .putExtra("dir", e.dirPath)
                        .putExtra("title", e.name)
                )
            },
            onVideo = { e -> e.vid?.let { play(it) } }
        )
    }

    private fun play(v: Vid) {
        pendingUri = v.uri.toString()
        playLauncher.launch(Playback.intentFor(this, v.uri.toString(), v.name))
    }
}

class TreeAdapter(
    private val items: List<TreeEntry>,
    private val grid: Boolean,
    private val onDir: (TreeEntry) -> Unit,
    private val onVideo: (TreeEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val typeDir = 0
    private val typeVid = 1

    class DirVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
    }

    class VidVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumbBox: View = v.findViewById(R.id.thumb_box)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val code: TextView = v.findViewById(R.id.code)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun getItemViewType(position: Int) =
        if (items[position].dirPath != null) typeDir else typeVid

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == typeDir) {
            DirVH(inf.inflate(R.layout.item_saf_folder, parent, false))
        } else {
            VidVH(inf.inflate(if (grid) R.layout.item_video_grid else R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val e = items[position]
        if (h is DirVH) {
            h.name.text = e.name
            h.itemView.setOnClickListener { onDir(e) }
        } else if (h is VidVH) {
            val v = e.vid!!
            val ctx = h.itemView.context
            h.thumbBox.visibility = if (LibPrefs.showThumb(ctx)) View.VISIBLE else View.GONE
            val res = if (v.height > 0 && LibPrefs.showRes(ctx)) "${v.height}p" else ""
            val sz = if (LibPrefs.showSize(ctx)) MediaLibrary.fmtSize(v.size) else ""
            val ext = if (LibPrefs.showExt(ctx)) v.nameExt.substringAfterLast(".", "").uppercase() else ""
            h.meta.text = listOf(res, sz, ext).filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (LibPrefs.showDur(ctx) && v.durationMs > 0) {
                h.dur.visibility = View.VISIBLE
                h.dur.text = MediaLibrary.fmtDur(v.durationMs)
            } else {
                h.dur.visibility = View.GONE
            }
            val pct = if (LibPrefs.showProgress(ctx)) Progress.percent(ctx, v.uri.toString()) else 0f
            if (pct > 0f) {
                h.progress.visibility = View.VISIBLE
                h.progress.progress = (pct * 100).toInt()
            } else {
                h.progress.visibility = View.GONE
            }
            ThumbLoader.load(h.thumb, h.code, h.title, v.uri, v.name)
            h.itemView.setOnClickListener { onVideo(e) }
            h.itemView.setOnLongClickListener { VideoDetailActivity.open(ctx, v.uri.toString(), v.name); true }
        }
    }
}
