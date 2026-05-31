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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
        val out = ArrayList<SearchItem>()
        // 내부 MediaStore
        for (v in MediaLibrary.queryVideos(ctx)) {
            out.add(SearchItem(v.name, v.uri, v.folderName, v.durationMs, v.size))
        }
        // 저장된 SAF 트리(USB 등) 재귀
        for (t in SafTrees.all(ctx)) {
            try {
                walkTree(ctx, Uri.parse(t), out)
            } catch (_: Throwable) {
            }
            if (out.size > 50000) break
        }
        items = out
        return out
    }

    private fun walkTree(ctx: Context, treeUri: Uri, out: ArrayList<SearchItem>) {
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val stack = ArrayDeque<Pair<String, String>>() // docId, 폴더명
        stack.addLast(DocumentsContract.getTreeDocumentId(treeUri) to "")
        while (stack.isNotEmpty()) {
            if (out.size > 50000) return
            val (doc, folderName) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, doc)
            try {
                ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val nm = c.getString(1) ?: ""
                        val mime = c.getString(2) ?: ""
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(id to nm)
                        } else if (mime.startsWith("video/") || SafBrowserActivity.isVideoName(nm)) {
                            out.add(
                                SearchItem(
                                    nm.substringBeforeLast("."),
                                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                    folderName,
                                    0L,
                                    size
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}

class SearchAdapter(
    private var items: List<SearchItem>,
    private val grid: Boolean,
    private val onClick: (SearchItem) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
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
        h.meta.text = listOf(item.folder, MediaLibrary.fmtSize(item.size)).filter { s -> s.isNotEmpty() }
            .joinToString("  ·  ")
        if (item.durationMs > 0) {
            h.dur.visibility = View.VISIBLE
            h.dur.text = MediaLibrary.fmtDur(item.durationMs)
            ThumbLoader.load(h.thumb, h.code, h.title, item.uri, item.name)
        } else {
            ThumbLoader.load(h.thumb, h.code, h.title, item.uri, item.name, durView = h.dur)
        }
        h.itemView.setOnClickListener { onClick(item) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        recycler = findViewById(R.id.recycler)

        val grid = getSharedPreferences("media_library", MODE_PRIVATE).getBoolean("video_grid", true)
        val span = maxOf(2, resources.configuration.screenWidthDp / 170)
        recycler.layoutManager = GridLayoutManager(this, span)
        adapter = SearchAdapter(emptyList(), grid) { play(it) }
        recycler.adapter = adapter

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

    private fun ensureIndex() {
        if (SearchIndex.cached() != null) {
            status.text = "검색어를 입력하세요"
            return
        }
        status.text = "인덱싱 중…"
        Thread {
            val list = SearchIndex.build(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                status.text = "색인 ${list.size}개 · 검색어를 입력하세요"
                runQuery()
            }
        }.start()
    }

    private fun runQuery() {
        val index = SearchIndex.cached() ?: return
        val q = input.text.toString().trim().lowercase()
        if (q.isEmpty()) {
            adapter.update(emptyList())
            status.text = "색인 ${index.size}개 · 검색어를 입력하세요"
            return
        }
        val res = index.asSequence().filter { item ->
            if (item.name.lowercase().contains(q)) return@filter true
            val m = ThumbLoader.cachedMeta(item.uri.toString()) ?: return@filter false
            m[0].lowercase().contains(q) || m[1].lowercase().contains(q)
        }.take(500).toList()
        adapter.update(res)
        status.text = "‘${input.text}’ — ${res.size}개"
    }

    private fun play(it: SearchItem) {
        Recents.add(this, it.uri.toString(), it.name)
        val i = Intent(Intent.ACTION_VIEW, it.uri)
        i.setClass(this, MPVActivity::class.java)
        startActivity(i)
    }
}
