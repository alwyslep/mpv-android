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
    private fun labelText(v: Vid, shortSide: Int): String {
        val res = if (shortSide > 0) "${shortSide}p" else "?"
        val folder = v.folderName.ifEmpty { "(폴더?)" }
        return "$res · ${MediaLibrary.fmtSize(v.size)}\n📁 $folder\n${v.name}"
    }

    fun show(ctx: Context, name: String) {
        val code = norm(JavCode.extract(name)) ?: run { Toast.makeText(ctx, "품번 인식 실패", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val vids = MediaLibrary.queryVideos(ctx)
                .filter { norm(JavCode.extract(it.name)) == code }
                .distinctBy { v -> if (v.path.isNotEmpty()) v.path else "${v.size}|${v.width}x${v.height}|${v.name}" }   // 같은 파일(중복행) 합침
                .sortedWith(compareByDescending<Vid> { minOf(it.width, it.height) }.thenByDescending { it.size })   // 고해상도·대용량 먼저
            JavDiag.log("scene", "tap='$name' code=$code  matched=${vids.size}")
            vids.forEach { JavDiag.log("scene", "  '${it.name}' folder=${it.folderName} dur=${it.durationMs}ms ${it.width}x${it.height} ${it.size}B path=${if (it.path.isEmpty()) "NOPATH" else it.path}") }
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
        val lbls = ArrayList<TextView>()

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
                text = labelText(v, minOf(v.width, v.height))   // MMR 로 실해상도 받으면 갱신
            }
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(10, 8, 10, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(iv); addView(lbl)
            }
            row!!.addView(cell); imgs.add(iv); lbls.add(lbl)
        }

        val posLabel = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 13f; text = "위치: 50%" }
        val seek = SeekBar(ctx).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { posLabel.text = "위치: $p%" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { extract(act, vids, imgs, lbls, s.progress, cellW, cellH) }
            })
        }
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 8)
            addView(posLabel); addView(seek); addView(grid)
        }
        AlertDialog.Builder(ctx).setTitle("장면 비교 · $code  (${vids.size}개)")
            .setView(ScrollView(ctx).apply { addView(outer) })
            .setPositiveButton("닫기", null).show()
        extract(act, vids, imgs, lbls, 50, cellW, cellH)
    }

    private fun extract(act: Activity, vids: List<Vid>, imgs: List<ImageView>, lbls: List<TextView>, pct: Int, w: Int, h: Int) {
        Thread {
            for (i in vids.indices) {
                val v = vids[i]
                var tUs = 1_000_000L
                var shortSide = minOf(v.width, v.height)
                val bmp = try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (v.path.isNotEmpty()) mmr.setDataSource(v.path) else mmr.setDataSource(act, v.uri)
                        // 빈경로(스코프 스토리지)는 durationMs=0 일 수 있음 → MMR 로 실제 길이 구해 정위치 추출
                        val durMs = if (v.durationMs > 0) v.durationMs
                            else mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (durMs > 0) tUs = durMs * pct / 100 * 1000L
                        // MediaStore 0x0(미스캔) 인 93% → MMR 로 실해상도 라벨 갱신
                        if (shortSide <= 0) {
                            val rw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val rh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            if (rw > 0 && rh > 0) shortSide = minOf(rw, rh)
                        }
                        if (Build.VERSION.SDK_INT >= 27)
                            mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                        else mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } finally { mmr.release() }
                } catch (e: Throwable) { JavDiag.ex("scene.extract", e); null }
                JavDiag.log("scene", "frame[$i] '${v.name}' @${pct}% tUs=$tUs ${shortSide}p → ${if (bmp != null) "${bmp.width}x${bmp.height}" else "NULL"}")
                val ss = shortSide
                act.runOnUiThread {
                    if (i < imgs.size) {
                        if (bmp != null) imgs[i].setImageBitmap(bmp)
                        else imgs[i].setImageDrawable(null)   // 추출 실패 = 검은 칸(빈 짝 식별)
                    }
                    if (i < lbls.size) lbls[i].text = labelText(v, ss)   // 실해상도 반영
                }
            }
        }.start()
    }
}
