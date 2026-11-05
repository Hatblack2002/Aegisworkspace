package com.aistudio.aegis.virtualcore

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Burbuja flotante de diagnóstico (engine v2) dentro del proceso stub (:p0 / :p1).
 *
 * Arquitectura pedida por el usuario:
 *  - La app invitada corre PRIMERO; la lectura de librerías NUNCA bloquea el lanzamiento.
 *  - Algunos juegos/apps solo exponen sus librerías al ENTRAR en la app: la burbuja
 *    permite disparar "Intentar leer librerías" en cualquier momento, sin salir del juego.
 *  - La burbuja es un círculo arrastrable a cualquier posición (posición recordada entre
 *    actividades del mismo proceso); un toque la expande en un panel con acciones.
 *
 * Vive en la Window de la actividad stub (TYPE_APPLICATION): no requiere permiso
 * SYSTEM_ALERT_WINDOW y flota sobre la UI del invitado, que pertenece a esa misma ventana.
 */
object GuestFloatingBubble {

    private const val TAG = "AegisBubble"
    private const val BUBBLE_SIZE_DP = 44
    private const val PANEL_WIDTH_DP = 240
    private const val DRAG_SLOP_DP = 8

    /** Posición recordada entre actividades del mismo proceso (stub). */
    @Volatile private var lastX: Int = Int.MIN_VALUE
    @Volatile private var lastY: Int = Int.MIN_VALUE
    @Volatile private var expanded: Boolean = false

