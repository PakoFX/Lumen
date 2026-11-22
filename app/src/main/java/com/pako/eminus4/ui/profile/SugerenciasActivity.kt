package com.pako.eminus4.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pako.eminus4.R
import com.pako.eminus4.ui.novedades.QueHayDeNuevo
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Buzón de sugerencias: se envía a un Google Form (las respuestas llegan a la cuenta del
 * desarrollador). NO se manda matrícula, nombre ni nada de Eminus: solo lo que la persona escribe
 * y, si lo permite, versión de la app / modelo / Android.
 */
class SugerenciasActivity : AppCompatActivity() {

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, SugerenciasActivity::class.java))

        // Formulario del buzón en Google Forms ("Buzón Bodrio-Nus" en la cuenta del desarrollador) y el id de cada pregunta
        private const val URL_FORM =
            "https://docs.google.com/forms/d/e/1FAIpQLSfGo2mux5XNb83tUcwqj9b5lJE9cVg31d4CrGU-xGJ0gtpzfQ/formResponse"
        private const val ENTRY_TIPO = "entry.2004858192"      // Sugerencia / Algo falla / Otro
        private const val ENTRY_MENSAJE = "entry.470979833"
        private const val ENTRY_DATOS = "entry.1822881987"
        private const val ENTRY_CONTACTO = "entry.927328003"
    }

    /** Cliente propio y limpio: nunca lleva el token ni las cookies de Eminus. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var chipsTipo: ChipGroup
    private lateinit var tilMensaje: TextInputLayout
    private lateinit var editMensaje: TextInputEditText
    private lateinit var editContacto: TextInputEditText
    private lateinit var chkDatos: MaterialCheckBox
    private lateinit var btnEnviar: MaterialButton
    private var enviando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sugerencias)

        val appBar = findViewById<View>(R.id.appBarLayout)
        val barra = findViewById<View>(R.id.barraEnviarSug)
        val padBarra = barra.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootSugerencias)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            appBar.setPadding(0, sb.top, 0, 0)
            barra.setPadding(barra.paddingLeft, barra.paddingTop, barra.paddingRight, padBarra + maxOf(sb.bottom, ime.bottom))
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        chipsTipo = findViewById(R.id.chipsTipo)
        tilMensaje = findViewById(R.id.tilMensajeSug)
        editMensaje = findViewById(R.id.editMensajeSug)
        editContacto = findViewById(R.id.editContactoSug)
        chkDatos = findViewById(R.id.chkDatosTecnicos)
        btnEnviar = findViewById(R.id.btnEnviarSug)

        // Mostrar exactamente qué datos técnicos se enviarían
        val txtDatos = findViewById<TextView>(R.id.txtDatosTecnicos)
        txtDatos.text = datosTecnicos()
        chkDatos.setOnCheckedChangeListener { _, v -> txtDatos.visibility = if (v) View.VISIBLE else View.GONE }

        chipsTipo.setOnCheckedStateChangeListener { _, _ ->
            tilMensaje.hint = when (chipsTipo.checkedChipId) {
                R.id.chipFalla -> "¿Qué falla? ¿En qué pantalla y qué estabas haciendo?"
                R.id.chipOtro -> "Escribe aquí tu mensaje"
                else -> "Escribe aquí tu sugerencia"
            }
        }
        btnEnviar.setOnClickListener { enviar() }
    }

    private fun datosTecnicos(): String =
        "Lumen v${QueHayDeNuevo.versionActual(this)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun tipo(): String = when (chipsTipo.checkedChipId) {
        R.id.chipFalla -> "Algo falla"
        R.id.chipOtro -> "Otro"
        else -> "Sugerencia"
    }

    private fun enviar() {
        if (enviando) return
        val mensaje = editMensaje.text?.toString()?.trim().orEmpty()
        tilMensaje.error = null
        if (mensaje.length < 5) {
            tilMensaje.error = "Cuéntanos un poco más"
            return
        }
        enviando = true
        btnEnviar.isEnabled = false
        btnEnviar.text = "Enviando…"

        val cuerpo = FormBody.Builder()
            .add(ENTRY_TIPO, tipo())
            .add(ENTRY_MENSAJE, mensaje)
            .add(ENTRY_DATOS, if (chkDatos.isChecked) datosTecnicos() else "")
            .add(ENTRY_CONTACTO, editContacto.text?.toString()?.trim().orEmpty())
            .build()
        val req = Request.Builder().url(URL_FORM).post(cuerpo).build()
        val principal = Handler(Looper.getMainLooper())

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                principal.post { terminar(ok = false, "Sin conexión. Inténtalo de nuevo.") }
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                val code = response.code
                response.close()
                principal.post { terminar(ok, if (ok) null else "No se pudo enviar ($code).") }
            }
        })
    }

    private fun terminar(ok: Boolean, error: String?) {
        if (isFinishing || isDestroyed) return
        enviando = false
        btnEnviar.isEnabled = true
        btnEnviar.text = "Enviar"
        if (!ok) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_check_circle)
            .setTitle("¡Gracias!")
            .setMessage("Recibimos tu mensaje. Lo vamos a revisar para mejorar Lumen.")
            .setPositiveButton("Listo") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
