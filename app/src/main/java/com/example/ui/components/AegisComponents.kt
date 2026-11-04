package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aistudio.aegis.virtualcore.GuestMapsReport
import com.example.R
import com.example.model.ClonedAppUiItem
import com.example.model.InstalledAppItem
import com.example.ui.theme.AegisCodeBg
import com.example.ui.theme.AegisDivider
import com.example.ui.theme.AegisError
import com.example.ui.theme.AegisErrorContainer
import com.example.ui.theme.AegisErrorText
import com.example.ui.theme.AegisIconMarkerColors
import com.example.ui.theme.AegisOnPrimary
import com.example.ui.theme.AegisOnSurface
import com.example.ui.theme.AegisOnSurfaceVariant
import com.example.ui.theme.AegisOutline
import com.example.ui.theme.AegisPrimary
import com.example.ui.theme.AegisPrimaryText
import com.example.ui.theme.AegisSuccess
import com.example.ui.theme.AegisSuccessContainer
import com.example.ui.theme.AegisSuccessText
import com.example.ui.theme.AegisSurface

/**
 * Cabecera azul con esquinas inferiores redondeadas (32 dp) según la Sección 5.1, 5.2, 5.3 y 5.4.
 */
@Composable
fun HeaderBar(
    title: String,
    modifier: Modifier = Modifier,
    topTag: String? = null,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    bottomExtraSpaceDp: Dp = 0.dp
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(AegisPrimary)
            .padding(
                top = statusBarTop + 16.dp,
                start = 20.dp,
                end = 20.dp,
                bottom = 24.dp + bottomExtraSpaceDp
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (onBackClick != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .testTag("header_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = AegisOnPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (!topTag.isNullOrBlank()) {
                            Text(
                                text = topTag,
                                color = AegisOnPrimary.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = AegisOnPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                if (!topTag.isNullOrBlank()) {
                    Text(
                        text = topTag,
                        color = AegisOnPrimary.copy(alpha = 0.82f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = AegisOnPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AegisOnPrimary.copy(alpha = 0.88f)
                    )
                }
            }

            if (searchQuery != null && onSearchQueryChange != null) {
                Spacer(modifier = Modifier.height(16.dp))
                SearchPillField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange
                )
            }
        }
    }
}

@Composable
private fun SearchPillField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AegisSurface,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("search_app_input")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = AegisOnSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f)
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AegisOnSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AegisOnSurface),
                    cursorBrush = SolidColor(AegisPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Limpiar búsqueda",
                        tint = AegisOnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Botón tipo píldora reutilizable (Sección 5.1 y 5.7):
 * Altura 54 dp principal, 46 dp dentro de tarjeta; primario relleno o secundario con borde.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSecondary: Boolean = false,
    isInsideCard: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    testTag: String = "pill_button"
) {
    val height = if (isInsideCard) 46.dp else 54.dp

    if (isSecondary) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.5.dp, if (enabled) AegisPrimary else AegisDivider),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AegisPrimaryText,
                disabledContentColor = AegisOnSurfaceVariant.copy(alpha = 0.5f)
            ),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = modifier
                .height(height)
                .testTag(testTag)
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = AegisPrimary,
                contentColor = AegisOnPrimary,
                disabledContainerColor = AegisPrimary.copy(alpha = 0.38f),
                disabledContentColor = AegisOnPrimary.copy(alpha = 0.8f)
            ),
            contentPadding = PaddingValues(horizontal = 22.dp),
            modifier = modifier
                .height(height)
                .testTag(testTag)
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Renderiza el icono de una app con radio del 30% de su lado (64 dp -> 19.2 dp, 56 dp -> 16.8 dp).
 */
@Composable
fun RoundedAppIcon(
    iconBitmap: ImageBitmap?,
    appName: String,
    fallbackColorIndex: Int,
    sizeDp: Dp,
    modifier: Modifier = Modifier
) {
    val cornerRadius = sizeDp * 0.30f
    val shape = RoundedCornerShape(cornerRadius)
    val bgColor = AegisIconMarkerColors[fallbackColorIndex % AegisIconMarkerColors.size]

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp)
            .clip(shape)
            .background(if (iconBitmap == null) bgColor else Color.Transparent)
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = appName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            )
        } else {
            val initials = appName.trim().take(2).uppercase().ifBlank { "AP" }
            Text(
                text = initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (sizeDp >= 60.dp) 20.sp else 17.sp
            )
        }
    }
}