    private val attachedRoots = WeakHashMap<Activity, FrameLayout>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Instala la burbuja en la ventana de la actividad (idempotente por actividad). */
    fun attach(activity: Activity, packageName: String) {
        if (attachedRoots.containsKey(activity)) return
        try {
            val density = activity.resources.displayMetrics.density
            val sizePx = (BUBBLE_SIZE_DP * density).toInt()
            val panelWidthPx = (PANEL_WIDTH_DP * density).toInt()

            val root = FrameLayout(activity).apply { clipChildren = false }
            val bubble = makeBubbleView(activity, sizePx)
            root.addView(bubble)

            val panel = makePanelView(activity, panelWidthPx, packageName)
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            root.addView(panel)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val dm = activity.resources.displayMetrics
                x = if (lastX != Int.MIN_VALUE) lastX else (dm.widthPixels - sizePx * 2)
                y = if (lastY != Int.MIN_VALUE) lastY else (dm.heightPixels / 3)
            }

            wireDragAndClick(activity, root, bubble, panel, params, packageName)
            activity.windowManager.addView(root, params)
            attachedRoots[activity] = root
            Log.i(TAG, "Burbuja de diagnóstico instalada en ${activity.javaClass.simpleName} ($packageName)")
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo instalar la burbuja: ${t.message}")
        }
    }

    /** Retira la burbuja de la ventana (onDestroy) para evitar WindowLeaked. */
    fun detach(activity: Activity) {
        val root = attachedRoots.remove(activity) ?: return
        try {
            activity.windowManager.removeView(root)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- Vistas

    private fun makeBubbleView(activity: Activity, sizePx: Int): FrameLayout {
        val statusColor = statusDotColor(activity)
        return FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6123B6B.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
            }
            val label = TextView(activity).apply {
                text = "◆"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            }
            addView(
                label, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val dot = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor)
                }
            }
            val dotSize = (9 * resources.displayMetrics.density).toInt()
            addView(
                dot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, (2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt())
                }
            )
            tag = "aegis_bubble_dot"
        }
    }

    private fun makePanelView(activity: Activity, widthPx: Int, packageName: String): LinearLayout {
        fun styledButton(text: String, bg: Int): Button = Button(activity).apply {
            this.text = text
            setBackgroundColor(bg)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0112240.toInt())
            setPadding((12 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                widthPx, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = (BUBBLE_SIZE_DP * resources.displayMetrics.density).toInt() + (4 * resources.displayMetrics.density).toInt()
            }

            val title = TextView(activity).apply {
                text = "Aegis · diagnóstico"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            val status = TextView(activity).apply {
                id = View.generateViewId()
                text = statusLine(activity, packageName)
                setTextColor(0xFFB9C6D9.toInt())
                textSize = 11f
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            }
            val btnRead = styledButton("Intentar leer librerías", 0xFF2563EB.toInt())
            val btnClose = styledButton("Cerrar contenedor", 0xFFB3261E.toInt())

            btnRead.setOnClickListener {
                status.text = "Leyendo librerías…"
                Thread {
                    val report = try {
                        val r = NativeGuestBridge.inspectCurrentProcessMaps(
                            packageName = packageName,
                            processName = GuestSession.stubProcessSuffix,
                            preloadLibsDir = GuestSession.nativeLibDir,
                            sandboxRootDir = GuestSession.sandboxRoot,
                            forceErrorReason = null
                        )
                        AegisReportProvider.sendReportToHost(activity.applicationContext, r)
                        r
                    } catch (t: Throwable) {
                        Log.w(TAG, "Lectura desde burbuja falló: ${t.message}")
                        null
                    }
                    mainHandler.post {
                        status.text = if (report != null && report.ok) {
                            "OK: ${report.libs.size} librerías mapeadas (PID ${report.pid})"
                        } else {
                            "Fallo: ${report?.error ?: "lectura no disponible"}"
                        }
                    }
                }.start()
            }

            btnClose.setOnClickListener {
                try { activity.finish() } catch (_: Throwable) {}
                Process.killProcess(Process.myPid())
            }

            addView(title)
            addView(status)
            addView(btnRead, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(btnClose, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------------------------------------------------------- Estado

    private fun statusLine(activity: Activity, packageName: String): String {
        val report = try {
            AegisReportProvider.reportsByPackage.value[packageName]
        } catch (_: Throwable) { null }
        return when {
            report == null -> "Sin lectura todavía — corre la app y usa el botón de abajo"
            report.ok -> "Última lectura OK: ${report.libs.size} librerías (PID ${report.pid})"
            else -> "Última lectura FALLÓ: ${report.error ?: "motivo desconocido"}"
        }
    }

    private fun statusDotColor(activity: Activity): Int {
        val pkg = GuestSession.packageName.ifBlank { return 0xFF9AA4B2.toInt() }
        val report = try {
            AegisReportProvider.reportsByPackage.value[pkg]
        } catch (_: Throwable) { null }
        return when {
            report == null -> 0xFF9AA4B2.toInt()   // gris: sin lectura
            report.ok -> 0xFF2ECC71.toInt()        // verde
            else -> 0xFFE74C3C.toInt()             // rojo
        }
    }

    // ---------------------------------------------------------------- Gestos

    private fun wireDragAndClick(
        activity: Activity,
        root: FrameLayout,
        bubble: FrameLayout,
        panel: LinearLayout,
        params: WindowManager.LayoutParams,
        packageName: String
    ) {
        val slop = (DRAG_SLOP_DP * activity.resources.displayMetrics.density).toInt()
        var downRawX = 0f
        var downRawY = 0f
        var startParamsX = 0
        var startParamsY = 0
        var dragged = false

        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startParamsX = params.x
                    startParamsY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragged && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragged = true
                    if (dragged) {
                        val dm = activity.resources.displayMetrics
                        params.x = (startParamsX + dx).coerceIn(0, dm.widthPixels - 8)
                        params.y = (startParamsY + dy).coerceIn(0, dm.heightPixels - 8)
                        try { activity.windowManager.updateViewLayout(root, params) } catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        lastX = params.x
                        lastY = params.y
                    } else {
                        expanded = !expanded
                        panel.visibility = if (expanded) View.VISIBLE else View.GONE
                        // Al expandir, si la burbuja está muy a la derecha, el panel puede
                        // salirse: se recoloca la raíz hacia dentro.
                        if (expanded) {
                            val dm = activity.resources.displayMetrics
                            val maxOverflow = panel.layoutParams.let { (it as FrameLayout.LayoutParams).width } + (16 * dm.density).toInt()
                            if (params.x + maxOverflow > dm.widthPixels) {
                                params.x = (dm.widthPixels - maxOverflow).coerceAtLeast(0)
                                try { activity.windowManager.updateViewLayout(root, params) } catch (_: Throwable) {}
                                lastX = params.x
                            }
                        }
                        root.requestLayout()
                    }
                    true
                }
                else -> false
            }
        }
    }
}
