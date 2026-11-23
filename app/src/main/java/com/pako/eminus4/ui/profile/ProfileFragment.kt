package com.pako.eminus4.ui.profile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pako.eminus4.AppUpdater
import androidx.lifecycle.lifecycleScope
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import kotlinx.coroutines.launch
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.domain.ProfileCard
import com.pako.eminus4.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileFragment : Fragment() {

    private val sessionStore: SessionStore get() = requireContext().appContainer.sessionStore
    private val viewModel: ProfileViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = requireContext().appContainer
                ProfileViewModel(container.profileRepository, container.credentialRepository)
            }
        }
    }
    private var textoBotonFoto: CharSequence? = null

    private lateinit var imgAvatar: ImageView
    private lateinit var txtStudentName: TextView
    private lateinit var txtStudentId: TextView
    private lateinit var txtStudentProgram: TextView
    private lateinit var txtStudentCampus: TextView
    private lateinit var txtStudentValidity: TextView
    private lateinit var clockProfileLive: TextView
    private lateinit var cardStudentCredential: View
    private lateinit var btnOpenCredential: MaterialButton
    private lateinit var txtEmailUv: TextView
    private lateinit var txtEmailAlternate: TextView
    private lateinit var layoutAlternateEmail: View
    private lateinit var txtSessionStatus: TextView
    private lateinit var btnCheckUpdates: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var layoutDarkModeToggle: View
    private lateinit var switchDarkMode: SwitchMaterial
    private lateinit var txtDarkModeStatus: TextView

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("dd/MMM/yyyy HH:mm:ss", Locale("es", "MX"))
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (::clockProfileLive.isInitialized && isAdded) {
                clockProfileLive.text = clockFormat.format(Date()).lowercase(Locale("es", "MX"))
            }
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inicializarVistas(view)
        iniciarRelojPerfil()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun inicializarVistas(view: View) {
        // La barra de estado la cubre la barra superior de MainActivity

        imgAvatar = view.findViewById(R.id.imgAvatar)
        txtStudentName = view.findViewById(R.id.txtStudentName)
        txtStudentId = view.findViewById(R.id.txtStudentId)
        txtStudentProgram = view.findViewById(R.id.txtStudentProgram)
        txtStudentCampus = view.findViewById(R.id.txtStudentCampus)
        txtStudentValidity = view.findViewById(R.id.txtStudentValidity)
        clockProfileLive = view.findViewById(R.id.clockProfileLive)
        cardStudentCredential = view.findViewById(R.id.cardStudentCredential)
        btnOpenCredential = view.findViewById(R.id.btnOpenCredential)
        view.findViewById<MaterialButton>(R.id.btnRecargarFoto).setOnClickListener { viewModel.reloadPhoto() }
        txtEmailUv = view.findViewById(R.id.txtEmailUv)

        // La credencial se abre SOLO con su botón (tocar la tarjeta hacía lo mismo: redundante)
        btnOpenCredential.setOnClickListener {
            CredentialFullscreenActivity.start(requireContext())
        }

        // Mantener presionada la tarjeta: editar los datos de la credencial
        cardStudentCredential.setOnLongClickListener {
            mostrarDialogoEditarCredencial()
            true
        }
        txtEmailAlternate = view.findViewById(R.id.txtEmailAlternate)
        layoutAlternateEmail = view.findViewById(R.id.layoutAlternateEmail)
        txtSessionStatus = view.findViewById(R.id.txtSessionStatus)
        try {
            (requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .registerDefaultNetworkCallback(escuchaRed)
        } catch (_: Exception) { }
        btnCheckUpdates = view.findViewById(R.id.btnCheckUpdates)
        btnLogout = view.findViewById(R.id.btnLogout)
        layoutDarkModeToggle = view.findViewById(R.id.layoutDarkModeToggle)
        switchDarkMode = view.findViewById(R.id.switchDarkMode)
        txtDarkModeStatus = view.findViewById(R.id.txtDarkModeStatus)

        val isDark = sessionStore.isDarkModeEnabled()
        switchDarkMode.isChecked = isDark
        txtDarkModeStatus.text = if (isDark) "Activado" else "Desactivado"

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (sessionStore.isDarkModeEnabled() != isChecked) {
                sessionStore.setDarkModeEnabled(isChecked)
                txtDarkModeStatus.text = if (isChecked) "Activado" else "Desactivado"
                AppCompatDelegate.setDefaultNightMode(
                    if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }

        layoutDarkModeToggle.setOnClickListener {
            switchDarkMode.toggle()
        }

        btnCheckUpdates.setOnClickListener {
            AppUpdater.verificarActualizaciones(requireActivity(), silent = false)
        }
        view.findViewById<View>(R.id.btnNotificaciones).setOnClickListener {
            com.pako.eminus4.notificaciones.NotificacionesActivity.start(requireContext())
        }
        view.findViewById<View>(R.id.btnSugerencias).setOnClickListener {
            SugerenciasActivity.start(requireContext())
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQueHayDeNuevo).apply {
            text = "Qué hay de nuevo · v${com.pako.eminus4.ui.novedades.QueHayDeNuevo.versionActual(requireContext())}"
            setOnClickListener { com.pako.eminus4.ui.novedades.QueHayDeNuevo.mostrarTodo(requireContext()) }
        }

        btnLogout.setOnClickListener {
            mostrarDialogoCerrarSesion()
        }
        view.findViewById<View>(R.id.btnPrivacidad).setOnClickListener { AvisoPrivacidad.mostrar(requireContext()) }
    }

    private fun iniciarRelojPerfil() {
        clockProfileLive.text = clockFormat.format(Date()).lowercase(Locale("es", "MX"))
        clockHandler.post(clockRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clockHandler.removeCallbacks(clockRunnable)
        try {
            (requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .unregisterNetworkCallback(escuchaRed)
        } catch (_: Exception) { }
    }

    /**
     * Recargar al volver a la pestaña o a la app (p. ej. tras renovar la sesión o sincronizar
     * con MiUV): la vista se creó antes y no se entera sola de los datos nuevos.
     */
    override fun onResume() {
        super.onResume()
        if (!isHidden) viewModel.refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.refresh()
    }

    private fun render(state: ProfileUiState) {
        val boton = view?.findViewById<MaterialButton>(R.id.btnRecargarFoto)
        if (boton != null) {
            if (textoBotonFoto == null) textoBotonFoto = boton.text
            boton.isEnabled = !state.reloadingPhoto
            boton.text = if (state.reloadingPhoto) "Cargando tu foto de MiUV…" else textoBotonFoto
        }
        state.card?.let(::mostrarPerfil)
    }

    private fun mostrarPerfil(card: ProfileCard) {
        txtStudentName.text = card.name
        txtStudentId.text = card.studentId
        txtStudentProgram.text = card.program.ifBlank { "Sin programa asignado" }
        txtStudentCampus.text = card.campus.ifBlank { "Sin campus asignado" }
        txtStudentValidity.text = card.validity
        txtEmailUv.text = card.email.ifBlank { "No disponible" }
        layoutAlternateEmail.visibility = if (card.alternateEmail != null) View.VISIBLE else View.GONE
        txtEmailAlternate.text = card.alternateEmail.orEmpty()

        // Tarjeta del Perfil: el avatar que la persona eligió en Eminus.
        // La foto oficial de MiUV es SOLO para la credencial (CredentialFullscreenActivity).
        if (card.avatarUrl != null) {
            imgAvatar.load(card.avatarUrl) {
                crossfade(true)
                transformations(coil.transform.CircleCropTransformation())
                placeholder(R.drawable.avatar_vacio)
                error(R.drawable.avatar_vacio)
            }
        } else {
            imgAvatar.setImageResource(R.drawable.avatar_vacio)
        }
        pintarEstadoSesion()
    }

    /** Solo "Activa" o "Fuera de línea" (sin internet). */
    private fun pintarEstadoSesion() {
        if (!::txtSessionStatus.isInitialized || !isAdded) return
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val red = cm.getNetworkCapabilities(cm.activeNetwork)
        val enLinea = red?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        txtSessionStatus.text = if (enLinea) "Activa" else "Fuera de línea"
        txtSessionStatus.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),
            if (enLinea) R.color.badge_success_text else R.color.text_secondary))
    }

    /** Se actualiza solo si se conecta o desconecta mientras se ve el Perfil. */
    private val escuchaRed = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { view?.post { pintarEstadoSesion() } }
        override fun onLost(network: android.net.Network) { view?.post { pintarEstadoSesion() } }
    }

    /** Resultado de volver a traer la foto oficial de MiUV; si la sesión de MiUV caducó, ofrece entrar otra vez. */
    private fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.PhotoUpdated -> Toast.makeText(requireContext(), "Foto actualizada", Toast.LENGTH_SHORT).show()
            ProfileEvent.NoPhoto ->
                Toast.makeText(requireContext(), "MiUV no tiene una foto registrada para tu credencial", Toast.LENGTH_LONG).show()
            ProfileEvent.NeedsLogin -> AlertDialog.Builder(requireContext())
                .setTitle("Entrar otra vez a MiUV")
                .setMessage("Para traer tu foto hay que iniciar sesión de nuevo en MiUV (con tu mismo usuario y contraseña). Solo tarda unos segundos.")
                .setPositiveButton("Iniciar sesión") { _, _ ->
                    startActivity(android.content.Intent(requireContext(), com.pako.eminus4.ui.auth.LoginWebViewActivity::class.java).apply {
                        putExtra("RENEWAL_ONLY", true)
                        putExtra("FORZAR_MIUV", true)
                    })
                }
                .setNegativeButton("Ahora no", null)
                .show()
        }
    }

    private fun mostrarDialogoCerrarSesion() {
        AlertDialog.Builder(requireContext())
            .setTitle("Cerrar Sesión")
            .setMessage("Se borrará de la app todo lo de tu cuenta: tu credencial y foto, tu horario, recordatorios y las copias de archivos que abriste o subiste desde la app.\n\nLo que guardaste en Descargas no se toca. Para volver a usar la app tendrás que iniciar sesión de nuevo.")
            .setPositiveButton("Cerrar sesión") { _, _ ->
                (activity as? MainActivity)?.cerrarSesionDirecta()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoEditarCredencial() {
        val cred = sessionStore.getStudentCredential() ?: return
        val context = requireContext()

        val scrollView = android.widget.ScrollView(context)
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scrollView.addView(layout)

        fun createField(hintText: String, initialValue: String): Pair<com.google.android.material.textfield.TextInputLayout, com.google.android.material.textfield.TextInputEditText> {
            val editText = com.google.android.material.textfield.TextInputEditText(context).apply {
                setText(initialValue)
            }
            val textLayout = com.google.android.material.textfield.TextInputLayout(context).apply {
                hint = hintText
                setPadding(0, 8, 0, 8)
                addView(editText)
            }
            return Pair(textLayout, editText)
        }

        val (layoutName, inputName) = createField("Nombre Completo", cred.getNombreFormateado())
        val (layoutId, inputId) = createField("Matrícula", cred.getMatriculaOficial())
        val (layoutProgram, inputProgram) = createField("Programa Educativo", cred.getProgramaOficial())
        val (layoutRegion, inputRegion) = createField("Campus / Región", cred.getCampusOficial())
        val (layoutValidity, inputValidity) = createField("Vigencia (ej. AGO-ENE 2027)", cred.getVigenciaTexto())

        layout.addView(layoutName)
        layout.addView(layoutId)
        layout.addView(layoutProgram)
        layout.addView(layoutRegion)
        layout.addView(layoutValidity)

        AlertDialog.Builder(context)
            .setTitle("Datos de la Credencial Oficial")
            .setMessage("Personaliza los datos que se muestran en tu credencial digital para que coincidan con los de la UV:")
            .setView(scrollView)
            .setPositiveButton("Guardar") { _, _ ->
                val nuevoNombre = inputName.text.toString().trim().ifBlank { cred.getNombreCompleto() }
                val nuevaMatricula = inputId.text.toString().trim().ifBlank { cred.user }
                val nuevoPrograma = inputProgram.text.toString().trim().ifBlank { cred.program }
                val nuevaRegion = inputRegion.text.toString().trim().ifBlank { cred.region }
                val nuevaVigencia = inputValidity.text.toString().trim().ifBlank { cred.getVigenciaTexto() }

                val partesVigencia = nuevaVigencia.split(" ", limit = 2)
                val months = if (partesVigencia.isNotEmpty()) partesVigencia[0] else cred.monthsValidity
                val year = if (partesVigencia.size > 1) partesVigencia[1] else cred.yearValidity

                val updated = cred.copy(
                    name = nuevoNombre,
                    lastNames = "",
                    user = nuevaMatricula,
                    program = nuevoPrograma,
                    region = nuevaRegion,
                    monthsValidity = months,
                    yearValidity = year,
                    // Editar a mano no debe borrar el QR oficial de MiUV
                    qrCode = cred.qrCode,
                    barCode = nuevaMatricula
                )
                sessionStore.saveStudentCredential(updated)
                viewModel.redraw()
                Toast.makeText(context, "Credencial guardada y actualizada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
