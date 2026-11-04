package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aistudio.aegis.virtualcore.GuestMapsReport
import com.example.model.UiState
import com.example.ui.components.CloneProgressDialog
import com.example.ui.screens.AddAppScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ResultScreen
import com.example.ui.theme.AegisTheme
import com.example.viewmodel.AegisViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                AegisEngineApp()
            }
        }
    }
}

object AegisRoutes {
    const val HOME = "home"
    const val ADD = "add"
    const val RESULT_PATTERN = "result/{packageName}"

    fun resultRoute(packageName: String): String = "result/${Uri.encode(packageName)}"
}

@Composable
fun AegisEngineApp(
    viewModel: AegisViewModel = viewModel()
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val clonedApps by viewModel.clonedApps.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedPackage by viewModel.selectedPackageToClone.collectAsState()

    // Mostrar diálogo modal de progreso o error cuando el motor está instalando, iniciando o si falló
    when (val state = uiState) {
        is UiState.Installing -> {
            CloneProgressDialog(
                appName = state.appName,
                stepMessage = state.stepMessage,
                isError = false
            )
        }
        is UiState.Running -> {
            CloneProgressDialog(
                appName = state.appName,
                stepMessage = state.stepMessage,
                isError = false
            )
        }
        is UiState.Error -> {
            CloneProgressDialog(
                appName = state.appName,
                stepMessage = "",
                isError = true,
                errorCause = state.causeMessage,
                errorLogs = state.logs,
                onDismiss = { viewModel.dismissDialogOrError() }
            )
        }
        else -> Unit
    }

    NavHost(
        navController = navController,
        startDestination = AegisRoutes.HOME,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(AegisRoutes.HOME) {
            HomeScreen(
                clonedApps = clonedApps,
                onAddAppClick = {
                    viewModel.clearSelection()
                    viewModel.refreshInstalledApps()
                    navController.navigate(AegisRoutes.ADD)
                },
                onLaunchClone = { app ->
                    viewModel.launchFullscreenClonedApp(app.info.packageName)
                },
                onRerunClone = { app ->
                    viewModel.launchOrRerunClonedApp(
                        packageName = app.info.packageName,
                        simulateReadFailure = false,
                        onNavigateToResult = { pkg ->
                            navController.navigate(AegisRoutes.resultRoute(pkg)) {
                                launchSingleTop = true
                            }
                        }
                    )
                },
                onViewCloneResult = { app ->
                    viewModel.openResultForPackage(
                        packageName = app.info.packageName,
                        onNavigateToResult = { pkg ->
                            navController.navigate(AegisRoutes.resultRoute(pkg)) {
                                launchSingleTop = true
                            }
                        }
                    )
                },
                onDeleteClone = { app ->
                    viewModel.deleteClonedApp(app.info.packageName)
                }
            )
        }

        composable(AegisRoutes.ADD) {
            AddAppScreen(
                installedApps = installedApps,
                searchQuery = searchQuery,
                selectedPackage = selectedPackage,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                onSelectApp = { viewModel.toggleSelectAppToClone(it) },
                onCloneConfirm = { pkg ->
                    viewModel.cloneAndLaunchSelectedApp(
                        packageName = pkg,
                        onNavigateToResult = { targetPkg ->
                            navController.navigate(AegisRoutes.resultRoute(targetPkg)) {
                                popUpTo(AegisRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    )
                },
                onBackClick = {
                    viewModel.clearSelection()
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = AegisRoutes.RESULT_PATTERN,
            arguments = listOf(
                navArgument("packageName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawPkg = backStackEntry.arguments?.getString("packageName").orEmpty()
            val pkg = Uri.decode(rawPkg)

            val activeResultState = uiState as? UiState.Result
            val clonedItem = when {
                activeResultState != null && activeResultState.clonedApp.info.packageName == pkg -> activeResultState.clonedApp
                else -> viewModel.getClonedAppUiItem(pkg)
            }

            if (clonedItem != null) {
                val report = when {
                    activeResultState != null && activeResultState.report.packageName == pkg -> activeResultState.report
                    clonedItem.info.lastReport != null -> clonedItem.info.lastReport!!
                    else -> GuestMapsReport(
                        ok = false,
                        pid = -1,
                        packageName = pkg,
                        libs = emptyList(),
                        error = "Sin datos de lectura aún",
                        processName = clonedItem.info.assignedProcess
                    )
                }

                ResultScreen(
                    clonedApp = clonedItem,
                    report = report,
                    onBackClick = {
                        viewModel.dismissDialogOrError()
                        navController.popBackStack(AegisRoutes.HOME, inclusive = false)
                    },
                    onRerunNormal = {
                        viewModel.launchOrRerunClonedApp(
                            packageName = pkg,
                            simulateReadFailure = false
                        )
                    },
                    onSimulateRedStateToggle = {
                        viewModel.launchOrRerunClonedApp(
                            packageName = pkg,
                            simulateReadFailure = true
                        )
                    }
                )
            }
        }
    }
}
