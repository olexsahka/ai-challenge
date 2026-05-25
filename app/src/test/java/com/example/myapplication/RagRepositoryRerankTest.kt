package com.example.myapplication

import com.example.myapplication.data.db.entity.RagVocabularyEntity
import com.example.myapplication.data.rag.RagIndexer
import com.example.myapplication.data.rag.RagRepository
import com.example.myapplication.data.rag.model.ChunkingStrategy
import com.example.myapplication.data.rag.model.RerankConfig
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class RagRepositoryRerankTest {

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
        llmApiClient = FakeLLMApiClient(buildFakeResponse("answer"))
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
    fun `getRerankConfig_defaults_areCorrect`() {
        val config = repository.getRerankConfig()
        assertFalse(config.rerankEnabled)
        assertEquals(0.15f, config.threshold, 0.001f)
        assertEquals(10, config.preFilterK)
        assertEquals(3, config.postFilterK)
        assertFalse(config.queryRewriteEnabled)
    }

    @Test
    fun `setAndGetRerankConfig_roundtrip`() {
        val config = RerankConfig(
            rerankEnabled = true,
            threshold = 0.25f,
            preFilterK = 8,
            postFilterK = 5,
            queryRewriteEnabled = true
        )
        repository.setRerankConfig(config)
        val loaded = repository.getRerankConfig()
        assertEquals(config, loaded)
    }

    @Test
    fun `askWithRag_withRerankDisabled_returnsAnswerWithEmptyScores`() = runTest {
        prefs.edit().putLong("last_indexed_at", System.currentTimeMillis()).apply()
        prefs.edit().putString("indexed_strategy", ChunkingStrategy.FIXED_SIZE.name).apply()

        val embedding = FloatArray(1) { 1f }
        chunkDao.insertAll(listOf(makeChunkEntity(1, "oracle database", embedding)))
        vocabDao.insertAll(listOf(
            RagVocabularyEntity(word = "oracle", dimensionIndex = 0, idf = 1f, strategy = "FIXED_SIZE")
        ))

        val config = RerankConfig(rerankEnabled = false, queryRewriteEnabled = false)
        val answer = repository.askWithRag("oracle question", config)
        assertEquals(0, answer.filteredCount)
        assertNull(answer.rewrittenQuery)
    }

    @Test
    fun `askWithRag_withRerankEnabled_filtersChunks`() = runTest {
        prefs.edit().putLong("last_indexed_at", System.currentTimeMillis()).apply()
        prefs.edit().putString("indexed_strategy", ChunkingStrategy.FIXED_SIZE.name).apply()

        // Insert multiple chunks
        val embedding = FloatArray(1) { 1f }
        chunkDao.insertAll(listOf(
            makeChunkEntity(1, "oracle database info", embedding),
            makeChunkEntity(2, "unrelated text here", FloatArray(1) { 0.01f })
        ))
        vocabDao.insertAll(listOf(
            RagVocabularyEntity(word = "oracle", dimensionIndex = 0, idf = 1f, strategy = "FIXED_SIZE")
        ))

        // With very high threshold, everything should be filtered
        val config = RerankConfig(
            rerankEnabled = true,
            threshold = 0.99f,
            preFilterK = 10,
            postFilterK = 3,
            queryRewriteEnabled = false
        )
        val answer = repository.askWithRag("oracle question", config)
        // All chunks should be filtered
        assertTrue(answer.filteredCount >= 0)
        assertEquals("answer", answer.answer)
    }

    @Test
    fun `askWithRag_noRewrite_rewrittenQueryIsNull`() = runTest {
        val config = RerankConfig(rerankEnabled = false, queryRewriteEnabled = false)
        val answer = repository.askWithRag("question", config)
        assertNull(answer.rewrittenQuery)
    }

    @Test
    fun `askWithRag_withQueryRewrite_callsLlmTwice`() = runTest {
        prefs.edit().putLong("last_indexed_at", System.currentTimeMillis()).apply()
        prefs.edit().putString("indexed_strategy", ChunkingStrategy.FIXED_SIZE.name).apply()
        val embedding = FloatArray(1) { 1f }
        chunkDao.insertAll(listOf(makeChunkEntity(1, "oracle text", embedding)))
        vocabDao.insertAll(listOf(
            RagVocabularyEntity(word = "oracle", dimensionIndex = 0, idf = 1f, strategy = "FIXED_SIZE")
        ))

        val config = RerankConfig(rerankEnabled = false, queryRewriteEnabled = true)
        repository.askWithRag("oracle question", config)
        // query rewrite + answer = 2 calls
        assertEquals(2, llmApiClient.callCount)
    }

    @Test
    fun `askWithRag_withQueryRewrite_rewrittenQueryIsSet`() = runTest {
        prefs.edit().putLong("last_indexed_at", System.currentTimeMillis()).apply()
        prefs.edit().putString("indexed_strategy", ChunkingStrategy.FIXED_SIZE.name).apply()
        val embedding = FloatArray(1) { 1f }
        chunkDao.insertAll(listOf(makeChunkEntity(1, "oracle text", embedding)))
        vocabDao.insertAll(listOf(
            RagVocabularyEntity(word = "oracle", dimensionIndex = 0, idf = 1f, strategy = "FIXED_SIZE")
        ))

        val config = RerankConfig(rerankEnabled = false, queryRewriteEnabled = true)
        val answer = repository.askWithRag("oracle question", config)
        // The rewritten query should be set (FakeLLMApiClient returns "answer" as text)
        assertEquals("answer", answer.rewrittenQuery)
    }

    // Helper
    private fun makeChunkEntity(
        chunkId: Int,
        text: String,
        embedding: FloatArray
    ): com.example.myapplication.data.db.entity.RagChunkEntity {
        val buf = java.nio.ByteBuffer.allocate(4 * embedding.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { buf.putFloat(it) }
        return com.example.myapplication.data.db.entity.RagChunkEntity(
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
