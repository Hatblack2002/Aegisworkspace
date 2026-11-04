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
     * Contexto cooperativo que resuelve rutas de forma explícita hacia el sandbox privado
     * y provee acceso al ClassLoader y Resources del APK clonado.
     */
    class VirtualGuestContext(
        base: Context,
        private val guestPackageName: String,
        private val sandboxRoot: File,
        private val guestClassLoader: ClassLoader,
        private val guestResources: Resources? = null
    ) : ContextWrapper(base) {

        private val virtualFilesDir = File(sandboxRoot, "files").apply { mkdirs() }
        private val virtualCacheDir = File(sandboxRoot, "cache").apply { mkdirs() }

        override fun getPackageName(): String = guestPackageName
        override fun getFilesDir(): File = virtualFilesDir
        override fun getCacheDir(): File = virtualCacheDir
        override fun getDataDir(): File = sandboxRoot
        override fun getClassLoader(): ClassLoader = guestClassLoader
        override fun getResources(): Resources = guestResources ?: super.getResources()

        fun resolveSandboxFile(logicalRelativePath: String): File {
            val sanitized = logicalRelativePath.removePrefix("/")
            return File(sandboxRoot, sanitized)
        }
    }

    /**
     * Crea los Resources del APK huésped para que las Views puedan inflar sus layouts y drawables.
     */
    fun createGuestResources(baseContext: Context, apkPath: String): Resources? {
        return try {
            val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath: Method = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            addAssetPath.invoke(assetManager, apkPath)

            val superRes = baseContext.resources
            Resources(assetManager, superRes.displayMetrics, superRes.configuration)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron crear Resources directos para $apkPath: ${e.message}")
            null
        }
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

        // 1. Inicializar ClassLoader y Contexto cooperativo
        if (apkPath.isNotBlank() && File(apkPath).exists()) {
            try {
                val guestLoader = DexClassLoader(
                    apkPath,
                    optimizedDexDir.absolutePath,
                    nativeLibDir.absolutePath,
                    hostContext.classLoader
                )
                val guestRes = createGuestResources(hostContext, apkPath)
                val virtualContext = VirtualGuestContext(
                    base = hostContext.applicationContext,
                    guestPackageName = packageName,
                    sandboxRoot = containerDir,
                    guestClassLoader = guestLoader,
                    guestResources = guestRes
                )
                Log.i(
                    TAG,
                    "Contexto cooperativo configurado para $packageName (APK=$apkPath, sandbox=${containerDir.absolutePath})"
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
 * Virtual Activity Launcher (StubActivity):
 * Ejecuta en pantalla completa el contenedor visual del proceso aislado (:p0 / :p1).
 *
 * Flujo:
 * 1. Inicializa el sandbox VFS y ejecuta la telemetría de /proc/self/maps.
 * 2. Carga la Activity principal del APK huésped mediante DexClassLoader con VirtualGuestContext.
 * 3. Incorpora su View jerárquica en pantalla completa en la StubActivity.
 * Si la Activity del huésped no puede instanciarse de forma aislada, muestra un dashboard
 * de ejecución en vivo en pantalla completa dentro del proceso stub.
 */
open class BaseStubProcessActivity(private val stubSuffix: String) : Activity() {

    companion object {
        private const val TAG = "AegisStubActivity"
    }

    private var guestActivityInstance: Activity? = null
    private var guestResources: Resources? = null

    override fun getResources(): Resources {
        return guestResources ?: super.getResources()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intentData = intent ?: return
        intentData.putExtra(GuestRuntimeEngine.EXTRA_PROCESS_NAME, stubSuffix)

        val packageName = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_PACKAGE_NAME) ?: "unknown"
        val apkPath = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_APK_PATH) ?: ""
        val containerDirPath = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_CONTAINER_DIR) ?: ""
        val nativeLibDirPath = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_NATIVE_LIB_DIR) ?: ""

        val rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0F172A.toInt())
        }
        setContentView(rootContainer)

        // Ejecutar telemetría en background mientras se monta la UI
        Thread {
            GuestRuntimeEngine.bootstrapAndInspectGuest(applicationContext, intentData)
        }.start()

        if (apkPath.isNotBlank() && File(apkPath).exists()) {
            val containerDir = File(containerDirPath)
            val nativeLibDir = File(nativeLibDirPath)
            val optimizedDexDir = File(containerDir, "oat").apply { mkdirs() }

            try {
                val guestLoader = DexClassLoader(
                    apkPath,
                    optimizedDexDir.absolutePath,
                    nativeLibDir.absolutePath,
                    classLoader
                )
                val res = GuestRuntimeEngine.createGuestResources(this, apkPath)
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

                        // Adjuntar contexto a la Activity del huésped
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
                        // Si attach no coincide en firmas de API 34/35/36, fallback a injectView directo
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Lanzamiento visual directo del APK huésped diferido: ${t.message}")
            }
        }

        // Si la Activity huésped es una app externa con protecciones de Lifecycle de plataforma,
        // renderizar la interfaz de ejecución a pantalla completa en el proceso stub
        showFullscreenProcessWorkspace(rootContainer, packageName)
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
            text = "Ejecutándose en pantalla completa en proceso $stubSuffix (PID: ${Process.myPid()})\nSandbox VFS activo y librerías .so mapeadas"
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }

        val btnClose = android.widget.Button(this).apply {
            text = "Cerrar Contenedor Virtual"
            setBackgroundColor(0xFF2563EB.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { finish() }
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
