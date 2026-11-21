package dev.imirror.receiver.util

/**
 * MdnsLabel — DNS-SD label budgeting for the names iMirror advertises.
 *
 * DNS-SD labels are limited to 63 **bytes**, not characters, and Android's NsdManager enforces that
 * limit on registration. The Settings screen used to allow 63 characters, which is fine for ASCII but
 * not for Cyrillic or CJK text (2–3 bytes per character). The worst case was the RAOP service, whose
 * label is `"<12 hex digits>@<name>"` — a 13-byte prefix — so a name over ~50 bytes made
 * `_raop._tcp` registration fail, which flipped the receiver into ERROR and removed it from sender
 * menus entirely.
 *
 * Clamping here keeps both registrations valid no matter what the user typed, without ever splitting a
 * UTF-8 sequence in half (which would advertise a broken name).
 */
object MdnsLabel {

    /** Maximum bytes in one DNS-SD label. */
    const val MAX_LABEL_BYTES = 63

    /** `"AABBCCDDEEFF@"` — the fixed part of the RAOP service label. */
    const val RAOP_PREFIX_BYTES = 13

    /** Bytes left for the device name inside the `_raop._tcp` label. */
    const val MAX_RAOP_NAME_BYTES = MAX_LABEL_BYTES - RAOP_PREFIX_BYTES

    /**
     * Truncates [text] to at most [maxBytes] UTF-8 bytes on a code-point boundary.
     *
     * Trailing whitespace is dropped so a name cut mid-word does not keep a dangling space. Strings
     * that already fit are returned unchanged.
     */
    fun clampUtf8(text: String, maxBytes: Int): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text

        val builder = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val codePointBytes = utf8Length(codePoint)
            if (bytes + codePointBytes > maxBytes) break
            builder.appendCodePoint(codePoint)
            bytes += codePointBytes
            i += Character.charCount(codePoint)
        }
        return builder.toString().trimEnd()
    }

    /** Byte length of [codePoint] when encoded as UTF-8. */
    private fun utf8Length(codePoint: Int): Int = when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }
}
