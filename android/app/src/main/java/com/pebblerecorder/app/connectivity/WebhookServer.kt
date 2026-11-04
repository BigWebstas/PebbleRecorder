package com.pebblerecorder.app.connectivity

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

private const val TAG = "WebhookServer"
private const val MAX_LINE = 8192
private const val MAX_HEADERS = 64
private const val MAX_BODY_BYTES = 256L * 1024 * 1024
private const val SOCKET_TIMEOUT_MS = 10_000
private const val DEDUP_WINDOW = 32

/**
 * A minimal HTTP/1.1 server for triggering recordings from other apps on the phone (Pebble Index
 * 01's webhook, Tasker, ...). Bound to loopback only, so nothing off the device can reach it, and
 * every request needs `Authorization: Bearer <token>` - other apps on the phone can still connect
 * to localhost, so the token is what keeps them out.
 *
 * Deliberately tiny: it reads the request line and headers, runs [handle] for authenticated
 * requests and replies with a JSON body. A request that carries audio - a raw audio body (an audio content type or
 * `application/octet-stream`), or a `multipart/form-data` file part, which is what Index
 * sends along with a recording - is an upload: the audio is streamed to [upload] instead of
 * [handle] being run, since the recording was already made elsewhere. Other bodies are discarded. Index's test events (`X-Index-Test: true`) only get the current status back, and requests
 * carrying an `X-Index-Delivery` id already seen are acknowledged without running [handle], since
 * Index can redeliver the same recording and a toggle must not fire twice.
 */
class WebhookServer(
    private val token: () -> String,
    private val handle: (method: String, path: String) -> Response,
    private val upload: (mime: String, filename: String?, audio: InputStream) -> Response,
) {
    data class Response(val code: Int, val body: String)

    private var server: ServerSocket? = null
    private val seenDeliveries = ArrayDeque<String>()

    @Synchronized
    fun start(port: Int) {
        if (server != null) return
        val socket = try {
            ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            Log.w(TAG, "Failed to listen on port $port", e)
            return
        }
        server = socket
        thread(name = "webhook-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                thread(name = "webhook-client", isDaemon = true) { serve(client) }
            }
        }
        Log.d(TAG, "Listening on 127.0.0.1:$port")
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
    }

    private fun serve(client: Socket) = client.use {
        try {
            it.soTimeout = SOCKET_TIMEOUT_MS
            val response = respondTo(BufferedInputStream(it.getInputStream()))
            val payload = response.body.toByteArray()
            it.getOutputStream().apply {
                write(
                    ("HTTP/1.1 ${response.code} ${reasonPhrase(response.code)}\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                write(payload)
                flush()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Request failed: ${e.message}")
        }
    }

    private fun respondTo(input: InputStream): Response {
        val parts = readLine(input)?.split(' ') ?: return Response(400, error("bad request"))
        if (parts.size < 2) return Response(400, error("bad request"))
        val (method, target) = parts
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return Response(400, error("bad request"))
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return Response(431, error("too many headers"))
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val provided = (headers["authorization"]?.removePrefix("Bearer ") ?: "").toByteArray()
        if (!MessageDigest.isEqual(provided, token().toByteArray())) {
            return Response(401, error("unauthorized"))
        }
        if ((headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_BODY_BYTES) {
            return Response(413, error("too large"))
        }

        val body = bodyOf(input, headers)
        val deliveryId = headers["x-index-delivery"]
        val response = if (headers["x-index-test"] == "true") {
            // Index's "Send test event": proves the URL and token work, so just report the
            // current state - it must never start or stop anything.
            handle("GET", "/status")
        } else if (deliveryId != null && !firstDelivery(deliveryId)) {
            Response(200, """{"duplicate":true}""")
        } else {
            (body?.let { readUpload(it, headers) }) ?: handle(method, target.substringBefore('?'))
        }
        // Replying before the sender finishes uploading can reset its connection, so read it out.
        body?.drain()
        return response
    }

    /** Remembers recent delivery ids; false if [id] was already seen. */
    @Synchronized
    private fun firstDelivery(id: String): Boolean {
        if (id in seenDeliveries) return false
        seenDeliveries.addLast(id)
        if (seenDeliveries.size > DEDUP_WINDOW) seenDeliveries.removeFirst()
        return true
    }

    /** The request body as a stream (null if there is none), whether sent chunked or with a length. */
    private fun bodyOf(input: InputStream, headers: Map<String, String>): InputStream? {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return LimitedInputStream(ChunkedInputStream(input), MAX_BODY_BYTES, failWhenExceeded = true)
        }
        val length = headers["content-length"]?.toLongOrNull() ?: return null
        return if (length > 0) LimitedInputStream(input, length, failWhenExceeded = false) else null
    }

    /** Hands the audio in [body] to [upload]; null if the body doesn't carry any. */
    private fun readUpload(body: InputStream, headers: Map<String, String>): Response? {
        val contentType = headers["content-type"].orEmpty()
        val type = contentType.substringBefore(';').trim().lowercase()
        if (type.startsWith("multipart/")) {
            val boundary = BOUNDARY.find(contentType)?.let { it.groups[2]?.value ?: it.groups[1]?.value }
                ?: return null
            return readMultipart(body, boundary)
        }
        if (type.startsWith("audio/") || type == "application/octet-stream") {
            return upload(type, filenameOf(headers["content-disposition"]), body)
        }
        return null
    }

    /** Streams the first file (or audio) part of a multipart body to [upload]; other fields are skipped. */
    private fun readMultipart(body: InputStream, boundary: String): Response? {
        val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        while (true) { // skip the preamble up to the first boundary line
            val line = readLine(body) ?: return null
            if (line == "--$boundary") break
        }
        while (true) {
            val partHeaders = HashMap<String, String>()
            while (true) {
                val line = readLine(body) ?: return null
                if (line.isEmpty()) break
                if (partHeaders.size >= MAX_HEADERS) return null
                val colon = line.indexOf(':')
                if (colon > 0) partHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val filename = filenameOf(partHeaders["content-disposition"])
            val type = partHeaders["content-type"]?.substringBefore(';')?.trim()?.lowercase()
            val part = DelimitedInputStream(body, delimiter)
            if (filename != null || type?.startsWith("audio/") == true) {
                return upload(type ?: "application/octet-stream", filename, part)
            }
            part.drain()
            if (readLine(body)?.startsWith("--") != false) return null // closing boundary or truncated
        }
    }

    private fun filenameOf(disposition: String?): String? =
        disposition?.let { FILENAME.find(it) }?.groups?.get(1)?.value?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** One CRLF/LF-terminated line, or null at end of stream; over-long lines are an IO error. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_LINE) throw IOException("line too long")
            line.append(c.toChar())
        }
    }

    private fun error(message: String) = """{"error":"$message"}"""

    private fun reasonPhrase(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        else -> "Error"
    }
}

