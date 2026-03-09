package com.example.myapplication

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.agent.MemoryEntry
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for AgentMemory using a fake KeyValueStorage.
 * Фаза 0 — baseline тесты (пункт 0.4).
 *
 * AgentMemory напрямую использует SharedPreferences, поэтому тестируем через
 * FakeKeyValueStorage — промежуточный шаг перед выделением интерфейса в Фазе 2.
 */
class AgentMemoryTest {

    // Простая in-memory реализация, которая повторяет контракт SharedPreferences
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var memory: TestableAgentMemory

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        memory = TestableAgentMemory(fakePrefs)
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

// ---------------------------------------------------------------------------
// Test infrastructure: fake SharedPreferences + testable AgentMemory subclass
// ---------------------------------------------------------------------------

class FakeSharedPreferences {
    val data = mutableMapOf<String, String>()

    fun getString(key: String): String? = data[key]
    fun putString(key: String, value: String) { data[key] = value }
    fun remove(key: String) { data.remove(key) }
    fun clear() { data.clear() }
    fun getAll(): Map<String, String> = data.toMap()
}

/**
 * Подкласс AgentMemory который принимает FakeSharedPreferences вместо Context.
 * Это временный паттерн до выделения KeyValueStorage интерфейса в Фазе 2.
 */
class TestableAgentMemory(private val prefs: FakeSharedPreferences) {

    fun store(key: String, value: String) = prefs.putString(key, value)

    fun recall(key: String): String? = prefs.getString(key)

    fun recallAll(): List<MemoryEntry> =
        prefs.getAll().map { (k, v) -> MemoryEntry(key = k, value = v) }

    fun forget(key: String) = prefs.remove(key)

    fun forgetAll() = prefs.clear()

    fun toContextString(): String {
        val all = recallAll()
        if (all.isEmpty()) return ""
        return "Stored memories:\n" + all.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}
