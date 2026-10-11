package com.phloots.busjam.game.rt

import com.phloots.busjam.game.NO_WINNING_MOVE
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.realtime.RealTimeState
import com.phloots.jam.core.solver.Solver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real-time ViewModel on the real core carousel, driven by a fake (tick-stepped) clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class RealTimeViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val main = UnconfinedTestDispatcher(scheduler)
    private val wins = mutableListOf<Pair<Int, Int>>()
    private lateinit var vm: RealTimeViewModel<RealTimeState>
    private val rules = CoreRealTimeRules()

    /** Two free S containers: 0 (colour 0) exits up, 1 (colour 1) exits right. */
    private val easy = Level(
        id = 1, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 1, 1, Dir.UP),
            Container(1, ContainerSize.S, 1, 3, 3, Dir.RIGHT),
        ),
        queue = listOf(0, 0, 0, 0, 1, 1, 1, 1),
    )

    /** S container 0 at (2,1) faces up; S container 1 at (1,1) sits on its exit ray. */
    private val blocked = Level(
        id = 2, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 2, 1, Dir.UP),
            Container(1, ContainerSize.S, 1, 1, 1, Dir.RIGHT),
        ),
        queue = listOf(1, 1, 1, 1, 0, 0, 0, 0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        vm = RealTimeViewModel({ n, s -> wins += n to s }, rules, main)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ui() = vm.ui.value!!

    /** Patient play: let the carousel run until it settles. */
    private fun settle(maxTicks: Int = 2000) {
        var n = 0
        vm.stepTicks(1)
        while (!ui().view.settled && n++ < maxTicks) vm.stepTicks(1)
        assertTrue("carousel did not settle", ui().view.settled)
    }

    @Test
    fun patientPlayWinsWithThreeStars() {
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        settle()
        assertEquals(Status.PLAYING, ui().status)
        vm.onContainerTapped(1)
        settle()
        assertEquals(Status.WON, ui().status)
        assertTrue(ui().showResult)
        assertEquals(3, ui().earnedStars)
        assertEquals(listOf(1 to 3), wins)
    }

    @Test
    fun theCarouselMovesWithTheClockAndInterpolates() {
        vm.start(easy, 1, hasNext = true)
        val r0 = ui().view.rotation
        vm.advance(rules.tickMs * 2.5)
        assertEquals(2L, ui().view.tick)
        assertEquals(0.5f, ui().tickFraction, 0.01f)
        assertNotEquals(r0, ui().view.rotation)
    }

    @Test
    fun framesMapWallTimeToTicksAndPauseStopsTheClock() {
        vm.start(easy, 1, hasNext = true)
        vm.onFrame(1_000)
        vm.onFrame(1_100) // 100 ms = 4 ticks at 25 ms
        assertEquals(4L, ui().view.tick)
        vm.togglePause()
        vm.onFrame(1_200)
        vm.onFrame(1_300)
        assertEquals(4L, ui().view.tick)
        vm.togglePause()
        vm.onFrame(10_000) // first frame after resuming never jumps
        assertEquals(4L, ui().view.tick)
        vm.onFrame(10_050)
        assertEquals(6L, ui().view.tick)
        vm.setHostPaused(true)
        vm.advance(500.0)
        assertEquals(6L, ui().view.tick)
        vm.setHostPaused(false)
        vm.onFrame(20_000)
        vm.onFrame(25_000) // a hiccup is clamped to 100 ms
        assertEquals(10L, ui().view.tick)
    }

    @Test
    fun undoRestoresTickRingPhaseAndPartialTick() {
        vm.start(easy, 1, hasNext = true)
        vm.advance(rules.tickMs * 7.25)
        val before = ui().view
        val fraction = ui().tickFraction
        vm.onContainerTapped(0)
        vm.stepTicks(60) // boarding in progress / done
        assertNotEquals(before.tick, ui().view.tick)
        vm.undo()
        assertEquals(before, ui().view) // tick, rotation, seats, dock, board
        assertEquals(fraction, ui().tickFraction, 1e-4f)
        assertFalse(ui().canUndo)
        assertEquals(2, ui().potentialStars)
        vm.onContainerTapped(0)
        settle()
        vm.onContainerTapped(1)
        settle()
        assertEquals(listOf(1 to 2), wins)
    }

    @Test
    fun tapsAreAppliedImmediatelyEvenWhileBoarding() {
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        vm.stepTicks(14) // docked, the first element is on its way
        assertFalse(ui().view.settled)
        vm.onContainerTapped(1)
        assertFalse(1 in ui().view.onBoard)
        assertNotNull(ui().view.slots[1])
        settle()
        assertEquals(Status.WON, ui().status)
    }

    @Test
    fun blockedTapBumpsWithoutUndo() {
        vm.start(blocked, 2, hasNext = false)
        val before = ui().view
        vm.onContainerTapped(0)
        assertEquals(1, ui().bumps.size)
        assertEquals(before, ui().view)
        assertFalse(ui().canUndo)
        vm.stepTicks(20)
        assertTrue(ui().bumps.isEmpty())
    }

    @Test
    fun hintWaitsForTheCarouselAndIsFreeUntilShown() {
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        vm.stepTicks(1)
        vm.hint()
        assertEquals(HINT_WAIT, ui().hintMessage)
        assertFalse(ui().usedHint)
        settle()
        assertNull("wait message clears once settled", ui().hintMessage)
        vm.hint()
        assertEquals(1, ui().hintContainerId)
        assertTrue(ui().usedHint)
        assertEquals(1, ui().potentialStars)
    }

    @Test
    fun nullHintKeepsStars() {
        vm = RealTimeViewModel({ n, s -> wins += n to s }, rules, main, findHint = { _, _ -> null })
        vm.start(easy, 1, hasNext = true)
        vm.hint()
        assertEquals(NO_WINNING_MOVE, ui().hintMessage)
        assertEquals(3, ui().potentialStars)
    }

    @Test
    fun undoIsDisabledOnceWon() {
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        settle()
        vm.onContainerTapped(1)
        settle()
        vm.undo()
        assertEquals(Status.WON, ui().status)
        assertEquals(1, wins.size)
    }

    @Test
    fun leavingMidBoardingRecordsNothing() {
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        vm.onContainerTapped(1)
        vm.stepTicks(10)
        vm.commitPendingWin() // not won yet (core only reports WON once settled)
        vm.start(blocked, 2, hasNext = false)
        assertTrue(wins.isEmpty())
    }

    @Test
    fun restartClearsPenaltiesAndRewindsTheRing() {
        vm.start(easy, 1, hasNext = true)
        val initial = ui().view
        vm.onContainerTapped(0)
        vm.stepTicks(30)
        vm.undo()
        vm.restart()
        assertEquals(initial, ui().view)
        assertFalse(ui().usedUndo)
        assertFalse(ui().canUndo)
    }

    @Test
    fun solverLinePlayedPatientlyWins() {
        vm.start(blocked, 2, hasNext = false)
        val line = Solver.solve(blocked)
        assertTrue(line.solvable)
        for (m in line.solution) {
            vm.onContainerTapped(m.containerId)
            settle()
        }
        assertEquals(Status.WON, ui().status)
        assertEquals(listOf(2 to 3), wins)
    }
}
