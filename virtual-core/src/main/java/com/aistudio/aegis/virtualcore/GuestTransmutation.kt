package com.aistudio.aegis.virtualcore

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.LayoutInflater
import java.io.File
import java.lang.reflect.Field

/**
 * Transmutación real de actividades invitado (técnica VirtualApp, probada en Aegis-space v3).
 *
 * El framework crea el STUB declarado en el manifest; [AegisInstrumentation] sustituye
 * la instancia por la clase REAL del invitado (cargada con su DexClassLoader) y, antes
 * de `onCreate`, intercambia el ContextImpl de Aegis por un [GuestRuntimeEngine.VirtualGuestContext]
 * que envuelve el original: identidad, almacenamiento y recursos resuelven al INVITADO
 * mientras los servicios del sistema siguen funcionando por el ContextImpl real.
 *
 * Esta es la corrección de la pantalla blanca: antes el stub solo instanciaba la clase
 * del invitado (constructor) y jamás invocaba attach()/onCreate() — el huesped nunca
 * entraba en su ciclo de vida y la pantalla se quedaba en el placeholder.
 */
object GuestActivityTransmuter {
    private const val TAG = "AegisTransmuter"

    private val aliasCache = HashMap<String, String>()

    /**
     * activity-alias -> clase REAL (ActivityInfo.targetActivity). VLC (".StartActivity")
     * y YouTube (".Shell$HomeActivity") declaran su launcher como ALIAS: loadClass del
     * alias da ClassNotFoundException POR DISEÑO (el alias no es una clase). Se resuelve
     * siempre a la clase real antes de instanciar. Con caché por proceso.
     */
    internal fun resolveAliasClass(guestPackage: String, className: String): String {
        if (guestPackage.isBlank()) return className
        synchronized(aliasCache) { aliasCache["$guestPackage|$className"]?.let { return it } }
        val resolved = runCatching {
            val ctx = GuestRuntimeEngine.hostContextForStubs ?: ActivityThreadHook.currentApplication()
            val info = ctx?.packageManager?.getActivityInfo(ComponentName(guestPackage, className), 0)
            val target = info?.targetActivity
            if (!target.isNullOrBlank()) {
                AegisLog.i("activity-alias resuelto: $className -> $target")
                target
            } else className
        }.getOrDefault(className)
        synchronized(aliasCache) { aliasCache["$guestPackage|$className"] = resolved }
        return resolved
    }

    /** Decide qué clase invitada representa un lanzamiento (extra o namespace del invitado). */
    fun transmutableGuestClass(intent: Intent?, stubClassName: String?): String? {
        val fromExtra = intent?.getStringExtra(GuestRuntimeEngine.EXTRA_GUEST_CLASS)
        if (!fromExtra.isNullOrBlank()) return resolveAliasClass(GuestSession.packageName, fromExtra)
        val pkg = GuestSession.packageName
        if (pkg.isBlank()) return null
        val candidate = intent?.component?.className ?: stubClassName ?: return null
        return if (candidate.startsWith("$pkg.")) resolveAliasClass(pkg, candidate) else null
    }

