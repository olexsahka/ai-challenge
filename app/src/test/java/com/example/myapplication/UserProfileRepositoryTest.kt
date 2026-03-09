package com.example.myapplication

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for UserProfileRepository.toContextString() logic.
 * Фаза 0 — baseline тесты (пункт 0.5).
 *
 * UserProfileRepository зависит от Context/SharedPreferences, поэтому
 * тестируем через TestableUserProfileRepository с FakeSharedPreferences.
 */
class UserProfileRepositoryTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var repo: TestableUserProfileRepository

    @Before
    fun setup() {
        prefs = FakeSharedPreferences()
        repo = TestableUserProfileRepository(prefs)
    }

    // --- toggle off ---

    @Test
    fun `toContextString when both toggles off returns blank`() {
        repo.profileEnabled = false
        repo.profileDescription = "Some profile"
        repo.taskEnabled = false
        repo.taskName = "My task"
        repo.taskDescription = "Do something"
        assertTrue(repo.toContextString().isBlank())
    }

    @Test
    fun `toContextString when profile enabled but blank description returns blank`() {
        repo.profileEnabled = true
        repo.profileDescription = "   "
        assertTrue(repo.toContextString().isBlank())
    }

    // --- profile only ---

    @Test
    fun `toContextString when profile enabled returns user profile section`() {
        repo.profileEnabled = true
        repo.profileDescription = "Senior Android developer"
        val result = repo.toContextString()
        assertTrue(result.contains("User profile:"))
        assertTrue(result.contains("Senior Android developer"))
    }

    @Test
    fun `toContextString profile section does not include task when task disabled`() {
        repo.profileEnabled = true
        repo.profileDescription = "Developer"
        repo.taskEnabled = false
        val result = repo.toContextString()
        assertFalse(result.contains("Current task:"))
    }

    // --- task only ---

    @Test
    fun `toContextString when task enabled with name and description includes both`() {
        repo.taskEnabled = true
        repo.taskName = "KMP Migration"
        repo.taskDescription = "Move business logic to shared module"
        val result = repo.toContextString()
        assertTrue(result.contains("Current task:"))
        assertTrue(result.contains("Name: KMP Migration"))
        assertTrue(result.contains("Description: Move business logic to shared module"))
    }

    @Test
    fun `toContextString when task enabled with name only shows name without description line`() {
        repo.taskEnabled = true
        repo.taskName = "My Task"
        repo.taskDescription = ""
        val result = repo.toContextString()
        assertTrue(result.contains("Current task:"))
        assertTrue(result.contains("Name: My Task"))
        assertFalse(result.contains("Description:"))
    }

    @Test
    fun `toContextString when task enabled with description only shows description without name line`() {
        repo.taskEnabled = true
        repo.taskName = ""
        repo.taskDescription = "Do the thing"
        val result = repo.toContextString()
        assertTrue(result.contains("Current task:"))
        assertTrue(result.contains("Description: Do the thing"))
        assertFalse(result.contains("Name:"))
    }

    @Test
    fun `toContextString when task enabled but both name and description blank returns blank`() {
        repo.taskEnabled = true
        repo.taskName = ""
        repo.taskDescription = "  "
        // blank description — but whitespace-only taskDescription fails isNotBlank so no section
        repo.taskDescription = ""
        assertTrue(repo.toContextString().isBlank())
    }

    // --- both enabled ---

    @Test
    fun `toContextString when both enabled both sections appear`() {
        repo.profileEnabled = true
        repo.profileDescription = "Alice"
        repo.taskEnabled = true
        repo.taskName = "Build app"
        repo.taskDescription = "Write tests"
        val result = repo.toContextString()
        assertTrue(result.contains("User profile:"))
        assertTrue(result.contains("Alice"))
        assertTrue(result.contains("Current task:"))
        assertTrue(result.contains("Build app"))
        assertTrue(result.contains("Write tests"))
    }

    @Test
    fun `toContextString trims whitespace from profile description`() {
        repo.profileEnabled = true
        repo.profileDescription = "  Alice  "
        val result = repo.toContextString()
        assertTrue(result.contains("Alice"))
        assertFalse(result.contains("  Alice  "))
    }
}

// ---------------------------------------------------------------------------
// Test infrastructure
// ---------------------------------------------------------------------------

class TestableUserProfileRepository(private val prefs: FakeSharedPreferences) {

    var profileDescription: String
        get() = prefs.getString(KEY_PROFILE_DESCRIPTION) ?: ""
        set(value) = prefs.putString(KEY_PROFILE_DESCRIPTION, value)

    var profileEnabled: Boolean
        get() = prefs.getString(KEY_PROFILE_ENABLED) == "true"
        set(value) = prefs.putString(KEY_PROFILE_ENABLED, value.toString())

    var taskName: String
        get() = prefs.getString(KEY_TASK_NAME) ?: ""
        set(value) = prefs.putString(KEY_TASK_NAME, value)

    var taskDescription: String
        get() = prefs.getString(KEY_TASK_DESCRIPTION) ?: ""
        set(value) = prefs.putString(KEY_TASK_DESCRIPTION, value)

    var taskEnabled: Boolean
        get() = prefs.getString(KEY_TASK_ENABLED) == "true"
        set(value) = prefs.putString(KEY_TASK_ENABLED, value.toString())

    fun toContextString(): String {
        val parts = mutableListOf<String>()
        if (profileEnabled && profileDescription.isNotBlank()) {
            parts.add("User profile:\n${profileDescription.trim()}")
        }
        if (taskEnabled && (taskName.isNotBlank() || taskDescription.isNotBlank())) {
            val sb = StringBuilder("Current task:")
            if (taskName.isNotBlank()) sb.append("\nName: ${taskName.trim()}")
            if (taskDescription.isNotBlank()) sb.append("\nDescription: ${taskDescription.trim()}")
            parts.add(sb.toString())
        }
        return parts.joinToString("\n\n")
    }

    companion object {
        private const val KEY_PROFILE_DESCRIPTION = "profile_description"
        private const val KEY_PROFILE_ENABLED = "profile_enabled"
        private const val KEY_TASK_NAME = "task_name"
        private const val KEY_TASK_DESCRIPTION = "task_description"
        private const val KEY_TASK_ENABLED = "task_enabled"
    }
}
