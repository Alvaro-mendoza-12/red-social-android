package conectate.com.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import conectate.com.R
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.LiveRole
import conectate.com.data.repository.LiveRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

@AndroidEntryPoint
class LiveChatHeadService : Service() {

    @Inject
    lateinit var liveRepository: LiveRepository

    companion object {
        const val ACTION_START = "conectate.com.action.START_LIVE_CHAT_HEAD"
        const val ACTION_STOP = "conectate.com.action.STOP_LIVE_CHAT_HEAD"
    }

    private val serviceScope = newSafeScope("LiveChatHeadService", Dispatchers.Main)
    private var overlayJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var rootView: LinearLayout? = null
    private var expandedContainer: LinearLayout? = null
    private var commentsContainer: LinearLayout? = null
    private var minimizedBubble: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isMinimized = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureOverlay()
                observeLiveState()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeLiveState() {
        if (overlayJob != null) return

        overlayJob = serviceScope.launch {
            combine(liveRepository.activeSession, liveRepository.comments) { session, comments ->
                session to comments
            }.collect { (session, comments) ->
                if (session == null || session.role != LiveRole.HOST) {
                    stopSelf()
                    return@collect
                }
                updateOverlay(
                    title = session.stream.title.ifBlank { getString(R.string.live_overlay_title_default) },
                    viewerCount = session.viewerCount,
                    comments = comments.takeLast(5)
                )
            }
        }
    }

    private fun ensureOverlay() {
        if (rootView != null) return

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 42
            y = 200
        }

        val bubbleBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 38f
            setColor(ContextCompat.getColor(this@LiveChatHeadService, android.R.color.black))
            alpha = 225
        }

        val cardBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 34f
            setColor(0xEE121212.toInt())
            setStroke(2, 0x44FFFFFF)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBackground
            setPadding(0, 0, 0, 0)
        }

        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumWidth = dp(240)
        }

        val header = AccessibleLinearLayout(this).apply {
            orientation = LinearLayoutCompat.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = getString(R.string.live_overlay_title)
            setTextColor(ContextCompat.getColor(this@LiveChatHeadService, android.R.color.white))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }

        val subtitleText = TextView(this).apply {
            text = getString(R.string.live_overlay_subtitle)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText)
            addView(subtitleText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            background = null
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.live_overlay_minimize)
            setOnClickListener { setMinimized(true) }
        }

        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = getString(R.string.live_overlay_close)
            setOnClickListener { stopSelf() }
        }

        header.addView(titleColumn)
        header.addView(minimizeButton)
        header.addView(closeButton)

        val comments = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val minimized = AccessibleTextView(this).apply {
            text = getString(R.string.live_overlay_bubble_idle)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@LiveChatHeadService, android.R.color.white))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 44f
                setColor(0xEEE53935.toInt())
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
            contentDescription = getString(R.string.live_overlay_bubble_description)
            setOnClickListener {
                setMinimized(false)
            }
        }

        expanded.addView(header)
        expanded.addView(comments)

        root.addView(expanded)
        root.addView(minimized)

        val headerDragListener = FloatingDragTouchListener(
            paramsProvider = { layoutParams },
            onClick = { },
            onMove = { params ->
                rootView?.let { view -> windowManager.updateViewLayout(view, params) }
            }
        )

        val bubbleDragListener = FloatingDragTouchListener(
            paramsProvider = { layoutParams },
            onClick = { setMinimized(false) },
            onMove = { params ->
                rootView?.let { view -> windowManager.updateViewLayout(view, params) }
            }
        )

        header.isClickable = true
        header.setOnTouchListener(headerDragListener)
        minimized.setOnTouchListener(bubbleDragListener)

        rootView = root
        expandedContainer = expanded
        commentsContainer = comments
        minimizedBubble = minimized

        windowManager.addView(root, layoutParams)
    }

    private fun updateOverlay(
        title: String,
        viewerCount: Int,
        comments: List<conectate.com.data.model.LiveComment>
    ) {
        ensureOverlay()
        val expanded = expandedContainer ?: return
        val commentsView = commentsContainer ?: return

        val headerColumn = (expanded.getChildAt(0) as? LinearLayout)
            ?.getChildAt(0) as? LinearLayout
        val titleView = headerColumn?.getChildAt(0) as? TextView
        val subtitleView = headerColumn?.getChildAt(1) as? TextView
        titleView?.text = title.ifBlank { getString(R.string.live_overlay_title_default) }
        subtitleView?.text = getString(R.string.live_overlay_status, viewerCount)
        minimizedBubble?.text = getString(R.string.live_overlay_bubble_status, viewerCount)

        commentsView.removeAllViews()

        if (comments.isEmpty()) {
            commentsView.addView(buildCommentText(getString(R.string.live_overlay_empty)))
            return
        }

        comments.forEach { comment ->
            commentsView.addView(
                buildCommentText(
                    getString(
                        R.string.live_overlay_comment_format,
                        comment.userName.ifBlank { getString(R.string.live_overlay_user_fallback) },
                        comment.content
                    )
                )
            )
        }
    }

    private fun buildCommentText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@LiveChatHeadService, android.R.color.white))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun setMinimized(minimized: Boolean) {
        isMinimized = minimized
        expandedContainer?.visibility = if (minimized) View.GONE else View.VISIBLE
        minimizedBubble?.visibility = if (minimized) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayJob?.cancel()
        overlayJob = null
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        expandedContainer = null
        commentsContainer = null
        minimizedBubble = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private class FloatingDragTouchListener(
        private val paramsProvider: () -> WindowManager.LayoutParams?,
        private val onClick: () -> Unit,
        private val onMove: (WindowManager.LayoutParams) -> Unit
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = paramsProvider() ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (abs(deltaX) > 8 || abs(deltaY) > 8) {
                        moved = true
                    }
                    params.x = initialX + deltaX
                    params.y = initialY + deltaY
                    onMove(params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val handledByView = view.performClick()
                        if (!handledByView) {
                            onClick()
                        }
                    }
                    return true
                }
            }
            return false
        }
    }

    private class AccessibleLinearLayout(context: Context) : LinearLayoutCompat(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class AccessibleTextView(context: Context) : AppCompatTextView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
