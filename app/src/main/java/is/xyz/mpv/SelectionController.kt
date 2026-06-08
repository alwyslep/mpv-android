package `is`.xyz.mpv

import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** 선택모드를 지원하는 어댑터 공통 계약 — VideoAdapter·TreeAdapter 가 구현. */
interface SelectableVids {
    var selectionMode: Boolean
    val selected: LinkedHashSet<String>
    var onSelectionChanged: (() -> Unit)?
    fun selectableVids(): List<Vid>   // uri→Vid lookup 용 (영상만)
    fun refreshSelection()            // notifyDataSetChanged
    fun notifyItem(uri: String)       // 항목 1개 갱신 — 임베드 완료 시 그 타일 썸네일 즉시 반영
    fun removeItem(uri: String)       // 항목 1개 제거 — 이동 완료 시 목록에서 즉시 사라짐
}

/**
 * jembed/이동 선택모드 공통 컨트롤러 — MediaLibraryActivity·FolderVideosActivity·TreeActivity 가 공유.
 * 화면이 어댑터를 만들 때 bind(), sel_bar 버튼을 enter/embedBatch/moveBatch/exit 에 연결.
 * 품번/이름은 어댑터의 selectableVids(Vid)에서 직접 추출 — 각 화면의 별도 목록 의존 제거.
 */
