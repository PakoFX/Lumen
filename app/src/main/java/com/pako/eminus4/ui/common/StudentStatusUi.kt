package com.pako.eminus4.ui.common

import androidx.annotation.DrawableRes
import com.pako.eminus4.R
import com.pako.eminus4.domain.StudentStatus

/** Ícono de cada estado del alumno (presentación; la regla vive en [StudentStatus]). */
@get:DrawableRes
val StudentStatus.icon: Int
    get() = when (this) {
        StudentStatus.TO_DO -> R.drawable.ic_pending_actions
        StudentStatus.DRAFT -> R.drawable.ic_edit_note
        StudentStatus.AWAITING_GRADE -> R.drawable.ic_hourglass_top
        StudentStatus.GRADED -> R.drawable.ic_star
        StudentStatus.NOT_SUBMITTED -> R.drawable.ic_block
    }