    /**
     * Transmutación COMPLETA de un activity stub ya adjuntado por el framework:
     *  1. mBase -> VirtualGuestContext (identidad + almacenamiento + recursos del invitado);
     *  2. mApplication -> Application del invitado (evita ClassCastException en casts propios);
     *  3. mComponent/mActivityInfo -> los del invitado (incluido su tema de manifest);
     *  4. intent.component -> la clase real del invitado.
     * Idempotente y con reflexión protegida: cada paso degrada sin romper el proceso.
     */
    fun prepareGuestActivity(activity: Activity) {
        val intent = activity.intent
        val guestClassName = transmutableGuestClass(intent, activity.javaClass.name) ?: return
        val packageName = GuestSession.packageName
        if (packageName.isBlank()) return
        runCatching { intent.component = ComponentName(packageName, guestClassName) }

        // 1. Contexto virtual (LA corrección de la pantalla blanca).
        if (baseContextOf(activity) !is GuestRuntimeEngine.VirtualGuestContext) {
            val original = baseContextOf(activity)
            if (original != null) {
                val virtual = GuestRuntimeEngine.buildGuestContext(original)
                val swapped = runCatching { contextWrapperBaseField?.set(activity, virtual) }.isSuccess
                Log.i(TAG, if (swapped) "mBase transmutado -> identidad $packageName"
                           else "no se pudo transmutar mBase de la actividad")
            }
        }

        // 1b. Invalidar caches de ContextThemeWrapper (mTheme/mResources/mInflater).
        // ContextThemeWrapper —superclase de Activity— cachea estos campos LA PRIMERA VEZ
        // que se consultan y quedan anclados a los recursos del HOST (recursos del stub).
        // Con ellos cacheados, inflate de layouts/temas del invitado resuelve IDs 0x7f
        // contra los recursos equivocados -> NotFoundException -> pantalla blanca/crash.
        clearThemedContextCaches(activity)

        // 1c. CRÍTICO (pantalla roja/blanca en el PRIMER setContentView): PhoneWindow
        // cachea su PROPIO LayoutInflater en attach() ("mLayoutInflater =
        // LayoutInflater.from(context)") con el ContextImpl del STUB. clearThemedContextCaches
        // no alcanza: la Activity llama a setContentView -> mWindow.setContentView ->
        // mLayoutInflater.inflate(layoutRes) y ESE inflater resuelve los IDs 0x7f del
        // invitado contra los Resources del HOST -> Resources$NotFoundException.
        // Se sustituye por un clon ligado al contexto virtual (mismas factories,
        // getResources() -> AssetManager del invitado).
        patchWindowInflater(activity)

        // 2. getApplication() debe devolver el Application del invitado.
        GuestSession.application?.let { app ->
            runCatching { activityField("mApplication").set(activity, app) }
        }

        // 3. Componente propio visible para el invitado.
        runCatching { activityField("mComponent").set(activity, ComponentName(packageName, guestClassName)) }

        // 4. ActivityInfo + tema del manifest del invitado (el framework usó el del stub).
        val realInfo = runCatching {
            activity.packageManager.getActivityInfo(ComponentName(packageName, guestClassName), 0)
        }.getOrNull()
        if (realInfo != null) {
            runCatching { activityField("mActivityInfo").set(activity, realInfo) }
            val themeRes = realInfo.theme.takeIf { it != 0 }
                ?: GuestSession.appInfo?.theme?.takeIf { it != 0 }
            if (themeRes != null) {
                runCatching {
                    activity.setTheme(themeRes)
                    GuestLaunchTracker.ok(
                        "Theme del guest",
                        "0x${Integer.toHexString(themeRes)} aplicado a $guestClassName (antes del onCreate, AppCompat lo lee en lazy)"
                    )
                }
            } else {
                GuestLaunchTracker.unverified(
                    "Theme del guest",
                    "ni la actividad ni la aplicación declaran android:theme en el manifest del guest"
                )
            }
        } else {
            GuestLaunchTracker.unverified(
                "Theme del guest",
                "ActivityInfo del guest no resoluble en el PackageManager del host — theme del manifest no aplicado"
            )
        }
        Log.i(TAG, "actividad invitado preparada: $guestClassName")

        // Burbuja flotante sobre la UI del invitado (engine v2): permite disparar la
        // lectura de librerías desde DENTRO del juego sin salir de él.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            GuestFloatingBubble.attach(activity, packageName)
        }

