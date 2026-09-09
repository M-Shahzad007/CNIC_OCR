package com.example.cnic_ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cnic_ocr.databinding.ActivityMainBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import pk.pitb.cnic_ocr_detection.OCRManager
import pk.pitb.cnic_ocr_detection.callbacks.OcrDetectionCallback
import pk.pitb.cnic_ocr_detection.views.urduExtractor.helper.CnicFields
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fieldInputs = LinkedHashMap<String, TextInputEditText>()
    private val urduFieldLabels = mutableSetOf<String>()

    // Track each field's containing TextInputLayout so we know where to
    // reposition the virtual keyboard within ll_fields_container.
    private val fieldContainers = LinkedHashMap<String, TextInputLayout>()

    private var preferVirtualKeyboardThisSession: Boolean? = null
    private var pendingRecheckField: EditText? = null
    private var floatingKeyboardWrapper: DraggableFloatingLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.openOrcActivity.setOnClickListener {
            startOcrFlow()
        }

        binding.btnSave.setOnClickListener {
            saveCorrections()
        }

      //  startOcrFlow()
    }

    override fun onResume() {
        super.onResume()
        val field = pendingRecheckField ?: return
        pendingRecheckField = null
        if (field.hasFocus()) {
            handleUrduFieldFocused(field)
        }
    }

    private fun startOcrFlow() {
        val ocrManager = OCRManager.getInstance(object : OcrDetectionCallback() {
            override fun onOcrDetection(cnicFields: CnicFields, imageBytes: ByteArray?) {
                val cardBitmap = imageBytes?.let { BitmapFactory.decodeByteArray(imageBytes, 0, it.size) }
                runOnUiThread {
                    displayResults(cnicFields, cardBitmap)
                }
            }
        })

        ocrManager.requestOcrDetection(this)
    }

    private fun displayResults(fields: CnicFields, cardBitmap: Bitmap?) {
        if (cardBitmap != null) {
            binding.ivCnicFull.setImageBitmap(cardBitmap)
            detectAndCropFace(cardBitmap)
        } else {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
        }

        populateFormFields(fields)

        binding.cardImages.visibility = View.VISIBLE
        binding.cardFields.visibility = View.VISIBLE
        binding.btnSave.visibility = View.VISIBLE
    }

    private fun detectAndCropFace(bitmap: Bitmap) {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()

        val detector = FaceDetection.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val primaryFace = faces.maxByOrNull { face ->
                        face.boundingBox.width() * face.boundingBox.height()
                    } ?: faces[0]

                    val croppedBitmap = cropFaceWithPadding(bitmap, primaryFace.boundingBox)
                    binding.ivCroppedFace.setImageBitmap(croppedBitmap)
                } else {
                   // Toast.makeText(this, "No face detected in document", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Face detection failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun cropFaceWithPadding(original: Bitmap, bounds: Rect): Bitmap {
        val paddingWidth = (bounds.width() * 0.30).toInt()
        val paddingHeight = (bounds.height() * 0.30).toInt()

        val left = max(0, bounds.left - paddingWidth)
        val top = max(0, bounds.top - paddingHeight)
        val right = min(original.width, bounds.right + paddingWidth)
        val bottom = min(original.height, bounds.bottom + paddingHeight)

        return Bitmap.createBitmap(original, left, top, right - left, bottom - top)
    }

    // ---------- Editable form ----------

    private fun populateFormFields(fields: CnicFields) {
        val container = binding.llFieldsContainer
        fieldInputs.clear()
        urduFieldLabels.clear()
        fieldContainers.clear()

        if (container.childCount > 2) {
            container.removeViews(2, container.childCount - 2)
        }

        val fieldMap = listOf(
            Triple("Name", fields.name, false),
            Triple("Name (Urdu)", fields.nameUrdu, true),
            Triple("Father Name", fields.fatherName, false),
            Triple("Father Name (Urdu)", fields.fatherNameUrdu, true),
            Triple("Identity Number", fields.identityNumber, false),
            Triple("Gender", fields.gender, false),
            Triple("Date of Birth", fields.dateOfBirth, false),
            Triple("Date of Issue", fields.dateOfIssue, false),
            Triple("Date of Expiry", fields.dateOfExpiry, false),
            Triple("Country of Stay", fields.countryOfStay, false)
        )

        for ((key, value, isUrdu) in fieldMap) {
            addEditableFieldRow(container, key, value, isUrdu)
            if (isUrdu) urduFieldLabels.add(key)
        }

        if (fields.debugBlocks.isNotEmpty()) {
            var s = ""
            fields.debugBlocks.forEach { (blockName, rawText) ->
                s += "${blockName}:  ${rawText} \n\n"
            }
            binding.debugTxt.text = s
        }
    }

    private fun addEditableFieldRow(container: LinearLayout, label: String, value: String?, isUrdu: Boolean) {
        val inputLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 14)
            }
            hint = label
            setBoxCornerRadii(12f, 12f, 12f, 12f)
            boxStrokeColor = android.graphics.Color.parseColor("#4C6EF5")
            hintTextColor = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#4C6EF5")
            )
        }

        val editText = TextInputEditText(inputLayout.context).apply {
            setText(value ?: "")
            setTextColor(android.graphics.Color.parseColor("#212529"))
            typeface = Typeface.DEFAULT

            if (isUrdu) {
                textDirection = View.TEXT_DIRECTION_RTL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                gravity = Gravity.END
                inputType = InputType.TYPE_CLASS_TEXT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    imeHintLocales = LocaleList(Locale.Builder().setLanguage("ur").build())
                }

                setOnFocusChangeListener { view, hasFocus ->
                    val et = view as EditText
                    if (hasFocus) {
                        handleUrduFieldFocused(et)
                    } else {
                        binding.urduVirtualKeyboard.visibility = View.GONE
                    }
                }
            } else {
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                gravity = Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    imeHintLocales = LocaleList(Locale.ENGLISH)
                }

                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        // Plain English field -> always the normal system keyboard,
                        // regardless of whatever the last-focused Urdu field decided.
                        showSoftInputOnFocus = true
                        binding.urduVirtualKeyboard.visibility = View.GONE
                    }
                }
            }
        }

        inputLayout.addView(editText)
        container.addView(inputLayout)
        fieldInputs[label] = editText
        fieldContainers[label] = inputLayout
    }

    // ---------- Urdu keyboard decision flow ----------

    private fun handleUrduFieldFocusedInUi(editText: EditText) {
        val hasUrduKeyboard = UrduKeyboardHelper.isUrduKeyboardEnabled(this)

        when {
            hasUrduKeyboard && preferVirtualKeyboardThisSession != true -> {
                preferVirtualKeyboardThisSession = false
                editText.showSoftInputOnFocus = true
                binding.urduVirtualKeyboard.visibility = View.GONE
                // Let the system show its own keyboard normally; imeHintLocales
                // nudges Gboard etc. to the Urdu subtype automatically.
            }

            preferVirtualKeyboardThisSession == true -> {
                showVirtualKeyboardFor(editText)
            }

            else -> {
                editText.showSoftInputOnFocus = false
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editText.windowToken, 0)

                UrduKeyboardHelper.promptUrduInputChoice(
                    context = this,
                    onChooseInstall = {
                        preferVirtualKeyboardThisSession = false
                        pendingRecheckField = editText
                        UrduKeyboardHelper.openInputMethodSettings(this)
                    },
                    onChooseVirtual = {
                        // Reached either by explicit "Use In-App Keyboard" tap,
                        // OR by dismissing the dialog (back/tap-outside) — both
                        // paths land here so the field never ends up with no
                        // way to type Urdu.
                        preferVirtualKeyboardThisSession = true
                        showVirtualKeyboardFor(editText)
                    }
                )
            }
        }
    }
    private fun handleUrduFieldFocused(editText: EditText) {
        val hasUrduKeyboard = UrduKeyboardHelper.isUrduKeyboardEnabled(this)

        when {
            hasUrduKeyboard && preferVirtualKeyboardThisSession != true -> {
                preferVirtualKeyboardThisSession = false
                editText.showSoftInputOnFocus = true
                hideFloatingKeyboard()
            }

            preferVirtualKeyboardThisSession == true -> {
                showFloatingKeyboardFor(editText)
            }

            else -> {
                editText.showSoftInputOnFocus = false
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editText.windowToken, 0)

                UrduKeyboardHelper.promptUrduInputChoice(
                    context = this,
                    onChooseInstall = {
                        preferVirtualKeyboardThisSession = false
                        pendingRecheckField = editText
                        UrduKeyboardHelper.openInputMethodSettings(this)
                    },
                    onChooseVirtual = {
                        preferVirtualKeyboardThisSession = true
                        showFloatingKeyboardFor(editText)
                    }
                )
            }
        }
    }
    private fun showFloatingKeyboardFor(editText: EditText) {
        editText.showSoftInputOnFocus = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)

        // Bind the active target EditText to the keyboard
        binding.urduVirtualKeyboard.targetEditText = editText

        // Create the wrapper if it doesn't exist yet
        if (floatingKeyboardWrapper == null) {
            val wrapper = DraggableFloatingLayout(this).apply {
                onDoneClick = {
                    hideFloatingKeyboard()
                    // Clear focus from current EditText so cursor hides
                    binding.urduVirtualKeyboard.targetEditText?.clearFocus()
                }
            }
            wrapper.setContentView(binding.urduVirtualKeyboard)

            val rootViewGroup = window.decorView as android.view.ViewGroup

            // --- Full Width Layout Configuration ---
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, // Full screen width
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                leftMargin = 24  // Padding from screen left
                rightMargin = 24 // Padding from screen right
                bottomMargin = 48
            }

            rootViewGroup.addView(wrapper, params)
            floatingKeyboardWrapper = wrapper
        }

        floatingKeyboardWrapper?.visibility = View.VISIBLE
        binding.urduVirtualKeyboard.visibility = View.VISIBLE
    }

    private fun hideFloatingKeyboard() {
        floatingKeyboardWrapper?.visibility = View.GONE
        binding.urduVirtualKeyboard.targetEditText?.clearFocus()
    }
    private fun showVirtualKeyboardFor(editText: EditText) {
        editText.showSoftInputOnFocus = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)

        val label = fieldInputs.entries.firstOrNull { it.value == editText }?.key
        val targetLayout = label?.let { fieldContainers[it] }

        val container = binding.llFieldsContainer
        val keyboard = binding.urduVirtualKeyboard

        // FIX: cast to ViewGroup (works regardless of what type the current
        // parent is — MaterialCardView, LinearLayout, ConstraintLayout, etc.)
        // instead of assuming it's specifically a LinearLayout.
        (keyboard.parent as? android.view.ViewGroup)?.removeView(keyboard)

        if (targetLayout != null) {
            val targetIndex = container.indexOfChild(targetLayout)
            if (targetIndex >= 0) {
                container.addView(keyboard, targetIndex + 1)
            } else {
                container.addView(keyboard)
            }
        } else {
            container.addView(keyboard)
        }

        keyboard.visibility = View.VISIBLE
        keyboard.targetEditText = editText

        keyboard.post {
            binding.main.smoothScrollTo(0, keyboard.top - 24)
        }
    }
    private fun saveCorrections() {
        val corrected = fieldInputs.mapValues { it.value.text?.toString().orEmpty() }
        Toast.makeText(this, "Saved: ${corrected.entries.joinToString { "${it.key}=${it.value}" }}", Toast.LENGTH_LONG).show()
    }
}