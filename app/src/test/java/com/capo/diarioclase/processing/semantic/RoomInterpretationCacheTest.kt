package com.capo.diarioclase.processing.semantic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.diary.cleanup.RoomTemporaryCleanupStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RoomInterpretationCacheTest {

    private lateinit var context: Context
    private lateinit var database: DiarioDatabase
    private lateinit var cache: RoomInterpretationCache

    private val json = """{"claims":[]}"""
    private val gemini = ProviderModel(InferenceProvider.GEMINI, "gemini-3-flash-preview")
    private val groq = ProviderModel(InferenceProvider.GROQ, "openai/gpt-oss-20b")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries().build()
        cache = RoomInterpretationCache(database.sessions())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun packet(id: String = "packet-1", prompt: String = "free-ele-v1", schema: String = "claims-v1") =
        InterpretationRequest(id, prompt, schema, emptyList())

    @Test
    fun `stores and finds a response for the same packet`() = runTest {
        cache.store("s", packet(), gemini.provider, gemini.modelId, json, 1)
        val hit = cache.find("s", packet(), listOf(gemini))
        assertNotNull(hit)
        assertEquals(InferenceProvider.GEMINI, hit!!.provider)
        assertEquals(json, hit.validatedJson)
    }

    @Test
    fun `a prior gemini hit avoids consulting groq`() = runTest {
        cache.store("s", packet(), gemini.provider, gemini.modelId, json, 1)
        // Aunque el orden incluya Groq después, la respuesta de Gemini se reutiliza.
        val hit = cache.find("s", packet(), listOf(gemini, groq))
        assertEquals(InferenceProvider.GEMINI, hit!!.provider)
    }

    @Test
    fun `missing provider entry is a miss`() = runTest {
        cache.store("s", packet(), gemini.provider, gemini.modelId, json, 1)
        assertNull(cache.find("s", packet(), listOf(groq)))
    }

    @Test
    fun `changing text prompt or schema invalidates the entry`() = runTest {
        cache.store("s", packet(), gemini.provider, gemini.modelId, json, 1)
        assertNull("texto distinto", cache.find("s", packet(id = "packet-2"), listOf(gemini)))
        assertNull("prompt distinto", cache.find("s", packet(prompt = "free-ele-v2"), listOf(gemini)))
        assertNull("esquema distinto", cache.find("s", packet(schema = "claims-v2"), listOf(gemini)))
    }

    @Test
    fun `is scoped by session`() = runTest {
        cache.store("s", packet(), gemini.provider, gemini.modelId, json, 1)
        assertNull(cache.find("otra-sesion", packet(), listOf(gemini)))
    }

    @Test
    fun `cleanup deletes cached responses`() = runTest {
        cache.store("s", packet(), gemini.provider, gemini.modelId, json, 1)
        assertEquals(1, database.sessions().interpretationCacheCount("s"))

        RoomTemporaryCleanupStore(database.sessions()).deleteSessionTemporaryRows(SessionId("s"))

        assertEquals(0, database.sessions().interpretationCacheCount("s"))
        assertEquals(0, database.sessions().temporaryRowCount("s"))
    }

    @Test
    fun `survives reopening the database`() = runTest {
        val name = "cache-${UUID.randomUUID()}.db"
        val persisted = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            RoomInterpretationCache(persisted.sessions())
                .store("s", packet(), gemini.provider, gemini.modelId, json, 1)
            persisted.close()

            val reopened = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
                .allowMainThreadQueries().build()
            try {
                val hit = RoomInterpretationCache(reopened.sessions()).find("s", packet(), listOf(gemini))
                assertNotNull(hit)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
