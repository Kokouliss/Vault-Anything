package com.kokouliss.vaultanything.core.data.firebase

import android.content.Context
import android.net.Uri
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kokouliss.vaultanything.core.domain.model.MAX_TEXT_LENGTH
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

/**
 * The words on a picture or a PDF, read on the phone, so search finds a scanned paper by what it
 * says. The result is kept for search only and never shown.
 */
// shortcut: the model reads Latin letters and numbers, so Greek words come out wrong. For Greek,
// send the pages to a cloud reader from firebase/functions instead.
internal class TextReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Call it off the main thread: the PDF pages are drawn before they are read. Null for a file
     * that is not a picture or a PDF, cannot be read, or has no words on it.
     */
    suspend fun of(uri: Uri, mimeType: String?): String? {
        val pages = try {
            when {
                mimeType?.startsWith("image/") == true -> listOf(InputImage.fromFilePath(context, uri))
                mimeType == "application/pdf" -> context.renderPdfPages(uri, MAX_PAGES, PAGE_PX).map { InputImage.fromBitmap(it, 0) }
                else -> return null
            }
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            pages.map { recognizer.process(it).await().text.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .take(MAX_TEXT_LENGTH)
                .takeIf { it.isNotEmpty() }
        } catch (e: MlKitException) {
            null
        } finally {
            recognizer.close()
        }
    }

    private companion object {
        /** Enough for a contract's first pages; a longer PDF is found by its title and tags. */
        const val MAX_PAGES = 5

        /** The longer side of a drawn page. Letters on an A4 need about this many pixels to be read well. */
        const val PAGE_PX = 1600
    }
}
