package com.kakario.comicviewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 앱 전체에서 쓰는 테두리(외곽선) 아이콘 모음 — 이모지 대신. */
object AppIcons {
    val Favorite: ImageVector get() = Icons.Outlined.StarOutline
    val FavoriteOn: ImageVector get() = Icons.Filled.Star
    val Server: ImageVector get() = Icons.Outlined.Dns
    val Phone: ImageVector get() = Icons.Outlined.PhoneAndroid
    val SdCard: ImageVector get() = Icons.Outlined.SdCard
    val Folder: ImageVector get() = Icons.Outlined.Folder
    val Book: ImageVector get() = Icons.AutoMirrored.Outlined.MenuBook
    val Image: ImageVector get() = Icons.Outlined.Image
    val Reset: ImageVector get() = Icons.Outlined.RestartAlt
    val Delete: ImageVector get() = Icons.Outlined.DeleteOutline
    val Warning: ImageVector get() = Icons.Outlined.WarningAmber
    val Done: ImageVector get() = Icons.Outlined.CheckCircle
    val Up: ImageVector get() = Icons.Outlined.ExpandLess
    val Down: ImageVector get() = Icons.Outlined.ExpandMore
}

@Composable
fun AppIcon(
    icon: ImageVector,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
