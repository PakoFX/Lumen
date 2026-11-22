package com.pako.eminus4.notificaciones

import android.content.Context
import com.google.gson.Gson
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.model.CourseActivityItem
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.model.MessageItem

/**
 * "Novedades desde tu última visita": se guarda una FOTO de lo visto (por cuenta) y al cargar
 * se compara contra ella. Solo detecta lo que apareció o cambió (no lo que desapareció).
 *  - La primera vez de cada fuente solo se guarda la foto (no se marca todo como nuevo).
 *  - "Marcar como visto" guarda lo actual como nueva foto.
 *  - Una fuente que no cargó (null) no se compara ni se toca.
 */
object Novedades {

    enum class Tipo { CALIFICADA, RECALIFICADA, TAREA_NUEVA, EXAMEN_NUEVO, FORO_COMENTARIOS, FORO_NUEVO, MENSAJE }

    data class Novedad(
        val tipo: Tipo,
        val idCurso: Long,
        val actividad: CourseActivityItem? = null,
        val examen: ExamItem? = null,
        val foro: ForumItem? = null,
        val mensaje: MessageItem? = null,
        /** Calificación anterior (recalificada) o comentarios nuevos (foros). */
        val antes: Double? = null,
        val cantidad: Int = 0
    )

    /** Foto guardada: qué se había visto la última vez. */
    private data class Foto(
        val actividades: Map<String, Double?>? = null, // clave → calificación (null = sin calificar)
        val examenes: Set<String>? = null,
        val foros: Map<String, Int>? = null,           // clave → total de comentarios
        val mensajes: Set<Long>? = null                // ids de no leídos ya vistos
    )

    // serializeNulls: sin esto Gson descarta las actividades sin calificar (valor null) y vuelven a salir como "nuevas"
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("novedades", Context.MODE_PRIVATE)
    private fun claveFoto(ctx: Context) = "foto_" + (SessionManager(ctx).getMatriculaSesion() ?: "sin_cuenta")

    private fun leer(ctx: Context): Foto =
        prefs(ctx).getString(claveFoto(ctx), null)?.let {
            try { gson.fromJson(it, Foto::class.java) } catch (_: Exception) { null }
        } ?: Foto()

    private fun guardar(ctx: Context, f: Foto) = prefs(ctx).edit().putString(claveFoto(ctx), gson.toJson(f)).apply()

    private fun kAct(a: CourseActivityItem) = "${a.courseId}:${a.activity.idActividad}"
    private fun kEx(e: ExamItem) = "${e.idCurso}:${e.idExamen}"
    private fun kForo(f: ForumItem) = "${f.idCurso}:${f.idForo}"

    private fun foto(
        base: Foto, acts: List<CourseActivityItem>?, exams: List<ExamItem>?,
        foros: List<ForumItem>?, msgs: List<MessageItem>?
    ) = Foto(
        actividades = acts?.associate { kAct(it) to it.activity.calificacion } ?: base.actividades,
        examenes = exams?.map { kEx(it) }?.toSet() ?: base.examenes,
        foros = foros?.associate { kForo(it) to it.totalComentarios } ?: base.foros,
        // Mensajes: se acumulan los ya vistos para no repetirlos
        mensajes = msgs?.let { (base.mensajes.orEmpty() + it.map { m -> m.idMensaje }).toSet() } ?: base.mensajes
    )

    /** Novedades respecto a la última foto. Las fuentes sin foto previa quedan como base. */
    fun calcular(
        ctx: Context, acts: List<CourseActivityItem>?, exams: List<ExamItem>?,
        foros: List<ForumItem>?, msgs: List<MessageItem>?
    ): List<Novedad> {
        val f = leer(ctx)
        // Primera vez de cada fuente: guardar como base, sin novedades
        val sinBase = Foto(
            actividades = if (f.actividades == null) acts?.associate { kAct(it) to it.activity.calificacion } else f.actividades,
            examenes = if (f.examenes == null) exams?.map { kEx(it) }?.toSet() else f.examenes,
            foros = if (f.foros == null) foros?.associate { kForo(it) to it.totalComentarios } else f.foros,
            mensajes = if (f.mensajes == null) msgs?.map { it.idMensaje }?.toSet() else f.mensajes
        )
        if (sinBase != f) guardar(ctx, sinBase)

        val lista = mutableListOf<Novedad>()
        if (f.actividades != null) acts?.forEach { a ->
            val k = kAct(a)
            val cal = a.activity.calificacion
            when {
                !f.actividades.containsKey(k) -> lista += Novedad(Tipo.TAREA_NUEVA, a.courseId, actividad = a)
                cal != null && f.actividades[k] == null -> lista += Novedad(Tipo.CALIFICADA, a.courseId, actividad = a)
                cal != null && f.actividades[k] != null && f.actividades[k] != cal ->
                    lista += Novedad(Tipo.RECALIFICADA, a.courseId, actividad = a, antes = f.actividades[k])
            }
        }
        if (f.examenes != null) exams?.forEach { e ->
            if (kEx(e) !in f.examenes) lista += Novedad(Tipo.EXAMEN_NUEVO, e.idCurso, examen = e)
        }
        if (f.foros != null) foros?.forEach { fo ->
            val antes = f.foros[kForo(fo)]
            when {
                antes == null -> lista += Novedad(Tipo.FORO_NUEVO, fo.idCurso, foro = fo)
                fo.totalComentarios > antes -> lista += Novedad(Tipo.FORO_COMENTARIOS, fo.idCurso, foro = fo, cantidad = fo.totalComentarios - antes)
            }
        }
        if (f.mensajes != null) msgs?.forEach { m ->
            if (m.idMensaje !in f.mensajes) lista += Novedad(Tipo.MENSAJE, 0, mensaje = m)
        }
        // Calificaciones primero, luego tareas/exámenes nuevos, luego lo demás
        return lista.sortedBy { it.tipo.ordinal }
    }

    /** Guarda lo actual como visto: la próxima vez solo sale lo nuevo a partir de aquí. */
    fun marcarVisto(
        ctx: Context, acts: List<CourseActivityItem>?, exams: List<ExamItem>?,
        foros: List<ForumItem>?, msgs: List<MessageItem>?
    ) = guardar(ctx, foto(leer(ctx), acts, exams, foros, msgs))
}
