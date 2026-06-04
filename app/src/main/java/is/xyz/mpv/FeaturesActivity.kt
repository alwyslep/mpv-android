package `is`.xyz.mpv

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

/**
 * 32 — 1층(기능 레이어). 번들 스크립트가 제공하는 '기능'을 사람이 읽는 항목으로 나열.
 * on/off = mpv.conf 의 `script=` 줄 주석(#) 토글 — mpv-android 는 scripts/ 자동로드를 안 하므로
 *   이 줄이 실제 로드 스위치(.off rename 방식은 무력이라 폐기).
 * 구체 파일 입력·편집(.conf/.lua/.js, GitHub동기화/파일/직접입력)은 2층 ScriptsActivity.
 * 내부 인프라(_soul_manager/multiwindow_fix/aurora_bridge)는 비노출, deprecated 는 '대체됨' 표시.
 */
class FeaturesActivity : AppCompatActivity() {
    private data class Feature(val file: String, val title: String, val desc: String)

    // 노출(사용자 기능) — mpv/앱 기본과 겹치지 않는 보완 기능
    private val features = listOf(
        Feature("jav_osd.lua", "JAV 품번 OSD", "i 표시 · I 고정 · Ctrl+c 코드복사"),
        Feature("precise_speed.lua", "정밀 배속", "[ / ] 0.05단위 · BS 리셋"),
        Feature("bookmarks.lua", "책갈피", "b 추가 · B 목록 · ' 이전 · ; 다음"),
        Feature("sub_style_toggle.lua", "자막 스타일 프리셋", "F1 순환"),
        Feature("screenshot_to_clip.lua", "스크린샷 + 코드 클립", "s (파일명 코드 클립보드)"),
        Feature("progress_bar.lua", "상단 진행바", "자동 (95%+ 남은시간)"),
    )
    // 앱 네이티브로 대체됨(비활성)
    private val replaced = listOf(
        Triple("favorite.lua", "즐겨찾기", "앱 찜(별·하트) 기능으로 대체됨"),
        Triple("playstats.lua", "재생 통계", "앱 시청 추적으로 대체됨"),
        Triple("prev_next_file.lua", "이전 / 다음 영상", "앱 자동 다음 재생으로 대체됨"),
    )

    private val cfgDir: File by lazy { Utils.mpvConfigDir() }
    private val mpvConf: File by lazy { File(cfgDir, "mpv.conf") }
    private lateinit var listLl: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuroraDrawable.apply(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "재생 기능 · 스크립트"
            navigationIcon = getDrawable(android.R.drawable.ic_menu_close_clear_cancel)
            setNavigationOnClickListener { finish() }
        }
        val scroll = ScrollView(this)
        listLl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 32) }
        scroll.addView(listLl)
        root.addView(toolbar); root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }   // 2층 다녀온 뒤(동기화/추가) 갱신

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        listLl.removeAllViews()
        val confText = if (mpvConf.exists()) mpvConf.readText() else ""

        sectionHeader("재생 기능")
        if (!mpvConf.exists())
            hint("mpv.conf 없음 — 아래 '원본 파일 관리'에서 GitHub 동기화 후 사용")
        for (f in features) {
            val installed = File(cfgDir, "scripts/${f.file}").exists()
            featureRow(f.title, f.desc, installed, scriptEnabled(confText, f.file)) { on ->
                setScriptEnabled(f.file, on)
                Toast.makeText(this, (if (on) "켬" else "끔") + " — 다음 재생부터 적용", Toast.LENGTH_SHORT).show()
            }
        }

        sectionHeader("관리")
        linkRow("⚙  원본 파일 관리 (.conf / .lua / .js · GitHub 동기화)") {
            startActivity(Intent(this, ScriptsActivity::class.java))
        }

        sectionHeader("앱 기능으로 대체됨 (비활성)")
        for ((_, title, why) in replaced) replacedRow(title, why)
    }

    private fun sectionHeader(t: String) = listLl.addView(TextView(this).apply {
        text = t; setPadding(dp(4), dp(18), dp(4), dp(6)); textSize = 13f; alpha = 0.7f
    })

    private fun hint(t: String) = listLl.addView(TextView(this).apply {
        text = t; setPadding(dp(8), dp(6), dp(8), dp(6)); textSize = 12f; alpha = 0.6f
    })

    private fun featureRow(title: String, desc: String, installed: Boolean, on: Boolean, toggle: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = title; textSize = 16f })
        texts.addView(TextView(this).apply {
            text = if (installed) desc else "$desc  · 미설치"; textSize = 12f; alpha = 0.6f
        })
        val sw = SwitchCompat(this).apply {
            isChecked = on && installed
            isEnabled = installed
            setOnCheckedChangeListener { _, c -> toggle(c) }
        }
        row.addView(texts); row.addView(sw)
        listLl.addView(row)
    }

    private fun linkRow(label: String, onClick: () -> Unit) = listLl.addView(TextView(this).apply {
        text = label; textSize = 15f; setPadding(dp(8), dp(14), dp(8), dp(14)); setOnClickListener { onClick() }
    })

    private fun replacedRow(title: String, why: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)); alpha = 0.45f }
        box.addView(TextView(this).apply { text = title; textSize = 15f })
        box.addView(TextView(this).apply { text = why; textSize = 12f })
        listLl.addView(box)
    }

    // mpv.conf 의 `script=...<file>` 줄이 비주석(활성)인가
    private fun scriptEnabled(confText: String, file: String): Boolean =
        confText.lines().any { val l = it.trim(); !l.startsWith("#") && l.startsWith("script=") && l.endsWith("/$file") }

    // 해당 줄 주석/해제 토글 후 mpv.conf 재작성. 줄이 없고 켜는 경우 새로 추가.
    private fun setScriptEnabled(file: String, on: Boolean) {
        if (!mpvConf.exists()) return
        val lines = mpvConf.readText().lines().toMutableList()
        var found = false
        for (i in lines.indices) {
            val bare = lines[i].trim().removePrefix("#").trim()
            if (bare.startsWith("script=") && bare.endsWith("/$file")) {
                lines[i] = (if (on) "" else "#") + "script=/storage/emulated/0/mpv/scripts/$file"
                found = true
            }
        }
        if (!found && on) lines.add("script=/storage/emulated/0/mpv/scripts/$file")
        mpvConf.writeText(lines.joinToString("\n"))
    }
}
