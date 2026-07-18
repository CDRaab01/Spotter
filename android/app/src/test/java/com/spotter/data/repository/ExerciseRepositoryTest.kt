package com.spotter.data.repository

import com.spotter.data.local.dao.ExerciseDao
import com.spotter.data.local.entity.ExerciseEntity
import com.spotter.data.model.ExerciseOut
import com.spotter.data.remote.ApiService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The exercise-catalog mirror contract: online reads seed Room as a side effect, a connectivity
 * failure (IOException) degrades to the mirror, and an HTTP error keeps erroring — the server
 * answered, so cached rows must not mask it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseRepositoryTest {

    private lateinit var api: ApiService
    private lateinit var dao: FakeExerciseCatalogDao
    private lateinit var repo: ExerciseRepository

    private val catalog = listOf(
        ExerciseOut(id = "bench-id", name = "Bench Press", muscleGroup = "chest", equipment = "barbell"),
        ExerciseOut(id = "squat-id", name = "Squat", muscleGroup = "legs", equipment = "barbell"),
        ExerciseOut(id = "curl-id", name = "Dumbbell Curl", muscleGroup = "arms", equipment = "dumbbell"),
    )

    @Before
    fun setup() {
        api = mock()
        dao = FakeExerciseCatalogDao()
        repo = ExerciseRepository(api, dao)
    }

    @Test
    fun `online search returns server results and seeds the mirror`() = runTest {
        whenever(api.searchExercises(any())).thenReturn(catalog)

        val result = repo.search("press")

        assertEquals(catalog, result)
        assertEquals(3, dao.rows.size, "an online search must seed the mirror as a side effect")
        assertEquals("chest", dao.rows.getValue("bench-id").muscleGroup)
    }

    @Test
    fun `offline search falls back to a LIKE match over the mirror`() = runTest {
        // Seed via one online round, then go offline.
        whenever(api.searchExercises(any())).thenReturn(catalog)
        repo.search("")
        whenever(api.searchExercises(any())).thenAnswer { throw IOException("offline") }

        val result = repo.search("bench")

        assertEquals(listOf("bench-id"), result.map { it.id })
        assertEquals("chest", result.single().muscleGroup)
    }

    @Test
    fun `offline search with a blank query returns the whole mirror`() = runTest {
        whenever(api.searchExercises(any())).thenReturn(catalog)
        repo.search("")
        whenever(api.searchExercises(any())).thenAnswer { throw IOException("offline") }

        val result = repo.search("")

        assertEquals(3, result.size)
    }

    @Test
    fun `offline search on an unseeded mirror degrades to empty`() = runTest {
        whenever(api.searchExercises(any())).thenAnswer { throw IOException("offline") }

        assertTrue(repo.search("bench").isEmpty())
    }

    @Test
    fun `listAll falls back to the full mirror when offline`() = runTest {
        whenever(api.searchExercises(any())).thenReturn(catalog)
        repo.listAll()
        whenever(api.searchExercises(any())).thenAnswer { throw IOException("offline") }

        val result = repo.listAll()

        assertEquals(3, result.size)
        assertEquals(setOf("bench-id", "squat-id", "curl-id"), result.map { it.id }.toSet())
    }

    @Test
    fun `an HTTP error keeps erroring instead of degrading to the mirror`() = runTest {
        whenever(api.searchExercises(any())).thenReturn(catalog)
        repo.search("") // mirror is seeded — the fallback WOULD have data to serve
        whenever(api.searchExercises(any())).thenThrow(
            HttpException(Response.error<Any>(500, "".toResponseBody("application/json".toMediaType())))
        )

        assertFailsWith<HttpException> { repo.search("bench") }
        assertFailsWith<HttpException> { repo.listAll() }
    }

    @Test
    fun `refreshCatalog seeds silently and reports whether the server was reached`() = runTest {
        whenever(api.searchExercises(any())).thenAnswer { throw IOException("offline") }
        assertEquals(false, repo.refreshCatalog())
        assertTrue(dao.rows.isEmpty())

        whenever(api.searchExercises(any())).thenReturn(catalog)
        assertEquals(true, repo.refreshCatalog())
        assertEquals(3, dao.rows.size)
    }
}

/** In-memory [ExerciseDao] with a contains-based emulation of the `%query%` LIKE pattern. */
internal class FakeExerciseCatalogDao : ExerciseDao {
    val rows = linkedMapOf<String, ExerciseEntity>()

    override suspend fun upsertAll(exercises: List<ExerciseEntity>) {
        exercises.forEach { rows[it.id] = it }
    }

    override suspend fun search(pattern: String): List<ExerciseEntity> {
        val needle = pattern.trim('%')
        return rows.values
            .filter { it.name.contains(needle, ignoreCase = true) }
            .sortedBy { it.name }
    }

    override suspend fun getByIds(ids: List<String>): List<ExerciseEntity> =
        ids.mapNotNull { rows[it] }

    override suspend fun getAll(): List<ExerciseEntity> = rows.values.sortedBy { it.name }
}
