package dev.imirror.receiver.service

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.imirror.receiver.util.Logger

/**
 * ServiceController — Provides a clean API to start, stop, and restart the MirrorService.
 *
 * WHY: Sending Intent actions to a service is verbose and error-prone if done directly
 * from the UI. This class centralizes all service control commands so the UI just calls
 * `ServiceController.start(context)` — no Intent construction in Fragment code.
 *
 * HOW: All methods are on the companion object (static-like). No instance needed.
 * Call from any Fragment or ViewModel that has a Context.
 *
 * Example:
 *   // From a Fragment or Activity:
 *   ServiceController.start(requireContext())
 *   ServiceController.stop(requireContext())
 *   ServiceController.restart(requireContext())
 */
object ServiceController {

    /**
     * Starts the MirrorService if it is not already running.
     *
     * Uses [ContextCompat.startForegroundService] which works correctly on all
     * Android versions (API 26+ requires the foreground service to be started
     * this way to avoid background start restrictions).
     *
     * @param context Any valid Android context.
     */
    fun start(context: Context) {
        Logger.i("ServiceController: start()")
        val intent = buildIntent(context, MirrorService.ACTION_START)
        send(context, intent)
    }

    /**
     * Stops the MirrorService.
     *
     * All receivers are stopped, all network ports are released, and the
     * persistent notification is removed.
     *
     * @param context Any valid Android context.
     */
    fun stop(context: Context) {
        Logger.i("ServiceController: stop()")
        // stopService is the right primitive here: it tears the service down through onDestroy (which
        // releases the receivers and closes the RTSP session), works whether or not the service is
        // running, and needs no foreground start. The old startService(ACTION_STOP) could throw
        // IllegalStateException on API 26+ — typically from MainActivity.onDestroy when the app was
        // already in the background — which crashed the app on exit.
        val intent = Intent(context, MirrorService::class.java)
        runCatching { context.stopService(intent) }
            .onFailure { Logger.e("ServiceController: stopService failed", it) }
    }

    /**
     * Restarts the MirrorService.
     *
     * Sends a restart command that stops all receivers and starts them again
     * with the latest settings. Useful after changing Settings or recovering
     * from an error state.
     *
     * The service itself keeps running during restart (no stopSelf() is called).
     * The visible interruption is brief (<500ms for port release + re-advertise).
     *
     * @param context Any valid Android context.
     */
    fun restart(context: Context) {
        Logger.i("ServiceController: restart()")
        val intent = buildIntent(context, MirrorService.ACTION_RESTART)
        send(context, intent)
    }

    /**
     * Delivers a start/restart action to the service.
     *
     * Starting a foreground service from the background is restricted on Android 12+ and throws
     * [android.app.ForegroundServiceStartNotAllowedException]; losing a settings-driven restart must
     * not take the app down with it, so failures are logged instead.
     */
    private fun send(context: Context, intent: Intent) {
        runCatching { startForegroundServiceCompat(context, intent) }
            .onFailure { Logger.e("ServiceController: ${intent.action} delivery failed", it) }
    }

    /**
     * Starts a foreground service using [Context.startForegroundService] on API 26+,
     * falling back to [Context.startService] on older versions.
     *
     * This replaces the AndroidX [androidx.core.content.ContextCompat.startForegroundService]
     * helper so that this class has no AndroidX dependency.
     */
    private fun startForegroundServiceCompat(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Constructs a service control [Intent] with the given action.
     *
     * @param context The calling context.
     * @param action  One of [MirrorService.ACTION_START], ACTION_STOP, ACTION_RESTART.
     */
    private fun buildIntent(context: Context, action: String): Intent =
        Intent(context, MirrorService::class.java).apply { this.action = action }
}
