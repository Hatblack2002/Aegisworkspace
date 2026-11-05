package com.aistudio.aegis.virtualcore

/**
 * Tabla de causas conocidas (regla del proyecto: nada específico por paquete).
 *
 * Traduce una excepción REAL (su texto, no suposiciones) a:
 *   - qué esperaba el motor
 *   - qué encontró en su lugar
 *   - qué falta / qué hay que arreglar en el motor
 *
 * Si ninguna regla casa, devuelve null y el informe muestra la excepción cruda
 * sin inventar ninguna causa.
 */
object KnownCauses {

    data class Cause(
        val esperaba: String,
        val encontro: String,
        val falta: String
    )

    private val rules: List<Pair<Regex, Cause>> = listOf(
        // El guest abrió OTRA pantalla suya y el framework la rechazó porque el
        // componente no está exportado para nuestro uid (elAMS solo ve stubs).
        Regex("not exported from uid|Permission Denial.*not exported", RegexOption.IGNORE_CASE) to Cause(
            esperaba = "lanzar una pantalla interna del guest (su propia actividad/servicio) sin que el AMS la rechace",
            encontro = "SecurityException: el componente del guest no está exportado para este uid",
            falta = "el guest llamó a startActivity hacia otro componente suyo y esa llamada no pasó por el router de intents de Aegis (VirtualGuestContext) — toda salida del guest debe reescribirse a un stub del host llevando el componente real"
        ),
        // Contexto de Application equivocado (injection de Hilt/Dagger lo detecta).
        Regex("GeneratedComponentManager|Hilt_", RegexOption.IGNORE_CASE) to Cause(
            esperaba = "que getApplicationContext() de las actividades del guest devolviera la Application del guest",
            encontro = "una Activity cuyo application context es el del host (no implementa GeneratedComponentManager / no es la Application esperada)",
            falta = "el swap de mBase/ContextImpl en prepareGuestActivity debe garantizar que mApplication y getApplicationContext() apunten a la Application del guest antes de Activity.attach"
        ),
        // Clase no presente en el classloader del guest.
        Regex("ClassNotFoundException|NoClassDefFoundError") to Cause(
            esperaba = "cargar la clase indicada desde el DexClassLoader del guest (base.apk + todos los splits)",
            encontro = "ClassNotFoundException/NoClassDefFoundError: la clase no está en ningún dex del sandbox",
            falta = "verificar que el APK copiado es completo (inventario SHA-256 + nº de classes*.dex) y que newActivity usa el MISMO ClassLoader con el que se cargó la Application (identityHashCode en el informe)"
        ),
        // Recursos del host usados para IDs del guest.
        Regex("Resources\\\$NotFoundException|resource ID.*0x7f") to Cause(
            esperaba = "resolver IDs 0x7f del guest contra SU AssetManager (base + splits)",
            encontro = "Resources\$NotFoundException: el layout/tema se infló con Resources del host",
            falta = "el contexto de la actividad debe usar VirtualGuestContext con los Resources del guest; el LayoutInflater de PhoneWindow debe estar re-clonado (patchWindowInflater)"
        ),
        // Nativas.
        Regex("UnsatisfiedLinkError|dlopen failed|couldn't find.*\\.so", RegexOption.IGNORE_CASE) to Cause(
            esperaba = "cargar las .so del guest por orden de dependencias (DT_NEEDED) desde nativeLibraryDir del sandbox",
            encontro = "UnsatisfiedLinkError/dlopen failed: falta una librería o su ABI no coincide con la del dispositivo",
            falta = "el sandbox debe contener las .so de TODAS las ABI necesarias (o las de la ABI del dispositivo), y la carga debe ser iterativa respetando dependencias — los fallos de dlopen son AVISO, no error"
        ),
        // Permisos del guest que el host no redeclara/concede.
        Regex("SecurityException") to Cause(
            esperaba = "ejecutar la operación con los permisos que el guest declara en su manifest",
            encontro = "SecurityException del sistema",
            falta = "los permisos peligrosos del guest deben declararse también en el manifest del host y concederse en tiempo de ejecución antes de lanzar el clon"
        ),
        // APK corrupto o dex ausente.
        Regex("DexFile|Classes.dex.*not found|dexopt|OPTIMIZ|zip.*corrupt|invalid.*zip", RegexOption.IGNORE_CASE) to Cause(
            esperaba = "un APK del sandbox íntegro y con sus classes*.dex dentro",
            encontro = "un dex/zip inválido o incompleto",
            falta = "la copia al sandbox no terminó o se truncó: revisar el inventario de APKs (tamaño/SHA-256 vs original) y repetir la copia con setReadOnly() al finalizar"
        ),
        // Intent implícito sin resolución contra el manifest del guest.
        Regex("ActivityNotFound|Unable to find explicit activity") to Cause(
            esperaba = "resolver el intent contra el manifest del guest",
            encontro = "ActivityNotFoundException: el AMS resolvió contra el sistema (que no conoce el guest)",
            falta = "los intents implícitos del guest deben resolverse con PackageManager del guest (queryIntentActivities del manifest del guest) y redirigirse a un stub del host"
        ),
    )

    /** Traduce la CADENA de la excepción (con todos sus Caused by) a causa conocida. */
    fun translate(exceptionChain: String): Cause? {
        if (exceptionChain.isBlank()) return null
        for ((regex, cause) in rules) {
            if (regex.containsMatchIn(exceptionChain)) return cause
        }
        return null
    }
}
