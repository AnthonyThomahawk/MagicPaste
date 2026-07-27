package com.tonyt.magicpaste.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Source of unguessable session identifiers.
 *
 * An interface rather than a call to `Random`, because the stdlib's generator is
 * not cryptographically secure and a guessable session token would undo the PIN
 * entirely. Platforms supply the real thing — `SecureRandom` on Android.
 */
fun interface TokenSource {
    fun newToken(): String
}

/**
 * Guards the server with a PIN that the device owner reads off the app.
 *
 * A short numeric PIN is only worth anything if guesses are expensive, so every
 * failed attempt is serialized behind [lock] and answered after a growing delay.
 * That makes the search space unreachable in practice — a few attempts a second
 * at first, one per five seconds shortly after — without needing a clock, which
 * this module has no way to read.
 *
 * The cost of serializing is that a determined attacker can hold up other
 * *logins* while they hammer. Already-authenticated browsers carry a session
 * token and never touch this path, so live clipboard traffic is unaffected.
 */
class PinGate(
    private val pin: String,
    private val tokens: TokenSource,
) {
    private val lock = Mutex()
    private val sessions = mutableSetOf<String>()
    private var consecutiveFailures = 0

    /** True when [candidate] is the PIN. Rate-limited; creates no session. */
    suspend fun verify(candidate: String): Boolean = lock.withLock { check(candidate) }

    /**
     * Verifies [candidate] and, on success, returns a fresh session token to
     * hand back as a cookie. Null means the PIN was wrong.
     */
    suspend fun authenticate(candidate: String): String? = lock.withLock {
        if (!check(candidate)) return@withLock null
        tokens.newToken().also { sessions += it }
    }

    /** True when [token] came from a successful [authenticate]. */
    suspend fun isValidSession(token: String?): Boolean {
        if (token == null) return false
        return lock.withLock { token in sessions }
    }

    /** Must be called with [lock] held. */
    private suspend fun check(candidate: String): Boolean {
        if (!candidate.matchesPin()) {
            consecutiveFailures++
            delay(penaltyMillis(consecutiveFailures))
            return false
        }
        consecutiveFailures = 0
        return true
    }

    /**
     * Compares without an early exit, so the time taken says nothing about how
     * many leading digits were right.
     */
    private fun String.matchesPin(): Boolean {
        if (length != pin.length) return false
        var difference = 0
        for (index in indices) difference = difference or (this[index].code xor pin[index].code)
        return difference == 0
    }

    private fun penaltyMillis(failures: Int): Long {
        val doublings = (failures - 1).coerceAtMost(MAX_DOUBLINGS)
        return (BASE_PENALTY_MILLIS shl doublings).coerceAtMost(MAX_PENALTY_MILLIS)
    }

    companion object {
        const val SESSION_COOKIE = "magicpaste_session"

        /** Header carrying the PIN itself, for `curl` and other non-browser clients. */
        const val PIN_HEADER = "X-MagicPaste-Pin"

        private const val BASE_PENALTY_MILLIS = 250L
        private const val MAX_PENALTY_MILLIS = 5_000L
        private const val MAX_DOUBLINGS = 5
    }
}
