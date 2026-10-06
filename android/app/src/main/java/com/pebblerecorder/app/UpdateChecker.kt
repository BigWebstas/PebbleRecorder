package com.pebblerecorder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val LATEST_RELEASE_URL = "https://api.github.com/repos/BigWebstas/PebbleRecorder/releases/latest"

/** A published GitHub release that is newer than the running app, with its installable APK. */
data class AvailableUpdate(val version: String, val apkUrl: String)

/**
 * Checks the GitHub Releases page for a newer version and downloads its APK. Only used by the
 * "github" flavor (see bool/has_update_checker) - F-Droid users get updates through F-Droid.
 */
object UpdateChecker {

    /** Returns the latest release if it is newer than [installedVersion], or null if up to date. */
    suspend fun findUpdate(installedVersion: String): Result<AvailableUpdate?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val release = JSONObject(httpGet(LATEST_RELEASE_URL))
                val version = release.getString("tag_name").removePrefix("v")
                if (!isNewer(version, installedVersion)) return@runCatching null

                // Each release carries two APKs: the plain one (github flavor, this app) and a
                // "-fdroid" one for the F-Droid reproducible build. Take the former.
                val assets = release.getJSONArray("assets")
                val apkUrl = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").let { n -> n.endsWith(".apk") && "fdroid" !in n } }
                    ?.getString("browser_download_url")
                    ?: throw IOException("Release v$version has no APK attached")
                AvailableUpdate(version, apkUrl)
            }
        }

    /** Downloads [update]'s APK into the cache dir (served via FileProvider) and returns it. */
    suspend fun download(context: Context, update: AvailableUpdate): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(context.cacheDir, "update.apk")
                val connection = open(update.apkUrl)
                try {
                    connection.inputStream.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    connection.disconnect()
                }
                target
            }
        }

    /** True when dotted-number [candidate] (e.g. "0.1.19") is greater than [installed]. */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun httpGet(url: String): String {
        val connection = open(url).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Opens [url] (GitHub's asset URLs redirect to a CDN, which HttpsURLConnection follows). */
    private fun open(url: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
}
