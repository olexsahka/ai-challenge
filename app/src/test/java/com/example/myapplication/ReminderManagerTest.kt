package com.example.myapplication

import android.content.Context
import android.content.SharedPreferences
import com.example.myapplication.data.reminder.ReminderEvent
import com.example.myapplication.data.reminder.ReminderManager
import com.example.myapplication.data.reminder.ReminderSseRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for ReminderManager.
 * Covers isEnabled toggle, connectFlow delegation, and event emission.
 */
class ReminderManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsEditor: SharedPreferences.Editor
    private lateinit var sseRepository: ReminderSseRepository
    private lateinit var httpClient: OkHttpClient
    private lateinit var manager: ReminderManager

    @Before
    fun setUp() {
        prefsEditor = mock()
        whenever(prefsEditor.putBoolean(any(), any())).thenReturn(prefsEditor)

        prefs = mock()
        whenever(prefs.edit()).thenReturn(prefsEditor)
        whenever(prefs.getBoolean(eq("reminder_enabled"), any())).thenReturn(false)

        context = mock()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)

        sseRepository = mock()
        httpClient = mock()
        manager = ReminderManager(context, sseRepository, httpClient)
    }

    // ---------------------------------------------------------------------------
    // isEnabled persistence
    // ---------------------------------------------------------------------------

    @Test
    fun `isEnabled getter returns false by default`() {
        assertFalse(manager.isEnabled)
    }

    @Test
    fun `isEnabled getter returns true when stored as true`() {
        whenever(prefs.getBoolean(eq("reminder_enabled"), any())).thenReturn(true)
        assertTrue(manager.isEnabled)
    }

    @Test
    fun `isEnabled setter persists value to SharedPreferences`() {
        manager.isEnabled = true
        verify(prefsEditor).putBoolean("reminder_enabled", true)
        verify(prefsEditor).apply()
    }

    @Test
    fun `isEnabled setter can set to false`() {
        manager.isEnabled = false
        verify(prefsEditor).putBoolean("reminder_enabled", false)
        verify(prefsEditor).apply()
    }

    // ---------------------------------------------------------------------------
    // connectFlow
    // ---------------------------------------------------------------------------

    @Test
    fun `connectFlow returns empty flow when sseRepository returns empty`() = runTest {
        whenever(sseRepository.connect(any(), any())).thenReturn(flowOf())

        val results = manager.connectFlow().toList()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `connectFlow passes correct base url`() = runTest {
        whenever(sseRepository.connect(eq("http://10.0.2.2:8080"), any())).thenReturn(flowOf())

        manager.connectFlow().toList()

        verify(sseRepository).connect(eq("http://10.0.2.2:8080"), any())
    }

    // ---------------------------------------------------------------------------
    // reminderEvents SharedFlow
    // ---------------------------------------------------------------------------

    @Test
    fun `emitReminder does not throw on valid event`() = runTest {
        val event = ReminderEvent("PERIODIC", "ETHUSDT", "3200.00", "ETH update", "eth-1")
        manager.emitReminder(event)
    }

    @Test
    fun `emitReminder does not throw on multiple events`() = runTest {
        val events = listOf(
            ReminderEvent("PRICE_ALERT", "BTCUSDT", "65000.00", "msg1", "s1"),
            ReminderEvent("PERIODIC",    "ETHUSDT", "3200.00",  "msg2", "s2"),
            ReminderEvent("ONCE",        "BNBUSDT", "400.00",   "msg3", "s3")
        )
        events.forEach { manager.emitReminder(it) }
    }
}
