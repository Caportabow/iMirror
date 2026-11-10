package dev.imirror.receiver.airplay

import dev.imirror.receiver.util.Logger
import java.io.InputStream
import java.util.TreeMap

/**
 * RtspRequestReader parses RTSP/HTTP-style requests from the AirPlay control socket.
 *
 * The reader consumes bytes directly from the socket so the caller can safely switch
 * to binary RTP interleaved frames after RTSP RECORD without losing buffered data.
 */
internal class RtspRequestReader(
    private val maxMessageBytes: Int,
    private val maxPhotoBytes: Int
) {
    /**
     * Reads one complete RTSP or AirPlay photo request from [inputStream].
     *
     * Returns null on clean EOF, malformed input, or payloads over the configured limits.
     */
    fun read(inputStream: InputStream): RtspRequest? {
        // Skip leading blank lines (clients sometimes flush a stray CRLF between requests) with a
        // loop, not recursion — a noisy peer sending a run of CRLFs would otherwise overflow the
        // stack, and StackOverflowError is an Error, so the caller's catch(Exception) misses it.
        var requestLine: String
        var blankLines = 0
        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isNotBlank()) {
                requestLine = line
                break
            }
            if (++blankLines > MAX_LEADING_BLANK_LINES) {
                Logger.w("RTSP request started with $blankLines blank lines — rejecting")
                return null
            }
        }

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            Logger.w("Malformed RTSP request line: '$requestLine'")
            return null
        }

        val headers = readHeaders(inputStream, requestLine.length) ?: return null
        val method = parts[0]
        val uri = parts[1]
        val protocol = parts[2]
        val bodyBytes = readBody(inputStream, method, uri, headers) ?: return null

        return RtspRequest(
            method = method,
            uri = uri,
            headers = headers,
            body = String(bodyBytes, Charsets.UTF_8),
            bodyBytes = bodyBytes,
            protocol = protocol
        )
    }

    private fun readHeaders(inputStream: InputStream, requestLineBytes: Int): Map<String, String>? {
        // RTSP header names are case-insensitive (RFC 2326 §4), so lookups must not depend on the
        // sender's capitalisation. TreeMap keeps the comparison consistent for every later lookup.
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        var totalBytes = requestLineBytes

        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isEmpty()) return headers

            totalBytes += line.length
            if (totalBytes > maxMessageBytes) {
                Logger.w("RTSP message too large — rejecting")
                return null
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim()] =
                    line.substring(colonIndex + 1).trim()
            }
        }
    }

    private fun readBody(
        inputStream: InputStream,
        method: String,
        uri: String,
        headers: Map<String, String>
    ): ByteArray? {
        val contentLength = headers.header("Content-Length")?.toIntOrNull() ?: 0
        val bodyLimit = if (method == "PUT" && uri.substringBefore("?") == PhotoHandler.PHOTO_PATH) {
            maxPhotoBytes
        } else {
            maxMessageBytes
        }

        if (contentLength > bodyLimit) {
            Logger.w("Request body too large ($contentLength bytes) — rejecting")
            return null
        }
        if (contentLength <= 0) return ByteArray(0)

        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = inputStream.read(buf, read, contentLength - read)
            if (n == -1) return null
            read += n
        }
        return buf
    }

    private companion object {
        /** How many blank lines a client may send before a request before we give up on it. */
        const val MAX_LEADING_BLANK_LINES = 10
    }

    private fun readLine(inputStream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inputStream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > maxMessageBytes) return null
        }
    }
}
