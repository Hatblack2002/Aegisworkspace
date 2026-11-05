package com.aistudio.aegis.virtualcore

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Log del motor con búfer circular en memoria. Todo lo que el motor escribe aquí
 * queda disponible para el informe de fallos sin necesitar READ_LOGS (que las
 * apps normales no tienen): el filtro "Aegis" de logcat y este búfer son el mismo flujo.
 */
object AegisLog {
    private const val CAP = 300
    private val ring = ArrayDeque<String>()

    private fun append(line: String) {
        synchronized(ring) {
            if (ring.size >= CAP) ring.removeFirst()
            ring.addLast(line)
        }
    }

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    fun i(msg: String) {
        Log.i("Aegis", msg)
        append("${ts()} I/Aegis: $msg")
    }

    fun w(msg: String) {
        Log.w("Aegis", msg)
        append("${ts()} W/Aegis: $msg")
    }

    fun e(msg: String, tr: Throwable? = null) {
        Log.e("Aegis", msg, tr)
        append("${ts()} E/Aegis: $msg")
        if (tr != null) append(Log.getStackTraceString(tr).trimEnd())
    }

    fun dump(): String = synchronized(ring) { ring.joinToString("\n") }
}

/**
 * Rastreo HONESTO de un intento de lanzamiento de un clon (engine v2.3).
 *
 * Regla del proyecto: un paso que no se pudo comprobar se marca "no verificado",
 * NUNCA como OK. Cada intento genera un informe en texto (persistido en el sandbox
 * del host, compartido con el proceso principal) con:
 *  1 cabecera del dispositivo y de la app clonada · 2 línea de tiempo ✔/✘/◦ ·
 *  3 qué buscaba y qué no encontró (siempre desde la excepción real) ·
 *  4 excepciones completas con todos los "Caused by" · 5 inventario de APKs
 *  (tamaño, SHA-256 vs original, entradas classes*.dex, permisos) ·
 *  6 ClassLoader (dexPath, nativeLibraryDir, identityHashCode por punto de uso) ·
 *  7 librerías nativas (ABI del dispositivo, ABIs del APK, mapeadas) ·
 *  8 logcat del sistema (best-effort; "no verificado" si el ROM lo prohíbe).
 * Sin contenido personal: solo rutas de motor, hashes y excepciones.
 */
object GuestLaunchTracker {

    enum class Status { OK, FAIL, UNVERIFIED }

    data class Step(val label: String, val status: Status, val detail: String, val atMs: Long)

    const val WATCHDOG_SECONDS = 10L

    @Volatile var guestPackage: String = ""
        private set
    @Volatile var guestPid: Int = 0
    @Volatile var dexPath: String = ""
    @Volatile var nativeLibDirPath: String = ""
    @Volatile var loaderHashForApplication: Long = 0L
    @Volatile var loaderHashForNewActivity: Long = 0L
    @Volatile var firstWindowSeen: Boolean = false
        private set
    @Volatile var watchdogFired: Boolean = false
        private set
    @Volatile var lastReportFile: String? = null
        private set
    @Volatile var apkInventory: String? = null
        private set
    @Volatile var crashChain: String? = null
        private set

    private val lock = Any()
    private val steps = mutableListOf<Step>()
    private val exceptions = mutableListOf<String>()
    private var watchdogArmed = false

    // ------------------------------------------------------------------ pasos

    fun reset(guestPackage: String) {
        synchronized(lock) {
            steps.clear()
            exceptions.clear()
            watchdogArmed = false
        }
        this.guestPackage = guestPackage
        guestPid = android.os.Process.myPid()
        dexPath = ""
        nativeLibDirPath = ""
        loaderHashForApplication = 0L
        loaderHashForNewActivity = 0L
        firstWindowSeen = false
        watchdogFired = false
        lastReportFile = null
        apkInventory = null
        crashChain = null
        AegisLog.i("nuevo intento de lanzamiento para $guestPackage (PID $guestPid)")
    }

    fun ok(label: String, detail: String = "") = record(label, Status.OK, detail)
    fun fail(label: String, detail: String = "") = record(label, Status.FAIL, detail)
    fun unverified(label: String, detail: String = "") = record(label, Status.UNVERIFIED, detail)

