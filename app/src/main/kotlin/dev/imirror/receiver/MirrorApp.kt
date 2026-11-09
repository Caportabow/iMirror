package dev.imirror.receiver

import android.app.Application
import android.util.Log
import timber.log.Timber

/**
 * MirrorApp — The Application class for iMirror.
 *
 * WHY: Android requires an Application subclass to run initialization code before
 * any Activity or Service starts. We use this to set up logging (Timber) once
 * at startup.
 *
 * HOW: This class is referenced in AndroidManifest.xml via android:name=".MirrorApp".
 * It is instantiated automatically by the Android framework when the app process starts.
 *
 * Example (automatic — no user code needed):
 *   The app starts → Android creates MirrorApp → onCreate() runs → Timber is ready.
 */
class MirrorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging()
    }

    /**
     * Sets up Timber logging.
     *
     * In debug builds: [Timber.DebugTree] logs everything with file/line info.
     * In release builds: [ReleaseLogTree] writes warnings and errors to logcat.
     *
     * WHY a release tree at all: every component in the app logs through [dev.imirror.receiver.util.Logger],
     * which forwards to Timber. Timber silently drops events when no tree is planted, so a
     * release build without a tree has NO diagnostics at all — exactly when they matter most
     * (a TV on someone else's Wi-Fi, a sender that won't connect). Errors must still reach
     * logcat (`adb logcat -s iMirror`), while verbose/debug noise stays out of release.
     */
    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseLogTree())
        }
    }

    /**
     * Release logging backend: writes WARN and ERROR events to logcat under a single, filterable
     * tag. Info/debug/verbose are dropped — on a release build they are noise, and the interesting
     * information at info level is already duplicated by the surrounding warning/error events.
     */
    private class ReleaseLogTree : Timber.Tree() {

        override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val logTag = if (tag.isNullOrEmpty()) LOG_TAG else "$LOG_TAG-$tag"
            if (t == null) {
                Log.println(priority, logTag, message)
            } else {
                // Fold the stack trace into one call so multi-line traces stay grouped in logcat.
                Log.println(priority, logTag, "$message\n${Log.getStackTraceString(t)}")
            }
        }
    }

    companion object {
        /** logcat tag filter for release diagnostics: `adb logcat -s iMirror`. */
        private const val LOG_TAG = "iMirror"
    }
}
