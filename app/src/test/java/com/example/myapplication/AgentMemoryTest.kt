package com.example.myapplication

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.agent.MemoryEntry
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for AgentMemory using FakeKeyValueStorage.
 * Фаза 0 — baseline тесты (пункт 0.4).
 * После Фазы 2 AgentMemory принимает KeyValueStorage — тесты используют FakeKeyValueStorage напрямую.
 */
class AgentMemoryTest {

    private lateinit var storage: FakeKeyValueStorage
    private lateinit var memory: AgentMemory

    @Before
    fun setup() {
        storage = FakeKeyValueStorage()
        memory = AgentMemory(storage)
    }

    // --- store / recall ---

    @Test
    fun `store and recall returns stored value`() {
        memory.store("name", "Alice")
        assertEquals("Alice", memory.recall("name"))
    }

    @Test
    fun `recall unknown key returns null`() {
        assertNull(memory.recall("nonexistent"))
    }

    @Test
    fun `store overwrites existing value`() {
        memory.store("key", "old")
        memory.store("key", "new")
        assertEquals("new", memory.recall("key"))
    }

    @Test
    fun `store multiple keys returns all via recallAll`() {
        memory.store("a", "1")
        memory.store("b", "2")
        memory.store("c", "3")
        val all = memory.recallAll()
        assertEquals(3, all.size)
        val map = all.associate { it.key to it.value }
        assertEquals("1", map["a"])
        assertEquals("2", map["b"])
        assertEquals("3", map["c"])
    }

    // --- forget ---

    @Test
    fun `forget removes specific key`() {
        memory.store("x", "val")
        memory.forget("x")
        assertNull(memory.recall("x"))
    }

    @Test
    fun `forget does not affect other keys`() {
        memory.store("a", "1")
        memory.store("b", "2")
        memory.forget("a")
        assertEquals("2", memory.recall("b"))
    }

    // --- forgetAll ---

    @Test
    fun `forgetAll clears all entries`() {
        memory.store("a", "1")
        memory.store("b", "2")
        memory.forgetAll()
        assertTrue(memory.recallAll().isEmpty())
    }

    @Test
    fun `forgetAll on empty store does not throw`() {
        memory.forgetAll()
        assertTrue(memory.recallAll().isEmpty())
    }

    // --- toContextString ---

    @Test
    fun `toContextString when empty returns blank`() {
        val result = memory.toContextString()
        assertTrue(result.isBlank())
    }

    @Test
    fun `toContextString with one entry formats correctly`() {
        memory.store("goal", "build KMP app")
        val result = memory.toContextString()
        assertTrue(result.contains("Stored memories:"))
        assertTrue(result.contains("- goal: build KMP app"))
    }

    @Test
    fun `toContextString with multiple entries contains all`() {
        memory.store("name", "Alice")
        memory.store("lang", "Kotlin")
        val result = memory.toContextString()
        assertTrue(result.contains("Stored memories:"))
        assertTrue(result.contains("- name: Alice"))
        assertTrue(result.contains("- lang: Kotlin"))
    }

    @Test
    fun `toContextString after forgetAll returns blank`() {
        memory.store("key", "val")
        memory.forgetAll()
        assertTrue(memory.toContextString().isBlank())
    }
}
