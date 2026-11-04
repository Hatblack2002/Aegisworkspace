package com.example.model

import androidx.compose.ui.graphics.ImageBitmap
import com.aistudio.aegis.virtualcore.ClonedAppInfo
import com.aistudio.aegis.virtualcore.GuestMapsReport

/**
 * Representa una app instalada descubierta mediante `<queries>` en el dispositivo.
 */
data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isGame: Boolean,
    val isSystemApp: Boolean,
    val iconBitmap: ImageBitmap?,
    val fallbackColorIndex: Int,
    val isAlreadyCloned: Boolean = false
)

/**
 * Modelo enriquecido para la cuadrícula de la pantalla de Inicio (`HomeScreen`).
 */
data class ClonedAppUiItem(
    val info: ClonedAppInfo,
    val iconBitmap: ImageBitmap?,
    val fallbackColorIndex: Int
)

/**
 * Estados del ciclo de clonado y ejecución (Sección 5.6 y 5.7):
 * Idle, Installing, Running, Result, Error.
 */
sealed class UiState {
    data object Idle : UiState()

    data class Installing(
        val packageName: String,
        val appName: String,
        val stepMessage: String = "Instalando en el contenedor…"
    ) : UiState()

    data class Running(
        val packageName: String,
        val appName: String,
        val stepMessage: String = "Iniciando proceso…"
    ) : UiState()

    data class Result(
        val clonedApp: ClonedAppUiItem,
        val report: GuestMapsReport
    ) : UiState()

    data class Error(
        val packageName: String,
        val appName: String,
        val causeMessage: String,
        val logs: List<String> = emptyList()
    ) : UiState()
}
