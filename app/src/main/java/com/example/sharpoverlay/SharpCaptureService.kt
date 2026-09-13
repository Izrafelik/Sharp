package com.example.sharpoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Держит EGL-контекст, VirtualDisplay (источник кадров игры) и SurfaceView
 * оверлея (куда рисуется обработанный кадр). Оригинальный экран НЕ скрывается
 * автоматически системой — overlay должен быть непрозрачным и перекрывать
 * весь экран, иначе будет наложение оригинала и обработанной копии.
 *
 * ВАЖНО: это прототип для замера задержки. Скрытие исходного контента под
 * overlay на Android не гарантировано на 100% на всех прошивках -
 * подробности и предостережения см. в README_LATENCY.md.
 */
class SharpCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "sharp_overlay_channel"
        const val NOTIF_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        var currentMode: SharpMode = SharpMode.CAS
        var currentSharpness: Float = 0.5f
    }

    private lateinit var windowManager: WindowManager
    private var overlaySurfaceView: SurfaceView? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private lateinit var renderer: GLRenderer

    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        renderThread = HandlerThread("GLRenderThread")
        renderThread.start()
        renderHandler = Handler(renderThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_CANCELED) ?: return START_NOT_STICKY
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        startForeground(NOTIF_ID, buildNotification())

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (resultData != null) {
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)
            setupOverlayAndCapture()
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupOverlayAndCapture() {
        val overlayView = SurfaceView(this)
        overlaySurfaceView = overlayView

        val layoutFlags = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlags,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE // непрозрачный - должен полностью перекрыть исходный кадр
        )

        windowManager.addView(overlayView, params)

        overlayView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                renderHandler.post {
                    initEGL(holder.surface)
                    renderer = GLRenderer(this@SharpCaptureService)
                    renderer.mode = currentMode
                    renderer.sharpness = currentSharpness
                    renderer.setupGL(screenWidth, screenHeight)

                    val captureSurface = Surface(renderer.surfaceTexture)
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "SharpCapture",
                        screenWidth, screenHeight, screenDensity,
                        android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        captureSurface, null, renderHandler
                    )

                    renderer.surfaceTexture?.setOnFrameAvailableListener({
                        renderHandler.post { drawAndSwap() }
                    }, renderHandler)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderHandler.post { releaseEGL() }
            }
        })
    }

    private fun drawAndSwap() {
        if (eglDisplay == null) return
        renderer.updateTexImage()
        renderer.drawFrame()
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun initEGL(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun releaseEGL() {
        virtualDisplay?.release()
        renderer.release()
        if (eglDisplay != null) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = null
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Sharp Overlay", NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Фильтр резкости активен")
            .setContentText("Режим: ${currentMode.name}")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlaySurfaceView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        mediaProjection?.stop()
        renderThread.quitSafely()
    }
}

// Небольшой хелпер, чтобы не тянуть Activity.RESULT_CANCELED константу лишним импортом
private const val Activity_RESULT_CANCELED = 0
