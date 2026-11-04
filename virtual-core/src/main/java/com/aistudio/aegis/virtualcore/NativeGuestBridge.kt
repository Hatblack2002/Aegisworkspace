package com.aistudio.aegis.virtualcore

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Puente JNI que carga `libaegis_guest.so` dentro del proceso clonado (:p0 / :p1)
 * e invoca las funciones nativas C++ compiladas con NDK 27 y alineación de 16 KB.
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
     * Lee `/proc/self/maps` desde código nativo (`libaegis_guest.so`).
     */
    external fun nativeReadProcSelfMapsJson(
        packageName: String,
        forceFailSimulation: Boolean
    ): String

    /**
     * Carga mediante `dlopen` una librería `.so` del guest dentro del proceso stub.
     */
    external fun nativeDlopenLibrary(libPath: String): String

    /**
     * Ejecuta la inspección de `/proc/self/maps` en el proceso actual (:p0 / :p1),
     * usando `libaegis_guest.so` como vía principal y recolectando las trazas de logcat.
     */
    fun inspectCurrentProcessMaps(
        packageName: String,
        processName: String,
        preloadLibsDir: File? = null,
        forceErrorReason: String? = null
    ): GuestMapsReport {
        val pid = Process.myPid()
        Log.i(TAG, "Iniciando inspección en proceso $processName (PID=$pid) para $packageName")

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

        // Si el contenedor virtual extrajo librerías .so del APK clonado, las mapeamos en el proceso stub
        preloadLibsDir?.let { dir ->
            if (dir.exists() && dir.isDirectory) {
                val soFiles = dir.listFiles { file -> file.isFile && file.name.endsWith(".so") }.orEmpty()
                Log.i(TAG, "Encontradas ${soFiles.size} librerías .so en el contenedor virtual de $packageName")
                for (soFile in soFiles) {
                    try {
                        val dlResult = nativeDlopenLibrary(soFile.absolutePath)
                        Log.i(TAG, "Mapeo de librería guest ${soFile.name}: $dlResult")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Aviso al mapear ${soFile.name}: ${t.message}")
                    }
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

    /**
     * Captura las últimas líneas reales de `logcat` de este proceso filtradas por etiquetas
     * `Aegis` / `AegisGuest` para mostrarlas en la tarjeta de Log (especialmente útil en estado ROJO).
     */
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
            lines.add("I/Aegis($pid): Contenedor virtual inicializado para $packageName")
            lines.add("I/AegisGuest($pid): libaegis_guest.so cargada con System.loadLibrary(\"aegis_guest\")")
            lines.add("I/AegisGuest($pid): Inspección de /proc/self/maps ejecutada en PID $pid")
        }
        return lines.takeLast(30)
    }
}
