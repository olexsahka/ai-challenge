package com.example.myapplication

import com.example.myapplication.data.repository.Constraints
import com.example.myapplication.data.repository.ConstraintsRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstraintsRepositoryTest {

    private lateinit var repo: ConstraintsRepository

    @BeforeTest
    fun setup() {
        repo = ConstraintsRepository(FakeKeyValueStorage())
    }

    // --- toContextBlock ---

    @Test
    fun `toContextBlock when disabled returns empty string`() {
        repo.constraints = Constraints(rules = "no code", enabled = false)
        assertTrue(repo.toContextBlock().isBlank())
    }

    @Test
    fun `toContextBlock when enabled but rules blank returns empty string`() {
        repo.constraints = Constraints(rules = "   ", enabled = true)
        assertTrue(repo.toContextBlock().isBlank())
    }

    @Test
    fun `toContextBlock when enabled with rules returns block with header`() {
        repo.constraints = Constraints(rules = "no code generation", enabled = true)
        val result = repo.toContextBlock()
        assertTrue(result.contains("Agent constraints (MUST NEVER violate):"))
        assertTrue(result.contains("no code generation"))
    }

    @Test
    fun `toContextBlock trims whitespace from rules`() {
        repo.constraints = Constraints(rules = "  no jargon  ", enabled = true)
        val result = repo.toContextBlock()
        assertTrue(result.contains("no jargon"))
        assertFalse(result.contains("  no jargon  "))
    }

    @Test
    fun `toContextBlock when disabled ignores non-blank rules`() {
        repo.constraints = Constraints(rules = "reply in Russian only", enabled = false)
        assertEquals("", repo.toContextBlock())
    }

    // --- Constraints data class defaults ---

    @Test
    fun `Constraints default is disabled with empty rules`() {
        val c = Constraints()
        assertFalse(c.enabled)
        assertEquals("", c.rules)
    }

    @Test
    fun `Constraints copy preserves unchanged fields`() {
        val original = Constraints(rules = "no code", enabled = true)
        val copy = original.copy(enabled = false)
        assertEquals("no code", copy.rules)
        assertFalse(copy.enabled)
    }
}
