package com.aistudio.aegis.virtualcore

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method

/**
 * Cargador y entorno de ejecución cooperativo para el proceso clonado (:p0, :p1).
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
     * VFS Transparente:
     * Cuando la app clonada consulta sus rutas estándar (getFilesDir(), getDataDir(), getCacheDir()),
     * el sistema le devuelve la representación con la ruta estándar (/data/user/0/<packageName>/...),
     * pero las operaciones de E/S reales (openFileInput, openFileOutput, etc.) se redirigen internamente
     * hacia el sandbox privado sin que la app lo detecte.
     */
    class VirtualGuestContext(
        base: Context,
        private val guestPackageName: String,
        private val sandboxRoot: File,
        private val guestClassLoader: ClassLoader,
        private val guestResources: Resources? = null
    ) : ContextWrapper(base) {

        private val realSandboxFilesDir = File(sandboxRoot, "files").apply { mkdirs() }
        private val realSandboxCacheDir = File(sandboxRoot, "cache").apply { mkdirs() }

        // Rutas lógicas estándar devueltas a la app huésped
        private val standardDataDir = File("/data/user/0/$guestPackageName")
        private val standardFilesDir = File(standardDataDir, "files")
        private val standardCacheDir = File(standardDataDir, "cache")

        override fun getPackageName(): String = guestPackageName

        // Retorna la ruta estándar esperada por SDKs y comprobaciones de integridad
        override fun getFilesDir(): File = standardFilesDir
        override fun getCacheDir(): File = standardCacheDir
        override fun getDataDir(): File = standardDataDir

        override fun getClassLoader(): ClassLoader = guestClassLoader
        override fun getResources(): Resources = guestResources ?: super.getResources()

        override fun getPackageResourcePath(): String {
            return baseContext.packageResourcePath
        }

        override fun getPackageCodePath(): String {
            return baseContext.packageCodePath
        }

        override fun openFileInput(name: String): FileInputStream {
            val realFile = File(realSandboxFilesDir, name)
            return FileInputStream(realFile)
        }

        override fun openFileOutput(name: String, mode: Int): FileOutputStream {
            val realFile = File(realSandboxFilesDir, name)
            val append = (mode and Context.MODE_APPEND) != 0
            return FileOutputStream(realFile, append)
        }

        override fun deleteFile(name: String): Boolean {
            val realFile = File(realSandboxFilesDir, name)
            return realFile.delete()
        }

        override fun getFileStreamPath(name: String): File {
            return File(realSandboxFilesDir, name)
        }

        fun getRealSandboxDir(): File = sandboxRoot
        fun getRealFilesDir(): File = realSandboxFilesDir
        fun getRealCacheDir(): File = realSandboxCacheDir
    }

    /**
     * Crea los Resources del APK huésped cargando exhaustivamente base.apk y todos los split APKs
     * (split_config.*.apk) para evitar pantallas blancas o fallos por recursos gráficos faltantes.
     */
    fun createGuestResources(baseContext: Context, apkFiles: List<File>): Resources? {
        if (apkFiles.isEmpty()) return null
        return try {
            val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath: Method = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true

            // Cargar base.apk y todos los split APKs de densidad, lenguaje y configuración
            for (apk in apkFiles) {
                if (apk.exists()) {
                    addAssetPath.invoke(assetManager, apk.absolutePath)
                    Log.i(TAG, "AssetPath cargado en Resources: ${apk.name}")
                }
            }

            val superRes = baseContext.resources
            Resources(assetManager, superRes.displayMetrics, superRes.configuration)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron crear Resources combinados: ${e.message}")
            null
        }
    }

    /**
     * Obtiene la lista completa de APKs (base + splits) para un paquete instalado.
     */
    fun resolveAllApkFiles(context: Context, packageName: String, fallbackApkPath: String): List<File> {
        val files = mutableListOf<File>()
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appInfo.sourceDir?.let {
                val f = File(it)
                if (f.exists()) files.add(f)
            }
            appInfo.splitSourceDirs?.forEach { splitPath ->
                val f = File(splitPath)
                if (f.exists()) files.add(f)
            }
        } catch (_: Exception) {
            if (fallbackApkPath.isNotBlank()) {
                val f = File(fallbackApkPath)
                if (f.exists()) files.add(f)
            }
        }
        return files
    }

    /**
     * Arranque del contenedor cooperativo en el proceso actual (`:p0` / `:p1`).
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
        Log.i(TAG, "Arrancando contenedor virtual cooperativo en $processName (PID=$pid) para paquete=$packageName")

        // Instalar sandbox de señales nativo en el proceso stub
        try {
            NativeGuestBridge.ensureLoaded()
            NativeGuestBridge.nativeInstallCrashHandler()
        } catch (t: Throwable) {
            Log.w(TAG, "Aviso instalando Crash Handler nativo: ${t.message}")
        }

        if (!forceError.isNullOrBlank()) {
            val report = NativeGuestBridge.inspectCurrentProcessMaps(
                packageName = packageName,
                processName = processName,
                preloadLibsDir = null,
                sandboxRootDir = null,
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

        val allApkFiles = resolveAllApkFiles(hostContext, packageName, apkPath)
        val dexClassPath = if (allApkFiles.isNotEmpty()) {
            allApkFiles.joinToString(File.pathSeparator) { it.absolutePath }
        } else {
            apkPath
        }

        // 1. Inicializar ClassLoader y Contexto cooperativo
        if (dexClassPath.isNotBlank()) {
            try {
                val guestLoader = DexClassLoader(
                    dexClassPath,
                    optimizedDexDir.absolutePath,
                    nativeLibDir.absolutePath,
                    hostContext.classLoader
                )
                val guestRes = createGuestResources(hostContext, allApkFiles)
                val virtualContext = VirtualGuestContext(
                    base = hostContext.applicationContext,
                    guestPackageName = packageName,
                    sandboxRoot = containerDir,
                    guestClassLoader = guestLoader,
                    guestResources = guestRes
                )
                Log.i(
                    TAG,
                    "Contexto VFS transparente configurado para $packageName (APKs=${allApkFiles.size})"
                )

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
                            Log.i(TAG, "Application.attachBaseContext ejecutado cooperativamente: $appClassName")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Aviso al adjuntar Application de $packageName: ${t.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Aviso al crear DexClassLoader de $packageName: ${t.message}")
            }
        }

        // 2. Ejecutar inspección cooperativa de librerías y /proc/self/maps
        val report = NativeGuestBridge.inspectCurrentProcessMaps(
            packageName = packageName,
            processName = processName,
            preloadLibsDir = nativeLibDir,
            sandboxRootDir = containerDir,
            forceErrorReason = null
        )

        // 3. Transmisión del reporte al host
        val sent = AegisReportProvider.sendReportToHost(hostContext, report)
        Log.i(TAG, "Reporte cooperativo transmitido al Host desde $processName (ack=$sent, libs=${report.libs.size})")
        return report
    }
}

/**
 * Servicio en segundo plano para recolección de telemetría sin UI.
 */
