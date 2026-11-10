package dev.imirror.receiver.util

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import timber.log.Timber
import java.net.NetworkInterface
import java.util.UUID

/**
 * NetworkUtils — Helper functions for reading network interface information.
 *
 * WHY: The AirPlay protocol requires the receiver to advertise its MAC address
 * and device name via mDNS TXT records. This class provides clean, safe methods
 * to read this information from the Android system.
 *
 * HOW: All methods are static (on the companion object) — no instance needed.
 * Read the device name and MAC address once at startup and pass them to MdnsService.
 *
 * Example:
 *   val name = NetworkUtils.getDeviceName(context)   // "My Android TV"
 *   val mac  = NetworkUtils.getMacAddress(context)    // "aa:bb:cc:dd:ee:ff"
 *   val uuid = NetworkUtils.getPersistentUuid(context) // stable UUID per device
 */
object NetworkUtils {

    /**
     * Returns the user-visible device name as configured in Android settings.
     *
     * This is the name that will appear in the macOS AirPlay picker, so it's
     * important that it matches what the user set in their TV's settings.
     *
     * Sources tried in order:
     * 1. Settings.Global.DEVICE_NAME (Android 5+, most TVs)
     * 2. Settings.Secure.BLUETOOTH_NAME (Bluetooth device name, often same as device name)
     * 3. Fallback: "iMirror" (if neither source is available)
     *
     * SECURITY: The returned value is sanitized — mDNS service names must not contain
     * certain special characters. We strip any character outside [A-Za-z0-9 _-].
     *
     * @param context Android context (needed to read system settings)
     * @return The sanitized device name, never null or empty.
     */
    fun getDeviceName(context: Context): String {
        val rawName = Settings.Global.getString(context.contentResolver, "device_name")
            ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            ?: DEFAULT_DEVICE_NAME

        // Sanitize: keep only safe characters for mDNS service names
        val sanitized = rawName.replace(Regex("[^A-Za-z0-9 _\\-]"), "").trim()

        return sanitized.ifEmpty { DEFAULT_DEVICE_NAME }
    }

    /**
     * Returns the device's stable Wi-Fi or Ethernet MAC address.
     *
     * The MAC address is used as the `deviceid` in AirPlay mDNS TXT records and in the RAOP service
     * name, so macOS treats changes to it as a DIFFERENT receiver (duplicate entries in the AirPlay
     * menu). Android lists whatever interfaces happen to be up: on a TV with Ethernet, Wi-Fi and
     * Wi-Fi Direct that includes `p2p0`/`dummy0`, and enumeration order is not stable. So the address
     * is picked once from the best real interface and persisted; later interface churn cannot change
     * the identity of an already-configured receiver.
     *
     * Preference order: `wlan*` (most TVs are Wi-Fi), then `eth*`, then any other physical interface.
     * Virtual interfaces (Wi-Fi Direct, cellular, VPN, dummy, tunnels) are ignored.
     *
     * NOTE: On Android 10+, direct MAC access is restricted. We use [NetworkInterface]
     * instead of WifiManager.getConnectionInfo() which is deprecated.
     *
     * @param context Android context (needed to persist the chosen address)
     * @return MAC address in "aa:bb:cc:dd:ee:ff" format (lowercase, colon-separated).
     */
    fun getMacAddress(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(PREF_KEY_DEVICE_MAC, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val detected = detectMacAddress()
        if (detected != null) {
            // Persist only a real address: the fallback must never become the permanent identity.
            runCatching { prefs.edit().putString(PREF_KEY_DEVICE_MAC, detected).apply() }
                .onFailure { Timber.w(it, "Could not persist MAC address") }
            return detected
        }
        return FALLBACK_MAC_ADDRESS
    }

    /** Best hardware address among the currently up physical interfaces, or null if none. */
    private fun detectMacAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { iface ->
                !iface.isLoopback &&
                    iface.isUp &&
                    iface.hardwareAddress?.any { it.toInt() != 0 } == true &&
                    VIRTUAL_INTERFACE_PREFIXES.none { iface.name.startsWith(it) }
            }
            ?.sortedBy { interfaceRank(it.name) }
            ?.mapNotNull { iface ->
                iface.hardwareAddress?.joinToString(":") { byte -> "%02x".format(byte) }
            }
            ?.firstOrNull()
    } catch (e: Exception) {
        Timber.w(e, "Could not read MAC address — using fallback")
        null
    }

    private fun interfaceRank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        else -> 2
    }

    /**
     * Returns a stable, device-specific UUID for use in AirPlay's `pi` TXT record.
     *
     * WHY: macOS uses the `pi` (persistent identifier) to recognize a receiver
     * across app restarts. If we generate a new UUID every time, macOS may show
     * duplicate entries in the AirPlay menu.
     *
     * This UUID is generated once and stored in Android's secure settings,
     * so it persists across app restarts and even reinstalls (as long as the
     * app's data is not cleared).
     *
     * SECURITY: This UUID is not a secret — it's transmitted in plaintext via mDNS.
     * It does not contain any sensitive device information.
     *
     * @param context Android context (needed to read/write secure settings)
     * @return A stable UUID string in standard "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" format.
     */
    fun getPersistentUuid(context: Context): String {
        // Persist the UUID in the app's own private SharedPreferences.
        // (Originally this used Settings.Secure, which requires the privileged
        // WRITE_SECURE_SETTINGS permission and threw a SecurityException on normal
        // installs, aborting AirPlay receiver startup. App-private storage needs no
        // permission and still persists across restarts.)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedUuid = prefs.getString(PREF_KEY_DEVICE_UUID, null)
        if (!storedUuid.isNullOrBlank()) {
            return storedUuid
        }

        // Generate a new UUID and store it for future use
        val newUuid = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_KEY_DEVICE_UUID, newUuid).apply()
        return newUuid
    }

    // Constants
    private const val DEFAULT_DEVICE_NAME = "iMirror"
    private const val FALLBACK_MAC_ADDRESS = "aa:bb:cc:dd:ee:ff"
    private const val PREFS_NAME = "imirror_prefs"
    private const val PREF_KEY_DEVICE_UUID = "imirror_device_uuid"
    private const val PREF_KEY_DEVICE_MAC = "imirror_device_mac"

    /** Interface name prefixes that never carry the receiver's LAN identity. */
    private val VIRTUAL_INTERFACE_PREFIXES = listOf(
        "p2p", "dummy", "rmnet", "tun", "sit", "ip6tnl", "gre", "ppp", "v4-", "ifb"
    )
}
