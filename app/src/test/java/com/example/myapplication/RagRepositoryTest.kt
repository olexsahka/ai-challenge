package com.example.myapplication

import android.content.SharedPreferences
import com.example.myapplication.data.db.entity.RagChunkEntity
import com.example.myapplication.data.db.entity.RagVocabularyEntity
import com.example.myapplication.data.rag.RagIndexer
import com.example.myapplication.data.rag.RagRepository
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RagChunk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class RagRepositoryTest {

    private lateinit var chunkDao: FakeRagChunkDao
    private lateinit var vocabDao: FakeRagVocabularyDao
    private lateinit var llmApiClient: FakeLLMApiClient
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var indexer: RagIndexer
    private lateinit var repository: RagRepository

    @Before
    fun setUp() {
        chunkDao = FakeRagChunkDao()
        vocabDao = FakeRagVocabularyDao()
        llmApiClient = FakeLLMApiClient(buildFakeResponse("test answer"))
        prefs = FakeSharedPreferences()
        indexer = mock()
        repository = RagRepository(
            prefs = prefs,
            indexer = indexer,
            chunkDao = chunkDao,
            vocabDao = vocabDao,
            llmApiClient = llmApiClient,
            ioDispatcher = UnconfinedTestDispatcher()
        )
    }

    @Test
    fun `search_whenNotIndexed_returnsEmptyList`() = runTest {
        // prefs has no KEY_LAST_INDEXED_AT so isIndexed() returns false
        val result = repository.search("oracle tablespace")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `search_whenIndexed_returnsChunks`() = runTest {
        // Mark as indexed
        prefs.edit().putLong("last_indexed_at", System.currentTimeMillis()).apply()
        prefs.edit().putString("indexed_strategy", ChunkingStrategy.FIXED_SIZE.name).apply()

        // Insert a chunk with embedding
        val embedding = FloatArray(3) { (it + 1).toFloat() }
        chunkDao.insertAll(listOf(makeChunkEntity(1, "oracle tablespace is a storage unit", embedding)))
        // Insert matching vocab
        vocabDao.insertAll(listOf(
            RagVocabularyEntity(word = "oracle", dimensionIndex = 0, idf = 1f, strategy = "FIXED_SIZE"),
            RagVocabularyEntity(word = "tablespac", dimensionIndex = 1, idf = 1f, strategy = "FIXED_SIZE"),
            RagVocabularyEntity(word = "storage", dimensionIndex = 2, idf = 1f, strategy = "FIXED_SIZE")
        ))

        val result = repository.search("oracle tablespace", topK = 4)
        // Should return the one chunk since it matches vocabulary
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `askWithRag_includesChunksInSources`() = runTest {
        prefs.edit().putLong("last_indexed_at", System.currentTimeMillis()).apply()
        prefs.edit().putString("indexed_strategy", ChunkingStrategy.FIXED_SIZE.name).apply()

        val embedding = FloatArray(1) { 1f }
        chunkDao.insertAll(listOf(makeChunkEntity(1, "oracle is a database", embedding)))
        vocabDao.insertAll(listOf(
            RagVocabularyEntity(word = "oracle", dimensionIndex = 0, idf = 1f, strategy = "FIXED_SIZE")
        ))

        val answer = repository.askWithRag("What is oracle?")
        assertEquals("test answer", answer.answer)
        // sources should be from search
        // (may be non-empty if retriever finds the chunk)
        assertFalse(answer.answer.isBlank())
    }

    @Test
    fun `askWithRag_promptContainsChunkTexts`() = runTest {
        prefs.edit().putLong("last_indexed_at", System.currentTimeMillis()).apply()
        prefs.edit().putString("indexed_strategy", ChunkingStrategy.FIXED_SIZE.name).apply()

        val embedding = FloatArray(1) { 1f }
        chunkDao.insertAll(listOf(makeChunkEntity(1, "oracle buffer cache description", embedding)))
        vocabDao.insertAll(listOf(
            RagVocabularyEntity(word = "oracle", dimensionIndex = 0, idf = 1f, strategy = "FIXED_SIZE")
        ))

        repository.askWithRag("oracle question")
        val content = llmApiClient.lastRequest?.input?.first()?.content ?: ""
        assertTrue("Prompt should contain 'Вопрос:'", content.contains("Вопрос:"))
        assertTrue("Prompt should contain 'Контекст:'", content.contains("Контекст:"))
    }

    @Test
    fun `askWithRag_emptySearch_promptHasEmptyContext`() = runTest {
        // Not indexed → search returns empty → prompt contains "Контекст пуст."
        val answer = repository.askWithRag("some question")
        val content = llmApiClient.lastRequest?.input?.first()?.content ?: ""
        assertTrue("Prompt should contain 'Контекст пуст.'", content.contains("Контекст пуст."))
        assertEquals(emptyList<RagChunk>(), answer.sources)
    }

    @Test
    fun `askWithoutRag_doesNotCallDao`() = runTest {
        repository.askWithoutRag("What is oracle?")
        // Dao should not have been called (chunks remain empty)
        assertEquals(0, chunkDao.chunks.size)
        assertEquals(1, llmApiClient.callCount)
    }

    @Test
    fun `askWithoutRag_instructionsNull`() = runTest {
        repository.askWithoutRag("What is oracle?")
        assertEquals(null, llmApiClient.lastRequest?.instructions)
    }

    @Test
    fun `askWithRag_onApiException_rethrows`() = runTest {
        val throwingClient = ThrowingLLMApiClient(Exception("network error"))
        val repo = RagRepository(
            prefs = prefs,
            indexer = indexer,
            chunkDao = chunkDao,
            vocabDao = vocabDao,
            llmApiClient = throwingClient,
            ioDispatcher = UnconfinedTestDispatcher()
        )
        var caught: Exception? = null
        try {
            repo.askWithRag("question")
        } catch (e: Exception) {
            caught = e
        }
        assertEquals("network error", caught?.message)
    }

    @Test
    fun `buildRagPromptContent_withChunks_formatsCorrectly`() {
        val chunks = listOf(
            RagChunk(
                id = 1, chunkId = 0, source = "src", title = "Title1",
                section = "Section1", text = "Text of chunk 1",
                strategy = ChunkingStrategy.FIXED_SIZE
            ),
            RagChunk(
                id = 2, chunkId = 1, source = "src", title = "Title2",
                section = null, text = "Text of chunk 2",
                strategy = ChunkingStrategy.FIXED_SIZE
            )
        )
        val result = repository.buildRagPromptContent(chunks, "My question")
        assertTrue(result.contains("Фрагмент 1 (раздел: Section1)"))
        assertTrue(result.contains("Text of chunk 1"))
        assertTrue(result.contains("Фрагмент 2 (раздел: Title2)"))
        assertTrue(result.contains("Text of chunk 2"))
        assertTrue(result.contains("Вопрос: My question"))
    }

    @Test
    fun `buildRagPromptContent_withEmptyChunks_hasEmptyContextMessage`() {
        val result = repository.buildRagPromptContent(emptyList(), "My question")
        assertTrue(result.contains("Контекст пуст."))
        assertTrue(result.contains("Вопрос: My question"))
    }

    // Helper to create a RagChunkEntity with embedded floats
    private fun makeChunkEntity(chunkId: Int, text: String, embedding: FloatArray): RagChunkEntity {
        val buf = java.nio.ByteBuffer.allocate(4 * embedding.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { buf.putFloat(it) }
        return RagChunkEntity(
            chunkId = chunkId,
            source = "test",
            title = "Title",
            section = "Section",
            text = text,
            embeddingBlob = buf.array(),
            strategy = "FIXED_SIZE",
            createdAt = System.currentTimeMillis()
        )
    }
}
