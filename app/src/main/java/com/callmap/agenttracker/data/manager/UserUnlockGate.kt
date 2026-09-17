package com.callmap.agenttracker.data.manager

import kotlinx.coroutines.delay

/** Do not even open credential-protected storage until the first unlock after boot. */
internal class UserUnlockGate(
    private val isUnlocked: () -> Boolean,
    private val awaitNextCheck: suspend () -> Unit = { delay(250) }
) {
    suspend fun <T> access(block: suspend () -> T): T {
        while (!isUnlocked()) awaitNextCheck()
        return block()
    }
}
