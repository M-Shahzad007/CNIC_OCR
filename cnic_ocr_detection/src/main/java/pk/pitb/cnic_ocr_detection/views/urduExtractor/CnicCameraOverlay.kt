package pk.pitb.cnic_ocr_detection.views.urduExtractor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CnicCameraOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val cardBounds = RectF()

    // Key spatial zones defined relative to the main card container
    val headerBounds = RectF()
    val nameLabelBounds = RectF()
    val fatherNameLabelBounds = RectF()

    val footerBlock1Bounds = RectF()
    val footerBlock2Bounds = RectF()
    val footerBlock3Bounds = RectF()
    val signatureBounds = RectF()
    val signatureTextBounds = RectF()

    // Direct Extraction Regions (derived from cardBounds)
    val urduNameBounds = RectF()
    val urduFatherNameBounds = RectF()

    private var isAligned = false
    var isHeaderValid = false
    var isNameValid = false
    var isFatherNameValid = false
    var isSignatureValid = false

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#99000000") // Dim background
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.WHITE
        isAntiAlias = true
    }
    private val guideZonePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#80FFFFFF") // Semi-transparent guide outline
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        // color = Color.parseColor("#B0FFFFFF")
        color = Color.CYAN
        textSize = 28f
        isAntiAlias = true
    }

    /*
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)

            // Standard CNIC Ratio in Landscape (~1.58)
            val cardWidth = w * 0.88f
            val cardHeight = cardWidth / 1.586f
            val left = (w - cardWidth) / 2f
            val top = (h - cardHeight) / 2f

            cardBounds.set(left, top, left + cardWidth, top + cardHeight)

            val cw = cardBounds.width()
            val ch = cardBounds.height()
            val cLeft = cardBounds.left
            val cTop = cardBounds.top

            // Relative Spatial Mapping matching Pakistani CNIC layout
            headerBounds.set(cLeft + cw * 0.25f, cTop + ch * 0.02f, cLeft + cw * 0.78f, cTop + ch * 0.18f)
            nameLabelBounds.set(cLeft + cw * 0.25f, cTop + ch * 0.19f, cLeft + cw * 0.50f, cTop + ch * 0.27f)
            fatherNameLabelBounds.set(cLeft + cw * 0.25f, cTop + ch * 0.43f, cLeft + cw * 0.50f, cTop + ch * 0.51f)
            signatureBounds.set(cLeft + cw * 0.68f, cTop + ch * 0.74f, cLeft + cw * 0.98f, cTop + ch * 0.98f)

            // Exact crop coordinates for Urdu fields (Right side of name areas)
            urduNameBounds.set(cLeft + cw * 0.25f, cTop + ch * 0.35f, cLeft + cw * 0.68f, cTop + ch * 0.44f)
            urduFatherNameBounds.set(cLeft + cw * 0.25f, cTop + ch * 0.59f, cLeft + cw * 0.68f, cTop + ch * 0.68f)
        }
    */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val cardWidth: Float
        val cardHeight: Float

        if (w > h) {
            // Landscape Mode: Scale relative to screen height
            cardHeight = h * 0.80f
            cardWidth = cardHeight * 1.586f
        } else {
            // Portrait Fallback: Scale relative to screen width
            cardWidth = w * 0.90f
            cardHeight = cardWidth / 1.586f
        }

        //Centering the CNIC box
        val left = (w - cardWidth) / 2f
        val top = (h - cardHeight) / 2f

        // Main Card Box Bounds
        cardBounds.set(left, top, left + cardWidth, top + cardHeight)

        val cw = cardBounds.width()
        val ch = cardBounds.height()
        val cLeft = cardBounds.left
        val cTop = cardBounds.top

        // Recalculate spatial sub-zones inside the Card Box

        headerBounds.set(toCardSpace(CnicFieldZones.HEADER, cLeft, cTop, cw, ch))
        nameLabelBounds.set(toCardSpace(CnicFieldZones.NAME_LABEL, cLeft, cTop, cw, ch))
        urduNameBounds.set(toCardSpace(CnicFieldZones.NAME_URDU, cLeft, cTop, cw, ch))
        fatherNameLabelBounds.set(toCardSpace(CnicFieldZones.FATHER_LABEL, cLeft, cTop, cw, ch))
        urduFatherNameBounds.set(toCardSpace(CnicFieldZones.FATHER_URDU, cLeft, cTop, cw, ch))
        footerBlock1Bounds.set(toCardSpace(CnicFieldZones.FOOTER_1, cLeft, cTop, cw, ch))
        footerBlock2Bounds.set(toCardSpace(CnicFieldZones.FOOTER_2, cLeft, cTop, cw, ch))
        footerBlock3Bounds.set(toCardSpace(CnicFieldZones.FOOTER_3, cLeft, cTop, cw, ch))
        signatureTextBounds.set(toCardSpace(CnicFieldZones.SIGNATURE_TEXT, cLeft, cTop, cw, ch))
        /*

        headerBounds.set(
            cLeft + cw * 0.22f,
            cTop + ch * 0.03f,
            cLeft + cw * 0.82f,
            cTop + ch * 0.13f
        )

        nameLabelBounds.set(
            cLeft + cw * 0.25f,
            cTop + ch * 0.18f,
            cLeft + cw * 0.65f,
            cTop + ch * 0.23f
        )
        urduNameBounds.set(
            cLeft + cw * 0.25f,
            cTop + ch * 0.28f,
            cLeft + cw * 0.68f,
            cTop + ch * 0.38f
        )


        fatherNameLabelBounds.set(
            cLeft + cw * 0.25f,
            cTop + ch * 0.38f,
            cLeft + cw * 0.65f,
            cTop + ch * 0.43f
        )
        urduFatherNameBounds.set(
            cLeft + cw * 0.25f,
            cTop + ch * 0.47f,
            cLeft + cw * 0.68f,
            cTop + ch * 0.58f
        )

        //footerBlock1Bounds.set(cLeft + cw * 0.25f, cTop + ch * 0.58f, cLeft + cw * 0.68f, cTop + ch * 0.74f)
        footerBlock1Bounds.set(
            cLeft + cw * 0.25f,
            cTop + ch * 0.58f,
            cLeft + cw * 0.68f,
            cTop + ch * 0.74f
        )
        footerBlock2Bounds.set(
            cLeft + cw * 0.25f,
            cTop + ch * 0.74f,
            cLeft + cw * 0.68f,
            cTop + ch * 0.86f
        )
        footerBlock3Bounds.set(cLeft + cw * 0.25f, cTop + ch * 0.86f, cLeft + cw * 0.68f, cTop + ch)

        //   signatureBounds.set(cLeft + cw * 0.68f, cTop + ch * 0.72f, cLeft + cw * 0.96f, cTop + ch * 0.96f)
        signatureTextBounds.set(
            cLeft + cw * 0.68f,
            cTop + ch * 0.90f,
            cLeft + cw * 0.96f,
            cTop + ch * 0.99f
        )
*/
        // Exact Urdu Crop Regions relative to Card Box
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // 1. Darken background outside CNIC Box
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // 2. Cut out clear rectangular window for CNIC
        canvas.drawRoundRect(cardBounds, 16f, 16f, clearPaint)

        // 3. Draw Main Border (Turns Green when aligned)
        borderPaint.color = if (isAligned) Color.GREEN else Color.WHITE
        canvas.drawRoundRect(cardBounds, 16f, 16f, borderPaint)

        // 4. Draw Helper Field Boxes & Placeholders
        // Helper lambda to draw individual zones with their dynamic color
        fun drawGuideZone(bounds: RectF, isValid: Boolean, label: String) {
            guideZonePaint.color = if (isValid) Color.GREEN else Color.RED
            canvas.drawRect(bounds, guideZonePaint)
            canvas.drawText(label, bounds.left + 8f, bounds.top + 24f, labelPaint)
        }

        // 4. Draw Individual Field Regions with independent validation colors
      //  drawGuideZone(headerBounds, isHeaderValid, "HEADER")
        drawGuideZone(nameLabelBounds, isNameValid, "NAME")
      /*  drawGuideZone(fatherNameLabelBounds, isFatherNameValid, "FATHER NAME")
        drawGuideZone(signatureTextBounds, isSignatureValid, "SIGNATURE")

        // Passive guide regions (or link them to their respective parents if needed)
        drawGuideZone(urduNameBounds, isNameValid, "NAME URDU")
        drawGuideZone(urduFatherNameBounds, isFatherNameValid, "FATHER NAME URDU")

        // Neutral guides (Footer blocks)
        guideZonePaint.color = if (isAligned) Color.GREEN else Color.RED
        canvas.drawRect(footerBlock1Bounds, guideZonePaint)
        canvas.drawRect(footerBlock2Bounds, guideZonePaint)
        canvas.drawRect(footerBlock3Bounds, guideZonePaint)*/
