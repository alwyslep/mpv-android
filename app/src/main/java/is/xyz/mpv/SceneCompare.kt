package `is`.xyz.mpv

import android.app.Activity
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

// B-68(52, GridPlayer A안 강화): 품번 중복 '장면 비교'.
//   파일=열, **여러 위치(10/30/50/70/90%)=행** 으로 동시 격자 → 좌/우 같은 위치 장면을 한눈에.
//   라이브 동시재생 X(단일 mpv·HW디코더 한계) → MediaMetadataRetriever 파일당 1회 open 후 위치별 프레임.
//   각 열 헤더에 해상도(MMR 실값)·크기·📁폴더·이름 → 진짜 중복(다른 폴더)인지 오판인지 식별.
object SceneCompare {
    private val POSITIONS = listOf(10, 30, 50, 70, 90)

    private fun labelText(v: Vid, shortSide: Int, folderText: String): String {
        val res = if (shortSide > 0) "${shortSide}p" else "?"
        return "$res · ${MediaLibrary.fmtSize(v.size)}\n$folderText\n${v.name}"
    }

    fun show(ctx: Context, name: String) {
        val code = JavCode.dupKey(name) ?: run { Toast.makeText(ctx, "품번 인식 실패", Toast.LENGTH_SHORT).show(); return }
        Thread {
            val vids = MediaLibrary.queryVideos(ctx)
                .filter { JavCode.dupKey(it.name) == code && !MediaLibrary.isTrashOrTemp(it) }
                .distinctBy { v -> if (v.path.isNotEmpty()) v.path else "${v.size}|${v.width}x${v.height}|${v.name}" }   // 같은 파일 합침
                .sortedWith(compareByDescending<Vid> { minOf(it.width, it.height) }.thenByDescending { it.size })
            JavDiag.log("scene", "tap='$name' code=$code  matched=${vids.size}")
            vids.forEach { JavDiag.log("scene", "  '${it.name}' folder=${it.folderName} ${it.width}x${it.height} ${it.size}B path=${if (it.path.isEmpty()) "NOPATH" else it.path}") }
            (ctx as? Activity)?.runOnUiThread {
                if (vids.size < 2) { Toast.makeText(ctx, "비교할 같은 품번이 없음 (이 파일뿐)", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                build(ctx, code, vids)
            }
        }.start()
    }

    private fun build(ctx: Context, code: String, vids: List<Vid>) {
        val act = ctx as? Activity ?: return
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        val cols = vids.size
        val posW = (34 * d).toInt()
        val cellW = (dm.widthPixels - posW) / cols - (16 * d).toInt()
        val cellH = cellW * 9 / 16

        // 동일 폴더명이 매칭들 사이 여러 드라이브에 충돌하면 그 폴더만 💾드라이브 태그.
        val volsByFolder = HashMap<String, MutableSet<String>>()
        vids.forEach { if (it.folderName.isNotEmpty()) volsByFolder.getOrPut(it.folderName) { HashSet() }.add(it.volume) }
        val tagFolders = volsByFolder.filterValues { it.size > 1 }.keys
        val folderTexts = vids.map { v ->
            when {
                v.folderName.isEmpty() -> "📁 (폴더?)"
                tagFolders.contains(v.folderName) -> "💾${MediaLibrary.volLabel(v.volume)} · 📁 ${v.folderName}"
                else -> "📁 ${v.folderName}"
            }
        }

        // 헤더(열별 라벨: 해상도·크기·폴더(드라이브)·이름)
        val headerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(TextView(ctx).apply { layoutParams = LinearLayout.LayoutParams(posW, LinearLayout.LayoutParams.WRAP_CONTENT) })
        val headerLbls = ArrayList<TextView>()
        vids.forEachIndexed { i, v ->
            val lbl = TextView(ctx).apply {
                setTextColor(0xFFD0D8E0.toInt()); textSize = 11f; maxLines = 4; setPadding(8, 6, 8, 6)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = labelText(v, minOf(v.width, v.height), folderTexts[i])
            }
            headerRow.addView(lbl); headerLbls.add(lbl)
        }

        // 위치별 행: [pct][파일0][파일1]…
        val imgs = Array(POSITIONS.size) { arrayOfNulls<ImageView>(cols) }
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(headerRow) }
        POSITIONS.forEachIndexed { r, pct ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (4 * d).toInt(), 0, 0) }
            row.addView(TextView(ctx).apply {
                setTextColor(0xFF8FA0B0.toInt()); textSize = 11f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(posW, cellH); text = "$pct%"
            })
            for (col in 0 until cols) {
                val iv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF0E1216.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, cellH, 1f).apply { leftMargin = (4 * d).toInt() }
                }
                imgs[r][col] = iv; row.addView(iv)
            }
            grid.addView(row)
        }

        AlertDialog.Builder(ctx).setTitle("장면 비교 · $code  (${vids.size}개)")
            .setView(ScrollView(ctx).apply { addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (8 * d).toInt()); addView(grid) }) })
            .setPositiveButton("닫기", null).show()
        extractAll(act, vids, imgs, headerLbls, folderTexts, cellW, cellH)
    }

    // 파일당 MMR 1회 open → 길이/해상도 확보 후 위치별 프레임 추출(디코더 동시 0).
    private fun extractAll(act: Activity, vids: List<Vid>, imgs: Array<Array<ImageView?>>, headerLbls: List<TextView>, folderTexts: List<String>, w: Int, h: Int) {
        Thread {
            vids.forEachIndexed { col, v ->
                var shortSide = minOf(v.width, v.height)
                val mmr = MediaMetadataRetriever()
                try {
                    if (v.path.isNotEmpty()) mmr.setDataSource(v.path) else mmr.setDataSource(act, v.uri)
                    val durMs = if (v.durationMs > 0) v.durationMs
                        else mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (shortSide <= 0) {
                        val rw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val rh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if (rw > 0 && rh > 0) shortSide = minOf(rw, rh)
                    }
                    val ss = shortSide
                    act.runOnUiThread { if (col < headerLbls.size) headerLbls[col].text = labelText(v, ss, folderTexts[col]) }
                    POSITIONS.forEachIndexed { r, pct ->
                        val tUs = if (durMs > 0) durMs * pct / 100 * 1000L else 1_000_000L
                        val bmp = try {
                            if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                            else mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        } catch (e: Throwable) { JavDiag.ex("scene.frame", e); null }
                        act.runOnUiThread {
                            imgs[r][col]?.let { if (bmp != null) it.setImageBitmap(bmp) else it.setImageDrawable(null) }
                        }
                    }
                    JavDiag.log("scene", "file[$col] '${v.name}' ${ss}p dur=${durMs}ms done")
                } catch (e: Throwable) { JavDiag.ex("scene.extract", e) } finally { mmr.release() }
            }
        }.start()
    }
}
