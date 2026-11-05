package com.aistudio.aegis.virtualcore

/**
 * Clasificador global de fallos POR CLASE (regla del proyecto: prohibido cualquier
 * if/when por paquete o nombre de clase de una app). Todo se decide por el TIPO de
 * excepción, su mensaje y el estado del proceso.
 *
 * La clase se determina por la CAUSA RAÍZ (la última "Caused by" de la cadena),
 * no por la primera excepción.
 */
object FailureClassifier {

    enum class Clase {
        AISLAMIENTO_CLASES,
        IDENTIDAD_PERMISOS,
        RECURSOS_TEMA,
        CONTEXTO_APP,
        NATIVO_ABI,
        SILENCIOSO,
        DESCONOCIDO
    }

    data class Diagnostico(
        val clase: Clase,
        val mecanismo: String,
        val causaRaiz: String,
        val responsable: String
    )

    /** La última línea "Caused by:" de la cadena; si no hay, la primera línea. */
    fun causaRaiz(chain: String): String {
        val lines = chain.lines().filter { it.isNotBlank() }
        val lastCausedBy = lines.lastOrNull { it.trimStart().startsWith("Caused by:") }
            ?: lines.lastOrNull { it.contains("Caused by:") }
        return (lastCausedBy ?: lines.firstOrNull() ?: "(sin excepción)").trim()
    }

    private fun firstLine(chain: String): String =
        chain.lines().firstOrNull()?.trim().orEmpty()

    /**
     * Clasifica una cadena de excepción (con todos sus Caused by). [verdict] aporta
     * el estado del proceso para detectar SILENCIOSO (sin ventana / muerte del
     * proceso sin excepción).
     */
    fun clasificar(chain: String, verdict: String): Diagnostico {
        val root = causaRaiz(chain)
        val haystack = "$chain $verdict"

        fun d(clase: Clase, mecanismo: String, responsable: String) =
            Diagnostico(clase, mecanismo, root, responsable)

        // SILENCIOSO primero: proceso muerto sin excepción o sin ventana visible.
        // (La señal nativa SIN ninguna excepción Java es NATIVO_ABI — cae más abajo.)
        if (verdict.contains("PANTALLA EN BLANCO", ignoreCase = true) ||
            verdict.contains("sin ventana", ignoreCase = true) ||
            (chain.isBlank() && (verdict.contains("ARRANQUE FALLIDO", ignoreCase = true) ||
                verdict.contains("SIN INFORME", ignoreCase = true)))
        ) {
            return d(
                Clase.SILENCIOSO,
                "el proceso (o su UI) murió sin lanzar una excepción Java visible: crash nativo, kill del sistema o UI que nunca dibujó",
                "el inspector no debe tocar librerías nativas del guest (dlopen elimina el proceso antes de informar); el crash sandbox nativo y el watchdog de ventana deben cubrir la muerte y persistir el informe"
            )
        }

        return when {
            // VerifyError, NoSuchMethodError, NoSuchFieldError, IllegalAccessError,
            // IncompatibleClassChangeError, ArrayStoreException, ClassCastException
            Regex("VerifyError|NoSuchMethodError|NoSuchFieldError|IllegalAccessError|IncompatibleClassChangeError|ArrayStoreException|ClassCastException")
                .containsMatchIn(root) -> d(
                Clase.AISLAMIENTO_CLASES,
                "el guest resolvió una clase de su propio classpath con la versión del HOST (padre del loader equivocado) y las firmas no coinciden",
                "ClassLoader: el DexClassLoader del guest debe tener padre BootClassLoader, nunca el classLoader de la app host"
            )
            Regex("SecurityException|not exported from uid|does not own package|Permission Denial", RegexOption.IGNORE_CASE)
                .containsMatchIn(root) -> d(
                Clase.IDENTIDAD_PERMISOS,
                "el guest llamó al sistema con la identidad del paquete guest (uid del host no lo posee) o abrió un componente suyo que el AMS no conoce",
                "router de intents/proveedores con identidad del host: toda salida del guest (startActivity/startService/bind/broadcast/API con packageName) debe reescribirse al host con el componente real en extras"
            )
            Regex("Resources\\\$NotFoundException|Theme\\.AppCompat|InflateException")
                .containsMatchIn(root) -> d(
                Clase.RECURSOS_TEMA,
                "IDs 0x7f o estilos del guest se resolvieron contra los Resources/Theme del host en vez de los del guest",
                "Resources/Context/tema del guest: AssetManager con base+splits, ContextThemeWrapper sin caches, theme del manifest del guest aplicado ANTES del onCreate y con el bypass de hidden APIs activo"
            )
            Regex("GeneratedComponentManager|NoSuchElementException.*SharedPreferences|RuntimeException.*null.*Context|NullPointerException.*(Context|Application|SharedPreferences)")
                .containsMatchIn(root) -> d(
                Clase.CONTEXTO_APP,
                "la Activity/SDK del guest recibió un application context que no es la Application del guest",
                "LoadedApk.mApplication del proceso debe publicarse con la Application del guest antes del primer Activity.attach, además del swap de mBase"
            )
            Regex("UnsatisfiedLinkError|dlopen failed|32-bit instead of 64-bit|señal|SIGABRT|SIGSEGV|tombstone", RegexOption.IGNORE_CASE)
                .containsMatchIn(root) -> d(
                Clase.NATIVO_ABI,
                "una librería nativa del guest falta, es de otra ABI del proceso, o el motor cargó algo que la mata (dlopen en el inspector)",
                "solo deben cargarse .so de la ABI del proceso, por carga NATURAL del guest (System.loadLibrary), nunca dlopen deliberado del inspector"
            )
            else -> d(
                Clase.DESCONOCIDO,
                "ninguna regla casó con la causa raíz",
                "clase nueva candidata: revisar la traza completa de este informe y proponer una clase si se repite en más apps"
            )
        }
    }
}
