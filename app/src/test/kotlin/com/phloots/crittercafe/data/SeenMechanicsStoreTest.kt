package com.phloots.crittercafe.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** SeenMechanicsStore against a real Preferences DataStore in a temp file. */
class SeenMechanicsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun store() = SeenMechanicsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "seen.preferences_pb") })

    @Test
    fun marksSeenAndPersists() = runBlocking {
        val store = store()
        assertTrue(store.seen.first().isEmpty())
        store.markSeen("frozen")
        store.markSeen("gate")
        store.markSeen("frozen")
        assertEquals(setOf("frozen", "gate"), store.seen.first())
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        assertEquals(setOf("frozen", "gate"), store().seen.first())
    }

    @Test
    fun resetForgetsEverything() = runBlocking {
        val store = store()
        store.markSeen("tied")
        store.reset()
        assertTrue(store.seen.first().isEmpty())
    }

    @Test
    fun doesNotTouchProgress() = runBlocking {
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "shared.preferences_pb") }
        val progress = ProgressStore(ds)
        progress.recordWin(1, 3)
        SeenMechanicsStore(ds).markSeen("wide")
        SeenMechanicsStore(ds).reset()
        assertEquals(3, progress.progress.first().starsFor(1))
    }
}
