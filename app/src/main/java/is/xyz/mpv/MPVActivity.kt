package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.PlayerBinding
import `is`.xyz.mpv.MPVLib.MpvEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.os.*
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.provider.MediaStore
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.DisplayMetrics
import android.util.Rational
import androidx.core.content.ContextCompat
import android.view.*
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.io.File
import java.lang.IllegalArgumentException
import kotlin.math.roundToInt

typealias ActivityResultCallback = (Int, Intent?) -> Unit
typealias StateRestoreCallback = () -> Unit

class MPVActivity : AppCompatActivity(), MPVLib.EventObserver, TouchGesturesObserver {
    // for calls to eventUi() and eventPropertyUi()
    private val eventUiHandler = Handler(Looper.getMainLooper())
    // for use with fadeRunnable1..3
    private val fadeHandler = Handler(Looper.getMainLooper())
    // for use with stopServiceRunnable
    private val stopServiceHandler = Handler(Looper.getMainLooper())

    /**
     * DO NOT USE THIS
     */
    private var activityIsStopped = false

    private var activityIsForeground = true
    private var didResumeBackgroundPlayback = false
    private var userIsOperatingSeekbar = false

    private var toast: Toast? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequestCompat? = null
    private var audioFocusRestore: () -> Unit = {}

    private val psc = Utils.PlaybackStateCache()
    // 재생완료 위치기록: psc.eof() 가 position/duration 을 비우므로, END_FILE 직전 마지막 값 보존.
    private var lastPos = -1L
    private var lastDur = 0L
    // 트랙 선택 기억: eof/SHUTDOWN 경로에선 코어가 죽어 aid/sid 를 못 읽으므로 END_FILE 직전 보존.
    private var lastAid: String? = null
    private var lastSid: String? = null
    private var mediaSession: MediaSessionCompat? = null

    private lateinit var binding: PlayerBinding
    private lateinit var gestures: TouchGestures

