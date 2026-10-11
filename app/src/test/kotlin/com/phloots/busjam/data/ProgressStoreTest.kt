package com.phloots.busjam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** ProgressStore against a real Preferences DataStore in a temp file. */
class ProgressStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: ProgressStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = ProgressStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("progress.preferences_pb").also { it.delete() } },
        )
    }

    @After
    fun tearDown() = scope.cancel()

    private fun progress() = runBlocking { store.progress.first() }

    @Test
    fun freshStoreStartsAtLevelOne() {
        val p = progress()
        assertEquals(1, p.highestUnlocked)
        assertTrue(p.stars.isEmpty())
        assertEquals(1, p.firstUnbeaten(1000))
    }

    @Test
    fun keepsBestStars() = runBlocking {
        store.recordWin(1, 3)
        store.recordWin(1, 1)
        assertEquals(3, progress().starsFor(1))
        store.recordWin(2, 1)
        store.recordWin(2, 2)
        assertEquals(2, progress().starsFor(2))
    }

    @Test
    fun clampsStarsToOneToThree() = runBlocking {
        store.recordWin(1, 7)
        store.recordWin(2, 0)
        assertEquals(3, progress().starsFor(1))
        assertEquals(1, progress().starsFor(2))
    }

    @Test
    fun winUnlocksTheNextLevelAndNeverRelocks() = runBlocking {
        store.recordWin(1, 2)
        assertEquals(2, progress().highestUnlocked)
        store.recordWin(2, 3)
        store.recordWin(3, 3)
        assertEquals(4, progress().highestUnlocked)
        store.recordWin(1, 3) // replaying an old level does not lower the unlock
        assertEquals(4, progress().highestUnlocked)
        assertTrue(progress().isUnlocked(4))
        assertFalse(progress().isUnlocked(5))
    }

    @Test
    fun firstUnbeatenSkipsBeatenLevels() = runBlocking {
        store.recordWin(1, 3)
        store.recordWin(2, 1)
        assertEquals(3, progress().firstUnbeaten(1000))
    }

    @Test
    fun firstUnbeatenFindsAGapBelowTheUnlock() {
        val p = Progress(highestUnlocked = 6, stars = mapOf(1 to 3, 2 to 3, 4 to 1, 5 to 2))
        assertEquals(3, p.firstUnbeaten(1000))
    }

    @Test
    fun firstUnbeatenClampsAtThePackEnd() = runBlocking {
        for (n in 1..5) store.recordWin(n, 3)
        val p = progress()
        assertEquals(6, p.highestUnlocked)
        assertEquals(5, p.firstUnbeaten(5)) // everything beaten: stay on the last level
        assertEquals(1, Progress(highestUnlocked = 50).firstUnbeaten(3))
        assertEquals(3, Progress(highestUnlocked = 50, stars = mapOf(1 to 1, 2 to 1)).firstUnbeaten(3))
        assertEquals(1, Progress().firstUnbeaten(0))
    }

    @Test
    fun persistsAcrossStoreInstances() = runBlocking {
        store.recordWin(1, 2)
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val again = ProgressStore(
            PreferenceDataStoreFactory.create(scope = scope) { java.io.File(tmp.root, "progress.preferences_pb") },
        )
        val p = again.progress.first()
        assertEquals(2, p.starsFor(1))
        assertEquals(2, p.highestUnlocked)
    }
}
