package com.phloots.crittercafe.game.rt

import com.phloots.crittercafe.game.Attempt
import com.phloots.crittercafe.game.AttemptEnd
import com.phloots.crittercafe.game.HintOutcome
import com.phloots.crittercafe.game.LevelAnalytics
import com.phloots.crittercafe.game.NO_WINNING_MOVE
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.realtime.RealTimeState
import com.phloots.crittercafe.core.solver.Solver
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

    /**
     * Five free S containers of colour 4 take the first 20 elements, which fill the 20-seat ring; containers 0..3
     * (colours 0..3) have nothing on the ring, so docking all four deadlocks.
     */
    private val deadlock = Level(
        id = 3, width = 10, height = 5,
        containers = List(9) { Container(it, ContainerSize.S, if (it < 4) it else 4, 1, it, Dir.UP) },
        queue = List(20) { 4 } + (0 until 4).flatMap { c -> List(4) { c } },
        stagingSpots = 20,
    )

    /** Records every analytics call as a readable line. */
    private class RecordingAnalytics : LevelAnalytics {
        val calls = mutableListOf<String>()
        val attempts = mutableListOf<Attempt>()
        override fun started(attempt: Attempt) = log("start", attempt)
        override fun ended(attempt: Attempt, end: AttemptEnd, stars: Int) = log("end $end $stars", attempt)
        override fun undo(attempt: Attempt) = log("undo", attempt)
        override fun hint(attempt: Attempt, outcome: HintOutcome) = log("hint $outcome", attempt)
        private fun log(what: String, attempt: Attempt) {
            calls += what
            attempts += attempt
        }
    }

    private val analytics = RecordingAnalytics()

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
    fun levelStartsWithEverythingQueuedAndTheRingFills() {
        vm.start(easy, 1, hasNext = true)
        assertTrue(ui().view.seats.all { it == null })
        assertEquals(easy.queue.size, ui().view.queueRemaining)
        assertFalse(ui().view.settled)
        vm.hint()
        assertEquals(HINT_WAIT, ui().hintMessage)
        settle()
        assertEquals(easy.queue.size, ui().view.seats.count { it != null })
        assertEquals(0, ui().view.queueRemaining)
        assertEquals(Status.PLAYING, ui().status)
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
        settle() // the ring fills first
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

    // ------------------------------------------------------------------ analytics

    private fun analyticsVm() = RealTimeViewModel({ n, s -> wins += n to s }, rules, main, analytics = analytics)

    @Test
    fun aWinReportsStartThenWonWithStars() {
        vm = analyticsVm()
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        settle()
        vm.onContainerTapped(1)
        settle()
        vm.commitPendingWin()
        vm.start(blocked, 2, hasNext = false) // leaving after a win reports nothing more for level 1
        assertEquals(listOf("start", "end WON 3", "start"), analytics.calls)
        val won = analytics.attempts[1]
        assertEquals(1, won.levelNumber)
        assertEquals(LevelAnalytics.hash(easy), won.levelHash)
        assertFalse(won.resumed)
        assertEquals(2, won.taps)
        assertTrue(won.gameMs > 0)
    }

    @Test
    fun aDeadlockIsReportedOnceAndUndoOpensAResumedAttempt() {
        vm = analyticsVm()
        vm.start(deadlock, 3, hasNext = true)
        settle()
        for (id in 0 until 4) vm.onContainerTapped(id)
        settle()
        assertEquals(Status.DEADLOCK, ui().status)
        vm.stepTicks(50)
        vm.onContainerTapped(4) // ignored once lost
        assertEquals(listOf("start", "end DEADLOCK 0"), analytics.calls)
        assertEquals(4, analytics.attempts[1].taps)

        vm.undo()
        assertEquals(Status.PLAYING, ui().status)
        assertEquals(listOf("start", "end DEADLOCK 0", "start", "undo"), analytics.calls)
        val resumed = analytics.attempts[2]
        assertTrue(resumed.resumed)
        assertEquals(LevelAnalytics.hash(deadlock), resumed.levelHash)
        assertEquals(1, analytics.attempts[3].undos)
        vm.onContainerTapped(3)
        settle()
        assertEquals(Status.DEADLOCK, ui().status)
        assertEquals("end DEADLOCK 0", analytics.calls.last())
        assertEquals(1, analytics.attempts.last().taps)
        assertEquals(1, analytics.attempts.last().undos)
    }

    @Test
    fun restartEndsTheAttemptAndStartsANewOne() {
        vm = analyticsVm()
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        vm.stepTicks(30)
        vm.restart()
        assertEquals(listOf("start", "end RESTART 0", "start"), analytics.calls)
        assertEquals(1, analytics.attempts[1].taps)
        assertFalse(analytics.attempts[2].resumed)
        vm.start(blocked, 2, hasNext = false)
        assertEquals(listOf("start", "end RESTART 0", "start", "end QUIT 0", "start"), analytics.calls)
        assertEquals(0, analytics.attempts[3].taps)
        assertEquals(2, analytics.attempts[4].levelNumber)
    }

    @Test
    fun undosHintsAndBlockedTapsAreCounted() {
        vm = analyticsVm()
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        vm.stepTicks(30)
        vm.undo()
        vm.hint() // the ring is still filling: asked to wait, reported but not counted
        settle()
        vm.hint()
        vm.onContainerTapped(0)
        settle()
        vm.hint()
        vm.onContainerTapped(1)
        settle()
        assertEquals(Status.WON, ui().status)
        assertEquals(
            listOf("start", "undo", "hint WAIT", "hint SHOWN", "hint SHOWN", "end WON 1"),
            analytics.calls,
        )
        assertEquals(0, analytics.attempts[2].hints) // the wait
        val won = analytics.attempts.last()
        assertEquals(3, won.taps) // an undone tap still counts
        assertEquals(1, won.undos)
        assertEquals(2, won.hints)
        assertEquals(0, won.hintsNoMove)
        assertEquals(1, analytics.attempts[3].hints)
    }

    @Test
    fun aHintWithNoWinningMoveIsReportedAndCountedSeparately() {
        vm = RealTimeViewModel({ n, s -> wins += n to s }, rules, main, findHint = { _, _ -> null }, analytics = analytics)
        vm.start(easy, 1, hasNext = true)
        settle()
        vm.hint()
        assertEquals(NO_WINNING_MOVE, ui().hintMessage)
        vm.hint()
        assertEquals(listOf("start", "hint NO_MOVE", "hint NO_MOVE"), analytics.calls)
        assertEquals(1, analytics.attempts[1].hintsNoMove)
        assertEquals(2, analytics.attempts[2].hintsNoMove)
        assertEquals(0, analytics.attempts[2].hints)
        vm.restart()
        assertEquals("end RESTART 0", analytics.calls[3])
        assertEquals(2, analytics.attempts[3].hintsNoMove)
        assertEquals(0, analytics.attempts.last().hintsNoMove) // the new attempt starts clean
    }

    @Test
    fun restartAfterALossStartsANewAttemptWithoutAnotherEnd() {
        vm = analyticsVm()
        vm.start(deadlock, 3, hasNext = true)
        settle()
        for (id in 0 until 4) vm.onContainerTapped(id)
        settle()
        assertEquals(Status.DEADLOCK, ui().status)
        vm.restart()
        assertEquals(listOf("start", "end DEADLOCK 0", "start"), analytics.calls)
        val next = analytics.attempts.last()
        assertFalse(next.resumed)
        assertEquals(0, next.taps)
        assertEquals(3, next.levelNumber)
    }

    @Test
    fun blockedTapsAreNotCounted() {
        vm = analyticsVm()
        vm.start(blocked, 2, hasNext = false)
        vm.onContainerTapped(0) // bumps into container 1
        vm.restart()
        assertEquals(0, analytics.attempts[1].taps)
    }

    /** Frozen S 0 (one release), free S 1, S 2 tied to 1; all exit up. */
    private val mechanics = Level(
        id = 4, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 1, 0, Dir.UP, frozen = 1),
            Container(1, ContainerSize.S, 1, 1, 2, Dir.UP),
            Container(2, ContainerSize.S, 2, 1, 4, Dir.UP, tiedTo = 1),
        ),
        queue = List(4) { 1 } + List(4) { 0 } + List(4) { 2 },
    )

    @Test
    fun frozenAndTiedTapsNudgeTheTicketInPlace() {
        vm.start(mechanics, 1, hasNext = true)
        settle()
        for (id in listOf(0, 2)) {
            val before = ui().bumpSignal
            vm.onContainerTapped(id)
            val bump = ui().bumps.last()
            assertEquals(id, bump.event.containerId)
            assertTrue(bump.isNudge)
            assertEquals(before + 1, ui().bumpSignal)
            assertEquals(0, ui().dockFullSignal)
            assertFalse(ui().canUndo) // nothing changed
        }
        // one release thaws 0 and unties 2
        vm.onContainerTapped(1)
        assertEquals(1, ui().view.releases)
        assertEquals(0, ui().view.frozenRemaining(0))
        assertFalse(ui().view.isTieLocked(2))
    }

    @Test
    fun aTapWithNoFittingSlotShakesTheDock() {
        vm.start(mechanics.copy(benchColors = List(4) { 1 }, containers = mechanics.containers.map { it.copy(frozen = 0) }), 1, hasNext = true)
        settle()
        vm.onContainerTapped(0) // colour 0: every bench takes colour 1 only
        assertEquals(1, ui().dockFullSignal)
        assertEquals(0, ui().bumpSignal)
        assertTrue(ui().bumps.isEmpty())
    }
}