    // convenience alias
    private val player get() = binding.player

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser)
                return
            player.timePos = progress.toDouble() / SEEK_BAR_PRECISION
            // Note: don't call updatePlaybackPos() here either
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
            showControls() // re-trigger display timeout
        }
    }

    private var becomingNoisyReceiverRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "noisy")
            }
        }
    }

    // Fade out controls
    private val fadeRunnable = object : Runnable {
        var hasStarted = false
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { hasStarted = true }

            override fun onAnimationCancel(animation: Animator) { hasStarted = false }

            override fun onAnimationEnd(animation: Animator) {
                if (hasStarted)
                    hideControls()
                hasStarted = false
            }
        }

        override fun run() {
            binding.topControls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION)
            binding.controls.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out unlock button
    private val fadeRunnable2 = object : Runnable {
        private val listener = object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.unlockBtn.visibility = View.GONE
            }
        }

        override fun run() {
            binding.unlockBtn.animate().alpha(0f).setDuration(CONTROLS_FADE_DURATION).setListener(listener)
        }
    }

    // Fade out gesture text
    private val fadeRunnable3 = object : Runnable {
        // okay this doesn't actually fade...
        override fun run() {
            binding.gestureTextView.visibility = View.GONE
        }
    }

    private val stopServiceRunnable = Runnable {
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)
    }

    /* Settings */
    private var statsFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var backgroundPlayMode = ""
    private var noUIPauseMode = ""

    private var shouldSavePosition = false

    private var autoRotationMode = ""

    private var controlsAtBottom = true
    private var showMediaTitle = false
    private var useTimeRemaining = false

    private var ignoreAudioFocus = false
    private var playlistExitWarning = true
    private var newIntentReplace = false

    private var smoothSeekGesture = false
    /* * */

    @SuppressLint("ClickableViewAccessibility")
    private fun initListeners() {
        with (binding) {
            prevBtn.setOnClickListener { playlistPrev() }
            nextBtn.setOnClickListener { playlistNext() }
            cycleAudioBtn.setOnClickListener { cycleAudio() }
            cycleSubsBtn.setOnClickListener { cycleSub() }
            playBtn.setOnClickListener { player.cyclePause() }
            cycleDecoderBtn.setOnClickListener { player.cycleHwdec() }
            cycleSpeedBtn.setOnClickListener { cycleSpeed() }
            topLockBtn.setOnClickListener { showToast("화면 잠금 (오작동 방지 — 다시 탭해 해제)"); lockUI() }
            topPiPBtn.setOnClickListener { showToast("작은 창으로 (PiP)"); goIntoPiP() }
            topMenuBtn.setOnClickListener { openTopMenu() }   // 더보기 메뉴(자막·오디오·챕터·고급 등)
            unlockBtn.setOnClickListener { unlockUI() }
            playbackDurationTxt.setOnClickListener {
                useTimeRemaining = !useTimeRemaining
                updatePlaybackPos(psc.positionSec)
                updatePlaybackDuration(psc.durationSec)
            }

            cycleAudioBtn.setOnLongClickListener { pickAudio(); true }
            cycleSpeedBtn.setOnLongClickListener { pickSpeed(); true }
            cycleSubsBtn.setOnLongClickListener { pickSub(); true }
            prevBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            nextBtn.setOnLongClickListener { openPlaylistMenu(pauseForDialog()); true }
            cycleDecoderBtn.setOnLongClickListener { pickDecoder(); true }

            playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)
        }

        player.setOnTouchListener { _, e ->
            if (lockedUI) false else gestures.onTouchEvent(e)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.outside) { v, windowInsets ->
            // guidance: https://medium.com/androiddevelopers/gesture-navigation-handling-visual-overlaps-4aed565c134c
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<MarginLayoutParams> {
                // avoid system bars and cutout
                leftMargin = insets.left
                topMargin = insets.top
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            onBackPressedImpl()
        }

        addOnPictureInPictureModeChangedListener { info ->
            onPiPModeChangedImpl(info.isInPictureInPictureMode)
        }
    }

    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()

    // Activity lifetime

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)

        // JAV Soul Edition: config/scripts live in /sdcard/mpv, which needs "all files access" on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showToast(getString(R.string.toast_all_files_access))
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (e2: Exception) { }
            }
            finish()
            return
        }

        // Do these here and not in MainActivity because mpv can be launched from a file browser
        Utils.copyAssets(this)
        BackgroundPlaybackService.createNotificationChannel(this)

        binding = PlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init controls to be hidden and view fullscreen
        hideControls()

        // Initialize listeners for the player view
        initListeners()

        gestures = TouchGestures(this)

        // set up initial UI state
        readSettings()
        onConfigurationChanged(resources.configuration)
        run {
            // edge-to-edge & immersive mode
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            binding.topPiPBtn.visibility = View.GONE
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
            binding.topLockBtn.visibility = View.GONE

        if (showMediaTitle)
            binding.controlsTitleGroup.visibility = View.VISIBLE

        updateOrientation(true)

        // Parse the intent
        val filepath = parsePathFromIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            parseIntentExtras(intent.extras)
        }
        // 4: 외부 앱(파일 매니저) 실행 등 position 미지정 시 저장 진행위치로 이어보기 — 앱 내부 Playback 과 통일
        if ((intent.extras?.getInt("position", 0) ?: 0) <= 0) {
            playbackUri()?.let { u ->
                Progress.get(this, u, mediaName())?.let { (pos, dur) ->
                    if (pos > 3000 && pos < dur - 3000)
                        onloadCommands.add(arrayOf("set", "file-local-options/start", "${pos / 1000f}"))
                }
            }
        }

        if (filepath == null) {
            Log.e(TAG, "No file given, exiting")
            showToast(getString(R.string.error_no_file))
            finishWithResult(RESULT_CANCELED)
            return
        }

        player.addObserver(this)
        player.initialize(Utils.mpvConfigDir().path, cacheDir.path)
        player.playFile(filepath)

        mediaSession = initMediaSession()
        updateMediaSession()
        BackgroundPlaybackService.mediaToken = mediaSession?.sessionToken

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioSessionId = audioManager!!.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR)
            player.setAudioSessionId(audioSessionId)
        else
            Log.w(TAG, "AudioManager.generateAudioSessionId() returned error")

        volumeControlStream = STREAM_TYPE
    }

    // 재생 중 파일의 원본 uri (이어보기/진행저장 키) — content uri 또는 filepath extra.
    private fun playbackUri(): String? = intent.dataString ?: intent.getStringExtra("filepath")

    // 1-2차: 캐시 키(품번/파일명) 산출용 파일명. 앱 내부=media_name extra, 외부=DISPLAY_NAME, 없으면 mpv filename.
    private fun mediaName(): String? {
        intent.getStringExtra("media_name")?.let { return it }
        intent.data?.let { d ->
            runCatching {
                contentResolver.query(d, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
                }
            }
        }
        return runCatching { MPVLib.getPropertyString("filename") }.getOrNull()
    }

    private fun finishWithResult(code: Int, includeTimePos: Boolean = false, includeTracks: Boolean = false,
                                 removed: Boolean = false) {
        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // FIXME: should track end-file events to accurately report OK vs CANCELED
        if (isFinishing) // only count first call
            return
        val result = Intent(RESULT_INTENT)
        result.data = if (intent.data?.scheme == "file") null else intent.data
        // B-68: 삭제/이동으로 현재 파일이 사라짐 → 런처가 다음 영상으로 advance 하도록 신호.
        if (removed) result.putExtra("removed", true)
        if (includeTimePos) {
            // psc 가 eof 로 비워졌으면(완료 경로) END_FILE 직전 보존값 사용
            val pos = if (psc.position >= 0) psc.position else lastPos
            val dur = if (psc.duration > 0) psc.duration else lastDur
            result.putExtra("position", pos.toInt())
            result.putExtra("duration", dur.toInt())
            // 4: 외부/내부 통일 — MPVActivity 가 직접 진행위치 저장(결과 반환에만 의존하지 않음)
            playbackUri()?.let { u -> if (dur > 0 && pos in 0..dur) Progress.save(this, u, mediaName(), pos, dur) }
        }
        if (includeTracks) {
            // 살아있는 경로(뒤로키)면 직접 읽고, eof/SHUTDOWN 경로면 END_FILE 보존값 사용.
            val aid = try { MPVLib.getPropertyString("aid") } catch (_: Throwable) { null } ?: lastAid
            val sid = try { MPVLib.getPropertyString("sid") } catch (_: Throwable) { null } ?: lastSid
            aid?.let { result.putExtra("saved_aid", it) }
            sid?.let { result.putExtra("saved_sid", it) }
            // 1-2차: 트랙 선택도 품번/파일명 키로 직접 저장(외부 실행 통일)
            playbackUri()?.let { u -> Tracks.save(this, u, mediaName(), aid ?: "", sid ?: "") }
        }
        setResult(code, result)
        finish()
    }

    override fun onDestroy() {
        Log.v(TAG, "Exiting.")

        // Suppress any further callbacks
        activityIsForeground = false

        if (becomingNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
        }

        BackgroundPlaybackService.mediaToken = null
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null

        audioFocusRequest?.let {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager!!, it)
        }
        audioFocusRequest = null

        // take the background service with us
        stopServiceRunnable.run()

        player.removeObserver(this)
        player.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        Log.v(TAG, "onNewIntent($intent)")
        super.onNewIntent(intent)

        // Happens when mpv is still running (not necessarily playing) and the user selects a new
        // file to be played from another app
        val filepath = intent?.let { parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }

        if (!activityIsForeground && didResumeBackgroundPlayback) {
            if (this.newIntentReplace) {
                MPVLib.command(arrayOf("loadfile", filepath, "replace"))
                showToast(getString(R.string.notice_file_play))
            } else {
                MPVLib.command(arrayOf("loadfile", filepath, "append"))
                showToast(getString(R.string.notice_file_appended))
            }
            moveTaskToBack(true)
        } else {
            MPVLib.command(arrayOf("loadfile", filepath))
        }
    }

    private fun updateAudioPresence() {
        val haveAudio = MPVLib.getPropertyBoolean("current-tracks/audio/selected")
        if (haveAudio == null) {
            // If we *don't know* if there's an active audio track then don't update to avoid
            // spurious UI changes. The property will become available again later.
            return
        }
        isPlayingAudio = (haveAudio && MPVLib.getPropertyBoolean("mute") != true)
    }

    private fun isPlayingAudioOnly(): Boolean {
        if (!isPlayingAudio)
            return false
        val image = MPVLib.getPropertyString("current-tracks/video/image")
        return image.isNullOrEmpty() || image == "yes"
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        return when (backgroundPlayMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isInMultiWindowMode || isInPictureInPictureMode) {
                Log.v(TAG, "Going into multi-window mode")
                super.onPause()
                return
            }
        }

        onPauseImpl()
    }

    private fun tryStartForegroundService(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, e)
                return false
            }
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
        return true
    }

    private fun onPauseImpl() {
        val fmt = MPVLib.getPropertyString("video-format")
        val shouldBackground = shouldBackground()
        if (shouldBackground && !fmt.isNullOrEmpty())
            BackgroundPlaybackService.thumbnail = MPVLib.grabThumbnail(THUMB_SIZE)
        else
            BackgroundPlaybackService.thumbnail = null
        // media session uses the same thumbnail
        updateMediaSession()

        activityIsForeground = false
        eventUiHandler.removeCallbacksAndMessages(null)
        if (isFinishing) {
            savePosition()
            // tell mpv to shut down so that any other property changes or such are ignored,
            // preventing useless busywork
            MPVLib.command(arrayOf("stop"))
        } else if (!shouldBackground) {
            player.paused = true
        }
        writeSettings()
        super.onPause()

        didResumeBackgroundPlayback = shouldBackground
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            stopServiceHandler.removeCallbacks(stopServiceRunnable)
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            if (!tryStartForegroundService(serviceIntent)) {
                didResumeBackgroundPlayback = false
                player.paused = true
            }
        }
    }

    private fun readSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(applicationContext)
        val getString: (String, Int) -> String = { key, defaultRes ->
            prefs.getString(key, resources.getString(defaultRes))!!
        }

        gestures.syncSettings(prefs, resources)

        val statsMode = prefs.getString("stats_mode", "") ?: ""
        this.statsFPS = statsMode == "native_fps"
        this.statsLuaMode = if (statsMode.startsWith("lua"))
            statsMode.removePrefix("lua").toInt()
        else
            0
        this.backgroundPlayMode = getString("background_play", R.string.pref_background_play_default)
        this.noUIPauseMode = getString("no_ui_pause", R.string.pref_no_ui_pause_default)
        this.shouldSavePosition = prefs.getBoolean("save_position", false)
        if (this.autoRotationMode != "manual") // don't reset
            this.autoRotationMode = getString("auto_rotation", R.string.pref_auto_rotation_default)
        this.controlsAtBottom = prefs.getBoolean("bottom_controls", true)
        this.showMediaTitle = prefs.getBoolean("display_media_title", false)
        this.useTimeRemaining = prefs.getBoolean("use_time_remaining", false)
        this.ignoreAudioFocus = prefs.getBoolean("ignore_audio_focus", false)
        this.playlistExitWarning = prefs.getBoolean("playlist_exit_warning", true)
        this.newIntentReplace = prefs.getBoolean("new_intent_replace", false)
        this.smoothSeekGesture = prefs.getBoolean("seek_gesture_smooth", false)
    }

    private fun writeSettings() {
        val prefs = getDefaultSharedPreferences(applicationContext)

        with (prefs.edit()) {
            putBoolean("use_time_remaining", useTimeRemaining)
            commit()
        }
    }

    override fun onStart() {
        super.onStart()
        activityIsStopped = false
    }

    override fun onStop() {
        super.onStop()
        activityIsStopped = true
    }

    override fun onResume() {
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            return
        }

        if (lockedUI) { // precaution
            Log.w(TAG, "resumed with locked UI, unlocking")
            unlockUI()
        }

        // Init controls to be hidden and view fullscreen
        hideControls()
        readSettings()

        activityIsForeground = true
        // stop background service with a delay
        stopServiceHandler.removeCallbacks(stopServiceRunnable)
        stopServiceHandler.postDelayed(stopServiceRunnable, 1000L)

        refreshUi()

        super.onResume()
    }

    private fun savePosition() {
        if (!shouldSavePosition)
            return
        if (MPVLib.getPropertyBoolean("eof-reached") ?: true) {
            Log.d(TAG, "player indicates EOF, not saving watch-later config")
            return
        }
        MPVLib.command(arrayOf("write-watch-later-config"))
    }

    /**
     * Requests or abandons audio focus and noisy receiver depending on the playback state.
     * @warning Call from event thread, not UI thread
     */
    private fun handleAudioFocus() {
        if ((psc.pause && !psc.cachePause) || !isPlayingAudio) {
            if (becomingNoisyReceiverRegistered)
                unregisterReceiver(becomingNoisyReceiver)
            becomingNoisyReceiverRegistered = false
            // TODO: could abandon audio focus after a timeout
        } else {
            if (!becomingNoisyReceiverRegistered)
                registerReceiver(
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                )
            becomingNoisyReceiverRegistered = true
            // (re-)request audio focus
            // Note that this will actually request focus every time the user unpauses, refer to discussion in #1066
            if (requestAudioFocus()) {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN, "request")
            } else {
                onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS, "request")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        val req = audioFocusRequest ?:
            with(AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)) {
            setAudioAttributes(with(AudioAttributesCompat.Builder()) {
                // N.B.: libmpv may use different values in ao_audiotrack, but here we always pretend to be music.
                setUsage(AudioAttributesCompat.USAGE_MEDIA)
                setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                build()
            })
            setOnAudioFocusChangeListener {
                onAudioFocusChange(it, "callback")
            }
            build()
        }
        val res = AudioManagerCompat.requestAudioFocus(manager, req)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
            return true
        }
        return false
    }

    // This handles both "real" audio focus changes by the callbacks, which aren't
    // really used anymore after Android 12 (except for AUDIOFOCUS_LOSS),
    // as well as actions equivalent to a focus change that we make up ourselves.
    private fun onAudioFocusChange(type: Int, source: String) {
        Log.v(TAG, "Audio focus changed: $type ($source)")
        if (ignoreAudioFocus || isFinishing)
            return
        when (type) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // loss can occur in addition to ducking, so remember the old callback
                val oldRestore = audioFocusRestore
                val wasPlayerPaused = player.paused ?: false
                player.paused = true
                audioFocusRestore = {
                    oldRestore()
                    if (!wasPlayerPaused) player.paused = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                MPVLib.command(arrayOf("multiply", "volume", AUDIO_FOCUS_DUCKING.toString()))
                audioFocusRestore = {
                    val inv = 1f / AUDIO_FOCUS_DUCKING
                    MPVLib.command(arrayOf("multiply", "volume", inv.toString()))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusRestore()
                audioFocusRestore = {}
            }
        }
    }

    // UI

    /** dpad navigation */
    private var btnSelected = -1

    private var mightWantToToggleControls = false

    /** true if we're actually outputting any audio (includes the mute state, but not pausing) */
    private var isPlayingAudio = false

    private var useAudioUI = false

    private var lockedUI = false

    private fun pauseForDialog(): StateRestoreCallback {
        val useKeepOpen = when (noUIPauseMode) {
            "always" -> true
            "audio-only" -> isPlayingAudioOnly()
            else -> false // "never"
        }
        if (useKeepOpen) {
            // don't pause but set keep-open so mpv doesn't exit while the user is doing stuff
            val oldValue = MPVLib.getPropertyString("keep-open")
            MPVLib.setPropertyBoolean("keep-open", true)
            return {
                oldValue?.also { MPVLib.setPropertyString("keep-open", it) }
            }
        }

        // Pause playback during UI dialogs
        val wasPlayerPaused = player.paused ?: true
        player.paused = true
        return {
            if (!wasPlayerPaused)
                player.paused = false
        }
    }

    private fun updateStats() {
        if (!statsFPS)
            return
        binding.statsTextView.text = getString(R.string.ui_fps, player.estimatedVfFps)
    }

    private fun controlsShouldBeVisible(): Boolean {
        if (lockedUI)
            return false
        return useAudioUI || btnSelected != -1 || userIsOperatingSeekbar
    }

    /** Make controls visible, also controls the timeout until they fade. */
    private fun showControls() {
        if (lockedUI) {
            Log.w(TAG, "cannot show UI in locked mode")
            return
        }

        // remove all callbacks that were to be run for fading
        fadeHandler.removeCallbacks(fadeRunnable)
        binding.controls.animate().cancel()
        binding.topControls.animate().cancel()

        // reset controls alpha to be visible
        binding.controls.alpha = 1f
        binding.topControls.alpha = 1f

        // bottom_controls OFF → 하단 컨트롤(seek/버튼) 숨김(제스처로 제어), 상단만 표시.
        // 이미 표시 중이면 재설정 안 함 — 터치 중 매번 set 되는 폭주(seek 방해/떨림) 방지.
        if (binding.controls.visibility != View.VISIBLE && binding.topControls.visibility != View.VISIBLE) {
            // 하단 seek/버튼 그룹: bottom_controls 또는 오디오일 때만. 숨김모드여도 제목(showMediaTitle)은 표시(5).
            val bottomOn = controlsAtBottom || useAudioUI
            binding.controlsSeekbarGroup.visibility = if (bottomOn) View.VISIBLE else View.GONE
            binding.controlsButtonGroup.visibility = if (bottomOn) View.VISIBLE else View.GONE
            binding.controls.visibility = if (bottomOn || showMediaTitle) View.VISIBLE else View.GONE
            binding.topControls.visibility = View.VISIBLE

            if (this.statsFPS) {
                updateStats()
                binding.statsTextView.visibility = View.VISIBLE
            }

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        // add a new callback to hide the controls once again
        if (!controlsShouldBeVisible())
            fadeHandler.postDelayed(fadeRunnable, CONTROLS_DISPLAY_TIMEOUT)
    }

    /** Hide controls instantly */
    fun hideControls() {
        if (controlsShouldBeVisible())
            return
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        binding.controls.visibility = View.GONE
        binding.topControls.visibility = View.GONE
        binding.statsTextView.visibility = View.GONE

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Start fading out the controls */
    private fun hideControlsFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    /**
     * Toggle visibility of controls (if allowed)
     * @return future visibility state
     */
    private fun toggleControls(): Boolean {
        if (lockedUI)
            return false
        if (controlsShouldBeVisible())
            return true
        return if (binding.controls.visibility == View.VISIBLE && !fadeRunnable.hasStarted) {
            hideControlsFade()
            false
        } else {
            showControls()
            true
        }
    }

    private fun showUnlockControls() {
        fadeHandler.removeCallbacks(fadeRunnable2)
        binding.unlockBtn.animate().setListener(null).cancel()

        binding.unlockBtn.alpha = 1f
        binding.unlockBtn.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable2, CONTROLS_DISPLAY_TIMEOUT)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (lockedUI) {
            showUnlockControls()
            return super.dispatchKeyEvent(ev)
        }

        // try built-in event handler first, forward all other events to libmpv
        val handled = interceptDpad(ev) ||
                (ev.action == KeyEvent.ACTION_DOWN && interceptKeyDown(ev)) ||
                player.onKey(ev)
        if (handled) {
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (lockedUI)
            return super.dispatchGenericMotionEvent(ev)

        if (ev != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (player.onPointerEvent(ev))
                return true
            // keep controls visible when mouse moves
            if (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
                showControls()
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (lockedUI) {
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_DOWN)
                showUnlockControls()
            return super.dispatchTouchEvent(ev)
        }

        if (super.dispatchTouchEvent(ev)) {
            // 컨트롤(하단/상단)이 보일 때 화면 터치는 표시 타임아웃 리셋. 단 MOVE(제스처 seek/볼륨/밝기) 중엔
            // 스킵 — 매 MOVE 마다 showControls 가 호출돼 제스처를 방해하고 화면을 떨게 하던 문제(4·6) 방지.
            if (ev.action != MotionEvent.ACTION_MOVE && !fadeRunnable.hasStarted &&
                (binding.controls.visibility == View.VISIBLE || binding.topControls.visibility == View.VISIBLE))
                showControls()
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN)
            mightWantToToggleControls = true
        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls) {
            toggleControls()
        }
        return true
    }

    /**
     * Returns views eligible for dpad button navigation
     */
    private fun dpadButtons(): Sequence<View> {
        val groups = arrayOf(binding.controlsButtonGroup, binding.topControls)
        return sequence {
            for (g in groups) {
                for (i in 0 until g.childCount) {
                    val view = g.getChildAt(i)
                    if (view.isEnabled && view.isVisible && view.isFocusable)
                        yield(view)
                }
            }
        }
    }

    private fun interceptDpad(ev: KeyEvent): Boolean {
        if (btnSelected == -1) { // UP and DOWN are always grabbed and overridden
            when (ev.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) { // activate dpad navigation
                        btnSelected = 0
                        updateSelectedDpadButton()
                        showControls()
                    }
                    return true
                }
            }
            return false
        }

        // this runs when dpad navigation is active:
        when (ev.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (ev.action == KeyEvent.ACTION_DOWN) { // deactivate dpad navigation
                    btnSelected = -1
                    updateSelectedDpadButton()
                    hideControlsFade()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    btnSelected = (btnSelected + 1) % dpadButtons().count()
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ev.action == KeyEvent.ACTION_DOWN) {
                    val count = dpadButtons().count()
                    btnSelected = (count + btnSelected - 1) % count
                    updateSelectedDpadButton()
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (ev.action == KeyEvent.ACTION_UP) {
                    val view = dpadButtons().elementAtOrNull(btnSelected)
                    // 500ms appears to be the standard
                    if (ev.eventTime - ev.downTime > 500L)
                        view?.performLongClick()
                    else
                        view?.performClick()
                }
                return true
            }
        }
        return false
    }

    private fun updateSelectedDpadButton() {
        val colorFocused = ContextCompat.getColor(this, R.color.tint_btn_bg_focused)
        val colorNoFocus = ContextCompat.getColor(this, R.color.tint_btn_bg_nofocus)

        dpadButtons().forEachIndexed { i, child ->
            if (i == btnSelected)
                child.setBackgroundColor(colorFocused)
            else
                child.setBackgroundColor(colorNoFocus)
        }
    }

    private fun interceptKeyDown(event: KeyEvent): Boolean {
        // intercept some keys to provide functionality "native" to
        // mpv-android even if libmpv already implements these
        var unhandled = 0

        when (event.unicodeChar.toChar()) {
            // (overrides a default binding)
            'j' -> cycleSub()
            '#' -> cycleAudio()

            else -> unhandled++
        }
        // Note: dpad center is bound according to how Android TV apps should generally behave,
        // see <https://developer.android.com/docs/quality-guidelines/tv-app-quality>.
        // Due to implementation inconsistencies enter and numpad enter need to perform the same
        // function (issue #963).
        when (event.keyCode) {
            // (no default binding)
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_MENU -> openTopMenu()
            KeyEvent.KEYCODE_GUIDE -> openTopMenu()
            // B-68: 물리 DEL 키 → 일시정지 + 액션 시트(찜/평점/장면/휴지통/이동/상세). DeX 키보드.
            KeyEvent.KEYCODE_FORWARD_DEL -> openActionMenu()
            // B-68: DeX Backspace — 재생 중엔 처음으로 되감기(메뉴 안에선 다이얼로그가 직접 '이전 메뉴' 처리).
            KeyEvent.KEYCODE_DEL -> MPVLib.command(arrayOf("seek", "0", "absolute"))
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> player.cyclePause()

            // (overrides a default binding)
            KeyEvent.KEYCODE_ENTER -> player.cyclePause()

            else -> unhandled++
        }

        return unhandled < 2
    }

    private fun onBackPressedImpl() {
        if (lockedUI)
            return showUnlockControls()

        val notYetPlayed = psc.playlistCount - psc.playlistPos - 1
        if (notYetPlayed <= 0 || !playlistExitWarning) {
            finishWithResult(RESULT_OK, true, true)
            return
        }

        val restore = pauseForDialog()
        with (AlertDialog.Builder(this)) {
            setMessage(getString(R.string.exit_warning_playlist, notYetPlayed))
            setPositiveButton(R.string.dialog_yes) { dialog, _ ->
                dialog.dismiss()
                finishWithResult(RESULT_OK, true, true)
            }
            setNegativeButton(R.string.dialog_no) { dialog, _ ->
                dialog.dismiss()
                restore()
            }
            create().show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = windowManager.currentWindowMetrics
            gestures.setMetrics(wm.bounds.width().toFloat(), wm.bounds.height().toFloat())
        } else @Suppress("DEPRECATION") {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            gestures.setMetrics(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        }

        // 하단 컨트롤은 표시될 때 화면 하단에 고정 (표시 여부는 bottom_controls = showControls 에서 처리)
        binding.controls.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = 0; leftMargin = 0; rightMargin = 0
        }
    }

    private fun onPiPModeChangedImpl(state: Boolean) {
        Log.v(TAG, "onPiPModeChanged($state)")
        if (state) {
            lockedUI = true
            hideControls()
            return
        }

        unlockUI()
        // For whatever stupid reason Android provides no good detection for when PiP is exited
        // so we have to do this shit <https://stackoverflow.com/questions/43174507/#answer-56127742>
        // If we don't exit the activity here it will stick around and not be retrievable from the
        // recents screen, or react to onNewIntent().
        if (activityIsStopped) {
            // Note: On Android 12 or older there's another bug with this: the result will not
            // be delivered to the calling activity and is instead instantly returned the next
            // time, which makes it looks like the file picker is broken.
            finishWithResult(RESULT_OK, true, true)
        }
    }

    private fun playlistPrev() = MPVLib.command(arrayOf("playlist-prev"))
    private fun playlistNext() = MPVLib.command(arrayOf("playlist-next"))

    private fun showToast(msg: String, cancel: Boolean = false) {
        if (cancel)
            toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
            show()
        }
    }

    // Intent/Uri parsing

    private fun parsePathFromIntent(intent: Intent): String? {
        fun safeResolveUri(u: Uri?): String? {
            return if (u != null && u.isHierarchical && !u.isRelative)
                resolveUri(u)
            else null
        }

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                // Normal file open or URL view
                intent.data?.let { resolveUri(it) }
            }

            Intent.ACTION_SEND -> {
                // Handle single shared file or text link
                var parsed = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (parsed == null) {
                    parsed = intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                        Uri.parse(it.trim())
                    }
                }

                safeResolveUri(parsed)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple shared files
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    val paths = uris.mapNotNull { uri ->
                        safeResolveUri(uri)
                    }
                    if (paths.size == 1) {
                        return paths[0]
                    } else if (!paths.isEmpty()) {
                        // Use a memory playlist
                        val memoryUri = "memory://#EXTM3U\n${paths.joinToString("\n")}\n"
                        Log.v(TAG, "Created memory playlist URI (${paths.size})")
                        return memoryUri
                    }
                }
                return null
            }

            else -> {
                // Custom intent from MainScreenFragment
                intent.getStringExtra("filepath")
            }
        }
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path
            "content" -> translateContentUri(data)
            // mpv supports data URIs but needs data:// to pass it through correctly
            "data" -> "data://${data.schemeSpecificPart}"
            "http", "https", "rtmp", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh",
            "tcp", "udp", "lavf", "ftp"
            -> data.toString()
            else -> null
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun translateContentUri(uri: Uri): String {
        val resolver = applicationContext.contentResolver
        Log.v(TAG, "Resolving content URI: $uri")
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // See if we can skip the indirection and read the real file directly
                val path = Utils.findRealPath(pfd.fd)
                if (path != null) {
                    Log.v(TAG, "Found real file path: $path")
                    return path
                }
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
        }

        // Otherwise, just let mpv open the content URI directly via ffmpeg
        return uri.toString()
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onloadCommands.clear()
        if (extras == null)
            return

        fun pushOption(key: String, value: String) {
            onloadCommands.add(arrayOf("set", "file-local-options/${key}", value))
        }

        // Refer to http://mpv-android.github.io/mpv-android/intent.html
        // Note: these only apply to the first file, it's not clear what the semantics for a
        // playlist should be.

        if (extras.getByte("decode_mode") == 2.toByte())
            pushOption("hwdec", "no")
        if (extras.containsKey("subs")) {
            val subList = Utils.getParcelableArray<Uri>(extras, "subs")
            val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onloadCommands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        extras.getInt("position", 0).let {
            if (it > 0)
                pushOption("start", "${it / 1000f}")
        }
        // 트랙 선택 기억 복원 (saved_aid/saved_sid)
        extras.getString("saved_aid", "").let {
            if (!it.isNullOrEmpty()) pushOption("aid", it)
        }
        extras.getString("saved_sid", "").let {
            if (!it.isNullOrEmpty()) pushOption("sid", it)
        }
        extras.getString("title", "").let {
            if (!it.isNullOrEmpty())
                pushOption("force-media-title", it)
        }
        // TODO: `headers` would be good, maybe `tls_verify`
    }

    // UI (Part 2)

    data class TrackData(val trackId: Int, val trackType: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> getString(R.string.track_audio)
            "sub"   -> getString(R.string.track_subs)
            "video" -> "Video"
            else    -> "???"
        }

        val msg = if (track_id == -1) {
            "$trackPrefix ${getString(R.string.track_off)}"
        } else {
            val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
            "$trackPrefix $trackName"
        }
        showToast(msg, true)
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio(); TrackData(player.aid, "audio")
    }
    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub(); TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
        val tracks = player.tracks.getValue(type)
        val selectedMpvId = get()
        val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
        val restore = pauseForDialog()

        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val trackId = tracks[item].mpvId

                set(trackId)
                dialog.dismiss()
                trackSwitchNotification { TrackData(trackId, type) }
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() {
        val restore = pauseForDialog()
        val impl = SubTrackDialog(player)
        lateinit var dialog: AlertDialog
        impl.listener = { it, secondary ->
            if (secondary)
                player.secondarySid = it.mpvId
            else
                player.sid = it.mpvId
            dialog.dismiss()
            trackSwitchNotification { TrackData(it.mpvId, SubTrackDialog.TRACK_TYPE) }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun openPlaylistMenu(restore: StateRestoreCallback) {
        val impl = PlaylistDialog(player)
        lateinit var dialog: AlertDialog

        impl.listeners = object : PlaylistDialog.Listeners {
            private fun openFilePicker(skip: Int) {
                openFilePickerFor(RCODE_LOAD_FILE, "", skip) { result, data ->
                    if (result == RESULT_OK) {
                        val path = data!!.getStringExtra("path")!!
                        MPVLib.command(arrayOf("loadfile", path, "append"))
                        impl.refresh()
                    }
                }
            }
            override fun pickFile() = openFilePicker(FilePickerActivity.FILE_PICKER)

            override fun openUrl() {
                val helper = Utils.OpenUrlDialog(this@MPVActivity)
                with (helper) {
                    builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                        MPVLib.command(arrayOf("loadfile", helper.text, "append"))
                        impl.refresh()
                    }
                    builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                    create().show()
                }
            }

            override fun onItemPicked(item: MPVView.PlaylistItem) {
                MPVLib.setPropertyInt("playlist-pos", item.index)
                dialog.dismiss()
            }
        }

        dialog = with (AlertDialog.Builder(this)) {
            val inflater = LayoutInflater.from(context)
            setView(impl.buildView(inflater))
            setOnDismissListener { restore() }
            create()
        }
        dialog.show()
    }

    private fun pickDecoder() {
        val restore = pauseForDialog()

        val items = mutableListOf(
            Pair("HW (mediacodec-copy)", "mediacodec-copy"),
            Pair("SW", "no")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            items.add(0, Pair("HW+ (mediacodec)", "mediacodec"))
        val hwdecActive = player.hwdecActive
        val selectedIndex = items.indexOfFirst { it.second == hwdecActive }
        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(items.map { it.first }.toTypedArray(), selectedIndex ) { dialog, idx ->
                MPVLib.setPropertyString("hwdec", items[idx].second)
                dialog.dismiss()
            }
            setOnDismissListener { restore() }
            create().show()
        }
    }

    private fun cycleSpeed() {
        player.cycleSpeed()
    }

    private fun pickSpeed() {
        // TODO: replace this with SliderPickerDialog
        val picker = SpeedPickerDialog()

        val restore = pauseForDialog()
        genericPickerDialog(picker, R.string.title_speed_dialog, "speed") {
            restore()
        }
    }

    private fun goIntoPiP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return
        updatePiPParams(true)
        enterPictureInPictureMode()
    }

    private fun lockUI() {
        lockedUI = true
        hideControlsFade()
    }

    private fun unlockUI() {
        binding.unlockBtn.visibility = View.GONE
        lockedUI = false
        showControls()
    }

    data class MenuItem(@IdRes val idRes: Int, val handler: () -> Boolean)
    private fun genericMenu(
            @LayoutRes layoutRes: Int, buttons: List<MenuItem>, hiddenButtons: Set<Int>,
            restoreState: StateRestoreCallback) {
        lateinit var dialog: AlertDialog

        // 24: 다크 테마 팝업(영상 위 라이트 방지). AppCompat 다이얼로그 — 커스텀 버튼 뷰 호환.
        val builder = AlertDialog.Builder(this, R.style.DarkPopupDialog)
        val dialogView = LayoutInflater.from(builder.context).inflate(layoutRes, null)

        for (button in buttons) {
            val buttonView = dialogView.findViewById<Button>(button.idRes)
            buttonView.setOnClickListener {
                val ret = button.handler()
                if (ret) // restore state immediately
                    restoreState()
                dialog.dismiss()
            }
        }

        hiddenButtons.forEach { dialogView.findViewById<View>(it).isVisible = false }

        if (Utils.visibleChildren(dialogView) == 0) {
            Log.w(TAG, "Not showing menu because it would be empty")
            restoreState()
            return
        }

        Utils.handleInsetsAsPadding(dialogView)

        with (builder) {
            setView(dialogView)
            setOnCancelListener { restoreState() }
            dialog = create()
        }
        dialog.show()
    }

    private fun openTopMenu() {
        val restoreState = pauseForDialog()

        fun addExternalThing(cmd: String, result: Int, data: Intent?) {
            if (result != RESULT_OK)
                return
            // file picker may return a content URI or a bare file path
            val path = data!!.getStringExtra("path")!!
            val path2 = if (path.startsWith("content://"))
                translateContentUri(Uri.parse(path))
            else
                path
            MPVLib.command(arrayOf(cmd, path2, "cached"))
        }

        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.audioBtn) {
                    openFilePickerFor(RCODE_EXTERNAL_AUDIO, R.string.open_external_audio) { result, data ->
                        addExternalThing("audio-add", result, data)
                        restoreState()
                    }; false
                },
                MenuItem(R.id.subBtn) {
                    openFilePickerFor(RCODE_EXTERNAL_SUB, R.string.open_external_sub) { result, data ->
                        addExternalThing("sub-add", result, data)
                        restoreState()
                    }; false
                },
                MenuItem(R.id.playlistBtn) {
                    openPlaylistMenu(restoreState); false
                },
                MenuItem(R.id.backgroundBtn) {
                    // restoring state may (un)pause so do that first
                    restoreState()
                    backgroundPlayMode = "always"
                    player.paused = false
                    moveTaskToBack(true)
                    false
                },
                MenuItem(R.id.chapterBtn) {
                    val chapters = player.loadChapters()
                    if (chapters.isEmpty())
                        return@MenuItem true
                    val chapterArray = chapters.map {
                        val timecode = Utils.prettyTime(it.time.roundToInt())
                        if (!it.title.isNullOrEmpty())
                            getString(R.string.ui_chapter, it.title, timecode)
                        else
                            getString(R.string.ui_chapter_fallback, it.index+1, timecode)
                    }.toTypedArray()
                    val selectedIndex = MPVLib.getPropertyInt("chapter") ?: 0
                    with (AlertDialog.Builder(this)) {
                        setSingleChoiceItems(chapterArray, selectedIndex) { dialog, item ->
                            MPVLib.setPropertyInt("chapter", chapters[item].index)
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
                MenuItem(R.id.chapterPrev) {
                    MPVLib.command(arrayOf("add", "chapter", "-1")); true
                },
                MenuItem(R.id.chapterNext) {
                    MPVLib.command(arrayOf("add", "chapter", "1")); true
                },
                MenuItem(R.id.advancedBtn) { openAdvancedMenu(restoreState); false },
                MenuItem(R.id.orientationBtn) {
                    autoRotationMode = "manual"
                    cycleOrientation()
                    true
                },
                MenuItem(R.id.thumbBtn) { setCurrentAsThumb(); true }
        )

        if (!isPlayingAudio)
            hiddenButtons.add(R.id.backgroundBtn)
        if ((MPVLib.getPropertyInt("chapter-list/count") ?: 0) == 0)
            hiddenButtons.add(R.id.rowChapter)
        /******/

        genericMenu(R.layout.dialog_top_menu, buttons, hiddenButtons, restoreState)
    }

    private fun genericPickerDialog(
        picker: PickerDialog, @StringRes titleRes: Int, property: String,
        restoreState: StateRestoreCallback
    ) {
        val dialog = with(AlertDialog.Builder(this)) {
            setTitle(titleRes)
            val inflater = LayoutInflater.from(context)
            setView(picker.buildView(inflater))
            setPositiveButton(R.string.dialog_ok) { _, _ ->
                picker.number?.let {
                    if (picker.isInteger())
                        MPVLib.setPropertyInt(property, it.toInt())
                    else
                        MPVLib.setPropertyDouble(property, it)
                }
            }
            setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            setOnDismissListener { restoreState() }
            create()
        }

        picker.number = MPVLib.getPropertyDouble(property)
        dialog.show()
    }

    // 개별 지정 — 재생 중 현재 장면을 이 영상의 라이브러리 썸네일로(정밀 지점). 일괄 %슬라이더와 공존.
    private fun setCurrentAsThumb() {
        val u = playbackUri() ?: return
        val posSec = MPVLib.getPropertyInt("time-pos") ?: return
        LibPrefs.setCustomThumbPos(this, MediaKey.of(u, mediaName()), posSec * 1000L)
        ThumbLoader.invalidate(this, Uri.parse(u))
        showToast("현재 장면을 라이브러리 썸네일로 지정 (커버 없는 영상에 적용)")
    }

    // ─── B-68: 재생 중 DEL 액션 시트 ───
    //   라이브러리 타일 액션(VideoActions/VideoTrash/JMove/Favorites/Ratings)을 재생 화면에서 호출.
    //   uri=playbackUri()(content/SAF), name=mediaName(). 찜·평점은 ReviewSync 로 queue.sqlite(rv_*) 자동 push.
    //   휴지통=VideoTrash(내부 MediaStore 30일/외부 SAF .mpv-trash), 이동=JMove(SAF 폴더 picker).
    //   메뉴가 떠 있는 동안 일시정지(pauseForDialog), 닫으면 자동 재개. 하위 흐름(평점/휴지통/이동/상세)은
    //   consumed=true 로 인계해 각자 재개 시점을 관리(취소 시 stuck-pause 방지).
    //   restore 파라미터: 하위 메뉴에서 'Backspace=이전 메뉴'로 돌아올 때 같은 일시정지 세션을 공유
    //   (재개 없이 메뉴 간 이동). DEL 최초 호출은 인자 생략 → pauseForDialog() 신규 세션.
    private fun openActionMenu(restore: StateRestoreCallback = pauseForDialog()) {
        val uri = playbackUri()
        if (uri == null) { showToast("이 영상엔 액션을 쓸 수 없음 (uri 없음)"); restore(); return }
        val name = mediaName() ?: ""
        var consumed = false

        val faved = Favorites.has(this, uri, name)
        val labels = arrayOf(
            if (faved) "★  찜 해제" else "☆  찜",
            "✪  평점 매기기",
            "🖼  현재 장면을 썸네일로",
            "🗑  휴지통으로 삭제",
            "📁  다른 폴더로 이동",
            "ℹ  상세 정보",
        )
        val dialog = AlertDialog.Builder(this, R.style.DarkPopupDialog)
            .setTitle(if (name.isNotEmpty()) name else getString(R.string.action_details))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> { Favorites.toggle(this, uri, name); showToast(if (!faved) "찜 추가" else "찜 해제") }
                    1 -> { consumed = true; actionRatingMenu(uri, name, restore) }
                    2 -> setCurrentAsThumb()
                    3 -> { consumed = true; actionTrashCurrent(uri, name, restore) }   // 일시정지 유지(완료까지)
                    4 -> { consumed = true; restore(); actionMoveCurrent(uri, name) }
                    5 -> { consumed = true; restore(); VideoDetailActivity.open(this, uri, name) }
                }
            }
            .setOnDismissListener { if (!consumed) restore() }
            .create()
        // B-68: DeX Backspace = 이전 메뉴 — 최상위 액션 메뉴에선 메뉴 닫고 영상으로 복귀.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_UP) { dialog.dismiss(); true } else false
        }
        dialog.show()
    }

    private fun actionRatingMenu(uri: String, name: String, restore: StateRestoreCallback) {
        val labels = arrayOf("★", "★★", "★★★", "★★★★", "★★★★★", getString(R.string.rating_none))
        val cur = Ratings.get(this, uri, name)
        val checked = if (cur in 1..5) cur - 1 else 5
        var goBack = false
        val d = AlertDialog.Builder(this, R.style.DarkPopupDialog)
            .setTitle(R.string.qs_rating)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                Ratings.set(this, uri, name, if (which == 5) 0 else which + 1)
                dlg.dismiss()
            }
            // Backspace → 이전(액션) 메뉴로, 그 외 닫힘 → 재개. 평점 선택 후엔 재개.
            .setOnDismissListener { if (goBack) openActionMenu(restore) else restore() }
            .create()
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_UP) { goBack = true; d.dismiss(); true } else false
        }
        d.show()
    }

    // 현재 재생 파일을 휴지통으로. 완료(실제 제거)까지 일시정지 유지 → 제거 성공 시 다음 영상 advance,
    //   취소 시 재개(restore). MediaStore(내부/SD)는 시스템 휴지통 결과(REQ)를 직접 관찰(낙관 종료 금지 —
    //   옛 버그: 확인 전 finish 가 시스템 확인창을 취소시켜 파일이 안 옮겨짐). 외부 SAF 는 moveDocument.
    private fun actionTrashCurrent(uri: String, name: String, restore: StateRestoreCallback) {
        val u = Uri.parse(uri)
        // B-68 진단: 어느 경로로 가는지 확정용(다음 빌드서 제거).
        showToast("trash: scheme=${u.scheme} auth=${u.authority} → ${if (u.authority == MediaStore.AUTHORITY) "MediaStore" else "SAF"}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && u.authority == MediaStore.AUTHORITY) {
            try {
                val pi = MediaStore.createTrashRequest(contentResolver, listOf(u), true)
                activityResultCallbacks[RCODE_TRASH] = cb@{ result, _ ->
                    if (result == RESULT_OK) {
                        runCatching { ThumbLoader.invalidate(this, u, name) }
                        showToast("휴지통으로 이동: $name"); afterCurrentFileGone()
                    } else restore()   // 취소 → 재개
                }
                startIntentSenderForResult(pi.intentSender, RCODE_TRASH, null, 0, 0, 0)
            } catch (e: Exception) { showToast("삭제 실패: ${e.message}"); restore() }
        } else {
            VideoTrash.confirmAndTrash(this, uri, name,
                onRemoved = { afterCurrentFileGone() },
                onCancel = { restore() })
        }
    }

    // 현재 재생 파일을 다른 폴더로. 시스템 폴더 picker(ACTION_OPEN_DOCUMENT_TREE) → JMove.moveToSaf.
    private fun actionMoveCurrent(uri: String, name: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        activityResultCallbacks[RCODE_MOVE_DEST] = cb@{ result, data ->
            val tree = if (result == RESULT_OK) data?.data else null
            if (tree == null) { showToast("이동 취소"); return@cb }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val dest = DocumentFile.fromTreeUri(this, tree)
            if (dest == null) { showToast("대상 폴더 열기 실패"); return@cb }
            showToast("이동 중: $name")
            JMove.moveToSaf(this, listOf(Uri.parse(uri) to name), dest,
                onProgress = { _, _, _ -> },
                onDone = { ok, _, fails ->
                    if (ok > 0) { showToast("이동 완료: $name"); afterCurrentFileGone() }
                    else showToast("이동 실패: ${fails.firstOrNull() ?: ""}")
                })
        }
        startActivityForResult(intent, RCODE_MOVE_DEST)
    }

    // 현재 재생 파일이 사라짐(휴지통/이동) → mpv 플레이리스트에 다음이 있으면 그걸로,
    //   없으면(라이브러리 단일파일 실행) removed 신호와 함께 종료 → 런처가 폴더 다음 영상으로 advance.
    private fun afterCurrentFileGone() {
        if (psc.playlistCount > 1 && psc.playlistPos < psc.playlistCount - 1)
            playlistNext()
        else
            finishWithResult(RESULT_OK, removed = true)
    }

    private fun openAdvancedMenu(restoreState: StateRestoreCallback) {
        /******/
        val hiddenButtons = mutableSetOf<Int>()
        val buttons: MutableList<MenuItem> = mutableListOf(
                MenuItem(R.id.subSeekPrev) {
                    MPVLib.command(arrayOf("sub-seek", "-1")); true
                },
                MenuItem(R.id.subSeekNext) {
                    MPVLib.command(arrayOf("sub-seek", "1")); true
                },
                MenuItem(R.id.statsBtn) {
                    MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle")); true
                },
                MenuItem(R.id.aspectBtn) {
                    val ratios = resources.getStringArray(R.array.aspect_ratios)
                    with (AlertDialog.Builder(this)) {
                        setItems(R.array.aspect_ratio_names) { dialog, item ->
                            if (ratios[item] == "panscan") {
                                MPVLib.setPropertyString("video-aspect-override", "-1")
                                MPVLib.setPropertyDouble("panscan", 1.0)
                            } else {
                                MPVLib.setPropertyString("video-aspect-override", ratios[item])
                                MPVLib.setPropertyDouble("panscan", 0.0)
                            }
                            dialog.dismiss()
                        }
                        setOnDismissListener { restoreState() }
                        create().show()
                    }; false
                },
        )

        val statsButtons = arrayOf(R.id.statsBtn1, R.id.statsBtn2, R.id.statsBtn3)
        for (i in 1..3) {
            buttons.add(MenuItem(statsButtons[i-1]) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-$i")); true
            })
        }

        // contrast, brightness and others get a -100 to 100 slider
        val basicIds = arrayOf(R.id.contrastBtn, R.id.brightnessBtn, R.id.gammaBtn, R.id.saturationBtn)
        val basicProps = arrayOf("contrast", "brightness", "gamma", "saturation")
        val basicTitles = arrayOf(R.string.contrast, R.string.video_brightness, R.string.gamma, R.string.saturation)
        basicIds.forEachIndexed { index, id ->
            buttons.add(MenuItem(id) {
                val slider = SliderPickerDialog(-100.0, 100.0, 1, R.string.format_fixed_number)
                genericPickerDialog(slider, basicTitles[index], basicProps[index], restoreState)
                false
            })
        }

        // audio / sub delay get a decimal picker
        buttons.add(MenuItem(R.id.audioDelayBtn) {
            val picker = DecimalPickerDialog(-600.0, 600.0)
            genericPickerDialog(picker, R.string.audio_delay, "audio-delay", restoreState)
            false
        })
        buttons.add(MenuItem(R.id.subDelayBtn) {
            val picker = SubDelayDialog(-600.0, 600.0)
            val dialog = with(AlertDialog.Builder(this)) {
                setTitle(R.string.sub_delay)
                val inflater = LayoutInflater.from(context)
                setView(picker.buildView(inflater))
                setPositiveButton(R.string.dialog_ok) { _, _ ->
                    picker.delay1?.let { player.subDelay = it }
                    picker.delay2?.let { player.secondarySubDelay = it }
                }
                setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
                setOnDismissListener { restoreState() }
                create()
            }

            picker.delay1 = player.subDelay ?: 0.0
            picker.delay2 = if (player.secondarySid != -1) player.secondarySubDelay else null
            dialog.show()
            false
        })

        if (player.vid == -1)
            hiddenButtons.addAll(arrayOf(R.id.rowVideo1, R.id.rowVideo2, R.id.aspectBtn))
        if (player.aid == -1 || player.vid == -1)
            hiddenButtons.add(R.id.audioDelayBtn)
        if (player.sid == -1)
            hiddenButtons.addAll(arrayOf(R.id.subDelayBtn, R.id.rowSubSeek))
        /******/

        genericMenu(R.layout.dialog_advanced_menu, buttons, hiddenButtons, restoreState)
    }

    private fun cycleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private var activityResultCallbacks: MutableMap<Int, ActivityResultCallback> = mutableMapOf()
    private fun openFilePickerFor(requestCode: Int, title: String, skip: Int?, callback: ActivityResultCallback) {
        val intent = Intent(this, FilePickerActivity::class.java)
        intent.putExtra("title", title)
        intent.putExtra("allow_document", true)
        skip?.let { intent.putExtra("skip", it) }
        // start file picker at directory of current file
        val path = MPVLib.getPropertyString("path") ?: ""
        if (path.startsWith('/'))
            intent.putExtra("default_path", File(path).parent)

        activityResultCallbacks[requestCode] = callback
        startActivityForResult(intent, requestCode)
    }
    private fun openFilePickerFor(requestCode: Int, @StringRes titleRes: Int, callback: ActivityResultCallback) {
        openFilePickerFor(requestCode, getString(titleRes), null, callback)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultCallbacks.remove(requestCode)?.invoke(resultCode, data)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when resuming the activity
        updatePlaybackStatus(psc.pause)
        updatePlaybackPos(psc.positionSec)
        updatePlaybackDuration(psc.durationSec)
        updateAudioUI()
        updateOrientation()
        updateMetadataDisplay()
        updateDecoderButton()
        updateSpeedButton()
        updatePlaylistButtons()
        player.loadTracks()
    }

    private fun updateAudioUI() {
        val audioButtons = arrayOf(R.id.prevBtn, R.id.cycleAudioBtn, R.id.playBtn,
                R.id.cycleSpeedBtn, R.id.nextBtn)
        val videoButtons = arrayOf(R.id.cycleAudioBtn, R.id.cycleSubsBtn, R.id.playBtn,
                R.id.cycleDecoderBtn, R.id.cycleSpeedBtn)

        val shouldUseAudioUI = isPlayingAudioOnly()
        if (shouldUseAudioUI == useAudioUI)
            return
        useAudioUI = shouldUseAudioUI
        Log.v(TAG, "Audio UI: $useAudioUI")

        val seekbarGroup = binding.controlsSeekbarGroup
        val buttonGroup = binding.controlsButtonGroup

        if (useAudioUI) {
            // Move prev/next file from seekbar group to buttons group
            Utils.viewGroupMove(seekbarGroup, R.id.prevBtn, buttonGroup, 0)
            Utils.viewGroupMove(seekbarGroup, R.id.nextBtn, buttonGroup, -1)

            // Change button layout of buttons group
            Utils.viewGroupReorder(buttonGroup, audioButtons)

            // Show song title and more metadata
            binding.controlsTitleGroup.visibility = View.VISIBLE
            Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.titleTextView, R.id.minorTitleTextView))
            updateMetadataDisplay()

            showControls()
        } else {
            Utils.viewGroupMove(buttonGroup, R.id.prevBtn, seekbarGroup, 0)
            Utils.viewGroupMove(buttonGroup, R.id.nextBtn, seekbarGroup, -1)

            Utils.viewGroupReorder(buttonGroup, videoButtons)

            // Show title only depending on settings
            if (showMediaTitle) {
                binding.controlsTitleGroup.visibility = View.VISIBLE
                Utils.viewGroupReorder(binding.controlsTitleGroup, arrayOf(R.id.fullTitleTextView))
                updateMetadataDisplay()
            } else {
                binding.controlsTitleGroup.visibility = View.GONE
            }

            hideControls() // do NOT use fade runnable
        }

        // Visibility might have changed, so update
        updatePlaylistButtons()
    }

    private fun updateMetadataDisplay() {
        if (!useAudioUI) {
            if (showMediaTitle)
                binding.fullTitleTextView.text = psc.meta.formatTitle()
        } else {
            binding.titleTextView.text = psc.meta.formatTitle()
            binding.minorTitleTextView.text = psc.meta.formatArtistAlbum()
        }
    }

    private fun updatePlaybackPos(position: Int) {
        binding.playbackPositionTxt.text = Utils.prettyTime(position)
        if (useTimeRemaining) {
            val diff = psc.durationSec - position
            binding.playbackDurationTxt.text = if (diff <= 0)
                "-00:00"
            else
                Utils.prettyTime(-diff, true)
        }
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.progress = position * SEEK_BAR_PRECISION

        // Note: do NOT add other update functions here just because this is called every second.
        // Use property observation instead.
        updateStats()
    }

    private fun updatePlaybackDuration(duration: Int) {
        if (!useTimeRemaining)
            binding.playbackDurationTxt.text = Utils.prettyTime(duration)
        if (!userIsOperatingSeekbar)
            binding.playbackSeekbar.max = duration * SEEK_BAR_PRECISION
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        binding.playBtn.setImageResource(r)

        updatePiPParams()
        if (paused) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateDecoderButton() {
        binding.cycleDecoderBtn.text = when (player.hwdecActive) {
            "mediacodec" -> "HW+"
            "no" -> "SW"
            else -> "HW"
        }
    }

    private fun updateSpeedButton() {
        binding.cycleSpeedBtn.text = getString(R.string.ui_speed, psc.speed)
    }

    private fun updatePlaylistButtons() {
        val plCount = psc.playlistCount
        val plPos = psc.playlistPos

        if (!useAudioUI && plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            binding.prevBtn.visibility = View.GONE
            binding.nextBtn.visibility = View.GONE
            return
        }
        binding.prevBtn.visibility = View.VISIBLE
        binding.nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(this, R.color.tint_disabled)
        val w = ContextCompat.getColor(this, R.color.tint_normal)
        binding.prevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        binding.nextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun updateOrientation(initial: Boolean = false) {
        // screen orientation is fixed (Android TV)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_SCREEN_PORTRAIT))
            return

        if (autoRotationMode != "auto") {
            if (!initial)
                return // don't reset at runtime
            requestedOrientation = when (autoRotationMode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (initial || player.vid == -1)
            return

        val ratio = player.getVideoAspect()?.toFloat() ?: 0f
        if (ratio == 0f || ratio in (1f / ASPECT_RATIO_MIN) .. ASPECT_RATIO_MIN) {
            // video is square, let Android do what it wants
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return
        }
        requestedOrientation = if (ratio > 1f)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    @RequiresApi(26)
    private fun makeRemoteAction(@DrawableRes icon: Int, @StringRes title: Int, intentAction: String): RemoteAction {
        val intent = NotificationButtonReceiver.createIntent(this, intentAction)
        return RemoteAction(Icon.createWithResource(this, icon), getString(title), "", intent)
    }

    /**
     * Update Picture-in-picture parameters. Will only run if in PiP mode unless
     * `force` is set.
     */
    private fun updatePiPParams(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return
        if (!isInPictureInPictureMode && !force)
            return

        val playPauseAction = if (psc.pause)
            makeRemoteAction(R.drawable.ic_play_arrow_black_24dp, R.string.btn_play, "PLAY_PAUSE")
        else
            makeRemoteAction(R.drawable.ic_pause_black_24dp, R.string.btn_pause, "PLAY_PAUSE")
        val actions = mutableListOf<RemoteAction>()
        if (psc.playlistCount > 1) {
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_previous_black_24dp, R.string.dialog_prev, "ACTION_PREV"
            ))
            actions.add(playPauseAction)
            actions.add(makeRemoteAction(
                R.drawable.ic_skip_next_black_24dp, R.string.dialog_next, "ACTION_NEXT"
            ))
        } else {
            actions.add(playPauseAction)
        }

        val params = with(PictureInPictureParams.Builder()) {
            val aspect = player.getVideoAspect() ?: 0.0
            setAspectRatio(Rational(aspect.times(10000).toInt(), 10000))
            setActions(actions)
        }
        try {
            setPictureInPictureParams(params.build())
        } catch (e: IllegalArgumentException) {
            // Android has some limits of what the aspect ratio can be
            params.setAspectRatio(Rational(1, 1))
            setPictureInPictureParams(params.build())
        }
    }

    // Media Session handling

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPause() {
            player.paused = true
        }
        override fun onPlay() {
            player.paused = false
        }
        override fun onSeekTo(pos: Long) {
            player.timePos = (pos / 1000.0)
        }
        override fun onSkipToNext() = playlistNext()
        override fun onSkipToPrevious() = playlistPrev()
        override fun onSetRepeatMode(repeatMode: Int) {
            MPVLib.setPropertyString("loop-playlist",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL) "inf" else "no")
            MPVLib.setPropertyString("loop-file",
                if (repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) "inf" else "no")
        }
        override fun onSetShuffleMode(shuffleMode: Int) {
            player.changeShuffle(false, shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL)
        }
    }

    private fun initMediaSession(): MediaSessionCompat {
        /*
            https://developer.android.com/guide/topics/media-apps/working-with-a-media-session
            https://developer.android.com/guide/topics/media-apps/audio-app/mediasession-callbacks
            https://developer.android.com/reference/android/support/v4/media/session/MediaSessionCompat
         */
        val session = MediaSessionCompat(this, TAG)
        session.setFlags(0)
        session.setCallback(mediaSessionCallback)
        return session
    }

    private fun updateMediaSession() {
        synchronized (psc) {
            mediaSession?.let { psc.write(it) }
        }
    }

    // mpv events

    private fun eventPropertyUi(property: String, dummy: Any?, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "track-list" -> player.loadTracks()
            "current-tracks/audio/selected", "current-tracks/video/image" -> updateAudioUI()
            "hwdec-current" -> updateDecoderButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "pause" -> updatePlaybackStatus(value)
            "mute" -> { // indirectly from updateAudioPresence()
                updateAudioUI()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        if (!activityIsForeground) return
        when (property) {
            "time-pos" -> updatePlaybackPos(psc.positionSec)
            "playlist-pos", "playlist-count" -> updatePlaylistButtons()
        }
    }

    private fun eventPropertyUi(property: String, value: Double) {
        if (!activityIsForeground) return
        when (property) {
            "duration/full" -> updatePlaybackDuration(psc.durationSec)
            "video-params/aspect", "video-params/rotate" -> {
                updateOrientation()
                updatePiPParams()
            }
        }
    }

    private fun eventPropertyUi(property: String, value: String, metaUpdated: Boolean) {
        if (!activityIsForeground) return
        when (property) {
            "speed" -> updateSpeedButton()
        }
        if (metaUpdated)
            updateMetadataDisplay()
    }

    private fun eventUi(eventId: Int) {
        if (!activityIsForeground) return
        // empty
    }

    override fun eventProperty(property: String) {
        val metaUpdated = psc.update(property)
        if (metaUpdated)
            updateMediaSession()
        if (property == "loop-file" || property == "loop-playlist") {
            mediaSession?.setRepeatMode(when (player.getRepeat()) {
                2 -> PlaybackStateCompat.REPEAT_MODE_ONE
                1 -> PlaybackStateCompat.REPEAT_MODE_ALL
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            })
        } else if (property == "current-tracks/audio/selected") {
            updateAudioPresence()
        }

        if (property == "pause" || property == "current-tracks/audio/selected")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, null, metaUpdated) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()
        if (property == "shuffle") {
            mediaSession?.setShuffleMode(if (value)
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            else
                PlaybackStateCompat.SHUFFLE_MODE_NONE)
        } else if (property == "mute") {
            updateAudioPresence()
        }

        if (metaUpdated || property == "mute")
            handleAudioFocus()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Double) {
        if (psc.update(property, value))
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "user-data/aurora/cmd") {   // 29: input.conf 단축키 → 네이티브 기능 브리지
            eventUiHandler.post { auroraCmd(value) }
            return
        }
        val metaUpdated = psc.update(property, value)
        if (metaUpdated)
            updateMediaSession()

        if (!activityIsForeground) return
        eventUiHandler.post { eventPropertyUi(property, value, metaUpdated) }
    }

    // 29: aurora_bridge.lua 가 user-data/aurora/cmd 에 "<명령>#<seq>" 를 set → 디스패치. seq 로 같은 명령 반복도 트리거.
    private fun auroraCmd(raw: String) {
        when (raw.substringBefore('#')) {
            "thumb" -> setCurrentAsThumb()
            "lock" -> lockUI()
            "pip" -> goIntoPiP()
            "menu" -> openTopMenu()
            "exit" -> finishWithResult(RESULT_OK, includeTimePos = true, includeTracks = true)
        }
    }

    override fun event(eventId: Int) {
        if (eventId == MpvEvent.MPV_EVENT_END_FILE) {
            // eof() 가 비우기 전 마지막 위치·트랙 보존(재생완료 위치기록 + 트랙 기억)
            if (psc.position >= 0) lastPos = psc.position
            if (psc.duration > 0) lastDur = psc.duration
            try {
                MPVLib.getPropertyString("aid")?.let { lastAid = it }
                MPVLib.getPropertyString("sid")?.let { lastSid = it }
            } catch (_: Throwable) {
            }
            psc.eof()
            updateMediaSession()
        }

        if (eventId == MpvEvent.MPV_EVENT_SHUTDOWN)
            finishWithResult(
                if (playbackHasStarted) RESULT_OK else RESULT_CANCELED,
                playbackHasStarted,
                playbackHasStarted   // eof 완료 경로에서도 트랙 기억(END_FILE 보존값)
            )

        if (eventId == MpvEvent.MPV_EVENT_START_FILE) {
            val cmds = onloadCommands.toTypedArray()
            onloadCommands.clear()
            for (c in cmds)
                MPVLib.command(c)
            if (this.statsLuaMode > 0 && !playbackHasStarted) {
                MPVLib.command(arrayOf("script-binding", "stats/display-page-${this.statsLuaMode}-toggle"))
            }
            // 32-B: 기능 on/off 를 user-data 로 (command set — lua 가 get_property 로 읽음). 파일 로드 시점=lua 준비 완료.
            val fp = getSharedPreferences("media_library", MODE_PRIVATE)
            for (n in arrayOf("jav_osd", "precise_speed", "bookmarks", "sub_style_toggle", "screenshot_to_clip", "progress_bar"))
                MPVLib.command(arrayOf("set", "user-data/aurora/feat/$n", if (fp.getBoolean("feat_$n", true)) "true" else "false"))

            // B-63(37): 다기능 lua(-- @feature id|...) 하위 기능 → user-data/aurora/feat/<stem>/<id> (lua 가 get_property_native 로 읽음)
            try {
                java.io.File(Utils.mpvConfigDir(), "scripts").listFiles { fl -> fl.name.endsWith(".lua") }?.forEach { lf ->
                    val stem = lf.name.removeSuffix(".lua")
                    lf.readText().lineSequence().take(40).forEach { line ->
                        val t = line.trim()
                        if (t.startsWith("-- @feature ")) {
                            val id = t.removePrefix("-- @feature ").substringBefore("|").trim()
                            if (id.isNotEmpty())
                                MPVLib.command(arrayOf("set", "user-data/aurora/feat/$stem/$id", if (fp.getBoolean("feat_${stem}_$id", true)) "true" else "false"))
                        }
                    }
                }
            } catch (_: Exception) {}

            // B-63(36): mpv.conf 의 # @feat 마커 옵션 중 feat_conf_*=false 면 `set <option> no` override (conf 파일 불변, SharedPrefs 영속)
            try {
                val conf = java.io.File(Utils.mpvConfigDir(), "mpv.conf")
                if (conf.exists()) {
                    val ls = conf.readText().lines()
                    for (i in ls.indices) {
                        val tt = ls[i].trim()
                        if (!tt.startsWith("# @feat ")) continue
                        val k = tt.removePrefix("# @feat ").substringBefore("|").trim()
                        val ti = i + 1
                        if (ti >= ls.size) continue
                        val op = ls[ti].trim().removePrefix("#").substringBefore("=").trim()
                        if (op.isNotEmpty() && !fp.getBoolean("feat_conf_$k", true))
                            MPVLib.command(arrayOf("set", op, "no"))
                    }
                }
            } catch (_: Exception) {}

            playbackHasStarted = true
        }

        if (!activityIsForeground) return
        eventUiHandler.post { eventUi(eventId) }
    }

    // Gesture handler

    private var initialSeek = 0f
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0
    /** 0 = initial, 1 = paused, 2 = was already paused */
    private var pausedForSeek = 0

    private fun fadeGestureText() {
        fadeHandler.removeCallbacks(fadeRunnable3)
        binding.gestureTextView.visibility = View.VISIBLE

        fadeHandler.postDelayed(fadeRunnable3, 500L)
    }

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        val gestureTextView = binding.gestureTextView
        when (p) {
            /* Drag gestures */
            PropertyChange.Init -> {
                mightWantToToggleControls = false

                initialSeek = (psc.position / 1000f)
                initialBright = Utils.getScreenBrightness(this) ?: 0.5f
                with (audioManager!!) {
                    initialVolume = getStreamVolume(STREAM_TYPE)
                    maxVolume = if (isVolumeFixed)
                        0
                    else
                        getStreamMaxVolume(STREAM_TYPE)
                }
                if (!isPlayingAudio)
                    maxVolume = 0 // disallow volume gesture if no audio
                pausedForSeek = 0

                fadeHandler.removeCallbacks(fadeRunnable3)
                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking when duration is unknown
                val duration = (psc.duration / 1000f)
                if (duration == 0f || initialSeek < 0)
                    return
                if (smoothSeekGesture && pausedForSeek == 0) {
                    pausedForSeek = if (psc.pause) 2 else 1
                    if (pausedForSeek == 1)
                        player.paused = true
                }

                val newPosExact = (initialSeek + diff).coerceIn(0f, duration)
                val newPos = newPosExact.roundToInt()
                val newDiff = (newPosExact - initialSeek).roundToInt()
                if (smoothSeekGesture) {
                    player.timePos = newPosExact.toDouble() // (exact seek)
                } else {
                    // seek faster than assigning to timePos but less precise
                    MPVLib.command(arrayOf("seek", "$newPosExact", "absolute+keyframes"))
                }
                // Note: don't call updatePlaybackPos() here because mpv will seek a timestamp
                // actually present in the file, and not the exact one we specified.

                val posText = Utils.prettyTime(newPos)
                val diffText = Utils.prettyTime(newDiff, true)
                gestureTextView.text = getString(R.string.ui_seek_distance, posText, diffText)
            }
            PropertyChange.Volume -> {
                if (maxVolume == 0)
                    return
                val newVolume = (initialVolume + (diff * maxVolume).toInt()).coerceIn(0, maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager!!.setStreamVolume(STREAM_TYPE, newVolume, 0)

                gestureTextView.text = getString(R.string.ui_volume, newVolumePercent)
            }
            PropertyChange.Bright -> {
                val lp = window.attributes
                val newBright = (initialBright + diff).coerceIn(0f, 1f)
                lp.screenBrightness = newBright
                window.attributes = lp

                gestureTextView.text = getString(R.string.ui_brightness, (newBright * 100).roundToInt())
            }
            PropertyChange.Finalize -> {
                if (pausedForSeek == 1)
                    player.paused = false
                gestureTextView.visibility = View.GONE
            }

            /* Tap gestures */
            PropertyChange.SeekFixed -> {
                val seekTime = diff * 10f
                val newPos = psc.positionSec + seekTime.toInt() // only for display
                MPVLib.command(arrayOf("seek", seekTime.toString(), "relative"))

                val diffText = Utils.prettyTime(seekTime.toInt(), true)
                gestureTextView.text = getString(R.string.ui_seek_distance, Utils.prettyTime(newPos), diffText)
                fadeGestureText()
            }
            PropertyChange.PlayPause -> player.cyclePause()
            PropertyChange.Custom -> {
                val keycode = 0x10002 + diff.toInt()
                MPVLib.command(arrayOf("keypress", "0x%x".format(keycode)))
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
        // how long should controls be displayed on screen (ms)
        private const val CONTROLS_DISPLAY_TIMEOUT = 1500L
        // how long controls fade to disappear (ms)
        private const val CONTROLS_FADE_DURATION = 500L
        // resolution (px) of the thumbnail displayed with playback notification
        private const val THUMB_SIZE = 384
        // smallest aspect ratio that is considered non-square
        private const val ASPECT_RATIO_MIN = 1.2f // covers 5:4 and up
        // fraction to which audio volume is ducked on loss of audio focus
        private const val AUDIO_FOCUS_DUCKING = 0.5f
        // request codes for invoking other activities
        private const val RCODE_EXTERNAL_AUDIO = 1000
        private const val RCODE_EXTERNAL_SUB = 1001
        private const val RCODE_LOAD_FILE = 1002
        private const val RCODE_MOVE_DEST = 1003   // B-68: DEL 액션 '폴더 이동' 대상 picker
        private const val RCODE_TRASH = 1004       // B-68: MediaStore 시스템 휴지통 결과 관찰
        // action of result intent
        private const val RESULT_INTENT = "is.xyz.mpv.MPVActivity.result"
        // stream type used with AudioManager
        private const val STREAM_TYPE = AudioManager.STREAM_MUSIC
        // precision used by seekbar (1/s)
        private const val SEEK_BAR_PRECISION = 2
    }
}
