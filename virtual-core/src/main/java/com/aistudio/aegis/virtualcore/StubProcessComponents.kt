package com.aistudio.aegis.virtualcore

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method

/**
 * Cargador y entorno de ejecución cooperativo para el proceso clonado (:p0, :p1).
 *
 * Correcciones v1.1 (pantalla blanca en Chrome/juegos):
 *  - El stub AHORA transmuta de verdad: instala [AegisInstrumentation] en el
 *    ActivityThread del proceso y lanza el Activity real del invitado; el huesped
 *    ejecuta su onCreate con su propio tema y recursos (antes la instancia se creaba
 *    sin attach() y nunca entraba en ciclo de vida: el huesped jamás se dibujaba).
 *  - VirtualGuestContext redirige TODO el almacenamiento al sandbox real del proceso
 *    de Aegis. Las rutas estándar del invitado (/data/user/0/<pkg>/) eran inaccesibles
 *    (EACCES: otro UID) y crasheaban al primer File(getFilesDir()).
 *  - El Application del invitado se adjunta con Application.attach() (no attachBaseContext)
 *    y su onCreate corre en el HILO PRINCIPAL del proceso stub.
 *  - Los Resources del invitado incluyen framework-res.apk (antes faltaba y el inflate
 *    de cualquier layout con atributos android: crasheaba -> pantalla blanca).
 *  - Un stub por launchMode (Chrome declara singleTask; varios juegos singleInstance).
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
    const val EXTRA_GUEST_CLASS = "extra_guest_class"

    /**
     * Contexto virtual del invitado (ParallelSpace-style). El invitado ve:
     *  - su propio package name y ApplicationInfo (patched hacia el sandbox);
     *  - su propio classloader y Resources (AssetManager aislado);
     *  - cada directorio escribible redirigido a /files/virtual_env/<pkg>/ del
     *    proceso de Aegis (mismo UID -> escritura real, sin EACCES);
     *  - SharedPreferences bajo espacio de nombres propio;
     *  - startActivity de componentes propios reenrutado al stub por launchMode.
     */
    class VirtualGuestContext(
        base: Context,
        private val guestPackageName: String,
        private val sandboxRoot: File,
        private val guestClassLoader: ClassLoader,
        private val guestResources: Resources? = null,
        private val guestAppInfo: ApplicationInfo? = null,
        private val guestApplication: Application? = null
    ) : android.content.ContextWrapper(base) {

        // ---- Identidad -------------------------------------------------------

        override fun getPackageName(): String = guestPackageName

        override fun getApplicationInfo(): ApplicationInfo = guestAppInfo ?: super.getApplicationInfo()

        override fun getClassLoader(): ClassLoader = guestClassLoader

        override fun getApplicationContext(): Context = guestApplication ?: super.getApplicationContext()

        override fun createPackageContext(name: String, flags: Int): Context =
            if (name == guestPackageName) this else super.createPackageContext(name, flags)

        // ---- Recursos del invitado -------------------------------------------

        override fun getAssets(): AssetManager = guestResources?.assets ?: super.getAssets()
        override fun getResources(): Resources = guestResources ?: super.getResources()

        // ---- Almacenamiento redirigido al sandbox -----------------------------

        private fun dir(child: String): File = File(sandboxRoot, child).apply { mkdirs() }

        override fun getFilesDir(): File = dir("files")
        override fun getCacheDir(): File = dir("cache")
        override fun getCodeCacheDir(): File = dir("code_cache")
        override fun getNoBackupFilesDir(): File = dir("no_backup")
        override fun getObbDir(): File = dir("obb")
        override fun getDir(name: String?, mode: Int): File = dir("app_${name ?: "misc"}")
        override fun getExternalFilesDir(type: String?): File? =
            dir(if (type.isNullOrBlank()) "external_files" else "external_files/$type")
        override fun getDatabasePath(name: String?): File = File(dir("databases"), name ?: "default.db")

        override fun openFileInput(name: String): FileInputStream =
            FileInputStream(File(getFilesDir(), name))

        override fun openFileOutput(name: String, mode: Int): FileOutputStream =
            FileOutputStream(File(getFilesDir(), name), (mode and Context.MODE_APPEND) != 0)

        override fun deleteFile(name: String): Boolean = File(getFilesDir(), name).delete()

        override fun getFileStreamPath(name: String): File = File(getFilesDir(), name)

        override fun openOrCreateDatabase(
            name: String?, mode: Int, factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        override fun openOrCreateDatabase(
            name: String?, mode: Int, factory: SQLiteDatabase.CursorFactory?,
            errorHandler: android.database.DatabaseErrorHandler?
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        override fun deleteDatabase(name: String?): Boolean = getDatabasePath(name).delete()

        /** SharedPreferences del invitado, guardadas en el proceso de Aegis con namespace propio. */
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            super.getSharedPreferences("$guestPackageName.${name ?: "default"}", mode)

        // ---- Enrutado de intents del invitado ---------------------------------

        private fun routedActivityIntent(intent: Intent): Intent {
            val stub = intent.component?.className
                ?.let { className -> stubActivityNameFor(className) }
                ?: "com.aistudio.aegis.virtualcore.StubProcessActivityP0"
            return GuestIntentRouter.route(intent, guestPackageName, stub)
        }

        override fun startActivity(intent: Intent) =
            super.startActivity(routedActivityIntent(intent))

        override fun startActivity(intent: Intent, options: Bundle?) =
            super.startActivity(routedActivityIntent(intent), options)
    }

    /** Stub (por launchMode y slot de proceso) para un componente del invitado. */
    private fun stubActivityNameFor(guestClassName: String): String = runCatching {
        val pkg = GuestSession.packageName
        val info = if (guestClassName.isNotBlank()) {
            GuestRuntimeEngine.hostContextForStubs?.packageManager
                ?.getActivityInfo(android.content.ComponentName(pkg, guestClassName), 0)
        } else null
        val suffix = if (GuestSession.stubProcessSuffix == ":p1") "P1" else "P0"
        when (info?.launchMode ?: ActivityInfo.LAUNCH_MULTIPLE) {
            ActivityInfo.LAUNCH_SINGLE_TOP -> "com.aistudio.aegis.virtualcore.StubProcessActivity${suffix}SingleTop"
            ActivityInfo.LAUNCH_SINGLE_TASK -> "com.aistudio.aegis.virtualcore.StubProcessActivity${suffix}SingleTask"
            ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "com.aistudio.aegis.virtualcore.StubProcessActivity${suffix}SingleInstance"
            else -> "com.aistudio.aegis.virtualcore.StubProcessActivity$suffix"
        }
    }.getOrDefault("com.aistudio.aegis.virtualcore.StubProcessActivityP0")

    /**
     * Construye un VirtualGuestContext sobre [base] con el estado de [GuestSession].
     * Usado tanto para el Application del invitado como para cada activity transmutada.
     */
    fun buildGuestContext(base: Context): VirtualGuestContext = VirtualGuestContext(
        base = base,
        guestPackageName = GuestSession.packageName,
        sandboxRoot = GuestSession.sandboxRoot ?: base.filesDir.parentFile ?: base.filesDir,
        guestClassLoader = GuestSession.classLoader ?: base.classLoader,
        guestResources = GuestSession.resources,
        guestAppInfo = GuestSession.appInfo,
        guestApplication = GuestSession.application
    )

    /** Contexto del host usado para resolver ActivityInfo de los invitados (proceso stub). */
    @Volatile var hostContextForStubs: Context? = null

    /**
     * Crea los Resources del APK huésped cargando el framework (framework-res + overlays),
     * base.apk y todos los split APKs. SIN el framework en el AssetManager, cualquier
     * atributo android: del layout/tema del invitado no resuelve -> inflate crashea.
     */
    fun createGuestResources(baseContext: Context, apkFiles: List<File>): Resources? {
        if (apkFiles.isEmpty()) return null
        return try {
            HiddenApiExempt.exemptAll()
            val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance() as AssetManager
            val addAssetPath: Method = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            var added = 0
            // 1) Framework primero: RRO overlays incluidos en API 29+, fallback framework-res.apk.
            for (frameworkPath in frameworkAssetPaths()) {
                val cookie = addAssetPath.invoke(assetManager, frameworkPath) as Int
                if (cookie != 0) added++
            }
            // 2) base.apk + split_config.*.apk del invitado.
            for (apk in apkFiles) {
                if (apk.exists()) {
                    val cookie = addAssetPath.invoke(assetManager, apk.absolutePath) as Int
                    if (cookie != 0) added++
                    Log.i(TAG, "AssetPath cargado en Resources: ${apk.name}")
                }
            }
            if (added <= apkFiles.size) {
                Log.w(TAG, "AssetManager sin paths del framework; los temas android: pueden no resolver")
            }
            val superRes = baseContext.resources
            Resources(assetManager, superRes.displayMetrics, superRes.configuration)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron crear Resources combinados: ${e.message}")
            null
        }
    }

    /** Rutas de assets del framework vía reflexión (getApkAssets/getAssetPath son APIs ocultas). */
    private fun frameworkAssetPaths(): List<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val assets = Resources.getSystem().assets
            val apkAssets = AssetManager::class.java.getDeclaredMethod("getApkAssets")
                .apply { isAccessible = true }
                .invoke(assets) as Array<*>
            apkAssets.mapNotNull { entry ->
                runCatching {
                    entry!!.javaClass.getDeclaredMethod("getAssetPath")
                        .apply { isAccessible = true }
                        .invoke(entry) as? String
                }.getOrNull()
            }.filterNotNull()
        } else emptyList()
    }.getOrNull().orEmpty().ifEmpty {
        listOfNotNull("/system/framework/framework-res.apk".takeIf { File(it).exists() })
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

    // ---- ClassLoader compartido por proceso ----------------------------------

    private val loadersByPackage = HashMap<String, DexClassLoader>()

    /** DexClassLoader por paquete, compartido entre telemetría y UI (mismas clases, un loader). */
    @Synchronized
    fun loaderFor(
        context: Context,
        packageName: String,
        allApkFiles: List<File>,
        containerDir: File,
        nativeLibDir: File
    ): DexClassLoader? {
        loadersByPackage[packageName]?.let { return it }
        val dexClassPath = if (allApkFiles.isNotEmpty()) {
            allApkFiles.joinToString(File.pathSeparator) { it.absolutePath }
        } else {
            return null
        }
        val optimizedDexDir = File(containerDir, "oat").apply { mkdirs() }
        return try {
            DexClassLoader(
                dexClassPath,
                optimizedDexDir.absolutePath,
                nativeLibDir.absolutePath,
                context.classLoader
            ).also { loadersByPackage[packageName] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo crear DexClassLoader de $packageName: ${t.message}")
            null
        }
    }

    // ---- Application del invitado (hilo principal) ----------------------------

    private val hostedApplications = HashMap<String, Application>()

    /**
     * Adjunta y arranca el Application del invitado con Application.attach() — no
     * attachBaseContext, que deja el objeto medio inicializado — y ejecuta onCreate().
     * Debe llamarse en el HILO PRINCIPAL del proceso stub (los SDKs del invitado
     * asumen Looper.main() durante onCreate).
     */
    @Synchronized
    fun hostGuestApplication(
        mainThreadContext: Context,
        packageName: String,
        guestLoader: ClassLoader,
        virtualContext: VirtualGuestContext
    ): Application? {
        hostedApplications[packageName]?.let { return it }
        return try {
            val appClassName = try {
                mainThreadContext.packageManager.getApplicationInfo(packageName, 0).className
            } catch (_: Exception) { null }
            if (appClassName.isNullOrBlank()) return null
            val clazz = guestLoader.loadClass(appClassName)
            if (!Application::class.java.isAssignableFrom(clazz)) return null
            val guestApp = clazz.getDeclaredConstructor().newInstance() as Application
            val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(guestApp, virtualContext)
            GuestSession.application = guestApp
            try {
                guestApp.onCreate()
            } catch (t: Throwable) {
                Log.w(TAG, "guest Application.onCreate falló (no fatal): ${t.message}")
            }
            hostedApplications[packageName] = guestApp
            Log.i(TAG, "Application del invitado alojado en hilo principal: $appClassName")
            guestApp
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo alojar el Application de $packageName (no fatal): ${t.message}")
            null
        }
    }

    /** Lanza el Activity real del invitado a través del stub que corresponda a su launchMode. */
    fun launchGuestActivityFrom(activity: Activity, guestClassName: String): Boolean {
        val pkg = GuestSession.packageName
        if (pkg.isBlank()) return false
        return try {
            val info = try {
                activity.packageManager.getActivityInfo(android.content.ComponentName(pkg, guestClassName), 0)
            } catch (_: Exception) { null }
            val suffix = if (GuestSession.stubProcessSuffix == ":p1") "P1" else "P0"
            val stubClass = when (info?.launchMode ?: ActivityInfo.LAUNCH_MULTIPLE) {
                ActivityInfo.LAUNCH_SINGLE_TOP -> StubProcessActivityP0SingleTop::class.java
                ActivityInfo.LAUNCH_SINGLE_TASK -> StubProcessActivityP0SingleTask::class.java
                ActivityInfo.LAUNCH_SINGLE_INSTANCE -> StubProcessActivityP0SingleInstance::class.java
                else -> StubProcessActivityP0::class.java
            }.let { standard ->
                if (suffix == "P1") {
                    when (standard) {
                        StubProcessActivityP0SingleTop::class.java -> StubProcessActivityP1SingleTop::class.java
                        StubProcessActivityP0SingleTask::class.java -> StubProcessActivityP1SingleTask::class.java
                        StubProcessActivityP0SingleInstance::class.java -> StubProcessActivityP1SingleInstance::class.java
                        else -> StubProcessActivityP1::class.java
                    }
                } else standard
            }
            val launchIntent = Intent(activity, stubClass).apply {
                putExtra(EXTRA_PACKAGE_NAME, pkg)
                putExtra(EXTRA_GUEST_CLASS, guestClassName)
                putExtra(EXTRA_LAUNCH_UI, true)
            }
            activity.startActivity(launchIntent)
            Log.i(TAG, "lanzamiento del invitado por stub=${stubClass.simpleName}: $guestClassName")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "lanzamiento del invitado falló: ${t.message}")
            false
        }
    }

    /**
     * Arranque del contenedor cooperativo en el proceso actual (`:p0` / `:p1`).
     * Responsable SOLO de telemetría (mapeo de librerías + /proc/self/maps + reporte).
     * La transmutación de UI vive en [BaseStubProcessActivity].
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

        val allApkFiles = resolveAllApkFiles(hostContext, packageName, apkPath)
        val guestLoader = loaderFor(hostContext, packageName, allApkFiles, containerDir, nativeLibDir)

        // Publicar la sesión (el stub activity ya lo habrá hecho si vino por UI).
        if (GuestSession.packageName.isBlank()) {
            GuestSession.packageName = packageName
            GuestSession.classLoader = guestLoader
            GuestSession.sandboxRoot = containerDir
            GuestSession.nativeLibDir = nativeLibDir
            GuestSession.stubProcessSuffix = processName
        }

        // Telemetría: el Application del invitado se aloja SOLO desde el stub activity
        // (hilo principal). Aquí correr en Thread{} rompería SDKs que exigen Looper.main().
        if (guestLoader != null && Looper.myLooper() == Looper.getMainLooper()) {
            val guestRes = createGuestResources(hostContext, allApkFiles)
            if (GuestSession.resources == null) GuestSession.resources = guestRes
            hostGuestApplication(
                hostContext, packageName, guestLoader, buildGuestContext(hostContext.applicationContext)
            )
        }

        // Inspección cooperativa de librerías y /proc/self/maps
        val report = NativeGuestBridge.inspectCurrentProcessMaps(
            packageName = packageName,
            processName = processName,
            preloadLibsDir = nativeLibDir,
            sandboxRootDir = containerDir,
            forceErrorReason = null
        )

        // Transmisión del reporte al host
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
            GuestSession.stubProcessSuffix = stubSuffix
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
 * Virtual Activity Launcher (StubActivity) con transmutación REAL del invitado.
 *
 * Flujo corregido (v1.1):
 *  1. Sandbox de fallos + crash handler nativo;
 *  2. Estado de sesión (paquete, classloader, recursos) publicado en [GuestSession];
 *  3. [AegisInstrumentation] instalada en el ActivityThread del proceso stub;
 *  4. Application del invitado alojado en el hilo principal;
 *  5. startActivity del LAUNCHER real del invitado -> el framework crea la instancia
 *     del invitado vía nuestra Instrumentation, transmuta mBase y ejecuta su onCreate
 *     con su propio tema y recursos. FIN de la pantalla blanca.
 *
 * Si la transmutación no está disponible, se muestra el workspace de respaldo
 * (nunca un proceso muerto ni una pantalla en blanco).
 */
open class BaseStubProcessActivity(private val stubSuffix: String) : Activity() {

    companion object {
        private const val TAG = "AegisStubActivity"
    }

    private var isTerminatingCleanly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sandbox de errores Java/Kotlin del proceso stub
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
        val launchUi = intentData.getBooleanExtra(GuestRuntimeEngine.EXTRA_LAUNCH_UI, true)

        // Crash handler nativo (SIGSEGV, SIGABRT) del invitado
        try {
            NativeGuestBridge.ensureLoaded()
            NativeGuestBridge.nativeInstallCrashHandler()
        } catch (_: Exception) {}

        // Pantalla de carga/respaldo: visible mientras el invitado transmuta
        // (o para siempre si la transmutación no está disponible).
        val rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0F172A.toInt())
        }
        setContentView(rootContainer)

        // Preparación del entorno del invitado en el HILO PRINCIPAL: los SDKs del
        // huesped asumen Looper.main() durante Application.onCreate.
        val containerDir = if (containerDirPath.isNotBlank()) File(containerDirPath)
            else File(filesDir, "virtual_env/$packageName").apply { mkdirs() }
        val nativeLibDir = if (nativeLibDirPath.isNotBlank()) File(nativeLibDirPath)
            else File(containerDir, "lib").apply { mkdirs() }
        containerDir.mkdirs(); nativeLibDir.mkdirs()

        var transmutationReady = false
        try {
            HiddenApiExempt.exemptAll()

            val allApkFiles = GuestRuntimeEngine.resolveAllApkFiles(this, packageName, apkPath)
            val guestLoader = GuestRuntimeEngine.loaderFor(this, packageName, allApkFiles, containerDir, nativeLibDir)
            if (guestLoader != null) {
                val guestRes = GuestRuntimeEngine.createGuestResources(this, allApkFiles)

                // ApplicationInfo patcheado hacia el sandbox (dataDir/lib accesibles).
                val patchedAppInfo = try {
                    ApplicationInfo(packageManager.getApplicationInfo(packageName, 0)).apply {
                        dataDir = containerDir.absolutePath
                        deviceProtectedDataDir = File(containerDir, "device_protected").absolutePath
                        nativeLibraryDir = nativeLibDir.absolutePath
                        processName = packageName
                    }
                } catch (_: Exception) { null }

                // Publicar la sesión ANTES de instalar la Instrumentation.
                GuestSession.packageName = packageName
                GuestSession.hostPackage = applicationContext.packageName
                GuestSession.stubProcessSuffix = stubSuffix
                GuestSession.classLoader = guestLoader
                GuestSession.resources = guestRes
                GuestSession.sandboxRoot = containerDir
                GuestSession.nativeLibDir = nativeLibDir
                GuestSession.appInfo = patchedAppInfo
                GuestRuntimeEngine.hostContextForStubs = applicationContext

                // Application del invitado (attach + onCreate) en el hilo principal.
                val guestApp = GuestRuntimeEngine.hostGuestApplication(
                    applicationContext, packageName, guestLoader,
                    GuestRuntimeEngine.buildGuestContext(applicationContext)
                )
                if (guestApp != null) {
                    Log.i(TAG, "Application del invitado listo (hilo principal): $packageName")
                }

                // Hooks de transmutación: los stubs pasan a ser actividades reales del invitado.
                val hooksOk = ActivityThreadHook.installIfNeeded()
                Log.i(TAG, "Instrumentation transmutadora ${if (hooksOk) "instalada" else "NO disponible"} en $stubSuffix")

                if (launchUi && hooksOk) {
                    val guestClassName = intentData.getStringExtra(GuestRuntimeEngine.EXTRA_GUEST_CLASS)
                        ?: findLaunchActivityClassName(packageName)
                    if (!guestClassName.isNullOrBlank() &&
                        GuestRuntimeEngine.launchGuestActivityFrom(this, guestClassName)
                    ) {
                        transmutationReady = true
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Transmutación del invitado diferida: ${t.message}")
        }

        if (!transmutationReady) {
            // Respaldo honesto: workspace aislado + telemetría, sin fingir UI del invitado.
            showFullscreenProcessWorkspace(rootContainer, packageName)
        }

        // Telemetría asíncrona (mapeo .so + /proc/self/maps + reporte al host)
        Thread {
            try {
                GuestRuntimeEngine.bootstrapAndInspectGuest(applicationContext, intentData)
            } catch (t: Throwable) {
                Log.w(TAG, "Telemetría en stub activity aislada: ${t.message}")
            }
        }.start()
    }

    /**
     * Captura y aísla fallos de ejecución de la app clonada: notifica al host,
     * finaliza esta Activity y termina SOLO el proceso stub (:p0/:p1). Aegis nunca se cierra.
     */
    private fun installStubCrashSandbox() {
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
        }

        val centerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
                "Sandbox VFS: /files/virtual_env/$packageName/\n" +
                "Transmutación de UI no disponible para este invitado; " +
                "las librerías .so siguen mapeándose e informando al host."
            textSize = 14f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        }

        val btnClose = Button(this).apply {
            text = "Cerrar Contenedor Virtual"
            setBackgroundColor(0xFF2563EB.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                isTerminatingCleanly = true
                finish()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
    }
}

class StubProcessActivityP0 : BaseStubProcessActivity(":p0")
class StubProcessActivityP1 : BaseStubProcessActivity(":p1")
class StubProcessActivityP0SingleTop : BaseStubProcessActivity(":p0")
class StubProcessActivityP0SingleTask : BaseStubProcessActivity(":p0")
class StubProcessActivityP0SingleInstance : BaseStubProcessActivity(":p0")
class StubProcessActivityP1SingleTop : BaseStubProcessActivity(":p1")
class StubProcessActivityP1SingleTask : BaseStubProcessActivity(":p1")
class StubProcessActivityP1SingleInstance : BaseStubProcessActivity(":p1")
