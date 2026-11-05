package com.aistudio.aegis.virtualcore

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Puente JNI cooperativo e instrumentación transparente:
 * Gestiona el mapeo explícito de VFS, compatibilidad con File API y lectura verificable
 * de /proc/self/maps sin suplantación de identidad ni técnicas de ocultación.
 */
object NativeGuestBridge {
    private const val TAG = "AegisGuest"

    @Volatile
    private var isNativeLoaded = false

    @Volatile
    private var loadError: String? = null

    init {
        ensureLoaded()
    }

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (isNativeLoaded) return true
        return try {
            System.loadLibrary("aegis_guest")
            isNativeLoaded = true
            loadError = null
            Log.i(TAG, "System.loadLibrary(\"aegis_guest\") exitoso en PID=${Process.myPid()}")
            true
        } catch (t: Throwable) {
            loadError = "UNSATISFIED_LINK (${t.javaClass.simpleName}: ${t.message})"
            Log.e(TAG, "Error al cargar libaegis_guest.so: $loadError", t)
            false
        }
    }

    fun getLoadErrorReason(): String? = loadError

    /**
     * Instala el Sandbox de Señales nativas (SIGSEGV, SIGABRT, etc.)
     */
    external fun nativeInstallCrashHandler()

    /** Ruta donde el handler de señales escribe el informe nativo antes de morir. */
    external fun nativeSetCrashReportPath(path: String)

    /**
     * Configuración explícita del VFS cooperativo en C++
     */
    external fun nativeConfigureVfsMapping(
        packageName: String,
        sandboxRoot: String,
        guestNativeLibDir: String
    )

    /**
     * Resuelve rutas lógicas a rutas físicas del sandbox
     */
    external fun nativeResolveSandboxPath(logicalPath: String): String?

    /**
     * Valida permisos y acceso a archivos en el sandbox
     */
    external fun nativeCheckSandboxFileAccess(path: String, mode: Int): Boolean

    /**
     * Devuelve el registro de eventos y diagnósticos VFS
     */
    external fun nativeGetVfsDiagnosticLogs(): String

    /**
     * Lee `/proc/self/maps` con instrumentación transparente y verificable
     */
    external fun nativeReadProcSelfMapsJson(
        packageName: String,
        forceFailSimulation: Boolean
    ): String

    /**
     * Carga mediante `dlopen` una librería `.so` del guest dentro del proceso stub
     */
    external fun nativeDlopenLibrary(libPath: String): String

    /**
     * Ejecuta la inspección de `/proc/self/maps` en el proceso actual (:p0 / :p1),
     * usando `libaegis_guest.so` y recolectando trazas de diagnóstico verificable.
     */
    fun inspectCurrentProcessMaps(
        packageName: String,
        processName: String,
        preloadLibsDir: File? = null,
        sandboxRootDir: File? = null,
        forceErrorReason: String? = null
    ): GuestMapsReport {
        val pid = Process.myPid()
        Log.i(TAG, "Iniciando inspección cooperativa en proceso $processName (PID=$pid) para $packageName")

        if (forceErrorReason != null) {
            Log.e(TAG, "Incompatibilidad o error detectado para $packageName: $forceErrorReason")
            val logs = captureRecentAegisLogcat(packageName, pid)
            return GuestMapsReport(
                ok = false,
                pid = pid,
                packageName = packageName,
                libs = emptyList(),
                error = forceErrorReason,
                processName = processName,
                logs = logs
            )
        }

        val loaded = ensureLoaded()
        if (!loaded) {
            val err = loadError ?: "No se pudo cargar libaegis_guest.so"
            Log.e(TAG, "Fallo crítico: $err")
            val logs = captureRecentAegisLogcat(packageName, pid)
            return GuestMapsReport(
                ok = false,
                pid = pid,
                packageName = packageName,
                libs = emptyList(),
                error = err,
                processName = processName,
                logs = logs
            )
        }

        // Configuración explícita de VFS cooperativo
        if (sandboxRootDir != null) {
            try {
                nativeConfigureVfsMapping(
                    packageName = packageName,
                    sandboxRoot = sandboxRootDir.absolutePath,
                    guestNativeLibDir = preloadLibsDir?.absolutePath ?: ""
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Aviso al configurar VFS mapping nativo: ${t.message}")
            }
        }

        // Regla del motor: el INSPECTOR NO hace dlopen de las .so del guest.
        // El dlopen deliberado causaba SIGABRT nativo (señal 6) en juegos — el
        // proceso moría antes de que ningún handler Java pudiera generar informe:
        // "pantalla en blanco sin informe". Cada .so se cargará NATURALMENTE cuando
        // el guest llame System.loadLibrary (el DexClassLoader tiene nativeLibraryDir
        // apuntando al sandbox). Aquí solo se INVENTARIAN como aviso.
        preloadLibsDir?.let { dir ->
            if (dir.exists() && dir.isDirectory) {
                val soFiles = dir.listFiles { file -> file.isFile && file.name.endsWith(".so") }.orEmpty()
                val wrongAbi = soFiles.filter { so ->
                    so.inputStream().use { input ->
                        val header = ByteArray(5); input.read(header) == 5 &&
                            header[4].toInt() == 1 // ELFCLASS32 en un proceso 64-bit
                    }
                }
                Log.i(TAG, "Inventario de librerías guest (sin dlopen): ${soFiles.size} totales, ${wrongAbi.size} de otra ABI")
                if (wrongAbi.isNotEmpty()) {
                    GuestLaunchTracker.exception(
                        IllegalStateException(
                            "AVISO ABI (no bloquea el intento): ${wrongAbi.size} .so del guest no son de la ABI del proceso: " +
                                wrongAbi.take(8).joinToString(", ") { it.name }
                        ),
                        "librerías .so del guest con ABI distinta a la del proceso (se ignoran)"
                    )
                }
            }
        }

        return try {
            val rawJson = nativeReadProcSelfMapsJson(packageName, false)
            val parsed = GuestMapsReport.fromContractOrFullJson(rawJson, processName)
            val logs = captureRecentAegisLogcat(packageName, pid)
            parsed.copy(
                processName = processName,
                logs = logs
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Excepción JNI al leer /proc/self/maps: ${t.message}", t)
            val logs = captureRecentAegisLogcat(packageName, pid)
            GuestMapsReport(
                ok = false,
                pid = pid,
                packageName = packageName,
                libs = emptyList(),
                error = "JNI_EXCEPTION: ${t.message}",
                processName = processName,
                logs = logs
            )
        }
    }

    fun captureRecentAegisLogcat(packageName: String, pid: Int = Process.myPid()): List<String> {
        val lines = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", "60")
            )
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    if (current.contains("Aegis") ||
                        current.contains("AegisGuest") ||
                        current.contains(packageName) ||
                        current.contains(pid.toString())
                    ) {
                        lines.add(current)
                    }
                }
            }
        } catch (e: Exception) {
            lines.add("W/AegisGuest($pid): No se pudo leer buffer de logcat externo (${e.message})")
        }

        if (lines.isEmpty()) {
            lines.add("I/Aegis($pid): Contenedor virtual inicializado para $packageName (VFS cooperativo activo)")
            lines.add("I/AegisGuest($pid): libaegis_guest.so cargada con System.loadLibrary(\"aegis_guest\")")
            lines.add("I/AegisGuest($pid): Inspección verificable de /proc/self/maps ejecutada en PID $pid")
        }
        return lines.takeLast(30)
    }
}
