package com.pako.eminus4.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nombres reales de materias de Eminus (como vienen, en mayúsculas y cortados). */
class SubjectNameTest {

    @Test
    fun `nombre completo en formato titulo sin el NRC`() {
        assertEquals("Administracion de Proyectos de", SubjectName.displayName("ADMINISTRACION DE PROYECTOS DE (11986)"))
        assertEquals("Bases de Datos", SubjectName.displayName("BASES DE DATOS (27873)"))
    }

    @Test
    fun `nombre corto abrevia y no pasa de 20 caracteres`() {
        assertEquals("Admón. Proyectos", SubjectName.shortName("ADMINISTRACION DE PROYECTOS DE (11986)"))
        assertEquals("Tec. Construcción", SubjectName.shortName("TECNOLOGIAS PARA LA CONSTRUCCI (12111)"))
        listOf(
            "FUNDAMENTOS DE PRUEBA DE SOFTW (12205)",
            "ASPECTOS DE SEGURIDAD PARA EL (12227)",
            "PRINCIPIOS DE DISEÑO DE SOFTWA (39013)"
        ).forEach { assertTrue(it, SubjectName.shortName(it).length <= 20) }
    }

    @Test
    fun `nombres cortos se quedan completos`() {
        assertEquals("Bases Datos", SubjectName.shortName("BASES DE DATOS (27873)"))
        assertEquals("Lógica", SubjectName.shortName("LÓGICA"))
    }

    @Test
    fun `vacio o nulo`() {
        assertEquals("Materia", SubjectName.shortName(null))
        assertEquals("Materia", SubjectName.shortName("   "))
        assertEquals("", SubjectName.displayName(null))
    }
}
