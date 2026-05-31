package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// P2: 폴더 카드 — 대표 비디오의 임베드 커버(우하단 길이 오버레이) + 폴더명 + 경로 + "N 동영상".
class FolderAdapter(
    private val items: List<Fold>,
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
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = items[position]
        h.name.text = f.name
        h.path.text = f.path
        h.count.text = "${f.count} 동영상"
        val d = f.rep?.durationMs ?: 0L
        if (d > 0) {
            h.dur.visibility = View.VISIBLE
            h.dur.text = MediaLibrary.fmtDur(d)
        } else {
            h.dur.visibility = View.GONE
        }
        if (f.rep != null) {
            // 폴더는 제목 파싱 불필요 → titleView=null, 커버만 로드
            ThumbLoader.load(h.thumb, null, f.rep.uri, f.rep.path, "")
        } else {
            h.thumb.setImageDrawable(null)
        }
        h.itemView.setOnClickListener { onClick(f) }
    }
}

// P2: 비디오 카드 — 임베드 커버 + 길이 오버레이 + 라벨(품번\n한글제목 또는 파일명) + 해상도·크기.
//   grid=true 면 포스터 타일 레이아웃, false 면 목록 행.
class VideoAdapter(
    private val items: List<Vid>,
    private val grid: Boolean,
    private val onClick: (Vid) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (grid) R.layout.item_video_grid else R.layout.item_video
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        val res = if (v.height > 0) "${v.height}p" else ""
        val sz = MediaLibrary.fmtSize(v.size)
        h.meta.text = listOf(res, sz).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (v.durationMs > 0) {
            h.dur.visibility = View.VISIBLE
            h.dur.text = MediaLibrary.fmtDur(v.durationMs)
        } else {
            h.dur.visibility = View.GONE
        }
        // 커버 + 임베드 제목(품번\n한글제목) 비동기 로드. 임베드 없으면 fallback = 파일명.
        ThumbLoader.load(h.thumb, h.name, v.uri, v.path, v.name)
        h.itemView.setOnClickListener { onClick(v) }
    }
}
