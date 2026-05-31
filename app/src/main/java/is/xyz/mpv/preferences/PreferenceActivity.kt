package `is`.xyz.mpv.preferences

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
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
        }
    }

    // P2: mpv 에 없던 '미디어 라이브러리' 설정(우리 추가 기능 관리). prefs 파일은 LibPrefs 와 동일("media_library").
    class MediaLibraryPreference : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "media_library"
            setPreferencesFromResource(R.xml.pref_media_library, rootKey)
            findPreference<Preference>("action_rebuild_index")?.setOnPreferenceClickListener {
                `is`.xyz.mpv.SearchIndex.clear()
                android.widget.Toast.makeText(requireContext(), "검색 인덱스를 비웠습니다 (다음 검색 시 재인덱싱)", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("action_clear_trees")?.setOnPreferenceClickListener {
                `is`.xyz.mpv.SafTrees.clear(requireContext())
                `is`.xyz.mpv.SearchIndex.clear()
                android.widget.Toast.makeText(requireContext(), "등록된 폴더를 초기화했습니다", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("action_clear_thumbs")?.setOnPreferenceClickListener {
                `is`.xyz.mpv.ThumbLoader.clearCache()
                android.widget.Toast.makeText(requireContext(), "썸네일 캐시를 비웠습니다", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}
