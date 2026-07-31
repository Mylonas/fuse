package com.mikmy.fuse

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole simulation for FUSE, with no Android types, so it can be tuned and
 * unit tested on the JVM.
 *
 * Verlet integration plus a positional constraint solver — the combination that
 * keeps a tall pile of circles stable instead of jittering or exploding. The
 * constants here were chosen by running bots through a JavaScript port of this
 * exact model; see README.md.
 */
object Tune {
    const val TIERS = 11
    const val R0 = 0.055f          // smallest radius, as a fraction of well width
    const val GROW = 1.16f         // radius ratio between tiers
    const val GRAVITY = 2.41f      // * well width, per second squared
    const val DAMP = 0.985f
    const val ITERS = 8            // constraint iterations per substep
    const val SUBSTEPS = 2

    /**
     * Fuse tolerance as a fraction of the touching distance.
     *
     * MUST be greater than 1. The solver settles resting balls at exactly
     * r1 + r2, so any value below 1 requires real overlap and a settled pile
     * can then never fuse at all — only mid-air collisions would ever merge.
     */
    const val MERGE = 1.04f

    const val MAX_STEP = 0.45f     // max displacement per substep, in radii
    const val SPAWN_TIERS = 5      // only the smallest five can be dropped
    const val DANGER_Y = 0.13f     // * well height
    const val DANGER_TIME = 1.6f   // seconds above the line before the run ends
    const val SAFE_AGE = 1.2f      // grace for a ball that was just dropped
    const val DROP_COOLDOWN = 0.38f
    const val SURGE_EVERY = 18     // merges per surge
    const val SURGE_KICK = 190f    // px/s
    const val MAX_BALLS = 140

    /**
     * The aspect ratio every constant here was tuned against (a well 1.35x as
     * tall as it is wide). A phone well is much taller than that, so orbs are
     * scaled up to keep the number needed to fill the well the same — without
     * this, the balance measured in the simulator simply does not reach the
     * device, and the game plays far too loose.
     */
    const val REF_ASPECT = 1.35f
}

class Ball(
    @JvmField var x: Float,
    @JvmField var y: Float,
    @JvmField val tier: Int,
    @JvmField val r: Float
) {
    @JvmField var px = x
    @JvmField var py = y
    @JvmField var age = 0f
    @JvmField var rest = 0f
    @JvmField var dead = false
    @JvmField var pop = 1f        // birth animation, purely cosmetic

    val vx: Float get() = x - px
    val vy: Float get() = y - py
    val speed: Float get() = hypot(vx, vy)
}

/** A fusion that happened this frame, for the presenter to celebrate. */
class MergeEvent(
    @JvmField val x: Float,
    @JvmField val y: Float,
    @JvmField val tier: Int,
    @JvmField val chain: Int,
    @JvmField val points: Int
)

class Well(@JvmField val w: Float, @JvmField val h: Float, seed: Long = 1L) {