//        guideZonePaint.color = if (isAligned) Color.GREEN else Color.parseColor("#AABFFFFF")
      /*  guideZonePaint.color = if (isAligned) Color.GREEN else Color.RED

        canvas.drawRect(headerBounds, guideZonePaint)
        canvas.drawRect(nameLabelBounds, guideZonePaint)
        canvas.drawRect(fatherNameLabelBounds, guideZonePaint)
        // canvas.drawRect(signatureBounds, guideZonePaint)
        canvas.drawRect(signatureTextBounds, guideZonePaint)

        canvas.drawRect(footerBlock1Bounds, guideZonePaint)
        canvas.drawRect(footerBlock2Bounds, guideZonePaint)
        canvas.drawRect(footerBlock3Bounds, guideZonePaint)

        canvas.drawRect(urduNameBounds, guideZonePaint)
        canvas.drawRect(urduFatherNameBounds, guideZonePaint)

        // Semi-transparent field guide labels
        canvas.drawText("HEADER", headerBounds.left + 8f, headerBounds.top + 28f, labelPaint)
        canvas.drawText("NAME", nameLabelBounds.left + 8f, nameLabelBounds.top + 24f, labelPaint)
        canvas.drawText(
            "FATHER NAME",
            fatherNameLabelBounds.left + 8f,
            fatherNameLabelBounds.top + 24f,
            labelPaint
        )

        canvas.drawText("NAME URDU", urduNameBounds.left + 8f, urduNameBounds.top + 24f, labelPaint)
        canvas.drawText(
            "FATHER NAME URDU",
            urduFatherNameBounds.left + 8f,
            urduFatherNameBounds.top + 24f,
            labelPaint
        )*/
    }

    fun toCardSpace(zone: RectF, cLeft: Float, cTop: Float, cw: Float, ch: Float) =
        RectF(
            cLeft + cw * zone.left,
            cTop + ch * zone.top,
            cLeft + cw * zone.right,
            cTop + ch * zone.bottom
        )

    fun setAlignmentStatus(aligned: Boolean) {
        if (isAligned != aligned) {
            isAligned = aligned
            postInvalidate()
        }
    }
    fun setFieldStatuses(header: Boolean, name: Boolean, fatherName: Boolean, signature: Boolean) {
        this.isHeaderValid = header
        this.isNameValid = name
        this.isFatherNameValid = fatherName
        this.isSignatureValid = signature
      //  this.isAligned = header && name && fatherName && signature
        this.isAligned = name && fatherName && signature
        postInvalidate()
    }
}