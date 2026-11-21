package dev.imirror.receiver.airplay.handshake

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ReferenceSrpClient — a client-side SRP-6a implementation of Apple's legacy AirPlay
 * `/pair-setup-pin` handshake, used by the pairing tests to drive [LegacyPairSetupPin] (and
 * `/pair-setup-pin` through [dev.imirror.receiver.airplay.RtspHandler]).
 *
 * WHY: the server code must agree with what a real sender computes. SRP is self-checking: the client
 * derives S as `(B − k·g^x)^(a+u·x)` while the server uses `(A·v^u)^b`, and those only converge if k,
 * u, x, the padding and Apple's two-counter session key all match byte for byte. Matching the
 * `rfc5054_compat` path of UxPlay (a receiver whose PIN pairing works with macOS/iOS) makes a passing
 * handshake here meaningful rather than a self-consistent round-trip.
 *
 * The AES-GCM helpers are here too because step 3 of the same handshake needs them.
 */
internal class ReferenceSrpClient(user: String, pin: String, salt: ByteArray, b: BigInteger) {

    val a: BigInteger
    val m1: ByteArray
    val m2: ByteArray
    val k: ByteArray   // 40-byte SRP session key

    init {
        val aPriv = BigInteger(256, SecureRandom())
        a = G.modPow(aPriv, N)
        val x = BigInteger(1, sha1(salt, sha1("$user:$pin".toByteArray())))
        val kMul = BigInteger(1, sha1(pad(N), pad(G)))
        val u = BigInteger(1, sha1(pad(a), pad(b)))
        // client secret: S = (B − k·g^x)^(a + u·x)
        val s = b.subtract(kMul.multiply(G.modPow(x, N))).mod(N).modPow(aPriv.add(u.multiply(x)), N)
        k = sessionKey(toBytes(s))
        val hNxorHg = sha1(toBytes(N)).let { hn ->
            val hg = sha1(toBytes(G))
            ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        }
        m1 = sha1(hNxorHg, sha1(user.toByteArray()), salt, toBytes(a), toBytes(b), k)
        m2 = sha1(toBytes(a), m1, k)
    }

    /** Step-3 request body: the client's Ed25519 key encrypted at IV+1 (ciphertext‖tag, 48 bytes). */
    fun step3Epk(clientEdPublic: ByteArray): ByteArray {
        val ivAtM1 = aesIv().also { incrementIv(it) }
        return gcm(true, aesKey(), ivAtM1, clientEdPublic)!!
    }

    /** AES key for the step-3 key exchange: SHA-512("Pair-Setup-AES-Key" ‖ K)[0..15]. */
    fun aesKey(): ByteArray = sha512("Pair-Setup-AES-Key".toByteArray(), k).copyOf(16)

    /** Raw (not incremented) AES IV for the step-3 key exchange: SHA-512("Pair-Setup-AES-IV" ‖ K)[0..15]. */
    fun aesIv(): ByteArray = sha512("Pair-Setup-AES-IV".toByteArray(), k).copyOf(16)

    companion object {
        private const val N_BYTES = 256

        val G: BigInteger = BigInteger.valueOf(2)

        /** RFC 5054 2048-bit SRP group prime. */
        val N: BigInteger = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16
        )

        /** Apple session key K = H(S | 0x00000000) ‖ H(S | 0x00000001). */
        fun sessionKey(sBytes: ByteArray): ByteArray =
            sha1(sBytes, byteArrayOf(0, 0, 0, 0)) + sha1(sBytes, byteArrayOf(0, 0, 0, 1))

        fun sha1(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        /** Increment a 16-byte IV by 1 as a big-endian counter (carry from the last byte). */
        fun incrementIv(iv: ByteArray) {
            for (i in iv.indices.reversed()) {
                iv[i] = (iv[i] + 1).toByte()
                if (iv[i].toInt() != 0) break
            }
        }

        /** AES-128-GCM (128-bit tag): ciphertext‖tag when encrypting, plaintext or null when decrypting. */
        fun gcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray? = try {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv)
            )
            c.doFinal(input)
        } catch (_: Exception) { null }

        /** Minimal big-endian bytes, stripping a single leading sign byte. */
        fun toBytes(bi: BigInteger): ByteArray {
            val b = bi.toByteArray()
            return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
        }

        /** Left-pad to N's byte length (SRP6 computeU/computeK pad each value to N's length). */
        fun pad(bi: BigInteger): ByteArray {
            val raw = toBytes(bi)
            if (raw.size >= N_BYTES) return raw
            return ByteArray(N_BYTES).also { System.arraycopy(raw, 0, it, N_BYTES - raw.size, raw.size) }
        }
    }
}
