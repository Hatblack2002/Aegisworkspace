package com.aistudio.aegis.virtualcore

import org.json.JSONArray
import org.json.JSONObject

/**
 * Representa el contrato de datos JSON exacto enviado desde el proceso clonado (:p0 / :p1)
 * hacia el host Aegis tras ejecutar libaegis_guest.so.
 *
 * Contrato JSON (Sección 6.4):
 * {
 *   "ok": true,
 *   "pid": 12345,
 *   "packageName": "com.dev.migame",
 *   "libs": ["/data/app/.../libgame.so", "/system/lib64/libc.so"],
 *   "error": null
 * }
 */
data class GuestMapsReport(
    val ok: Boolean,
    val pid: Int,
    val packageName: String,
    val libs: List<String>,
    val error: String?,
    val processName: String = ":p0",
    val timestampMs: Long = System.currentTimeMillis(),
    val logs: List<String> = emptyList()
) {
    /**
     * Genera exactamente el objeto JSON especificado en la Sección 6.4 para el botón "Copiar JSON".
     */
    fun toStrictContractJson(): String {
        val root = JSONObject()
        root.put("ok", ok)
        root.put("pid", pid)
        root.put("packageName", packageName)
        val libsArray = JSONArray()
        libs.forEach { libsArray.put(it) }
        root.put("libs", libsArray)
        if (error == null) {
            root.put("error", JSONObject.NULL)
        } else {
            root.put("error", error)
        }
        return root.toString(2)
    }

    /**
     * Serializa el reporte completo incluyendo metadatos de UI (proceso, timestamp y logs)
     * para persistencia local y transferencia por ContentProvider.
     */
    fun toFullStorageJson(): JSONObject {
        val obj = JSONObject()
        obj.put("ok", ok)
        obj.put("pid", pid)
        obj.put("packageName", packageName)
        val libsArray = JSONArray()
        libs.forEach { libsArray.put(it) }
        obj.put("libs", libsArray)
        if (error == null) {
            obj.put("error", JSONObject.NULL)
        } else {
            obj.put("error", error)
        }
        obj.put("processName", processName)
        obj.put("timestampMs", timestampMs)
        val logsArray = JSONArray()
        logs.forEach { logsArray.put(it) }
        obj.put("logs", logsArray)
        return obj
    }

    companion object {
        fun fromContractOrFullJson(rawJson: String, fallbackProcessName: String = ":p0"): GuestMapsReport {
            return try {
                val obj = JSONObject(rawJson)
                val ok = obj.optBoolean("ok", false)
                val pid = obj.optInt("pid", -1)
                val pkg = obj.optString("packageName", "unknown")
                val libsArr = obj.optJSONArray("libs")
                val libs = mutableListOf<String>()
                if (libsArr != null) {
                    for (i in 0 until libsArr.length()) {
                        val item = libsArr.optString(i)
                        if (item.isNotBlank()) libs.add(item)
                    }
                }
                val errorVal = if (obj.isNull("error")) null else obj.optString("error").takeIf { it.isNotBlank() }
                val procName = obj.optString("processName", fallbackProcessName).ifBlank { fallbackProcessName }
                val timestamp = obj.optLong("timestampMs", System.currentTimeMillis())
                val logsArr = obj.optJSONArray("logs")
                val logs = mutableListOf<String>()
                if (logsArr != null) {
                    for (i in 0 until logsArr.length()) {
                        val line = logsArr.optString(i)
                        if (line.isNotBlank()) logs.add(line)
                    }
                }
                GuestMapsReport(
                    ok = ok && libs.isNotEmpty(),
                    pid = pid,
                    packageName = pkg,
                    libs = libs,
                    error = if (!ok && errorVal == null) "Lectura de /proc/self/maps fallida" else errorVal,
                    processName = procName,
                    timestampMs = timestamp,
                    logs = logs
                )
            } catch (e: Exception) {
                GuestMapsReport(
                    ok = false,
                    pid = -1,
                    packageName = "unknown",
                    libs = emptyList(),
                    error = "JSON_PARSE_ERROR: ${e.message}",
                    processName = fallbackProcessName
                )
            }
        }
    }
}

/**
 * Representa una aplicación instalada en el contenedor virtual de Aegis Engine.
 *
 * Campos v2 (engine redesign):
 *  - sandboxApkPaths: COPIAS de base.apk + splits dentro del sandbox (el guest corre
 *    desde su propia copia, inmune a rutas /data/app que cambian o se restringen).
 *  - launchProbeOk / launchProbeMessage: veredicto HONESTO de la sonda dex ejecutada
 *    en el momento del clonado (¿ART puede cargar realmente el código de esta app?).
 */
data class ClonedAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val sourceApkPath: String,
    val virtualContainerDir: String,
    val virtualNativeLibDir: String,
    val primaryAbi: String,
    val assignedProcess: String,
    val clonedAtMs: Long,
    val isCompatibleAbi: Boolean = true,
    val incompatibilityReason: String? = null,
    val lastReport: GuestMapsReport? = null,
    val sandboxApkPaths: List<String> = emptyList(),
    val launchProbeOk: Boolean? = null,
    val launchProbeMessage: String? = null
)
