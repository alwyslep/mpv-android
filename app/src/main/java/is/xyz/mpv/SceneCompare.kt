package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

// B-68(52, GridPlayer A안): 품번 중복 '장면 비교' — 같은 품번 버전들의 *같은 위치* 프레임을 격자로 추출해 나란히.
//   라이브 동시 재생 X(단일 mpv·HW디코더 한계 회피) → MediaMetadataRetriever 한 장씩(동시 디코더 0).
//   슬라이더로 위치(0~100%) 조절 시 각 버전 프레임 갱신 → 화질·장면을 버전별로 눈으로 비교 후 정리.
object SceneCompare {
    private fun norm(c: String?) = c?.uppercase()?.replace("-", "")?.replace("_", "")?.replace(" ", "")
    private fun shortRes(v: Vid): String { val s = minOf(v.width, v.height); return if (s > 0) "${s}p" else "?" }

    fun show(ctx: Context, name: String) {
        val code = norm(JavCode.extract(name)) ?: run { Toast.makeText(ctx, "품번 인식 실패", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val vids = MediaLibrary.queryVideos(ctx).filter { norm(JavCode.extract(it.name)) == code }   // DuplicatesActivity 와 동일 기준(길이 0 도 포함)
                .sortedByDescending { minOf(it.width, it.height) }   // 고해상도 먼저
            (ctx as? Activity)?.runOnUiThread {
                if (vids.size < 2) { Toast.makeText(ctx, "비교할 같은 품번이 없음 (이 파일뿐)", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                build(ctx, code, vids)
            }
        }.start()
    }

    private fun build(ctx: Context, code: String, vids: List<Vid>) {
        val act = ctx as? Activity ?: return
        val dm = ctx.resources.displayMetrics
        val cols = if (vids.size <= 2) vids.size else 2
        val cellW = (dm.widthPixels / cols) - (32 * dm.density).toInt()
        val cellH = cellW * 9 / 16
        val imgs = ArrayList<ImageView>()
        val grid = GridLayout(ctx).apply { columnCount = cols; setPadding(8, 8, 8, 8) }
        for (v in vids) {
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF0E1216.toInt())
                layoutParams = LinearLayout.LayoutParams(cellW, cellH)
            }
            val lbl = TextView(ctx).apply {
                setTextColor(0xFFCCD4DC.toInt()); textSize = 11f; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = "${shortRes(v)} · ${MediaLibrary.fmtSize(v.size)}\n${v.name}"
                layoutParams = LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 10, 10, 10); addView(iv); addView(lbl) }
            grid.addView(col); imgs.add(iv)
        }
        val posLabel = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 13f; text = "위치: 50%" }
        val seek = SeekBar(ctx).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { posLabel.text = "위치: $p%" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { extract(act, vids, imgs, s.progress, cellW, cellH) }
            })
        }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 8); addView(posLabel); addView(seek); addView(grid) }
        AlertDialog.Builder(ctx).setTitle("장면 비교 · $code  (${vids.size}개)")
            .setView(ScrollView(ctx).apply { addView(inner) })
            .setPositiveButton("닫기", null).show()
        extract(act, vids, imgs, 50, cellW, cellH)
    }

    private fun extract(act: Activity, vids: List<Vid>, imgs: List<ImageView>, pct: Int, w: Int, h: Int) {
        Thread {
            for (i in vids.indices) {
                val v = vids[i]
                val tUs = if (v.durationMs > 0) v.durationMs * pct / 100 * 1000L else 1_000_000L   // 길이 0=1초 폴백
                val bmp = try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (v.path.isNotEmpty()) mmr.setDataSource(v.path) else mmr.setDataSource(act, v.uri)
                        if (Build.VERSION.SDK_INT >= 27)
                            mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                        else mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } finally { mmr.release() }
                } catch (e: Throwable) { null }
                act.runOnUiThread { if (bmp != null && i < imgs.size) imgs[i].setImageBitmap(bmp) }
            }
        }.start()
    }
}
