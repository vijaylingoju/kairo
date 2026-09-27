package ai.kairo.gallery.assistant.ball

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ai.kairo.gallery.KairoApp
import ai.kairo.gallery.assistant.AssistantSession
import ai.kairo.gallery.data.Prefs
import ai.kairo.gallery.embed.Clip
import ai.kairo.gallery.index.Indexer
import ai.kairo.gallery.llm.Llm
import ai.kairo.gallery.ui.AppNavigation
import ai.kairo.gallery.ui.OpenRequest
import ai.kairo.gallery.ui.gallery.KairoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Kairo's floating ball: a small button over every app. Tapping it opens a box to ask Kairo; the answer shows
 * right there, or in the app when it's something to read or browse ([BallDialog]).
 *
 * A foreground service, because Android stops ordinary background services after about a minute. It also keeps
 * the memory use in check: Gemma is loaded when the ball is tapped and freed after [IDLE_UNLOAD_MS] without a
 * question, unless the user chose "Keep Kairo ready".
 */
class BallService : Service() {

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ball: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var dialog: View? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        owner.create()
        try {
            showBall()
        } catch (e: RuntimeException) {
            // "Display over other apps" was taken away: nothing to show. Stopped in onStartCommand, after
            // startForeground(), which Android requires once startForegroundService() was called.
            Log.w(TAG, "Can't show the ball", e)
            return
        }
        _running.value = true
        scope.launch {
            appVisible.collect { visible ->
                // The app is on screen: it has its own Kairo tab, so the ball steps aside.
                if (visible) closeDialog()
                ball?.isVisible = !visible && dialog == null
            }
        }
        scope.launch { unloadWhenIdle() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setBallEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() must be answered with startForeground(), even if we were running already.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (ball == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closeDialog()
        ball?.let { runCatching { wm.removeView(it) } }
        ball = null
        owner.destroy()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    // --- The ball ---------------------------------------------------------------------------------------------

    private fun showBall() {
        val size = dp(BALL_DP)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: typing and back presses keep going to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0  // screen coordinates; [area] keeps the ball clear of the status and navigation bars
            val area = area()
            x = if (Prefs.ballRight(this@BallService)) area.right - size else area.left
            y = Prefs.ballY(this@BallService).takeIf { it >= 0 }?.coerceIn(area.top, area.bottom - size)
                ?: (area.top + (area.bottom - area.top) * 0.35f).roundToInt()
        }
        val view = ComposeView(this).apply {
            contentDescription = "Ask Kairo"
            alpha = IDLE_ALPHA
            setContent { KairoTheme { BallFace() } }
        }
        owner.attach(view)
        view.setOnTouchListener(DragOrTap(params))
        view.setOnClickListener { openDialog() }
        wm.addView(view, params)
        ball = view
        ballParams = params
    }

    /** Moves the ball with the finger (screen coordinates, so it doesn't jitter), snaps it to the nearest edge, or taps. */
    private inner class DragOrTap(private val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BallService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    dragging = false
                    v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        val area = area()
                        p.x = (startX + dx.roundToInt()).coerceIn(area.left, area.right - p.width)
                        p.y = (startY + dy.roundToInt()).coerceIn(area.top, area.bottom - p.height)
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = IDLE_ALPHA
                    if (dragging) snapToEdge(v) else if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            return true
        }

        private fun snapToEdge(v: View) {
            val area = area()
            val right = p.x + p.width / 2 > (area.left + area.right) / 2
            val target = if (right) area.right - p.width else area.left
            ValueAnimator.ofInt(p.x, target).apply {
                duration = 180
                addUpdateListener {
                    p.x = it.animatedValue as Int
                    if (v.isAttachedToWindow) wm.updateViewLayout(v, p)
                }
                start()
            }
            Prefs.setBallPosition(this@BallService, right, p.y)
        }
    }

