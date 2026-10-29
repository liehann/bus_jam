package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.model.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RebuildScheduleTest {
    private val rs = recipes4()

    /** The belt dial: 10 early and on gentle levels, longer on hard tiers, never more than 20 (and so never > 20 below 28 seats). */
    @Test
    fun beltDialStaysWithinTheAppLimits() {
        for (slot in plan()) {
            val tier = rs[slot.recipe].tier
            val belt = beltFor(slot, tier)
            assertTrue(belt in Limits.DEFAULT_QUEUE_PREVIEW..20, "L${slot.number}: belt $belt")
            if (slot.ring < 28) assertTrue(belt <= 20, "L${slot.number}: belt $belt on ring ${slot.ring}")
            if (tier == 0) assertEquals(Limits.DEFAULT_QUEUE_PREVIEW, belt, "L${slot.number}: gentle levels keep the default belt")
        }
        // longer belts on harder tiers, from cycle 3 on
        val late = plan().filter { it.number > 20 }
        val byTier = late.groupBy { rs[it.recipe].tier }.mapValues { (t, ss) -> ss.map { beltFor(it, t) }.distinct().single() }
        assertEquals(byTier.keys.sorted(), byTier.keys.sorted().sortedBy { byTier[it] })
        assertEquals(20, byTier[3])
    }

    @Test
    fun rebuildSampleCoversTheWorstLevelsAndEveryCycle() {
        for (n in listOf(72, 25, 43, 67, 80, 10)) assertTrue(n in REBUILD_SLOTS, "L$n")
        assertTrue(REBUILD_SLOTS.size in 16..20)
        assertEquals((1..10).toList(), REBUILD_SLOTS.map { (it - 1) / 10 + 1 }.distinct().sorted())
        assertEquals((0..3).toList(), REBUILD_SLOTS.map { n -> rs[plan().first { it.number == n }.recipe].tier }.distinct().sorted())
    }

    private fun summary(f5: Double, fails: List<String> = emptyList(), solved2M: Boolean = true, clued: Double? = null) =
        F5Summary(if (clued == null) emptyMap() else mapOf("clued" to clued), emptyMap(), 0.0, f5, emptyList(), fails, 1000, solved2M, 40, 10, 2)

    /** Keep or replace (OfficialPackF5): a passing shipped level stays unless the new one is clearly better. */
    @Test
    fun keepOrReplace() {
        val pass = summary(30.0)
        // ties and near ties keep the shipped level
        assertEquals(false, decide(pass, summary(30.0), MAX4, true, CHURN_MARGIN).first)
        assertEquals(false, decide(pass, summary(31.0), MAX4, true, CHURN_MARGIN).first)
        assertEquals(true, decide(pass, summary(33.0), MAX4, true, CHURN_MARGIN).first)
        // on a target recipe closer is better, higher is not
        assertEquals(false, decide(summary(20.0), summary(30.0), 20.0, true, CHURN_MARGIN).first)
        assertEquals(true, decide(summary(25.0), summary(20.5), 20.0, true, CHURN_MARGIN).first)
        // passing the floors beats any score; fewer fails beat more
        val failing = summary(60.0, listOf("unfair 2.0", "order 0.00"))
        assertEquals(true, decide(failing, summary(10.0), MAX4, true, CHURN_MARGIN).first)
        assertEquals(false, decide(pass, failing, MAX4, true, CHURN_MARGIN).first)
        assertEquals(true, decide(failing, summary(10.0, listOf("order 0.00")), MAX4, true, CHURN_MARGIN).first)
        // the same number of fails: only the same floors, by less or with a clearly better score
        assertEquals(false, decide(summary(40.0, listOf("order 0.03")), summary(40.0, listOf("unsolved")), 33.0, true, CHURN_MARGIN).first)
        assertEquals(true, decide(summary(25.6, listOf("fairhidden 0.2")), summary(24.8, listOf("fairhidden 2.4")), 24.0, true, CHURN_MARGIN).first)
        assertEquals(true, decide(summary(64.7, listOf("order 0.00")), summary(68.0, listOf("order 0.00")), MAX4, true, CHURN_MARGIN).first)
        assertEquals(false, decide(summary(64.7, listOf("order 0.00")), summary(65.0, listOf("order 0.00")), MAX4, true, CHURN_MARGIN).first)
        // the new level must win on its line and be solved at 2M nodes
        assertEquals(false, decide(failing, summary(40.0), MAX4, false, CHURN_MARGIN).first)
        assertEquals(false, decide(failing, summary(40.0, solved2M = false), MAX4, true, CHURN_MARGIN).first)
    }

    /**
     * Keep or replace with the clued weight: the score is dist5(fitness5) - w x clued-fork share, as in select5v's
     * ranking, so a candidate that is better once its clued share is counted replaces the shipped level; the floors
     * still come first.
     */
    @Test
    fun keepOrReplaceCountsCluedSqueezes() {
        val w = 15.0
        val old = summary(30.0, clued = 0.30)
        // fitness5 a little lower but many more clued forks: 15 x 0.4 = 6 more than makes up for 2 points
        val cluedNew = summary(28.0, clued = 0.70)
        assertEquals(false, decide(old, cluedNew, MAX4, true, CHURN_MARGIN).first, "without the weight fitness5 alone decides")
        assertEquals(true, decide(old, cluedNew, MAX4, true, CHURN_MARGIN, w).first)
        // the margin applies to the combined score: 31 - 15 x 0.30 vs 30 - 15 x 0.30 is within it
        assertEquals(false, decide(old, summary(31.0, clued = 0.30), MAX4, true, CHURN_MARGIN, w).first)
        // a better fitness5 that loses clued forks can be worse overall
        assertEquals(false, decide(old, summary(33.0, clued = 0.0), MAX4, true, CHURN_MARGIN, w).first)
        // on a target recipe: the distance to the target, less the clued term
        assertEquals(true, decide(summary(20.0, clued = 0.2), summary(21.0, clued = 0.6), 20.0, true, CHURN_MARGIN, w).first)
        // a candidate failing more floors never wins on its clued share; failing the same floors, the score decides
        assertEquals(false, decide(old, summary(40.0, listOf("order 0.00"), clued = 1.0), MAX4, true, CHURN_MARGIN, w).first)
        assertEquals(true, decide(summary(64.7, listOf("order 0.00"), clued = 0.2), summary(64.0, listOf("order 0.00"), clued = 0.6), MAX4, true, CHURN_MARGIN, w).first)
        // a shipped level that fails a floor is still replaced by one that passes, whatever the clued shares
        assertEquals(true, decide(summary(60.0, listOf("unfair 2.0"), clued = 1.0), summary(10.0, clued = 0.0), MAX4, true, CHURN_MARGIN, w).first)
        // a summary without the clued share (an old checkpoint) falls back to fitness5 alone
        assertEquals(false, decide(summary(30.0), cluedNew, MAX4, true, CHURN_MARGIN, w).first)
    }

    /** Provenance written before the rename names the construction `readable`; it is still read. */
    @Test
    fun constructionKeyAcceptsTheOldName() {
        fun meta(vararg kv: Pair<String, String>) = kotlinx.serialization.json.JsonObject(kv.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
        assertEquals("off", constructionOf(null))
        assertEquals("off", constructionOf(meta("recipe" to "x")))
        assertEquals("w3:d10", constructionOf(meta("clued" to "w3:d10")))
        assertEquals("w3:d10", constructionOf(meta("readable" to "w3:d10")))
    }
}
