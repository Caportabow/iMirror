package dev.imirror.receiver.airplay

import android.content.Context
import dev.imirror.receiver.airplay.handshake.PairingStore
import dev.imirror.receiver.airplay.handshake.PlistCodec
import dev.imirror.receiver.airplay.handshake.ReferenceSrpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger

/**
 * PinAuthFlowTest — the legacy AirPlay PIN access-control flow as a sender drives it:
 * `/pair-pin-start` → `/pair-setup-pin` step 1 → step 2 (proof) → optional step 3 (key exchange).
 *
 * WHY: this is the flow that made PIN pairing unusable in practice:
 *  - the failed-attempt counter was persistent and could only be cleared by a successful pairing, so
 *    after ten failed proofs PERMANENTLY every request got 470 — including with the right code, which
 *    is exactly what a sender reports as "the code is incorrect";
 *  - `/pair-pin-start` was itself gated by that counter, so there was no way back;
 *  - pairing was only considered done after the optional step-3 key exchange, so a sender that stops
 *    after M2 stayed blocked at pair-verify.
 *
 * The SRP crypto itself is covered by [LegacyPairSetupPinTest]; here we pin down the state machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinAuthFlowTest {

    private lateinit var context: Context
    private lateinit var store: PairingStore
    private var shownPin: String? = null

    /** How many times a NEW code was minted (hiding the code does not count). */
    private var pinsMinted: Int = 0

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("imirror_pairings", Context.MODE_PRIVATE).edit().clear().commit()
        store = PairingStore(context)
        shownPin = null
        pinsMinted = 0
    }

    @Test
    fun `pair-pin-start recovers from a stale lockout and shows a fresh four-digit code`() {
        repeat(10) { store.recordFailedAttempt() }
        val handler = createHandler()

        val response = handler.handlePairPinStartPublic(post("/pair-pin-start", ByteArray(0)))

        assertEquals(200, response.statusCode)
        val shown = shownPin
        assertTrue("a 4-digit code must be shown", shown != null && shown.matches(Regex("\\d{4}")))
        assertEquals("a new PIN starts a new attempt budget", 0, store.failedAttempts())
    }

    @Test
    fun `ten wrong proofs lock the current code, and a new pair-pin-start unlocks it`() {
        val handler = createHandler()

        repeat(10) {
            // Step 1 (no prior pair-pin-start the second time round) hands out salt + B.
            val step1 = handler.handlePairSetupPinPublic(
                post("/pair-setup-pin", step1Body("AirPlay"))
            )
            assertEquals("step 1 must answer before any proof", 200, step1.statusCode)
            val salt = saltOf(step1)
            val b = BigInteger(1, publicKeyOf(step1))

            val wrong = ReferenceSrpClient("AirPlay", "0000", salt, b)   // never the shown code
            val step2 = handler.handlePairSetupPinPublic(
                post("/pair-setup-pin", step2Body(wrong))
            )
            assertEquals("a wrong PIN must be refused", 470, step2.statusCode)
        }
        assertEquals(10, store.failedAttempts())

        assertEquals(
            "once the budget is spent the current code is refused outright",
            470,
            handler.handlePairSetupPinPublic(post("/pair-setup-pin", step1Body("AirPlay"))).statusCode
        )

        // The user (or the sender's next connection) starts pairing again → fresh PIN, fresh budget.
        assertEquals(200, handler.handlePairPinStartPublic(post("/pair-pin-start", ByteArray(0))).statusCode)
        assertEquals(0, store.failedAttempts())
        assertEquals(200, handler.handlePairSetupPinPublic(post("/pair-setup-pin", step1Body("AirPlay"))).statusCode)
    }

    @Test
    fun `a correct proof pairs the controller and hides the code before step 3`() {
        val handler = createHandler()
        assertEquals(200, handler.handlePairPinStartPublic(post("/pair-pin-start", ByteArray(0))).statusCode)
        val pin = shownPin!!
        assertFalse(handler.pinPairedForTest())

        val step1 = handler.handlePairSetupPinPublic(post("/pair-setup-pin", step1Body("AirPlay")))
        val client = ReferenceSrpClient("AirPlay", pin, saltOf(step1), BigInteger(1, publicKeyOf(step1)))
        val step2 = handler.handlePairSetupPinPublic(post("/pair-setup-pin", step2Body(client)))

        assertEquals(200, step2.statusCode)
        assertTrue("M2 proves knowledge of the PIN — pair-verify must be allowed from here", handler.pinPairedForTest())
        assertNull("the code must disappear once accepted", shownPin)

        // A sender that does follow up with the optional key exchange must still be served (no new PIN).
        val mintedAfterProof = pinsMinted
        val clientKey = ByteArray(32) { (10 + it).toByte() }
        val epk = client.step3Epk(clientKey)
        val step3 = handler.handlePairSetupPinPublic(
            post("/pair-setup-pin", step3Body(epk.copyOf(epk.size - 16), epk.copyOfRange(epk.size - 16, epk.size)))
        )
        assertEquals(200, step3.statusCode)
        assertEquals("step 3 must not mint another PIN", mintedAfterProof, pinsMinted)
        assertNull("step 3 must not put a code back on screen", shownPin)
        assertTrue(handler.pinPairedForTest())
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun createHandler(): TestableRtspHandler = TestableRtspHandler(
        onStreamingStarted = {},
        onStreamingStopped = {},
        pinAuthEnabled = true,
        pairingStore = store,
        onShowPin = { pin ->
            shownPin = pin
            if (pin != null) pinsMinted++
        },
        context = context
    )

    private fun post(uri: String, body: ByteArray) =
        RtspRequest(method = "POST", uri = uri, headers = emptyMap(), body = "", bodyBytes = body)

    private fun step1Body(user: String): ByteArray = PlistCodec.encode(mapOf("method" to "pin", "user" to user))

    private fun step2Body(client: ReferenceSrpClient): ByteArray = PlistCodec.encode(
        mapOf("pk" to ReferenceSrpClient.toBytes(client.a), "proof" to client.m1)
    )

    private fun step3Body(epk: ByteArray, authTag: ByteArray): ByteArray =
        PlistCodec.encode(mapOf("epk" to epk, "authTag" to authTag))

    private fun saltOf(response: RtspResponse): ByteArray =
        PlistCodec.decode(response.bodyBytes!!)["salt"] as ByteArray

    private fun publicKeyOf(response: RtspResponse): ByteArray =
        PlistCodec.decode(response.bodyBytes!!)["pk"] as ByteArray
}