    /** Where the ball may go: the screen minus the status and navigation bars, with a small margin. */
    private fun area(): android.graphics.Rect {
        val metrics = wm.currentWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val b = metrics.bounds
        val m = dp(4)
        return android.graphics.Rect(b.left + bars.left + m, b.top + bars.top + m, b.right - bars.right - m, b.bottom - bars.bottom - m)
    }

    // --- The dialog -------------------------------------------------------------------------------------------

    private fun openDialog() {
        if (dialog != null) return
        warmUp()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE), or the keyboard can't type into the box.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.35f
            // The window keeps clear of the bars and the keyboard, so the card is never hidden under them.
            fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
        }
        val root = BackToClose(this) { closeDialog() }
        root.addView(ComposeView(this).apply {
            setContent { KairoTheme { BallDialog(onClose = ::closeDialog, onOpen = ::openApp) } }
        })
        owner.attach(root)
        wm.addView(root, params)
        dialog = root
        ball?.isVisible = false
    }

    private fun closeDialog() {
        dialog?.let { runCatching { wm.removeView(it) } }
        dialog = null
        ball?.isVisible = !appVisible.value
    }

    private fun openApp(request: OpenRequest) {
        closeDialog()
        AppNavigation.open(this, request)
    }

    /** The overlay has no Activity, so no back dispatcher: back closes the dialog (after the keyboard, if it's up). */
    private class BackToClose(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    // --- Model memory -----------------------------------------------------------------------------------------

    /** Loads the models while the user types (Gemma takes 5–9 s). */
    private fun warmUp() {
        scope.launch(Dispatchers.IO) {
            runCatching { Llm.ensure(applicationContext) }
            if (Clip.isAvailable(applicationContext)) runCatching { Clip.embedTexts(applicationContext, listOf("warm up")) }
        }
    }

    /**
     * Gemma and CLIP take about 2 GB. While this service runs Android treats Kairo as important and closes the
     * user's other apps first, so Gemma is freed once nobody has used it for a while.
     */
    private suspend fun unloadWhenIdle() {
        while (true) {
            delay(CHECK_EVERY_MS)
            val idle = SystemClock.elapsedRealtime() - Llm.lastUsedAt
            val inUse = appVisible.value || dialog != null || Indexer.status.value.running ||
                AssistantSession.get(this).busy.value
            if (Llm.isReady() && idle > IDLE_UNLOAD_MS && !inUse && !Prefs.keepModelReady(this)) {
                Log.i(TAG, "Gemma unused for ${idle / 1000} s: unloading")
                Llm.unload()
            }
        }
    }

    // --- Notification (required for a foreground service) ------------------------------------------------------

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BallService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, KairoApp.CHANNEL_BALL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Kairo is ready")
            .setContentText("Tap the ball to ask about your photos or your phone")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "KairoBall"
        private const val ACTION_STOP = "ai.kairo.gallery.ball.STOP"
        private const val NOTIFICATION_ID = 2
        private const val BALL_DP = 56
        private const val IDLE_ALPHA = 0.8f
        private const val CHECK_EVERY_MS = 60_000L
        private const val IDLE_UNLOAD_MS = 5 * 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Set by the gallery while it's on screen: the ball hides then. */
        val appVisible = MutableStateFlow(false)

        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * Opens the system screen where the user allows "Display over other apps" for Kairo. OriginOS ignores the
         * package and shows the whole list, so a hint says what to look for.
         */
        fun askPermission(context: Context) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            Toast.makeText(context, "Find “Kairo Gallery” and choose Allow", Toast.LENGTH_LONG).show()
        }

        /** Starts the ball if the user turned it on and allowed it. Call while the app is in front. */
        fun startIfEnabled(context: Context) {
            if (Prefs.ballEnabled(context) && canShow(context) && !running.value) {
                context.startForegroundService(Intent(context, BallService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BallService::class.java))
        }
    }
}

/** What an Activity normally gives Compose: a lifecycle and saved state. Overlay windows have neither. */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }

    fun attach(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
