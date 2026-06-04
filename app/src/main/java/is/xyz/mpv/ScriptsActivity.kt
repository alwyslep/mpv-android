package `is`.xyz.mpv

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * mpv 설정·스크립트 통합 관리 — config-dir(/sdcard/mpv) 직접 편집(mpv 가 실제 읽는 곳).
 *  · 설정 파일(.conf): config-dir 루트.  · 스크립트(.lua/.js): scripts/ (토글=.off rename).
 *  · 추가 = URL(GitHub raw) / 파일(SAF) / 직접입력.  · 편집(직접입력 에디터) · 삭제.
 *  · GitHub 동기화 = mpv-config/manifest.json 의 conf·scripts 를 raw 로 일괄 pull(덮어쓰기).
 * (기존 설정>고급의 ConfigEditDialogPreference 는 앱 내부 filesDir 에 써 mpv 가 안 읽던 버그 — 이 화면으로 대체)
 */
class ScriptsActivity : AppCompatActivity() {
    private val cfgDir: File by lazy { Utils.mpvConfigDir().also { it.mkdirs() } }
    private val scriptsDir: File by lazy { File(cfgDir, "scripts").also { it.mkdirs() } }
    private lateinit var listLl: LinearLayout
    private val raw = "https://raw.githubusercontent.com/alwyslep/mpv-android/p1-launcher/mpv-config"

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuroraDrawable.apply(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "mpv 설정 · 스크립트"
            navigationIcon = getDrawable(android.R.drawable.ic_menu_close_clear_cancel)
            setNavigationOnClickListener { finish() }
        }
        toolbar.menu.add(0, 1, 0, "추가").apply { setIcon(android.R.drawable.ic_input_add); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        toolbar.menu.add(0, 2, 1, "GitHub 동기화").apply { setIcon(android.R.drawable.ic_popup_sync); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> addMenu(); 2 -> syncGithub() }
            true
        }
        val scroll = ScrollView(this)
        listLl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 32) }
        scroll.addView(listLl)
        root.addView(toolbar); root.addView(scroll)
        setContentView(root)
        cleanupOff()   // 32: .off 잔재 정리
        render()
    }

    private fun confFiles() = cfgDir.listFiles { f -> f.isFile && f.name.endsWith(".conf") }?.sortedBy { it.name } ?: emptyList()
    private fun scriptFiles() = scriptsDir.listFiles { f ->
        f.isFile && (f.name.endsWith(".lua") || f.name.endsWith(".js"))
    }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun render() {
        listLl.removeAllViews()
        emptyHint("기능 켜기/끄기는 이전 화면 '재생 기능'에서. 여기는 원본 파일 추가·편집·삭제만.")
        sectionHeader("설정 파일 (.conf) — /sdcard/mpv")
        confFiles().let { if (it.isEmpty()) emptyHint("설정 파일 없음") else it.forEach { f -> row(f) } }
        sectionHeader("스크립트 (.lua / .js) — /sdcard/mpv/scripts")
        scriptFiles().let { if (it.isEmpty()) emptyHint("스크립트 없음") else it.forEach { f -> row(f) } }
    }

    private fun sectionHeader(t: String) = listLl.addView(TextView(this).apply {
        text = t; setPadding(8, 28, 8, 8); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
    })
    private fun emptyHint(t: String) = listLl.addView(TextView(this).apply { text = t; setPadding(16, 12, 8, 12); alpha = 0.6f })

    private fun row(f: File) {
        val rowLl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10)
        }
        rowLl.addView(TextView(this).apply {
            text = f.name; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 16, 0)
        })
        rowLl.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "편집"; setOnClickListener { editFile(f) }
        })
        rowLl.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "삭제"; setOnClickListener {
                AlertDialog.Builder(this@ScriptsActivity).setMessage("${f.name} 삭제?")
                    .setPositiveButton("삭제") { _, _ -> f.delete(); render() }.setNegativeButton("취소", null).show()
            }
        })
        listLl.addView(rowLl)
    }

    // 32: 1층 일원화 — 폐기된 .off 잔재 정리(원본 복원, 중복이면 삭제). on/off 는 1층 feat prefs.
    private fun cleanupOff() {
        for (d in arrayOf(cfgDir, scriptsDir)) {
            d.listFiles { f -> f.isFile && f.name.endsWith(".off") }?.forEach { f ->
                val orig = File(f.parentFile, f.name.removeSuffix(".off"))
                if (orig.exists()) f.delete() else f.renameTo(orig)
            }
        }
    }

    // 직접입력/편집 공용 — 멀티라인 monospace 에디터
    private fun editFile(f: File) {
        val et = EditText(this).apply {
            setText(if (f.exists()) f.readText() else ""); gravity = Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f; setPadding(24, 24, 24, 24)
        }
        val sc = ScrollView(this).apply { addView(et); minimumHeight = 900 }
        AlertDialog.Builder(this).setTitle(f.name.removeSuffix(".off")).setView(sc)
            .setPositiveButton("저장") { _, _ ->
                val body = et.text.toString()
                f.writeText(body)
                toastResult(f.name.removeSuffix(".off"), body, "저장됨 (재생/재시작 후 적용)"); render()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun addMenu() {
        AlertDialog.Builder(this).setTitle("추가")
            .setItems(arrayOf("GitHub / URL 에서", "파일에서 (저장소)", "직접 입력")) { _, which ->
                when (which) { 0 -> addFromUrl(); 1 -> pick.launch(arrayOf("*/*")); 2 -> addDirect() }
            }.show()
    }

    private fun addFromUrl() {
        val et = EditText(this).apply { hint = "https://...  (.conf / .lua / .js)" }
        AlertDialog.Builder(this).setTitle("URL 에서 가져오기").setView(et)
            .setPositiveButton("가져오기") { _, _ ->
                val url = et.text.toString().trim()
                if (url.isNotEmpty()) downloadOne(url)
            }.setNegativeButton("취소", null).show()
    }

    private fun addDirect() {
        val nameEt = EditText(this).apply { hint = "파일명 (예: my.lua, extra.conf)" }
        val bodyEt = EditText(this).apply {
            hint = "내용"; gravity = Gravity.TOP; minLines = 8
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f
        }
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8); addView(nameEt); addView(bodyEt) }
        AlertDialog.Builder(this).setTitle("직접 입력").setView(ScrollView(this).apply { addView(ll) })
            .setPositiveButton("저장") { _, _ ->
                val name = nameEt.text.toString().trim()
                val dest = destFor(name) ?: run { Toast.makeText(this, ".conf / .lua / .js 만 가능", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val body = bodyEt.text.toString()
                dest.writeText(body)
                toastResult(name, body, "추가: $name"); render()
            }.setNegativeButton("취소", null).show()
    }

    // 확장자로 저장 위치 결정 (.conf→config-dir 루트, .lua/.js→scripts/). 미지원 null.
    private fun destFor(name: String): File? = when {
        name.endsWith(".conf") -> File(cfgDir, name)
        name.endsWith(".lua") || name.endsWith(".js") -> File(scriptsDir, name)
        else -> null
    }

    private fun importFile(uri: Uri) {
        val name = displayName(uri)
        val dest = destFor(name) ?: run { Toast.makeText(this, ".conf / .lua / .js 만 가능", Toast.LENGTH_SHORT).show(); return }
        try {
            dest.outputStream().use { out -> contentResolver.openInputStream(uri)?.use { it.copyTo(out) } }
            toastResult(name, dest.readText(), "추가: $name"); render()
        } catch (e: Throwable) { Toast.makeText(this, "실패: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun downloadOne(url: String) {
        val name = url.substringAfterLast('/').substringBefore('?')
        val dest = destFor(name) ?: run { Toast.makeText(this, "지원하지 않는 확장자: $name", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "다운로드 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = pullTo(url, dest)
            ui { if (ok) toastResult(name, dest.readText(), "추가: $name") else Toast.makeText(this, "실패: $url", Toast.LENGTH_SHORT).show(); render() }
        }.start()
    }

    private fun syncGithub() {
        Toast.makeText(this, "GitHub 동기화 중…", Toast.LENGTH_SHORT).show()
        Thread {
            var n = 0
            try {
                val mtext = URL("$raw/manifest.json").openStream().bufferedReader().use { it.readText() }
                val mo = JSONObject(mtext)
                mo.optJSONArray("conf")?.let { for (i in 0 until it.length()) if (pullTo("$raw/${it.getString(i)}", File(cfgDir, it.getString(i)))) n++ }
                mo.optJSONArray("scripts")?.let { for (i in 0 until it.length()) if (pullTo("$raw/scripts/${it.getString(i)}", File(scriptsDir, it.getString(i)))) n++ }
            } catch (_: Throwable) {}
            val cnt = n
            ui { Toast.makeText(this, "동기화 완료: ${cnt}개 갱신", Toast.LENGTH_LONG).show(); render() }
        }.start()
    }

    private fun pullTo(url: String, dest: File): Boolean = try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 4000; con.readTimeout = 8000
        val body = con.inputStream.bufferedReader().use { it.readText() }
        con.disconnect(); dest.writeText(body); true
    } catch (_: Throwable) { false }

    // 30: 등록 정합성 검증 — null=정상, 아니면 경고 문구(토스트). aurora_bridge.lua 등록 명령과 동기화.
    private val auroraCmds = setOf("aurora-thumb", "aurora-lock", "aurora-pip", "aurora-menu", "aurora-exit")
    private fun validateRegistration(name: String, text: String): String? {
        val low = name.lowercase()
        when {
            low.endsWith(".lua") || low.endsWith(".js") ->
                return if (text.isBlank()) "⚠ 빈 스크립트: $name" else null
            low == "mpv.conf" -> return null                       // 옵션 파일 — 키 검증 안 함
            low == "input.conf" -> { /* 아래 키바인딩 검증 */ }
            low.endsWith(".conf") ->
                return "⚠ mpv 는 input.conf / mpv.conf 만 읽음 — '$name' 은 미반영"
            else -> return null
        }
        // input.conf 키바인딩 파싱 — 중복 키(충돌) + aurora 오타
        val seen = HashMap<String, Int>()
        val dups = LinkedHashSet<String>()
        val badAurora = LinkedHashSet<String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val key = line.split(Regex("\\s+"), 2).first().trim()
            if (key.isEmpty()) continue
            val rest = line.removePrefix(key).trim()
            seen[key] = (seen[key] ?: 0) + 1
            if (seen[key] == 2) dups.add(key)
            Regex("script-message\\s+(aurora-\\S+)").find(rest)?.let {
                if (it.groupValues[1] !in auroraCmds) badAurora.add(it.groupValues[1])
            }
        }
        val msgs = ArrayList<String>()
        if (dups.isNotEmpty()) msgs.add("키 충돌 ${dups.size}건(${dups.joinToString(",")}) — 마지막만 적용됨")
        if (badAurora.isNotEmpty()) msgs.add("미등록 aurora 명령: ${badAurora.joinToString(",")}")
        return if (msgs.isEmpty()) null else "⚠ " + msgs.joinToString(" / ")
    }

    // 저장 후 검증 토스트 — 경고면 LONG, 정상이면 기본 문구 SHORT.
    private fun toastResult(name: String, text: String, okMsg: String) {
        val warn = validateRegistration(name, text)
        Toast.makeText(this, warn ?: okMsg, if (warn != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) { val nm = it.getString(0); if (!nm.isNullOrBlank()) return nm }
            }
        } catch (_: Throwable) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file.lua"
    }

    private fun ui(cb: () -> Unit) = Handler(Looper.getMainLooper()).post(cb)
}
