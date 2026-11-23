package com.pako.eminus4.domain

import com.pako.eminus4.data.model.StudentCredential
import com.pako.eminus4.data.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCardTest {

    private val profile = UserProfile(userId = "zs11111111", nombre = "Ana", paterno = "Pérez", userEmail = "a@x.mx", userCampus = "Xalapa")

    @Test
    fun `sin credencial se crea una con la matricula de la sesion`() {
        val r = ProfileCardBuilder.build(profile, saved = null)
        assertEquals("S11111111", r.card.studentId)
        assertEquals("S11111111", r.credentialToSave?.user)
        assertNull(r.credentialToSave?.photo)
    }

    @Test
    fun `credencial de otra cuenta se ignora`() {
        val other = StudentCredential(user = "S99999999", name = "Otra Persona", program = "Medicina")
        val r = ProfileCardBuilder.build(profile, other)
        assertEquals("", r.card.program)
        assertEquals("S11111111", r.credentialToSave?.user)
    }

    @Test
    fun `credencial al dia no se vuelve a guardar y conserva su programa`() {
        val mine = StudentCredential(user = "S11111111", name = "Ana Pérez", program = "Ingeniería de Software")
        val r = ProfileCardBuilder.build(profile, mine)
        assertNull(r.credentialToSave)
        assertEquals("Ingeniería de Software", r.card.program)
        assertEquals("Xalapa", r.card.campus)
    }

    @Test
    fun `vigencia vieja se corrige sin perder datos de MiUV`() {
        val old = StudentCredential(user = "S11111111", name = "Ana", program = "Software", yearValidity = "2025", facultad = "FIEE")
        val saved = ProfileCardBuilder.build(profile, old).credentialToSave!!
        assertEquals("2027", saved.yearValidity)
        assertEquals("FIEE", saved.facultad)
    }
}
