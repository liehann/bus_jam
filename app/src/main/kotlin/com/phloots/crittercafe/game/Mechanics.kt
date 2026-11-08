package com.phloots.crittercafe.game

import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Mechanic

/**
 * Level mechanics that get a first-time intro card, as the string ids of core's [Mechanic] (these are what the seen
 * store persists); [ALL] is the order the cards show in when one level brings several new ones.
 */
object Mechanics {
    val FROZEN = Mechanic.FROZEN.id
    val TIED = Mechanic.TIED.id
    val HIDDEN = Mechanic.HIDDEN.id
    val WIDE = Mechanic.WIDE.id
    val COLOUR_BENCH = Mechanic.COLOUR_BENCH.id
    val TURNTABLE = Mechanic.TURNTABLE.id
    val GATE = Mechanic.GATE.id

    val ALL = listOf(FROZEN, TIED, HIDDEN, WIDE, COLOUR_BENCH, TURNTABLE, GATE)
}

/** The mechanics [level] uses (ids from [Mechanics]). */
fun levelMechanics(level: Level): Set<String> = level.mechanics().mapTo(LinkedHashSet()) { it.id }

/** The intro cards to show for a level using [mechanics], given the ones already [seen]; unknown ids are skipped. */
fun introsToShow(mechanics: Set<String>, seen: Set<String>): List<String> =
    Mechanics.ALL.filter { it in mechanics && it !in seen }
