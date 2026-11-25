package com.kokouliss.vaultanything.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.kokouliss.vaultanything.core.ui.R

/** Material Symbols (outlined) shipped as vector drawables in this module. */
object AppIcons {
    val Add = R.drawable.core_ui_ic_add
    val ArrowBack = R.drawable.core_ui_ic_arrow_back
    val Camera = R.drawable.core_ui_ic_photo_camera
    val Check = R.drawable.core_ui_ic_check
    val Close = R.drawable.core_ui_ic_close
    val CloudOff = R.drawable.core_ui_ic_cloud_off
    val ContentCopy = R.drawable.core_ui_ic_content_copy
    val Delete = R.drawable.core_ui_ic_delete
    val Edit = R.drawable.core_ui_ic_edit
    val Event = R.drawable.core_ui_ic_event
    val Group = R.drawable.core_ui_ic_group
    val GroupAdd = R.drawable.core_ui_ic_group_add
    val Image = R.drawable.core_ui_ic_image
    val Link = R.drawable.core_ui_ic_link
    val Logout = R.drawable.core_ui_ic_logout
    val Mail = R.drawable.core_ui_ic_mail
    val MoreVert = R.drawable.core_ui_ic_more_vert
    val OpenInNew = R.drawable.core_ui_ic_open_in_new
    val PersonAdd = R.drawable.core_ui_ic_person_add
    val QrCodeScanner = R.drawable.core_ui_ic_qr_code_scanner
    val Refresh = R.drawable.core_ui_ic_refresh
    val Remove = R.drawable.core_ui_ic_remove
    val Search = R.drawable.core_ui_ic_search
    val Send = R.drawable.core_ui_ic_send
    val Settings = R.drawable.core_ui_ic_settings
    val PictureAsPdf = R.drawable.core_ui_ic_picture_as_pdf
    val Share = R.drawable.core_ui_ic_share
    val Undo = R.drawable.core_ui_ic_undo
    val Star = R.drawable.core_ui_ic_star
    val StarOutline = R.drawable.core_ui_ic_star_outline
    val Document = R.drawable.core_ui_ic_description
    val Print = R.drawable.core_ui_ic_print
    val AttachFile = R.drawable.core_ui_ic_attach_file
    val Folder = R.drawable.core_ui_ic_folder
    val Person = R.drawable.core_ui_ic_person
    val Lock = R.drawable.core_ui_ic_lock
    val LockOpen = R.drawable.core_ui_ic_lock_open
    val History = R.drawable.core_ui_ic_history
}

/** Pass null as [contentDescription] only when the icon sits next to text that says the same. */
@Composable
fun AppIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painter = painterResource(icon), contentDescription = contentDescription, modifier = modifier, tint = tint)
}
