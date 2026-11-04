package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.InstalledAppItem
import com.example.ui.components.HeaderBar
import com.example.ui.components.PillButton
import com.example.ui.components.SelectableAppTile
import com.example.ui.theme.AegisBackground
import com.example.ui.theme.AegisOnSurfaceVariant
import com.example.ui.theme.AegisSurface

/**
 * Pantalla 2: Añadir app (Sección 5.3)
 * - Cabecera azul con botón volver (circular, translúcido), título "Añadir app" y campo de búsqueda en píldora blanca.
 * - Tarjeta con cuadrícula de 4 columnas de apps instaladas (icono 56 dp + nombre).
 *   La app seleccionada muestra una insignia verde con check.
 * - Botón inferior "Clonar 1 app" (deshabilitado si no hay selección).
 * - Excluye Aegis de la lista y descubre apps con `<queries>` (MAIN / LAUNCHER).
 */
@Composable
fun AddAppScreen(
    installedApps: List<InstalledAppItem>,
    searchQuery: String,
    selectedPackage: String?,
    onSearchQueryChange: (String) -> Unit,
    onSelectApp: (String) -> Unit,
    onCloneConfirm: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            val q = searchQuery.trim().lowercase()
            installedApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    val hasSelection = !selectedPackage.isNullOrBlank()
    val buttonLabel = if (hasSelection) {
        stringResource(R.string.btn_clone_one)
    } else {
        stringResource(R.string.btn_clone_none)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisBackground)
    ) {
        HeaderBar(
            title = stringResource(R.string.add_screen_title),
            onBackClick = onBackClick,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange
        )

        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = navBarsBottom + 20.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AegisSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black.copy(alpha = 0.06f),
                        spotColor = Color.Black.copy(alpha = 0.06f)
                    )
                    .testTag("installed_apps_grid_card")
            ) {
                if (filteredApps.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No se encontraron aplicaciones para \"$searchQuery\""
                            } else {
                                "Cargando aplicaciones instaladas en el dispositivo…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AegisOnSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = filteredApps,
                            key = { it.packageName }
                        ) { appItem ->
                            SelectableAppTile(
                                item = appItem,
                                isSelected = selectedPackage == appItem.packageName,
                                onClick = { onSelectApp(appItem.packageName) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PillButton(
                text = buttonLabel,
                enabled = hasSelection,
                onClick = {
                    selectedPackage?.let { pkg -> onCloneConfirm(pkg) }
                },
                testTag = "confirm_clone_button",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
