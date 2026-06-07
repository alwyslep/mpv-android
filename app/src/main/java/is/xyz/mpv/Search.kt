package `is`.xyz.mpv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class SearchItem(
    val name: String,        // 확장자 제외(=품번 후보)
    val uri: Uri,
    val folder: String,
    val durationMs: Long,
    val size: Long
)

// 통합 검색 인덱스 — 내부 MediaStore + 저장된 SAF 트리(재귀). 세션 동안 캐시.
object SearchIndex {
    @Volatile
    private var items: List<SearchItem>? = null

    fun cached(): List<SearchItem>? = items
    fun clear() { items = null }

    fun build(ctx: Context): List<SearchItem> {
        // v5: queryVideos 가 내부(MediaStore) + 외부(SAF 트리)를 모두 포함 → 여기선 매핑만.
        val out = ArrayList<SearchItem>()
        for (v in MediaLibrary.queryVideos(ctx)) {
            out.add(SearchItem(v.name, v.uri, v.folderName, v.durationMs, v.size))
        }
        items = out
        return out
    }
}

class SearchAdapter(
    private var items: List<SearchItem>,
    private val grid: Boolean,
    private val onClick: (SearchItem) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumbBox: View = v.findViewById(R.id.thumb_box)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val code: TextView = v.findViewById(R.id.code)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    fun update(newItems: List<SearchItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (grid) R.layout.item_video_grid else R.layout.item_video
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        val ctx = h.itemView.context
        h.thumbBox.visibility = if (LibPrefs.showThumb(ctx)) View.VISIBLE else View.GONE
        val pct = if (LibPrefs.showProgress(ctx)) Progress.percent(ctx, item.uri.toString(), item.name) else 0f
        if (pct > 0f) {
            h.progress.visibility = View.VISIBLE
            h.progress.progress = (pct * 100).toInt()
        } else {
            h.progress.visibility = View.GONE
        }
        val sizePart = if (LibPrefs.showSize(ctx)) MediaLibrary.fmtSize(item.size) else ""
        h.meta.text = listOf(item.folder, sizePart).filter { s -> s.isNotEmpty() }
            .joinToString("  ·  ")
        if (!LibPrefs.showDur(ctx)) {
            h.dur.visibility = View.GONE
            ThumbLoader.load(h.thumb, h.code, h.title, item.uri, item.name)
        } else if (item.durationMs > 0) {
            h.dur.visibility = View.VISIBLE
            h.dur.text = MediaLibrary.fmtDur(item.durationMs)
            ThumbLoader.load(h.thumb, h.code, h.title, item.uri, item.name)
        } else {
            ThumbLoader.load(h.thumb, h.code, h.title, item.uri, item.name, durView = h.dur)
        }
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnLongClickListener {
            VideoActions.longPress(it, item.uri.toString(), item.name, onChanged = { notifyItemChanged(h.bindingAdapterPosition) })
            true
        }
    }
}

// NextPlayer식 "동영상·폴더 검색" 화면. 통합 인덱스에서 품번/파일명 + 캐시된 한글제목 매칭.
class SearchActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var adapter: SearchAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var grid = true

    private var pendingUri: String? = null
    private var results: List<SearchItem> = emptyList()
    private var playlist: List<SearchItem> = emptyList()
    private var playIndex = -1
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            // B-68: 삭제/이동으로 제거됐으면 재조회 전에 다음 영상 캡처 → advance.
            val rmNext = if (Playback.wasRemoved(res.data) && u != null) {
                val i = playlist.indexOfFirst { it.uri.toString() == u }; if (i >= 0) playlist.getOrNull(i + 1) else null
            } else null
            runQuery()
            if (rmNext != null) play(rmNext)
            else if (u != null && Playback.shouldAdvance(this, u, playlist.find { it.uri.toString() == u }?.name) && playIndex + 1 in playlist.indices) {
                play(playlist[playIndex + 1])
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        Utils.applyRtl(this)   // 58: RTL 레이아웃 토글

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        recycler = findViewById(R.id.recycler)

        grid = LibPrefs.grid(this)
        setupRecycler()

        findViewById<ImageButton>(R.id.qs).setOnClickListener {
            QuickSettings.show(this) {
                grid = LibPrefs.grid(this)
                setupRecycler()
                runQuery()
            }
        }
        findViewById<ImageButton>(R.id.sort).setOnClickListener {
            SortDialog.show(this, "search", false) { runQuery() }   // 26: 검색 결과 정렬
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runQuery(); true } else false
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val r = Runnable { runQuery() }
                pending = r
                handler.postDelayed(r, 220)
            }
        })

        ensureIndex()
    }

    private fun setupRecycler() {
        recycler.layoutManager = GridLayoutManager(this, LibPrefs.spanCount(this))
        adapter = SearchAdapter(emptyList(), grid) { play(it) }
        recycler.adapter = adapter
    }

    private fun ensureIndex() {
        if (SearchIndex.cached() != null) {
            status.text = getString(R.string.search_prompt)
            return
        }
        status.text = getString(R.string.search_indexing)
        Thread {
            val list = SearchIndex.build(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                status.text = getString(R.string.search_index_prompt, list.size)
                runQuery()
            }
        }.start()
    }

    private fun runQuery() {
        val index = SearchIndex.cached() ?: return
        val q = input.text.toString().trim().lowercase()
        if (q.isEmpty()) {
            adapter.update(emptyList())
            status.text = getString(R.string.search_index_prompt, index.size)
            return
        }
        val res = index.asSequence().filter { item ->
            if (!LibPrefs.passWatch(this, item.uri.toString(), item.name)) return@filter false
            if (item.name.lowercase().contains(q)) return@filter true
            val m = ThumbLoader.cachedMeta(item.uri.toString()) ?: return@filter false
            m[0].lowercase().contains(q) || m[1].lowercase().contains(q)
        }.take(500).toList()
        val sorted = LibPrefs.sortSearch(this, res)   // 26: 검색 결과도 정렬 적용
        results = sorted
        adapter.update(sorted)
        status.text = getString(R.string.search_results, input.text, sorted.size)
    }

    private fun play(item: SearchItem) {
        playlist = results
        playIndex = playlist.indexOfFirst { it.uri == item.uri }
        pendingUri = item.uri.toString()
        playLauncher.launch(Playback.intentFor(this, item.uri.toString(), item.name))
    }
}
