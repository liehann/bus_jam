package com.phloots.busjam.game

import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.model.Vehicle
import com.phloots.busjam.core.model.VehicleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val wins = mutableListOf<Pair<Int, Int>>()
    private lateinit var vm: GameViewModel

    /** Two free cars: 0 (colour 0) exits up, 1 (colour 1) exits right. */
    private val easy = Level(
        id = 1, width = 5, height = 5,
        vehicles = listOf(
            Vehicle(0, VehicleType.CAR, 0, 1, 1, Dir.UP),
            Vehicle(1, VehicleType.CAR, 1, 3, 3, Dir.RIGHT),
        ),
        queue = listOf(0, 0, 0, 0, 1, 1, 1, 1),
    )

    /** Car 0 faces up but car 1 (front (1,1), tail (1,0)) sits on its exit ray. */
    private val blocked = Level(
        id = 2, width = 5, height = 5,
        vehicles = listOf(
            Vehicle(0, VehicleType.CAR, 0, 2, 1, Dir.UP),
            Vehicle(1, VehicleType.CAR, 1, 1, 1, Dir.RIGHT),
        ),
        queue = listOf(1, 1, 1, 1, 0, 0, 0, 0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = GameViewModel({ n, s -> wins += n to s }, dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ui() = vm.ui.value!!

    private fun finishAnimations() {
        while (true) {
            val a = vm.ui.value?.anim ?: return
            vm.onAnimationFinished(a.seq)
        }
    }

    private fun tap(id: Int) {
        vm.onVehicleTapped(id)
        finishAnimations()
    }

    @Test
    fun cleanWinGivesThreeStarsAndRecordsProgress() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        tap(1)
        assertEquals(Status.WON, ui().state.status)
        assertTrue(ui().showResult)
        assertEquals(3, ui().earnedStars)
        assertEquals(listOf(1 to 3), wins)
    }

    @Test
    fun undoCostsOneStar() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        assertTrue(ui().canUndo)
        vm.undo()
        assertEquals(easy.vehicles.size, Snapshot.of(ui().state).onBoard.size)
        assertFalse(ui().canUndo)
        tap(0)
        tap(1)
        assertEquals(Status.WON, ui().state.status)
        assertEquals(2, ui().earnedStars)
        assertEquals(listOf(1 to 2), wins)
    }

    @Test
    fun hintCostsTwoStarsAndHighlightsAVehicle() {
        vm.start(easy, 1, hasNext = true)
        vm.hint()
        assertNotNull(ui().hintVehicleId)
        assertEquals(1, ui().potentialStars)
        tap(ui().hintVehicleId!!)
        assertNull(ui().hintVehicleId)
        tap(if (ui().state.isOnBoard(0)) 0 else 1)
        assertEquals(1, ui().earnedStars)
    }

    @Test
    fun restartClearsUndoStackAndPenalties() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.undo()
        vm.hint()
        assertEquals(1, ui().potentialStars)
        vm.restart()
        assertEquals(3, ui().potentialStars)
        assertFalse(ui().canUndo)
        assertFalse(ui().usedUndo)
        assertFalse(ui().usedHint)
        tap(0)
        tap(1)
        assertEquals(3, ui().earnedStars)
    }

    @Test
    fun tapsDuringAnimationAreQueuedInOrder() {
        vm.start(easy, 1, hasNext = true)
        vm.onVehicleTapped(0)
        vm.onVehicleTapped(1)
        val first = ui().anim!!
        assertTrue(first.events.first() is Event.Exited)
        assertEquals(0, (first.events.first() as Event.Exited).vehicleId)
        vm.onAnimationFinished(first.seq)
        val second = ui().anim!!
        assertEquals(1, (second.events.first() as Event.Exited).vehicleId)
        assertFalse(ui().showResult) // result waits for the animation
        vm.onAnimationFinished(second.seq)
        assertTrue(ui().showResult)
        assertEquals(Status.WON, ui().state.status)
    }

    @Test
    fun bumpAnimatesWithoutChangingStateOrUndo() {
        vm.start(blocked, 2, hasNext = false)
        val before = ui().state
        vm.onVehicleTapped(0)
        val anim = ui().anim!!
        assertTrue(anim.events.single() is Event.Bumped)
        assertEquals(PhaseKind.BUMP, anim.phases.single().kind)
        vm.onAnimationFinished(anim.seq)
        assertEquals(before, ui().state)
        assertFalse(ui().canUndo)
        tap(1)
        tap(0)
        assertEquals(Status.WON, ui().state.status)
        assertEquals(3, ui().earnedStars)
    }

    @Test
    fun boardingPhasesReplayToFinalState() {
        vm.start(easy, 1, hasNext = true)
        vm.onVehicleTapped(0)
        val anim = ui().anim!!
        val kinds = anim.phases.map { it.kind }
        assertEquals(listOf(PhaseKind.EXIT, PhaseKind.BOARD, PhaseKind.DEPART), kinds)
        assertEquals(Snapshot.of(ui().state), anim.phases.last().after)
    }
}
