package com.pako.eminus4.domain

import com.pako.eminus4.data.repository.CapturedSession
import com.pako.eminus4.domain.FreshLoginGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FreshLoginGateTest {

    @Test
    fun `sin esperar login fresco acepta cualquier token`() {
        assertEquals(Decision.ACCEPT, FreshLoginGate(expiredToken = "viejo").decide("viejo"))
    }

    @Test
    fun `tras cerrar sesion rechaza un token sin login y valida el caducado con el servidor`() {
        val gate = FreshLoginGate(expiredToken = "viejo").apply { requireFreshLogin() }
        assertEquals(Decision.VALIDATE_WITH_SERVER, gate.decide("viejo"))
        val noExpired = FreshLoginGate(expiredToken = null).apply { requireFreshLogin() }
        assertEquals(Decision.REJECT, noExpired.decide("cualquiera"))
    }

    @Test
    fun `se abre con token distinto, pantalla de login o almacenamiento vacio`() {
        assertEquals(Decision.ACCEPT, FreshLoginGate("viejo").apply { requireFreshLogin() }.decide("nuevo"))
        assertEquals(Decision.ACCEPT, FreshLoginGate("viejo").apply { requireFreshLogin(); onLoginPageSeen() }.decide("viejo"))
        val gate = FreshLoginGate("viejo").apply { requireFreshLogin(); onEmptyStorageSeen() }
        assertEquals(Decision.ACCEPT, gate.decide("viejo"))
        assertFalse(gate.waitingForFreshLogin)
    }

    @Test
    fun `sesion capturada del WebView`() {
        val s = CapturedSession.fromJson("""{"token":"eyJabc","userName":"Ana","userId":null}""")!!
        assertEquals("eyJabc", s.token)
        assertEquals("Ana", s.userName)
        assertNull(s.userId)
        assertNull(CapturedSession.fromJson("""{"token":"null"}"""))
        assertNull(CapturedSession.fromJson("""{"token":null}"""))
        assertNull(CapturedSession.fromJson("no es json"))
    }
}