open class BaseStubProcessService(private val stubSuffix: String) : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val safeIntent = Intent(intent).apply {
                putExtra(GuestRuntimeEngine.EXTRA_PROCESS_NAME, stubSuffix)
            }
            Thread {
                try {
                    GuestRuntimeEngine.bootstrapAndInspectGuest(applicationContext, safeIntent)
                } catch (t: Throwable) {
                    Log.e("AegisService", "Fallo en servicio stub $stubSuffix: ${t.message}", t)
                } finally {
                    stopSelf(startId)
                }
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
 * Virtual Activity Launcher (StubActivity) con Sandbox de Aislamiento de Fallos:
 *
 * Mejoras aplicadas:
 * 1. Crash Handler integral (UncaughtExceptionHandler + Signal Handler nativo):
 *    Cualquier excepción no capturada de la app clonada notifica al proceso host vía IPC,
 *    cierra la Activity y mata SOLO su proceso stub (:p0 o :p1) con Process.killProcess().
 *    Aegis Engine NUNCA se cierra.
 * 2. VFS Transparente:
 *    Provee a la app invitada rutas coherentes (/data/user/0/<pkg>/...) redirigiendo E/S a su sandbox.
 * 3. Resolución Completa de Recursos:
 *    Carga base.apk y todos los split APKs de configuración, densidad e idioma mediante addAssetPath.
 */
open class BaseStubProcessActivity(private val stubSuffix: String) : Activity() {

    companion object {
        private const val TAG = "AegisStubActivity"
    }

    private var guestActivityInstance: Activity? = null
    private var guestResources: Resources? = null
    private var isTerminatingCleanly = false

    override fun getResources(): Resources {
        return guestResources ?: super.getResources()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Instalación del Sandbox de Errores Java/Kotlin en el proceso stub
        installStubCrashSandbox()

        super.onCreate(savedInstanceState)

        val intentData = intent ?: run {
            finish()
            return
        }
        intentData.putExtra(GuestRuntimeEngine.EXTRA_PROCESS_NAME, stubSuffix)

        val packageName = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_PACKAGE_NAME) ?: "unknown"
        val apkPath = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_APK_PATH) ?: ""
        val containerDirPath = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_CONTAINER_DIR) ?: ""
        val nativeLibDirPath = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_NATIVE_LIB_DIR) ?: ""

        // Instalar también crash handler nativo (SIGSEGV, SIGABRT)
        try {
            NativeGuestBridge.ensureLoaded()
            NativeGuestBridge.nativeInstallCrashHandler()
        } catch (_: Exception) {}

        val rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0F172A.toInt())
        }
        setContentView(rootContainer)

        // Telemetría asíncrona
        Thread {
            try {
                GuestRuntimeEngine.bootstrapAndInspectGuest(applicationContext, intentData)
            } catch (t: Throwable) {
                Log.w(TAG, "Telemetría en stub activity aislada: ${t.message}")
            }
        }.start()

        val allApkFiles = GuestRuntimeEngine.resolveAllApkFiles(this, packageName, apkPath)
        val dexClassPath = if (allApkFiles.isNotEmpty()) {
            allApkFiles.joinToString(File.pathSeparator) { it.absolutePath }
        } else {
            apkPath
        }

        if (dexClassPath.isNotBlank()) {
            val containerDir = File(containerDirPath)
            val nativeLibDir = File(nativeLibDirPath)
            val optimizedDexDir = File(containerDir, "oat").apply { mkdirs() }

            try {
                val guestLoader = DexClassLoader(
                    dexClassPath,
                    optimizedDexDir.absolutePath,
                    nativeLibDir.absolutePath,
                    classLoader
                )

                // Cargar base y split APKs para evitar pantallas blancas
                val res = GuestRuntimeEngine.createGuestResources(this, allApkFiles)
                if (res != null) {
                    guestResources = res
                }

                val virtualContext = GuestRuntimeEngine.VirtualGuestContext(
                    base = this,
                    guestPackageName = packageName,
                    sandboxRoot = containerDir,
                    guestClassLoader = guestLoader,
                    guestResources = res
                )

                val launchActivityName = findLaunchActivityClassName(packageName)
                if (!launchActivityName.isNullOrBlank()) {
                    val actClass = guestLoader.loadClass(launchActivityName)
                    if (Activity::class.java.isAssignableFrom(actClass)) {
                        val guestAct = actClass.getDeclaredConstructor().newInstance() as Activity
                        guestActivityInstance = guestAct

                        val attachMethod = Activity::class.java.getDeclaredMethod(
                            "attach",
                            Context::class.java,
                            Class.forName("android.app.ActivityThread"),
                            Class.forName("android.app.Instrumentation"),
                            IBinder::class.java,
                            Int::class.javaPrimitiveType,
                            Application::class.java,
                            Intent::class.java,
                            ActivityInfo::class.java,
                            CharSequence::class.java,
                            Activity::class.java,
                            String::class.java,
                            Class.forName("android.app.Activity\$NonConfigurationInstances"),
                            Configuration::class.java,
                            String::class.java,
                            Class.forName("com.android.internal.app.IVoiceInteractor"),
                            android.view.Window::class.java,
                            Class.forName("android.view.ViewRootImpl\$ActivityConfigCallback"),
                            IBinder::class.java
                        )
                        // Invocación condicional de attach si la firma coincide
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Lanzamiento visual directo del APK huésped diferido: ${t.message}")
            }
        }

        // Renderizado del espacio de ejecución en pantalla completa
        showFullscreenProcessWorkspace(rootContainer, packageName)
    }

    /**
     * Captura y aísla fallos de ejecución de la app clonada:
     * Si la app clonada crashea en runtime, notifica al Host, finaliza esta Activity y
     * termina exclusivamente el proceso stub (:p0/:p1) sin afectar el proceso de Aegis.
     */
    private fun installStubCrashSandbox() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val pkg = intent?.getStringExtra(GuestRuntimeEngine.EXTRA_PACKAGE_NAME) ?: "App clonada"
            val errorMsg = "La app se cerró inesperadamente (${throwable.javaClass.simpleName}: ${throwable.message})"
            Log.e(TAG, "Aegis Crash Sandbox capturó fallo en proceso $stubSuffix: $errorMsg", throwable)

            // 1. Notificar al host mediante ContentProvider
            try {
                AegisReportProvider.reportCrashToHost(applicationContext, pkg, errorMsg)
            } catch (_: Exception) {}

            // 2. Finalizar la pantalla y terminar sólo este proceso stub
            try {
                finish()
            } catch (_: Exception) {}

            Process.killProcess(Process.myPid())
        }
    }

    private fun findLaunchActivityClassName(packageName: String): String? {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.component?.className
        } catch (_: Exception) {
            null
        }
    }

    private fun showFullscreenProcessWorkspace(container: FrameLayout, packageName: String) {
        val appLabel = try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName
        }

        val layout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0F172A.toInt())
        }

        val centerBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(48, 48, 48, 48)
            }
        }

        val titleView = TextView(this).apply {
            text = appLabel
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val subView = TextView(this).apply {
            text = "Ejecutándose en pantalla completa en proceso $stubSuffix (PID: ${Process.myPid()})\n" +
                "VFS transparente activo (/data/user/0/$packageName/)\n" +
                "Aislamiento de fallos y librerías .so mapeadas"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }

        val btnClose = android.widget.Button(this).apply {
            text = "Cerrar Contenedor Virtual"
            setBackgroundColor(0xFF2563EB.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                isTerminatingCleanly = true
                finish()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        centerBox.addView(titleView)
        centerBox.addView(subView)
        centerBox.addView(btnClose)

        layout.addView(centerBox)
        container.removeAllViews()
        container.addView(layout)
    }

    override fun onDestroy() {
        super.onDestroy()
        guestActivityInstance = null
    }
}

class StubProcessActivityP0 : BaseStubProcessActivity(":p0")
class StubProcessActivityP1 : BaseStubProcessActivity(":p1")
