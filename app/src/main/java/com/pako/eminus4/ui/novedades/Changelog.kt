package com.pako.eminus4.ui.novedades

import androidx.annotation.DrawableRes
import com.pako.eminus4.R

/**
 * "Qué hay de nuevo": lo que trae cada versión, para mostrárselo al usuario.
 *
 * REGLA: al publicar una versión, agregar su entrada ARRIBA (la más nueva primero) con el mismo
 * `version` que `versionName` en build.gradle.kts. Frases cortas y en lenguaje de usuario.
 */
object Changelog {

    data class Punto(@DrawableRes val icono: Int, val titulo: String, val detalle: String)
    data class Version(val version: String, val fecha: String, val puntos: List<Punto>)

    val versiones = listOf(
        Version("2.12.1", "Noviembre 2026", listOf(
            Punto(R.drawable.ic_rocket_launch, "Mejoras internas",
                "La app quedó más ordenada por dentro: carga más estable y menos errores al perder la conexión."),
        )),
        Version("2.12.0", "Noviembre 2026", listOf(
            Punto(R.drawable.ic_rocket_launch, "Ahora nos llamamos Lumen",
                "Bodrio-Nus cambia de nombre e ícono. Es la misma app, con todo lo que ya tenías."),
            Punto(R.drawable.ic_schedule, "Tu horario de clases",
                "Toca el reloj junto a Mensajes: tus clases de cada día con salón y profesor, y cuál sigue. Se toma de MiUV al iniciar sesión."),
            Punto(R.drawable.ic_menu, "Nueva navegación",
                "Menú ☰ con todas las secciones; Mensajes y tu foto de perfil siempre a la mano arriba."),
            Punto(R.drawable.ic_schedule, "Recordatorios de entregas",
                "Te avisamos antes de que venza una tarea o cierre un examen (24 h, 5 h y 1 h), aunque tu sesión caduque. Configúralos en Perfil → Notificaciones."),
            Punto(R.drawable.ic_star, "Novedades desde tu última visita",
                "En Avisos ves qué te calificaron, qué tareas o exámenes nuevos subieron y quién te escribió."),
            Punto(R.drawable.ic_library_books, "Contenido, miembros y seguimiento",
                "Dentro de cada materia: lee el contenido del profe, ve quiénes están en el grupo y revisa tu avance y puntos."),
            Punto(R.drawable.ic_school, "Todo ordenado por materia",
                "Toca una materia en Avisos para ver todos sus pendientes. Exámenes y Foros se agrupan en bloques por materia, activas o pasadas."),
            Punto(R.drawable.ic_forum, "Participa en los foros",
                "Lee, comenta, responde y da me gusta sin salir de la app. También abre y adjunta archivos."),
            Punto(R.drawable.ic_mail, "Escribe y responde mensajes",
                "Redacta mensajes a tu profe o compañeros, adjunta archivos y responde desde el detalle del mensaje."),
            Punto(R.drawable.ic_folder_zip, "Cursos archivados",
                "Mantén presionado un curso para archivarlo; consúltalos en el apartado Archivados."),
            Punto(R.drawable.ic_qr_code, "Credencial mejorada",
                "Tu foto ahora se ve en alta calidad y el código QR verifica tu credencial oficial de la UV."),
            Punto(R.drawable.ic_edit_note, "Buzón de sugerencias",
                "¿Una idea o algo que falla? Cuéntanos desde Perfil → Buzón de sugerencias."),
            Punto(R.drawable.ic_task_alt, "Detalles y correcciones",
                "Filtros más compactos, nombres de materia resumidos, notificaciones con o sin emojis, contenido de las materias completo (lo publicado fuera de unidades, enlaces, imágenes, videos, páginas web y comentarios), botón para volver a cargar la foto de tu credencial, aviso de privacidad en Perfil y aviso de actualización más claro.")
        )),
        Version("2.8.0", "Noviembre 2026", listOf(
            Punto(R.drawable.ic_school, "Credencial con datos oficiales",
                "Tu programa, campus y foto se toman directo de MiUV."),
            Punto(R.drawable.ic_lock, "Inicio de sesión más estable",
                "Menos errores al entrar y al renovar tu sesión.")
        ))
    )
}
