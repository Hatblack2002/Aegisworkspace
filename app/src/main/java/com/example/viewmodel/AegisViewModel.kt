package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.aegis.virtualcore.ClonedAppInfo
import com.aistudio.aegis.virtualcore.GuestMapsReport
import com.aistudio.aegis.virtualcore.VirtualEngineHost
import com.example.model.ClonedAppUiItem
import com.example.model.InstalledAppItem
import com.example.model.UiState
import com.example.ui.theme.AegisIconMarkerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class AegisViewModel(application: Application) : AndroidViewModel(application) {

    private val engineHost = VirtualEngineHost.getInstance(application)
    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _clonedApps = MutableStateFlow<List<ClonedAppUiItem>>(emptyList())
    val clonedApps: StateFlow<List<ClonedAppUiItem>> = _clonedApps.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedPackageToClone = MutableStateFlow<String?>(null)
    val selectedPackageToClone: StateFlow<String?> = _selectedPackageToClone.asStateFlow()

    init {
        observeEngineClones()
        refreshInstalledApps()
    }

    private fun observeEngineClones() {
        viewModelScope.launch {
            engineHost.clonedApps.collect { list ->
                val uiItems = withContext(Dispatchers.IO) {
                    list.map { info ->
                        ClonedAppUiItem(
                            info = info,
                            iconBitmap = loadAppIconBitmap(info.packageName),
                            fallbackColorIndex = abs(info.packageName.hashCode()) % AegisIconMarkerColors.size
                        )
                    }
                }
                _clonedApps.value = uiItems
                // Actualizar el flag isAlreadyCloned en la lista de instaladas
                val clonedSet = list.map { it.packageName }.toSet()
                _installedApps.value = _installedApps.value.map { item ->
                    item.copy(isAlreadyCloned = clonedSet.contains(item.packageName))
                }
            }
        }
    }

    /**
     * Descubre todas las aplicaciones instaladas visibles mediante `<queries>` (MAIN / LAUNCHER)
     * excluyendo el propio paquete de Aegis Engine (Sección 5.3).
     */
    fun refreshInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val pm = context.packageManager
            val ownPkg = context.packageName
            val clonedSet = engineHost.clonedApps.value.map { it.packageName }.toSet()

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolvedList: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            val itemsByPkg = linkedMapOf<String, InstalledAppItem>()
            for (resolveInfo in resolvedList) {
                val activityInfo = resolveInfo.activityInfo ?: continue
                val pkgName = activityInfo.packageName ?: continue
                if (pkgName == ownPkg || itemsByPkg.containsKey(pkgName)) continue

                try {
                    val appInfo = activityInfo.applicationInfo
                    val label = pm.getApplicationLabel(appInfo).toString().ifBlank { pkgName }
                    val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkgName, 0)
                    }
                    val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appInfo.category == ApplicationInfo.CATEGORY_GAME
                    } else {
                        @Suppress("DEPRECATION")
                        (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    }
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    itemsByPkg[pkgName] = InstalledAppItem(
                        packageName = pkgName,
                        appName = label,
                        versionName = pkgInfo.versionName ?: "1.0",
                        isGame = isGame,
                        isSystemApp = isSystem,
                        iconBitmap = loadAppIconBitmap(pkgName),
                        fallbackColorIndex = abs(pkgName.hashCode()) % AegisIconMarkerColors.size,
                        isAlreadyCloned = clonedSet.contains(pkgName)
                    )
                } catch (_: Exception) {
                    // Ignorar paquetes inaccesibles
                }
            }

            // Ordenar priorizando apps de usuario y juegos primero, luego alfabéticamente
            val sorted = itemsByPkg.values.sortedWith(
                compareBy<InstalledAppItem> { it.isSystemApp }
                    .thenByDescending { it.isGame }
                    .thenBy { it.appName.lowercase() }
            )
            _installedApps.value = sorted
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSelectAppToClone(packageName: String) {
        _selectedPackageToClone.value = if (_selectedPackageToClone.value == packageName) {
            null
        } else {
            packageName
        }
    }

    fun clearSelection() {
        _selectedPackageToClone.value = null
        _searchQuery.value = ""
    }

    fun dismissDialogOrError() {
        _uiState.value = UiState.Idle
    }

    /**
     * Flujo completo de Clonado y Ejecución (Sección 4 y 5.6):
     * 1. Estado "Instalando en el contenedor…"
     * 2. Estado "Iniciando proceso…"
     * 3. Estado "Leyendo librerías…"
     * 4. Navega automáticamente a la pantalla de Resultado (Verde o Rojo).
     */
    fun cloneAndLaunchSelectedApp(
        packageName: String,
        onNavigateToResult: (String) -> Unit
    ) {
        val appItem = _installedApps.value.find { it.packageName == packageName }
        val displayName = appItem?.appName ?: packageName

        viewModelScope.launch {
            _uiState.value = UiState.Installing(
                packageName = packageName,
                appName = displayName,
                stepMessage = "Instalando en el contenedor…"
            )
            delay(350)

            val installResult = engineHost.installAppInVirtualContainer(packageName)
            if (installResult.isFailure) {
                val err = installResult.exceptionOrNull()?.message ?: "Error al crear contenedor virtual"
                _uiState.value = UiState.Error(
                    packageName = packageName,
                    appName = displayName,
                    causeMessage = err,
                    logs = listOf("E/Aegis: Fallo al instalar $packageName -> $err")
                )
                return@launch
            }

            val clonedInfo = installResult.getOrThrow()
            executeCloneProcess(clonedInfo, simulateReadFailure = false, onNavigateToResult = onNavigateToResult)
        }
    }

    /**
     * Lanza o reejecuta un clon ya instalado y refresca la lectura de `/proc/self/maps`.
     */
    fun launchOrRerunClonedApp(
        packageName: String,
        simulateReadFailure: Boolean = false,
        onNavigateToResult: (String) -> Unit = {}
    ) {
        val existing = _clonedApps.value.find { it.info.packageName == packageName }?.info ?: return
        viewModelScope.launch {
            executeCloneProcess(existing, simulateReadFailure = simulateReadFailure, onNavigateToResult = onNavigateToResult)
        }
    }

    /**
     * Virtual Activity Launcher: Lanza la app clonada visualmente en pantalla completa en el proceso stub.
     */
    fun launchFullscreenClonedApp(packageName: String) {
        val existing = _clonedApps.value.find { it.info.packageName == packageName }?.info ?: return
        engineHost.launchClonedAppFullscreenActivity(existing)
    }


    private suspend fun executeCloneProcess(
        clonedInfo: ClonedAppInfo,
        simulateReadFailure: Boolean,
        onNavigateToResult: (String) -> Unit
    ) {
        _uiState.value = UiState.Running(
            packageName = clonedInfo.packageName,
            appName = clonedInfo.appName,
            stepMessage = "Iniciando proceso…"
        )
        delay(300)

        _uiState.value = UiState.Running(
            packageName = clonedInfo.packageName,
            appName = clonedInfo.appName,
            stepMessage = "Leyendo librerías…"
        )

        val report = engineHost.launchClonedAppAndCollectMaps(
            clonedApp = clonedInfo,
            simulateReadFailure = simulateReadFailure
        )

        val uiItem = ClonedAppUiItem(
            info = clonedInfo.copy(lastReport = report),
            iconBitmap = loadAppIconBitmap(clonedInfo.packageName),
            fallbackColorIndex = abs(clonedInfo.packageName.hashCode()) % AegisIconMarkerColors.size
        )

        _uiState.value = UiState.Result(
            clonedApp = uiItem,
            report = report
        )
        clearSelection()
        onNavigateToResult(clonedInfo.packageName)
    }

    /**
     * Abre la pantalla de resultado para un clon existente; si aún no tiene reporte, ejecuta la lectura.
     */
    fun openResultForPackage(
        packageName: String,
        onNavigateToResult: (String) -> Unit
    ) {
        val item = _clonedApps.value.find { it.info.packageName == packageName } ?: return
        val lastReport = item.info.lastReport
        if (lastReport != null) {
            _uiState.value = UiState.Result(clonedApp = item, report = lastReport)
            onNavigateToResult(packageName)
        } else {
            launchOrRerunClonedApp(packageName = packageName, onNavigateToResult = onNavigateToResult)
        }
    }

    fun deleteClonedApp(packageName: String) {
        viewModelScope.launch {
            engineHost.removeClonedApp(packageName)
            if ((_uiState.value as? UiState.Result)?.clonedApp?.info?.packageName == packageName) {
                _uiState.value = UiState.Idle
            }
        }
    }

    fun getClonedAppUiItem(packageName: String): ClonedAppUiItem? {
        return _clonedApps.value.find { it.info.packageName == packageName }
    }

    private fun loadAppIconBitmap(packageName: String): ImageBitmap? {
        iconCache[packageName]?.let { return it }
        return try {
            val pm = getApplication<Application>().packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable, 144, 144).asImageBitmap()
            iconCache[packageName] = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else width
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
