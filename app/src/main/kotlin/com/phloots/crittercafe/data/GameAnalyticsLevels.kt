package com.phloots.crittercafe.data

import android.app.Activity
import com.gameanalytics.sdk.GAProgressionStatus
import com.gameanalytics.sdk.GameAnalytics
import com.phloots.crittercafe.BuildConfig
import com.phloots.crittercafe.game.Attempt
import com.phloots.crittercafe.game.AttemptEnd
import com.phloots.crittercafe.game.HintOutcome
import com.phloots.crittercafe.game.LevelAnalytics
import java.util.Locale

/**
 * [LevelAnalytics] over GameAnalytics. An attempt is a progression `campaign:level_007` (Start, then Complete with
 * score = stars, or Fail); undo is a design event `undo:level_007` and every hint request is
 * `hint:shown:level_007`, `hint:no_move:level_007` or `hint:wait:level_007`. Every event carries the attempt's custom
 * fields (level_hash, resumed as 0/1, taps, undos, hints, hints_no_move, seconds; end on Fail) plus build
 * (version-commit[-dirty]) and pack (CRC32 of the shipped levels.json).
 */
object GameAnalyticsLevels : LevelAnalytics {
    private const val P1 = "campaign"
    private var initialized = false

    /** Initialises GameAnalytics once per process; [LevelAnalytics.None] when the build has no keys. */
    fun start(activity: Activity): LevelAnalytics {
        val gameKey = BuildConfig.GAMEANALYTICS_GAME_KEY
        val secretKey = BuildConfig.GAMEANALYTICS_SECRET_KEY
        if (gameKey.isEmpty() || secretKey.isEmpty()) return LevelAnalytics.None
        if (!initialized) {
            GameAnalytics.setEnabledInfoLog(BuildConfig.DEBUG)
            GameAnalytics.configureBuild(BuildConfig.VERSION_NAME)
            GameAnalytics.initialize(activity, gameKey, secretKey)
            initialized = true
        }
        return this
    }

    override fun started(attempt: Attempt) {
        GameAnalytics.addProgressionEvent(GAProgressionStatus.Start, P1, level(attempt), fields(attempt))
    }

    override fun ended(attempt: Attempt, end: AttemptEnd, stars: Int) {
        if (end == AttemptEnd.WON) {
            GameAnalytics.addProgressionEvent(
                GAProgressionStatus.Complete, P1, level(attempt), stars.toDouble(), fields(attempt),
            )
        } else {
            val fields = fields(attempt) + ("end" to end.name.lowercase())
            GameAnalytics.addProgressionEvent(GAProgressionStatus.Fail, P1, level(attempt), fields)
        }
    }

    override fun undo(attempt: Attempt) = design("undo", attempt)

    override fun hint(attempt: Attempt, outcome: HintOutcome) = design("hint:${outcome.id}", attempt)

    /** [action] is one or two id parts; with the level that stays within GameAnalytics' five. */
    private fun design(action: String, attempt: Attempt) {
        GameAnalytics.addDesignEvent("$action:${level(attempt)}", 1.0, fields(attempt))
    }

    private fun level(attempt: Attempt) = String.format(Locale.ROOT, "level_%03d", attempt.levelNumber)

    private fun fields(a: Attempt): Map<String, Any> = mapOf(
        "level_hash" to a.levelHash,
        "resumed" to if (a.resumed) 1 else 0,
        "taps" to a.taps,
        "undos" to a.undos,
        "hints" to a.hints,
        "hints_no_move" to a.hintsNoMove,
        "seconds" to a.gameMs / 1000.0,
        "build" to BuildConfig.BUILD_ID,
        "pack" to BuildConfig.PACK_HASH,
    )
}
