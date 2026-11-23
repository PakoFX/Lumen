package com.pako.eminus4.domain

import com.pako.eminus4.data.model.StudentCredential
import com.pako.eminus4.data.model.UserProfile

/** Lo que muestra la tarjeta del Perfil. Textos vacíos = no se sabe. */
data class ProfileCard(
    val name: String,
    val studentId: String,
    val program: String,
    val campus: String,
    val validity: String,
    val email: String,
    val alternateEmail: String?,
    val avatarUrl: String?
)

/**
 * Une el perfil de Eminus con la credencial guardada (que puede traer datos oficiales de MiUV).
 * La matrícula siempre sale de la sesión actual: una credencial de otra cuenta se ignora.
 */
object ProfileCardBuilder {

    const val VALIDITY_MONTHS = "AGO-ENE"
    const val VALIDITY_YEAR = "2027"

    data class Result(val card: ProfileCard, val credentialToSave: StudentCredential?)

    fun build(profile: UserProfile, saved: StudentCredential?): Result {
        val studentId = StudentId.normalize(profile.userId).orEmpty()
        val credential = saved?.takeIf { it.getMatriculaOficial() == studentId }

        val name = credential?.getNombreFormateado()?.takeIf { it.isNotBlank() } ?: profile.getNombreCompleto()
        val campus = profile.userCampus.ifBlank { credential?.getCampusOficial().orEmpty() }
        val card = ProfileCard(
            name = name,
            studentId = studentId,
            program = credential?.getProgramaOficial().orEmpty(),
            campus = campus,
            validity = credential?.getVigenciaTexto() ?: "$VALIDITY_MONTHS $VALIDITY_YEAR",
            email = profile.userEmail,
            alternateEmail = profile.alternateEmail?.takeIf { it.isNotBlank() },
            avatarUrl = profile.getFullAvatarUrl()?.takeIf { it.isNotBlank() }
        )
        return Result(card, credentialToSave(credential, card))
    }

    /** Completa o crea la credencial de esta cuenta; null si ya está bien. Conserva lo que trajo MiUV. */
    private fun credentialToSave(current: StudentCredential?, card: ProfileCard): StudentCredential? {
        if (card.studentId.isBlank()) return null
        val upToDate = current != null && current.name.isNotBlank() &&
            current.monthsValidity == VALIDITY_MONTHS && current.yearValidity == VALIDITY_YEAR
        if (upToDate) return null
        return current?.copy(name = card.name, region = card.campus, monthsValidity = VALIDITY_MONTHS, yearValidity = VALIDITY_YEAR)
            ?: StudentCredential(
                user = card.studentId,
                name = card.name,
                program = card.program,
                region = card.campus,
                monthsValidity = VALIDITY_MONTHS,
                yearValidity = VALIDITY_YEAR,
                photo = null,   // nunca el avatar casual de Eminus
                qrCode = "https://dsia.uv.mx/credencial/validar?u=${card.studentId}",
                barCode = card.studentId
            )
    }
}
