package com.openminis.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * [fix/chat-bitmap-draw-limit] Downsampled decode for chat UI thumbnails.
 *
 * Crash signature (beta.106, 2026-09-06): "Canvas: trying to draw too
 * large(163840000 bytes) bitmap" — a desktop-UA full_page browser screenshot
 * saved at 5120×8000 was decoded at FULL physical resolution by the chat
 * card/preview (bare `BitmapFactory.decodeFile`), then drawn into a small
 * `Image(bitmap=)` composable. Recording the 160MB ARGB bitmap into the
 * hardware-accelerated display list exceeds Android's ~100MB canvas bitmap
 * draw limit and throws unconditionally — regardless of the on-screen size
 * the image is displayed at.
 *
 * The screenshot side already caps its capture bitmap
 * ([fix/fullpage-bitmap-byte-cap] MAX_CAPTURE_BITMAP_BYTES = 48MB in
 * BrowserUseManager), but the saved JPEG keeps full pixel dimensions, so the
 * decode side must sample down before drawing. Mirrors the intent of
 * ImageBudget.MAX_EDGE_PX (provider payload ladder) at the render layer.
 *
 * Halving loop keeps `inSampleSize` a power of two (the only value BitmapFactory
 * honors) and stops as soon as BOTH budgets are satisfied:
 *  - longest edge ≤ [MAX_DECODED_EDGE_PX] (2048 → plenty for a thumbnail),
 *  - ARGB_8888 byte size ≤ [MAX_DECODED_BITMAP_BYTES] (well under the
 *    ~100MB hardware canvas draw limit even if later drawn full-screen).
 *
 * 5120×8000 source → sample=4 → 1280×2000 ≈ 10MB. A 12MP photo (4000×3000)
 * → sample=2 → 2000×1500 ≈ 12MB. Normal screenshots (~1440×2560) → sample=2
 * → ~7MB. Small images decode 1:1, unchanged.
 */
private const val MAX_DECODED_EDGE_PX = 2048
private const val MAX_DECODED_BITMAP_BYTES = 24L * 1024L * 1024L

internal fun decodeScaledBitmap(
    path: String?,
    maxEdgePx: Int = MAX_DECODED_EDGE_PX,
    maxBytes: Long = MAX_DECODED_BITMAP_BYTES,
): Bitmap? {
    if (path == null || !File(path).exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var w = bounds.outWidth
        var h = bounds.outHeight
        var sample = 1
        while (w >= 2 && h >= 2 &&
            (w > maxEdgePx || h > maxEdgePx || w.toLong() * h * 4L > maxBytes)
        ) {
            w /= 2; h /= 2; sample *= 2
        }
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()
}
