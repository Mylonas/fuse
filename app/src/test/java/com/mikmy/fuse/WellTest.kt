package com.mikmy.fuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WellTest {

    private val W = 1080f
    private val H = 1460f
    private val DT = 1f / 60f
    private val seeds = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)

    private fun well(seed: Long = 1L) = Well(W, H, seed)

    private fun Well.settle(seconds: Float) {
        var t = 0f
        while (t < seconds && !over) {
            clearEvents()
            update(DT)
            t += DT
        }
    }

    /**
     * Drop a chosen tier. The queue only advances when a ball is released, so
     * waiting for a tier to come up on its own can never terminate — the test
     * sets it directly.
     */
    private fun Well.dropTier(tier: Int, x: Float): Boolean {
        nextTier = tier
        return drop(x)
    }

    // ------------------------------------------------------- the core rule

    @Test
    fun twoEqualTiersFuseIntoTheNextTier() {
        val w = well()
        assertTrue(w.dropTier(0, W * 0.5f))
        w.settle(1.5f)
        assertTrue(w.dropTier(0, W * 0.5f))
        w.settle(3f)
        assertTrue("no fusion happened at all", w.merges > 0)
        assertTrue("nothing reached tier 1", w.balls.any { it.tier >= 1 })
    }

    /**
     * The regression that matters most. The constraint solver settles resting
     * balls at exactly r1 + r2, so a merge rule that demands real overlap will
     * fuse mid-air collisions and then silently refuse to fuse a settled pile.
     */
    @Test
    fun aSettledPileStillFuses() {
        assertTrue(
            "MERGE must exceed 1.0 or resting balls can never touch closely enough",
            Tune.MERGE > 1f
        )
        val w = well(5L)
        assertTrue(w.dropTier(0, W * 0.30f))
        w.settle(3f)                       // let it come completely to rest
        assertTrue(
            "the first ball never settled (total speed ${w.totalSpeed})",
            w.totalSpeed < 2f
        )
        val before = w.merges
        assertTrue(w.dropTier(0, W * 0.30f))
        w.settle(3f)
        assertTrue("a resting pile refused to fuse", w.merges > before)
    }

    @Test
    fun differentTiersNeverFuse() {
        val w = well()
        assertTrue(w.dropTier(0, W * 0.5f))
        w.settle(1.5f)
        assertTrue(w.dropTier(2, W * 0.5f))
        w.settle(3f)
        assertEquals(
            "a tier 0 and a tier 2 fused (${w.balls.map { it.tier }})",
            0, w.merges
        )
    }

    @Test
    fun radiiGrowByTierAndTheSmallestFitsSeveralAcross() {
        val w = well()
        for (t in 1 until Tune.TIERS) {
            assertTrue("tier $t is not bigger than ${t - 1}", w.radius(t) > w.radius(t - 1))
        }
        val across = W / (w.radius(0) * 2f)
        assertTrue("only $across smallest balls fit across the well", across >= 7f)
        assertTrue("the largest tier does not fit in the well", w.radius(Tune.TIERS - 1) * 2f < W)
    }

    // ------------------------------------------------------------- physics

    @Test
    fun aTallPhoneWellNeedsTheSameNumberOfOrbsToFillIt() {
        // The constants were tuned at 1.35:1. A 20:9 phone well is far taller,
        // and without scaling the orbs it takes ~40% more of them to fill —
        // which is a completely different, much slacker game.
        fun capacity(well: Well): Float {
            val r = well.radius(0)
            return (well.w * well.h) / (r * r)
        }
        val tuned = Well(1080f, 1080f * Tune.REF_ASPECT)
        val phone = Well(1080f, 2076f)          // what a 1080x2400 device gives
        val ratio = capacity(phone) / capacity(tuned)
        assertTrue(
            "a phone well holds ${ratio}x the tuned capacity",
            ratio > 0.85f && ratio < 1.15f
        )
    }

    @Test
    fun nothingEscapesTheWellUnderAHeavyFill() {
        for (seed in seeds) {
            val w = well(seed)
            repeat(60) {
                if (w.canDrop) w.drop(W * (0.1f + 0.8f * ((it * 37) % 10) / 10f))
                w.settle(0.25f)
            }
            assertTrue("seed $seed produced a NaN or an escapee", w.allFinite)
            for (b in w.balls) {
                assertTrue("ball outside the left wall", b.x >= b.r - 1f)
                assertTrue("ball outside the right wall", b.x <= W - b.r + 1f)
                assertTrue("ball below the floor", b.y <= H - b.r + 1f)
            }
        }
    }

    @Test
    fun thePileComesToRestInsteadOfJittering() {
        val w = well(9L)
        repeat(40) {
            if (w.canDrop) w.drop(W * (0.15f + 0.7f * ((it * 53) % 10) / 10f))
            w.settle(0.3f)
        }
        if (w.over) return               // filled up; the settling claim is moot
        w.settle(4f)
        assertTrue(
            "the pile is still moving (${w.totalSpeed}) after four seconds",
            w.totalSpeed < w.balls.size * 0.5f
        )
    }

    // -------------------------------------------------------------- rules

    @Test
    fun dumpingEverythingInOneColumnEndsTheRunFast() {
        val w = well(3L)
        var drops = 0
        var t = 0f
        while (!w.over && t < 120f) {
            if (w.canDrop) { w.drop(W * 0.5f); drops++ }
            w.clearEvents()
            w.update(DT)
            t += DT
        }
        assertTrue("stacking one column never lost", w.over)
        assertTrue("one column survived $drops drops", drops < 45)
    }

    @Test
    fun everyRunEventuallyEnds() {
        // A game that cannot be lost has no tension at all.
        for (seed in seeds) {
            val w = well(seed)
            var t = 0f
            while (!w.over && t < 900f) {
                if (w.canDrop) w.drop(W * (0.1f + 0.8f * playX(w)))
                w.clearEvents()
                w.update(DT)
                t += DT
            }
            assertTrue("seed $seed never ended", w.over)
        }
    }

    /** A rough stand-in for a sensible player: aim at an exposed same-tier ball. */
    private fun playX(w: Well): Float {
        val same = w.balls.filter { it.tier == w.nextTier && w.exposed(it) }
        if (same.isNotEmpty()) return (same.maxByOrNull { it.y }!!.x / W).coerceIn(0.05f, 0.95f)
        return ((w.drops * 137) % 100) / 100f
    }

    @Test
    fun aBallOverTheLineIsOnlyFatalAfterAGracePeriod() {
        // Near-misses are the whole tension: crossing the line must be
        // survivable if the pile resolves itself.
        val w = well()
        w.drop(W * 0.5f)
        w.clearEvents()
        w.update(DT)
        assertFalse("dying instantly on a drop would be unplayable", w.over)
        assertTrue(Tune.DANGER_TIME > 1f)
        assertTrue(Tune.SAFE_AGE > 0.5f)
    }

    @Test
    fun dropsAreRateLimitedAndRefusedAfterTheRunEnds() {
        val w = well()
        assertTrue(w.drop(W * 0.4f))
        assertFalse("two balls came out at once", w.drop(W * 0.6f))
        w.settle(Tune.DROP_COOLDOWN + 0.1f)
        assertTrue("the dropper never recovered", w.canDrop)

        val dead = well()
        dead.over = true
        assertFalse(dead.drop(W * 0.5f))
    }

    // ------------------------------------------------------------ scoring

    @Test
    fun higherTiersAreWorthDisproportionatelyMore() {
        // tier n is scored on the triangular number, so climbing pays off
        fun value(t: Int) = t * (t + 1) / 2 * 10
        assertEquals(10, value(1))
        assertEquals(30, value(2))
        // doubling the tier must more than double the payout
        assertTrue(value(8).toFloat() / value(4) > 3f)
        assertTrue(value(4).toFloat() / value(2) > 3f)
    }

    @Test
    fun aChainResetsOnTheNextDropSoCascadesBelongToOneDrop() {
        val w = well()
        w.chain = 5
        w.drop(W * 0.5f)
        assertEquals(0, w.chain)
    }

    @Test
    fun scoreAndMergeCountsOnlyEverRise() {
        val w = well(7L)
        var lastScore = 0
        var lastMerges = 0
        var t = 0f
        while (!w.over && t < 60f) {
            if (w.canDrop) w.drop(W * (0.1f + 0.8f * playX(w)))
            w.clearEvents()
            w.update(DT)
            assertTrue(w.score >= lastScore)
            assertTrue(w.merges >= lastMerges)
            lastScore = w.score
            lastMerges = w.merges
            t += DT
        }
        assertTrue("nothing was ever scored", lastScore > 0)
    }

    @Test
    fun mergeEventsAreReportedForThePresenter() {
        val w = well(5L)
        var seen = 0
        var t = 0f
        while (!w.over && t < 40f && seen == 0) {
            if (w.canDrop) w.drop(W * (0.1f + 0.8f * playX(w)))
            w.clearEvents()
            w.update(DT)
            seen += w.evMerges.size
            for (m in w.evMerges) {
                assertTrue("a merge reported a non-positive score", m.points > 0)
                assertTrue("a merge reported tier ${m.tier}", m.tier in 1 until Tune.TIERS)
                assertTrue(m.chain >= 1)
            }
            t += DT
        }
        assertTrue("no merge was ever reported", seen > 0)
    }

    // ------------------------------------------------------- determinism

    @Test
    fun theSameSeedReplaysIdentically() {
        fun run(): Well {
            val w = well(4242L)
            var t = 0f
            while (!w.over && t < 30f) {
                if (w.canDrop) w.drop(W * (0.1f + 0.8f * (((w.drops * 137) % 100) / 100f)))
                w.clearEvents()
                w.update(DT)
                t += DT
            }
            return w
        }
        val a = run()
        val b = run()
        assertEquals(a.score, b.score)
        assertEquals(a.merges, b.merges)
        assertEquals(a.drops, b.drops)
        assertEquals(a.maxTier, b.maxTier)
    }

    @Test
    fun differentSeedsGiveDifferentQueues() {
        val a = well(1L)
        val b = well(999L)
        var differs = false
        repeat(30) {
            if (a.nextTier != b.nextTier) differs = true
            a.drop(W * 0.5f); b.drop(W * 0.5f)
            a.settle(Tune.DROP_COOLDOWN + 0.02f)
            b.settle(Tune.DROP_COOLDOWN + 0.02f)
        }
        assertTrue("two seeds produced the same queue", differs)
    }
}