    private fun record(label: String, status: Status, detail: String) {
        synchronized(lock) { steps.add(Step(label, status, detail, System.currentTimeMillis())) }
        val mark = when (status) {
            Status.OK -> "OK"
            Status.FAIL -> "FALLO"
            Status.UNVERIFIED -> "no verificado"
        }
        AegisLog.i("paso [$label] = $mark ${if (detail.isBlank()) "" else "— $detail"}")
    }

    fun snapshotSteps(): List<Step> = synchronized(lock) { steps.toList() }

    fun firstFailedStep(): Step? = synchronized(lock) { steps.firstOrNull { it.status == Status.FAIL } }

    /**
     * Último paso INICIADO (regla A.2): si el crash ocurre fuera de los pasos
     * previstos, el handler global lo atribuye a este paso y lo marca ✘.
     */
    fun lastStartedStep(): Step? = synchronized(lock) { steps.lastOrNull() }

    /** Excepción REAL: cadena completa con todos los "Caused by". Nunca suposiciones. */
    fun exception(t: Throwable, contextWhatItWasLookingFor: String) {
        val chain = describeThrowable(t)
        synchronized(lock) { exceptions.add("[$contextWhatItWasLookingFor]\n$chain") }
        AegisLog.e("excepción ($contextWhatItWasLookingFor)", t)
    }

    fun recordCrash(t: Throwable) {
        crashChain = describeThrowable(t)
        // Regla A.2: el crash pertenece al último paso iniciado. Si aún no hay
        // ningún paso marcado ✘, ese paso es el primer fallo real del intento.
        val attributed = synchronized(lock) { steps.lastOrNull() }
        if (attributed != null && firstFailedStep() == null) {
            fail(
                attributed.label,
                "crash DURANTE este paso (atribuido por el handler global): " +
                    (t.message ?: t.javaClass.simpleName)
            )
        }
    }

    fun snapshotExceptions(): List<String> = synchronized(lock) { exceptions.toList() }

