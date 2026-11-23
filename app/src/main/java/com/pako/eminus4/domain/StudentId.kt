package com.pako.eminus4.domain

/** Matrícula oficial a partir de como la manda Eminus: "zs24022198" / "zS24022198" → "S24022198". */
object StudentId {

    fun normalize(id: String?): String? {
        val upper = id?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "NULL" } ?: return null
        return if (upper.startsWith("Z") && upper.length > 1 && upper[1].isLetter()) upper.substring(1) else upper
    }
}