        // Primera ventana visible + watchdog de pantalla blanca (engine v2.3):
        // si en 10s nada dibujó, el informe se genera con el estado real de cada paso.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            FirstDrawWatcher.watch(activity) {
                GuestLaunchTracker.markFirstWindow(activity.javaClass.simpleName)
            }
            GuestLaunchTracker.scheduleWindowWatchdog(activity.applicationContext)
        }
    }

    /**
     * ContextThemeWrapper cachea mResources / mTheme / mInflater de forma lazy sobre el
     * ContextImpl ORIGINAL (host). Se invalidan para que se re-creen sobre el nuevo mBase
     * (VirtualGuestContext -> recursos del invitado). Cada campo es best-effort: si el
     * nombre cambia entre versiones de Android, simplemente se omite.
     */
    private fun clearThemedContextCaches(activity: Activity) {
        var clazz: Class<*>? = activity.javaClass
        while (clazz != null && clazz != Any::class.java) {
            if (clazz.name == "android.view.ContextThemeWrapper") {
                for (fieldName in CACHEABLE_FIELDS) {
                    runCatching {
                        clazz.getDeclaredField(fieldName).apply { isAccessible = true }.set(activity, null)
                    }
                }
                return
            }
            clazz = clazz.superclass
        }
    }

    private val CACHEABLE_FIELDS = arrayOf("mTheme", "mResources", "mInflater")

    /**
     * Sustituye el LayoutInflater cacheado por PhoneWindow (creado en attach() contra el
     * ContextImpl del host) por un clon ligado al contexto virtual del invitado.
     * Best-effort: si el campo cambia de nombre entre ROMs, se degrada sin romper.
     */
    private fun patchWindowInflater(activity: Activity) {
        runCatching {
            val window = activity.window ?: return
            val virtualInflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as? LayoutInflater
                ?: return
            val field = window.javaClass.getDeclaredField("mLayoutInflater")
            field.isAccessible = true
            field.set(window, virtualInflater)
        }.onFailure {
            Log.w(TAG, "No se pudo parchear el inflater de la ventana: ${it.message}")
        }
    }

    private fun activityField(name: String): Field =
        Activity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun baseContextOf(activity: Activity): Context? =
        contextWrapperBaseField?.let { field ->
            runCatching { field.get(activity) as? Context }.getOrNull()
        }

    /** ContextWrapper.mBase — lazy y nullable: si la reflexión falla, se degrada al stub plano. */
    private val contextWrapperBaseField: Field? by lazy {
        runCatching {
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        }.getOrNull()
    }
}

/**
 * Estado de la sesión de un invitado dentro del proceso stub actual (:p0 / :p1).
 * Cada proceso sirve a UN invitado por lanzamiento; el estado se publica antes de
 * startActivity y lo consume la Instrumentation transmutadora (mismo hilo principal).
 */
object GuestSession {
    @Volatile var packageName: String = ""
    @Volatile var hostPackage: String = ""
    @Volatile var stubProcessSuffix: String = ":p0"
    @Volatile var classLoader: ClassLoader? = null
    @Volatile var resources: Resources? = null
    @Volatile var application: Application? = null
    @Volatile var sandboxRoot: File? = null
    @Volatile var nativeLibDir: File? = null
    @Volatile var appInfo: android.content.pm.ApplicationInfo? = null

    /** Último error de transmutación (diagnóstico visible en la pantalla de respaldo). */
    @Volatile var lastError: String = ""
}