    /** Cadena completa de la excepción con todos los niveles de "Caused by". */
    fun describeThrowable(t: Throwable): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (depth > 0) append("\n  Caused by: ")
            append(cur.javaClass.name).append(": ").append(cur.message ?: "sin mensaje")
            cur = cur.cause
            depth++
            if (length > 1500) {
                append(" …")
                break
            }
        }
    }

    // ----------------------------------------------------------- classloader

    fun recordClassLoader(dexPath: String, nativeLibDir: String, loaderHash: Long) {
        this.dexPath = dexPath
        this.nativeLibDirPath = nativeLibDir
        AegisLog.i("ClassLoader creado: dexPath=$dexPath nativeLibraryDir=$nativeLibDir loader=0x${java.lang.Long.toHexString(loaderHash)}")
    }

    // --------------------------------------------------- primera ventana / watchdog

    /** Se llama en el PRIMER onDraw de la ventana de una actividad (invitada o stub). */
    fun markFirstWindow(activityClassSimpleName: String) {
        if (firstWindowSeen) return
        firstWindowSeen = true
        if (watchdogFired) {
            // Llegó tarde: honesto — la ventana apareció, pero después del watchdog.
            ok("Primera ventana visible", "TARDÍA ($activityClassSimpleName: apareció después del watchdog de ${WATCHDOG_SECONDS}s)")
        } else {
            ok("Primera ventana visible", activityClassSimpleName)
        }
    }

    /**
     * Watchdog de pantalla blanca: si en N segundos ninguna ventana llegó a dibujarse,
     * se registra el fallo REAL y se persiste el informe con el estado de cada paso.
     */
    fun scheduleWindowWatchdog(appContext: Context) {
        synchronized(lock) {
            if (watchdogArmed) return
            watchdogArmed = true
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!firstWindowSeen && !watchdogFired) {
                watchdogFired = true
                fail(
                    "Primera ventana visible",
                    "sin ventana en ${WATCHDOG_SECONDS}s — pantalla en blanco: el onCreate del invitado no llegó a dibujar (ver pasos y excepciones)"
                )
                // Regla A.5: consultar al AMS la razón de salida del proceso stub
                // (crashes nativos y muertes que ningún handler Java ve).
                queryProcessExitAsync(appContext, appContext.packageName, android.os.Process.myPid(), blocking = true)
                persistReport(appContext, guestPackage, "PANTALLA EN BLANCO (sin ventana visible en ${WATCHDOG_SECONDS}s)")
            }
        }, WATCHDOG_SECONDS * 1000L)
    }

    // -------------------------------------------------------- inventario APKs

    /**
     * Inventario completo de los APKs del invitado (sección 5 del informe).
     * Se calcula en un hilo aparte: el SHA-256 de un APK de 300 MB no puede ir en el
     * hilo principal. Mientras calcula, el informe dice "no verificado (en curso)".
     */
    fun computeApkInventoryAsync(appContext: Context, sandboxApks: List<File>, sourceApks: List<File>) {
        Thread {
            apkInventory = buildApkInventory(sandboxApks, sourceApks)
            AegisLog.i("inventario de APKs listo (${sandboxApks.size} archivos)")
        }.start()
    }

    private fun buildApkInventory(sandboxApks: List<File>, sourceApks: List<File>): String {
        if (sandboxApks.isEmpty()) return "(no verificado: sin APKs en el sandbox)"
        val sb = StringBuilder()
        val compareByIndex = sourceApks.size == sandboxApks.size
        if (!compareByIndex && sourceApks.isNotEmpty()) {
            sb.appendLine("! recuento distinto: ${sandboxApks.size} copias vs ${sourceApks.size} originales — hash no comparable por índice")
        }
        sandboxApks.forEachIndexed { index, apk ->
            val sizeMb = "%.1f MB".format(Locale.US, apk.length() / 1048576.0)
            val copyHash = runCatching { sha256(apk) }.getOrNull()
            val dexCount = runCatching {
                ZipFile(apk).use { zip -> zip.entries().asSequence().count { it.name.matches(Regex("classes[0-9]*\\.dex")) } }
            }.getOrDefault(-1)
            val wasWritable = apk.canWrite()
            if (wasWritable) apk.setReadOnly()   // regla: solo lectura antes de cargar
            val ro = if (!apk.canWrite()) "solo-lectura" else "ESCRIBIBLE"

            val hashLine = when {
                copyHash == null -> "SHA-256 no verificado (error leyendo la copia)"
                !compareByIndex -> "SHA-256 copia=${copyHash.take(16)}… (original no comparado)"
                else -> {
                    val src = sourceApks[index]
                    val srcHash = runCatching { sha256(src) }.getOrNull()
                    when {
                        srcHash == null -> "SHA-256 copia=${copyHash.take(16)}… · original no legible para comparar"
                        srcHash == copyHash -> "SHA-256 = original ✓ (${copyHash.take(16)}…)"
                        else -> "SHA-256 DISTINTO DEL ORIGINAL ✘ (copia=${copyHash.take(12)}… original=${srcHash.take(12)}…)"
                    }
                }
            }
            val dexLine = when {
                dexCount > 0 -> "$dexCount classes*.dex"
                dexCount == 0 && apk.name != "base.apk" -> "0 classes*.dex (split de recursos/ABI: correcto)"
                else -> "0 classes*.dex ¡ANÓMALO EN base.apk!"
            }
            sb.appendLine("· ${apk.absolutePath}")
            sb.appendLine("    $sizeMb · $dexLine · $ro · $hashLine")
        }
        return sb.toString().trimEnd()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ---------------------------------------------------------------- informe

    // ------------------------------------------------ muerte del proceso (A.5)

    /** Razón de salida del proceso stub, consultada por el host vía AMS. */
    @Volatile var processExitReason: String = ""

    /**
     * El host consulta getHistoricalProcessExitReasons() del proceso stub:
     * cubre crashes NATIVOS y muertes del proceso que ningún handler Java ve.
     * API 30+ (Android 11); en versiones anteriores queda "no verificado" (nunca se inventa).
     */
    fun queryProcessExitAsync(appContext: Context, processPackage: String, pid: Int, blocking: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            processExitReason = "(no verificado: getHistoricalProcessExitReasons requiere Android 11+)"
            return
        }
        val worker = Thread {
            val reason = runCatching {
                val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val exits = am.getHistoricalProcessExitReasons(processPackage, pid, 1)
                val e = exits.firstOrNull()
                if (e == null) "(sin registro de salida para PID $pid)"
                else "reason=${e.reason} descripción=\"${e.description ?: ""}\""
            }.getOrElse { "(no verificado: ${it.javaClass.simpleName}: ${it.message})" }
            processExitReason = reason
            AegisLog.i("salida del proceso stub: $reason")
        }
        worker.start()
        if (blocking) runCatching { worker.join(4000) }
    }

    /**
     * RESUMEN GLOBAL (regla 3): tabla "clase de fallo x apps afectadas" con TODOS
     * los clones, priorizada por nº de apps — se arregla primero la clase con más
     * apps. Se persiste con fecha para comparar antes/después de cada cambio del
     * motor (criterio de éxito: menos apps en la clase tras cada cambio).
     */
    fun buildGlobalSummary(appContext: Context): String {
        val root = File(appContext.filesDir, "virtual_env")
        data class Row(val pkg: String, val clase: String, val verdict: String, val fecha: String)
        val rows = mutableListOf<Row>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { pkgDir ->
            val latest = File(pkgDir, "reports/latest.txt")
            if (!latest.exists()) return@forEach
            val text = runCatching { latest.readText() }.getOrDefault("")
            if (text.isBlank()) return@forEach
            fun line(prefix: String): String =
                text.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
            val pkg = line("app clonada:").substringBefore(" (").ifBlank { pkgDir.name }
            val verdict = line("resultado:").ifBlank { "(sin resultado)" }
            val declared = line("clase de fallo:")
            val clase = declared.ifBlank {
                // Informes antiguos sin clasificador: clasificar ahora con su texto.
                FailureClassifier.clasificar(text, verdict).clase.name
            }
            val fecha = line("fecha:").ifBlank { "?" }
            rows.add(Row(pkg, clase, verdict, fecha))
        }
        return buildString {
            appendLine("=== AEGIS · RESUMEN GLOBAL (tabla clase de fallo x apps) ===")
            appendLine("fecha: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("(prioridad: primero la clase con más apps afectadas)")
            appendLine()
            val grouped = rows.groupBy { it.clase }.entries.sortedByDescending { it.value.size }
            if (grouped.isEmpty()) {
                appendLine("(sin informes todavía: lanzar clones y volver)")
            }
            for ((clase, apps) in grouped) {
                appendLine("$clase (${apps.size} app${if (apps.size == 1) "" else "s"}):")
                for (app in apps.sortedBy { it.pkg }) {
                    appendLine("  · ${app.pkg} — ${app.verdict.take(90)} (${app.fecha})")
                }
                appendLine()
            }
            appendLine("== HISTORIAL ==")
            appendLine("Guardar este archivo tras cada cambio del motor; el éxito es ver bajar la fila con más apps.")
        }
    }

    /** Persiste el resumen global (latest + historial con fecha). Devuelve la ruta latest. */
    fun persistGlobalSummary(appContext: Context): String? = runCatching {
        val text = buildGlobalSummary(appContext)
        val dir = File(appContext.filesDir, "virtual_env/summaries").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(dir, "sumario_$stamp.txt").writeText(text)
        val latest = File(dir, "resumen_global.txt")
        latest.writeText(text)
        latest.absolutePath
    }.getOrNull()

    /**
     * Genera el informe completo y lo persiste en el sandbox del host:
     *   files/virtual_env/<pkg>/reports/intento_<fecha>_p<pid>.txt  (+ latest.txt)
     * Devuelve la ruta del archivo generado. Cada intento queda en el historial.
     */
    @Synchronized
    fun persistReport(appContext: Context, guestPackage: String, verdict: String): String? {
        return runCatching {
            val text = buildReport(appContext, guestPackage, verdict)
            val dir = File(appContext.filesDir, "virtual_env/$guestPackage/reports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "intento_${stamp}_p${guestPid}.txt")
            file.writeText(text)
            File(dir, "latest.txt").writeText(text)
            lastReportFile = file.absolutePath
            pruneHistory(dir)
            AegisLog.i("informe persistido: ${file.absolutePath}")
            file.absolutePath
        }.getOrElse {
            AegisLog.w("no se pudo persistir el informe: ${it.message}")
            null
        }
    }

    private fun pruneHistory(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("intento_") }?.sortedBy { it.name } ?: return
        if (files.size > 10) {
            files.take(files.size - 10).forEach { runCatching { it.delete() } }
        }
    }

    fun buildReport(appContext: Context, guestPackage: String, verdict: String): String {
        val sb = StringBuilder()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // 1. Cabecera
        val hostVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "desconocida"
        val guestVersion = runCatching {
            appContext.packageManager.getPackageInfo(guestPackage, 0).versionName
        }.getOrNull() ?: "desconocida"

        sb.appendLine("=== AEGIS · INFORME DE INTENTO (v1) ===")
        sb.appendLine("fecha: ${dateFmt.format(Date())}")
        sb.appendLine("resultado: $verdict")
        sb.appendLine("PID del invitado: $guestPid")
        sb.appendLine("app clonada: $guestPackage (versión $guestVersion)")
        sb.appendLine("host Aegis: $hostVersion · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        sb.appendLine("(informe técnico del motor: sin cuentas ni contenido de la app)")
        sb.appendLine()

        // 1.b Clase de fallo — clasificador POR CAUSA RAÍZ (última Caused by),
        // prohibido decidir por paquete. La línea "clase de fallo:" es parseable
        // por el resumen global.
        val diag = FailureClassifier.clasificar(
            crashChain ?: snapshotExceptions().lastOrNull().orEmpty(),
            verdict
        )
        sb.appendLine("clase de fallo: ${diag.clase}")
        sb.appendLine("== CLASE DE FALLO (${diag.clase}) ==")
        sb.appendLine("mecanismo: ${diag.mecanismo}")
        sb.appendLine("causa raíz (literal, última Caused by): ${diag.causaRaiz}")
        sb.appendLine("responsable del motor: ${diag.responsable}")
        sb.appendLine()

        // 2. Línea de tiempo ✔ / ✘ / no verificado, con el PRIMER FALLO marcado
        sb.appendLine("== TIMELINE ==")
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val firstFail = firstFailedStep()
        val allSteps = snapshotSteps()
        if (allSteps.isEmpty()) {
            sb.appendLine("(no verificado: no se registró ningún paso)")
        }
        for (step in allSteps) {
            val mark = when (step.status) {
                Status.OK -> "✔"
                Status.FAIL -> "✘"
                Status.UNVERIFIED -> "◦"
            }
            sb.appendLine("[${timeFmt.format(Date(step.atMs))}] $mark ${step.label}${if (step.detail.isBlank()) "" else " — ${step.detail}"}")
            if (step.status == Status.FAIL && step === firstFail) {
                sb.appendLine("   ↑ PRIMER FALLO DEL INTENTO")
            }
        }
        sb.appendLine()

        // 4 (y 3). Excepciones completas: qué buscaba y qué no encontró, desde la excepción real
        sb.appendLine("== EXCEPCIONES (qué buscaba y qué no encontró) ==")
        val exs = snapshotExceptions()
        if (crashChain != null) sb.appendLine("[crash no capturado del proceso]\n$crashChain\n")
        if (exs.isEmpty() && crashChain == null) sb.appendLine("(ninguna excepción registrada)")
        exs.forEachIndexed { i, chain ->
            sb.appendLine("${i + 1}. $chain")
            KnownCauses.translate(chain)?.let { c ->
                sb.appendLine("   → CAUSA CONOCIDA:")
                sb.appendLine("     esperaba: ${c.esperaba}")
                sb.appendLine("     encontró: ${c.encontro}")
                sb.appendLine("     falta en el motor: ${c.falta}")
            }
            sb.appendLine()
        }
        if (crashChain != null) {
            KnownCauses.translate(crashChain ?: "")?.let { c ->
                sb.appendLine("   → CAUSA CONOCIDA (crash):")
                sb.appendLine("     esperaba: ${c.esperaba}")
                sb.appendLine("     encontró: ${c.encontro}")
                sb.appendLine("     falta en el motor: ${c.falta}")
                sb.appendLine()
            }
        }
        sb.appendLine()

        // 4.b Muerte del proceso (crashes NATIVOS y muertes que ningún handler Java ve)
        sb.appendLine("== SALIDA DEL PROCESO STUB (AMS) ==")
        sb.appendLine(processExitReason.ifBlank { "(no verificado: el host no consultó getHistoricalProcessExitReasons)" })
        sb.appendLine()

        // 5. APKs del invitado
        sb.appendLine("== APKs DEL INVITADO (sandbox) ==")
        sb.appendLine(apkInventory ?: "(no verificado: inventario en cálculo o no iniciado)")
        sb.appendLine()

        // 6. ClassLoader
        sb.appendLine("== CLASSLOADER ==")
        sb.appendLine("dexPath: ${dexPath.ifBlank { "(no verificado: no se creó ningún classloader)" }}")
        sb.appendLine("nativeLibraryDir: ${nativeLibDirPath.ifBlank { "(no verificado)" }}")
        sb.appendLine("loader usado para Application: ${if (loaderHashForApplication != 0L) "0x" + java.lang.Long.toHexString(loaderHashForApplication) else "(no verificado: no se cargó Application)"}")
        sb.appendLine("loader usado en newActivity: ${if (loaderHashForNewActivity != 0L) "0x" + java.lang.Long.toHexString(loaderHashForNewActivity) else "(no verificado: newActivity no llegó a usar el loader)"}")
        sb.appendLine()

        // 7. Librerías nativas
        sb.appendLine("== LIBRERÍAS NATIVAS ==")
        sb.appendLine("ABI del dispositivo: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        val nativeLibDir = nativeLibDirPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val extracted = nativeLibDir?.listFiles { f -> f.name.endsWith(".so") }?.size
        sb.appendLine(".so extraídas al sandbox: ${extracted ?: "(no verificado)"}")
        val lastReport = runCatching { AegisReportProvider.reportsByPackage.value[guestPackage] }.getOrNull()
        if (lastReport != null) {
            sb.appendLine(".so mapeadas en el proceso: ${lastReport.libs.size} (lectura ${if (lastReport.ok) "OK" else "FALLÓ: ${lastReport.error ?: "sin motivo"}"})")
        } else {
            sb.appendLine(".so mapeadas en el proceso: (no verificado: sin lectura de /proc/self/maps todavía)")
        }
        sb.appendLine()

        // 8. Logs
        sb.appendLine("== LOG DEL MOTOR (búfer en memoria, últimas 300) ==")
        sb.appendLine(AegisLog.dump().ifBlank { "(sin líneas del motor)" })
        sb.appendLine()
        sb.appendLine("== LOGCAT DEL SISTEMA (best-effort) ==")
        sb.appendLine(systemLogcatSnapshot())
        return sb.toString()
    }

    /**
     * Logcat del sistema filtrado (Aegis*, AndroidRuntime, ClassLoader, DEBUG).
     * Las apps normales NO pueden leer el log desde Android 4.1 (READ_LOGS es de
     * sistema): si el ROM lo bloquea, se declara "no verificado" — nunca se inventa.
     */
    private fun systemLogcatSnapshot(): String = runCatching {
        val proc = Runtime.getRuntime().exec(
            arrayOf(
                "logcat", "-d", "-t", "200", "-v", "time",
                "Aegis:V", "AegisGuest:V", "AegisTransmuter:V", "AegisInstrumentation:V",
                "AegisStubActivity:V", "AegisBubble:V", "AegisService:V", "AegisHiddenApi:V",
                "AndroidRuntime:E", "ClassLoader:V", "DEBUG:I", "*:S"
            )
        )
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        if (out.isBlank()) "(no verificado: logcat no devolvió líneas — READ_LOGS no disponible para apps normales; usa 'adb logcat -s Aegis AndroidRuntime ClassLoader DEBUG')"
        else out.trimEnd()
    }.getOrElse { "(no verificado: logcat falló — ${it.javaClass.simpleName}: ${it.message})" }
}

/** Detecta el primer dibujo de una ventana (usado para "primera ventana visible"). */
object FirstDrawWatcher {
    /**
     * Añade un listener de un solo disparo al ViewTreeObserver del decorView.
     * El marcado se hace vía post() (no se pueden modificar listeners durante onDraw).
     */
    fun watch(activity: android.app.Activity, onFirstDraw: () -> Unit) {
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val fired = AtomicBoolean(false)
        runCatching {
            decor.viewTreeObserver.addOnDrawListener {
                if (fired.compareAndSet(false, true)) {
                    decor.post { runCatching { onFirstDraw() } }
                }
            }
        }
    }
}
