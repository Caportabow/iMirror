package dev.imirror.receiver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MdnsLabelTest — regression tests for the DNS-SD label budget.
 *
 * WHY: An over-long label makes NsdManager reject `_raop._tcp`, which flips the receiver to ERROR and
 * removes the device from every sender menu. The limits are in bytes, not characters, so these tests
 * pin both the byte budget and the "never split a code point" rule.
 */
class MdnsLabelTest {

    private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun `ascii name within budget is unchanged`() {
        val name = "Living Room TV"
        assertEquals(name, MdnsLabel.clampUtf8(name, MdnsLabel.MAX_LABEL_BYTES))
    }

    @Test
    fun `name exactly at the byte limit is unchanged`() {
        val name = "a".repeat(MdnsLabel.MAX_LABEL_BYTES)
        assertEquals(name, MdnsLabel.clampUtf8(name, MdnsLabel.MAX_LABEL_BYTES))
    }

    @Test
    fun `cyrillic name is clamped by bytes not characters`() {
        // 40 Cyrillic characters = 80 UTF-8 bytes, well past the 63-byte label limit.
        val name = "Ж".repeat(40)
        val clamped = MdnsLabel.clampUtf8(name, MdnsLabel.MAX_LABEL_BYTES)

        assertEquals(31, clamped.length)                       // 31 × 2 = 62 bytes, 32 would be 64
        assertEquals("Ж".repeat(31), clamped)
        assertTrue(utf8Size(clamped) <= MdnsLabel.MAX_LABEL_BYTES)
    }

    @Test
    fun `raop budget leaves room for the mac prefix`() {
        // "AABBCCDDEEFF@" is 13 bytes, so only 50 bytes are left for the name.
        val name = "Ж".repeat(40)
        val clamped = MdnsLabel.clampUtf8(name, MdnsLabel.MAX_RAOP_NAME_BYTES)
        val label = "AABBCCDDEEFF@".toByteArray(Charsets.UTF_8).size + utf8Size(clamped)

        assertEquals(25, clamped.length)                       // 25 × 2 = 50 bytes
        assertTrue(label <= MdnsLabel.MAX_LABEL_BYTES)
    }

    @Test
    fun `surrogate pairs are never split`() {
        // Each emoji is a surrogate pair in UTF-16 and 4 bytes in UTF-8.
        val name = "😀".repeat(20)                              // 80 bytes
        val clamped = MdnsLabel.clampUtf8(name, MdnsLabel.MAX_LABEL_BYTES)

        assertEquals("😀".repeat(15), clamped)                 // 15 × 4 = 60 bytes
        assertEquals(60, utf8Size(clamped))
        assertTrue(utf8Size(clamped) <= MdnsLabel.MAX_LABEL_BYTES)
    }

    @Test
    fun `long ascii name is cut at the byte limit`() {
        val name = "Living Room Television"
        val clamped = MdnsLabel.clampUtf8(name, 20)

        assertEquals("Living Room Televisi", clamped)
        assertEquals(20, utf8Size(clamped))
    }

    @Test
    fun `trailing space is dropped when truncation happens`() {
        // 11 bytes lands exactly on the space after "abcdefghij"; it must not survive.
        val clamped = MdnsLabel.clampUtf8("abcdefghij klm", 11)

        assertEquals("abcdefghij", clamped)
    }

    @Test
    fun `blank name is returned as is`() {
        assertEquals("", MdnsLabel.clampUtf8("", MdnsLabel.MAX_LABEL_BYTES))
    }
}
