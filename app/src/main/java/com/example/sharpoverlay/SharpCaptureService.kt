package com.example.sharpoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class SharpCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "sharp_overlay_channel"
        const val NOTIF_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val TAG = "SharpCapture"

        @Volatile var currentMode: SharpMode = SharpMode.CAS
        @Volatile var currentSharpness: Float = 0.5f
    }

    private lateinit var windowManager: WindowManager
    private var overlaySurfaceView: SurfaceView? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var renderer: GLRenderer? = null

    private lateinit var renderThread: HandlerThread
    private lateinit var renderHandler: Handler

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        renderThread = HandlerThread("GLRenderThread")
        renderThread.start()
        renderHandler = Handler(renderThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // 1. Сразу поднимаем foreground service с правильным типом (критично для Android 14+)
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (resultData == null || resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "Bad MediaProjection result: code=$resultCode data=$resultData")
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData)

            // 2. Колбэк ОБЯЗАТЕЛЬНО до createVirtualDisplay (Android 14+)
            mediaProjection?.registerCallback(projectionCallback, renderHandler)

            setupOverlayAndCapture()
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection / setup failed", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupOverlayAndCapture() {
        val overlayView = SurfaceView(this).also { overlaySurfaceView = it }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView overlay FAILED (check SYSTEM_ALERT_WINDOW)", e)
            stopSelf()
            return
        }

        overlayView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                renderHandler.post {
                    try {
                        initEGL(holder.surface)

                        val glRenderer = GLRenderer(this@SharpCaptureService)
                        glRenderer.mode = currentMode
                        glRenderer.sharpness = currentSharpness
                        glRenderer.setupGL(screenWidth, screenHeight)
                        renderer = glRenderer

                        val captureSurface = Surface(glRenderer.surfaceTexture)
                        virtualDisplay = mediaProjection?.createVirtualDisplay(
                            "SharpCapture",
                            screenWidth,
                            screenHeight,
                            screenDensity,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            captureSurface,
                            null,
                            renderHandler
                        )

                        if (virtualDisplay == null) {
                            throw RuntimeException("createVirtualDisplay returned null")
                        }

                        glRenderer.surfaceTexture?.setOnFrameAvailableListener({
                            renderHandler.post { drawAndSwap() }
                        }, renderHandler)

                        Log.i(TAG, "OK: capture ${screenWidth}x${screenHeight}")
                    } catch (e: Exception) {
                        Log.e(TAG, "GL or VirtualDisplay init FAILED", e)
                        stopSelf()
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderHandler.post { releaseEGL() }
            }
        })
    }

    private fun drawAndSwap() {
        try {
            val disp = eglDisplay ?: return
            val r = renderer ?: return
            r.mode = currentMode
            r.sharpness = currentSharpness
            r.updateTexImage()
            r.drawFrame()
            EGL14.eglSwapBuffers(disp, eglSurface)
        } catch (e: Exception) {
            Log.e(TAG, "drawAndSwap error", e)
        }
    }

    private fun initEGL(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        // 0x0040 = EGL_OPENGL_ES3_BIT_KHR
        val ES3_BIT = 0x0040

        var config: EGLConfig? = null
        val numConfigs = IntArray(1)

        // Сначала пробуем ES3
        val attribsEs3 = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, ES3_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        if (EGL14.eglChooseConfig(eglDisplay, attribsEs3, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            config = configs[0]
        }

        // Fallback на ES2
        if (config == null) {
            val attribsEs2 = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            if (EGL14.eglChooseConfig(eglDisplay, attribsEs2, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                config = configs[0]
            }
        }

        if (config == null) throw RuntimeException("No suitable EGL config found")

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            // пробуем ES2-контекст
            val ctx2 = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctx2, 0)
        }
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
    }

    private fun releaseEGL() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            renderer?.release()
            renderer = null
            if (eglDisplay != null) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                eglSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
                eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseEGL error", e)
        } finally {
            eglDisplay = null
            eglContext = null
            eglSurface = null
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
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        overlaySurfaceView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlaySurfaceView = null

        if (::renderHandler.isInitialized) {
            renderHandler.post { releaseEGL() }
        }
        if (::renderThread.isInitialized) {
            renderThread.quitSafely()
        }
    }
}
