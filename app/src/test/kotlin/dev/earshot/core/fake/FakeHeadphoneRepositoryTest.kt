package dev.earshot.core.fake

import dev.earshot.core.FormFactor
import dev.earshot.core.HeadphoneCommandException
import dev.earshot.core.NoiseSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeHeadphoneRepositoryTest {

    @Test
    fun `the three variants fill the 0_4_0 fields`() {
        val hp = FakeHeadphoneRepository.connectedState()
        assertEquals("2nd Gen", hp.info?.generation)
        assertEquals(20, hp.autoOffMinutes)
        assertEquals(-10..10, hp.eqRange)
        assertNotNull(hp.battery?.overall?.minutesLeft)

        val buds = FakeHeadphoneRepository.connectedEarbudsState()
        assertEquals(FormFactor.EARBUDS, buds.capabilities.formFactor)
        assertNotNull(buds.battery?.left)
        assertNotNull(buds.battery?.case)
        assertNull(buds.autoOffMinutes)

        val basic = FakeHeadphoneRepository.basicTierState()
        assertFalse(basic.capabilities.listeningModes)
        assertNotNull(basic.capabilities.supportNote)
        assertTrue(basic.modes.isEmpty())
    }

    @Test
    fun `choosing another device connects to that variant`() = runTest {
        val repo = FakeHeadphoneRepository(scope = this, commandDelayMillis = 1, startConnected = true)
        repo.chooseDevice(FakeHeadphoneRepository.EARBUDS_ADDRESS, FakeHeadphoneRepository.EARBUDS_NAME)
        testScheduler.advanceUntilIdle()
        assertTrue(repo.state.value.isConnected)
        assertEquals(FakeHeadphoneRepository.EARBUDS_ADDRESS, repo.state.value.connection.deviceAddress)
        assertEquals(FormFactor.EARBUDS, repo.state.value.capabilities.formFactor)

        repo.chooseDevice(FakeHeadphoneRepository.BASIC_ADDRESS, FakeHeadphoneRepository.BASIC_NAME)
        testScheduler.advanceUntilIdle()
        assertFalse(repo.state.value.capabilities.listeningModes)
    }

    @Test
    fun `auto-off is set where supported and refused elsewhere`() = runTest {
        val repo = FakeHeadphoneRepository(scope = this, commandDelayMillis = 1, startConnected = true)
        repo.setAutoOff(180)
        assertEquals(180, repo.state.value.autoOffMinutes)

        val buds = FakeHeadphoneRepository(scope = this, commandDelayMillis = 1, startConnected = true, connectedTemplate = { FakeHeadphoneRepository.connectedEarbudsState() })
        assertFailsWith<HeadphoneCommandException> { buds.setAutoOff(20) }
    }

    @Test
    fun `disconnecting drops the headphone data but keeps the name`() = runTest {
        val repo = FakeHeadphoneRepository(scope = this, commandDelayMillis = 1, startConnected = true)
        repo.disconnect()
        assertFalse(repo.state.value.isConnected)
        assertNull(repo.state.value.batteryPercent)
        assertEquals(FakeHeadphoneRepository.DEVICE_NAME, repo.state.value.connection.deviceName)
    }

    @Test
    fun `custom slots are whatever the model marks editable`() = runTest {
        val repo = FakeHeadphoneRepository(scope = this, commandDelayMillis = 1, startConnected = true)
        repo.saveCustomMode(5, "Run", NoiseSettings(cancellingLevel = 3))
        assertEquals("Run", repo.state.value.modes.first { it.index == 5 }.name)
        assertFailsWith<HeadphoneCommandException> { repo.saveCustomMode(1, "Nope", NoiseSettings()) }
    }
}
