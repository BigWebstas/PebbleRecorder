package com.pebblerecorder.app.connectivity

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebhookServerTest {
    private class Received(val mime: String, val filename: String?, val bytes: ByteArray)

    private var received: Received? = null
    private var handled: String? = null
    private lateinit var server: WebhookServer
    private var port = 0

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = WebhookServer(
            token = { "secret" },
            handle = { _, path -> handled = path; WebhookServer.Response(200, """{"ok":true}""") },
            upload = { mime, filename, audio ->
                received = Received(mime, filename, audio.readBytes())
                WebhookServer.Response(200, """{"status":"received"}""")
            },
        )
        server.start(port)
    }

    @After
    fun tearDown() = server.stop()

    private fun send(request: ByteArray): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply { write(request); flush() }
            socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    private fun head(extra: String, auth: String = "Bearer secret") =
        "POST /toggle HTTP/1.1\r\nHost: x\r\nAuthorization: $auth\r\n$extra\r\n"

    private val audio = ByteArray(5000) { (it * 31).toByte() } // includes CR/LF and boundary-ish bytes

    @Test
    fun rawAudioBodyIsUploadedInsteadOfToggling() {
        val body = audio
        val response = send(head("Content-Type: audio/mp4\r\nContent-Length: ${body.size}\r\n").toByteArray() + body)
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        assertEquals("audio/mp4", received?.mime)
        assertArrayEquals(audio, received?.bytes)
        assertNull(handled)
    }

    @Test
    fun chunkedAudioBodyIsDecoded() {
        val chunks = ByteArrayOutputStream().apply {
            audio.toList().chunked(1500).forEach {
                write("${it.size.toString(16)}\r\n".toByteArray())
                write(it.toByteArray())
                write("\r\n".toByteArray())
            }
            write("0\r\n\r\n".toByteArray())
        }.toByteArray()
        val response = send(head("Content-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n").toByteArray() + chunks)
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        assertArrayEquals(audio, received?.bytes)
    }

    @Test
    fun multipartFilePartIsUploadedAndOtherFieldsSkipped() {
        val boundary = "----abc123"
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhello\r\n".toByteArray())
            write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.m4a\"\r\n" +
                    "Content-Type: audio/mp4\r\n\r\n").toByteArray(),
            )
            write(audio)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val response = send(
            head("Content-Type: multipart/form-data; boundary=$boundary\r\nContent-Length: ${body.size}\r\n").toByteArray() + body,
        )
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        assertEquals("clip.m4a", received?.filename)
        assertEquals("audio/mp4", received?.mime)
        assertArrayEquals(audio, received?.bytes)
        assertNull(handled)
    }

    @Test
    fun requestWithoutAudioStillRunsTheHandler() {
        val response = send(head("Content-Length: 0\r\n").toByteArray())
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        assertEquals("/toggle", handled)
        assertNull(received)
    }

    @Test
    fun jsonBodyIsNotTreatedAsAudio() {
        val body = """{"a":1}""".toByteArray()
        send(head("Content-Type: application/json\r\nContent-Length: ${body.size}\r\n").toByteArray() + body)
        assertEquals("/toggle", handled)
        assertNull(received)
    }

    @Test
    fun wrongTokenIsRejectedBeforeAnythingRuns() {
        val response = send(head("Content-Type: audio/mp4\r\nContent-Length: 3\r\n", auth = "Bearer nope").toByteArray() + byteArrayOf(1, 2, 3))
        assertTrue(response, response.startsWith("HTTP/1.1 401"))
        assertNull(received)
        assertNull(handled)
    }
}
