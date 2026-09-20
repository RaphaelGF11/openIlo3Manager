package net.raphaelgf11.ilo3manager.auth

import android.os.SystemClock

/**
 * Tracks whether the app is currently unlocked, outside the composition.
 *
 * Re-locking on every ON_STOP is too blunt: opening the QR scanner or the file picker puts the
 * activity in the background, so the user was thrown back to the biometric prompt — and, since
 * those screens can also recreate the activity, back to the host list with the form they were
 * filling in lost. A short grace period distinguishes "the user stepped away" from "the app itself
 * opened a picker".
 *
 * State lives in a process-scoped object rather than in composition so it survives activity
 * recreation (a rotation must not re-prompt) while still being lost when the process dies, which
 * is what should genuinely require authenticating again.
 */
object AppLock {

    /** Long enough to pick a file or scan a code, short enough that a real task switch re-locks. */
    private const val GRACE_MS = 30_000L

    private var unlocked = false
    private var backgroundedAt = 0L

    fun markUnlocked() {
        unlocked = true
        backgroundedAt = 0L
    }

    fun markBackgrounded() {
        if (unlocked && backgroundedAt == 0L) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }
    }

    fun isUnlocked(): Boolean {
        if (!unlocked) return false
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > GRACE_MS) {
            unlocked = false
        }
        return unlocked
    }

    fun lock() {
        unlocked = false
        backgroundedAt = 0L
    }
}
