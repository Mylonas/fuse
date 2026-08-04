package com.mikmy.fuse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * FUSE
 *
 * Drop orbs into the well. Two of the same tier that touch fuse into the next
 * one, and a fusion can set off the ones underneath it — that cascade is the
 * whole game. The well fills, the line at the top is always waiting, and every
 * run ends with an obvious better move you should have made.
 *
 * [Well] is the simulation; this is presentation, input and celebration.
 */
class Game(ctx: Context, private val sfx: Sfx) {

    private val tierCol = intArrayOf(
        0xFF6EE7FF.toInt(), 0xFF7CF6C0.toInt(), 0xFFC6F24E.toInt(), 0xFFFFD84D.toInt(),
        0xFFFFA23A.toInt(), 0xFFFF6B4A.toInt(), 0xFFFF4E8A.toInt(), 0xFFC86BFF.toInt(),
        0xFF7C6BFF.toInt(), 0xFF4ADEFF.toInt(), 0xFFFFFFFF.toInt()
    )
    private val colBg = 0xFF080A14.toInt()
    private val colWell = 0xFF10131F.toInt()
    private val colDanger = 0xFFFF3B5C.toInt()

    private enum class Phase { TITLE, PLAY, OVER }

    private var phase = Phase.TITLE
    private var well: Well? = null

    private var w = 1f
    private var h = 1f
    private var unit = 1f
    private var headerH = 1f
    private var wellW = 1f
    private var wellH = 1f

    private var clock = 0f
    private var overTimer = 0f
    private var shake = 0f
    private var flash = 0f
    private var surgeFlash = 0f
    private var hitStop = 0f
    private var aimX = 0.5f
    private var newBest = false

