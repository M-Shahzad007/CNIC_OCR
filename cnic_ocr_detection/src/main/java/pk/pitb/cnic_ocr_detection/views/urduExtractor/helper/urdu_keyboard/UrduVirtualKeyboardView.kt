package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.urdu_keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class UrduVirtualKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var targetEditText: EditText? = null

    /** Wired up by the host Activity to reopen the Urdu input-method picker/settings dialog. */
    var onSettingsClick: (() -> Unit)? = null

    // ---------- Palette ----------
    private val colorSurface = Color.parseColor("#F4F5F7")
    private val colorKeyBg = Color.WHITE
    private val colorKeyText = Color.parseColor("#212529")
    private val colorAccent = Color.parseColor("#4C6EF5")
    private val colorAccentText = Color.WHITE
    private val colorHeaderText = Color.parseColor("#6C757D")

    private val urduKeys = listOf(
        "ا", "ب", "پ", "ت", "ٹ", "ث", "ج", "چ", "ح", "خ",
        "د", "ڈ", "ذ", "ر", "ڑ", "ز", "ژ", "س", "ش", "ص",
        "ض", "ط", "ظ", "ع", "غ", "ف", "ق", "ک", "گ", "ل",
        "م", "ن", "ں", "و", "ہ", "ھ", "ء", "ی", "ے"
    )

    init {
//        orientation = VERTICAL
//        setBackgroundColor(colorSurface)
//        setPadding(dp(10), dp(10), dp(10), dp(10))
//        buildHeaderRow()
//        buildKeyRows()
//        buildControlRow()

        orientation = VERTICAL
        setBackgroundColor(Color.TRANSPARENT) // Background handled by Draggable container
        setPadding(dp(8), dp(4), dp(8), dp(8))

        // Remove buildHeaderRow() from here since DraggableFloatingLayout handles it
        buildKeyRows()
        buildControlRow()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    // ---------- Header: title + settings gear ----------

    private fun buildHeaderRow() {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val title = TextView(context).apply {
            text = "اردو کی بورڈ"
            textDirection = TEXT_DIRECTION_RTL
            textAlignment = TEXT_ALIGNMENT_VIEW_START
            setTextColor(colorHeaderText)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsBtn = Button(context).apply {
            text = "⚙"
            isAllCaps = false
            textSize = 16f
            setTextColor(colorHeaderText)
            background = flatCircleBackground(Color.TRANSPARENT)
            layoutParams = LayoutParams(dp(36), dp(36))
            setOnClickListener { onSettingsClick?.invoke() }
        }

        header.addView(title)
        header.addView(settingsBtn)
        addView(header)
    }

    // ---------- Letter grid ----------

    private fun buildKeyRows() {
        val keysPerRow = 10
        urduKeys.chunked(keysPerRow).forEach { rowKeys ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                // This is the actual fix: forces child order to render
                // right-to-left, so the first key added (ا) sits on the
                // right — matching how an Urdu keyboard is actually read.
                layoutDirection = LAYOUT_DIRECTION_RTL
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            rowKeys.forEach { key ->
                row.addView(keyButton(key))
            }
            addView(row)
        }
    }

    // ---------- Bottom row: backspace + space ----------

    private fun buildControlRow() {
        val controlRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        controlRow.addView(keyButton("⌫", weight = 1.4f, isSpecial = true) { deleteAtCursor() })
        controlRow.addView(keyButton("Space", weight = 3f, isSpecial = false) { insertAtCursor(" ") })

        addView(controlRow)
    }

    // ---------- Reusable styled key builder ----------

    private fun keyButton(
        label: String,
        weight: Float = 1f,
        isSpecial: Boolean = false,
        onClick: (() -> Unit)? = null
    ): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            textDirection = TEXT_DIRECTION_RTL
            textSize = 18f
            setTextColor(if (isSpecial) colorAccentText else colorKeyText)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(46)
            minimumHeight = dp(46)
            stateListAnimator = null
            elevation = dp(1).toFloat()
            background = rippleKeyBackground(if (isSpecial) colorAccent else colorKeyBg)

            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }

            setOnClickListener {
                if (onClick != null) onClick() else insertAtCursor(label)
            }
        }
    }

    // ---------- Drawable helpers (no XML resources needed) ----------

    private fun rippleKeyBackground(baseColor: Int): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(baseColor)
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#33000000"))
        return RippleDrawable(rippleColor, shape, shape)
    }

    private fun flatCircleBackground(baseColor: Int): Drawable {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(baseColor)
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#22000000"))
        return RippleDrawable(rippleColor, shape, shape)
    }

    // ---------- Text editing ----------

    private fun insertAtCursor(value: String) {
        val et = targetEditText ?: return
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        et.text?.replace(minOf(start, end), maxOf(start, end), value)
    }

    private fun deleteAtCursor() {
        val et = targetEditText ?: return
        val start = et.selectionStart
        if (start > 0) et.text?.delete(start - 1, start)
    }
}