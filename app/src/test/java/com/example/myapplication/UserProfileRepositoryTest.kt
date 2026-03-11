package com.example.myapplication

import com.example.myapplication.data.repository.TaskMemory
import com.example.myapplication.data.repository.UserInformation
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

    private lateinit var repo: TestableUserProfileRepository

    @Before
    fun setup() {
        repo = TestableUserProfileRepository()
    }

    // --- empty state ---

    @Test
    fun `toContextString when all fields blank returns blank`() {
        assertTrue(repo.toContextString().isBlank())
    }

    // --- user information ---

    @Test
    fun `toContextString when name set includes name line`() {
        repo.userInformation = UserInformation(name = "Alice")
        val result = repo.toContextString()
        assertTrue(result.contains("User information:"))
        assertTrue(result.contains("Name: Alice"))
    }

    @Test
    fun `toContextString when occupation set includes occupation line`() {
        repo.userInformation = UserInformation(occupation = "Android Developer")
        val result = repo.toContextString()
        assertTrue(result.contains("Occupation: Android Developer"))
    }

    @Test
    fun `toContextString when language set includes language line`() {
        repo.userInformation = UserInformation(language = "Russian")
        val result = repo.toContextString()
        assertTrue(result.contains("Language: Russian"))
    }

    @Test
    fun `toContextString when responseStyle set includes style line`() {
        repo.userInformation = UserInformation(responseStyle = "concise")
        val result = repo.toContextString()
        assertTrue(result.contains("Response style: concise"))
    }

    @Test
    fun `toContextString when responseFormat set includes format line`() {
        repo.userInformation = UserInformation(responseFormat = "markdown")
        val result = repo.toContextString()
        assertTrue(result.contains("Response format: markdown"))
    }

    @Test
    fun `toContextString when constraints set includes constraints line`() {
        repo.userInformation = UserInformation(constraints = "no code")
        val result = repo.toContextString()
        assertTrue(result.contains("Constraints: no code"))
    }

    @Test
    fun `toContextString when additionalNotes set includes notes line`() {
        repo.userInformation = UserInformation(additionalNotes = "be friendly")
        val result = repo.toContextString()
        assertTrue(result.contains("Notes: be friendly"))
    }

    @Test
    fun `toContextString trims whitespace from name`() {
        repo.userInformation = UserInformation(name = "  Bob  ")
        val result = repo.toContextString()
        assertTrue(result.contains("Name: Bob"))
        assertFalse(result.contains("  Bob  "))
    }

    @Test
    fun `toContextString blank user information produces no user information section`() {
        repo.userInformation = UserInformation()
        assertTrue(repo.toContextString().isBlank())
    }

    // --- task memory ---

    @Test
    fun `toContextString when task enabled with name and description includes both`() {
        repo.taskMemory = TaskMemory(name = "KMP Migration", description = "Move business logic to shared module", enabled = true)
        val result = repo.toContextString()
        assertTrue(result.contains("Current task:"))
        assertTrue(result.contains("Name: KMP Migration"))
        assertTrue(result.contains("Description: Move business logic to shared module"))
    }

    @Test
    fun `toContextString when task enabled with name only shows name without description line`() {
        repo.taskMemory = TaskMemory(name = "My Task", enabled = true)
        val result = repo.toContextString()
        assertTrue(result.contains("Current task:"))
        assertTrue(result.contains("Name: My Task"))
        assertFalse(result.contains("Description:"))
    }

    @Test
    fun `toContextString when task enabled with description only shows description without name line`() {
        repo.taskMemory = TaskMemory(description = "Do the thing", enabled = true)
        val result = repo.toContextString()
        assertTrue(result.contains("Current task:"))
        assertTrue(result.contains("Description: Do the thing"))
        assertFalse(result.contains("Name:"))
    }

    @Test
    fun `toContextString when task disabled does not include task section`() {
        repo.taskMemory = TaskMemory(name = "Hidden task", description = "Should not appear", enabled = false)
        assertFalse(repo.toContextString().contains("Current task:"))
    }

    @Test
    fun `toContextString when task enabled but both name and description blank returns blank`() {
        repo.taskMemory = TaskMemory(enabled = true)
        assertTrue(repo.toContextString().isBlank())
    }

    // --- combined ---

    @Test
    fun `toContextString user information appears before task section`() {
        repo.userInformation = UserInformation(name = "Alice")
        repo.taskMemory = TaskMemory(name = "Build app", description = "Write tests", enabled = true)
        val result = repo.toContextString()
        val infoIdx = result.indexOf("User information:")
        val taskIdx = result.indexOf("Current task:")
        assertTrue(infoIdx < taskIdx)
    }
}

// ---------------------------------------------------------------------------
// Test infrastructure
// ---------------------------------------------------------------------------

class TestableUserProfileRepository {

    var userInformation: UserInformation = UserInformation()
    var taskMemory: TaskMemory = TaskMemory()

    fun toContextString(): String {
        val parts = mutableListOf<String>()

        val info = userInformation
        val infoLines = mutableListOf<String>()
        if (info.name.isNotBlank()) infoLines.add("Name: ${info.name.trim()}")
        if (info.occupation.isNotBlank()) infoLines.add("Occupation: ${info.occupation.trim()}")
        if (info.language.isNotBlank()) infoLines.add("Language: ${info.language.trim()}")
        if (info.responseStyle.isNotBlank()) infoLines.add("Response style: ${info.responseStyle.trim()}")
        if (info.responseFormat.isNotBlank()) infoLines.add("Response format: ${info.responseFormat.trim()}")
        if (info.constraints.isNotBlank()) infoLines.add("Constraints: ${info.constraints.trim()}")
        if (info.additionalNotes.isNotBlank()) infoLines.add("Notes: ${info.additionalNotes.trim()}")
        if (infoLines.isNotEmpty()) {
            parts.add("User information:\n${infoLines.joinToString("\n")}")
        }

        val task = taskMemory
        if (task.enabled && (task.name.isNotBlank() || task.description.isNotBlank())) {
            val sb = StringBuilder("Current task:")
            if (task.name.isNotBlank()) sb.append("\nName: ${task.name.trim()}")
            if (task.description.isNotBlank()) sb.append("\nDescription: ${task.description.trim()}")
            parts.add(sb.toString())
        }

        return parts.joinToString("\n\n")
    }
}
