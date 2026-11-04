package com.pebblerecorder.app.ui

import android.content.Context
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.pebblerecorder.app.R
import com.pebblerecorder.app.transcription.WhisperModel
import com.pebblerecorder.app.transcription.WhisperModels

/**
 * Every Whisper model on one screen: its size, whether it's on the phone, a download progress,
 * and a button to fetch or delete it - so the downloads can be managed without selecting each
 * model in turn.
 */
class WhisperModelList(private val context: Context, private val container: LinearLayout) {

    fun refresh() {
        container.removeAllViews()
        val download = WhisperModels.download.value
        WhisperModel.entries.forEach { model ->
            val downloaded = WhisperModels.isDownloaded(context, model)
            val running = download as? WhisperModels.Download.Running
            val failed = (download as? WhisperModels.Download.Failed)?.takeIf { it.model == model }

            val status = when {
                running?.model == model ->
                    context.getString(R.string.whisper_model_downloading, (running.bytes * 100 / model.sizeBytes).toInt())
                failed != null -> context.getString(R.string.whisper_model_failed, failed.message)
                downloaded -> context.getString(R.string.whisper_model_downloaded)
                else -> Formatter.formatShortFileSize(context, model.sizeBytes)
            }
            val label = TextView(context).apply {
                text = "${model.label}\n$status"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(if (downloaded) R.string.whisper_model_delete else R.string.whisper_model_download)
                // One download at a time.
                isEnabled = running == null
                setOnClickListener {
                    if (downloaded) WhisperModels.delete(context, model) else WhisperModels.startDownload(context, model)
                    refresh()
                }
            }
            container.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label)
                    addView(button)
                },
            )
        }
    }
}
