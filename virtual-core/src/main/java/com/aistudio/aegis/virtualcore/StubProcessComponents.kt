package com.aistudio.aegis.virtualcore

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Cargador y entorno de ejecución aislado para el proceso clonado (:p0, :p1).
 *
 * Responsabilidades:
 * 1. Redirigir el directorio de datos del proceso guest al sandbox privado del host.
 * 2. Crear un `DexClassLoader` apuntando al APK de la app clonada y a su directorio `lib/`
 *    con las librerías `.so` extraídas por el motor virtual.
 * 3. Inicializar la clase `Application` del guest dentro del contexto redirigido.
 * 4. Cargar `System.loadLibrary("aegis_guest")`, mapear las librerías nativas del guest,
 *    leer `/proc/self/maps` del proceso clonado (`:p0` / `:p1`) y enviar el reporte JSON
 *    al host mediante `AegisReportProvider`.
 */
object GuestRuntimeEngine {
    private const val TAG = "AegisGuest"

    const val EXTRA_PACKAGE_NAME = "extra_guest_package_name"
    const val EXTRA_APK_PATH = "extra_guest_apk_path"
    const val EXTRA_CONTAINER_DIR = "extra_guest_container_dir"
    const val EXTRA_NATIVE_LIB_DIR = "extra_guest_native_lib_dir"
    const val EXTRA_PROCESS_NAME = "extra_guest_process_name"
    const val EXTRA_LAUNCH_UI = "extra_guest_launch_ui"
    const val EXTRA_FORCE_ERROR = "extra_guest_force_error"

    /**
     * Contexto envuelto que redirige `filesDir`, `cacheDir`, `dataDir` y `SharedPreferences`
     * al contenedor virtual asignado por Aegis Engine para el paquete guest.
     */
    class VirtualGuestContext(
        base: Context,
        private val guestPackageName: String,
        private val sandboxRoot: File,
        private val guestClassLoader: ClassLoader
    ) : ContextWrapper(base) {

        private val virtualFilesDir = File(sandboxRoot, "files").apply { mkdirs() }
        private val virtualCacheDir = File(sandboxRoot, "cache").apply { mkdirs() }

        override fun getPackageName(): String = guestPackageName
        override fun getFilesDir(): File = virtualFilesDir
        override fun getCacheDir(): File = virtualCacheDir
        override fun getDataDir(): File = sandboxRoot
        override fun getClassLoader(): ClassLoader = guestClassLoader
    }

