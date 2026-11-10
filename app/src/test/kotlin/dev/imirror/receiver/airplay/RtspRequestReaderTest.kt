package dev.imirror.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtspRequestReaderTest — Parsing tests for the RTSP control socket.
 *
 * WHY: The reader handles untrusted bytes from the network. Header lookup must not depend on the
 * sender's capitalisation (RTSP header names are case-insensitive), and a noisy peer sending a run of
 * CRLFs must not be able to blow the stack.
 */
class RtspRequestReaderTest {

    private fun reader() = RtspRequestReader(maxMessageBytes = 65536, maxPhotoBytes = 1024)

    private fun parse(raw: String) =
        reader().read(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))

    @Test
    fun `header names are matched case-insensitively`() {
        val request = parse("ANNOUNCE rtsp://x/ RTSP/1.0\r\ncseq: 7\r\nContent-Type: text/plain\r\n\r\n")

        assertEquals("7", request?.headers?.get("CSeq"))
        assertEquals("7", request?.headers?.header("CSEQ"))
        assertEquals("text/plain", request?.headers?.header("content-type"))
    }

    @Test
    fun `lowercase content-length still reads the body`() {
        val body = "v=0\ns=AirTunes"
        val raw = "ANNOUNCE rtsp://x/ RTSP/1.0\r\ncontent-length: ${body.length}\r\n\r\n$body"

        val request = parse(raw)

        assertEquals(body, request?.body)
        assertEquals(body.length, request?.bodyBytes?.size)
    }

    @Test
    fun `leading blank lines are skipped`() {
        val request = parse("\r\n\r\n\r\nOPTIONS rtsp://x/ RTSP/1.0\r\nCSeq: 1\r\n\r\n")

        assertEquals("OPTIONS", request?.method)
        assertEquals("1", request?.headers?.header("CSeq"))
    }

    @Test
    fun `an endless run of blank lines is rejected without recursion`() {
        val raw = "\r\n".repeat(10_000) + "OPTIONS rtsp://x/ RTSP/1.0\r\n\r\n"

        assertNull(parse(raw))
    }
}
