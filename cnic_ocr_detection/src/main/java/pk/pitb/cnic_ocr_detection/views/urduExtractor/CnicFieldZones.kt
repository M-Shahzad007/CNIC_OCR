package pk.pitb.cnic_ocr_detection.views.urduExtractor

import android.graphics.RectF

object CnicFieldZones {
    // left, top, right, bottom — all fractions of cardBounds
    val HEADER          = RectF(0.22f, 0.03f, 0.82f, 0.13f)
   // val NAME_LABEL       = RectF(0.25f, 0.18f, 0.65f, 0.23f)
    val NAME_LABEL       = RectF(0.25f, 0.18f, 0.65f, 0.24f)
    val NAME_URDU        = RectF(0.25f, 0.28f, 0.68f, 0.38f)
   // val FATHER_LABEL     = RectF(0.25f, 0.38f, 0.65f, 0.43f)
    val FATHER_LABEL     = RectF(0.25f, 0.38f, 0.65f, 0.44f)
    val FATHER_URDU      = RectF(0.25f, 0.47f, 0.68f, 0.58f)
    val FOOTER_1         = RectF(0.25f, 0.58f, 0.68f, 0.74f)
    val FOOTER_2         = RectF(0.25f, 0.74f, 0.68f, 0.86f)
    val FOOTER_3         = RectF(0.25f, 0.86f, 0.68f, 1.00f)
    val SIGNATURE_TEXT   = RectF(0.68f, 0.90f, 0.96f, 0.98f)
}

/*
object CnicFieldZones {
    // left = 0.0f (0%), right = 1.0f (100%) for full-width horizontal coverage
    val HEADER          = RectF(0.00f, 0.03f, 1.00f, 0.13f)
    val NAME_LABEL       = RectF(0.00f, 0.18f, 1.00f, 0.24f)
    val NAME_URDU        = RectF(0.00f, 0.28f, 1.00f, 0.38f)
    val FATHER_LABEL     = RectF(0.00f, 0.38f, 1.00f, 0.44f)
    val FATHER_URDU      = RectF(0.00f, 0.47f, 1.00f, 0.58f)
    val FOOTER_1         = RectF(0.00f, 0.58f, 1.00f, 0.74f)
    val FOOTER_2         = RectF(0.00f, 0.74f, 1.00f, 0.86f)
    val FOOTER_3         = RectF(0.00f, 0.86f, 1.00f, 1.00f)
    val SIGNATURE_TEXT   = RectF(0.00f, 0.90f, 1.00f, 0.98f)
}*/