/**
 * Azulejo de app clonada en la cuadrícula de Inicio (64 dp de icono, toque simple y pulsación larga con menú).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClonedAppTile(
    item: ClonedAppUiItem,
    onLaunchClick: () -> Unit,
    onRerunClick: () -> Unit,
    onViewResultClick: () -> Unit,
    onExploreFilesClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val statusReport = item.info.lastReport

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onLaunchClick,
                    onLongClick = { showMenu = true }
                )
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .testTag("cloned_tile_${item.info.packageName}")
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                RoundedAppIcon(
                    iconBitmap = item.iconBitmap,
                    appName = item.info.appName,
                    fallbackColorIndex = item.fallbackColorIndex,
                    sizeDp = 64.dp
                )
                if (statusReport != null) {
                    val badgeColor = if (statusReport.ok) AegisSuccess else AegisError
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(AegisSurface)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    ) {
                        Icon(
                            imageVector = if (statusReport.ok) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = if (statusReport.ok) "Estado verde" else "Estado rojo",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.info.appName,
                style = MaterialTheme.typography.labelLarge,
                color = AegisOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = item.info.assignedProcess,
                style = MaterialTheme.typography.labelSmall,
                color = AegisOnSurfaceVariant,
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_rerun)) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onRerunClick()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_view_result)) },
                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onViewResultClick()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_explore_files)) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onExploreFilesClick()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.menu_delete_clone),
                        color = AegisError
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = AegisError
                    )
                },
                onClick = {
                    showMenu = false
                    onDeleteClick()
                }
            )
        }
    }
}

/**
 * Botón "Añadir" con borde punteado azul (#2563EB) y signo "+" al final de la cuadrícula de 3 columnas (Sección 5.2).
 */
@Composable
fun DashedAddAppTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cornerRadiusDp = 64.dp * 0.30f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .testTag("grid_dashed_add_button")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(64.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radiusPx = cornerRadiusDp.toPx()
                drawRoundRect(
                    color = AegisOutline,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    )
                )
            }
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.cd_add_app),
                tint = AegisPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tile_add),
            style = MaterialTheme.typography.labelLarge,
            color = AegisPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Cuadrícula de 3 columnas para las apps clonadas en la Pantalla 1 (Inicio).
 */
