package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val items: List<Vid>,
    private val grid: Boolean,
    private val onClick: (Vid) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumbBox: View = v.findViewById(R.id.thumb_box)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val code: TextView = v.findViewById(R.id.code)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val badge: TextView = v.findViewById(R.id.badge)
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
        val res = if (v.height > 0 && LibPrefs.showRes(ctx)) "${v.height}p" else ""
        val sz = if (LibPrefs.showSize(ctx)) MediaLibrary.fmtSize(v.size) else ""
        val ext = if (LibPrefs.showExt(ctx)) v.nameExt.substringAfterLast(".", "").uppercase() else ""
        h.meta.text = listOf(res, sz, ext).filter { it.isNotEmpty() }.joinToString("  ·  ")
        // MediaStore 는 길이를 알고 있으니 직접 설정(durView 미사용)
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
        // ♥/평점 뱃지
        val us = v.uri.toString()
        if (LibPrefs.showFav(ctx)) {
            val fav = Favorites.has(ctx, us)
            val rt = Ratings.get(ctx, us)
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
        h.itemView.setOnClickListener { onClick(v) }
        h.itemView.setOnLongClickListener {
            VideoActions.longPress(it, us, v.name) { notifyItemChanged(h.bindingAdapterPosition) }
            true
        }
    }
}
