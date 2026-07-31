package com.mikmy.fuse

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * SurfaceView + a dedicated render thread running a plain variable-timestep
 * loop. Touch events arrive on the UI thread, so every mutation of [Game] is
 * guarded by a single lock shared with the render thread.
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    baseline: Boolean = false
) : SurfaceView(context), SurfaceHolder.Callback {

    private val baselineOnly = BuildConfig.DEBUG && baseline

    private val sfx = Sfx()
    private val game = Game(context, sfx)
    private val lock = Any()

    /** ~60 fps. */
    private val FRAME_NS = 16_666_667L

    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        sfx.start()
        startThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) { game.resize(width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
        sfx.stop()
    }

    fun onPauseGame() {
        paused = true
    }

    fun onResumeGame() {
        paused = false
    }

    fun handleBack(): Boolean = synchronized(lock) { game.handleBack() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        synchronized(lock) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> game.onDown(event.x, event.y)
                MotionEvent.ACTION_MOVE -> game.onMove(event.x, event.y)
                MotionEvent.ACTION_UP -> game.onUp(event.x, event.y)
                MotionEvent.ACTION_CANCEL -> game.onUp(event.x, event.y)
            }
        }
        return true
    }

    private fun startThread() {
        if (running) return
        running = true
        thread = Thread {
            var last = System.nanoTime()
            var frames = 0
            var fpsWindow = 0f
            var lockNs = 0L
            var drawNs = 0L
            while (running) {
                if (paused) {
                    try {
                        Thread.sleep(60)
                    } catch (e: InterruptedException) {
                        break
                    }
                    last = System.nanoTime()
                    continue
                }
                val now = System.nanoTime()
                val dt = ((now - last) / 1_000_000_000.0).toFloat()
                last = now

                // GPU-backed canvas (API 26+); every frame repaints the whole
                // surface, so the unpreserved back buffer is fine.
                val t0 = System.nanoTime()
                val canvas = try {
                    holder.lockHardwareCanvas()
                } catch (e: Throwable) {
                    try {
                        holder.lockCanvas()
                    } catch (e2: Throwable) {
                        null
                    }
                }
                if (canvas == null) {
                    try {
                        Thread.sleep(8)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                val t1 = System.nanoTime()
                try {
                    synchronized(lock) {
                        game.update(dt)
                        if (baselineOnly) {
                            canvas.drawColor(android.graphics.Color.BLACK)
                        } else {
                            game.draw(canvas)
                        }
                    }
                } finally {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Throwable) {
                        // surface went away mid-frame
                    }
                }
                val t2 = System.nanoTime()

                // Pace to the target frame rate. Posting the buffer usually
                // blocks on vsync and paces us for free, but when it does not
                // (emulators, or a surface with spare buffers) an unpaced loop
                // burns a whole core and starves everything else on the device.
                val frameNs = t2 - t0
                if (frameNs < FRAME_NS) {
                    val sleepMs = (FRAME_NS - frameNs) / 1_000_000L
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }

                if (BuildConfig.DEBUG) {
                    frames++
                    fpsWindow += dt
                    lockNs += t1 - t0
                    drawNs += t2 - t1
                    if (fpsWindow >= 2f) {
                        Log.d(
                            "Fuse",
                            "fps=%.1f lock=%.1fms draw+post=%.1fms".format(
                                frames / fpsWindow,
                                lockNs / 1e6 / frames,
                                drawNs / 1e6 / frames
                            )
                        )
                        frames = 0; fpsWindow = 0f; lockNs = 0L; drawNs = 0L
                    }
                }
            }
        }.also { it.name = "fuse-render"; it.start() }
    }

    private fun stopThread() {
        running = false
        thread?.let {
            it.interrupt()
            try {
                it.join(800)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }
}