@Composable
fun ClonedAppGrid(
    clonedApps: List<ClonedAppUiItem>,
    onLaunchApp: (ClonedAppUiItem) -> Unit,
    onRerunApp: (ClonedAppUiItem) -> Unit,
    onViewResult: (ClonedAppUiItem) -> Unit,
    onExploreFiles: (ClonedAppUiItem) -> Unit,
    onDeleteApp: (ClonedAppUiItem) -> Unit,
    onAddAppClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Construir elementos de la cuadrícula: N apps clonadas + 1 azulejo final "Añadir"
    val totalCount = clonedApps.size + 1
    val rows = (totalCount + 2) / 3

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        for (rowIdx in 0 until rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                for (colIdx in 0 until 3) {
                    val itemIdx = rowIdx * 3 + colIdx
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        when {
                            itemIdx < clonedApps.size -> {
                                val app = clonedApps[itemIdx]
                                ClonedAppTile(
                                    item = app,
                                    onLaunchClick = { onLaunchApp(app) },
                                    onRerunClick = { onRerunApp(app) },
                                    onViewResultClick = { onViewResult(app) },
                                    onExploreFilesClick = { onExploreFiles(app) },
                                    onDeleteClick = { onDeleteApp(app) }
                                )
                            }
                            itemIdx == clonedApps.size -> {
                                DashedAddAppTile(onClick = onAddAppClick)
                            }
                            else -> {
                                Spacer(modifier = Modifier.height(1.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Azulejo de app instalada en la cuadrícula de 4 columnas de la Pantalla 2 (Añadir app),
 * con icono de 56 dp e insignia verde con check cuando está seleccionada (Sección 5.3).
 */
@Composable
fun SelectableAppTile(
    item: InstalledAppItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .background(
                if (isSelected) AegisPrimary.copy(alpha = 0.08f) else Color.Transparent
            )
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .testTag("select_app_${item.packageName}")
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            RoundedAppIcon(
                iconBitmap = item.iconBitmap,
                appName = item.appName,
                fallbackColorIndex = item.fallbackColorIndex,
                sizeDp = 56.dp,
                modifier = if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = AegisSuccess,
                        shape = RoundedCornerShape(56.dp * 0.30f)
                    )
                } else {
                    Modifier
                }
            )
            if (isSelected) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(AegisSurface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(AegisSuccess)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Seleccionada",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.appName,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) AegisPrimaryText else AegisOnSurface,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (item.isGame) {
            Text(
                text = "JUEGO",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = AegisPrimary,
                maxLines = 1
            )
        } else if (item.isAlreadyCloned) {
            Text(
                text = "Clonada",
                fontSize = 9.sp,
                color = AegisSuccessText,
                maxLines = 1
            )
        }
    }
}

/**
 * Tarjeta del Semáforo (Sección 5.4 y 5.5):
 * Círculo de 80 dp con check (VERDE) o X (ROJO), etiqueta "VERDE" o "ROJO", frase explicativa y dos botones de acción.
 */
@Composable
fun StatusCard(
    report: GuestMapsReport,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isGreen = report.ok
    val circleBg = if (isGreen) AegisSuccessContainer else AegisErrorContainer
    val iconColor = if (isGreen) AegisSuccess else AegisError
    val statusTextColor = if (isGreen) AegisSuccessText else AegisErrorText
    val statusTitle = if (isGreen) {
        stringResource(R.string.status_green_title)
    } else {
        stringResource(R.string.status_red_title)
    }
    val statusSubtitle = if (isGreen) {
        stringResource(R.string.status_green_subtitle)
    } else {
        stringResource(R.string.status_red_subtitle)
    }
    val semanticDesc = if (isGreen) {
        stringResource(R.string.cd_status_green)
    } else {
        stringResource(R.string.cd_status_red)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AegisSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .testTag("result_status_card")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Círculo de 80 dp con icono check o X
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(circleBg)
                    .border(2.5.dp, iconColor.copy(alpha = 0.35f), CircleShape)
                    .semantics { contentDescription = semanticDesc }
            ) {
                Icon(
                    imageVector = if (isGreen) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Texto "VERDE" o "ROJO"
            Surface(
                color = circleBg,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = statusTitle,
                    color = statusTextColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("semaphore_status_label")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = statusSubtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = AegisOnSurface,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Dos botones píldora de 46 dp dentro de la tarjeta
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PillButton(
                    text = if (isGreen) {
                        stringResource(R.string.btn_copy_json)
                    } else {
                        stringResource(R.string.btn_retry)
                    },
                    onClick = onPrimaryAction,
                    isInsideCard = true,
                    isSecondary = false,
                    leadingIcon = {
                        Icon(
                            imageVector = if (isGreen) Icons.Default.ContentCopy else Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    testTag = if (isGreen) "btn_copy_json" else "btn_retry_maps",
                    modifier = Modifier.weight(1f)
                )

                PillButton(
                    text = if (isGreen) {
                        stringResource(R.string.btn_rerun)
                    } else {
                        stringResource(R.string.btn_copy_log)
                    },
                    onClick = onSecondaryAction,
                    isInsideCard = true,
                    isSecondary = true,
                    leadingIcon = {
                        Icon(
                            imageVector = if (isGreen) Icons.Default.Refresh else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    testTag = if (isGreen) "btn_rerun_clone" else "btn_copy_log",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Tarjeta "Librerías (N)" (Sección 5.4):
 * Muestra las rutas completas en monoespaciada (11 sp), con ajuste de línea.
 * Tocar una librería la copia al portapapeles.
 */
@Composable
fun LibsCard(
    libs: List<String>,
    onCopySingleLib: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAll by remember { mutableStateOf(false) }
    val previewLimit = 18
    val displayedLibs = if (showAll || libs.size <= previewLimit) libs else libs.take(previewLimit)

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AegisSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("libs_inventory_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Librerías (${libs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = AegisOnSurface
                )
                Text(
                    text = "Toca una ruta para copiar",
                    style = MaterialTheme.typography.labelMedium,
                    color = AegisOnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                displayedLibs.forEachIndexed { index, libPath ->
                    val isAppSpecific = libPath.contains("/data/") || libPath.contains("libaegis_guest.so")
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isAppSpecific) AegisPrimary.copy(alpha = 0.07f) else AegisCodeBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onCopySingleLib(libPath) }
                            .testTag("lib_item_$index")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = libPath,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = if (isAppSpecific) AegisPrimaryText else AegisOnSurface,
                                fontWeight = if (isAppSpecific) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copiar ruta de librería",
                                tint = AegisOnSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            if (libs.size > previewLimit) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showAll = !showAll },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, AegisDivider),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (showAll) {
                            "Mostrar menos"
                        } else {
                            "Ver las ${libs.size} librerías completas"
                        },
                        color = AegisPrimaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Tarjeta "Log" para el Resultado Rojo (Sección 5.5):
 * Muestra las últimas líneas del logcat filtrado por etiqueta Aegis / AegisGuest.
 */
@Composable
fun LogCard(
    logs: List<String>,
    onCopyLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AegisSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("logcat_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.card_log_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AegisOnSurface
                )
                Text(
                    text = "Aegis / AegisGuest",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = AegisOnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0F172A),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCopyLogs() }
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    val effectiveLogs = if (logs.isNotEmpty()) {
                        logs
                    } else {
                        listOf("E/AegisGuest: fopen(\"/proc/self/maps\", \"r\") failed -> EACCES (Permission denied)")
                    }
                    effectiveLogs.forEach { line ->
                        val isErrorLine = line.startsWith("E/") || line.contains("ERROR") || line.contains("EACCES")
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = if (isErrorLine) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta "Detalles" (Sección 5.4 y 5.5):
 * Muestra PID, paquete, proceso (por ejemplo `:p0`), error ("ninguno" o motivo).
 * Puede expandirse con la lista completa de rutas / JSON crudo.
 */
@Composable
fun DetailsCard(
    report: GuestMapsReport,
    abiInfo: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AegisSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("details_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    text = stringResource(R.string.card_details_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AegisOnSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) "Ocultar rutas" else "Expandir",
                        style = MaterialTheme.typography.labelMedium,
                        color = AegisPrimaryText
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Contraer detalles" else "Expandir detalles",
                        tint = AegisPrimaryText
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            DetailRowItem(label = "PID", value = if (report.pid > 0) report.pid.toString() else "N/A", isMono = true)
            HorizontalDivider(color = AegisDivider.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))

            DetailRowItem(label = "Paquete", value = report.packageName, isMono = true)
            HorizontalDivider(color = AegisDivider.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))

            DetailRowItem(label = "Proceso", value = report.processName, isMono = true)
            HorizontalDivider(color = AegisDivider.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))

            DetailRowItem(label = "ABI contenedor", value = abiInfo, isMono = true)
            HorizontalDivider(color = AegisDivider.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 8.dp))

            DetailRowItem(
                label = "Error",
                value = report.error ?: "ninguno",
                isMono = true,
                valueColor = if (report.error == null) AegisSuccessText else AegisErrorText
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Contrato JSON (/proc/self/maps):",
                        style = MaterialTheme.typography.labelMedium,
                        color = AegisOnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AegisCodeBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = report.toStrictContractJson(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = AegisOnSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRowItem(
    label: String,
    value: String,
    isMono: Boolean = false,
    valueColor: Color = AegisOnSurface
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AegisOnSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default,
            fontSize = if (isMono) 12.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Diálogo modal de estados de clonado y arranque (Sección 5.6):
 * "Instalando en el contenedor…", "Iniciando proceso…", "Leyendo librerías…",
 * o estado de error legible con botón "Ver log".
 */
@Composable
fun CloneProgressDialog(
    appName: String,
    stepMessage: String,
    isError: Boolean = false,
    errorCause: String? = null,
    errorLogs: List<String> = emptyList(),
    onDismiss: () -> Unit = {}
) {
    var showErrorLog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (isError) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = isError,
            dismissOnClickOutside = isError
        )
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = AegisSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("clone_progress_dialog")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                if (!isError) {
                    CircularProgressIndicator(
                        color = AegisPrimary,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = AegisOnSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stepMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AegisPrimaryText,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AegisErrorContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Error de clonado",
                            tint = AegisError,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No se pudo iniciar $appName",
                        style = MaterialTheme.typography.titleMedium,
                        color = AegisOnSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorCause ?: "El proceso se cerró al arrancar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AegisErrorText,
                        textAlign = TextAlign.Center
                    )

                    if (showErrorLog && errorLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AegisCodeBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorLogs.joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = AegisOnSurface,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (errorLogs.isNotEmpty()) {
                            PillButton(
                                text = if (showErrorLog) "Ocultar log" else "Ver log",
                                onClick = { showErrorLog = !showErrorLog },
                                isSecondary = true,
                                isInsideCard = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        PillButton(
                            text = "Cerrar",
                            onClick = onDismiss,
                            isInsideCard = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
