package com.aistudio.aegis.virtualcore

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Representa un nodo (archivo o directorio) dentro del sandbox virtual (/files/virtual_env/<packageName>/).
 */
data class VirtualFileNode(
    val name: String,
    val relativePath: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val formattedSize: String,
    val lastModifiedMs: Long,
    val formattedDate: String,
    val children: List<VirtualFileNode> = emptyList(),
    val isTextPreviewable: Boolean = false,
    val previewSnippet: String? = null
)

/**
 * Auditoría completa de almacenamiento para una aplicación clonada.
 */
data class VirtualStorageAudit(
    val packageName: String,
    val sandboxRootPath: String,
    val rootNodes: List<VirtualFileNode>,
    val totalSizeBytes: Long,
    val formattedTotalSize: String,
    val totalFilesCount: Int,
    val totalDirsCount: Int,
    val timestampMs: Long = System.currentTimeMillis()
)

object VirtualFileUtils {
    private val textExtensions = setOf(
        "xml", "json", "txt", "log", "properties", "conf", "cfg", "ini", "yaml", "yml",
        "csv", "tsv", "sh", "prop", "env", "md", "policy", "rules"
    )

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    fun formatDate(timestampMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }

    fun isTextFile(name: String, sizeBytes: Long): Boolean {
        if (sizeBytes > 256 * 1024) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in textExtensions || name.startsWith(".") || name.contains("prefs", ignoreCase = true)
    }
}
