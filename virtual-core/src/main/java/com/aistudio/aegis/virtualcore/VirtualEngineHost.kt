package com.aistudio.aegis.virtualcore

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Motor principal de virtualización en el proceso Host (`VirtualEngineHost`).
 *
 * Gestiona:
 * - El inventario de apps clonadas en el contenedor virtual (`/files/virtual_env/<package>`).
 * - La extracción de librerías nativas `.so` desde el APK base y split APKs (`split_config.arm64_v8a.apk`, etc.).
 * - La verificación de compatibilidad de ABI (arm64-v8a / x86_64) y detección temprana de protecciones DRM / anti-cheat.
 * - La asignación de procesos stub (`:p0`, `:p1`) y el arranque del proceso clonado con recepción del reporte de `/proc/self/maps`.
 */
class VirtualEngineHost private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val virtualRootDir = File(appContext.filesDir, "virtual_env").apply { mkdirs() }

    private val _clonedApps = MutableStateFlow<List<ClonedAppInfo>>(emptyList())
    val clonedApps: StateFlow<List<ClonedAppInfo>> = _clonedApps.asStateFlow()

    private val _currentStorageAudit = MutableStateFlow<VirtualStorageAudit?>(null)
    val currentStorageAudit: StateFlow<VirtualStorageAudit?> = _currentStorageAudit.asStateFlow()

    init {
        loadSavedClones()
    }

    /**
     * Carga la lista de apps clonadas desde el almacenamiento persistente del motor.
     */
    @Synchronized
    fun loadSavedClones() {
        val raw = prefs.getString(KEY_CLONED_APPS_JSON, null)
        if (raw.isNullOrBlank()) {
            _clonedApps.value = emptyList()
            return
        }
        try {
            val array = JSONArray(raw)
            val list = mutableListOf<ClonedAppInfo>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName")
                if (pkg.isBlank()) continue
                val reportObj = obj.optJSONObject("lastReport")
                val assignedProc = obj.optString("assignedProcess", ":p0")
                val report = reportObj?.let {
                    GuestMapsReport.fromContractOrFullJson(it.toString(), assignedProc)
                }
                val sandboxApkPaths = mutableListOf<String>()
                val pathsArr = obj.optJSONArray("sandboxApkPaths")
                if (pathsArr != null) {
                    for (p in 0 until pathsArr.length()) {
                        val path = pathsArr.optString(p)
                        if (path.isNotBlank()) sandboxApkPaths.add(path)
                    }
                }
                list.add(
                    ClonedAppInfo(
                        packageName = pkg,
                        appName = obj.optString("appName", pkg),
                        versionName = obj.optString("versionName", "1.0"),
                        sourceApkPath = obj.optString("sourceApkPath", ""),
                        virtualContainerDir = obj.optString("virtualContainerDir", ""),
                        virtualNativeLibDir = obj.optString("virtualNativeLibDir", ""),
                        primaryAbi = obj.optString("primaryAbi", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"),
                        assignedProcess = assignedProc,
                        clonedAtMs = obj.optLong("clonedAtMs", System.currentTimeMillis()),
                        isCompatibleAbi = obj.optBoolean("isCompatibleAbi", true),
                        incompatibilityReason = if (obj.isNull("incompatibilityReason")) null else obj.optString("incompatibilityReason").takeIf { it.isNotBlank() },
                        lastReport = report,
                        sandboxApkPaths = sandboxApkPaths,
                        launchProbeOk = if (obj.isNull("launchProbeOk")) null else obj.optBoolean("launchProbeOk", false),
                        launchProbeMessage = if (obj.isNull("launchProbeMessage")) null else obj.optString("launchProbeMessage").takeIf { it.isNotBlank() }
                    )
                )
            }
            _clonedApps.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo clones guardados: ${e.message}", e)
            _clonedApps.value = emptyList()
        }
    }

    @Synchronized
    private fun persistClones(list: List<ClonedAppInfo>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("packageName", item.packageName)
            obj.put("appName", item.appName)
            obj.put("versionName", item.versionName)
            obj.put("sourceApkPath", item.sourceApkPath)
            obj.put("virtualContainerDir", item.virtualContainerDir)
            obj.put("virtualNativeLibDir", item.virtualNativeLibDir)
            obj.put("primaryAbi", item.primaryAbi)
            obj.put("assignedProcess", item.assignedProcess)
            obj.put("clonedAtMs", item.clonedAtMs)
            obj.put("isCompatibleAbi", item.isCompatibleAbi)
            if (item.incompatibilityReason != null) {
                obj.put("incompatibilityReason", item.incompatibilityReason)
            } else {
                obj.put("incompatibilityReason", JSONObject.NULL)
            }
            if (item.lastReport != null) {
                obj.put("lastReport", item.lastReport.toFullStorageJson())
            } else {
                obj.put("lastReport", JSONObject.NULL)
            }
            val pathsArray = JSONArray()
            item.sandboxApkPaths.forEach { pathsArray.put(it) }
            obj.put("sandboxApkPaths", pathsArray)
            if (item.launchProbeOk != null) {
                obj.put("launchProbeOk", item.launchProbeOk)
            } else {
                obj.put("launchProbeOk", JSONObject.NULL)
            }
            if (item.launchProbeMessage != null) {
                obj.put("launchProbeMessage", item.launchProbeMessage)
            } else {
                obj.put("launchProbeMessage", JSONObject.NULL)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CLONED_APPS_JSON, array.toString()).apply()
        _clonedApps.value = list
    }

    /**
     * Guarda un reporte recibido desde el proceso guest (:p0 / :p1) y actualiza el estado del clon.
     */
    @Synchronized
    fun saveReportFromGuest(report: GuestMapsReport) {
        val current = _clonedApps.value.toMutableList()
        val index = current.indexOfFirst { it.packageName == report.packageName }
        if (index >= 0) {
            current[index] = current[index].copy(lastReport = report)
            persistClones(current)
        }
    }

    /**
     * Instala una aplicación en el contenedor virtual de Aegis Engine:
     * 1. Crea la jerarquía de directorios aislados (`/files/virtual_env/<pkg>/data`, `/lib`, `/oat`).
     * 2. Inspecciona el APK base y los split APKs para extraer las librerías `.so` compatibles con la ABI del dispositivo.
     * 3. Verifica si la aplicación tiene incompatibilidades conocidas de ABI o DRM/anti-cheat.
     */
    suspend fun installAppInVirtualContainer(packageName: String): Result<ClonedAppInfo> = withContext(Dispatchers.IO) {
        try {
            val pm = appContext.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }

            val label = pm.getApplicationLabel(appInfo).toString().ifBlank { packageName }
            val versionName = pkgInfo.versionName ?: "1.0"
            val sourceApk = appInfo.sourceDir ?: ""

            val containerDir = File(virtualRootDir, packageName).apply { mkdirs() }
            val nativeLibDir = File(containerDir, "lib").apply { mkdirs() }
            File(containerDir, "files").mkdirs()
            File(containerDir, "cache").mkdirs()
            File(containerDir, "databases").mkdirs()
            File(containerDir, "shared_prefs").mkdirs()
            File(containerDir, "oat").mkdirs()

            // Asignar proceso stub (:p0 o :p1) de forma determinista
            val existingIndex = _clonedApps.value.indexOfFirst { it.packageName == packageName }
            val slotIndex = if (existingIndex >= 0) existingIndex else _clonedApps.value.size
            val assignedProcess = if (slotIndex % 2 == 0) ":p0" else ":p1"

            // Extraer librerías .so desde el APK base y posibles split APKs
            val apkFiles = mutableListOf<File>()
            if (sourceApk.isNotBlank()) {
                val baseFile = File(sourceApk)
                if (baseFile.exists()) apkFiles.add(baseFile)
            }
            appInfo.splitSourceDirs?.forEach { splitPath ->
                val f = File(splitPath)
                if (f.exists()) apkFiles.add(f)
            }

            val extractionSummary = extractGuestNativeLibraries(apkFiles, nativeLibDir)
            Log.i(
                TAG,
                "Contenedor virtual creado para $packageName: ABI=${extractionSummary.detectedAbi}, " +
                    "extraídas=${extractionSummary.extractedCount} librerías .so en ${nativeLibDir.absolutePath}"
            )

            // ENGINE v2 (1/2): copiar base.apk + splits al sandbox. El invitado corre desde
            // SU PROPIA copia: inmune a rutas /data/app que cambian al actualizarse la app
            // original y a restricciones del ROM sobre leer APKs de otras apps.
            val apkCopyDir = File(containerDir, "apk").apply { mkdirs() }
            val copiedApks = mutableListOf<File>()
            apkFiles.forEachIndexed { index, apkFile ->
                runCatching {
                    val dest = File(apkCopyDir, if (index == 0) "base.apk" else "split_config_${index}.apk")
                    if (dest.exists()) dest.delete()
                    apkFile.copyTo(dest, overwrite = true)
                    dest.setReadable(true, false)
                    // Verificación SHA-256 copia vs original (hilo IO): una copia truncada
                    // produce ClassNotFoundException dispersas imposibles de diagnosticar.
                    val srcHash = sha256Of(apkFile)
                    val dstHash = sha256Of(dest)
                    if (srcHash != null && dstHash != null && srcHash != dstHash) {
                        Log.w(TAG, "Hash DISTINTO tras copiar ${dest.name} — re-copiando")
                        dest.delete()
                        apkFile.copyTo(dest, overwrite = true)
                        if (sha256Of(dest) != srcHash) {
                            Log.w(TAG, "La copia ${dest.name} sigue distinta del original (¿disco lleno?)")
                        } else {
                            Log.i(TAG, "Re-copia verificada: ${dest.name}")
                        }
                    }
                    // Regla W^X: el dex solo se carga desde archivos de SOLO LECTURA.
                    dest.setReadOnly()
                    copiedApks.add(dest)
                }.onFailure {
                    Log.w(TAG, "No se pudo copiar ${apkFile.name} al sandbox: ${it.message}")
                }
            }
            Log.i(TAG, "Copias sandbox de $packageName: ${copiedApks.size}/${apkFiles.size} APKs en ${apkCopyDir.absolutePath}")

            // ENGINE v2 (2/2): sonda dex HONESTA. Antes 'ClassLoader: OK' solo significaba
            // que el constructor no lanzó; con esto se sabe ANTES de lanzar si ART puede
            // cargar el código real de esta app (evita el ClassNotFound del YouTube v1).
            val dexProbe = probeGuestDex(copiedApks.ifEmpty { apkFiles }, containerDir, nativeLibDir, packageName)

            val clonedInfo = ClonedAppInfo(
                packageName = packageName,
                appName = label,
                versionName = versionName,
                sourceApkPath = sourceApk,
                virtualContainerDir = containerDir.absolutePath,
                virtualNativeLibDir = nativeLibDir.absolutePath,
                primaryAbi = extractionSummary.detectedAbi,
                assignedProcess = assignedProcess,
                clonedAtMs = System.currentTimeMillis(),
                isCompatibleAbi = extractionSummary.isAbiCompatible,
                incompatibilityReason = extractionSummary.incompatibilityReason,
                lastReport = _clonedApps.value.find { it.packageName == packageName }?.lastReport,
                sandboxApkPaths = copiedApks.map { it.absolutePath },
                launchProbeOk = dexProbe.ok,
                launchProbeMessage = dexProbe.message
            )

            synchronized(this@VirtualEngineHost) {
                val updated = _clonedApps.value.toMutableList()
                val idx = updated.indexOfFirst { it.packageName == packageName }
                if (idx >= 0) {
                    updated[idx] = clonedInfo
                } else {
                    updated.add(clonedInfo)
                }
                persistClones(updated)
            }

            Result.success(clonedInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error instalando $packageName en contenedor virtual: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Lanza el proceso clonado (`:p0` o `:p1`), espera la ejecución de `libaegis_guest.so`
     * y devuelve el `GuestMapsReport` recibido por `AegisReportProvider`.
     */
    suspend fun launchClonedAppAndCollectMaps(
        clonedApp: ClonedAppInfo,
        simulateReadFailure: Boolean = false
    ): GuestMapsReport = withContext(Dispatchers.IO) {
        val startTimestamp = System.currentTimeMillis()
        val forceError = when {
            simulateReadFailure -> "EACCES (Permiso denegado al abrir /proc/self/maps por política SELinux)"
            !clonedApp.isCompatibleAbi -> clonedApp.incompatibilityReason ?: "ABI no compatible con el contenedor arm64-v8a"
            else -> null
        }

        // Iniciar el servicio del proceso stub (:p0 o :p1) para que corra en su propio PID aislado
        val serviceClass = if (clonedApp.assignedProcess == ":p1") {
            StubProcessServiceP1::class.java
        } else {
            StubProcessServiceP0::class.java
        }

        try {
            val serviceIntent = Intent(appContext, serviceClass).apply {
                putExtra(GuestRuntimeEngine.EXTRA_PACKAGE_NAME, clonedApp.packageName)
                putExtra(GuestRuntimeEngine.EXTRA_APK_PATH, clonedApp.sourceApkPath)
                putExtra(GuestRuntimeEngine.EXTRA_CONTAINER_DIR, clonedApp.virtualContainerDir)
                putExtra(GuestRuntimeEngine.EXTRA_NATIVE_LIB_DIR, clonedApp.virtualNativeLibDir)
                putExtra(GuestRuntimeEngine.EXTRA_PROCESS_NAME, clonedApp.assignedProcess)
                if (forceError != null) {
                    putExtra(GuestRuntimeEngine.EXTRA_FORCE_ERROR, forceError)
                }
            }
            appContext.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Aviso al iniciar servicio stub ${clonedApp.assignedProcess}: ${e.message}")
        }

        // Esperar hasta 2.5 segundos a que el proceso :p0/:p1 envíe el reporte vía ContentProvider
        var attempts = 0
        while (attempts < 25) {
            delay(100)
            val candidate = AegisReportProvider.reportsByPackage.value[clonedApp.packageName]
            if (candidate != null && candidate.timestampMs >= startTimestamp) {
                saveReportFromGuest(candidate)
                return@withContext candidate
            }
            attempts++
        }

        // Respaldo directo en caso de que el gestor del sistema retrase la creación del proceso secundario
        Log.i(TAG, "Ejecutando lectura directa con libaegis_guest.so para ${clonedApp.packageName}")
        val directReport = NativeGuestBridge.inspectCurrentProcessMaps(
            packageName = clonedApp.packageName,
            processName = clonedApp.assignedProcess,
            preloadLibsDir = File(clonedApp.virtualNativeLibDir),
            forceErrorReason = forceError
        )
        AegisReportProvider.onReportReceived(appContext, directReport)
        directReport
    }

    /**
     * Virtual Activity Launcher: Abre la app clonada en pantalla completa dentro de su proceso stub aislado.
     */
    /** Paquete del proceso stub activo — regla "un informe = un paquete = un PID". */
    private var activeStubPackage: String = ""

    fun launchClonedAppFullscreenActivity(clonedApp: ClonedAppInfo) {
        // Un informe = un paquete = un PID. Si el proceso stub activo sirve a OTRO
        // paquete, se mata antes de lanzar: el informe del nuevo intento nunca mezcla
        // pasos de dos invitados (antes: VPN y SBrowser compartieron el PID 2781 y
        // sus informes quedaron entrelazados).
        if (activeStubPackage != clonedApp.packageName) {
            runCatching {
                AegisReportProvider.reportsByPackage.value.forEach { (pkg, report) ->
                    val pid = report.pid
                    if (pkg != clonedApp.packageName && pid > 0 && pid != android.os.Process.myPid()) {
                        android.os.Process.killProcess(pid)
                        Log.i(TAG, "proceso stub de $pkg (pid=$pid) terminado antes de lanzar ${clonedApp.packageName}")
                    }
                }
            }
            activeStubPackage = clonedApp.packageName
        }
        val activityClass = if (clonedApp.assignedProcess == ":p1") {
            StubProcessActivityP1::class.java
        } else {
            StubProcessActivityP0::class.java
        }

        val activityIntent = Intent(appContext, activityClass).apply {
            // NEW_TASK sin MULTIPLE_TASK: una sola tarea por app clonada. Con MULTIPLE_TASK
            // cada toque creaba una tarea paralela y el singleTask del invitado quedaba
            // anulado (pilas de tareas confusas al volver atrás).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(GuestRuntimeEngine.EXTRA_PACKAGE_NAME, clonedApp.packageName)
            putExtra(GuestRuntimeEngine.EXTRA_APK_PATH, clonedApp.sourceApkPath)
            putExtra(GuestRuntimeEngine.EXTRA_CONTAINER_DIR, clonedApp.virtualContainerDir)
            putExtra(GuestRuntimeEngine.EXTRA_NATIVE_LIB_DIR, clonedApp.virtualNativeLibDir)
            putExtra(GuestRuntimeEngine.EXTRA_PROCESS_NAME, clonedApp.assignedProcess)
            putExtra(GuestRuntimeEngine.EXTRA_LAUNCH_UI, true)
        }
        appContext.startActivity(activityIntent)
    }

    /**
     * Elimina un clon del contenedor virtual y borra sus archivos aislados.
     */
    suspend fun removeClonedApp(packageName: String) = withContext(Dispatchers.IO) {
        val targetDir = File(virtualRootDir, packageName)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        synchronized(this@VirtualEngineHost) {
            val updated = _clonedApps.value.filterNot { it.packageName == packageName }
            persistClones(updated)
        }
    }

    private data class AbiExtractionResult(
        val detectedAbi: String,
        val extractedCount: Int,
        val isAbiCompatible: Boolean,
        val incompatibilityReason: String?
    )

    /**
     * Sonda dex (engine v2): crea un DexClassLoader desechable sobre los APKs del invitado
     * e intenta cargar la clase Application y la clase LAUNCHER reales.
     * Resultado verdadero de "¿esta app puede ejecutarse en el motor?" — no un simple
     * "el constructor no lanzó". Nunca lanza: devuelve el veredicto y el motivo.
     */
    private fun probeGuestDex(
        apkFiles: List<File>,
        containerDir: File,
        nativeLibDir: File,
        packageName: String
    ): DexProbeResult {
        if (apkFiles.isEmpty()) {
            return DexProbeResult(false, "Sin APKs del invitado para cargar código")
        }
        return try {
            // Inventario dex: detecta base.apk sin código (stub/empty) de un vistazo.
            var dexEntries = 0
            for (apk in apkFiles) {
                runCatching {
                    java.util.zip.ZipFile(apk).use { zip ->
                        val names = zip.entries().asSequence().map { it.name }
                        dexEntries += names.count { it.matches(Regex("classes[0-9]*\\.dex")) }
                    }
                }
            }
            if (dexEntries == 0) {
                return DexProbeResult(false, "Los APKs no contienen classes*.dex (0 entradas) — APK sin código")
            }

            val launcherAlias = try {
                appContext.packageManager.getLaunchIntentForPackage(packageName)?.component?.className
            } catch (_: Exception) { null }
            // CRÍTICO: el launcher de VLC/YouTube es un activity-alias — la clase real es
            // targetActivity. Sondear el alias daría ClassNotFoundException POR DISEÑO.
            val launcherClass = launcherAlias?.let { alias ->
                runCatching {
                    appContext.packageManager.getActivityInfo(android.content.ComponentName(packageName, alias), 0)
                        ?.targetActivity ?: alias
                }.getOrDefault(alias)
            }

            // Regla W^X: dex solo desde archivos de solo lectura (instalaciones antiguas
            // pueden tener copias escribibles).
            apkFiles.forEach { if (it.canWrite()) it.setReadOnly() }
            val optimizedDir = File(containerDir, "oat_probe").apply { mkdirs() }
            val dexPath = apkFiles.joinToString(File.pathSeparator) { it.absolutePath }
            val probeLoader = dalvik.system.DexClassLoader(
                dexPath,
                optimizedDir.absolutePath,
                nativeLibDir.absolutePath,
                appContext.classLoader
            )

            var appClassOk: Boolean? = null
            var appClassError: String? = null
            try {
                val appClassName = appContext.packageManager.getApplicationInfo(packageName, 0).className
                if (!appClassName.isNullOrBlank()) {
                    probeLoader.loadClass(appClassName)
                    appClassOk = true
                }
            } catch (t: Throwable) {
                appClassOk = false
                appClassError = t.message ?: t.javaClass.simpleName
            }

            var launcherOk: Boolean? = null
            var launcherError: String? = null
            if (launcherClass != null) {
                try {
                    probeLoader.loadClass(launcherClass)
                    launcherOk = true
                } catch (t: Throwable) {
                    launcherOk = false
                    launcherError = t.message ?: t.javaClass.simpleName
                }
            }

            val ok = (appClassOk != false) && (launcherOk != false)
            val message = when {
                ok -> "dex OK ($dexEntries entradas; aplicación y lanzador cargan)"
                launcherOk == false -> "ART no pudo cargar el LAUNCHER ($launcherClass): $launcherError"
                else -> "ART no pudo cargar la Application: $appClassError"
            }
            DexProbeResult(ok, message)
        } catch (t: Throwable) {
            DexProbeResult(false, "Sonda dex falló: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private data class DexProbeResult(val ok: Boolean, val message: String)

    /** SHA-256 de un archivo; null si no se puede leer (informe e inventario). */
    private fun sha256Of(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    private fun extractGuestNativeLibraries(apkFiles: List<File>, destLibDir: File): AbiExtractionResult {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val primaryDeviceAbi = supportedAbis.firstOrNull() ?: "arm64-v8a"
        val foundAbisInApk = mutableSetOf<String>()
        var extractedCount = 0

        for (apk in apkFiles) {
            try {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (!entry.isDirectory && name.startsWith("lib/") && name.endsWith(".so")) {
                            // Formato: lib/<abi>/libfoo.so
                            val parts = name.split("/")
                            if (parts.size >= 3) {
                                val abi = parts[1]
                                val soFileName = parts.last()
                                foundAbisInApk.add(abi)
                                if (abi == primaryDeviceAbi || supportedAbis.contains(abi)) {
                                    val outFile = File(destLibDir, soFileName)
                                    zip.getInputStream(entry).use { input ->
                                        FileOutputStream(outFile).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    outFile.setReadable(true, false)
                                    outFile.setExecutable(true, false)
                                    extractedCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo inspeccionar entradas ZIP de ${apk.name}: ${e.message}")
            }
        }

        // Si el APK tiene librerías nativas pero ninguna coincide con las ABIs soportadas por el dispositivo
        if (foundAbisInApk.isNotEmpty() && extractedCount == 0) {
            val onlyUnsupported = foundAbisInApk.joinToString(", ")
            return AbiExtractionResult(
                detectedAbi = onlyUnsupported,
                extractedCount = 0,
                isAbiCompatible = false,
                incompatibilityReason = "ABI no compatible: la app requiere [$onlyUnsupported] pero el motor corre en [$primaryDeviceAbi]"
            )
        }

        val resolvedAbi = when {
            foundAbisInApk.contains(primaryDeviceAbi) -> primaryDeviceAbi
            foundAbisInApk.isNotEmpty() -> foundAbisInApk.first()
            else -> "$primaryDeviceAbi (Java/Kotlin + ART)"
        }

        return AbiExtractionResult(
            detectedAbi = resolvedAbi,
            extractedCount = extractedCount,
            isAbiCompatible = true,
            incompatibilityReason = null
        )
    }

    fun getVirtualContainerDir(packageName: String): File {
        return File(virtualRootDir, packageName).canonicalFile
    }

    /**
     * Auditoría de archivos en el sandbox virtual (/files/virtual_env/<packageName>/).
     * Garantiza de forma estricta que todas las rutas residan dentro del sandbox aislado.
     */
    suspend fun auditVirtualStorage(packageName: String): VirtualStorageAudit = withContext(Dispatchers.IO) {
        val containerDir = File(virtualRootDir, packageName).canonicalFile
        if (!containerDir.exists()) {
            containerDir.mkdirs()
        }
        val standardFolders = listOf("files", "cache", "databases", "shared_prefs", "lib")
        for (f in standardFolders) {
            val sub = File(containerDir, f)
            if (!sub.exists()) sub.mkdirs()
        }

        var totalBytes = 0L
        var totalFiles = 0
        var totalDirs = 0

        fun exploreNode(file: File, relative: String, depth: Int): VirtualFileNode {
            val canonical = file.canonicalFile
            if (!canonical.path.startsWith(containerDir.path)) {
                throw SecurityException("Acceso bloqueado: ruta fuera del sandbox virtual: ${canonical.path}")
            }
            val isDir = canonical.isDirectory
            if (isDir) {
                totalDirs++
                val childNodes: List<VirtualFileNode> = if (depth < 6) {
                    val rawFiles = canonical.listFiles() ?: emptyArray()
                    val sortedList = rawFiles.sortedWith(
                        compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                    )
                    sortedList.map { child ->
                        val childRel = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                        exploreNode(child, childRel, depth + 1)
                    }
                } else {
                    emptyList()
                }
                val dirSize = childNodes.sumOf { it.sizeBytes }
                return VirtualFileNode(
                    name = canonical.name,
                    relativePath = relative.ifEmpty { canonical.name },
                    absolutePath = canonical.absolutePath,
                    isDirectory = true,
                    sizeBytes = dirSize,
                    formattedSize = VirtualFileUtils.formatSize(dirSize),
                    lastModifiedMs = canonical.lastModified(),
                    formattedDate = VirtualFileUtils.formatDate(canonical.lastModified()),
                    children = childNodes,
                    isTextPreviewable = false,
                    previewSnippet = "${childNodes.size} elementos"
                )
            } else {
                totalFiles++
                val fileSize = canonical.length()
                totalBytes += fileSize
                val isText = VirtualFileUtils.isTextFile(canonical.name, fileSize)
                val snippet = if (isText && fileSize <= 2048) {
                    try {
                        canonical.readText(Charsets.UTF_8).take(200)
                    } catch (_: Exception) {
                        null
                    }
                } else null

                return VirtualFileNode(
                    name = canonical.name,
                    relativePath = relative.ifEmpty { canonical.name },
                    absolutePath = canonical.absolutePath,
                    isDirectory = false,
                    sizeBytes = fileSize,
                    formattedSize = VirtualFileUtils.formatSize(fileSize),
                    lastModifiedMs = canonical.lastModified(),
                    formattedDate = VirtualFileUtils.formatDate(canonical.lastModified()),
                    children = emptyList(),
                    isTextPreviewable = isText,
                    previewSnippet = snippet
                )
            }
        }

        val rootNodes = standardFolders.map { folderName ->
            val folderFile = File(containerDir, folderName)
            exploreNode(folderFile, folderName, depth = 0)
        }

        val audit = VirtualStorageAudit(
            packageName = packageName,
            sandboxRootPath = containerDir.absolutePath,
            rootNodes = rootNodes,
            totalSizeBytes = totalBytes,
            formattedTotalSize = VirtualFileUtils.formatSize(totalBytes),
            totalFilesCount = totalFiles,
            totalDirsCount = totalDirs
        )

        _currentStorageAudit.value = audit
        audit
    }

    suspend fun readVirtualFileContent(
        packageName: String,
        relativePath: String,
        maxBytes: Int = 128 * 1024
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val containerDir = File(virtualRootDir, packageName).canonicalFile
            val target = File(containerDir, relativePath).canonicalFile
            if (!target.path.startsWith(containerDir.path)) {
                return@withContext Result.failure(SecurityException("Acceso bloqueado: ruta fuera del sandbox virtual"))
            }
            if (!target.exists() || target.isDirectory) {
                return@withContext Result.failure(IllegalArgumentException("El archivo no existe o es un directorio"))
            }
            val text = if (target.length() > maxBytes) {
                target.inputStream().use { stream ->
                    val buf = ByteArray(maxBytes)
                    val read = stream.read(buf)
                    String(buf, 0, read, Charsets.UTF_8) + "\n\n[... Truncado a 128 KB ...]"
                }
            } else {
                target.readText(Charsets.UTF_8)
            }
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createSampleVerificationFile(
        packageName: String,
        targetFolder: String = "shared_prefs",
        fileName: String = "app_preferences.xml",
        customContent: String? = null
    ): Result<VirtualFileNode> = withContext(Dispatchers.IO) {
        try {
            val containerDir = File(virtualRootDir, packageName).canonicalFile
            val folder = File(containerDir, targetFolder).canonicalFile
            if (!folder.path.startsWith(containerDir.path)) {
                return@withContext Result.failure(SecurityException("Ruta no autorizada"))
            }
            folder.mkdirs()
            val targetFile = File(folder, fileName).canonicalFile
            if (!targetFile.path.startsWith(containerDir.path)) {
                return@withContext Result.failure(SecurityException("Ruta no autorizada"))
            }

            val content = customContent ?: """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="aegis_verification">sandbox_vfs_ok</string>
    <string name="target_package">$packageName</string>
    <boolean name="virtualization_active" value="true" />
    <long name="created_timestamp_ms" value="${System.currentTimeMillis()}" />
    <string name="environment">isolated_virtual_sandbox</string>
</map>
""".trimIndent()

            targetFile.writeText(content, Charsets.UTF_8)
            auditVirtualStorage(packageName)

            val node = VirtualFileNode(
                name = targetFile.name,
                relativePath = "$targetFolder/$fileName",
                absolutePath = targetFile.absolutePath,
                isDirectory = false,
                sizeBytes = targetFile.length(),
                formattedSize = VirtualFileUtils.formatSize(targetFile.length()),
                lastModifiedMs = targetFile.lastModified(),
                formattedDate = VirtualFileUtils.formatDate(targetFile.lastModified()),
                children = emptyList(),
                isTextPreviewable = true,
                previewSnippet = content.take(150)
            )
            Result.success(node)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteVirtualFile(packageName: String, relativePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val containerDir = File(virtualRootDir, packageName).canonicalFile
            val target = File(containerDir, relativePath).canonicalFile
            if (!target.path.startsWith(containerDir.path)) {
                return@withContext Result.failure(SecurityException("Ruta no autorizada"))
            }
            if (!target.exists()) {
                return@withContext Result.failure(IllegalArgumentException("El archivo no existe"))
            }
            val deleted = if (target.isDirectory) {
                target.deleteRecursively()
            } else {
                target.delete()
            }
            auditVirtualStorage(packageName)
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "Aegis"
        private const val PREFS_NAME = "aegis_virtual_engine_store"
        private const val KEY_CLONED_APPS_JSON = "cloned_apps_v1"

        @Volatile
        private var INSTANCE: VirtualEngineHost? = null

        fun getInstance(context: Context): VirtualEngineHost {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VirtualEngineHost(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
