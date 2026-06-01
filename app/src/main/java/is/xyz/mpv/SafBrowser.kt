package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

data class SafEntry(
    val name: String,
    val uri: Uri,
    val docId: String,
    val size: Long,
    val isDir: Boolean
)

// P2: SAF(트리) 폴더 브라우저 — USB OTG·내부 무관. 폴더는 풀폭 행, 영상은 임베드 커버 타일.
//   MediaStore 가 색인 못 하는 USB 저장소도 여기서 브라우징·재생.
class SafBrowserActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var treeUri: Uri
    private lateinit var docId: String
    private var entries: List<SafEntry> = emptyList()
    private var grid = true
    private var toggleItem: MenuItem? = null

    private var pendingUri: String? = null
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            rebuild()
            if (u != null && Playback.shouldAdvance(this, u)) {
                val vids = entries.filter { !it.isDir }
                val idx = vids.indexOfFirst { it.uri.toString() == u }
                if (idx >= 0 && idx + 1 in vids.indices) play(vids[idx + 1])
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)

        treeUri = Uri.parse(intent.getStringExtra("tree") ?: "")
        docId = intent.getStringExtra("docId") ?: DocumentsContract.getTreeDocumentId(treeUri)
        val title = intent.getStringExtra("title") ?: "폴더"

        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        grid = prefs.getBoolean("video_grid", true)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = title
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
            val list = queryChildren()
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

    private fun queryChildren(): List<SafEntry> {
        val out = ArrayList<SafEntry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            contentResolver.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val nm = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val isVid = mime.startsWith("video/") || isVideoName(nm)
                    if (!isDir && !isVid) continue
                    out.add(SafEntry(nm, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), id, size, isDir))
                }
            }
        } catch (_: Throwable) {
        }
        val dirs = out.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val vids = LibPrefs.sortSaf(this, out.filter { !it.isDir })
        return dirs + vids
    }

    private fun rebuild() {
        val span = spanCount()
        if (grid) {
            val glm = GridLayoutManager(this, span)
            glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (position < entries.size && entries[position].isDir) span else 1
            }
            recycler.layoutManager = glm
        } else {
            recycler.layoutManager = LinearLayoutManager(this)
        }
        recycler.adapter = SafAdapter(
            entries, grid,
            onFolder = { e ->
                startActivity(
                    Intent(this, SafBrowserActivity::class.java)
                        .putExtra("tree", treeUri.toString())
                        .putExtra("docId", e.docId)
                        .putExtra("title", e.name)
                )
            },
            onVideo = { e -> play(e) }
        )
    }

    private fun play(e: SafEntry) {
        pendingUri = e.uri.toString()
        playLauncher.launch(Playback.intentFor(this, e.uri.toString(), e.name.substringBeforeLast(".")))
    }

    companion object {
        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m4v", "webm", "mpg", "mpeg", "m2ts", "3gp"
        )

        fun isVideoName(name: String): Boolean {
            val ext = name.substringAfterLast(".", "").lowercase()
            return ext in VIDEO_EXT
        }
    }
}

// 폴더(풀폭 행) + 영상(커버 타일/행) 혼합 어댑터.
class SafAdapter(
    private val items: List<SafEntry>,
    private val grid: Boolean,
    private val onFolder: (SafEntry) -> Unit,
    private val onVideo: (SafEntry) -> Unit
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

    override fun getItemViewType(position: Int) = if (items[position].isDir) typeDir else typeVid

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == typeDir) {
            DirVH(inf.inflate(R.layout.item_saf_folder, parent, false))
        } else {
            val layout = if (grid) R.layout.item_video_grid else R.layout.item_video
            VidVH(inf.inflate(layout, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        val e = items[position]
        if (h is DirVH) {
            h.name.text = e.name
            h.itemView.setOnClickListener { onFolder(e) }
        } else if (h is VidVH) {
            val ctx = h.itemView.context
            h.thumbBox.visibility = if (LibPrefs.showThumb(ctx)) View.VISIBLE else View.GONE
            val szPart = if (LibPrefs.showSize(ctx)) MediaLibrary.fmtSize(e.size) else ""
            val extPart = if (LibPrefs.showExt(ctx)) e.name.substringAfterLast(".", "").uppercase() else ""
            h.meta.text = listOf(szPart, extPart).filter { it.isNotEmpty() }.joinToString("  ·  ")
            val fallback = e.name.substringBeforeLast(".")
            val pct = if (LibPrefs.showProgress(ctx)) Progress.percent(ctx, e.uri.toString()) else 0f
            if (pct > 0f) {
                h.progress.visibility = View.VISIBLE
                h.progress.progress = (pct * 100).toInt()
            } else {
                h.progress.visibility = View.GONE
            }
            if (LibPrefs.showDur(ctx)) {
                ThumbLoader.load(h.thumb, h.code, h.title, e.uri, fallback, durView = h.dur)
            } else {
                h.dur.visibility = View.GONE
                ThumbLoader.load(h.thumb, h.code, h.title, e.uri, fallback)
            }
            h.itemView.setOnClickListener { onVideo(e) }
            h.itemView.setOnLongClickListener {
                VideoActions.longPress(it, e.uri.toString(), fallback) { notifyItemChanged(h.bindingAdapterPosition) }
                true
            }
        }
    }
}
