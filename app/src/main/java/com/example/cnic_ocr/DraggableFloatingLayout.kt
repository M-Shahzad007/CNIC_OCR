package com.example.cnic_ocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@SuppressLint("ClickableViewAccessibility")
class DraggableFloatingLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var dX = 0f
    private var dY = 0f
    private val mainContainer: LinearLayout

    // Bottom inset padding to protect against system navigation bar overlap
    private var systemNavBottomInset = 0

    var onDoneClick: (() -> Unit)? = null

    init {
        // Issue 2 Fix: Root FrameLayout must be fully transparent to hide rectangle corners
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false

        // Outer rounded card containing the drag bar + keyboard
        mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F5F7"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#D0D5DD"))
            }
            elevation = dp(12).toFloat()
            clipToOutline = true // Enforces strict rounded corners on internal content
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        }

        // --- Drag Handle Header Bar ---
        val dragHandle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E9ECEF"))
            }
        }

        val leftSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }

        // Pill-shaped visual drag handle
        val handlePill = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#ADB5BD"))
                cornerRadius = dp(4).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                weight = 1f
            }
        }

        // Done button
        val doneBtn = Button(context).apply {
            text = "✓  Done"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.parseColor("#4C6EF5"))
            background = null
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            setOnClickListener { onDoneClick?.invoke() }
        }

        dragHandle.addView(leftSpacer)
        dragHandle.addView(handlePill)
        dragHandle.addView(doneBtn)

        // Issue 3 Fix: Listen for Window Navigation Bar Insets
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemNavBottomInset = navInsets.bottom
            insets
        }

        // Touch Listener for Vertical/Horizontal Dragging bounded by System Navigation Bar
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = x - event.rawX
                    dY = y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parentView = parent as? View ?: return@setOnTouchListener true

                    // Constrain Y position so keyboard stays completely ABOVE system navigation bar
                    val maxAllowedY = (parentView.height - height.toFloat() - systemNavBottomInset).coerceAtLeast(0f)
                    val newX = (event.rawX + dX).coerceIn(0f, parentView.width - width.toFloat())
                    val newY = (event.rawY + dY).coerceIn(0f, maxAllowedY)

                    animate()
                        .x(newX)
                        .y(newY)
                        .setDuration(0)
                        .start()
                    true
                }
                else -> false
            }
        }

        mainContainer.addView(dragHandle)
        addView(mainContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setContentView(contentView: View) {
        if (contentView.parent != null) {
            (contentView.parent as? android.view.ViewGroup)?.removeView(contentView)
        }
        mainContainer.addView(contentView)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}