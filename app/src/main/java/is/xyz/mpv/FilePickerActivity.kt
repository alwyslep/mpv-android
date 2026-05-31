package `is`.xyz.mpv

import `is`.xyz.filepicker.AbstractFilePickerFragment
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.util.Log
import android.view.*
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Predicate
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import `is`.xyz.filepicker.DocumentPickerFragment
import `is`.xyz.filepicker.FilePickerFragment
import `is`.xyz.mpv.databinding.FragmentFilepickerChoiceBinding
import java.io.File
import java.io.FileFilter

class FilePickerActivity : AppCompatActivity(), AbstractFilePickerFragment.OnFilePickedListener {
    private var fragment: MPVFilePickerFragment? = null
    private var fragment2: MPVDocumentPickerFragment? = null

    private var lastSeenInsets: WindowInsets? = null

    private var documentOpener = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri ->
            if (isHome) playDirect(uri.toString())
            else finishWithResult(RESULT_OK, uri.toString())
        }
    }

    // P1 (launcher): home 모드 — FilePicker 를 "홈"으로 띄우고, 픽한 항목을 결과반환 대신
    //   곧장 MPVActivity 로 재생(브라우저 유지). 시동 루트는 저장된 SAF 트리, 없으면 1회 선택.
    private val isHome get() = intent.getIntExtra("skip", -1) == HOME

    private val homeTreeOpener = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            finish() // 첫 실행에 폴더 선택 취소 → 표시할 홈이 없으니 종료
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString("MainScreenFragment_remember_data", uri.toString()).apply()
        initDocPicker(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        Log.v(TAG, "FilePickerActivity: created")

        setContentView(R.layout.activity_filepicker)
        supportActionBar?.title = ""
        supportActionBar?.hide()

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        // The basic issue we have here is this: https://stackoverflow.com/questions/31190612/
        // Some part of the view hierarchy swallows the insets during fragment transitions
        // and it's impossible to invoke this calculation a second time (requestApplyInsets doesn't help).
        // For that reason I wrote this creative workaround, it works surprisingly well.
        findViewById<View>(R.id.fragment_container_view).setOnApplyWindowInsetsListener { _, insets ->
            lastSeenInsets = WindowInsets(insets)
            insets
        }

        when (intent.getIntExtra("skip", -1)) {
            URL_DIALOG -> {
                showUrlDialog()
                return
            }
            FILE_PICKER -> {
                initFilePicker()
                return
            }
            DOC_PICKER -> {
                val root = Uri.parse(intent.getStringExtra("root")!!)
                initDocPicker(root)
                return
            }
            HOME -> {
                initHome()
                return
            }
        }

        // Ask the user what he wants
        val args = Bundle().apply {
            putString("title", intent.getStringExtra("title"))
            putBoolean("allow_document", intent.getBooleanExtra("allow_document", false))
        }
        with (supportFragmentManager.beginTransaction()) {
            setReorderingAllowed(true)
            add(R.id.fragment_container_view, ChoiceFragment::class.java, args, null)
            commit()
        }
    }

    private fun doUiTweaks() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Part 2 of the workaround: apply the insets to the recycler so it can
        // take them into account.
        val recycler: RecyclerView = findViewById(android.R.id.list)
        lastSeenInsets?.let { recycler.onApplyWindowInsets(lastSeenInsets) }
    }

    private fun getFilterState(): Boolean {
        with (PreferenceManager.getDefaultSharedPreferences(this)) {
            // naming is a legacy leftover
            return getBoolean("MainActivity_filter_state", false)
        }
    }

    private fun saveFilterState(enabled: Boolean) {
        with (PreferenceManager.getDefaultSharedPreferences(this).edit()) {
            this.putBoolean("MainActivity_filter_state", enabled)
            apply()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (fragment == null)
            return
        if (permissions.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // re-init file picker with correct paths
            initFilePicker()
        }
    }

    private fun inflateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.menu_filepicker, menu)
        // document picker does not have a concept of storages
        if (fragment == null)
            menu.findItem(R.id.action_external_storage).isVisible = false
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION) {
            inflateOptionsMenu(menu)
        } else {
            // add a dummy menu item so the menu icon shows up, even though you can't use it on TV.
            // it is instead opened via dpad keys
            menu.add(Menu.NONE, Menu.NONE, Menu.NONE, "...")
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_external_storage -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    val path = Environment.getExternalStorageDirectory()
                    fragment!!.goToDir(path) // attempt to do something useful
                    return true
                }

                val vols = Utils.getStorageVolumes(this)

                with (AlertDialog.Builder(this)) {
                    setItems(vols.map { it.description }.toTypedArray()) { dialog, item ->
                        val vol = vols[item]
                        with (fragment!!) {
                            root = vol.path
                            goToDir(vol.path)
                        }
                        dialog.dismiss()
                    }
                    show()
                }
                return true
            }
            R.id.action_file_filter -> {
                var old = false
                fragment?.apply {
                    old = filterPredicate != null
                    filterPredicate = if (!old) MEDIA_FILE_FILTER else null
                }
                fragment2?.apply {
                    old = filterPredicate != null
                    filterPredicate = if (!old) MEDIA_DOC_FILTER else null
                }
                with (Toast.makeText(this, "", Toast.LENGTH_SHORT)) {
                    setText(if (!old) R.string.notice_show_media_files else R.string.notice_show_all_files)
                    show()
                }
                saveFilterState(!old)
                return true
            }
            else -> return false
        }
    }

    private fun initFilePicker() {
        // Create fragment first
        if (fragment == null) {
            fragment = MPVFilePickerFragment()
            with (supportFragmentManager.beginTransaction()) {
                setReorderingAllowed(true)
                add(R.id.fragment_container_view, fragment!!, null)
                runOnCommit { doUiTweaks() }
                commit()
            }
        }
        supportActionBar?.show()

        if (!FilePickerFragment.hasPermission(this, File("/"))) {
            Log.v(TAG, "FilePickerActivity: waiting for file picker permission")
            return
        }

        Log.v(TAG, "FilePickerActivity: showing file picker")
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (getFilterState())
            fragment!!.filterPredicate = MEDIA_FILE_FILTER

        var defaultPathStr = intent.getStringExtra("default_path")
        if (defaultPathStr.isNullOrEmpty()) {
            // TODO: rework or remove this setting
            defaultPathStr = sharedPrefs.getString("default_file_manager_path",
                Environment.getExternalStorageDirectory().path)
        }
        val defaultPath = File(defaultPathStr!!)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // check that the preferred path is inside a storage volume
            val vols = Utils.getStorageVolumes(this)
            var vol = vols.find { defaultPath.startsWith(it.path) }
            if (vol == null) {
                // looks like it wasn't
                Log.w(TAG, "default path set to \"$defaultPath\" but no such storage volume")
                vol = vols.firstOrNull()
                if (vol == null) {
                    Log.e(TAG, "can't find any volumes at all!")
                } else {
                    with(fragment!!) {
                        root = vol.path
                        goToDir(vol.path)
                    }
                }
            } else {
                with (fragment!!) {
                    root = vol.path
                    goToDir(defaultPath)
                }
            }
        } else {
            // Old device: go to preferred path but don't restrict root
            fragment!!.goToDir(defaultPath)
        }
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        // If up is pressed at the header element display the usual options menu as a popup menu
        // to make it usable on Android TV.
        var openMenu = false
        if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val recycler: RecyclerView = findViewById(android.R.id.list)
            val holder = try {
                window.currentFocus?.let { recycler.getChildViewHolder(it) }
            } catch (e: IllegalArgumentException) {
                null
            }
            openMenu = (holder is AbstractFilePickerFragment<*>.HeaderViewHolder)
        }
        if (openMenu) {
            PopupMenu(this, findViewById(R.id.context_anchor)).apply {
                setOnMenuItemClickListener {
                    this@FilePickerActivity.onOptionsItemSelected(it)
                }
                this@FilePickerActivity.inflateOptionsMenu(menu)
                show()
            }
            return true
        }

        return super.dispatchKeyEvent(ev)
    }

    private fun initDocPicker(root: Uri) {
        Log.v(TAG, "FilePickerActivity: showing document picker at \"$root\"")
        assert(fragment2 == null)
        fragment2 = MPVDocumentPickerFragment(root)
        supportActionBar?.show()

        val defaultPathStr = intent.getStringExtra("default_path")
        if (!defaultPathStr.isNullOrEmpty()) {
            fragment2!!.apply {
                goToDir(pathFromString(defaultPathStr))
            }
        }

        if (getFilterState())
            fragment2!!.filterPredicate = MEDIA_DOC_FILTER

        with (supportFragmentManager.beginTransaction()) {
            setReorderingAllowed(true)
            add(R.id.fragment_container_view, fragment2!!, null)
            runOnCommit { doUiTweaks() }
            commit()
        }
    }

    private fun showUrlDialog() {
        Log.v(TAG, "FilePickerActivity: showing url dialog")
        val helper = Utils.OpenUrlDialog(this)
        with (helper) {
            builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                if (isHome) playDirect(helper.text) else finishWithResult(RESULT_OK, helper.text)
            }
            builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            builder.setOnCancelListener { if (!isHome) finishWithResult(RESULT_CANCELED) }
            create().show()
        }
    }

    private fun onBackPressedImpl() {
        fragment?.apply {
            if (!isBackTop) {
                goUp()
                return
            }
        }
        fragment2?.apply {
            if (!isBackTop) {
                goUp()
                return
            }
        }
        finishWithResult(RESULT_CANCELED)
    }

    private fun finishWithResult(code: Int, path: String? = null) {
        val result = Intent()
        fragment?.apply {
            result.putExtra("last_path", pathToString(currentDir))
        }
        fragment2?.apply {
            result.putExtra("last_path", pathToString(currentDir))
        }
        if (path != null) {
            result.putExtra("path", path)
            Log.v(TAG, "FilePickerActivity: picked \"$path\"")
        } else {
            Log.v(TAG, "FilePickerActivity: nothing picked")
        }
        setResult(code, result)
        finish()
    }

    private fun playDirect(path: String) {
        val i: Intent = if (path.startsWith("content://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(path))
        } else {
            Intent().putExtra("filepath", path)
        }
        i.setClass(this, MPVActivity::class.java)
        startActivity(i)
    }

    private fun initHome() {
        setupHomeFab()
        val saved = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("MainScreenFragment_remember_data", null)
        if (!saved.isNullOrEmpty()) {
            val uri = Uri.parse(saved)
            if (DocumentPickerFragment.isTreeUsable(this, uri)) {
                initDocPicker(uri)
                return
            }
        }
        // 저장된 SAF 트리 없음/접근불가 → 1회 폴더 선택(이후 영구권한으로 바로 진입)
        homeTreeOpener.launch(null)
    }

    private fun setupHomeFab() {
        val menu = findViewById<View>(R.id.fab_menu)
        val main = findViewById<FloatingActionButton>(R.id.fab_main)
        main.visibility = View.VISIBLE
        main.setOnClickListener {
            menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<FloatingActionButton>(R.id.fab_url).setOnClickListener {
            menu.visibility = View.GONE
            showUrlDialog()
        }
        findViewById<FloatingActionButton>(R.id.fab_local).setOnClickListener {
            menu.visibility = View.GONE
            documentOpener.launch(arrayOf("*/*"))
        }
        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener {
            menu.visibility = View.GONE
            startActivity(Intent(this, `is`.xyz.mpv.preferences.PreferenceActivity::class.java))
        }
    }

    override fun onFilePicked(file: File) =
        if (isHome) playDirect(file.absolutePath) else finishWithResult(RESULT_OK, file.absolutePath)

    override fun onDirPicked(dir: File) =
        if (isHome) Unit else finishWithResult(RESULT_OK, dir.absolutePath)

    override fun onDocumentPicked(uri: Uri, isDir: Boolean) {
        assert(fragment2 != null)
        if (!isDir) {
            val p = fragment2!!.pathToString(uri)
            if (isHome) playDirect(p) else finishWithResult(RESULT_OK, p)
        }
    }

    override fun onCancelled() = finishWithResult(RESULT_CANCELED)

    class ChoiceFragment : Fragment(R.layout.fragment_filepicker_choice) {
        private lateinit var binding: FragmentFilepickerChoiceBinding

        private fun removeMyself() {
            with (requireActivity().supportFragmentManager.beginTransaction()) {
                setReorderingAllowed(true)
                remove(this@ChoiceFragment)
                commit()
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            binding = FragmentFilepickerChoiceBinding.bind(view)

            binding.message.text = requireArguments().getString("title")
            binding.fileBtn.setOnClickListener {
                removeMyself()
                (activity as FilePickerActivity).initFilePicker()
            }
            binding.urlBtn.setOnClickListener {
                // leave visible, dialog will exit anyway
                (activity as FilePickerActivity).showUrlDialog()
            }
            binding.docBtn.setOnClickListener {
                (activity as FilePickerActivity).documentOpener.launch(arrayOf("*/*"))
            }
            if (!requireArguments().getBoolean("allow_document", false))
                binding.docBtn.visibility = View.GONE
        }
    }

    companion object {
        private const val TAG = "mpv"

        private val MEDIA_FILE_FILTER = FileFilter { file ->
            if (file.isDirectory) {
                val contents: Array<String> = file.list() ?: arrayOf()
                // filter hidden files due to stuff like ".thumbnails"
                contents.filterNot { it.startsWith('.') }.any()
            } else {
                Utils.MEDIA_EXTENSIONS.contains(file.extension.lowercase())
            }
        }

        private val MEDIA_DOC_FILTER = Predicate<DocumentPickerFragment.Document> { doc ->
            if (doc.isDirectory) {
                true
            } else {
                val ext = doc.displayName.substringAfterLast('.', "")
                Utils.MEDIA_EXTENSIONS.contains(ext.lowercase())
            }
        }

        // values for "skip" in intent
        const val URL_DIALOG = 0
        const val FILE_PICKER = 1
        const val DOC_PICKER = 2
        const val HOME = 3
    }
}
