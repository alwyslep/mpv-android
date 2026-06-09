package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// P2: 폴더 카드 — 대표 비디오의 임베드 커버(우하단 길이) + 폴더명 + 경로 + "N 동영상".
class FolderAdapter(
    private val items: List<Fold>,
    private val grid: Boolean,
    private val onClick: (Fold) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val name: TextView = v.findViewById(R.id.name)
        val path: TextView = v.findViewById(R.id.path)
        val count: TextView = v.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (grid) R.layout.item_folder_grid else R.layout.item_folder
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        val ctx = h.itemView.context
        // B-57 v3: grid 폴더 커버 높이도 셀 폭에 비례.
        if (grid) {
            val lp = h.thumb.layoutParams
            lp.height = LibPrefs.gridCoverHeightPx(ctx)
            h.thumb.layoutParams = lp
        }
        h.name.text = f.name
        h.path.text = f.path
        h.path.visibility = if (LibPrefs.showPath(ctx)) View.VISIBLE else View.GONE
        h.count.text = ctx.getString(R.string.n_videos, f.count)
        val d = f.rep?.durationMs ?: 0L
        if (LibPrefs.showDur(ctx) && d > 0) {
            h.dur.visibility = View.VISIBLE
            h.dur.text = MediaLibrary.fmtDur(d)
        } else {
            h.dur.visibility = View.GONE
        }
        if (f.rep != null) {
            ThumbLoader.load(h.thumb, null, null, f.rep.uri, "") // 커버만(폴더명은 위에서 설정)
        } else {
            h.thumb.setImageDrawable(null)
        }
        h.itemView.setOnClickListener { onClick(f) }
    }
}