private val BOUNDARY = Regex("""boundary=("([^"]+)"|[^;\s]+)""", RegexOption.IGNORE_CASE)
private val FILENAME = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)

private fun InputStream.drain() {
    val sink = ByteArray(8192)
    while (read(sink) >= 0) { /* discard */ }
}

/** The first [limit] bytes of [src]; past that it reports end of stream, or throws if [failWhenExceeded]. */
private class LimitedInputStream(
    private val src: InputStream,
    private var limit: Long,
    private val failWhenExceeded: Boolean,
) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) {
            if (failWhenExceeded && src.read() >= 0) throw IOException("body too large")
            return -1
        }
        val n = src.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }
}

/** Decodes an HTTP/1.1 chunked body. */
private class ChunkedInputStream(private val src: InputStream) : InputStream() {
    private var remaining = 0L
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (finished) return -1
        if (remaining == 0L) {
            remaining = nextChunkSize()
            if (remaining == 0L) {
                finished = true
                // Trailers, up to the blank line that ends the message.
                while (readRawLine().isNotEmpty()) { /* ignore */ }
                return -1
            }
        }
        val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw IOException("truncated chunked body")
        remaining -= n
        if (remaining == 0L) readRawLine() // CRLF after the chunk data
        return n
    }

    private fun nextChunkSize(): Long =
        readRawLine().substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size")

    private fun readRawLine(): String {
        val line = StringBuilder()
        while (true) {
            val c = src.read()
            if (c == -1) throw IOException("truncated chunked body")
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_LINE) throw IOException("line too long")
            line.append(c.toChar())
        }
    }
}

/** [src] up to (and consuming) the first occurrence of [delimiter], which marks the end of a multipart part. */
private class DelimitedInputStream(private val src: InputStream, private val delimiter: ByteArray) : InputStream() {
    private val window = ByteArray(delimiter.size)
    private var filled = 0
    private var done = false

    override fun read(): Int {
        if (done) return -1
        while (filled < window.size) {
            val c = src.read()
            if (c < 0) throw IOException("truncated multipart body")
            window[filled++] = c.toByte()
        }
        if (window.last() == delimiter.last() && window.contentEquals(delimiter)) {
            done = true
            return -1
        }
        val out = window[0].toInt() and 0xFF
        System.arraycopy(window, 1, window, 0, window.size - 1)
        filled--
        return out
    }
}
