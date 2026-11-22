package com.pako.eminus4.data.model

import com.google.gson.annotations.SerializedName
import com.pako.eminus4.core.Constants

// ===================== CONTENIDO =====================

/** GET Contenido/getUnidades/{idCurso}/0 */
data class ContentUnit(
    @SerializedName("idUnidad") val idUnidad: Long = 0,
    @SerializedName("nombre") val nombre: String? = null,
    @SerializedName("totalElementos") val totalElementos: Int = 0,
    @SerializedName("posicion") val posicion: Int = 0,
    @SerializedName("visible") val visible: Int = 1
)

/** GET Contenido/getElementos/{idCurso}/{idUnidad} */
data class ContentElement(
    @SerializedName("idElemento") val idElemento: Long = 0,
    @SerializedName("idUnidad") val idUnidad: Long = 0,
    @SerializedName("nombre") val nombre: String? = null,
    @SerializedName("texto") val texto: String? = null,
    @SerializedName("fechaPublicacion") val fechaPublicacion: String? = null,
    @SerializedName("lecturaObligatoria") val lecturaObligatoria: Int = 0,
    @SerializedName("usuarioLeido") val usuarioLeido: Int = 0,
    @SerializedName("usuarioLike") val usuarioLike: Int = 0,
    @SerializedName("likes") val likes: Int = 0,
    @SerializedName("posicion") val posicion: Int = 0,
    @SerializedName("visible") val visible: Int = 1,
    @SerializedName("archivosElementosConTamano") val archivos: List<ContentFile>? = null,
    /** 1 = texto, 2 = galería de imágenes, 5 = página web (ZIP que Eminus sirve con [paginaInicio]). */
    @SerializedName("idTipoElemento") val idTipoElemento: Int = 1,
    /** Enlaces e imágenes que el profe puso en el texto (editor Froala). */
    @SerializedName("archivosFroala") val enlaces: List<String>? = null,
    @SerializedName("paginaInicio") val paginaInicio: String? = null,
    @SerializedName("spsComentariosElementos") val comentarios: List<ContentComment>? = null
) {
    val leido get() = usuarioLeido == 1
    val esPaginaWeb get() = idTipoElemento == 5 && !paginaInicio.isNullOrBlank()

    /** Tipo 5: `eminusapi/drive/<ruta del recurso>/<paginaInicio>` (público, como lo abre Eminus web). */
    fun urlPaginaWeb(): String? {
        if (!esPaginaWeb) return null
        val ruta = archivos.orEmpty().firstOrNull { !it.ruta.isNullOrBlank() }?.ruta ?: return null
        val carpeta = ruta.replace("\\", "/").trim('/')
        return "${Constants.EMINUS_DRIVE_URL}$carpeta/${paginaInicio!!.trimStart('/')}"
    }

    /** Enlaces externos (sin las imágenes/audios incrustados, que ya se ven en el texto). */
    fun enlacesExternos(): List<String> = enlaces.orEmpty()
        .map { it.trim() }
        .filter { it.startsWith("http", true) && !it.contains("eminus.uv.mx/eminusapi/froala/", true) }
        .filterNot { Regex("""\.(png|jpe?g|gif|webp|mp3|wav|ogg|m4a)(\?.*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        .distinct()

    fun comentariosVisibles() = comentarios.orEmpty().filter { it.visible == 1 }
}

data class ContentComment(
    @SerializedName("idComentarioElemento") val idComentario: Long = 0,
    @SerializedName("nombreCompleto") val nombreCompleto: String? = null,
    @SerializedName("texto") val texto: String? = null,
    @SerializedName("fechaPublicacion") val fechaPublicacion: String? = null,
    @SerializedName("ruta") val ruta: String? = null,
    @SerializedName("visible") val visible: Int = 1
) {
    /** "LOPEZ VASQUEZ, YESENIA " → "Yesenia Lopez Vasquez" */
    fun nombreBonito(): String {
        val n = nombreCompleto.orEmpty().trim()
        val partes = n.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val orden = if (partes.size == 2) "${partes[1]} ${partes[0]}" else n
        return orden.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    fun fotoUrl(): String? {
        val r = ruta?.takeIf { it.isNotBlank() } ?: return null
        if (r.startsWith("http")) return r
        return "https://eminus.uv.mx/eminusapi/" + r.replace("\\", "/").trimStart('/')
    }
}

data class ContentFile(
    @SerializedName("idArchivosElementos") val idArchivosElementos: Long = 0,
    @SerializedName("nombreOriginal") val nombreOriginal: String? = null,
    @SerializedName("peso") val peso: String? = null,
    @SerializedName("ruta") val ruta: String? = null,
    @SerializedName("visible") val visible: Int = 1
) {
    /** GET contenido/descargaElementoArchivo/{idCurso}/{idElemento}/{idArchivosElementos} */
    fun urlDescarga(idCurso: Long, idElemento: Long) =
        "${Constants.API_GLOBAL_URL}contenido/descargaElementoArchivo/$idCurso/$idElemento/$idArchivosElementos"
}

// ===================== MIEMBROS =====================

/** GET Usuario/getIntegrantes/{idCurso}. Solo se usan nombre, rol, foto, correo y último acceso. */
data class CourseMember(
    @SerializedName("idUsuario") val idUsuario: String? = null,
    @SerializedName("idTipoPerfil") val idTipoPerfil: Int = 0,
    @SerializedName("tipoPerfil") val tipoPerfil: String? = null,
    @SerializedName("nombre") val nombre: String? = null,
    @SerializedName("paterno") val paterno: String? = null,
    @SerializedName("materno") val materno: String? = null,
    @SerializedName("correo") val correo: String? = null,
    @SerializedName("ultimoAcceso") val ultimoAcceso: String? = null,
    @SerializedName("ruta") val ruta: String? = null
) {
    /** 1 = facilitador (profe), 2 = estudiante, 3 = apoyo académico… (igual que Eminus web). */
    val esProfe get() = idTipoPerfil == 1 || idTipoPerfil == 3

    fun nombreCompleto(): String = listOfNotNull(nombre, paterno, materno)
        .filter { it.isNotBlank() }.joinToString(" ")
        .lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    fun fotoUrl(): String? {
        val r = ruta?.takeIf { it.isNotBlank() } ?: return null
        if (r.startsWith("http")) return r
        return "https://eminus.uv.mx/eminusapi/" + r.replace("\\", "/").trimStart('/')
    }
}

// ===================== SEGUIMIENTO =====================

/** GET Seguimiento/SegDetalle/{idCurso}/{idUsuario}: avance propio en el curso. */
data class TrackingDetail(
    @SerializedName("idUsuario") val idUsuario: String? = null,
    @SerializedName("totalRecursos") val totalRecursos: String? = null,   // "22/27"
    @SerializedName("totalPuntos") val totalPuntos: String? = null,       // "149/270"
    @SerializedName("detalle") val detalle: TrackingGroups? = null
)

data class TrackingGroups(
    @SerializedName("actividades") val actividades: List<TrackingItem>? = null,
    @SerializedName("foros") val foros: List<TrackingItem>? = null,
    @SerializedName("examenes") val examenes: List<TrackingItem>? = null
)

data class TrackingItem(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("titulo") val titulo: String? = null,
    @SerializedName("valor") val valor: String? = null,         // "9.5 / 10 pts."
    @SerializedName("realizado") val realizado: String? = null, // fecha de entrega
    @SerializedName("revisado") val revisado: String? = null    // fecha de revisión
)

/** "22/27" → (22, 27) */
fun fraccion(texto: String?): Pair<Double, Double>? {
    val partes = texto?.split("/")?.map { it.trim().replace(",", ".") } ?: return null
    if (partes.size != 2) return null
    val a = partes[0].toDoubleOrNull() ?: return null
    val b = partes[1].substringBefore(" ").toDoubleOrNull() ?: return null
    return a to b
}
