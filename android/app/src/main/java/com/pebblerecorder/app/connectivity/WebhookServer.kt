package com.pebblerecorder.app.connectivity

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

private const val TAG = "WebhookServer"
private const val MAX_LINE = 8192
private const val MAX_HEADERS = 64
private const val MAX_BODY_BYTES = 64L * 1024 * 1024
private const val SOCKET_TIMEOUT_MS = 10_000
private const val DEDUP_WINDOW = 32

/**
 * A minimal HTTP/1.1 server for triggering recordings from other apps on the phone (Pebble Index
 * 01's webhook, Tasker, ...). Bound to loopback only, so nothing off the device can reach it, and
 * every request needs `Authorization: Bearer <token>` - other apps on the phone can still connect
 * to localhost, so the token is what keeps them out.
 *
 * Deliberately tiny: it reads the request line and headers, runs [handle] for authenticated
 * requests, discards any body (Index sends the recorded audio along) and replies with a JSON
 * body. Index's test events (`X-Index-Test: true`) only get the current status back, and requests
 * carrying an `X-Index-Delivery` id already seen are acknowledged without running [handle], since
 * Index can redeliver the same recording and a toggle must not fire twice.
 */
class WebhookServer(
    private val token: () -> String,
    private val handle: (method: String, path: String) -> Response,
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
            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
            val response = respondTo(reader)
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

    private fun respondTo(reader: BufferedReader): Response {
        val parts = readLine(reader)?.split(' ') ?: return Response(400, error("bad request"))
        if (parts.size < 2) return Response(400, error("bad request"))
        val (method, target) = parts
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(reader) ?: return Response(400, error("bad request"))
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return Response(431, error("too many headers"))
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val provided = (headers["authorization"]?.removePrefix("Bearer ") ?: "").toByteArray()
        if (!MessageDigest.isEqual(provided, token().toByteArray())) {
            return Response(401, error("unauthorized"))
        }

        val deliveryId = headers["x-index-delivery"]
        val response = if (headers["x-index-test"] == "true") {
            // Index's "Send test event": proves the URL and token work, so just report the
            // current state - it must never start or stop anything.
            handle("GET", "/status")
        } else if (deliveryId != null && !firstDelivery(deliveryId)) {
            Response(200, """{"duplicate":true}""")
        } else {
            handle(method, target.substringBefore('?'))
        }
        discardBody(reader, headers["content-length"]?.toLongOrNull() ?: 0L)
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

    // Replying before the sender finishes uploading can reset its connection, so read it out.
    private fun discardBody(reader: BufferedReader, length: Long) {
        var remaining = length.coerceAtMost(MAX_BODY_BYTES)
        while (remaining > 0) {
            val skipped = reader.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    /** One CRLF/LF-terminated line, or null at end of stream; over-long lines are an IO error. */
    private fun readLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
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
        431 -> "Request Header Fields Too Large"
        else -> "Error"
    }
}
