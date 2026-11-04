package com.pebblerecorder.app.transcription

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** POSTs a JSON [body] with [headers]; returns the status code and the response body (the error body for non-2xx). */
internal fun postJson(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
    val connection = URL(url).openConnection() as HttpsURLConnection
    try {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
    } finally {
        connection.disconnect()
    }
}