    private var rngState: Long = if (seed == 0L) 1L else seed
    private fun rnd(): Float {
        var s = rngState
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        rngState = s
        return ((s ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
    }

    @JvmField val balls = ArrayList<Ball>(64)
    @JvmField var nextTier = 0
    @JvmField var score = 0
    @JvmField var merges = 0
    @JvmField var drops = 0
    @JvmField var chain = 0
    @JvmField var bestChain = 0
    @JvmField var maxTier = 0
    @JvmField var surgeCharge = 0
    @JvmField var surges = 0
    @JvmField var over = false
    @JvmField var time = 0f
    private var cooldown = 0f

    // ---- one-frame events ----
    @JvmField val evMerges = ArrayList<MergeEvent>(8)
    @JvmField var evDrop = false
    @JvmField var evSurge = false
    @JvmField var evOver = false
    @JvmField var evNewTier = 0

    @JvmField val dangerY = h * Tune.DANGER_Y

    init {
        nextTier = (rnd() * Tune.SPAWN_TIERS).toInt().coerceIn(0, Tune.SPAWN_TIERS - 1)
    }

    /** Keeps "how many orbs fill this well" constant across screen shapes. */
    @JvmField
    val sizeScale: Float = sqrt(h / (Tune.REF_ASPECT * w)).coerceIn(0.8f, 1.6f)

    fun radius(tier: Int): Float {
        var r = Tune.R0 * w * sizeScale
        repeat(tier) { r *= Tune.GROW }
        return r
    }

    /** How close the pile is to the line, 0..1, for the HUD. */
    val pressure: Float
        get() {
            var top = h
            for (b in balls) if (b.y - b.r < top) top = b.y - b.r
            return ((h * 0.55f - top) / (h * 0.55f - dangerY)).coerceIn(0f, 1f)
        }

    val canDrop: Boolean get() = !over && cooldown <= 0f && balls.size < Tune.MAX_BALLS

    fun clearEvents() {
        evMerges.clear()
        evDrop = false
        evSurge = false
        evOver = false
        evNewTier = 0
    }

    /** @return true if the ball was released. */
    fun drop(atX: Float): Boolean {
        if (!canDrop) return false
        val t = nextTier
        val r = radius(t)
        val x = atX.coerceIn(r, w - r)
        balls.add(Ball(x, h * 0.06f, t, r))
        drops++
        chain = 0                 // a cascade belongs to the drop that caused it
        cooldown = Tune.DROP_COOLDOWN
        nextTier = (rnd() * Tune.SPAWN_TIERS).toInt().coerceIn(0, Tune.SPAWN_TIERS - 1)
        evDrop = true
        return true
    }

    fun update(dt: Float) {
        if (over) return
        time += dt
        if (cooldown > 0f) cooldown -= dt

        val sub = dt / Tune.SUBSTEPS
        for (s in 0 until Tune.SUBSTEPS) {
            integrate(sub)
            for (k in 0 until Tune.ITERS) solve()
        }
        fuse()
        for (b in balls) if (b.pop < 1f) b.pop = min(1f, b.pop + dt * 5f)
        checkDanger(dt)
    }

    private fun integrate(dt: Float) {
        val g = Tune.GRAVITY * w * dt * dt
        for (b in balls) {
            b.age += dt
            val vx = (b.x - b.px) * Tune.DAMP
            val vy = (b.y - b.py) * Tune.DAMP
            b.px = b.x
            b.py = b.y
            var sx = vx
            var sy = vy + g
            // A deeply compressed pile can otherwise resolve into a huge
            // displacement and fling a ball through a wall.
            val lim = b.r * Tune.MAX_STEP
            val mag = hypot(sx, sy)
            if (mag > lim) {
                sx *= lim / mag
                sy *= lim / mag
            }
            b.x += sx
            b.y += sy
        }
    }

    private fun solve() {
        val n = balls.size
        for (i in 0 until n) {
            val a = balls[i]
            for (j in i + 1 until n) {
                val b = balls[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d2 = dx * dx + dy * dy
                val rr = a.r + b.r
                if (d2 >= rr * rr || d2 <= 0f) continue
                val d = sqrt(d2)
                val overlap = (rr - d) * 0.5f
                val nx = dx / d
                val ny = dy / d
                // bigger balls are heavier and move less
                val ma = a.r * a.r
                val mb = b.r * b.r
                val tot = ma + mb
                a.x -= nx * overlap * 2f * (mb / tot)
                a.y -= ny * overlap * 2f * (mb / tot)
                b.x += nx * overlap * 2f * (ma / tot)
                b.y += ny * overlap * 2f * (ma / tot)
            }
        }
        for (b in balls) {
            if (b.x < b.r) b.x = b.r
            if (b.x > w - b.r) b.x = w - b.r
            if (b.y > h - b.r) b.y = h - b.r
        }
    }

    private fun fuse() {
        val n = balls.size
        var any = false
        for (i in 0 until n) {
            val a = balls[i]
            if (a.dead) continue
            for (j in i + 1 until n) {
                val b = balls[j]
                if (b.dead || b.tier != a.tier) continue
                val rr = (a.r + b.r) * Tune.MERGE
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (dx * dx + dy * dy > rr * rr) continue

                a.dead = true
                b.dead = true
                val t = a.tier + 1
                val mx = (a.x + b.x) * 0.5f
                val my = (a.y + b.y) * 0.5f
                if (t < Tune.TIERS) {
                    val nb = Ball(mx, my, t, radius(t))
                    // inherit momentum so a fusion feels alive
                    nb.px = nb.x - (a.vx + b.vx) * 0.5f
                    nb.py = nb.y - (a.vy + b.vy) * 0.5f
                    nb.pop = 0f
                    balls.add(nb)
                    if (t > maxTier) {
                        maxTier = t
                        evNewTier = t
                    }
                }
                chain++
                if (chain > bestChain) bestChain = chain
                val points = t * (t + 1) / 2 * 10 * chain
                score += points
                merges++
                surgeCharge++
                evMerges.add(MergeEvent(mx, my, t, chain, points))
                any = true
                break
            }
        }
        if (any) {
            var i = 0
            while (i < balls.size) if (balls[i].dead) balls.removeAt(i) else i++
        }

        if (surgeCharge >= Tune.SURGE_EVERY) {
            surgeCharge = 0
            surges++
            evSurge = true
            // THE SURGE: jolt the pile. Often sets off cascades, and sometimes
            // rescues a board that had nothing left to fuse.
            for (b in balls) {
                b.px = b.x + (rnd() * 2f - 1f) * Tune.SURGE_KICK / 60f
                b.py = b.y + (rnd() * 0.6f) * Tune.SURGE_KICK / 60f
            }
        }
    }

    private fun checkDanger(dt: Float) {
        for (b in balls) {
            // Deliberately no "is it resting" test: a SURGE re-jostles the
            // whole pile, which would reset that timer forever and make the
            // game unloseable.
            if (b.y - b.r < dangerY && b.age > Tune.SAFE_AGE) {
                b.rest += dt
                if (b.rest > Tune.DANGER_TIME) {
                    over = true
                    evOver = true
                    return
                }
            } else {
                b.rest = 0f
            }
        }
    }

    /** How long the most endangered ball has been over the line, 0..1. */
    val alarm: Float
        get() {
            var worst = 0f
            for (b in balls) if (b.rest > worst) worst = b.rest
            return (worst / Tune.DANGER_TIME).coerceIn(0f, 1f)
        }

    /** True if any ball is currently over the line at all. */
    val breaching: Boolean
        get() {
            for (b in balls) if (b.y - b.r < dangerY && b.age > Tune.SAFE_AGE) return true
            return false
        }

    /** Total motion in the well — used by tests to assert the pile settles. */
    val totalSpeed: Float
        get() {
            var s = 0f
            for (b in balls) s += b.speed
            return s
        }

    val allFinite: Boolean
        get() {
            for (b in balls) {
                if (!b.x.isFinite() || !b.y.isFinite()) return false
                if (b.x < -w || b.x > w * 2 || b.y > h * 2 || b.y < -h) return false
            }
            return true
        }

    /** Is anything resting on top of this ball? Used by the tests' player model. */
    fun exposed(b: Ball): Boolean {
        for (o in balls) {
            if (o === b) continue
            if (o.y < b.y - b.r * 0.35f && abs(o.x - b.x) < (o.r + b.r) * 0.85f) return false
        }
        return true
    }
}
