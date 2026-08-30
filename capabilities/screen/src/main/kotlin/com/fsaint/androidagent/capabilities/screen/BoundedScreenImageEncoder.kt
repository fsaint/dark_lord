package com.fsaint.androidagent.capabilities.screen

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object BoundedScreenImageEncoder {
    fun encode(bitmap: Bitmap, request: ScreenCaptureRequest): ScreenCaptureOutcome {
        if (looksLikeSecureWindow(bitmap)) return ScreenCaptureOutcome.SecureWindow

        var working = bitmap.scaledWithin(request.maxWidth, request.maxHeight)
        try {
            while (true) {
                for (quality in 90 downTo 30 step 10) {
                    val output = ByteArrayOutputStream()
                    if (!working.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        return ScreenCaptureOutcome.Failed
                    }
                    val bytes = output.toByteArray()
                    if (bytes.size <= request.maxBytes) {
                        return ScreenCaptureOutcome.Success(
                            ScreenCaptureResult(
                                bytes = bytes,
                                width = working.width,
                                height = working.height,
                                mimeType = "image/jpeg",
                            ),
                        )
                    }
                }

                if (working.width == 1 && working.height == 1) return ScreenCaptureOutcome.Failed
                val nextWidth = if (working.width > 1) {
                    min(working.width - 1, max(1, (working.width * 0.75f).roundToInt()))
                } else {
                    1
                }
                val nextHeight = if (working.height > 1) {
                    min(working.height - 1, max(1, (working.height * 0.75f).roundToInt()))
                } else {
                    1
                }
                val reduced = Bitmap.createScaledBitmap(
                    working,
                    nextWidth,
                    nextHeight,
                    true,
                )
                if (working !== bitmap) working.recycle()
                working = reduced
            }
        } finally {
            if (working !== bitmap && !working.isRecycled) working.recycle()
        }
    }

    private fun looksLikeSecureWindow(bitmap: Bitmap): Boolean {
        val xSamples = min(16, bitmap.width)
        val ySamples = min(16, bitmap.height)
        var blockedSamples = 0
        val totalSamples = xSamples * ySamples
        for (yIndex in 0 until ySamples) {
            val y = yIndex * (bitmap.height - 1) / max(1, ySamples - 1)
            for (xIndex in 0 until xSamples) {
                val x = xIndex * (bitmap.width - 1) / max(1, xSamples - 1)
                val pixel = bitmap.getPixel(x, y)
                if (
                    Color.alpha(pixel) <= 4 ||
                    (Color.red(pixel) <= 4 && Color.green(pixel) <= 4 && Color.blue(pixel) <= 4)
                ) {
                    blockedSamples += 1
                }
            }
        }
        // FLAG_SECURE content is blacked out, while system bars may remain visible.
        return blockedSamples.toFloat() / totalSamples >= 0.90f
    }

    private fun Bitmap.scaledWithin(maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = min(1f, min(maxWidth.toFloat() / width, maxHeight.toFloat() / height))
        if (scale == 1f) return this
        return Bitmap.createScaledBitmap(
            this,
            max(1, (width * scale).roundToInt()),
            max(1, (height * scale).roundToInt()),
            true,
        )
    }
}