// P2: 비디오 카드 — 임베드 커버 + 길이 + 라벨(품번/한글제목 분리, 없으면 파일명) + 해상도·크기.
class VideoAdapter(
    val items: MutableList<Vid>,
    private val grid: Boolean,
    private val showFolder: Boolean = false,        // 중복 검수: meta 에 📁폴더 표기
    private val keepUris: Set<String> = emptySet(),  // 중복 검수: ✅추천(KEEP) 표시할 uri 들
    private val resByUri: Map<String, Int> = emptyMap(),  // 중복 검수: MediaStore 0x0 보완용 MMR 짧은변(px)
    private val coverUris: Set<String> = emptySet(),  // 중복 검수: 임베드 커버 있는 uri(🖼 표시)
    private val onClick: (Vid) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>(), SelectableVids {

    // jembed 선택모드 — 체크박스 다중선택
    override var selectionMode = false
    override val selected = LinkedHashSet<String>()
    override var onSelectionChanged: (() -> Unit)? = null
    override fun selectableVids(): List<Vid> = items
    override fun refreshSelection() { notifyDataSetChanged() }
    override fun notifyItem(uri: String) { val t = android.net.Uri.parse(uri); val i = items.indexOfFirst { it.uri == t }; if (i >= 0) notifyItemChanged(i) }
    override fun removeItem(uri: String) {
        val t = android.net.Uri.parse(uri)   // Uri equals 비교 — 문자열 round-trip 표현차(SAF 인코딩) 무관
        val i = items.indexOfFirst { it.uri == t }
        if (i >= 0) { items.removeAt(i); selected.remove(uri); notifyItemRemoved(i) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumbBox: View = v.findViewById(R.id.thumb_box)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val code: TextView = v.findViewById(R.id.code)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val badge: TextView = v.findViewById(R.id.badge)
        val resBadge: TextView = v.findViewById(R.id.res_badge)
        val check: CheckBox? = v.findViewById(R.id.check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (grid) R.layout.item_video_grid else R.layout.item_video
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        val ctx = h.itemView.context
        h.thumbBox.visibility = if (LibPrefs.showThumb(ctx)) View.VISIBLE else View.GONE
        // B-57 v3: grid 커버 높이를 셀 폭에 비례(열+높이 동시 스케일). 목록 모드는 xml 고정.
        if (grid) {
            val lp = h.thumbBox.layoutParams
            lp.height = LibPrefs.gridCoverHeightPx(ctx)
            h.thumbBox.layoutParams = lp
        }
        // B-60: 화질=짧은변 min(W,H) (세로영상 정확 — 480x842는 480p). 세로(W<H)는 ↕ 표기로 가로와 구분.
        // 표시는 글자줄(meta) 대신 **썸네일 위 배지(res_badge)** — 저화질/불일치 마커도 배지에서.
        val localShort = if (v.width > 0 && v.height > 0) minOf(v.width, v.height) else v.height
        val isPortrait = v.width in 1 until v.height
        fun resTag(n: Int) = if (isPortrait) "↕${n}p" else "${n}p"
        val sz = if (LibPrefs.showSize(ctx)) MediaLibrary.fmtSize(v.size) else ""
        val ext = if (LibPrefs.showExt(ctx)) v.nameExt.substringAfterLast(".", "").uppercase() else ""
        val uriStr = v.uri.toString()
        val cover = if (showFolder && coverUris.contains(uriStr)) "🖼임베드" else ""
        val folder = if (showFolder && v.folderName.isNotEmpty()) "📁${v.folderName}" else ""
        val isKeep = keepUris.contains(uriStr)
        val metaParts = listOf(sz, ext, cover, folder).filter { it.isNotEmpty() }.joinToString("  ·  ")
        h.meta.text = if (isKeep) "✅추천  $metaParts" else metaParts
        if (isKeep) h.meta.setTextColor(0xFF66BB6A.toInt())   // KEEP = 초록
        else run { val tvc = android.util.TypedValue(); ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tvc, true); h.meta.setTextColor(tvc.data) }
        // 해상도 배지(썸네일 위). badgeDark=기본, badgeRed=저화질/불일치 경고.
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
            // 절대 임계: 짧은변 < 720p → 저화질 경고(빨강+⚠)
            if (localShort in 1..719) { rb.setBackgroundColor(badgeRed); rb.text = "${resTag(localShort)} ⚠" }
            // hub 기대(abs)보다 낮으면 빨강 + →기대 ⚠ (비동기, tag 가드)
            ThumbLoader.checkResMismatch(ctx, v.uri, localShort) { hubRaw ->
                if (rb.tag == rKey) {
                    rb.setBackgroundColor(badgeRed)
                    val expStr = if (hubRaw < 0) "↕${-hubRaw}p" else "${hubRaw}p"
                    rb.text = "${resTag(localShort)}→$expStr ⚠"
                }
            }
        } else if (resByUri[rKey] != null && resByUri[rKey]!! > 0) {
            // 중복 검수: MMR 로 구한 실해상도(MediaStore 0x0 보완) — 즉시 표시
            rb.visibility = View.VISIBLE
            rb.setBackgroundColor(badgeDark)
            val mp = resByUri[rKey]!!
            rb.text = if (mp in 1..719) "${mp}p ⚠".also { rb.setBackgroundColor(badgeRed) } else "${mp}p"
        } else {
            // MediaStore height=0(임베드 remux/SAF) → hub /resolutions 값으로 표시(마커 아님)
            rb.visibility = View.GONE
            ThumbLoader.fetchHubRes(ctx, v.uri) { hubRaw ->
                if (rb.tag == rKey) {
                    rb.visibility = View.VISIBLE
                    rb.setBackgroundColor(badgeDark)
                    rb.text = if (hubRaw < 0) "↕${-hubRaw}p" else "${hubRaw}p"
                }
            }
        }
        // MediaStore 는 길이를 알고 있으니 직접 설정(durView 미사용)
        if (LibPrefs.showDur(ctx) && v.durationMs > 0) {
            h.dur.visibility = View.VISIBLE
            h.dur.text = MediaLibrary.fmtDur(v.durationMs)
            h.dur.setTextColor(0xFFFFFFFF.toInt())   // 기본 흰색(재활용 복원)
            // v6: hub 길이와 불일치 시 마커(빨강+⚠). 품번 비동기 확정 후, tag 가드로 재활용 안전.
            val durKey = v.uri.toString()
            h.dur.tag = durKey
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
        // ♥/평점 뱃지
        val us = v.uri.toString()
        if (LibPrefs.showFav(ctx)) {
            val fav = Favorites.has(ctx, us, v.name)
            val rt = Ratings.get(ctx, us, v.name)
            val txt = buildString {
                if (fav) append("♥")
                if (rt > 0) { if (isNotEmpty()) append(" "); append("★").append(rt) }
            }
            h.badge.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
            h.badge.text = txt
        } else {
            h.badge.visibility = View.GONE
        }
        ThumbLoader.load(h.thumb, h.code, h.title, v.uri, v.name)
        h.check?.visibility = if (selectionMode) View.VISIBLE else View.GONE
        h.check?.isChecked = selected.contains(us)
        h.itemView.setOnClickListener {
            if (selectionMode) {
                if (!selected.remove(us)) selected.add(us)
                notifyItemChanged(h.bindingAdapterPosition)
                onSelectionChanged?.invoke()
            } else onClick(v)
        }
        h.itemView.setOnLongClickListener {
            VideoActions.longPress(it, us, v.name,
                onChanged = { notifyItemChanged(h.bindingAdapterPosition) },
                onRemoved = { removeItem(us) },   // 59: 삭제 후 타일 즉시 제거
                permanent = v.folderPath.contains(".mpv-trash"))   // B-68: 휴지통 폴더(MediaStore 경로)면 영구삭제
            true
        }
    }
}
