package com.callmap.agenttracker.data.manager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UserUnlockGateTest {
    @Test fun lockedReadCannotCacheMissingFileAndResumesWithSavedSession() = runBlocking {
        var unlocked = false
        var storageOpened = false
        val unlockSignal = CompletableDeferred<Unit>()
        val gate = UserUnlockGate({ unlocked }, { unlockSignal.await() })
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            gate.access {
                storageOpened = true
                if (unlocked) "saved-registration" else null
            }
        }
        assertFalse(storageOpened)
        assertFalse(read.isCompleted)
        unlocked = true
        unlockSignal.complete(Unit)
        assertEquals("saved-registration", read.await())
    }

    @Test fun writesAlsoWaitWithoutOverwritingTheExistingSession() = runBlocking {
        var unlocked = false
        var stored = "saved-registration"
        val unlockSignal = CompletableDeferred<Unit>()
        val gate = UserUnlockGate({ unlocked }, { unlockSignal.await() })
        val write = async(start = CoroutineStart.UNDISPATCHED) {
            gate.access { stored += ":state-update" }
        }
        assertEquals("saved-registration", stored)
        unlocked = true
        unlockSignal.complete(Unit)
        write.await()
        assertEquals("saved-registration:state-update", stored)
    }

    @Test fun cancellationWhileLockedNeverOpensStorage() = runBlocking {
        var opened = false
        val gate = UserUnlockGate({ false }, { CompletableDeferred<Unit>().await() })
        val read = async(start = CoroutineStart.UNDISPATCHED) { gate.access { opened = true } }
        read.cancelAndJoin()
        assertFalse(opened)
    }
}
