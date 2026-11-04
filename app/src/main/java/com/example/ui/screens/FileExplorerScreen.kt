package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.aegis.virtualcore.VirtualFileNode
import com.aistudio.aegis.virtualcore.VirtualStorageAudit
import com.example.R
import com.example.model.ClonedAppUiItem
import com.example.ui.components.HeaderBar
import com.example.ui.components.PillButton
import com.example.ui.components.RoundedAppIcon
import com.example.ui.theme.AegisBackground
import com.example.ui.theme.AegisDivider
import com.example.ui.theme.AegisError
import com.example.ui.theme.AegisOnPrimary
import com.example.ui.theme.AegisOnSurface
import com.example.ui.theme.AegisOnSurfaceVariant
import com.example.ui.theme.AegisPrimary
import com.example.ui.theme.AegisPrimaryText
import com.example.ui.theme.AegisSuccess
import com.example.ui.theme.AegisSurface
import com.example.viewmodel.AegisViewModel

@Composable
fun FileExplorerScreen(
    clonedApp: ClonedAppUiItem,
    viewModel: AegisViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrollState = rememberScrollState()

    val storageAudit by viewModel.currentStorageAudit.collectAsState()
    val selectedFilePreview by viewModel.selectedFileContent.collectAsState()
    val operationMessage by viewModel.fileOperationMessage.collectAsState()
    val isLoading by viewModel.isFilesLoading.collectAsState()

    // Registrar BackHandler para navegación limpia hacia atrás
    BackHandler {
        onBackClick()
    }

    LaunchedEffect(clonedApp.info.packageName) {
        viewModel.loadVirtualStorageAudit(clonedApp.info.packageName)
    }

    LaunchedEffect(operationMessage) {
        val msg = operationMessage
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearFileOperationMessage()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisBackground)
            .verticalScroll(scrollState)
            .padding(bottom = navBarsBottom + 24.dp)
            .testTag("file_explorer_screen_root")
    ) {
        HeaderBar(
            topTag = "AUDITORÍA VIRTUAL · SANDBOX VFS",
            title = stringResource(R.string.files_screen_title),
            subtitle = clonedApp.info.appName,
            onBackClick = onBackClick,
            bottomExtraSpaceDp = 38.dp
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-32).dp)
                .padding(horizontal = 20.dp)
        ) {
            // Tarjeta de Resumen del Sandbox
            StorageSummaryCard(
                clonedApp = clonedApp,
                audit = storageAudit,
                isLoading = isLoading,
                onRefresh = { viewModel.loadVirtualStorageAudit(clonedApp.info.packageName) },
                onCreateSampleFile = {
                    viewModel.createSampleVerificationFile(
                        packageName = clonedApp.info.packageName,
                        targetFolder = "shared_prefs",
                        fileName = "config.xml"
                    )
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Directorios principales del Sandbox
            Text(
                text = "ESTRUCTURA DE DIRECTORIOS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = AegisOnSurfaceVariant,
                letterSpacing = 1.1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            val rootNodes = storageAudit?.rootNodes ?: emptyList()
            if (rootNodes.isEmpty() && isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    CircularProgressIndicator(color = AegisPrimary)
                }
            } else {
                rootNodes.forEach { folderNode ->
                    FolderTreeCard(
                        folderNode = folderNode,
                        onFileClick = { fileNode ->
                            viewModel.selectFileForPreview(fileNode, clonedApp.info.packageName)
                        },
                        onDeleteFile = { fileNode ->
                            viewModel.deleteFile(clonedApp.info.packageName, fileNode.relativePath)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }

    // Modal Visor de Contenido y Verificación de Escritura
    val preview = selectedFilePreview
    if (preview != null) {
        FileContentPreviewDialog(
            fileNode = preview.first,
            content = preview.second,
            onDismiss = { viewModel.closeFilePreview() },
            onDelete = {
                viewModel.deleteFile(clonedApp.info.packageName, preview.first.relativePath)
            }
        )
    }
}

@Composable
private fun StorageSummaryCard(
    clonedApp: ClonedAppUiItem,
    audit: VirtualStorageAudit?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onCreateSampleFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AegisSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .testTag("storage_summary_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RoundedAppIcon(
                    iconBitmap = clonedApp.iconBitmap,
                    appName = clonedApp.info.appName,
                    fallbackColorIndex = clonedApp.fallbackColorIndex,
                    sizeDp = 48.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = clonedApp.info.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AegisOnSurface
                    )
                    Text(
                        text = clonedApp.info.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = AegisOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refrescar explorador",
                        tint = AegisPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = AegisDivider, thickness = 1.dp)
            Spacer(modifier = Modifier.height(14.dp))

            // Badges con métricas de almacenamiento
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricItem(
                    label = "Tamaño Total",
                    value = audit?.formattedTotalSize ?: "0 B",
                    color = AegisPrimary
                )
                MetricItem(
                    label = "Archivos",
                    value = "${audit?.totalFilesCount ?: 0}",
                    color = AegisSuccess
                )
                MetricItem(
                    label = "Carpetas",
                    value = "${audit?.totalDirsCount ?: 0}",
                    color = AegisOnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Ruta física en el host
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AegisBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Ruta real en almacenamiento host:",
                        style = MaterialTheme.typography.labelSmall,
                        color = AegisOnSurfaceVariant
                    )
                    Text(
                        text = audit?.sandboxRootPath ?: "/files/virtual_env/${clonedApp.info.packageName}/",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = AegisOnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Botón para probar la verificación de escritura
            PillButton(
                text = stringResource(R.string.files_create_sample),
                onClick = onCreateSampleFile,
                isInsideCard = true,
                isSecondary = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                testTag = "btn_create_sample_file",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AegisOnSurfaceVariant
        )
    }
}

@Composable
private fun FolderTreeCard(
    folderNode: VirtualFileNode,
    onFileClick: (VirtualFileNode) -> Unit,
    onDeleteFile: (VirtualFileNode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(folderNode.children.isNotEmpty() || folderNode.name == "files" || folderNode.name == "shared_prefs") }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AegisSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("folder_card_${folderNode.name}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AegisPrimary.copy(alpha = 0.1f))
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                        contentDescription = null,
                        tint = AegisPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = folderNode.name + "/",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AegisOnSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AegisBackground,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "${folderNode.children.size} items",
                                style = MaterialTheme.typography.labelSmall,
                                color = AegisOnSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "Tamaño: ${folderNode.formattedSize}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AegisOnSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Colapsar" else "Expandir",
                    tint = AegisOnSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                ) {
                    HorizontalDivider(color = AegisDivider, thickness = 0.8.dp)

                    if (folderNode.children.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.files_empty_dir),
                                style = MaterialTheme.typography.bodySmall,
                                color = AegisOnSurfaceVariant.copy(alpha = 0.7f),
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    } else {
                        folderNode.children.forEach { child ->
                            FileListItem(
                                node = child,
                                onClick = { onFileClick(child) },
                                onDelete = { onDeleteFile(child) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListItem(
    node: VirtualFileNode,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .testTag("file_item_${node.name}")
    ) {
        val icon = when {
            node.isDirectory -> Icons.Default.Folder
            node.name.endsWith(".xml") || node.name.endsWith(".json") -> Icons.Default.Code
            node.isTextPreviewable -> Icons.Default.Description
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (node.isDirectory) AegisPrimary else AegisPrimaryText,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = AegisOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = node.formattedSize,
                    style = MaterialTheme.typography.labelSmall,
                    color = AegisOnSurfaceVariant
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = AegisOnSurfaceVariant
                )
                Text(
                    text = node.formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = AegisOnSurfaceVariant
                )
            }
        }

        if (node.isTextPreviewable) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AegisSuccess.copy(alpha = 0.12f),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = "TEXTO",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AegisSuccess,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Eliminar archivo",
                tint = AegisError.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Diálogo modal para inspección de contenido de texto (.xml, .json, etc.) y verificación de escritura.
 */
@Composable
private fun FileContentPreviewDialog(
    fileNode: VirtualFileNode,
    content: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = AegisPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileNode.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${fileNode.formattedSize} · ${fileNode.relativePath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AegisOnSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0F172A),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Aegis file: ${fileNode.name}", content))
                        copied = true
                        Toast.makeText(context, "Contenido copiado al portapapeles", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (copied) "Copiado" else "Copiar")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDelete()
                    onDismiss()
                }
            ) {
                Text("Eliminar", color = AegisError)
            }
        }
    )
}
