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
    private lateinit var toolbar: MaterialToolbar
    private var pendingUri: String? = null
    private var scrollState: android.os.Parcelable? = null   // 31: 스크롤 보존(onPause+Bundle)
    private lateinit var selCtl: SelectionController
    private val playLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val u = pendingUri
            if (u != null) Playback.onResult(this, u, res.data)
            // B-68: 삭제/이동으로 제거됐으면 리스트 갱신 전에 다음 영상 캡처 → advance.
            val rmNext = if (Playback.wasRemoved(res.data) && u != null) {
                val vids = entries.mapNotNull { it.vid }; val i = vids.indexOfFirst { it.uri.toString() == u }
                if (i >= 0) vids.getOrNull(i + 1) else null
            } else null
            rebuild()
            if (rmNext != null) play(rmNext)
            else if (u != null && Playback.shouldAdvance(this, u, entries.mapNotNull { it.vid }.find { it.uri.toString() == u }?.name)) {
                val vids = entries.mapNotNull { it.vid }
                val idx = vids.indexOfFirst { it.uri.toString() == u }
                if (idx >= 0 && idx + 1 in vids.indices) play(vids[idx + 1])
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_videos)
        Utils.applyRtl(this)   // 58: RTL 레이아웃 토글
        @Suppress("DEPRECATION")
        savedInstanceState?.getParcelable<android.os.Parcelable>("scroll")?.let { scrollState = it }  // 31

        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        grid = prefs.getBoolean("video_grid", true)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = intent.getStringExtra("title") ?: getString(R.string.qs_tree)
        toolbar.setNavigationOnClickListener { finish() }
        MetaHub.fetchAsync(this)
        setupToolbar()   // B-68(52): 공통 LibToolbar

        recycler = findViewById(R.id.recycler)
        ResolutionHub.fetchAsync(this)  // 48: hub 해상도맵(트리 직진입 시도 채움)
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
        Thread {
            val vids = MediaLibrary.queryVideos(this)
            val dir = intent.getStringExtra("dir") ?: MediaLibrary.treeRoot(vids)
            val list = MediaLibrary.treeChildren(this, vids, dir, "home_tree")
                .filter { it.dirPath != null || FilterEngine.passes(this, it.vid!!) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                entries = list
                rebuild()
            }
        }.start()
    }

    // B-68(52): 공통 12아이콘 툴바. 트리 활성 = toggle/sort/tune/select/filter/move/thumb/heal.
    private fun setupToolbar() {
        val prefs = getSharedPreferences("media_library", MODE_PRIVATE)
        LibToolbar.build(toolbar, setOf("toggle", "sort", "tune", "select", "filter", "move", "thumb", "heal"), grid) { key ->
            when (key) {
                "toggle" -> { grid = !grid; prefs.edit().putBoolean("video_grid", grid).apply(); setupToolbar(); rebuild() }
                "tune" -> QuickSettings.show(this) { grid = LibPrefs.grid(this); setupToolbar(); reload() }
                "sort" -> SortDialog.show(this, "home_tree", false) { reload() }
                "select" -> selCtl.enter(showEmbed = true, showMove = false)
                "filter" -> FilterSheet.show(this) { reload() }
                "move" -> selCtl.enter(showEmbed = false, showMove = true)
                "thumb" -> selCtl.enter(showEmbed = false, showMove = false, showThumb = true)
                "heal" -> VideoHeal.healFolderConfirm(this, entries.mapNotNull { it.vid }.map { it.uri to it.name }) { reload() }
            }
        }
    }

    private fun spanCount(): Int = LibPrefs.spanCount(this)

    private fun rebuild() {
        // 31: LM 재생성 전(기존, 아이템 있을 때만) 위치 저장 → 필드.
        if ((recycler.adapter?.itemCount ?: 0) > 0)
            recycler.layoutManager?.onSaveInstanceState()?.let { scrollState = it }
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
        val ta = TreeAdapter(
            entries.toMutableList(), grid,
            onDir = { e ->
                startActivity(
                    android.content.Intent(this, TreeActivity::class.java)
                        .putExtra("dir", e.dirPath)
                        .putExtra("title", e.name)
                )
            },
            onVideo = { e -> e.vid?.let { play(it) } }
        )
        selCtl.bind(ta)
        recycler.adapter = ta
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
        pendingUri = v.uri.toString()
        playLauncher.launch(Playback.intentFor(this, v.uri.toString(), v.name))
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selCtl.isActive) selCtl.exit() else super.onBackPressed()
    }
}

