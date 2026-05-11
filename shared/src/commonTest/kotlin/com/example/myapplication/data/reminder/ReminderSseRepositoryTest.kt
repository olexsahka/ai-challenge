package com.example.myapplication.data.reminder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for parseReminderNotification — JSON-RPC 2.0 notification/message parser.
 * Covers all event types, missing fields, wrong method, and invalid inputs.
 */
class ReminderSseRepositoryTest {

    // ---------------------------------------------------------------------------
    // parseReminderNotification — happy path
    // ---------------------------------------------------------------------------

    @Test
    fun parseReminderNotification_validPriceAlert_returnsEvent() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/message",
              "params": {
                "level": "info",
                "data": {
                  "type": "PRICE_ALERT",
                  "symbol": "BTCUSDT",
                  "price": "65000.00",
                  "message": "BTC price hit your target!",
                  "subscriptionId": "abc-123"
                }
              }
            }
        """.trimIndent()

        val result = parseReminderNotification(json)

        assertNotNull(result)
        assertEquals("PRICE_ALERT", result!!.type)
        assertEquals("BTCUSDT", result.symbol)
        assertEquals("65000.00", result.price)
        assertEquals("BTC price hit your target!", result.message)
        assertEquals("abc-123", result.subscriptionId)
    }

    @Test
    fun parseReminderNotification_periodicType_returnsEvent() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/message",
              "params": {
                "level": "info",
                "data": {
                  "type": "PERIODIC",
                  "symbol": "ETHUSDT",
                  "price": "3200.50",
                  "message": "Periodic ETH update",
                  "subscriptionId": "eth-periodic-1"
                }
              }
            }
        """.trimIndent()

        val result = parseReminderNotification(json)

        assertNotNull(result)
        assertEquals("PERIODIC", result!!.type)
        assertEquals("ETHUSDT", result.symbol)
    }

    @Test
    fun parseReminderNotification_onceType_returnsEvent() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/message",
              "params": {
                "level": "info",
                "data": {
                  "type": "ONCE",
                  "symbol": "USDTUSDT",
                  "price": "1.00",
                  "message": "One-time alert fired",
                  "subscriptionId": "once-999"
                }
              }
            }
        """.trimIndent()

        val result = parseReminderNotification(json)

        assertNotNull(result)
        assertEquals("ONCE", result!!.type)
        assertEquals("once-999", result.subscriptionId)
    }

    // ---------------------------------------------------------------------------
    // parseReminderNotification — missing optional fields
    // ---------------------------------------------------------------------------

    @Test
    fun parseReminderNotification_emptyDataObject_returnsEventWithEmptyStrings() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/message",
              "params": {
                "data": {}
              }
            }
        """.trimIndent()

        val result = parseReminderNotification(json)

        assertNotNull(result)
        assertEquals("", result!!.type)
        assertEquals("", result.symbol)
        assertEquals("", result.price)
        assertEquals("", result.message)
        assertEquals("", result.subscriptionId)
    }

    @Test
    fun parseReminderNotification_missingPrice_returnsEmptyPrice() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/message",
              "params": {
                "data": {
                  "type": "PRICE_ALERT",
                  "symbol": "BTCUSDT",
                  "message": "No price field"
                }
              }
            }
        """.trimIndent()

        val result = parseReminderNotification(json)

        assertNotNull(result)
        assertEquals("", result!!.price)
        assertEquals("BTCUSDT", result.symbol)
    }

    // ---------------------------------------------------------------------------
    // parseReminderNotification — wrong method / non-notification messages
    // ---------------------------------------------------------------------------

    @Test
    fun parseReminderNotification_wrongMethod_returnsNull() {
        val json = """{"jsonrpc":"2.0","method":"tools/list","params":{}}"""
        assertNull(parseReminderNotification(json))
    }

    @Test
    fun parseReminderNotification_endpointEventData_returnsNull() {
        val data = "/messages?sessionId=key:uuid-1234"
        assertNull(parseReminderNotification(data))
    }

    @Test
    fun parseReminderNotification_jsonWithoutMethod_returnsNull() {
        val json = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        assertNull(parseReminderNotification(json))
    }

    @Test
    fun parseReminderNotification_emptyString_returnsNull() {
        assertNull(parseReminderNotification(""))
    }

    @Test
    fun parseReminderNotification_plainPingString_returnsNull() {
        assertNull(parseReminderNotification("ping"))
    }

    @Test
    fun parseReminderNotification_missingDataField_returnsNull() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/message",
              "params": {
                "level": "info"
              }
            }
        """.trimIndent()
        assertNull(parseReminderNotification(json))
    }

    @Test
    fun parseReminderNotification_missingParamsField_returnsNull() {
        val json = """{"jsonrpc":"2.0","method":"notifications/message"}"""
        assertNull(parseReminderNotification(json))
    }

    // ---------------------------------------------------------------------------
    // ReminderEvent data class
    // ---------------------------------------------------------------------------

    @Test
    fun reminderEvent_copyPreservesUnchangedFields() {
        val event = ReminderEvent(
            type = "PRICE_ALERT",
            symbol = "BTCUSDT",
            price = "65000.00",
            message = "BTC hit target",
            subscriptionId = "sub-1"
        )
        val copy = event.copy(price = "66000.00")

        assertEquals("PRICE_ALERT", copy.type)
        assertEquals("66000.00", copy.price)
        assertNotEquals(event, copy)
    }

    @Test
    fun reminderEvent_equalInstancesAreEqual() {
        val a = ReminderEvent("PERIODIC", "ETHUSDT", "3000.00", "msg", "id-1")
        val b = ReminderEvent("PERIODIC", "ETHUSDT", "3000.00", "msg", "id-1")
        assertEquals(a, b)
    }
}
