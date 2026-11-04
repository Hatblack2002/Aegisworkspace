package com.aistudio.aegis.virtualcore

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Canal principal Guest -> Host (Sección 6.4):
 * `ContentProvider` alojado en el proceso principal de Aegis.
 * Cuando el proceso clonado (`:p0` o `:p1`) arranca y `libaegis_guest.so` lee `/proc/self/maps`,
 * envía el reporte JSON a través de `ContentResolver.call()` a este provider.
 */
class AegisReportProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Log.i(TAG, "AegisReportProvider inicializado en proceso host")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_SUBMIT_REPORT && extras != null) {
            val reportJson = extras.getString(KEY_REPORT_JSON)
            val processName = extras.getString(KEY_PROCESS_NAME, ":p0")
            if (!reportJson.isNullOrBlank()) {
                val report = GuestMapsReport.fromContractOrFullJson(reportJson, processName)
                Log.i(
                    TAG,
                    "Reporte recibido desde proceso guest $processName para ${report.packageName}: " +
                        "ok=${report.ok}, pid=${report.pid}, libs=${report.libs.size}"
                )
                onReportReceived(context, report)
                return Bundle().apply { putBoolean("ack", true) }
            }
        }
        return super.call(method, arg, extras)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "Aegis"
        const val METHOD_SUBMIT_REPORT = "submit_guest_maps_report"
        const val KEY_REPORT_JSON = "report_json"
        const val KEY_PROCESS_NAME = "process_name"

        private val _reportsByPackage = MutableStateFlow<Map<String, GuestMapsReport>>(emptyMap())
        val reportsByPackage: StateFlow<Map<String, GuestMapsReport>> = _reportsByPackage.asStateFlow()

        private val _latestEvent = MutableStateFlow<GuestMapsReport?>(null)
        val latestEvent: StateFlow<GuestMapsReport?> = _latestEvent.asStateFlow()

        fun authority(context: Context): String = "${context.packageName}.aegis.report.provider"

        fun uri(context: Context): Uri = Uri.parse("content://${authority(context)}")

        internal fun onReportReceived(context: Context?, report: GuestMapsReport) {
            val updated = _reportsByPackage.value.toMutableMap()
            updated[report.packageName] = report
            _reportsByPackage.value = updated
            _latestEvent.value = report

            // Persistir también en el almacén del motor virtual del host
            if (context != null) {
                VirtualEngineHost.getInstance(context).saveReportFromGuest(report)
            }
        }

        /**
         * Invocado desde el proceso clonado (:p0 / :p1) para enviar el resultado al host.
         */
        fun sendReportToHost(context: Context, report: GuestMapsReport): Boolean {
            return try {
                val extras = Bundle().apply {
                    putString(KEY_REPORT_JSON, report.toFullStorageJson().toString())
                    putString(KEY_PROCESS_NAME, report.processName)
                }
                val res = context.contentResolver.call(
                    uri(context),
                    METHOD_SUBMIT_REPORT,
                    report.packageName,
                    extras
                )
                res?.getBoolean("ack", false) == true
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando reporte por ContentProvider desde ${report.processName}: ${e.message}", e)
                false
            }
        }
    }
}
