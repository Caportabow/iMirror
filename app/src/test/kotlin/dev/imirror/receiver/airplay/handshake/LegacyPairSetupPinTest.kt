package dev.imirror.receiver.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Validates [LegacyPairSetupPin] (the SRP server) against [ReferenceSrpClient], a client implemented
 * from Apple's legacy AirPlay routines (UxPlay's `rfc5054_compat` path + the two-counter session key).
 * A passing handshake means the byte-level crypto matches what macOS sends.
 *
 * Also pins down WHAT COUNTS AS PAIRED: the PIN proof (step 2) is the access-control decision, while
 * step 3 is only the optional exchange of long-term keys. Treating step 3 as mandatory is what left a
 * sender that stops after M2 unable to get past pair-verify.
 */
class LegacyPairSetupPinTest {

    @Test
    fun `full SRP + AES-GCM handshake against a reference Apple client succeeds`() {
        val pin = "4271"
        val user = "52:C7:B3:75:A2:D7"
        val serverEdPublic = ByteArray(32) { it.toByte() }
        val server = LegacyPairSetupPin(pin, serverEdPublic)

        // ── step 1 → salt, B ─────────────────────────────────────────────────
        val r1 = server.handle(mapOf("method" to "pin", "user" to user))
        val salt = r1.reply!!["salt"] as ByteArray
        val b = BigInteger(1, r1.reply!!["pk"] as ByteArray)

        // ── step 2: client M1 proof → server M2 ──────────────────────────────
        val client = ReferenceSrpClient(user, pin, salt, b)
        val r2 = server.handle(mapOf("pk" to ReferenceSrpClient.toBytes(client.a), "proof" to client.m1))
        assertFalse("a correct proof must not be reported as failed", r2.failed)
        assertNotNull(r2.reply)
        assertArrayEquals("server M2 must match", client.m2, r2.reply!!["proof"] as ByteArray)
        assertTrue("the PIN proof alone must count as paired", r2.pinVerified)
        assertFalse("step 3 has not run yet", r2.complete)

        // ── step 3: AES-GCM key exchange ─────────────────────────────────────
        val aesKey = client.aesKey()
        val clientEdPublic = ByteArray(32) { (100 + it).toByte() }
        val clientEpk = client.step3Epk(clientEdPublic)   // encrypted at IV+1
        val r3 = server.handle(mapOf(
            "epk" to clientEpk.copyOf(clientEpk.size - 16),
            "authTag" to clientEpk.copyOfRange(clientEpk.size - 16, clientEpk.size),
        ))
        assertTrue("server must complete after the AES key exchange", r3.complete)
        // Server reply is encrypted at IV+2 (per-message counter) — decrypt with the next IV.
        val aesIv = client.aesIv().also {
            ReferenceSrpClient.incrementIv(it)
            ReferenceSrpClient.incrementIv(it)
        }
        val serverEpk = (r3.reply!!["epk"] as ByteArray) + (r3.reply!!["authTag"] as ByteArray)
        assertArrayEquals(
            "decrypted accessory key must match",
            serverEdPublic,
            ReferenceSrpClient.gcm(false, aesKey, aesIv, serverEpk)
        )
    }

    @Test
    fun `wrong PIN is rejected`() {
        val server = LegacyPairSetupPin("4271", ByteArray(32))
        val r1 = server.handle(mapOf("method" to "pin", "user" to "u"))
        val salt = r1.reply!!["salt"] as ByteArray
        val b = BigInteger(1, r1.reply!!["pk"] as ByteArray)
        val client = ReferenceSrpClient("u", "0000", salt, b)   // wrong PIN
        val r2 = server.handle(mapOf("pk" to ReferenceSrpClient.toBytes(client.a), "proof" to client.m1))
        assertTrue("server must reject a wrong-PIN proof", r2.failed)
        assertFalse("a rejected proof must never count as paired", r2.pinVerified)
    }
}
