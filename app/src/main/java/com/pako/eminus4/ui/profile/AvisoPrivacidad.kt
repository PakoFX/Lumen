package com.pako.eminus4.ui.profile

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pako.eminus4.R

/** Aviso de privacidad y aviso legal (Perfil → "Privacidad y aviso legal"). Mantener igual al README. */
object AvisoPrivacidad {

    private const val TEXTO = """Qué datos usa Lumen
• Tu contraseña NO se guarda. Solo se usa para entrar a Eminus y MiUV y se borra de la memoria en cuanto termina.
• Tu sesión, credencial, foto, matrícula y horario se guardan SOLO en este celular, dentro de la app.
• Estos datos no se respaldan en la nube ni se copian a otro teléfono.

A dónde se conecta
• Eminus y MiUV (servidores de la UV), igual que si entraras desde el navegador.
• GitHub, solo para buscar actualizaciones de la app.
• No hay servidor propio de Lumen, ni publicidad, ni estadísticas de uso.

Buzón de sugerencias
• Solo se envía lo que tú escribes (y, si lo marcas, versión de la app y modelo del celular). Nunca tu nombre, matrícula ni datos de Eminus.

Cómo borrar tus datos
• Al cerrar sesión se borra todo lo de tu cuenta: sesión, credencial y foto, horario, recordatorios y las copias de archivos que abriste o subiste desde la app (lo que guardaste en Descargas no se toca). Si la sesión solo caduca, no se borra nada.
• Para borrar absolutamente todo: desinstala la app, o en Ajustes de Android → Apps → Lumen → Borrar datos.

"""

    fun mostrar(ctx: Context) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Privacidad y aviso legal")
            .setMessage(TEXTO + ctx.getString(R.string.aviso_no_oficial))
            .setPositiveButton("Entendido", null)
            .show()
    }
}
