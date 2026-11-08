package com.phloots.crittercafe.game

import com.phloots.crittercafe.core.model.Level
import java.util.zip.CRC32

/** How an attempt at a level ended. */
enum class AttemptEnd { WON, DEADLOCK, STUCK, RESTART, QUIT }

/**
 * What a hint request came to: a move was highlighted ([SHOWN]), the solver found no winning move ([NO_MOVE],
 * [com.phloots.crittercafe.game.NO_WINNING_MOVE]), or the player was asked to wait for the carousel to settle
 * ([WAIT], `HINT_WAIT`). [id] is the event-id part.
 */
enum class HintOutcome(val id: String) { SHOWN("shown"), NO_MOVE("no_move"), WAIT("wait") }

/**
 * One attempt at a level: from start (or restart, or an undo out of a loss) to a win, a loss, a restart or leaving
 * the level. [levelHash] identifies the level's content, so stats stay separable when a level is rebuilt in place;
 * [resumed] marks an attempt that continues a lost one through undo. Counts and time cover only this attempt; time
 * is game time (pauses excluded).
 */
data class Attempt(
    val levelNumber: Int,
    val levelHash: String,
    val resumed: Boolean,
    val taps: Int = 0,
    val undos: Int = 0,
    /** Hints that highlighted a move. */
    val hints: Int = 0,
    /** Hints that came back with no winning move. */
    val hintsNoMove: Int = 0,
    val gameMs: Long = 0,
)

/** Where per-level play data goes (GameAnalytics in the app, nothing in tests). Called on the main thread. */
interface LevelAnalytics {
    fun started(attempt: Attempt) {}
    fun ended(attempt: Attempt, end: AttemptEnd, stars: Int) {}
    fun undo(attempt: Attempt) {}
    /** Every hint request; [attempt] already counts it when [outcome] is [HintOutcome.SHOWN] or [HintOutcome.NO_MOVE]. */
    fun hint(attempt: Attempt, outcome: HintOutcome) {}

    companion object {
        val None = object : LevelAnalytics {}

        /** A stable 8-hex-digit fingerprint of the level's content. */
        fun hash(level: Level): String =
            CRC32().apply { update(level.toString().toByteArray()) }.value.toString(16).padStart(8, '0')
    }
}
