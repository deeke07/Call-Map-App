package com.callmap.agenttracker

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callmap.agenttracker.data.manager.SessionManagerImpl
import com.callmap.agenttracker.domain.model.RegistrationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Invoked explicitly by scripts/test-reboot-recovery.sh on its dedicated emulator only. */
@RunWith(AndroidJUnit4::class)
class RecoveryFixtureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = SessionManagerImpl(context)

    private fun requireTestEmulator() {
        assumeTrue("Run with scripts/test-reboot-recovery.sh",
            InstrumentationRegistry.getArguments().getString("callmapRecoveryFixture") == "true")
        check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Emulator only" }
    }

    @Test fun prepare() = runBlocking {
        requireTestEmulator()
        val existing = session.getRegistration().first()
        check(existing == null || existing.deviceUuid == TEST_UUID) { "Refusing to replace a real registration" }
        // Never point fixture uploads at a real backend, including immediately after reboot.
        session.saveBaseUrl("https://127.0.0.1:9")
        session.saveRegistration(RegistrationResult(
            deviceUuid = TEST_UUID,
            deviceName = "Recovery Test Emulator",
            agentName = "Recovery Test User",
            agentEmail = "recovery@example.invalid",
            agentProfile = "",
            recordingEnabled = false,
            trackingEnabled = false,
            locationFrequency = 300_000,
            locationOnCall = false
        ))
        assertEquals(TEST_UUID, session.getRegistration().first()?.deviceUuid)
    }

    @Test fun verifySession() = runBlocking {
        requireTestEmulator()
        assertEquals(TEST_UUID, session.getRegistration().first()?.deviceUuid)
        assertEquals("recovery@example.invalid", session.getRegistration().first()?.agentEmail)
        assertEquals("https://127.0.0.1:9", session.getBaseUrl().first())
    }

    companion object {
        const val TEST_UUID = "56a3856a-9e5c-45d8-a19a-6d6307c3c0bc"
    }
}
