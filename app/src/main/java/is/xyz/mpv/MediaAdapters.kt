package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// P2: 폴더 카드 — 대표 비디오의 임베드 커버(우하단 길이) + 폴더명 + 경로 + "N 동영상".
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
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val dur: TextView = v.findViewById(R.id.dur)
        val code: TextView = v.findViewById(R.id.code)
        val title: TextView = v.findViewById(R.id.title)
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
        // MediaStore 는 길이를 알고 있으니 직접 설정(durView 미사용)
        if (v.durationMs > 0) {
            h.dur.visibility = View.VISIBLE
            h.dur.text = MediaLibrary.fmtDur(v.durationMs)
        } else {
            h.dur.visibility = View.GONE
        }
        ThumbLoader.load(h.thumb, h.code, h.title, v.uri, v.name)
        h.itemView.setOnClickListener { onClick(v) }
    }
}
