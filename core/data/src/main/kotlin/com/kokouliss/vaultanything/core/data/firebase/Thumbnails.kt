package com.kokouliss.vaultanything.core.data.firebase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/** A small picture of a document, kept inside its Firestore record so lists show it without a download. */
internal class Thumbnails @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Blocking. Call it on an IO dispatcher. A JPEG of a picture, or of the first page of a PDF; null
     * for any other kind of file, and for a file that cannot be read or is locked with a password.
     */
    fun of(uri: Uri, mimeType: String?): ByteArray? = try {
        when {
            mimeType?.startsWith("image/") == true -> decodeImage(uri)
            mimeType == "application/pdf" -> renderFirstPage(uri)
            else -> null
        }?.let { bitmap ->
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }.toByteArray()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // ImageDecoder applies the camera's rotation, so portrait shots stay upright.
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = fitWithin(info.size.width, info.size.height, MAX_PX)
            decoder.setTargetSize(width, height)
        }
    }

    private fun renderFirstPage(uri: Uri): Bitmap? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val (width, height) = fitWithin(page.width, page.height, MAX_PX)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        // A page is drawn over whatever is in the bitmap, so start from paper.
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private companion object {
        const val MAX_PX = 240
        const val QUALITY = 70
    }
}

/** Scales [width] x [height] down so the longer side is at most [maxPx]. Never scales up. */
internal fun fitWithin(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= maxPx) return width to height
    val scale = maxPx.toFloat() / longest
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}

private val UNSAFE_IN_FILE_NAME = Regex("[/\\\\\u0000]")

/** The name a downloaded file is kept under: a plain file name, never a path. */
internal fun safeFileName(name: String?): String =
    name?.replace(UNSAFE_IN_FILE_NAME, "_")?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "file"
