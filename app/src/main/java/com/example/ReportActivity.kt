package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AegisBackground
import com.example.ui.theme.AegisError
import com.example.ui.theme.AegisOnSurface
import com.example.ui.theme.AegisOnSurfaceVariant
import com.example.ui.theme.AegisPrimary
import com.example.ui.theme.AegisSuccess
import com.example.ui.theme.AegisSurface
import com.example.ui.theme.AegisTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Informe de fallos por clon (engine v2.3). Vive en el PROCESO PRINCIPAL del host:
 * sobrevive a la muerte del proceso guest y lee el historial de intentos persistido
 * en files/virtual_env/<pkg>/reports/ (escrito por GuestLaunchTracker).
 *
 * Cada intento: línea de tiempo ✔/✘/no verificado arriba, detalles expandibles y
 * botones "Copiar informe" (texto plano), "Compartir" y "Guardar archivo".
 * Sin contenido personal: el informe solo trae rutas de motor, hashes y excepciones.
 */
class ReportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "extra_report_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        setContent {
            AegisTheme {
                ReportScreen(pkg = pkg)
            }
        }
    }
}

private data class ReportSection(val title: String, val body: String)

@Composable
fun ReportScreen(pkg: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val reportsDir = remember(pkg) { File(context.filesDir, "virtual_env/$pkg/reports") }
    var reloadKey by remember { mutableStateOf(0) }
    val files = remember(pkg, reloadKey) {
        reportsDir.listFiles { f -> f.name.startsWith("intento_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }.orEmpty()
    }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    if (selectedFile == null && files.isNotEmpty()) selectedFile = files.first()

    val reportText = remember(selectedFile, reloadKey) {
        selectedFile?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
    }
    val (headerLines, sections) = remember(reportText) { parseReport(reportText) }
    val verdictLine = headerLines.firstOrNull { it.startsWith("resultado:") }
    val failedVerdict = verdictLine != null && (
        verdictLine.contains("FALLO", true) ||
            verdictLine.contains("CRASH", true) ||
            verdictLine.contains("PANTALLA EN BLANCO", true) ||
            verdictLine.contains("TRANSMUTACIÓN", true)
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AegisBackground)
            .statusBarsPadding()
    ) {
        // Cabecera
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(
                text = "Informe de fallos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AegisOnSurface
            )
            Text(
                text = if (pkg.isBlank()) "clon no indicado" else pkg,
                style = MaterialTheme.typography.bodySmall,
                color = AegisOnSurfaceVariant
            )
        }

        if (files.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(28.dp)
            ) {
                Spacer(Modifier.height(60.dp))
                Text(
                    text = "Sin informes todavía",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AegisOnSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Cuando un clon no abra, se cierre o se quede en blanco, el motor generará aquí el informe del intento: línea de tiempo, excepción completa con sus Caused by, APKs con SHA-256, classloader y logs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AegisOnSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Historial de intentos (cada intento queda guardado en el clon)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                files.take(10).forEach { f ->
                    FilterChip(
                        selected = f == selectedFile,
                        onClick = { selectedFile = f },
                        label = { Text(fileLabel(f.name), fontSize = 11.sp) }
                    )
                }
            }

            // Contenido del informe
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AegisSurface)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            headerLines.forEach { line ->
                                val isResult = line.startsWith("resultado:")
                                val color = when {
                                    isResult && failedVerdict -> AegisError
                                    isResult -> AegisSuccess
                                    else -> AegisOnSurface
                                }
                                Text(
                                    text = line,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isResult) FontWeight.Bold else FontWeight.Normal,
                                    color = color
                                )
                            }
                        }
                    }
                }
                items(sections) { section ->
                    var expanded by remember(section.title, reportText) {
                        mutableStateOf(section.title.startsWith("TIMELINE") || section.title.startsWith("EXCEPCIONES"))
                    }
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = AegisSurface)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = !expanded }
                            ) {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AegisPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (expanded) "▲" else "▼",
                                    color = AegisOnSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                            if (expanded) {
                                Spacer(Modifier.height(6.dp))
                                SelectionContainer {
                                    Column {
                                        section.body.lines().forEach { line ->
                                            val mark = when {
                                                line.contains("✘") || line.contains("PRIMER FALLO") -> AegisError
                                                line.contains("✔") -> AegisSuccess
                                                line.contains("◦") -> AegisOnSurfaceVariant
                                                else -> AegisOnSurface
                                            }
                                            Text(
                                                text = line,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = mark
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Acciones sobre el informe actual
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(reportText))
                        Toast.makeText(context, "Informe copiado (texto plano)", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Copiar informe", maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Aegis · informe ${selectedFile?.name ?: pkg}")
                            putExtra(Intent.EXTRA_TEXT, reportText)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(send, "Compartir informe"))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Compartir", maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        val name = selectedFile?.name ?: "aegis_informe.txt"
                        val where = saveReportToFile(context, name, reportText)
                        Toast.makeText(
                            context,
                            if (where != null) "Guardado en $where" else "No se pudo guardar el archivo",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Guardar", maxLines = 1) }
            }
        }
    }
}

/** "intento_20261105_022331_p12345.txt" -> "05 nov 02:23 · PID 12345". */
private fun fileLabel(fileName: String): String {
    return runCatching {
        val body = fileName.removePrefix("intento_").removeSuffix(".txt")
        val parts = body.split("_p")
        val stamp = parts.getOrNull(0) ?: return fileName
        val pid = parts.getOrNull(1) ?: "?"
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)
        val out = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(date ?: Date())
        "$out · PID ${pid.removeSuffix(".txt")}"
    }.getOrDefault(fileName)
}

/** Divide el informe en (cabecera, secciones "== TITULO =="). Tolerante a cambios. */
private fun parseReport(text: String): Pair<List<String>, List<ReportSection>> {
    val headerLines = mutableListOf<String>()
    val sections = mutableListOf<ReportSection>()
    var title: String? = null
    val body = StringBuilder()

    fun close() {
        val t = title
        if (t == null) {
            headerLines.addAll(body.lines().filter { it.isNotBlank() && !it.startsWith("===") })
        } else {
            sections.add(ReportSection(t, body.toString().trimEnd()))
        }
        body.clear()
    }

    text.lines().forEach { raw ->
        val line = raw.trimEnd()
        if (line.startsWith("== ") && line.endsWith(" ==") && line.length > 6) {
            close()
            title = line.removePrefix("== ").removeSuffix(" ==")
        } else {
            body.appendLine(line)
        }
    }
    close()
    return headerLines to sections
}

/** "Guardar archivo": MediaStore → Descargas (API 29+) o dir externo de la app. */
private fun saveReportToFile(context: Context, fileName: String, text: String): String? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Descargas/$fileName"
    } else {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, fileName)
        file.writeText(text)
        file.absolutePath
    }
}.getOrElse { null }
