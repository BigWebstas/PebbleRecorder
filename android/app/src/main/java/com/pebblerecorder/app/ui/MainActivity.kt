package com.pebblerecorder.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.res.ColorStateList
import android.os.Environment
import android.os.StatFs
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.chip.Chip
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.pebblerecorder.app.R
import com.pebblerecorder.app.connectivity.AvailableUpdate
import com.pebblerecorder.app.connectivity.PebbleListenerService
import com.pebblerecorder.app.connectivity.WatchProtocol
import com.pebblerecorder.app.connectivity.UpdateChecker
import com.pebblerecorder.app.recording.RecordingFormat
import com.pebblerecorder.app.recording.RecordingQuality
import com.pebblerecorder.app.recording.RecordingLog
import com.pebblerecorder.app.recording.RecordingRetention
import com.pebblerecorder.app.recording.RecordingSettings
import com.pebblerecorder.app.recording.QuietMode
import com.pebblerecorder.app.recording.RecordingState
import com.pebblerecorder.app.recording.RecordingStatus
import com.pebblerecorder.app.recording.micSpectrum
import com.pebblerecorder.app.settings.GeminiPrefs
import com.pebblerecorder.app.settings.SettingsBackup
import com.pebblerecorder.app.settings.SummaryProvider
import com.pebblerecorder.app.settings.TranscriptionEngine
import com.pebblerecorder.app.settings.TranscriptionPrefs
import com.pebblerecorder.app.transcription.Summarizer
import com.pebblerecorder.app.transcription.TranscriptionStatus
import com.pebblerecorder.app.transcription.WhisperModel
import com.pebblerecorder.app.transcription.WhisperModels
import com.pebblerecorder.app.settings.QuietModePrefs
import com.pebblerecorder.app.settings.RecordingFolderPrefs
import com.pebblerecorder.app.settings.WEBHOOK_PORT
import com.pebblerecorder.app.settings.WatchPrefs
import com.pebblerecorder.app.settings.WebhookPrefs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        /** Intent extra: the transcription error text a "transcription failed" notification opens with. */
        const val EXTRA_TRANSCRIPTION_ERROR = "com.pebblerecorder.app.extra.TRANSCRIPTION_ERROR"
    }

    private lateinit var recordingStatusText: TextView
    private lateinit var recordingProgressText: TextView
    private lateinit var recordingsList: RecordingsList
    private lateinit var spectrogram: SpectrogramView
    private lateinit var statusDot: View
    private lateinit var recordButton: MaterialButton
    private lateinit var pauseButton: MaterialButton
    private var dotPulse: ObjectAnimator? = null
    private var spectrogramJob: Job? = null
    private var downloadJob: Job? = null
    private var refreshWhisper: () -> Unit = {}

    private val pickFolder = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            RecordingFolderPrefs.set(this, uri)
            updateStatus()
        }
    }

    private val exportSettings = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(SettingsBackup.export(this).toByteArray()) }
            }.onSuccess { Toast.makeText(this, R.string.settings_exported, Toast.LENGTH_SHORT).show() }
        }
    }

    private val importSettings = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val json = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            val restored = json?.let { SettingsBackup.import(this, it) }
            if (restored == null) {
                Toast.makeText(this, R.string.settings_import_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.settings_imported, restored), Toast.LENGTH_SHORT).show()
                recreate() // so every control shows the restored values
            }
        }
    }

    private val requestPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { updateStatus() }

    private val requestLocationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is Theme.Material3.DayNight and every view uses theme attributes (no
        // hardcoded colours), so this only makes the "follow the phone's light/dark setting"
        // intent explicit for all API levels rather than leaning on the AppCompat default.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_main)
        // Edge-to-edge: dark status/navigation bar icons on the light theme, light ones on the dark.
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        recordingStatusText = findViewById(R.id.recording_status_text)
        recordingProgressText = findViewById(R.id.recording_progress_text)
        spectrogram = findViewById(R.id.spectrogram)
        statusDot = findViewById(R.id.status_dot)
        recordButton = findViewById(R.id.record_button)
        pauseButton = findViewById(R.id.pause_button)
        recordButton.setOnClickListener { onRecordClicked() }
        pauseButton.setOnClickListener { onPauseClicked() }
        findViewById<View>(R.id.bookmark_button).setOnClickListener {
            it.performHapticFeedback(tapFeedback())
            PebbleListenerService.sendCommand(this, WatchProtocol.COMMAND_BOOKMARK)
        }
        recordingsList = RecordingsList(
            this,
            findViewById(R.id.recordings_container),
            findViewById(R.id.recordings_empty),
            findViewById(R.id.recordings_search_layout),
            findViewById(R.id.recordings_search),
            findViewById(R.id.recordings_storage_bar),
            findViewById(R.id.recordings_storage_text),
        )
        bindRecordingLog(recordingsList)
        setUpSections()
        findViewById<Button>(R.id.choose_folder_button).setOnClickListener {
            pickFolder.launch(null)
        }
        findViewById<Button>(R.id.install_watchapp_button).apply {
            // The fdroid flavor ships without the bundled watch.pbw - see app/build.gradle.kts.
            visibility = if (resources.getBoolean(R.bool.has_bundled_watchapp)) View.VISIBLE else View.GONE
            setOnClickListener { installWatchapp() }
        }

        val advancedToggle = findViewById<MaterialButton>(R.id.advanced_toggle_button)
        val advancedBox = findViewById<View>(R.id.advanced_box)
        advancedToggle.setOnClickListener {
            TransitionManager.beginDelayedTransition(findViewById(R.id.content_root), quickTransition())
            val show = advancedBox.visibility != View.VISIBLE
            advancedBox.visibility = if (show) View.VISIBLE else View.GONE
            advancedToggle.setText(if (show) R.string.hide_advanced else R.string.show_advanced)
            advancedToggle.setIconResource(if (show) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        }

        findViewById<TextView>(R.id.version_text).text =
            getString(R.string.version_label, packageManager.getPackageInfo(packageName, 0).versionName)

        setUpGeminiSettings()
        setUpRecordingSettings()
        setUpWebhookSettings()

        findViewById<MaterialSwitch>(R.id.quiet_mode_switch).apply {
            isChecked = QuietModePrefs.isEnabled(this@MainActivity) && QuietMode.hasAccess(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !QuietMode.hasAccess(this@MainActivity)) {
                    // Do Not Disturb access is a special permission granted on its own settings page.
                    isChecked = false
                    Toast.makeText(this@MainActivity, R.string.quiet_mode_needs_access, Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } else {
                    QuietModePrefs.setEnabled(this@MainActivity, checked)
                }
            }
        }

        findViewById<MaterialSwitch>(R.id.watch_touch_switch).apply {
            isChecked = WatchPrefs.isTouchEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked -> WatchPrefs.setTouchEnabled(this@MainActivity, isChecked) }
        }

        // savedInstanceState == null: only on a fresh launch, so rotating doesn't re-pop the dialog.
        findViewById<Button>(R.id.check_updates_button).apply {
            // Only the github flavor self-updates - see app/build.gradle.kts.
            if (resources.getBoolean(R.bool.has_update_checker)) {
                visibility = View.VISIBLE
                this@MainActivity.findViewById<View>(R.id.updates_card).visibility = View.VISIBLE
                setOnClickListener { checkForUpdate(userInitiated = true) }
                if (savedInstanceState == null) checkForUpdate(userInitiated = false)
            }
        }

        requestPermissions.launch(requiredPermissions())

        lifecycleScope.launch {
            RecordingStatus.state.collect { state ->
                val labelRes = when (state) {
                    RecordingState.IDLE -> R.string.status_idle
                    RecordingState.RECORDING -> R.string.status_recording
                    RecordingState.PAUSED -> R.string.status_paused
                }
                recordingStatusText.text = getString(R.string.status_label, getString(labelRes))
                showControls(state)
            }
        }

        // "01:23 · 1.2 MB" under the status while a recording exists (running or paused).
        lifecycleScope.launch {
            RecordingStatus.state.combine(RecordingStatus.progress) { state, progress -> state to progress }
                .collect { (state, progress) ->
                    recordingProgressText.visibility = if (state == RecordingState.IDLE) View.GONE else View.VISIBLE
                    recordingProgressText.text = getString(
                        R.string.recording_progress,
                        formatClock(progress.elapsedSeconds),
                        Formatter.formatShortFileSize(this@MainActivity, progress.sizeBytes),
                    )
                }
        }

        // "Transcribing… 42% · 00:12" with a bar while the on-device engine works on a recording.
        val transcriptionText = findViewById<TextView>(R.id.transcription_text)
        val transcriptionBar = findViewById<LinearProgressIndicator>(R.id.transcription_progress)
        lifecycleScope.launch {
            TranscriptionStatus.active.collectLatest { active ->
                val visibility = if (active == null) View.GONE else View.VISIBLE
                transcriptionText.visibility = visibility
                transcriptionBar.visibility = visibility
                if (active == null) return@collectLatest
                if (active.percent == null) {
                    transcriptionBar.isIndeterminate = true
                } else {
                    transcriptionBar.isIndeterminate = false
                    transcriptionBar.setProgressCompat(active.percent, true)
                }
                // The clock ticks once a second, independent of whisper's coarser progress updates.
                while (true) {
                    val elapsed = formatClock(((System.currentTimeMillis() - active.startedAtMs) / 1000).toInt())
                    val label = active.label(this@MainActivity)
                    transcriptionText.text = if (active.percent == null) {
                        getString(R.string.transcribing_progress, "$label · $elapsed")
                    } else {
                        getString(R.string.transcribing_progress_percent, active.percent, "$label · $elapsed")
                    }
                    delay(1000)
                }
            }
        }

        // Arms PebbleListenerService as a persistent foreground service while we still have a
        // visible UI - required so it can later add the microphone type when a watch COMMAND
        // arrives, since Android forbids starting a *new* mic-type foreground service from the
        // background. See PebbleListenerService's class doc for the full explanation.
        ContextCompat.startForegroundService(this, Intent(this, PebbleListenerService::class.java))

        showTranscriptionErrorIfPresent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTranscriptionErrorIfPresent(intent)
    }

    // The spectrogram opens its own mic capture, which has no business running (or holding the
    // mic) once the user has left the app - so it lives exactly between onStart and onStop.
    override fun onStart() {
        super.onStart()
        refreshMicDropdown()
        showControls(RecordingStatus.state.value)
        spectrogramJob = lifecycleScope.launch {
            RecordingStatus.state.collectLatest { showSpectrogram(it) }
        }
        // Model downloads run on, so pick up where they are whenever the screen is back.
        downloadJob = lifecycleScope.launch { WhisperModels.download.collect { refreshWhisper() } }
    }

    override fun onStop() {
        spectrogramJob?.cancel()
        downloadJob?.cancel()
        recordingsList.release()
        dotPulse?.cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    /** CONFIRM (API 30+) feels like a deliberate tick; older phones get the plain keyboard tap. */
    private fun tapFeedback() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP

    private fun onRecordClicked() {
        recordButton.performHapticFeedback(tapFeedback())
        if (RecordingStatus.state.value != RecordingState.IDLE) {
            PebbleListenerService.sendCommand(this, WatchProtocol.COMMAND_STOP)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            PebbleListenerService.sendCommand(this, WatchProtocol.COMMAND_START)
        } else {
            requestPermissions.launch(requiredPermissions())
        }
    }

    private fun onPauseClicked() {
        pauseButton.performHapticFeedback(tapFeedback())
        val command = if (RecordingStatus.state.value == RecordingState.PAUSED) {
            WatchProtocol.COMMAND_RESUME
        } else {
            WatchProtocol.COMMAND_PAUSE
        }
        PebbleListenerService.sendCommand(this, command)
    }

    /** Record/stop and pause/resume buttons, and the status dot: pulsing red while recording, amber when paused. */
    /** Advanced sections open and close; the choice is remembered. Recording and transcription start open. */
    private fun setUpSections() {
        val prefs = getSharedPreferences("ui_state", MODE_PRIVATE)
        listOf(
            Triple(R.id.recording_section_label, R.id.recording_section_body, true),
            Triple(R.id.connectivity_section_label, R.id.connectivity_section_body, false),
            Triple(R.id.transcription_section_label, R.id.transcription_section_body, true),
            Triple(R.id.watch_section_label, R.id.watch_section_body, false),
            Triple(R.id.backup_section_label, R.id.backup_section_body, false),
            Triple(R.id.updates_section_label, R.id.updates_section_body, false),
        ).forEach { (header, body, open) ->
            makeCollapsible(
                findViewById(header), findViewById(body), findViewById(R.id.content_root), prefs,
                resources.getResourceEntryName(header), open,
            )
        }
    }

    private fun formatClock(totalSeconds: Int): String =
        if (totalSeconds >= 3600) {
            String.format(Locale.US, "%d:%02d:%02d", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
        } else {
            String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        }

    private fun showControls(state: RecordingState) {
        val idle = state == RecordingState.IDLE
        recordButton.setText(if (idle) R.string.record_button else R.string.stop_button)
        recordButton.setIconResource(if (idle) R.drawable.ic_record else R.drawable.ic_stop)
        pauseButton.visibility = if (idle) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bookmark_button).visibility =
            if (state == RecordingState.RECORDING) View.VISIBLE else View.GONE
        val paused = state == RecordingState.PAUSED
        pauseButton.setText(if (paused) R.string.resume_button else R.string.pause_button)
        pauseButton.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)

        dotPulse?.cancel()
        statusDot.alpha = 1f
        statusDot.backgroundTintList = ColorStateList.valueOf(
            when (state) {
                RecordingState.RECORDING -> Color.parseColor("#E53935")
                RecordingState.PAUSED -> Color.parseColor("#FFB300")
                RecordingState.IDLE -> MaterialColors.getColor(statusDot, com.google.android.material.R.attr.colorOutline)
            },
        )
        // Skipped when the user turned animations off system-wide.
        if (state == RecordingState.RECORDING && ValueAnimator.areAnimatorsEnabled()) {
            dotPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    /**
     * Shows the live spectrogram while recording (frozen while paused, hidden when idle). Cancelled
     * and restarted by collectLatest on every state change, which also releases the mic.
     */
    private suspend fun showSpectrogram(state: RecordingState) {
        // A second mic capture next to the recorder's own is only allowed from Android 10.
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        spectrogram.visibility = if (supported && state != RecordingState.IDLE) View.VISIBLE else View.GONE
        if (state == RecordingState.IDLE) spectrogram.clear()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!supported || !hasMic || state != RecordingState.RECORDING) return
        micSpectrum().collect { spectrogram.addColumn(it) }
    }

    /** Shows the error carried by a tapped "transcription failed" notification (see PebbleListenerService). */
    private fun showTranscriptionErrorIfPresent(intent: Intent?) {
        val message = intent?.getStringExtra(EXTRA_TRANSCRIPTION_ERROR) ?: return
        // Clear it so a config change (rotation) doesn't re-pop the dialog.
        intent.removeExtra(EXTRA_TRANSCRIPTION_ERROR)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transcription_failed_notification_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Format and quality are fixed lists; the microphone list is rebuilt in [onStart] as devices come and go. */
    private fun setUpRecordingSettings() {
        val formats = RecordingFormat.entries.filter { it.isSupported }
        val qualities = RecordingQuality.entries
        setUpDropdown(
            R.id.format_dropdown, formats.map { getString(it.labelRes) },
            formats.indexOf(RecordingSettings.format(this)),
        ) { RecordingSettings.setFormat(this, formats[it]) }
        setUpDropdown(
            R.id.quality_dropdown, qualities.map { getString(it.labelRes) },
            qualities.indexOf(RecordingSettings.quality(this)),
        ) { RecordingSettings.setQuality(this, qualities[it]) }

        val maxLimits = listOf(0, 30, 60, 120, 240)
        setUpDropdown(
            R.id.stop_after_dropdown, maxLimits.map(::limitLabel), maxLimits.indexOf(RecordingSettings.maxMinutes(this)),
        ) { RecordingSettings.setMaxMinutes(this, maxLimits[it]) }
        val silenceLimits = listOf(0, 1, 2, 5, 10)
        setUpDropdown(
            R.id.silence_stop_dropdown, silenceLimits.map(::limitLabel),
            silenceLimits.indexOf(RecordingSettings.silenceMinutes(this)),
        ) { RecordingSettings.setSilenceMinutes(this, silenceLimits[it]) }

        setUpAutoDelete()
        findViewById<Button>(R.id.export_settings_button).setOnClickListener {
            exportSettings.launch("pebblerecorder-settings.json")
        }
        findViewById<Button>(R.id.import_settings_button).setOnClickListener {
            importSettings.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    /**
     * "Delete recordings automatically after": Never, or a number of days. Turning it on asks first -
     * it deletes files from the recording folder with no way back - and says how many it would
     * delete right away.
     */
    private fun setUpAutoDelete() {
        val options = listOf(0, 7, 30, 90)
        val labels = options.map { if (it == 0) getString(R.string.never) else getString(R.string.days_option, it) }
        val dropdown = findViewById<MaterialAutoCompleteTextView>(R.id.auto_delete_dropdown)
        fun showSaved() = dropdown.setText(labels[options.indexOf(RecordingSettings.autoDeleteDays(this)).coerceAtLeast(0)], false)
        dropdown.setSimpleItems(labels.toTypedArray())
        showSaved()
        dropdown.setOnItemClickListener { _, _, position, _ ->
            val days = options[position]
            if (days == 0) {
                RecordingSettings.setAutoDeleteDays(this, 0)
                return@setOnItemClickListener
            }
            val now = RecordingRetention.expired(RecordingLog.entries.value, days, System.currentTimeMillis()).size
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_delete_title)
                .setMessage(
                    getString(R.string.auto_delete_confirm, days, days + 1) +
                        if (now > 0) "\n\n" + resources.getQuantityString(R.plurals.auto_delete_now, now, now) else "",
                )
                .setPositiveButton(R.string.auto_delete_turn_on) { _, _ ->
                    RecordingSettings.setAutoDeleteDays(this, days)
                    lifecycleScope.launch(Dispatchers.IO) { RecordingRetention.sweep(this@MainActivity) }
                }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener { showSaved() } // back to what is actually saved if they cancelled
                .show()
        }
    }

    private fun limitLabel(minutes: Int): String = when {
        minutes == 0 -> getString(R.string.never)
        minutes % 60 == 0 -> getString(R.string.hours_short, minutes / 60)
        else -> getString(R.string.minutes_short, minutes)
    }

    private fun setUpWebhookSettings() {
        val enabledSwitch = findViewById<MaterialSwitch>(R.id.webhook_switch)
        val details = findViewById<View>(R.id.webhook_details)
        findViewById<TextView>(R.id.webhook_url_text).text =
            getString(R.string.webhook_url, "http://127.0.0.1:$WEBHOOK_PORT/toggle")

        enabledSwitch.isChecked = WebhookPrefs.isEnabled(this)
        details.visibility = if (enabledSwitch.isChecked) View.VISIBLE else View.GONE
        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            WebhookPrefs.setEnabled(this, isChecked)
            details.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.webhook_copy_token_button).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("webhook token", WebhookPrefs.token(this)))
            Toast.makeText(this, R.string.webhook_token_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.webhook_regenerate_button).setOnClickListener {
            WebhookPrefs.regenerateToken(this)
            Toast.makeText(this, R.string.webhook_token_regenerated, Toast.LENGTH_SHORT).show()
        }
    }

    private fun EditText.onTextChanged(onChanged: (String) -> Unit) {
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onChanged(s?.toString().orEmpty())
            },
        )
    }

    private fun setUpWhisperSettings() {
        val models = WhisperModel.entries
        val list = WhisperModelList(this, findViewById(R.id.whisper_models))
        val labels = models.map { getString(R.string.whisper_model_item, it.label, it.sizeBytes / 1_000_000) }
        setUpDropdown(R.id.whisper_model_dropdown, labels, models.indexOf(TranscriptionPrefs.whisperModel(this))) {
            TranscriptionPrefs.setWhisperModel(this, models[it])
        }
        findViewById<MaterialSwitch>(R.id.whisper_tiny_long_switch).apply {
            isChecked = TranscriptionPrefs.tinyForLongRecordings(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> TranscriptionPrefs.setTinyForLongRecordings(this@MainActivity, checked) }
        }
        list.refresh()
        refreshWhisper = list::refresh
    }

    private fun refreshMicDropdown() {
        val mics = RecordingSettings.availableMics(this)
        val saved = RecordingSettings.micKey(this)
        // A saved mic that isn't connected right now shows as Automatic, which is what recording will use.
        val selected = mics.indexOfFirst { it.key == saved } + 1
        setUpDropdown(
            R.id.mic_dropdown, listOf(getString(R.string.mic_automatic)) + mics.map { it.label }, selected,
        ) { RecordingSettings.setMicKey(this, if (it == 0) null else mics[it - 1].key) }
    }

    private fun setUpDropdown(id: Int, items: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        val dropdown = findViewById<MaterialAutoCompleteTextView>(id)
        dropdown.setSimpleItems(items.toTypedArray())
        dropdown.setText(items[selected.coerceAtLeast(0)], false)
        dropdown.setOnItemClickListener { _, _, position, _ -> onSelected(position) }
    }

    private fun setUpGeminiSettings() {
        val enabledSwitch = findViewById<MaterialSwitch>(R.id.transcribe_switch)
        val geminiKeyInput = findViewById<EditText>(R.id.gemini_api_key_input)
        val openAiKeyInput = findViewById<EditText>(R.id.openai_api_key_input)
        val whisperBox = findViewById<View>(R.id.whisper_box)
        val locationSwitch = findViewById<MaterialSwitch>(R.id.gemini_location_switch)

        enabledSwitch.isChecked = GeminiPrefs.isEnabled(this)
        geminiKeyInput.setText(GeminiPrefs.getApiKey(this))
        openAiKeyInput.setText(TranscriptionPrefs.openAiApiKey(this))
        locationSwitch.isChecked = GeminiPrefs.isLocationEnabled(this)

        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            GeminiPrefs.setEnabled(this, isChecked)
        }

        val claudeKeyInput = findViewById<EditText>(R.id.claude_api_key_input)
        val geminiKeyLayout = findViewById<View>(R.id.gemini_api_key_input_layout)
        val openAiKeyLayout = findViewById<View>(R.id.openai_api_key_input_layout)
        val claudeKeyLayout = findViewById<View>(R.id.claude_api_key_input_layout)
        val summarySwitch = findViewById<MaterialSwitch>(R.id.summary_switch)
        val summaryBox = findViewById<View>(R.id.summary_box)
        val summaryTestButton = findViewById<Button>(R.id.summary_test_button)
        val summaryTestResult = findViewById<TextView>(R.id.summary_test_result)
        claudeKeyInput.setText(TranscriptionPrefs.claudeApiKey(this))
        summarySwitch.isChecked = TranscriptionPrefs.summaryEnabled(this)

        // Each engine - and the summary provider, if summaries are on - only shows the settings it needs.
        fun showEngineSettings() {
            val engine = TranscriptionPrefs.engine(this)
            val summary = TranscriptionPrefs.summaryEnabled(this)
            val provider = TranscriptionPrefs.summaryProvider(this)
            geminiKeyLayout.visibility =
                if (engine == TranscriptionEngine.GEMINI || (summary && provider == SummaryProvider.GEMINI)) View.VISIBLE else View.GONE
            openAiKeyLayout.visibility =
                if (engine == TranscriptionEngine.OPENAI || (summary && provider == SummaryProvider.OPENAI)) View.VISIBLE else View.GONE
            claudeKeyLayout.visibility = if (summary && provider == SummaryProvider.CLAUDE) View.VISIBLE else View.GONE
            whisperBox.visibility = if (engine == TranscriptionEngine.WHISPER) View.VISIBLE else View.GONE
            summaryBox.visibility = if (summary) View.VISIBLE else View.GONE

            // "Test connection" only makes sense once the chosen provider has a key; any change
            // here also makes a previous result stale.
            val hasKey = when (provider) {
                SummaryProvider.GEMINI -> GeminiPrefs.getApiKey(this)
                SummaryProvider.OPENAI -> TranscriptionPrefs.openAiApiKey(this)
                SummaryProvider.CLAUDE -> TranscriptionPrefs.claudeApiKey(this)
            } != null
            summaryTestButton.visibility = if (summary && hasKey) View.VISIBLE else View.GONE
            summaryTestResult.visibility = View.GONE
        }
        geminiKeyInput.onTextChanged { GeminiPrefs.setApiKey(this, it); showEngineSettings() }
        openAiKeyInput.onTextChanged { TranscriptionPrefs.setOpenAiApiKey(this, it); showEngineSettings() }
        claudeKeyInput.onTextChanged { TranscriptionPrefs.setClaudeApiKey(this, it); showEngineSettings() }
        summaryTestButton.setOnClickListener {
            summaryTestButton.isEnabled = false
            summaryTestButton.setText(R.string.summary_testing)
            summaryTestResult.visibility = View.GONE
            lifecycleScope.launch {
                val provider = TranscriptionPrefs.summaryProvider(this@MainActivity)
                val result = Summarizer.test(this@MainActivity)
                summaryTestButton.isEnabled = true
                summaryTestButton.setText(R.string.summary_test_button)
                summaryTestResult.text = result.fold(
                    { getString(R.string.summary_test_ok, provider.label) },
                    { getString(R.string.summary_test_failed, (it.message ?: it.javaClass.simpleName).take(200)) },
                )
                summaryTestResult.setTextColor(
                    MaterialColors.getColor(
                        summaryTestResult,
                        if (result.isSuccess) androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorError,
                    ),
                )
                summaryTestResult.visibility = View.VISIBLE
            }
        }
        val engines = TranscriptionEngine.entries
        val engineLabels = listOf(R.string.engine_gemini, R.string.engine_openai, R.string.engine_whisper)
            .map { getString(it) }
        setUpDropdown(R.id.engine_dropdown, engineLabels, engines.indexOf(TranscriptionPrefs.engine(this))) {
            TranscriptionPrefs.setEngine(this, engines[it])
            showEngineSettings()
        }
        val providers = SummaryProvider.entries
        setUpDropdown(
            R.id.summary_provider_dropdown, providers.map { it.label },
            providers.indexOf(TranscriptionPrefs.summaryProvider(this)),
        ) {
            TranscriptionPrefs.setSummaryProvider(this, providers[it])
            showEngineSettings()
        }
        summarySwitch.setOnCheckedChangeListener { _, checked ->
            TranscriptionPrefs.setSummaryEnabled(this, checked)
            showEngineSettings()
        }
        showEngineSettings()
        setUpWhisperSettings()
        locationSwitch.setOnCheckedChangeListener { _, isChecked ->
            GeminiPrefs.setLocationEnabled(this, isChecked)
            updateStatus()
            if (isChecked) {
                // Transcripts still work fine without it if the user declines - location is just
                // omitted from the header.
                requestLocationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
    }

    /**
     * Copies the bundled watch.pbw (see app/build.gradle.kts's syncWatchAppAsset task) out to the
     * cache dir and hands it off via ACTION_VIEW to whatever's registered to open *.pbw files -
     * the Pebble/Core companion app - which offers to install it onto the paired watch. This app
     * has no direct line to the watch itself (see PebbleListenerService's class doc), so this is
     * just a shortcut for the same "open the .pbw with the companion app" sideload flow a user
     * would do manually with a downloaded release artifact.
     */
    private fun installWatchapp() {
        val cachedPbw = File(cacheDir, "watch.pbw")
        try {
            assets.open("watch.pbw").use { input ->
                FileOutputStream(cachedPbw).use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            Toast.makeText(this, R.string.install_watchapp_missing, Toast.LENGTH_LONG).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", cachedPbw)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.install_watchapp_no_handler, Toast.LENGTH_LONG).show()
        }
    }

    /** Asks GitHub Releases for a newer version and, if there is one, offers to install it. */
    private fun checkForUpdate(userInitiated: Boolean) {
        lifecycleScope.launch {
            val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            val result = UpdateChecker.findUpdate(installed)
            // The launch-time check stays silent unless there's an update (a failure just retries
            // next launch); a button press always answers.
            if (userInitiated) {
                if (result.isFailure) {
                    Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                } else if (result.getOrNull() == null) {
                    Toast.makeText(this@MainActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }
            val update = result.getOrNull() ?: return@launch
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.update_available_title)
                .setMessage(getString(R.string.update_available_message, update.version, installed))
                .setPositiveButton(R.string.update_install_button) { _, _ -> downloadAndInstall(update) }
                .setNegativeButton(R.string.update_later_button, null)
                .show()
        }
    }

    private fun downloadAndInstall(update: AvailableUpdate) {
        // Android gates installs per source app; send the user to grant it before downloading.
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.update_allow_installs, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            UpdateChecker.download(this@MainActivity, update)
                .onSuccess { apk ->
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", apk)
                    startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }
                .onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_download_failed, it.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun updateStatus() {
        val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val folder = RecordingFolderPrefs.get(this)
        val batteryUnrestricted = isBatteryUnrestricted()

        val folderName = folder?.let { DocumentFile.fromTreeUri(this, it)?.name ?: it.lastPathSegment }
            ?: getString(R.string.chip_folder_default)

        bindChip(findViewById(R.id.chip_mic), hasMicPermission) { requestPermissions.launch(requiredPermissions()) }
        findViewById<Chip>(R.id.chip_location).apply {
            // Only matters when transcripts are set to include where you were.
            visibility = if (GeminiPrefs.isLocationEnabled(this@MainActivity)) View.VISIBLE else View.GONE
            bindChip(this, hasLocationPermission) {
                requestLocationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
        bindChip(findViewById(R.id.chip_battery), batteryUnrestricted) { openBatterySettings() }
        findViewById<Chip>(R.id.chip_folder).apply {
            text = getString(R.string.chip_folder, folderName)
            bindChip(this, true) { pickFolder.launch(null) }
        }
        val free = Formatter.formatShortFileSize(this, StatFs(Environment.getExternalStorageDirectory().path).availableBytes)
        findViewById<TextView>(R.id.storage_text).text = getString(R.string.storage_line, folderName, free)
    }

    /** An assist chip that reads green-ish when [ok] and error-red when something needs fixing; tapping fixes it. */
    private fun bindChip(chip: Chip, ok: Boolean, onClick: () -> Unit) {
        val background = if (ok) com.google.android.material.R.attr.colorSecondaryContainer
        else com.google.android.material.R.attr.colorErrorContainer
        val content = if (ok) com.google.android.material.R.attr.colorOnSecondaryContainer
        else com.google.android.material.R.attr.colorOnErrorContainer
        chip.chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(chip, background))
        val contentColor = MaterialColors.getColor(chip, content)
        chip.setTextColor(contentColor)
        chip.chipIconTint = ColorStateList.valueOf(contentColor)
        chip.setChipIconResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning)
        chip.isChipIconVisible = true
        chip.setOnClickListener { onClick() }
    }

    /**
     * True when Android won't throttle the app in the background: exempt from battery
     * optimization (Doze) and not under a user "background restriction". The watch-trigger
     * service needs both to reliably receive a command while the phone is idle.
     */
    private fun isBatteryUnrestricted(): Boolean {
        val ignoringOptimizations = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
        val backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        return ignoringOptimizations && !backgroundRestricted
    }

    private fun openBatterySettings() {
        val packageUri = Uri.parse("package:$packageName")
        val ignoringOptimizations = getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true
        // If optimization is still on, ask for the exemption directly (one dialog). Once that's
        // granted, any remaining problem is the "background restricted" toggle, which has no
        // direct intent - fall back to the app's settings page.
        @Suppress("BatteryLife")
        val primary = if (!ignoringOptimizations) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
        try {
            startActivity(primary)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.battery_restricted, Toast.LENGTH_LONG).show()
            }
        }
    }
}