class TreeAdapter(
    private val items: MutableList<TreeEntry>,
    private val grid: Boolean,
    private val onDir: (TreeEntry) -> Unit,
    private val onVideo: (TreeEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), SelectableVids {

    override var selectionMode = false
    override val selected = LinkedHashSet<String>()
    override var onSelectionChanged: (() -> Unit)? = null
    override fun selectableVids(): List<Vid> = items.mapNotNull { it.vid }
    override fun refreshSelection() { notifyDataSetChanged() }
    override fun notifyItem(uri: String) { val t = android.net.Uri.parse(uri); val i = items.indexOfFirst { it.vid?.uri == t }; if (i >= 0) notifyItemChanged(i) }
    override fun removeItem(uri: String) {
        val t = android.net.Uri.parse(uri)
        val i = items.indexOfFirst { it.vid?.uri == t }
        if (i >= 0) { items.removeAt(i); selected.remove(uri); notifyItemRemoved(i) }
    }

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
        val resBadge: TextView = v.findViewById(R.id.res_badge)
        val check: android.widget.CheckBox? = v.findViewById(R.id.check)
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
            // B-64(48): 해상도를 글자줄 대신 썸네일 위 배지(res_badge)로 — videos 모드와 통일.
            val localShort = if (v.width > 0 && v.height > 0) minOf(v.width, v.height) else v.height
            val isPortrait = v.width in 1 until v.height
            fun resTag(n: Int) = if (isPortrait) "↕${n}p" else "${n}p"
            val sz = if (LibPrefs.showSize(ctx)) MediaLibrary.fmtSize(v.size) else ""
            val ext = if (LibPrefs.showExt(ctx)) v.nameExt.substringAfterLast(".", "").uppercase() else ""
            h.meta.text = listOf(sz, ext).filter { it.isNotEmpty() }.joinToString("  ·  ")
            run { val tvc = android.util.TypedValue(); ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tvc, true); h.meta.setTextColor(tvc.data) }
            val rb = h.resBadge
            val rKey = v.uri.toString()
            rb.tag = rKey
            val badgeDark = 0x99000000.toInt(); val badgeRed = 0xCCD32F2F.toInt()
            if (!LibPrefs.showRes(ctx)) {
                rb.visibility = View.GONE
            } else if (localShort > 0) {
                rb.visibility = View.VISIBLE
                rb.setBackgroundColor(badgeDark)
                rb.text = resTag(localShort)
                if (localShort in 1..719) { rb.setBackgroundColor(badgeRed); rb.text = "${resTag(localShort)} ⚠" }
                ThumbLoader.checkResMismatch(ctx, v.uri, localShort) { hubRaw ->
                    if (rb.tag == rKey) {
                        rb.setBackgroundColor(badgeRed)
                        val expStr = if (hubRaw < 0) "↕${-hubRaw}p" else "${hubRaw}p"
                        rb.text = "${resTag(localShort)}→$expStr ⚠"
                    }
                }
            } else {
                rb.visibility = View.GONE
                ThumbLoader.fetchHubRes(ctx, v.uri) { hubRaw ->
                    if (rb.tag == rKey) {
                        rb.visibility = View.VISIBLE
                        rb.setBackgroundColor(badgeDark)
                        rb.text = if (hubRaw < 0) "↕${-hubRaw}p" else "${hubRaw}p"
                    }
                }
            }
            if (LibPrefs.showDur(ctx) && v.durationMs > 0) {
                h.dur.visibility = View.VISIBLE
                h.dur.text = MediaLibrary.fmtDur(v.durationMs)
                h.dur.setTextColor(0xFFFFFFFF.toInt())
                val durKey = v.uri.toString(); h.dur.tag = durKey
                ThumbLoader.checkDurMismatch(ctx, v.uri, v.durationMs) { mismatch ->
                    if (mismatch && h.dur.tag == durKey) {
                        h.dur.setTextColor(0xFFFF5252.toInt())
                        h.dur.text = MediaLibrary.fmtDur(v.durationMs) + " ⚠"
                    }
                }
            } else {
                h.dur.visibility = View.GONE
            }
            val pct = if (LibPrefs.showProgress(ctx)) Progress.percent(ctx, v.uri.toString(), v.name) else 0f
            if (pct > 0f) {
                h.progress.visibility = View.VISIBLE
                h.progress.progress = (pct * 100).toInt()
            } else {
                h.progress.visibility = View.GONE
            }
            ThumbLoader.load(h.thumb, h.code, h.title, v.uri, v.name)
            val us = v.uri.toString()
            h.check?.visibility = if (selectionMode) View.VISIBLE else View.GONE
            h.check?.isChecked = selected.contains(us)
            h.itemView.setOnClickListener {
                if (selectionMode) {
                    if (!selected.remove(us)) selected.add(us)
                    notifyItemChanged(h.bindingAdapterPosition)
                    onSelectionChanged?.invoke()
                } else onVideo(e)
            }
            h.itemView.setOnLongClickListener {
                VideoActions.longPress(it, v.uri.toString(), v.name, onChanged = { notifyItemChanged(h.bindingAdapterPosition) })
                true
            }
        }
    }
}
