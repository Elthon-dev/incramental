package com.elthon.infinite.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.elthon.infinite.core.CombatPhase
import com.elthon.infinite.platform.AudioEngine
import com.elthon.infinite.platform.SaveStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class GameView(context: Context, store: SaveStore, audio: AudioEngine) : SurfaceView(context), SurfaceHolder.Callback {
    private val queue = ConcurrentLinkedQueue<PointerEvent>()
    private val session = GameSession(store, audio)
    private val renderer = Renderer(session)
    private val storeRef = store
    private val eventLock = Any()
    private var thread: GameThread? = null
    @Volatile private var resumed = false
    @Volatile private var suspendLatch: CountDownLatch? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    fun onResumeActivity() {
        resumed = true
        queue.offer(PointerEvent(PointerEvent.Kind.RESUME, -1, 0f, 0f))
    }

    fun onPauseActivity() {
        resumed = false
        val latch = CountDownLatch(1)
        suspendLatch = latch
        queue.offer(PointerEvent(PointerEvent.Kind.SUSPEND, -1, 0f, 0f))
        latch.await(1_500L, TimeUnit.MILLISECONDS)
        suspendLatch = null
    }

    fun backPressed(): Boolean = synchronized(eventLock) { session.onBackPressed() }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (thread == null) {
            thread = GameThread(holder).also { it.start() }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val current = thread
        queue.offer(PointerEvent(PointerEvent.Kind.SHUTDOWN, -1, 0f, 0f))
        current?.join(4_000L)
        thread = null
        storeRef.shutdown()
    }

    fun release() {
        session.release()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val width = width.toFloat().coerceAtLeast(1f)
        val height = height.toFloat().coerceAtLeast(1f)
        val scale = minOf(width / Ui.WIDTH, height / Ui.HEIGHT)
        val offsetX = (width - Ui.WIDTH * scale) / 2f
        val offsetY = (height - Ui.HEIGHT * scale) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                queue.offer(PointerEvent(PointerEvent.Kind.DOWN, event.getPointerId(index), virtualX(event.getX(index), offsetX, scale), virtualY(event.getY(index), offsetY, scale)))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    queue.offer(PointerEvent(PointerEvent.Kind.MOVE, event.getPointerId(index), virtualX(event.getX(index), offsetX, scale), virtualY(event.getY(index), offsetY, scale)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                queue.offer(PointerEvent(PointerEvent.Kind.UP, event.getPointerId(index), virtualX(event.getX(index), offsetX, scale), virtualY(event.getY(index), offsetY, scale)))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (index in 0 until event.pointerCount) {
                    queue.offer(PointerEvent(PointerEvent.Kind.CANCEL, event.getPointerId(index), 0f, 0f))
                }
            }
        }
        return true
    }

    private fun virtualX(raw: Float, offset: Float, scale: Float): Float = if (scale > 0f) (raw - offset) / scale else 0f
    private fun virtualY(raw: Float, offset: Float, scale: Float): Float = if (scale > 0f) (raw - offset) / scale else 0f

    private class PointerEvent(val kind: Kind, val id: Int, val x: Float, val y: Float) {
        enum class Kind { DOWN, MOVE, UP, CANCEL, SUSPEND, RESUME, SHUTDOWN }
    }

    private inner class GameThread(private val surfaceHolder: SurfaceHolder) : Thread("infinite-loop") {
        private var running = true
        private var lastNanos = System.nanoTime()
        private var lastHaptic = 0L
        private var scrollPointer = -1
        private var scrollLastY = 0f

        override fun run() {
            while (running) {
                val now = System.nanoTime()
                val delta = ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                lastNanos = now
                synchronized(eventLock) {
                    processEvents()
                    session.tick(if (resumed) delta else 0f)
                }
                renderFrame()
                val frameBudget = 16_666_667L - ((System.nanoTime() - now) / 1000L)
                if (frameBudget > 0L) {
                    try {
                        sleep(frameBudget / 1_000_000L)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }

        private fun processEvents() {
            while (true) {
                val event = queue.poll() ?: return
                when (event.kind) {
                    PointerEvent.Kind.SHUTDOWN -> {
                        running = false
                        session.onAppPause()
                        session.persist(force = true)
                        session.flush()
                        return
                    }
                    PointerEvent.Kind.SUSPEND -> {
                        session.onAppPause()
                        session.persist(force = true)
                        session.flush()
                        suspendLatch?.countDown()
                    }
                    PointerEvent.Kind.RESUME -> {
                        session.onAppResume()
                    }
                    PointerEvent.Kind.DOWN -> {
                        val scrollable = session.screen == com.elthon.infinite.ui.Screen.META || session.screen == com.elthon.infinite.ui.Screen.ARCHIVE
                        if (scrollable) {
                            scrollPointer = event.id
                            scrollLastY = event.y
                        } else {
                            session.input.down(event.id, event.x, event.y, session.regions)
                        }
                    }
                    PointerEvent.Kind.MOVE -> {
                        if (event.id == scrollPointer) {
                            val delta = scrollLastY - event.y
                            if (abs(delta) > 0.5f) {
                                session.scrollBy(delta)
                                scrollLastY = event.y
                            }
                        } else {
                            session.input.move(event.id, event.x, event.y)
                        }
                    }
                    PointerEvent.Kind.UP -> {
                        if (event.id == scrollPointer) {
                            scrollPointer = -1
                        } else {
                            session.input.up(event.id, event.x, event.y, session.regions)
                        }
                    }
                    PointerEvent.Kind.CANCEL -> {
                        if (event.id == scrollPointer) scrollPointer = -1
                        session.input.cancel(event.id)
                    }

                }
            }
        }

        private fun renderFrame() {
            val surface = surfaceHolder.surface
            if (!surface.isValid) return
            val canvas = try {
                surfaceHolder.lockCanvas()
            } catch (error: Exception) {
                Log.w("InfiniteView", "lockCanvas failed", error)
                null
            } ?: return
            try {
                synchronized(canvas) {
                    renderer.draw(canvas)
                }
            } catch (error: Exception) {
                Log.e("InfiniteView", "draw failed", error)
            } finally {
                try {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                } catch (error: Exception) {
                    Log.w("InfiniteView", "unlockCanvas failed", error)
                }
            }
            if (session.profile.settings.hapticsEnabled) maybeHaptic()
        }

        private fun maybeHaptic() {
            val active = session.run ?: return
            if (active.phase != CombatPhase.FIGHTING) return
            if (session.shake <= 0.45f) return
            val now = System.currentTimeMillis()
            if (now - lastHaptic < 110L) return
            lastHaptic = now
            try {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } catch (error: Exception) {
                Log.w("InfiniteView", "haptic failed", error)
            }
        }
    }
}
