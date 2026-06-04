package `is`.xyz.mpv.preferences

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import com.google.android.material.color.DynamicColors
import `is`.xyz.mpv.R

class PreferenceActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
    SharedPreferences.OnSharedPreferenceChangeListener, FragmentManager.OnBackStackChangedListener {
    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        supportFragmentManager.addOnBackStackChangedListener(this)
        // 7: 설정 리스트 투명화 → 오로라 배경 비침 (카드형 항목은 pref_jav_item)
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: androidx.fragment.app.Fragment, v: android.view.View, s: Bundle?) {
                    if (f is androidx.preference.PreferenceFragmentCompat) {
                        f.listView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        f.listView?.clipToPadding = false
                    }
                }
            }, true)
        if (preferences.getBoolean("material_you_theming", false))
            DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()

        val frameLayout = FrameLayout(this).apply {
            id = R.id.main
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(frameLayout)
        `is`.xyz.mpv.AuroraDrawable.apply(this)   // 설정 화면 배경 = 오로라 (슬라이더 라이브 미리보기)
        supportActionBar?.elevation = 0F
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (savedInstanceState != null) {
            supportActionBar?.subtitle = savedInstanceState.getCharSequence("subtitle")
        } else {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main, SettingsFragment())
                .commit()
        }
    }

    // ── 설정 제목 옆 EN/한글 언어 토글 (B-57) ──────────────────────
    //   AppCompat per-app locale 로 전환 → 설정(및 앱) 전체 문자열이 영어↔한글로.
    //   locales_config.xml(Android13+) + values-ko 완역 기반. 탭 시 액티비티 재생성.
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val item = menu.add(Menu.NONE, 1001, Menu.NONE, "Language")
        item.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
        item.actionView = buildLangToggle()
        return true
    }

    private fun buildLangToggle(): View {
        val dm = resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val isKo = tags.startsWith("ko")
        val accent = 0xFF8E3A99.toInt()
        val inactive = 0xFFB7BDC4.toInt()

        fun seg(label: String, active: Boolean, tag: String) = TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setTextColor(if (active) Color.WHITE else inactive)
            setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            if (active) background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(accent)
            }
            setOnClickListener {
                if (!active) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(0x33FFFFFF)
            }
            addView(seg("EN", !isKo, "en"))
            addView(seg("한글", isKo, "ko"))
            // 툴바 우측 여백
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            layoutParams = lp
        }
    }

    override fun onBackStackChanged() {
        if (supportFragmentManager.backStackEntryCount == 0) {
            supportActionBar?.subtitle = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence("subtitle", supportActionBar?.subtitle)
        supportFragmentManager.removeOnBackStackChangedListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key != null && key.startsWith("aurora_")) {   // 오로라 값 변경 → 배경 즉시 재적용(라이브 미리보기)
            `is`.xyz.mpv.AuroraDrawable.apply(this); return
        }
        if (key != "material_you_theming") return
        if (sharedPreferences.getBoolean(key, false))
            DynamicColors.applyToActivityIfAvailable(this)
        recreate()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat, pref: Preference
    ): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader, pref.fragment ?: return false
        ).apply { arguments = pref.extras }

        supportFragmentManager.beginTransaction().replace(R.id.main, fragment).addToBackStack(null)
            .commit()

        supportActionBar?.subtitle = pref.title
        return true
    }

    /**
     * The root preference fragment that displays preferences that link to the other preference
     * fragments below.
     */
    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_root, rootKey)
        }
    }

    class GeneralPreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_general, rootKey)
            // hide Material You on Android 11 or lower
            preferenceManager.findPreference<Preference>("material_you_theming")?.isVisible =
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        }
    }

    class VideoPreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_video, rootKey)
        }
    }

    class PlayerPreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_player, rootKey)
        }
    }

    class SubtitlePreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_subtitle, rootKey)
        }
    }

    class UIPreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_ui, rootKey)
            val packageManager = requireContext().packageManager
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
                findPreference<Preference>("auto_rotation")?.isEnabled = false

            // B-57 v3: 오로라 효과 프리셋 entries/values 를 AuroraPresets(SSOT)에서 주입.
            //   현재 앱 언어로 라벨 선택. summary 는 선택값 자동 표시.
            val ko = java.util.Locale.getDefault().language == "ko"
            findPreference<ListPreference>(`is`.xyz.mpv.AuroraDrawable.KEY_PRESET)?.apply {
                entries = `is`.xyz.mpv.AuroraPresets.labels(ko)
                entryValues = `is`.xyz.mpv.AuroraPresets.ids()
                if (value == null) value = `is`.xyz.mpv.AuroraPresets.DEFAULT_ID
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            // 속도 배율 슬라이더 하한(25%) — androidx SeekBarPreference 는 xml min 미지원.
            findPreference<SeekBarPreference>(`is`.xyz.mpv.AuroraDrawable.KEY_SPEED)?.min = 25
        }
    }

    class GesturePreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_gestures, rootKey)
            val packageManager = requireContext().packageManager
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                for (i in 0 until preferenceScreen.preferenceCount)
                    preferenceScreen.getPreference(i).isEnabled = false
            }
        }
    }

    class DeveloperPreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_developer, rootKey)
        }
    }

    class AdvancePreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.pref_advanced, rootKey)
            findPreference<Preference>("manage_scripts")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), `is`.xyz.mpv.FeaturesActivity::class.java))  // 32: 1층(기능 레이어)
                true
            }
            findPreference<Preference>("data_backup")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), `is`.xyz.mpv.DataBackupActivity::class.java))
                true
            }
        }
    }

    // P2: mpv 에 없던 '미디어 라이브러리' 설정(우리 추가 기능 관리). prefs 파일은 LibPrefs 와 동일("media_library").
    class MediaLibraryPreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "media_library"
            setPreferencesFromResource(R.xml.pref_media_library, rootKey)
            findPreference<Preference>("action_rebuild_index")?.setOnPreferenceClickListener {
                confirmDanger("검색 인덱스 재생성", "검색 인덱스를 비웁니다. 다음 검색 시 다시 만들어집니다.", "재생성") {
                    `is`.xyz.mpv.SearchIndex.clear(); toast("검색 인덱스를 비웠습니다")
                }; true
            }
            findPreference<Preference>("action_clear_trees")?.setOnPreferenceClickListener {
                confirmDanger("등록 폴더 초기화", "등록된 외부 폴더(USB/SD)를 모두 해제합니다. 다시 'FAB 폴더 열기'로 등록해야 합니다.", "초기화") {
                    `is`.xyz.mpv.SafTrees.clear(requireContext()); `is`.xyz.mpv.SearchIndex.clear(); toast("등록된 폴더를 초기화했습니다")
                }; true
            }
            findPreference<Preference>("action_clear_thumbs")?.setOnPreferenceClickListener {
                confirmDanger("썸네일 캐시 비우기", "썸네일 디스크 캐시를 모두 삭제합니다. 표시 시 다시 생성됩니다.", "비우기") {
                    `is`.xyz.mpv.ThumbLoader.clearCache(requireContext()); toast("썸네일 캐시를 비웠습니다")
                }; true
            }
        }

        private fun toast(m: String) = android.widget.Toast.makeText(requireContext(), m, android.widget.Toast.LENGTH_SHORT).show()

        // 17: 위험 액션 3중 안전장치 — ①경고 다이얼로그 ②체크박스 ③키워드 입력.
        private fun confirmDanger(title: String, warning: String, keyword: String, action: () -> Unit) {
            val ctx = requireContext()
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("⚠ $title")
                .setMessage("$warning\n\n오클릭 방지 — 계속하려면 2단계 확인이 필요합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("계속") { _, _ ->
                    val ll = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL; setPadding(56, 28, 56, 8)
                    }
                    val cb = android.widget.CheckBox(ctx).apply { text = "되돌릴 수 없음을 이해했습니다" }
                    val et = android.widget.EditText(ctx).apply { hint = "확인: '$keyword' 입력" }
                    ll.addView(cb); ll.addView(et)
                    val d = androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle(title).setView(ll)
                        .setNegativeButton("취소", null).setPositiveButton("실행", null).create()
                    d.setOnShowListener {
                        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (cb.isChecked && et.text.toString().trim() == keyword) { action(); d.dismiss() }
                            else toast("체크 + '$keyword' 입력이 필요합니다")
                        }
                    }
                    d.show()
                }.show()
        }
    }
}
