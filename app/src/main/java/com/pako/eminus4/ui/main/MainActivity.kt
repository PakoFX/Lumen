package com.pako.eminus4.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.pako.eminus4.AppUpdater
import com.pako.eminus4.R
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.ui.auth.LoginWebViewActivity
import com.pako.eminus4.ui.courses.AllActivitiesActivity
import com.pako.eminus4.ui.courses.CoursesFragment
import com.pako.eminus4.ui.exams.ExamsActivity
import com.pako.eminus4.ui.forums.ForumsActivity
import com.pako.eminus4.ui.messages.MessagesActivity
import com.pako.eminus4.ui.notifications.NotificationsFragment
import com.pako.eminus4.ui.profile.ProfileFragment

/**
 * Contenedor principal.
 * Navegación: barra superior (☰ · título · Mensajes · avatar → Perfil) + menú lateral
 * (Cursos, Avisos · Mis actividades, Foros, Exámenes). Cursos es la pantalla de inicio.
 */
class MainActivity : AppCompatActivity() {

    /** Contadores rojos que muestra la navegación (los calcula Avisos al cargar la agenda). */
    enum class Contador { ACTIVIDADES, MENSAJES, FOROS, EXAMENES, NOVEDADES }

    private lateinit var sessionStore: SessionStore
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var topBar: View
    private lateinit var txtTituloPantalla: TextView
    private lateinit var badgeMensajes: TextView
    private lateinit var imgAvatarBarra: ImageView
    private lateinit var txtSaludoBarra: TextView
    private lateinit var layoutOfflineBanner: View
    private lateinit var txtBannerMessage: TextView
    private lateinit var btnBannerRenew: MaterialButton

    private var coursesFragment = CoursesFragment()
    private var notificationsFragment = NotificationsFragment()
    private var profileFragment = ProfileFragment()
    private var activeFragment: Fragment = coursesFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionStore = SessionStore(this)
        // Permitir entrada si hay datos guardados (modo offline)
        if (!sessionStore.hasSession()) {
            navegarALogin()
            return
        }

        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        topBar = findViewById(R.id.topBar)
        txtTituloPantalla = findViewById(R.id.txtTituloPantalla)
        badgeMensajes = findViewById(R.id.badgeMensajes)
        imgAvatarBarra = findViewById(R.id.imgAvatarBarra)
        txtSaludoBarra = findViewById(R.id.txtSaludoBarra)
        txtSaludoBarra.setOnClickListener { cambiarFragmento(profileFragment) }
        findViewById<View>(R.id.btnHorario).setOnClickListener {
            com.pako.eminus4.ui.horario.HorarioActivity.start(this)
        }
        layoutOfflineBanner = findViewById(R.id.layoutOfflineBanner)
        txtBannerMessage = findViewById(R.id.txtBannerMessage)
        btnBannerRenew = findViewById(R.id.btnBannerRenew)

        btnBannerRenew.setOnClickListener { abrirLoginParaRenovar() }

        val headerMenu: View? = navigationView.getHeaderView(0)
        val padHeaderBase = headerMenu?.paddingTop ?: 0

