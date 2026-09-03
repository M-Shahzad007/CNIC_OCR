package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper

import android.graphics.RectF

/**
 * SINGLE SOURCE OF TRUTH for where the card box sits inside ANY rectangle
 * (a screen View or a Bitmap). Both the on-screen overlay and the analyzer
 * MUST call this same function — never re-derive the box independently.
 */
object CardGeometry {

    private const val CNIC_ASPECT = 1.586f // width / height of a Pakistani CNIC

    /**
     * Returns the card bounding box (in the same pixel units as containerW/H)
     * centered inside a container of size containerW x containerH.
     */
    fun computeCardBounds(containerW: Float, containerH: Float): RectF {
        val cardWidth: Float
        val cardHeight: Float

        if (containerW > containerH) {
            // Landscape container
            cardHeight = containerH * 0.80f
            cardWidth = cardHeight * CNIC_ASPECT
        } else {
            // Portrait container
            cardWidth = containerW * 0.90f
            cardHeight = cardWidth / CNIC_ASPECT
        }

        val left = (containerW - cardWidth) / 2f
        val top = (containerH - cardHeight) / 2f
        return RectF(left, top, left + cardWidth, top + cardHeight)
    }

    /** Maps a fractional zone (0..1) into pixel space of a given card box. */
    fun toCardSpace(zone: RectF, card: RectF): RectF {
        val cw = card.width()
        val ch = card.height()
        return RectF(
            card.left + cw * zone.left,
            card.top + ch * zone.top,
            card.left + cw * zone.right,
            card.top + ch * zone.bottom
        )
    }
}