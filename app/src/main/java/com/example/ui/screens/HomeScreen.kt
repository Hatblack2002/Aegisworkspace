package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ClonedAppUiItem
import com.example.ui.components.ClonedAppGrid
import com.example.ui.components.HeaderBar
import com.example.ui.components.PillButton
import com.example.ui.theme.AegisBackground
import com.example.ui.theme.AegisOnSurface
import com.example.ui.theme.AegisOnSurfaceVariant
import com.example.ui.theme.AegisPrimary
import com.example.ui.theme.AegisSurface

/**
 * Pantalla 1: Inicio (Sección 5.2)
 * - Cabecera azul: etiqueta "AEGIS ENGINE", título "Mis apps clonadas", subtítulo "Ejecútalas en un espacio aislado".
 * - Tarjeta blanca con cuadrícula de 3 columnas: icono + nombre de cada app clonada y botón "Añadir" con borde punteado azul y signo "+".
 * - Botón píldora inferior "Añadir app".
 * - Toque en un icono: lanza el clon. Pulsación larga: menú (Reejecutar, Ver resultado, Eliminar clon).
 * - Estado vacío: ilustración simple y texto "Aún no has clonado ninguna app" con el botón "Añadir app".
 */
@Composable
fun HomeScreen(
    clonedApps: List<ClonedAppUiItem>,
    onAddAppClick: () -> Unit,
    onLaunchClone: (ClonedAppUiItem) -> Unit,
    onRerunClone: (ClonedAppUiItem) -> Unit,
    onViewCloneResult: (ClonedAppUiItem) -> Unit,
    onExploreFiles: (ClonedAppUiItem) -> Unit,
    onDeleteClone: (ClonedAppUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisBackground)
    ) {
        HeaderBar(
            topTag = stringResource(R.string.header_tag),
            title = stringResource(R.string.home_title),
            subtitle = stringResource(R.string.home_subtitle)
        )

        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = navBarsBottom + 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = AegisSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = Color.Black.copy(alpha = 0.06f),
                            spotColor = Color.Black.copy(alpha = 0.06f)
                        )
                        .testTag("home_clones_card")
                ) {
                    if (clonedApps.isEmpty()) {
                        EmptyClonesState(onAddAppClick = onAddAppClick)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp)
                        ) {
                            ClonedAppGrid(
                                clonedApps = clonedApps,
                                onLaunchApp = onLaunchClone,
                                onRerunApp = onRerunClone,
                                onViewResult = onViewCloneResult,
                                onExploreFiles = onExploreFiles,
                                onDeleteApp = onDeleteClone,
                                onAddAppClick = onAddAppClick
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (clonedApps.isNotEmpty()) {
                    Text(
                        text = "Toque rápido para lanzar en :p0 / :p1 · Mantén pulsado un icono para ver opciones",
                        style = MaterialTheme.typography.labelMedium,
                        color = AegisOnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PillButton(
                text = stringResource(R.string.btn_add_app),
                onClick = onAddAppClick,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                testTag = "home_bottom_add_app_button",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyClonesState(
    onAddAppClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp)
            .testTag("home_empty_state")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(AegisPrimary.copy(alpha = 0.10f))
        ) {
            Icon(
                imageVector = Icons.Outlined.Layers,
                contentDescription = null,
                tint = AegisPrimary,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AegisOnSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.home_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = AegisOnSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(22.dp))

        PillButton(
            text = stringResource(R.string.btn_add_app),
            onClick = onAddAppClick,
            isInsideCard = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            testTag = "empty_state_add_button"
        )
    }
}