class SelectionController(
    private val act: AppCompatActivity,
    private val selBar: View,
    private val selCount: TextView,
    private val onReload: () -> Unit
) {
    var sel: SelectableVids? = null
        private set

    fun bind(a: SelectableVids) {
        sel = a; a.onSelectionChanged = { update() }
        // B-64(47): 선택바 버튼 툴팁
        mapOf(
            R.id.sel_all to "현재 목록 전체 선택/해제", R.id.sel_embed to "선택 영상에 hub 메타·커버 임베드(품번 없으면 remux만)",
            R.id.sel_move to "선택 영상을 다른 폴더로 이동", R.id.sel_thumb to "선택 영상의 썸네일 위치를 일괄 지정",
            R.id.sel_cancel to "선택 모드 종료"
        ).forEach { (id, t) -> selBar.findViewById<View>(id)?.let { Utils.tip(it, t) } }
    }
    fun unbind() { sel = null }

    val isActive: Boolean get() = sel?.selectionMode == true

    // 임베드/이동/썸네일 독립 진입 — 액션에 맞는 버튼만 노출.
    fun enter(showEmbed: Boolean = true, showMove: Boolean = true, showThumb: Boolean = false) {
        val a = sel
        if (a == null) { toast("'영상' 보기 모드 또는 폴더 안에서 선택하세요"); return }
        a.selectionMode = true; a.refreshSelection()
        selBar.findViewById<View>(R.id.sel_embed)?.visibility = if (showEmbed) View.VISIBLE else View.GONE
        selBar.findViewById<View>(R.id.sel_move)?.visibility = if (showMove) View.VISIBLE else View.GONE
        selBar.findViewById<View>(R.id.sel_thumb)?.visibility = if (showThumb) View.VISIBLE else View.GONE
        selBar.visibility = View.VISIBLE; update()
    }

    // 장면 썸네일 일괄 — % 위치 슬라이더로 적용(커버 없는 파일만) / 해제. 품번·파일명 키.
    fun thumbBatch() {
        val vids = (sel?.selected?.toList() ?: emptyList()).mapNotNull { vidOf(it) }
        if (vids.isEmpty()) { toast("선택 없음"); return }
        val ll = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8) }
        val tv = TextView(act).apply { text = "썸네일 위치: 50%" }
        ll.addView(tv)
        val seek = android.widget.SeekBar(act).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, u: Boolean) { tv.text = "썸네일 위치: $p%" }
                override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
            })
        }
        ll.addView(seek)
        MaterialAlertDialogBuilder(act)
            .setTitle("장면 썸네일 (${"%,d".format(vids.size)}개)").setView(ll)
            .setPositiveButton("적용") { _, _ ->
                val pct = seek.progress
                Thread {   // 커버 유무 판정에 MMR 가능 → 백그라운드
                    var n = 0
                    for (v in vids) {
                        // 커버 '이미지' 있는 파일만 제외(메타 유무 아님). SAF 등 길이 0 도 제외.
                        if (v.durationMs > 0 && !ThumbLoader.hasCover(act, v.uri)) {
                            LibPrefs.setCustomThumbPos(act, MediaKey.of(v.uri.toString(), v.name), v.durationMs * pct / 100)
                            ThumbLoader.invalidate(act, v.uri); n++
                        }
                    }
                    act.runOnUiThread {
                        exit(); onReload()
                        Toast.makeText(act, "썸네일 적용: ${n}개 (커버 있는 파일 제외)", Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNeutralButton("해제") { _, _ ->
                for (v in vids) {
                    LibPrefs.clearCustomThumbPos(act, MediaKey.of(v.uri.toString(), v.name))
                    ThumbLoader.invalidate(act, v.uri); sel?.notifyItem(v.uri.toString())
                }
                exit(); onReload()
                Toast.makeText(act, "썸네일 해제: ${"%,d".format(vids.size)}개", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null).show()
    }

    fun exit() {
        sel?.let { it.selectionMode = false; it.selected.clear(); it.refreshSelection() }
        selBar.visibility = View.GONE
    }

    // 전체선택 토글 — 이미 전부 선택돼 있으면 해제. (필터된 목록 전체를 한 번에)
    fun selectAll() {
        val a = sel ?: run { toast("'영상' 보기 모드 또는 폴더 안에서 선택하세요"); return }
        if (!a.selectionMode) { a.selectionMode = true; selBar.visibility = View.VISIBLE }
        val all = a.selectableVids().map { it.uri.toString() }
        if (a.selected.size == all.size && a.selected.containsAll(all)) a.selected.clear()
        else { a.selected.clear(); a.selected.addAll(all) }
        a.refreshSelection(); update()
    }

    fun update() {  // B-63(34): 선택수/총수 표기
        val n = sel?.selected?.size ?: 0
        val total = sel?.selectableVids()?.size ?: 0
        selCount.text = "${"%,d".format(n)} / ${"%,d".format(total)} 선택"  // B-64(44): 천단위 콤마
    }

    private fun vidOf(u: String): Vid? = sel?.selectableVids()?.find { it.uri.toString() == u }
    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_SHORT).show()

    // 진행 다이얼로그를 비모달로 — 배경(목록) 터치/스크롤이 다이얼로그 너머로 통과, dim 제거, 하단 배치.
    private fun nonModal(dlg: androidx.appcompat.app.AlertDialog) {
        dlg.window?.apply {
            setGravity(android.view.Gravity.BOTTOM)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
    }

    // ─── 배치 임베드 ───
    fun embedBatch() {
        val a = sel ?: return
        val items = a.selected.toList().mapNotNull { u ->
            val name = vidOf(u)?.name ?: return@mapNotNull null
            // 품번 추출 실패(xhamster 등 비품번)여도 제외하지 않고 빈 code 로 포함 → JEmbed 가 remux-only 처리.
            val code = JavCode.extract(name) ?: ""  // B-53: 공용 파서
            Uri.parse(u) to code
        }
        if (items.isEmpty()) { toast("선택 없음"); return }
        runEmbed(items, skipIfHasCover = false, titlePrefix = "임베드")
    }

    // B-52 A: 커버 보강 — 주어진 영상들 중 covr 없는 것만 hub 커버 재임베드(검증된 JEmbed 경로 재사용).
    fun coverFixBatch(items: List<Pair<Uri, String>>) {
        if (items.isEmpty()) { toast("대상 없음"); return }
        runEmbed(items, skipIfHasCover = true, titlePrefix = "커버 보강")
    }

    // B-68(52): 중앙 팝업 제거 → 하단 리치 배너(JobProgress)로 진행/중단. 선택모드는 즉시 빠져나옴.
    private fun runEmbed(items: List<Pair<Uri, String>>, skipIfHasCover: Boolean, titlePrefix: String) {
        exit()                                                  // 선택모드 종료(배너로 진행 추적)
        JobProgress.start("$titlePrefix (${"%,d".format(items.size)}개)", items.size)
        JEmbed.embedBatch(act, items,
            onProgress = { idx, total, code, stage, pct ->
                val one = if (stage == "remux") pct else if (stage == "embed") 100 else 0
                JobProgress.update(idx, "$code  $stage", one)
            },
            onDone = { ok, fail, _ ->
                runCatching { onReload() }
                val head = if (JobProgress.isCancelled()) "중단됨" else "완료"
                JobProgress.done("$titlePrefix $head: 성공 $ok, 실패 $fail")
            },
            cancel = { JobProgress.isCancelled() },             // 배너 중단 버튼 → 우아한 종료
            onItemDone = { uri, success -> if (success) runCatching { sel?.notifyItem(uri.toString()) } },
            skipIfHasCover = skipIfHasCover)
    }

    // ─── 배치 이동 ───
    fun moveBatch() {
        val a = sel ?: return
        val items = a.selected.toList().mapNotNull { u -> vidOf(u)?.let { Uri.parse(u) to it.name } }
        if (items.isEmpty()) { toast("선택 없음"); return }
        pickFolder(
            onInternal = { dir -> runMoveProgress(items) { onP, onD, cancel -> JMove.move(act, items, dir, onP, onD, { u -> sel?.removeItem(u.toString()) }, cancel) } },
            onSaf = { doc -> runMoveProgress(items) { onP, onD, cancel -> JMove.moveToSaf(act, items, doc, onP, onD, { u -> sel?.removeItem(u.toString()) }, cancel) } }
        )
    }

    private fun runMoveProgress(
        items: List<Pair<Uri, String>>,
        mover: ((Int, Int, String) -> Unit, (Int, Int, List<String>) -> Unit, () -> Boolean) -> Unit
    ) {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val ll = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val tv = TextView(act)
        val pb = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = items.size }
        val btnCancel = MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "중단" }
        ll.addView(tv); ll.addView(pb); ll.addView(btnCancel)
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle("이동 중 (${"%,d".format(items.size)}개)").setView(ll).setCancelable(false).create()
        btnCancel.setOnClickListener { cancelled.set(true); btnCancel.isEnabled = false; btnCancel.text = "중단 중… (현재 파일 완료 후)" }
        dlg.show(); nonModal(dlg)
        mover({ idx, _, name -> tv.text = "${"%,d".format(idx + 1)}/${"%,d".format(items.size)}   $name"; pb.progress = idx },
            { ok, fail, fails ->
                dlg.dismiss(); exit(); onReload()
                val head = if (cancelled.get()) "중단됨" else "이동 완료"
                val msg = "$head: 성공 $ok, 실패 $fail" +
                    if (fails.isNotEmpty()) "\n" + fails.take(3).joinToString("\n") else ""
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
            },
            { cancelled.get() })
    }

    // 우측 사이드 패널 폴더트리 — 내부저장소(File)+외부저장소(SAF) 탐색, 마지막 위치 기억(다음 이동 시 그 폴더부터).
    private fun pickFolder(
        onInternal: (String) -> Unit,
        onSaf: (DocumentFile) -> Unit
    ) {
        val prefs = act.getSharedPreferences("media_library", android.content.Context.MODE_PRIVATE)
        val dlg = android.app.Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 28)
            setBackgroundColor(0xF21A1A1A.toInt())
        }
        val pathTv = TextView(act).apply { setPadding(8, 8, 8, 16); textSize = 13f }
        val scroll = ScrollView(act)
        val listLl = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listLl)
        val moveBtn = MaterialButton(act).apply { text = "여기로 이동" }

        // 마지막 위치 복원
        var mode = prefs.getInt("move_mode", 0)
        var curFile: File? = prefs.getString("move_file", null)?.let { File(it) }?.takeIf { it.isDirectory }
        var curDoc: DocumentFile? = prefs.getString("move_doc", null)?.let {
            runCatching { DocumentFile.fromTreeUri(act, Uri.parse(it)) }.getOrNull()
        }?.takeIf { it.isDirectory }
        if (mode == 1 && curFile == null) mode = 0
        if (mode == 2 && curDoc == null) mode = 0

        fun saveLoc() = prefs.edit()
            .putInt("move_mode", mode)
            .putString("move_file", curFile?.absolutePath)
            .putString("move_doc", curDoc?.uri?.toString())
            .apply()
        fun addRow(text: String, onClick: () -> Unit) {
            listLl.addView(TextView(act).apply {
                this.text = text; textSize = 15f; setPadding(8, 26, 8, 26); setOnClickListener { onClick() }
            })
        }
        // B-64(46): 새 폴더 만들기 — 이름 입력 다이얼로그
        fun newFolder(onCreate: (String) -> Unit) {
            val et = android.widget.EditText(act).apply { hint = "폴더 이름" }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                .setTitle("새 폴더 만들기").setView(et)
                .setPositiveButton("만들기") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onCreate) }
                .setNegativeButton("취소", null).show()
        }
        fun render() {
            listLl.removeAllViews()
            when (mode) {
                0 -> {
                    pathTv.text = "대상 저장소 선택"; moveBtn.visibility = View.GONE
                    addRow("📁  내부저장소") { mode = 1; curFile = Environment.getExternalStorageDirectory(); render() }
                    for (t in SafTrees.all(act)) {
                        val doc = runCatching { DocumentFile.fromTreeUri(act, Uri.parse(t)) }.getOrNull()
                        if (doc != null) addRow("💾  ${doc.name ?: "외부저장소"}") { mode = 2; curDoc = doc; render() }
                    }
                }
                1 -> {
                    val cur = curFile!!; pathTv.text = cur.absolutePath; moveBtn.visibility = View.VISIBLE
                    addRow("⬆  ..") { val p = cur.parentFile; if (p != null) curFile = p else mode = 0; render() }
                    addRow("➕  새 폴더 만들기") { newFolder { n -> val nd = File(cur, n); if (nd.mkdir()) curFile = nd else toast("폴더 생성 실패"); render() } }
                    cur.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { d ->
                        addRow("📁  ${d.name}") { curFile = d; render() }
                    }
                }
                2 -> {
                    val cur = curDoc!!; pathTv.text = cur.name ?: "외부저장소"; moveBtn.visibility = View.VISIBLE
                    addRow("⬆  ..") { val p = cur.parentFile; if (p != null) curDoc = p else mode = 0; render() }
                    addRow("➕  새 폴더 만들기") { newFolder { n -> val nd = cur.createDirectory(n); if (nd != null) curDoc = nd else toast("폴더 생성 실패"); render() } }
                    cur.listFiles().filter { it.isDirectory }.sortedBy { (it.name ?: "").lowercase() }.forEach { d ->
                        addRow("📁  ${d.name}") { curDoc = d; render() }
                    }
                }
            }
            saveLoc()
        }
        moveBtn.setOnClickListener {
            saveLoc(); dlg.dismiss()
            when (mode) { 1 -> onInternal(curFile!!.absolutePath); 2 -> onSaf(curDoc!!) }
        }
        outer.addView(pathTv)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(moveBtn)
        dlg.setContentView(outer)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(Utils.convertDp(act, 340f), ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(android.view.Gravity.END)
        }
        render(); dlg.show()
    }
}
