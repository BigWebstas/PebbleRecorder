package com.pebblerecorder.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        findViewById<Button>(R.id.choose_folder_button).setOnClickListener {
            pickFolder.launch(null)
        }
        findViewById<TextView>(R.id.version_text).text =
            getString(R.string.version_label, packageManager.getPackageInfo(packageName, 0).versionName)

        setUpGeminiSettings()

        requestPermissions.launch(requiredPermissions())

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

        enabledCheckbox.isChecked = GeminiPrefs.isEnabled(this)
        apiKeyInput.setText(GeminiPrefs.getApiKey(this))

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
        val folder = RecordingFolderPrefs.get(this)

        statusText.text = buildString {
            append(getString(R.string.main_status_text))
            append("\n\n")
            append(
                getString(
                    if (hasMicPermission) R.string.mic_permission_granted else R.string.mic_permission_missing,
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