        // Insets: la barra de estado la cubre el banner (si está visible) o la barra superior
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val dp8 = (8 * resources.displayMetrics.density).toInt()
            layoutOfflineBanner.setPadding(
                layoutOfflineBanner.paddingLeft, systemBars.top + dp8,
                layoutOfflineBanner.paddingRight, dp8
            )
            topBar.setPadding(
                topBar.paddingLeft, if (isBannerVisible()) 0 else systemBars.top,
                topBar.paddingRight, 0
            )
            findViewById<View>(R.id.fragmentContainer).setPadding(0, 0, 0, systemBars.bottom)
            // Encabezado del menú lateral: bajo la barra de estado (no encima de la hora)
            headerMenu?.let { h ->
                h.setPadding(h.paddingLeft, padHeaderBase + systemBars.top, h.paddingRight, h.paddingBottom)
            }
            insets
        }

        // Inicializar o restaurar fragmentos en el contenedor
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, profileFragment, "profile").hide(profileFragment)
                .add(R.id.fragmentContainer, notificationsFragment, "notifications").hide(notificationsFragment)
                .add(R.id.fragmentContainer, coursesFragment, "courses")
                .commit()
        } else {
            profileFragment = supportFragmentManager.findFragmentByTag("profile") as? ProfileFragment ?: profileFragment
            notificationsFragment = supportFragmentManager.findFragmentByTag("notifications") as? NotificationsFragment ?: notificationsFragment
            coursesFragment = supportFragmentManager.findFragmentByTag("courses") as? CoursesFragment ?: coursesFragment
            activeFragment = listOf(profileFragment, notificationsFragment, coursesFragment)
                .firstOrNull { it.isAdded && !it.isHidden } ?: coursesFragment
        }
        actualizarBarra()

        configurarNavegacion()

        // Verificar actualizaciones de GitHub en segundo plano
        AppUpdater.verificarActualizaciones(this, silent = true)

        com.pako.eminus4.notificaciones.Recordatorios.crearCanales(this)
        // Primero "¿Qué hay de nuevo?" (si se acaba de actualizar) y, al cerrarla, la pregunta
        // de recordatorios: nunca dos ventanas encimadas
        if (savedInstanceState == null) {
            com.pako.eminus4.ui.novedades.QueHayDeNuevo.mostrarSiHayNovedades(this) {
                if (!isFinishing && !isDestroyed) preguntarNotificacionesPrimeraVez()
            }
        }
    }

    private val pedirPermisoNotif = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (!concedido) {
            android.widget.Toast.makeText(this,
                "Sin permiso no se mostrarán recordatorios. Puedes activarlos en Perfil → Notificaciones.",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Una sola vez: explicar los recordatorios y pedir el permiso (Android 13+). */
    private fun preguntarNotificacionesPrimeraVez() {
        val prefs = com.pako.eminus4.notificaciones.NotifPrefs(this)
        if (prefs.yaSePregunto) return
        prefs.yaSePregunto = true
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_schedule)
            .setTitle("¿Activar recordatorios?")
            .setMessage("Lumen te avisará antes de que venza una tarea o cierre un examen (24 h, 5 h y 1 h antes), aunque tu sesión caduque.\n\nPuedes cambiar qué y cuándo te avisa en Perfil → Notificaciones.")
            .setPositiveButton("Activar") { _, _ ->
                prefs.activadas = true
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) pedirPermisoNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                com.pako.eminus4.notificaciones.Recordatorios.reprogramar(this)
            }
            .setNegativeButton("Ahora no") { _, _ ->
                prefs.activadas = false
                com.pako.eminus4.notificaciones.Recordatorios.reprogramar(this)
            }
            .show()
    }

    private fun configurarNavegacion() {
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btnMensajes).setOnClickListener {
            MessagesActivity.start(this)
        }
        imgAvatarBarra.setOnClickListener { cambiarFragmento(profileFragment) }

        navigationView.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.nav_courses -> cambiarFragmento(coursesFragment)
                R.id.nav_notifications -> cambiarFragmento(notificationsFragment)
                R.id.nav_actividades -> AllActivitiesActivity.start(this)
                R.id.nav_foros -> ForumsActivity.start(this)
                R.id.nav_examenes -> ExamsActivity.start(this)
            }
            // Solo Cursos/Avisos quedan marcados; los demás abren otra pantalla
            item.itemId == R.id.nav_courses || item.itemId == R.id.nav_notifications
        }

        // Atrás: cierra el menú; desde Avisos/Perfil vuelve a Cursos; en Cursos sale de la app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawerLayout.isDrawerOpen(GravityCompat.START) -> drawerLayout.closeDrawer(GravityCompat.START)
                    activeFragment != coursesFragment -> cambiarFragmento(coursesFragment)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (sessionStore.isSessionExpired()) {
            val tieneCursos = sessionStore.getCachedCourses().isNotEmpty()
            val msg = if (tieneCursos) "Sesión caducada. Mostrando datos guardados." else "Sesión en línea caducada."
            mostrarBannerSesionExpirada(msg)
        } else {
            ocultarBannerSesion()
            coursesFragment.recargarCursos()
        }
        cargarDatosUsuario()
    }

    /** Avatar de Eminus en la barra y nombre en el encabezado del menú. */
    private fun cargarDatosUsuario() {
        val profile = sessionStore.getUserProfile()
        val avatarUrl = profile.getFullAvatarUrl()
        if (!avatarUrl.isNullOrBlank()) {
            imgAvatarBarra.load(avatarUrl) {
                crossfade(true)
                transformations(CircleCropTransformation())
                placeholder(R.drawable.avatar_vacio)
                error(R.drawable.avatar_vacio)
            }
        } else {
            imgAvatarBarra.setImageResource(R.drawable.avatar_vacio)
        }
        txtSaludoBarra.text = profile.getPrimerNombre().takeIf { it.isNotBlank() }?.let { "Hola, $it" }.orEmpty()
        actualizarBarra()
        navigationView.getHeaderView(0)?.findViewById<TextView>(R.id.txtDrawerNombre)?.text =
            profile.getNombreCompleto()
    }

    /** Pone un contador rojo en la navegación; se oculta en 0. */
    fun ponerContador(tipo: Contador, n: Int) {
        if (!::navigationView.isInitialized) return
        fun badgeDelMenu(id: Int): TextView? =
            navigationView.menu.findItem(id).actionView?.findViewById(R.id.txtDrawerBadge)
        val badge: TextView? = when (tipo) {
            Contador.MENSAJES -> badgeMensajes
            Contador.ACTIVIDADES -> badgeDelMenu(R.id.nav_actividades)
            Contador.FOROS -> badgeDelMenu(R.id.nav_foros)
            Contador.EXAMENES -> badgeDelMenu(R.id.nav_examenes)
            Contador.NOVEDADES -> badgeDelMenu(R.id.nav_notifications)
        }
        badge ?: return
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
        badge.text = if (n > 99) "99+" else n.toString()
    }

    fun isBannerVisible(): Boolean {
        return ::layoutOfflineBanner.isInitialized && layoutOfflineBanner.visibility == View.VISIBLE
    }

    fun mostrarBannerSesionExpirada(mensaje: String = "Sesión caducada. Mostrando datos guardados.") {
        runOnUiThread {
            layoutOfflineBanner.visibility = View.VISIBLE
            txtBannerMessage.text = mensaje
            ViewCompat.requestApplyInsets(findViewById(R.id.mainLayout))
        }
    }

    fun ocultarBannerSesion() {
        runOnUiThread {
            layoutOfflineBanner.visibility = View.GONE
            ViewCompat.requestApplyInsets(findViewById(R.id.mainLayout))
        }
    }

    fun abrirLoginParaRenovar() {
        val intent = Intent(this, LoginWebViewActivity::class.java).apply {
            putExtra("SESSION_EXPIRED", true)
            putExtra("RENEWAL_ONLY", true)
            val currentToken = sessionStore.getAccessToken()
            if (!currentToken.isNullOrBlank()) {
                putExtra("EXPIRED_TOKEN", currentToken)
            }
        }
        startActivity(intent)
    }

    fun notificarSesionExpirada() {
        sessionStore.setSessionExpired(true)
        mostrarBannerSesionExpirada("Sesión caducada. Mostrando datos guardados.")
    }

    private fun cambiarFragmento(target: Fragment) {
        if (target != activeFragment) {
            supportFragmentManager.beginTransaction()
                .hide(activeFragment)
                .show(target)
                .commit()
            activeFragment = target
        }
        actualizarBarra()
    }

    /** Título de la barra y opción marcada en el menú según la pantalla visible. */
    private fun actualizarBarra() {
        txtTituloPantalla.text = when (activeFragment) {
            notificationsFragment -> "Avisos"
            profileFragment -> "Perfil"
            else -> "Cursos"
        }
        if (::txtSaludoBarra.isInitialized) {
            txtSaludoBarra.visibility =
                if (activeFragment == coursesFragment && !txtSaludoBarra.text.isNullOrBlank()) View.VISIBLE else View.GONE
        }
        val menu = navigationView.menu
        menu.findItem(R.id.nav_courses).isChecked = activeFragment == coursesFragment
        menu.findItem(R.id.nav_notifications).isChecked = activeFragment == notificationsFragment
    }

    fun cerrarSesionDirecta() {
        val expiredToken = sessionStore.getAccessToken()
        // No recordar tareas de esta cuenta después de cerrar sesión
        com.pako.eminus4.notificaciones.Recordatorios.borrarTodo(this)
        // Cerrar sesión a propósito: no queda nada de la cuenta (credencial, foto, documentos…)
        sessionStore.clearAccountData()
        navegarALogin(sessionExpired = false, isLogout = true, expiredToken = expiredToken)
    }

    fun navegarALoginPorExpiracion() {
        notificarSesionExpirada()
    }

    private fun navegarALogin(sessionExpired: Boolean = false, isLogout: Boolean = false, expiredToken: String? = null) {
        val intent = Intent(this, LoginWebViewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("SESSION_EXPIRED", sessionExpired)
            putExtra("LOGOUT_ACTION", isLogout)
            if (!expiredToken.isNullOrBlank()) {
                putExtra("EXPIRED_TOKEN", expiredToken)
            }
        }
        startActivity(intent)
        finish()
    }
}
