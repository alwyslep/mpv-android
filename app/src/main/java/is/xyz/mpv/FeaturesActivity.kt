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
    // B-63(36): mpv.conf 의 "# @feat key|label|desc" 마커로 노출한 conf 옵션 (1층 토글)
    private data class ConfFeature(val key: String, val label: String, val desc: String, val option: String, val enabled: Boolean)

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
    private val prefs by lazy { getSharedPreferences("media_library", MODE_PRIVATE) }
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
        ensureLuaLoaded()   // 32-B: 노출 lua 는 항상 로드(mpv.conf 보정) — on/off 는 feat_* prefs 로

        sectionHeader("재생 기능")
        if (!mpvConf.exists())
            hint("mpv.conf 없음 — 아래 '원본 파일 관리'에서 GitHub 동기화 후 사용")
        for (f in features) {
            val installed = File(cfgDir, "scripts/${f.file}").exists()
            val stem = f.file.removeSuffix(".lua")
            val subs = if (installed) scanLuaFeatures(f.file) else emptyList()
            if (subs.size >= 2) {
                // B-63(37): 다기능 lua — 그룹 헤더 + 하위 기능별 토글(feat_<stem>_<id>). lua 는 user-data/aurora/feat/<stem>/<id> 로 읽음.
                groupHeader(f.title)
                for ((id, label, desc) in subs)
                    featureRow(label, desc, installed, prefs.getBoolean("feat_${stem}_$id", true)) { on ->
                        prefs.edit().putBoolean("feat_${stem}_$id", on).apply()
                        Toast.makeText(this, (if (on) "켬" else "끔") + " — 다음 재생부터 적용", Toast.LENGTH_SHORT).show()
                    }
            } else {
                featureRow(f.title, f.desc, installed, prefs.getBoolean("feat_$stem", true)) { on ->
                    prefs.edit().putBoolean("feat_$stem", on).apply()
                    Toast.makeText(this, (if (on) "켬" else "끔") + " — 다음 재생부터 적용", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // B-63(36): mpv.conf 의 # @feat 마커 옵션을 1층 토글로 (GUI 에 없는 고급 토글, input.conf 는 사용자 보존이라 제외)
        val confFeats = parseConfFeatures()
        if (confFeats.isNotEmpty()) {
            sectionHeader("설정 (mpv.conf)")
            for (cf in confFeats) featureRow(cf.label, cf.desc, true, cf.enabled) { on ->
                prefs.edit().putBoolean("feat_conf_${cf.key}", on).apply()
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

    // B-63(37): lua 상단 40줄의 "-- @feature id|label|desc" 메타 스캔 (다기능 lua 그룹화 인프라).
    //   한 lua 에 2개 이상이면 1층에서 그룹(하위 토글)으로 노출. 없거나 1개면 기존 단일 feat_<stem>.
    //   lua 측은 get_property_native("user-data/aurora/feat/<stem>/<id>") 로 각 하위기능 on/off 읽음.
    private fun scanLuaFeatures(file: String): List<Triple<String, String, String>> {
        val f = File(cfgDir, "scripts/$file")
        if (!f.exists()) return emptyList()
        val out = ArrayList<Triple<String, String, String>>()
        try {
            for (line in f.readText().lineSequence().take(40)) {
                val t = line.trim()
                if (!t.startsWith("-- @feature ")) continue
                val spec = t.removePrefix("-- @feature ").split("|")
                if (spec.size >= 2) out.add(Triple(spec[0].trim(), spec[1].trim(), spec.getOrElse(2) { "" }.trim()))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun groupHeader(t: String) = listLl.addView(TextView(this).apply {
        text = "▸ $t"; setPadding(dp(4), dp(14), dp(4), dp(4)); textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    })

    // B-63(36): mpv.conf 의 "# @feat key|label|desc" 마커 + 다음 줄(대상 옵션)에서 옵션명 추출.
    //   토글 상태는 feat_conf_<key> SharedPrefs(영속) — conf 파일은 안 건드림(config_version 갱신에도 유지).
    //   적용은 MPVActivity 파일 로드 시 OFF 면 `set <option> no` (lua feat_* 와 동일 패턴).
    private fun parseConfFeatures(): List<ConfFeature> {
        if (!mpvConf.exists()) return emptyList()
        val lines = mpvConf.readText().lines()
        val out = ArrayList<ConfFeature>()
        for (i in lines.indices) {
            val t = lines[i].trim()
            if (!t.startsWith("# @feat ")) continue
            val spec = t.removePrefix("# @feat ").split("|")
            if (spec.size < 2) continue
            val ti = i + 1
            if (ti >= lines.size) continue
            val opt = lines[ti].trim().removePrefix("#").substringBefore("=").trim()
            if (opt.isEmpty()) continue
            val key = spec[0].trim()
            out.add(ConfFeature(key, spec[1].trim(), spec.getOrElse(2) { "" }.trim(),
                opt, prefs.getBoolean("feat_conf_$key", true)))
        }
        return out
    }

    // 32-B: 노출 lua 는 항상 로드돼야 user-data 런타임 토글이 동작 → mpv.conf 에서 주석된 노출 lua 활성화(1회 보정).
    //   (on/off 는 더 이상 script= 주석이 아니라 feat_* prefs 로 제어. deprecated 3개는 목록에 없어 건드리지 않음.)
    private fun ensureLuaLoaded() {
        if (!mpvConf.exists()) return
        val lines = mpvConf.readText().lines().toMutableList()
        var changed = false
        for (f in features) {
            for (i in lines.indices) {
                val raw = lines[i].trim()
                val bare = raw.removePrefix("#").trim()
                if (raw.startsWith("#") && bare.startsWith("script=") && bare.endsWith("/${f.file}")) {
                    lines[i] = "script=/storage/emulated/0/mpv/scripts/${f.file}"; changed = true
                }
            }
        }
        if (changed) mpvConf.writeText(lines.joinToString("\n"))
    }
}
