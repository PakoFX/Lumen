package com.pako.eminus4.notificaciones

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.pako.eminus4.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Perfil → Notificaciones: qué recordar, cuánto antes y permisos para que lleguen a tiempo. */
class NotificacionesActivity : AppCompatActivity() {

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, NotificacionesActivity::class.java))
    }

    private lateinit var prefs: NotifPrefs
    private lateinit var layoutPermisos: LinearLayout
    private lateinit var txtResumen: TextView
    private var yaSePidioPermiso = false

    /** Qué tan importante es cada ajuste (se muestra en color junto al título). */
    private enum class Nivel(val texto: String, val color: Int) {
        NECESARIO("NECESARIO", R.color.badge_error_text),
        MUY_RECOMENDABLE("MUY RECOMENDABLE", R.color.badge_warning_text),
        RECOMENDADO("RECOMENDADO", R.color.badge_info_text),
        OPCIONAL("OPCIONAL", R.color.text_tertiary)
    }

    private val pedirPermiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pintarPermisos(); Recordatorios.reprogramar(this); pintarResumen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notificaciones)
        prefs = NotifPrefs(this)

        val appBar = findViewById<View>(R.id.appBarLayout)
        val scroll = findViewById<View>(R.id.scrollNotif)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootNotif)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBar.setPadding(0, sb.top, 0, 0)
            scroll.setPadding(0, 0, 0, sb.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        layoutPermisos = findViewById(R.id.layoutPermisos)
        txtResumen = findViewById(R.id.txtResumenAlarmas)
        configurarInterruptores()
        configurarMinutos()
        findViewById<MaterialButton>(R.id.btnProbar).setOnClickListener {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Toast.makeText(this, "Primero permite las notificaciones (abajo)", Toast.LENGTH_SHORT).show()
            } else Recordatorios.probar(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver de los ajustes de Android, reflejar permisos y reprogramar
        Recordatorios.reprogramar(this)
        pintarPermisos()
        pintarResumen()
    }

    private fun cambio() {
        Recordatorios.reprogramar(this)
        pintarResumen()
    }

    private fun configurarInterruptores() {
        val contenido = findViewById<View>(R.id.contenidoAjustes)
        fun sw(id: Int, valor: Boolean, set: (Boolean) -> Unit) = findViewById<MaterialSwitch>(id).apply {
            isChecked = valor
            setOnCheckedChangeListener { _, v -> set(v); cambio() }
        }
        val general = findViewById<MaterialSwitch>(R.id.swActivadas)
        general.isChecked = prefs.activadas
        contenido.alpha = if (prefs.activadas) 1f else 0.4f
        general.setOnCheckedChangeListener { _, v ->
            prefs.activadas = v
            contenido.alpha = if (v) 1f else 0.4f
            pintarPermisos()
            if (v) pedirPermisoSiFalta()
            cambio()
        }
        sw(R.id.swTareas, prefs.tareas) { prefs.tareas = it }
        sw(R.id.swVencida, prefs.avisarVencida) { prefs.avisarVencida = it }
        sw(R.id.swExamenes, prefs.examenes) { prefs.examenes = it }
        sw(R.id.swExamenAbre, prefs.examenAlAbrir) { prefs.examenAlAbrir = it }
        sw(R.id.swForos, prefs.foros) { prefs.foros = it }
        sw(R.id.swEmojis, prefs.conEmojis) { prefs.conEmojis = it }
    }

    private fun configurarMinutos() {
        val grupo = findViewById<ChipGroup>(R.id.chipsMinutos)
        val elegidos = prefs.minutosAntes
        NotifPrefs.OPCIONES_MINUTOS.forEach { m ->
            grupo.addView((LayoutInflater.from(this).inflate(R.layout.item_chip_filtro, grupo, false) as Chip).apply {
                id = View.generateViewId()
                text = NotifPrefs.etiqueta(m)
                isChecked = m in elegidos
                setOnCheckedChangeListener { _, marcado ->
                    prefs.minutosAntes = if (marcado) prefs.minutosAntes + m else prefs.minutosAntes - m
                    cambio()
                }
            })
        }
    }

    /** "12 recordatorios programados · el próximo: vie 21 nov, 18:00". */
    private fun pintarResumen() {
        val alarmas = Recordatorios.alarmasProgramadas(this)
        txtResumen.text = when {
            !prefs.activadas -> "Desactivados: no recibirás recordatorios."
            alarmas.isEmpty() -> "Sin recordatorios por ahora. Se programan solos al cargar tus actividades."
            else -> {
                val prox = alarmas.minByOrNull { it.at }!!
                val nombre = Recordatorios.buscarFuente(this, prox.key)?.name.orEmpty()
                val cuando = SimpleDateFormat("EEE d MMM, HH:mm", Locale("es", "MX")).format(Date(prox.at))
                "${alarmas.size} recordatorio${if (alarmas.size == 1) "" else "s"} programado${if (alarmas.size == 1) "" else "s"}" +
                    " · el próximo: $cuando" + if (nombre.isNotBlank()) " ($nombre)" else ""
            }
        }
    }

    // ===================== PERMISOS =====================

    private fun pedirPermisoSiFalta() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("BatteryLife")
    private fun pintarPermisos() {
        layoutPermisos.removeAllViews()
        // Con los recordatorios apagados, los permisos no importan
        findViewById<View>(R.id.seccionPermisos).visibility = if (prefs.activadas) View.VISIBLE else View.GONE
        faltaNecesario = false
        faltaAlgo = false

        val notifOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        filaPermiso("Notificaciones", Nivel.NECESARIO, if (notifOk) "Permitidas" else "Bloqueadas: no verás ningún aviso", notifOk, "Permitir") {
            val sinPermiso = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (sinPermiso && !yaSePidioPermiso) {
                yaSePidioPermiso = true
                pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Ya lo negó antes (Android no vuelve a preguntar): ajustes de notificaciones de la app
                abrir(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val exactas = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            filaPermiso("Alarmas a la hora exacta", Nivel.RECOMENDADO, if (exactas) "Permitidas" else "Sin esto pueden llegar unos minutos tarde", exactas, "Permitir") {
                abrir(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }

        val pm = getSystemService(PowerManager::class.java)
        val bateria = pm.isIgnoringBatteryOptimizations(packageName)
        filaPermiso("Batería sin restricciones", Nivel.OPCIONAL, if (bateria) "Listo" else "Android podría retrasar los avisos para ahorrar batería", bateria, "Quitar") {
            abrir(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

        // Xiaomi / Redmi / POCO: sin "Inicio automático" MIUI puede bloquear los avisos
        val fab = Build.MANUFACTURER.lowercase()
        if (fab.contains("xiaomi") || fab.contains("redmi") || fab.contains("poco")) {
            filaPermiso("Inicio automático (Xiaomi)", Nivel.MUY_RECOMENDABLE, "Actívalo para Lumen; si no, MIUI puede bloquear los avisos", null, "Abrir") {
                val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                if (!abrir(miui)) abrir(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    private var faltaNecesario = false
    private var faltaAlgo = false

    /** Texto de ayuda arriba de la sección según lo que falte. */
    private fun pintarAyudaPermisos() {
        val ayuda = findViewById<TextView>(R.id.txtAyudaPermisos)
        when {
            faltaNecesario -> {
                ayuda.text = "Falta lo NECESARIO: sin esto no verás ningún recordatorio."
                ayuda.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
            }
            faltaAlgo -> {
                ayuda.text = "Lo necesario está listo. Lo demás hace que lleguen puntuales y no se pierdan."
                ayuda.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            else -> {
                ayuda.text = "Todo listo: tus recordatorios llegarán a tiempo."
                ayuda.setTextColor(ContextCompat.getColor(this, R.color.badge_success_text))
            }
        }
    }

    /** @param ok true = listo (sin botón), false = falta, null = no se puede saber (botón siempre). */
    private fun filaPermiso(titulo: String, nivel: Nivel, detalle: String, ok: Boolean?, boton: String, accion: () -> Unit) {
        if (ok != true) {
            faltaAlgo = true
            if (nivel == Nivel.NECESARIO) faltaNecesario = true
        }
        val fila = LayoutInflater.from(this).inflate(R.layout.item_aviso_fila, layoutPermisos, false)
        fila.tag = nivel.ordinal
        fila.findViewById<android.widget.ImageView>(R.id.imgFilaIcono).apply {
            setImageResource(if (ok == true) R.drawable.ic_check_circle else if (ok == false) R.drawable.ic_warning else R.drawable.ic_lock)
            imageTintList = ContextCompat.getColorStateList(this@NotificacionesActivity,
                if (ok == true) R.color.badge_success_text else if (ok == false) R.color.badge_warning_text else R.color.text_secondary)
        }
        // Título + nivel en color: "Notificaciones  NECESARIO"
        val colorNivel = ContextCompat.getColor(this, nivel.color)
        fila.findViewById<TextView>(R.id.txtFilaTitulo).text = android.text.SpannableStringBuilder(titulo).apply {
            append("  ")
            val ini = length
            append(nivel.texto)
            setSpan(android.text.style.ForegroundColorSpan(colorNivel), ini, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.RelativeSizeSpan(0.72f), ini, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), ini, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fila.findViewById<TextView>(R.id.txtFilaSubtitulo).apply { text = detalle; visibility = View.VISIBLE }
        fila.findViewById<TextView>(R.id.txtFilaValor).apply {
            if (ok == true) { visibility = View.GONE } else {
                visibility = View.VISIBLE
                text = boton
                setTextColor(ContextCompat.getColor(this@NotificacionesActivity, R.color.brand_uv_green))
            }
        }
        if (ok != true) fila.setOnClickListener { accion() }
        // Insertar ordenado por importancia (Necesario primero)
        val pos = (0 until layoutPermisos.childCount).firstOrNull { (layoutPermisos.getChildAt(it).tag as Int) > nivel.ordinal }
        if (pos == null) layoutPermisos.addView(fila) else layoutPermisos.addView(fila, pos)
        pintarAyudaPermisos()
    }

    private fun abrir(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }
}
