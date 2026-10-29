package com.phloots.crittercafe.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.phloots.crittercafe.data.SeenMechanics
import com.phloots.crittercafe.game.introsToShow
import kotlinx.coroutines.flow.first

/**
 * The first-time mechanic intro cards still to show for one level, front first. Before the seen set has been read
 * the queue is unknown and play already waits ([blocking]), so the carousel never moves before the first card shows.
 */
@Stable
class MechanicIntroQueue(initial: List<String>?) {
    private var queue by mutableStateOf(initial)
    var total by mutableIntStateOf(initial?.size ?: 0)
        private set

    /** The card on screen, or null. */
    val current: String? get() = queue?.firstOrNull()

    /** 1-based position of [current] among this level's cards. */
    val step: Int get() = total - (queue?.size ?: total) + 1

    /** Play waits (game paused) while true: cards still to show, or not known yet. */
    val blocking: Boolean get() = queue?.isNotEmpty() ?: true

    /** Sets the cards once the seen set is known (ignored if already set). */
    fun load(cards: List<String>) {
        if (queue != null) return
        queue = cards
        total = cards.size
    }

    /** "Got it" on [current]: moves to the next card and returns the dismissed mechanic (to mark it seen). */
    fun dismiss(): String? {
        val q = queue ?: return null
        val head = q.firstOrNull() ?: return null
        queue = q.drop(1)
        return head
    }
}

/**
 * Intro cards for the level [key] (its number) using [mechanics]: the ones not in [seen] yet, snapshotted when the
 * level opens (a restart does not show them again). No mechanics, or no [seen] store, means no cards and no wait.
 */
@Composable
fun rememberMechanicIntros(key: Any, mechanics: Set<String>, seen: SeenMechanics?): MechanicIntroQueue {
    val needsLookup = seen != null && mechanics.isNotEmpty()
    val intros = remember(key, mechanics, seen) { MechanicIntroQueue(if (needsLookup) null else emptyList()) }
    if (needsLookup) {
        LaunchedEffect(intros) { intros.load(introsToShow(mechanics, seen!!.seen.first())) }
    }
    return intros
}
