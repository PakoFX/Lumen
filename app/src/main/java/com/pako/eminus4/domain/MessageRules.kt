package com.pako.eminus4.domain

import com.pako.eminus4.data.model.MessageDetail

object MessageRules {

    /**
     * Se puede responder si el mensaje lo envió otra persona, desde un curso que sigue vigente
     * (igual que Eminus web: no se responde en cursos concluidos).
     */
    fun canReply(detail: MessageDetail, myStudentId: String?, activeCourseIds: Set<Long>): Boolean {
        val sender = detail.remitente ?: return false
        val courseId = sender.idCurso ?: return false
        val isMine = StudentId.normalize(sender.idUsuario) == myStudentId
        return !isMine && courseId in activeCourseIds
    }
}
