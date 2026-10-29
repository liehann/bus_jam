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

    private fun summary(f5: Double, fails: List<String> = emptyList(), solved2M: Boolean = true) =
        F5Summary(emptyMap(), emptyMap(), 0.0, f5, emptyList(), fails, 1000, solved2M, 40, 10, 2)

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
}