    /**
     * Ejecuta el arranque del contenedor guest dentro del proceso actual (`:p0` / `:p1`),
     * carga el APK y sus librerías `.so`, invoca `libaegis_guest.so` para leer `/proc/self/maps`,
     * y transmite el resultado JSON al proceso host.
     */
    fun bootstrapAndInspectGuest(
        hostContext: Context,
        intent: Intent
    ): GuestMapsReport {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "unknown"
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: ""
        val containerDirPath = intent.getStringExtra(EXTRA_CONTAINER_DIR) ?: ""
        val nativeLibDirPath = intent.getStringExtra(EXTRA_NATIVE_LIB_DIR) ?: ""
        val processName = intent.getStringExtra(EXTRA_PROCESS_NAME) ?: ":p0"
        val forceError = intent.getStringExtra(EXTRA_FORCE_ERROR)

        val pid = Process.myPid()
        Log.i(TAG, "Arrancando contenedor virtual en $processName (PID=$pid) para paquete=$packageName")

        if (!forceError.isNullOrBlank()) {
            val report = NativeGuestBridge.inspectCurrentProcessMaps(
                packageName = packageName,
                processName = processName,
                preloadLibsDir = null,
                forceErrorReason = forceError
            )
            AegisReportProvider.sendReportToHost(hostContext, report)
            return report
        }

        val containerDir = if (containerDirPath.isNotBlank()) {
            File(containerDirPath).apply { mkdirs() }
        } else {
            File(hostContext.filesDir, "virtual_data/$packageName").apply { mkdirs() }
        }

        val nativeLibDir = if (nativeLibDirPath.isNotBlank()) {
            File(nativeLibDirPath).apply { mkdirs() }
        } else {
            File(containerDir, "lib").apply { mkdirs() }
        }

        val optimizedDexDir = File(containerDir, "oat").apply { mkdirs() }

        // 1. Inicializar DexClassLoader del Guest con sus librerías nativas
        if (apkPath.isNotBlank() && File(apkPath).exists()) {
            try {
                val guestLoader = DexClassLoader(
                    apkPath,
                    optimizedDexDir.absolutePath,
                    nativeLibDir.absolutePath,
                    hostContext.classLoader
                )
                val virtualContext = VirtualGuestContext(
                    base = hostContext.applicationContext,
                    guestPackageName = packageName,
                    sandboxRoot = containerDir,
                    guestClassLoader = guestLoader
                )
                Log.i(
                    TAG,
                    "ClassLoader virtual creado para $packageName (APK=$apkPath, nativeLibDir=${nativeLibDir.absolutePath})"
                )

                // Intentar instanciar Application del guest si está definida y no requiere firma del sistema
                try {
                    val appInfo = hostContext.packageManager.getApplicationInfo(packageName, 0)
                    val appClassName = appInfo.className
                    if (!appClassName.isNullOrBlank()) {
                        val clazz = guestLoader.loadClass(appClassName)
                        if (Application::class.java.isAssignableFrom(clazz)) {
                            val guestApp = clazz.getDeclaredConstructor().newInstance() as Application
                            val attachMethod = ContextWrapper::class.java.getDeclaredMethod(
                                "attachBaseContext",
                                Context::class.java
                            )
                            attachMethod.isAccessible = true
                            attachMethod.invoke(guestApp, virtualContext)
                            Log.i(TAG, "Application.attachBaseContext ejecutado en contenedor aislado: $appClassName")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Aviso al adjuntar Application de $packageName en modo aislado: ${t.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Aviso al crear DexClassLoader de $packageName: ${t.message}")
            }
        }

        // 2. Cargar libaegis_guest.so, mapear librerías del guest y leer /proc/self/maps
        val report = NativeGuestBridge.inspectCurrentProcessMaps(
            packageName = packageName,
            processName = processName,
            preloadLibsDir = nativeLibDir,
            forceErrorReason = null
        )

        // 3. Enviar el resultado al Host mediante el ContentProvider
        val sent = AegisReportProvider.sendReportToHost(hostContext, report)
        Log.i(TAG, "Resultado enviado al Host desde $processName (ack=$sent, libs=${report.libs.size})")
        return report
    }
}

/**
 * Servicio stub ejecutándose en el proceso aislado `:p0`.
 * Permite lanzar el proceso clonado de forma inmediata y silenciosa mientras la UI muestra
 * los pasos de progreso ("Iniciando proceso…", "Leyendo librerías…") y redirige a la pantalla de Resultado.
 */
open class BaseStubProcessService(private val stubSuffix: String) : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val safeIntent = Intent(intent).apply {
                putExtra(GuestRuntimeEngine.EXTRA_PROCESS_NAME, stubSuffix)
            }
            Thread {
                GuestRuntimeEngine.bootstrapAndInspectGuest(applicationContext, safeIntent)
                stopSelf(startId)
            }.start()
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}

class StubProcessServiceP0 : BaseStubProcessService(":p0")
class StubProcessServiceP1 : BaseStubProcessService(":p1")

/**
 * Activity stub ejecutándose en el proceso aislado `:p0` / `:p1`.
 * Se utiliza cuando el usuario lanza un clon o cuando se requiere contenedor de Activity.
 */
open class BaseStubProcessActivity(private val stubSuffix: String) : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val currentIntent = intent
        if (currentIntent != null) {
            currentIntent.putExtra(GuestRuntimeEngine.EXTRA_PROCESS_NAME, stubSuffix)
            Thread {
                GuestRuntimeEngine.bootstrapAndInspectGuest(applicationContext, currentIntent)
                runOnUiThread {
                    finish()
                }
            }.start()
        } else {
            finish()
        }
    }
}

class StubProcessActivityP0 : BaseStubProcessActivity(":p0")
class StubProcessActivityP1 : BaseStubProcessActivity(":p1")
