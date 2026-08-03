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
 * failed attempt is serialized behind [lock] and answered after a delay that
 * doubles with each consecutive failure, without bound. A mistyped PIN or two
 * costs under a second; a sweep of the space is over an hour in by the twenty-
 * fifth guess and only gets worse. No clock is needed, which is convenient,
 * because this module has no way to read one.
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
        val doublings = failures - 1
        // Not a policy cap — shifting further would overflow the Long.
        if (doublings > MAX_DOUBLINGS_BEFORE_OVERFLOW) return Long.MAX_VALUE
        return BASE_PENALTY_MILLIS shl doublings
    }

    companion object {
        const val SESSION_COOKIE = "magicpaste_session"

        /**
         * The cookie used when TLS is on, deliberately a different name.
         *
         * Cookies are scoped to a host, not to a port or a scheme, so both modes
         * would otherwise write the same one. That breaks badly in one direction:
         * a cookie set `Secure` over HTTPS cannot be overwritten by a plain one
         * over HTTP — browsers refuse it outright — so after using HTTPS once,
         * logging in over HTTP would appear to succeed and change nothing.
         * Separate names keep the two from ever meeting.
         */
        const val SECURE_SESSION_COOKIE = "magicpaste_session_tls"

        /** The cookie name for a server that is, or is not, behind TLS. */
        fun sessionCookieName(secure: Boolean): String =
            if (secure) SECURE_SESSION_COOKIE else SESSION_COOKIE

        /** Header carrying the PIN itself, for `curl` and other non-browser clients. */
        const val PIN_HEADER = "X-MagicPaste-Pin"

        private const val BASE_PENALTY_MILLIS = 250L

        /** 250ms is just under 2^8; 54 more doublings is the last that fits in a Long. */
        private const val MAX_DOUBLINGS_BEFORE_OVERFLOW = 54
    }
}