    private val prefs = ctx.getSharedPreferences("fuse", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var bestTier = prefs.getInt("bestTier", 0)
    private var runs = prefs.getInt("runs", 0)

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Throwable) {
        null
    }

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, var maxLife: Float, var size: Float, var col: Int
    )

    private class FloatText(
        var x: Float, var y: Float, var text: String, var col: Int,
        var life: Float, var maxLife: Float, var size: Float
    )

    private val parts = ArrayList<Particle>(400)
    private val texts = ArrayList<FloatText>(14)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontBold: Typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    private val fontCond: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun resize(width: Int, height: Int) {
        val changed = width.toFloat() != w || height.toFloat() != h
        w = width.toFloat()
        h = height.toFloat()
        unit = min(w, h)
        headerH = h * 0.135f
        wellW = w
        wellH = h - headerH
        if (changed && phase == Phase.PLAY) startRun()
    }

    // =============================================================== UPDATE

    fun update(rawDt: Float) {
        var dt = rawDt.coerceIn(0f, 0.05f)
        clock += dt
        if (hitStop > 0f) {
            hitStop -= dt
            dt *= 0.3f
        }
        shake *= (1f - min(1f, dt * 9f))
        flash = max(0f, flash - dt * 2.4f)
        surgeFlash = max(0f, surgeFlash - dt * 1.7f)

        when (phase) {
            Phase.PLAY -> updatePlay(dt)
            Phase.OVER -> overTimer += dt
            Phase.TITLE -> {}
        }
        updateParticles(dt)
        updateTexts(dt)
    }

    private fun updatePlay(dt: Float) {
        val well = this.well ?: return
        well.clearEvents()
        well.update(dt)

        for (m in well.evMerges) {
            val sx = m.x
            val sy = m.y + headerH
            val col = tierCol[m.tier.coerceIn(0, tierCol.size - 1)]
            burst(sx, sy, col, 10 + m.tier * 3, unit * (0.25f + m.tier * 0.06f))
            sfx.playFuse(min(11, m.tier), 0.85f)

            if (m.chain > 1) {
                pushText(sx, sy - unit * 0.05f, "CHAIN x${m.chain}", col, 1.0f, unit * 0.05f)
                sfx.play("chain", min(1f, 0.4f + m.chain * 0.15f))
                shake = max(shake, unit * 0.004f * m.chain)
                tap(6L * m.chain)
            } else {
                pushText(sx, sy - unit * 0.04f, "+${m.points}", col, 0.7f, unit * 0.034f)
                tap(8)
            }
            if (m.tier >= 6) {
                shake = max(shake, unit * 0.010f)
                hitStop = max(hitStop, 0.04f)
            }
        }

        if (well.evNewTier > 0) {
            val t = well.evNewTier
            pushText(w / 2f, headerH + wellH * 0.35f, "TIER $t", tierCol[t.coerceIn(0, 10)], 1.6f, unit * 0.08f)
            sfx.play("newtier", 1f)
            flash = 0.5f
            tap(30)
        }
        if (well.evSurge) {
            surgeFlash = 1f
            shake = max(shake, unit * 0.016f)
            pushText(w / 2f, headerH + wellH * 0.22f, "SURGE", 0xFF7CF6C0.toInt(), 1.3f, unit * 0.075f)
            sfx.play("surge", 1f)
            tap(40)
        }
        if (well.evDrop) sfx.play("drop", 0.5f)
        if (well.evOver) gameOver()
    }

    private fun startRun() {
        well = Well(wellW, wellH, System.nanoTime())
        phase = Phase.PLAY
        parts.clear()
        texts.clear()
        aimX = 0.5f
        flash = 0f
        sfx.play("start", 0.9f)
    }

    /** Set by MainActivity so a finished run can offer an interstitial. */
    @JvmField var onRunEnded: (() -> Unit)? = null

    private fun gameOver() {
        val well = this.well ?: return
        phase = Phase.OVER
        overTimer = 0f
        newBest = well.score > best
        runs++
        if (well.score > best) best = well.score
        if (well.maxTier > bestTier) bestTier = well.maxTier
        prefs.edit()
            .putInt("best", best)
            .putInt("bestTier", bestTier)
            .putInt("runs", runs)
            .apply()
        shake = unit * 0.03f
        flash = 1f
        for (b in well.balls) {
            burst(b.x, b.y + headerH, tierCol[b.tier.coerceIn(0, 10)], 6, unit * 0.4f)
        }
        sfx.play("over", 1f)
        tap(70)
        onRunEnded?.invoke()
    }

    // ================================================================ INPUT

    fun onDown(x: Float, y: Float) {
        when (phase) {
            Phase.TITLE -> startRun()
            Phase.PLAY -> aimX = (x / w).coerceIn(0f, 1f)
            Phase.OVER -> {}
        }
    }

    fun onMove(x: Float, y: Float) {
        if (phase == Phase.PLAY) aimX = (x / w).coerceIn(0f, 1f)
    }

    fun onUp(x: Float, y: Float) {
        when (phase) {
            Phase.PLAY -> {
                aimX = (x / w).coerceIn(0f, 1f)
                well?.drop(aimX * wellW)
            }
            Phase.OVER -> if (overTimer > 0.6f) startRun()
            Phase.TITLE -> {}
        }
    }

    fun handleBack(): Boolean = when (phase) {
        Phase.PLAY, Phase.OVER -> {
            phase = Phase.TITLE
            well = null
            true
        }
        Phase.TITLE -> false
    }

    // ------------------------------------------------------------- helpers

    private fun tap(ms: Long) {
        val v = vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Throwable) {
        }
    }

    private fun burst(x: Float, y: Float, col: Int, n: Int, power: Float) {
        if (parts.size > 430) return
        for (i in 0 until n) {
            val a = Random.nextFloat() * 6.2832f
            val sp = power * (0.25f + Random.nextFloat() * 0.9f)
            val life = 0.28f + Random.nextFloat() * 0.45f
            parts.add(
                Particle(
                    x, y, cos(a) * sp, sin(a) * sp, life, life,
                    unit * (0.004f + Random.nextFloat() * 0.007f), col
                )
            )
        }
    }

    private fun pushText(x: Float, y: Float, s: String, col: Int, life: Float, size: Float) {
        if (texts.size > 12) texts.removeAt(0)
        texts.add(FloatText(x, y, s, col, life, life, size))
    }

    private fun updateParticles(dt: Float) {
        var i = 0
        while (i < parts.size) {
            val q = parts[i]
            q.life -= dt
            if (q.life <= 0f) { parts.removeAt(i); continue }
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.vy += unit * 1.2f * dt
            val drag = 1f - min(1f, dt * 1.6f)
            q.vx *= drag
            i++
        }
    }

    private fun updateTexts(dt: Float) {
        var i = 0
        while (i < texts.size) {
            val t = texts[i]
            t.life -= dt
            if (t.life <= 0f) { texts.removeAt(i); continue }
            t.y -= dt * unit * 0.09f
            i++
        }
    }

    // ================================================================= DRAW

    fun draw(c: Canvas) {
        c.drawColor(colBg)
        val saved = c.save()
        if (shake > 0.4f) {
            c.translate(
                (Random.nextFloat() - 0.5f) * shake * 2f,
                (Random.nextFloat() - 0.5f) * shake * 2f
            )
        }

        drawWellBackground(c)
        val well = this.well
        if (well != null) {
            drawDangerLine(c, well)
            drawBalls(c, well)
            if (phase == Phase.PLAY) drawAim(c, well)
        }
        drawParticles(c)
        drawTexts(c)
        c.restoreToCount(saved)

        if (well != null && phase != Phase.TITLE) drawHud(c, well)

        if (surgeFlash > 0.001f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((surgeFlash * 70).toInt().coerceIn(0, 255), 124, 246, 192)
            c.drawRect(0f, 0f, w, h, p)
        }
        if (flash > 0.001f) {
            p.style = Paint.Style.FILL
            p.color = Color.argb((flash * 70).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawRect(0f, 0f, w, h, p)
        }

        when (phase) {
            Phase.TITLE -> drawTitle(c)
            Phase.OVER -> drawGameOver(c)
            else -> {}
        }
    }

    private fun drawWellBackground(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = colWell
        c.drawRect(0f, headerH, w, h, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = unit * 0.006f
        p.color = withAlpha(0xFF3A4470.toInt(), 150)
        c.drawLine(0f, headerH, w, headerH, p)
    }

    private fun drawDangerLine(c: Canvas, well: Well) {
        val y = headerH + well.dangerY
        val alarm = well.alarm
        val pulse = 0.4f + 0.6f * (0.5f + 0.5f * sin(clock * (5f + alarm * 12f)))
        p.style = Paint.Style.STROKE
        p.strokeWidth = unit * (0.003f + alarm * 0.006f)
        p.color = withAlpha(colDanger, (70 + 185 * alarm * pulse).toInt())
        var x = 0f
        val dash = unit * 0.03f
        while (x < w) {
            c.drawLine(x, y, x + dash, y, p)
            x += dash * 2f
        }
        if (well.breaching) {
            p.style = Paint.Style.FILL
            p.color = withAlpha(colDanger, (40 * alarm).toInt())
            c.drawRect(0f, headerH, w, y, p)
        }
    }

    private fun drawBalls(c: Canvas, well: Well) {
        for (b in well.balls) {
            val col = tierCol[b.tier.coerceIn(0, tierCol.size - 1)]
            // pop-in overshoot on a freshly fused orb
            val e = b.pop
            val scale = if (e >= 1f) 1f else 0.55f + 0.65f * e - 0.20f * e * e
            val r = b.r * scale
            val cx = b.x
            val cy = b.y + headerH

            p.style = Paint.Style.FILL
            p.color = withAlpha(col, 45)
            c.drawCircle(cx, cy, r * 1.22f, p)
            p.color = col
            c.drawCircle(cx, cy, r, p)
            p.color = withAlpha(0xFF000000.toInt(), 55)
            c.drawCircle(cx, cy + r * 0.18f, r * 0.82f, p)
            p.color = col
            c.drawCircle(cx, cy, r * 0.78f, p)
            p.color = withAlpha(Color.WHITE, 140)
            c.drawCircle(cx - r * 0.30f, cy - r * 0.34f, r * 0.20f, p)

            if (b.tier >= 8) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = unit * 0.004f
                p.color = withAlpha(Color.WHITE, (90 + 90 * sin(clock * 4f + b.tier)).toInt())
                c.drawCircle(cx, cy, r * 1.08f, p)
            }
        }
    }

    private fun drawAim(c: Canvas, well: Well) {
        val r = well.radius(well.nextTier)
        val x = (aimX * wellW).coerceIn(r, wellW - r)
        val col = tierCol[well.nextTier.coerceIn(0, tierCol.size - 1)]

        // guide line down the well
        p.style = Paint.Style.STROKE
        p.strokeWidth = unit * 0.0035f
        p.color = withAlpha(col, 70)
        var y = headerH + wellH * 0.10f
        val dash = unit * 0.02f
        while (y < h) {
            c.drawLine(x, y, x, y + dash, p)
            y += dash * 2.2f
        }

        val cy = headerH + wellH * 0.055f
        val ready = well.canDrop
        p.style = Paint.Style.FILL
        p.color = withAlpha(col, if (ready) 70 else 30)
        c.drawCircle(x, cy, r * 1.2f, p)
        p.color = withAlpha(col, if (ready) 255 else 110)
        c.drawCircle(x, cy, r, p)
        p.color = withAlpha(Color.WHITE, if (ready) 140 else 60)
        c.drawCircle(x - r * 0.30f, cy - r * 0.34f, r * 0.20f, p)
    }

    private fun drawParticles(c: Canvas) {
        p.style = Paint.Style.FILL
        for (q in parts) {
            val t = (q.life / q.maxLife).coerceIn(0f, 1f)
            p.color = withAlpha(q.col, (255 * t * t).toInt())
            c.drawCircle(q.x, q.y, q.size * (0.4f + t), p)
        }
    }

    private fun drawTexts(c: Canvas) {
        p.style = Paint.Style.FILL
        p.typeface = fontCond
        p.textAlign = Paint.Align.CENTER
        for (t in texts) {
            val k = (t.life / t.maxLife).coerceIn(0f, 1f)
            p.textSize = t.size * (1f + (1f - k) * 0.22f)
            p.color = withAlpha(t.col, (255 * min(1f, k * 2.4f)).toInt())
            c.drawText(t.text, t.x.coerceIn(w * 0.16f, w * 0.84f), t.y, p)
        }
    }

    private fun drawHud(c: Canvas, well: Well) {
        p.style = Paint.Style.FILL
        p.typeface = fontBold
        p.textAlign = Paint.Align.LEFT
        p.textSize = unit * 0.085f
        p.color = Color.WHITE
        c.drawText(well.score.toString(), w * 0.055f, headerH * 0.62f, p)

        p.typeface = fontCond
        p.textSize = unit * 0.028f
        p.color = withAlpha(Color.WHITE, 115)
        c.drawText("BEST $best", w * 0.055f, headerH * 0.85f, p)

        // next-up chip
        val nr = unit * 0.030f
        val nx = w * 0.86f
        val ny = headerH * 0.45f
        p.textAlign = Paint.Align.CENTER
        p.textSize = unit * 0.026f
        p.color = withAlpha(Color.WHITE, 110)
        c.drawText("NEXT", nx, headerH * 0.24f, p)
        val col = tierCol[well.nextTier.coerceIn(0, tierCol.size - 1)]
        p.color = withAlpha(col, 60)
        c.drawCircle(nx, ny, nr * 1.35f, p)
        p.color = col
        c.drawCircle(nx, ny, nr, p)

        // surge charge
        val frac = well.surgeCharge.toFloat() / Tune.SURGE_EVERY
        val bw = w * 0.30f
        val bx = w * 0.44f
        val by = headerH * 0.78f
        p.color = withAlpha(0xFF7CF6C0.toInt(), 45)
        c.drawRect(bx, by, bx + bw, by + unit * 0.008f, p)
        p.color = 0xFF7CF6C0.toInt()
        c.drawRect(bx, by, bx + bw * frac.coerceIn(0f, 1f), by + unit * 0.008f, p)
        p.textAlign = Paint.Align.LEFT
        p.textSize = unit * 0.024f
        p.color = withAlpha(0xFF7CF6C0.toInt(), 170)
        c.drawText("SURGE", bx, by - unit * 0.012f, p)
    }

    private fun drawTitle(c: Canvas) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.typeface = fontBold
        p.textSize = unit * 0.20f
        p.color = 0xFFFFD84D.toInt()
        c.drawText("FUSE", w / 2f, h * 0.26f, p)

        // a row of the tiers, as a legend
        var x = w * 0.14f
        for (t in 0 until 6) {
            val r = unit * (0.018f + t * 0.006f)
            p.color = tierCol[t]
            c.drawCircle(x, h * 0.38f, r, p)
            x += w * 0.145f
        }

        p.typeface = fontCond
        p.textSize = unit * 0.038f
        p.color = withAlpha(Color.WHITE, 155)
        val by = h * 0.74f
        c.drawText("DRAG TO AIM  ·  RELEASE TO DROP", w / 2f, by, p)
        c.drawText("TWO OF A KIND FUSE INTO THE NEXT", w / 2f, by + unit * 0.055f, p)
        c.drawText("DON'T LET THE WELL OVERFLOW", w / 2f, by + unit * 0.11f, p)

        val pulse = 0.55f + 0.45f * sin(clock * 3.2f)
        p.typeface = fontBold
        p.textSize = unit * 0.06f
        p.color = withAlpha(Color.WHITE, (255 * pulse).toInt())
        c.drawText("TAP TO PLAY", w / 2f, h * 0.90f, p)

        if (best > 0) {
            p.typeface = fontCond
            p.textSize = unit * 0.042f
            p.color = withAlpha(0xFFFFD84D.toInt(), 220)
            c.drawText("BEST  $best", w / 2f, h * 0.47f, p)
            p.textSize = unit * 0.030f
            p.color = withAlpha(Color.WHITE, 110)
            c.drawText("BIGGEST TIER $bestTier   ·   RUNS $runs", w / 2f, h * 0.515f, p)
        }
    }

    private fun drawGameOver(c: Canvas) {
        val well = this.well ?: return
        p.style = Paint.Style.FILL
        p.color = Color.argb((min(1f, overTimer * 2.2f) * 190).toInt(), 6, 8, 16)
        c.drawRect(0f, 0f, w, h, p)

        p.textAlign = Paint.Align.CENTER
        p.typeface = fontBold
        p.textSize = unit * 0.09f
        p.color = colDanger
        c.drawText("OVERFLOW", w / 2f, h * 0.30f, p)

        p.textSize = unit * 0.17f
        p.color = Color.WHITE
        c.drawText(well.score.toString(), w / 2f, h * 0.45f, p)

        p.typeface = fontCond
        p.textSize = unit * 0.042f
        if (newBest) {
            val pulse = 0.5f + 0.5f * sin(clock * 6f)
            p.color = withAlpha(0xFFFFD84D.toInt(), (255 * (0.6f + 0.4f * pulse)).toInt())
            c.drawText("NEW BEST!", w / 2f, h * 0.51f, p)
        } else {
            p.color = withAlpha(Color.WHITE, 130)
            c.drawText("BEST  $best", w / 2f, h * 0.51f, p)
        }

        p.textSize = unit * 0.036f
        p.color = withAlpha(Color.WHITE, 125)
        c.drawText("biggest tier ${well.maxTier}  ·  ${well.merges} fusions", w / 2f, h * 0.565f, p)
        c.drawText("best chain x${well.bestChain}  ·  ${well.drops} drops", w / 2f, h * 0.61f, p)

        if (overTimer > 0.6f) {
            val pulse = 0.55f + 0.45f * sin(clock * 3.4f)
            p.typeface = fontBold
            p.textSize = unit * 0.06f
            p.color = withAlpha(Color.WHITE, (255 * pulse).toInt())
            c.drawText("TAP TO PLAY AGAIN", w / 2f, h * 0.82f, p)
        }
    }

    private fun withAlpha(col: Int, a: Int): Int =
        (col and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)
}
