package com.kokouliss.vaultanything.core.ui.component

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kokouliss.vaultanything.core.domain.model.Document
import com.kokouliss.vaultanything.core.domain.model.DocumentCategory
import com.kokouliss.vaultanything.core.domain.model.DocumentKind
import com.kokouliss.vaultanything.core.domain.model.ImageData
import com.kokouliss.vaultanything.core.ui.R

/** The name of a category as the screens show it. */
val DocumentCategory.label: Int
    @StringRes get() = when (this) {
        DocumentCategory.IDENTITY -> R.string.core_ui_category_identity
        DocumentCategory.HEALTH -> R.string.core_ui_category_health
        DocumentCategory.HOME -> R.string.core_ui_category_home
        DocumentCategory.CAR -> R.string.core_ui_category_car
        DocumentCategory.SCHOOL -> R.string.core_ui_category_school
        DocumentCategory.FINANCE -> R.string.core_ui_category_finance
        DocumentCategory.OTHER -> R.string.core_ui_category_other
    }

/** The icon of a document without a picture of its own. */
val DocumentKind.icon: Int
    @DrawableRes get() = when (this) {
        DocumentKind.FILE -> AppIcons.Document
        DocumentKind.IMAGE -> AppIcons.Image
        DocumentKind.LINK -> AppIcons.Link
    }

/** Decoded once per picture. Thumbnails are a few kilobytes, so decoding them here is quicker than a round trip. */
@Composable
fun rememberImageBitmap(image: ImageData?): ImageBitmap? =
    remember(image) { image?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)?.asImageBitmap() } }

/** The small picture of a document, or the icon of its kind. Decoration: the title next to it says what it is. */
@Composable
fun DocumentThumbnail(document: Document, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val bitmap = rememberImageBitmap(document.thumbnail)
    Box(
        modifier.size(size).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            AppIcon(document.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Expires in 12 days", "Expires today" or "Expired 3 days ago". */
@Composable
fun expiryLabel(daysLeft: Long): String = when {
    daysLeft > 0 -> pluralStringResource(R.plurals.core_ui_expires_in, daysLeft.toInt(), daysLeft.toInt())
    daysLeft == 0L -> stringResource(R.string.core_ui_expires_today)
    else -> pluralStringResource(R.plurals.core_ui_expired_ago, (-daysLeft).toInt(), (-daysLeft).toInt())
}