/** Instrumentation transmutadora instalada en el ActivityThread del proceso stub. */
class AegisInstrumentation : Instrumentation() {

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
        val guestClass = GuestActivityTransmuter.transmutableGuestClass(intent, className)
        if (guestClass != null) {
            val loader = GuestSession.classLoader
            if (loader == null) {
                val detail = "GuestSession.classLoader es NULL — sesión no inicializada en este proceso (¿proceso reiniciado?)"
                GuestSession.lastError = "newActivity($guestClass): $detail"
                GuestLaunchTracker.fail("newActivity", "$guestClass — $detail")
                AegisLog.e("newActivity: $detail")
            } else {
                GuestLaunchTracker.loaderHashForNewActivity = System.identityHashCode(loader).toLong()
                runCatching {
                    val real = loader.loadClass(guestClass).getDeclaredConstructor().newInstance() as Activity
                    GuestLaunchTracker.ok(
                        "newActivity",
                        "$guestClass instanciada (loader=0x${Integer.toHexString(System.identityHashCode(loader))})"
                    )
                    Log.i("AegisInstrumentation", "STUB transmutado -> $guestClass")
                    return real
                }.onFailure {
                    val chain = GuestLaunchTracker.describeThrowable(it)
                    GuestSession.lastError = "newActivity($guestClass): $chain"
                    GuestLaunchTracker.exception(
                        it,
                        "newActivity: buscaba la clase $guestClass con el classloader del invitado (dexPath: ${GuestLaunchTracker.dexPath.ifBlank { "no registrado" }})"
                    )
                    GuestLaunchTracker.fail("newActivity", "$guestClass — $chain")
                }
            }
        }
        return super.newActivity(cl, className, intent)
    }

    /**
     * Regla IDENTIDAD_PERMISOS: Activity.startActivityForResult NO pasa por el Context
     * virtual (llama a Instrumentation.execStartActivity directamente). Sin esta
     * intercepción, cualquier pantalla interna que el guest abre desde su onCreate
     * llega al AMS con el componente del guest -> "not exported from uid" (crash de
     * MyFiles, informe p3530). Toda salida del guest se reescribe a un stub del host
     * llevando el componente real en extras — genérico, sin código por paquete.
     */
    override fun execStartActivity(
        who: Context?,
        contextThread: android.os.IBinder?,
        token: android.os.IBinder?,
        target: Activity?,
        intent: Intent?,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val guestPkg = GuestSession.packageName
        val comp = intent?.component
        if (intent != null && guestPkg.isNotBlank() && comp?.packageName == guestPkg) {
            val stub = comp.className?.let { stubActivityNameFor(it) }
                ?: "com.aistudio.aegis.virtualcore.StubProcessActivityP0"
            intent.component = ComponentName(GuestSession.hostPackage.ifBlank { guestPkg }, stub)
            intent.putExtra(GuestRuntimeEngine.EXTRA_GUEST_CLASS, comp.className)
            intent.putExtra(GuestRuntimeEngine.EXTRA_PACKAGE_NAME, guestPkg)
            AegisLog.i("execStartActivity enrutado: ${comp.className} -> $stub")
        }
        return super.execStartActivity(who, contextThread, token, target, intent, requestCode, options)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        trackedOnCreate(activity, icicle, null, withPersistent = false)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?, persistentState: PersistableBundle?) {
        trackedOnCreate(activity, icicle, persistentState, withPersistent = true)
    }

    /**
     * Rastrea el onCreate REAL: "Activity.onCreate" es OK solo si el onCreate corrió sin
     * lanzar excepción. La excepción se registra completa (con sus Caused by) y se
     * re-lanza: el crash sandbox la guardará en el informe antes de morir.
     */
    private fun trackedOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?,
        withPersistent: Boolean
    ) {
        GuestActivityTransmuter.prepareGuestActivity(activity)
        val proxyTarget = activity.intent?.getStringExtra(GuestRuntimeEngine.EXTRA_GUEST_CLASS)
        if (proxyTarget == null) {
            // Actividad del motor (bootstrap): no es parte del ciclo de vida del invitado.
            if (withPersistent) super.callActivityOnCreate(activity, icicle, persistentState!!)
            else super.callActivityOnCreate(activity, icicle)
            return
        }
        try {
            if (withPersistent) super.callActivityOnCreate(activity, icicle, persistentState!!)
            else super.callActivityOnCreate(activity, icicle)
            val who = if (activity is BaseStubProcessActivity) "stub (transmutación fallida)" else activity.javaClass.name
            GuestLaunchTracker.ok("Activity.onCreate", who)
        } catch (t: Throwable) {
            GuestLaunchTracker.exception(
                t,
                "Activity.onCreate de ${activity.javaClass.name} (proxy de $proxyTarget): el onCreate lanzó una excepción"
            )
            GuestLaunchTracker.fail("Activity.onCreate", GuestLaunchTracker.describeThrowable(t))
            throw t
        }
    }
}

/** Reflexión sobre el ActivityThread del proceso actual (API oculta, exenta por HiddenApiExempt). */
object ActivityThreadHook {
    fun currentActivityThread(): Any? = runCatching {
        HiddenApiExempt.exemptAll()
        val atClass = Class.forName("android.app.ActivityThread")
        atClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
    }.getOrNull()

