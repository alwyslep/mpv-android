package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File

// B-68(52, GridPlayer A안): 품번 중복 '장면 비교' — 같은 품번 버전들의 *같은 위치* 프레임을 좌/우(격자)로.
//   라이브 동시 재생 X(단일 mpv·HW디코더 한계 회피) → MediaMetadataRetriever 한 장씩(동시 디코더 0).
//   각 칸에 해상도·크기·**폴더 경로**·이름 → 진짜 중복(다른 폴더)인지 오판(같은 파일)인지 식별. 슬라이더로 위치 조절.
object SceneCompare {
    private fun norm(c: String?) = c?.uppercase()?.replace("-", "")?.replace("_", "")?.replace(" ", "")
    private fun shortRes(v: Vid): String { val s = minOf(v.width, v.height); return if (s > 0) "${s}p" else "?" }
    private fun prettyDir(path: String): String {
        if (path.isEmpty()) return "(경로?)"
        val d = File(path).parent ?: return "(경로?)"
        return d.removePrefix("/storage/emulated/0/").removePrefix("/storage/").ifEmpty { "/" }
    }

    fun show(ctx: Context, name: String) {
        val code = norm(JavCode.extract(name)) ?: run { Toast.makeText(ctx, "품번 인식 실패", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val vids = MediaLibrary.queryVideos(ctx).filter { norm(JavCode.extract(it.name)) == code }
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
        val cellW = dm.widthPixels / cols - (28 * dm.density).toInt()
        val cellH = cellW * 9 / 16
        val imgs = ArrayList<ImageView>()

        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        vids.forEachIndexed { i, v ->
            if (i % cols == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row)
            }
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF0E1216.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cellH)
            }
            val lbl = TextView(ctx).apply {
                setTextColor(0xFFD0D8E0.toInt()); textSize = 11f; maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                text = "${shortRes(v)} · ${MediaLibrary.fmtSize(v.size)}\n📁 ${prettyDir(v.path)}\n${v.name}"
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(10, 8, 10, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(iv); addView(lbl)
            }
            row!!.addView(cell); imgs.add(iv)
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
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 8)
            addView(posLabel); addView(seek); addView(grid)
        }
        AlertDialog.Builder(ctx).setTitle("장면 비교 · $code  (${vids.size}개)")
            .setView(ScrollView(ctx).apply { addView(outer) })
            .setPositiveButton("닫기", null).show()
        extract(act, vids, imgs, 50, cellW, cellH)
    }

    private fun extract(act: Activity, vids: List<Vid>, imgs: List<ImageView>, pct: Int, w: Int, h: Int) {
        Thread {
            for (i in vids.indices) {
                val v = vids[i]
                val tUs = if (v.durationMs > 0) v.durationMs * pct / 100 * 1000L else 1_000_000L
                val bmp = try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (v.path.isNotEmpty()) mmr.setDataSource(v.path) else mmr.setDataSource(act, v.uri)
                        if (Build.VERSION.SDK_INT >= 27)
                            mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                        else mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } finally { mmr.release() }
                } catch (e: Throwable) { null }
                act.runOnUiThread {
                    if (i < imgs.size) {
                        if (bmp != null) imgs[i].setImageBitmap(bmp)
                        else imgs[i].setImageDrawable(null)   // 추출 실패 = 검은 칸(빈 짝 식별)
                    }
                }
            }
        }.start()
    }
}
