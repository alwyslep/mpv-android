package `is`.xyz.mpv.preferences

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import `is`.xyz.mpv.BuildConfig
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvLogLevel
import `is`.xyz.mpv.R
import `is`.xyz.mpv.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity(), MPVLib.LogObserver {
    private lateinit var binding: ActivityAboutBinding
    private var logs = ""
    private var mpvDestroyed = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.getBoolean("material_you_theming", false))
            DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.elevation = 0f
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        logs = CHANGELOG + "\n" + "mpv-android ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE})\n"

        // create mpv context to capture version info from log
        MPVLib.create(this)
        mpvDestroyed = false
        MPVLib.addLogObserver(this)
        MPVLib.init()
    }

    private fun updateLog() {
        runOnUiThread {
            binding.logs.text = prettify(logs)
        }
    }

    // "Configuration:" 줄(긴 -/-- 옵션 나열, 가로 한 줄)을 옵션마다 줄바꿈해 세로 목록으로 — 가독성.
    private fun prettify(s: String): String =
        s.split("\n").joinToString("\n") { line ->
            if (line.startsWith("Configuration:"))
                "Configuration:\n" + line.removePrefix("Configuration:").trim()
                    .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("\n") { "  $it" }
            else line
        }

    companion object {
        // mpv-aurora(JAV 라이브러리 전용 빌드) 변경이력 — mpv 정보 화면 맨 위에 표시.
        private val CHANGELOG = """
            ┌─ mpv-aurora · JAV 라이브러리 전용 빌드 ─┐
            변경이력
              v20  해상도 불일치 마커 (실제 vs 최고화질 ⚠)
              v19  파일 이동 — 내부↔외부(SAF) 전 조합
              v18  jembed 배치 임베드 + '임베드없음' 필터
                   스크립트(.lua/.js)·mpv.conf 관리
              v15  jembed(메타 직접 임베드) + TS→mp4 remux
                   외부저장소(USB/SD) SAF 통합
              v6   동적 오로라 배경 + 커버 크기 슬라이더
                   길이 불일치 마커 + 미디어 라이브러리 홈
            └────────────────────────────┘
        """.trimIndent()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!mpvDestroyed) {
            MPVLib.destroy()
            mpvDestroyed = true
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (prefix != "cplayer")
            return
        if (level == MpvLogLevel.MPV_LOG_LEVEL_V)
            logs += text

        if (text.startsWith("List of enabled features:", true)) {
            // stop receiving log messages and populate text field
            MPVLib.removeLogObserver(this)
            updateLog()
        }
    }
}