    /** Application del proceso actual vía ActivityThread (útil antes de que exista contexto). */
    fun currentApplication(): Application? = runCatching {
        HiddenApiExempt.exemptAll()
        val atClass = Class.forName("android.app.ActivityThread")
        atClass.getDeclaredMethod("currentApplication").apply { isAccessible = true }
            .invoke(null) as? Application
    }.getOrNull()

    /** Sustituye la Instrumentation del proceso; false cuando la reflexión está bloqueada. */
    fun installInstrumentation(replacement: Instrumentation): Boolean = runCatching {
        val at = currentActivityThread() ?: return@runCatching false
        val field = at.javaClass.getDeclaredField("mInstrumentation")
        field.isAccessible = true
        field.set(at, replacement)
        true
    }.getOrDefault(false)

    fun isAlreadyInstalled(): Boolean = runCatching {
        val at = currentActivityThread() ?: return@runCatching false
        val field = at.javaClass.getDeclaredField("mInstrumentation")
        field.isAccessible = true
        field.get(at) is AegisInstrumentation
    }.getOrDefault(false)

    fun installIfNeeded(): Boolean =
        if (isAlreadyInstalled()) true else installInstrumentation(AegisInstrumentation())
}

/**
 * Exención de APIs ocultas sin dependencias externas (truco de meta-reflexión).
 * Android 9+ bloquea interfaces no-SDK; el motor necesita unas pocas
 * (ActivityThread.currentActivityThread, mInstrumentation, AssetManager.addAssetPath).
 * Nunca es fatal: cada hook degrada con gracia si la exención no está disponible.
 */
object HiddenApiExempt {
    private const val TAG = "AegisHiddenApi"

    @Volatile private var exempted = false
    @Volatile private var lastFailure: String? = null

    fun exemptAll(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        if (exempted) return true
        return try {
            val classClass = Class::class.java
            val meta = classClass.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass
            )
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = meta.invoke(
                vmRuntimeClass, *arrayOf<Any?>("getRuntime", arrayOf<Class<*>>())
            ) as java.lang.reflect.Method
            val setExemptions = meta.invoke(
                // BUG corregido: el parámetro 2 de Class.getDeclaredMethod es Class[]
                // (los TIPOS de cada parámetro). String[].class suelto era rechazado por
                // ART ("got java.lang.Class<java.lang.String[]>") y POR ESO las exemptions
                // nunca se instalaban: todas las apps con APIs ocultas (juegos, SDKs)
                // degradaban a pantalla en blanco. String[].class va ENVUELTO en Class[].
                vmRuntimeClass, *arrayOf<Any?>("setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java))
            ) as java.lang.reflect.Method
            val vmRuntime = getRuntime.invoke(null)
            setExemptions.invoke(vmRuntime, *arrayOf<Any?>(arrayOf("L")))
            exempted = true
            Log.i(TAG, "exemptions OK — Hidden API exemptions instaladas (prefix L)")
            true
        } catch (failure: Throwable) {
            lastFailure = "${failure.javaClass.simpleName}: ${failure.message}"
            Log.w(TAG, "Hidden API exemptions no disponibles ($lastFailure); los hooks degradarán")
            false
        }
    }
}

/** Reescribe intents con componentes del invitado para que el framework solo vea stubs de Aegis. */
object GuestIntentRouter {
    fun route(
        intent: Intent,
        guestPackage: String,
        stubClassName: String
    ): Intent {
        val component = intent.component ?: return intent
        if (component.packageName != guestPackage) return intent
        val routed = Intent(intent)
        routed.component = ComponentName(GuestSession.hostPackage.ifBlank { component.packageName }, stubClassName)
        routed.putExtra(GuestRuntimeEngine.EXTRA_GUEST_CLASS, component.className)
        routed.putExtra(GuestRuntimeEngine.EXTRA_PACKAGE_NAME, guestPackage)
        return routed
    }
}
