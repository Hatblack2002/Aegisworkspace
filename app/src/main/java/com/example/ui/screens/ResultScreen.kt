package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.aegis.virtualcore.GuestMapsReport
import com.example.model.ClonedAppUiItem
import com.example.ui.components.DetailsCard
import com.example.ui.components.HeaderBar
import com.example.ui.components.LibsCard
import com.example.ui.components.LogCard
import com.example.ui.components.StatusCard
import com.example.ui.theme.AegisBackground
import com.example.ui.theme.AegisOnSurfaceVariant
import com.example.ui.theme.AegisPrimaryText

/**
 * Pantalla 3 y 4: Resultado verde / Resultado rojo (Sección 5.4, 5.5 y 5.7)
 * Un único composable `ResultScreen` que renderiza el estado `ok = true` (VERDE) o `ok = false` (ROJO)
 * superponiendo la tarjeta de estado sobre la cabecera azul redondeada.
 */
@Composable
fun ResultScreen(
    clonedApp: ClonedAppUiItem,
    report: GuestMapsReport,
    onBackClick: () -> Unit,
    onRerunNormal: () -> Unit,
    onSimulateRedStateToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisBackground)
            .verticalScroll(scrollState)
            .padding(bottom = navBarsBottom + 24.dp)
            .testTag("result_screen_root")
    ) {
        // Cabecera azul con espacio inferior extra para que la tarjeta de semáforo se superponga ("Sobre ella, tarjeta...")
        HeaderBar(
            topTag = "PROCESO ${report.processName.uppercase()}",
            title = clonedApp.info.appName,
            onBackClick = onBackClick,
            bottomExtraSpaceDp = 42.dp
        )

        // Contenedor principal con desplazamiento negativo superior (-36.dp) para superponer StatusCard sobre HeaderBar
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-36).dp)
                .padding(horizontal = 20.dp)
        ) {
            StatusCard(
                report = report,
                onPrimaryAction = {
                    if (report.ok) {
                        val jsonString = report.toStrictContractJson()
                        copyToClipboard(
                            context = context,
                            label = "AegisMapsJSON",
                            text = jsonString,
                            toastMessage = "JSON de /proc/self/maps copiado al portapapeles"
                        )
                    } else {
                        onRerunNormal()
                    }
                },
                onSecondaryAction = {
                    if (report.ok) {
                        onRerunNormal()
                    } else {
                        val logText = report.logs.joinToString("\n").ifBlank {
                            "E/AegisGuest(${report.pid}): ${report.error ?: "EACCES"}"
                        }
                        copyToClipboard(
                            context = context,
                            label = "AegisLogcat",
                            text = logText,
                            toastMessage = "Log de Aegis/AegisGuest copiado al portapapeles"
                        )
                    }
                }
            )

            if (report.ok) {
                // Pantalla 3: Resultado verde -> "Librerías (N)"
                LibsCard(
                    libs = report.libs,
                    onCopySingleLib = { libPath ->
                        copyToClipboard(
                            context = context,
                            label = "AegisLibPath",
                            text = libPath,
                            toastMessage = "Ruta copiada: ${libPath.substringAfterLast('/')}"
                        )
                    }
                )
            } else {
                // Pantalla 4: Resultado rojo -> Tarjeta "Log"
                LogCard(
                    logs = report.logs,
                    onCopyLogs = {
                        val logText = report.logs.joinToString("\n")
                        copyToClipboard(
                            context = context,
                            label = "AegisLogcat",
                            text = logText,
                            toastMessage = "Log copiado al portapapeles"
                        )
                    }
                )
            }

            // Tarjeta "Detalles" (PID, paquete, proceso :p0, error)
            DetailsCard(
                report = report,
                abiInfo = clonedApp.info.primaryAbi
            )

            // Barra de diagnóstico técnico para desarrolladores: permite verificar tanto la vista VERDE real
            // como simular un bloqueo SELinux (EACCES -> ROJO) y volver a la lectura real.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (report.ok) {
                            "Modo diagnóstico SELinux:"
                        } else {
                            "Diagnóstico de fallo activo:"
                        },
                        fontSize = 12.sp,
                        color = AegisOnSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            if (report.ok) {
                                onSimulateRedStateToggle()
                            } else {
                                onRerunNormal()
                            }
                        },
                        modifier = Modifier.testTag("toggle_diagnostic_mode_btn")
                    ) {
                        Text(
                            text = if (report.ok) {
                                "Probar estado ROJO (EACCES)"
                            } else {
                                "Restaurar lectura real (/proc/self/maps)"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AegisPrimaryText
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    toastMessage: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}
