package com.pebblerecorder.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var permissionsText: TextView
    private lateinit var recordingStatusText: TextView

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

    private val requestPermissions = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { updateStatus() }

    private val requestLocationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionsText = findViewById(R.id.permissions_text)
        recordingStatusText = findViewById(R.id.recording_status_text)
        findViewById<Button>(R.id.choose_folder_button).setOnClickListener {
            pickFolder.launch(null)
        }
        findViewById<Button>(R.id.install_watchapp_button).apply {
            // The fdroid flavor ships without the bundled watch.pbw - see app/build.gradle.kts.
            visibility = if (resources.getBoolean(R.bool.has_bundled_watchapp)) View.VISIBLE else View.GONE
            setOnClickListener { installWatchapp() }
        }
        findViewById<TextView>(R.id.version_text).text =
            getString(R.string.version_label, packageManager.getPackageInfo(packageName, 0).versionName)

        setUpGeminiSettings()

        requestPermissions.launch(requiredPermissions())

        lifecycleScope.launch {
            RecordingStatus.state.collect { state ->
                val labelRes = when (state) {
                    RecordingState.IDLE -> R.string.status_idle
                    RecordingState.RECORDING -> R.string.status_recording
                    RecordingState.PAUSED -> R.string.status_paused
                }
                recordingStatusText.text = getString(R.string.status_label, getString(labelRes))
            }
        }

        // Arms PebbleListenerService as a persistent foreground service while we still have a
        // visible UI - required so it can later add the microphone type when a watch COMMAND
        // arrives, since Android forbids starting a *new* mic-type foreground service from the
        // background. See PebbleListenerService's class doc for the full explanation.
        ContextCompat.startForegroundService(this, Intent(this, PebbleListenerService::class.java))
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setUpGeminiSettings() {
        val enabledCheckbox = findViewById<CheckBox>(R.id.gemini_enabled_checkbox)
        val apiKeyInput = findViewById<EditText>(R.id.gemini_api_key_input)
        val locationCheckbox = findViewById<CheckBox>(R.id.gemini_location_checkbox)

        enabledCheckbox.isChecked = GeminiPrefs.isEnabled(this)
        apiKeyInput.setText(GeminiPrefs.getApiKey(this))
        locationCheckbox.isChecked = GeminiPrefs.isLocationEnabled(this)

        enabledCheckbox.setOnCheckedChangeListener { _, isChecked ->
            GeminiPrefs.setEnabled(this, isChecked)
        }
        apiKeyInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    GeminiPrefs.setApiKey(this@MainActivity, s?.toString().orEmpty())
                }
            },
        )
        locationCheckbox.setOnCheckedChangeListener { _, isChecked ->
            GeminiPrefs.setLocationEnabled(this, isChecked)
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

        permissionsText.text = buildString {
            append(
                getString(
                    if (hasMicPermission) R.string.mic_permission_granted else R.string.mic_permission_missing,
                ),
            )
            append("\n")
            append(
                getString(
                    if (hasLocationPermission) {
                        R.string.location_permission_granted
                    } else {
                        R.string.location_permission_missing
                    },
                ),
            )
            append("\n")
            append(
                if (folder != null) {
                    getString(R.string.folder_selected, folder.lastPathSegment ?: folder.toString())
                } else {
                    getString(R.string.folder_missing)
                },
            )
        }
    }
}
