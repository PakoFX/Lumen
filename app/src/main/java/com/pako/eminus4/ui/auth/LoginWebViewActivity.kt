package com.pako.eminus4.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import com.pako.eminus4.util.iconoInicio
import androidx.core.content.ContextCompat
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pako.eminus4.R
import com.pako.eminus4.appContainer
import com.pako.eminus4.data.repository.CapturedSession
import com.pako.eminus4.domain.FreshLoginGate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.pako.eminus4.core.Constants
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.data.MiuvSync
import com.pako.eminus4.ui.main.MainActivity

class LoginWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var sessionStore: SessionStore
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    private var isSessionCaptured = false

    /**
     * Usuario y contraseña escritos en el login, SOLO en memoria, para entrar una vez a MiUV
     * (que tiene su propio login) y leer el programa educativo. Se borran al terminar.
     * Nunca se guardan en disco ni se escriben en el log.
     */
    private var credUsuario: String? = null
    private var credClave: CharArray? = null

    private fun descartarCredenciales() {
        credClave?.fill('\u0000')
        credClave = null
        credUsuario = null
    }

    /** Decide si un token del WebView sale de un inicio de sesión nuevo (ver [FreshLoginGate]). */
    private lateinit var gate: FreshLoginGate
    private val auth by lazy { appContainer.authRepository }

    /**
     * Si el token "caducado" reaparece sin pasar por el login, se pregunta al servidor si sigue
     * siendo válido (falsas alarmas de 401). Mientras se valida, no se repite la consulta.
     */
    private var validandoTokenViejo = false

    private var ultimaValidacionMs = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        sessionStore = SessionStore(this)
        progressBar = findViewById(R.id.loginProgressBar)
        webView = findViewById(R.id.loginWebView)

        val isSessionExpired = intent.getBooleanExtra("SESSION_EXPIRED", false)
        val isRenewalOnly = intent.getBooleanExtra("RENEWAL_ONLY", false)
        val isLogout = intent.getBooleanExtra("LOGOUT_ACTION", false)
        gate = FreshLoginGate(expiredToken = intent.getStringExtra("EXPIRED_TOKEN"))

        configurarWebView()

        // "Volver a cargar foto/horario" con la sesión de MiUV caducada: MiUV necesita usuario y contraseña,
        // pero si Eminus aún recuerda la sesión se entra sin escribirlos y MiUV se omitía. Se limpia la
        // sesión web (no los datos de la app) para que se pidan y se capturen.
        val forzarMiuv = intent.getBooleanExtra("FORZAR_MIUV", false)
        if (isSessionExpired || isLogout || forzarMiuv) {
            if (!isRenewalOnly) {
                sessionStore.clearSession(preserveCredential = true)
            }
            limpiarSesionWebCompleta {
                gate.requireFreshLogin()
                Log.i("EMINUS_LOGIN", "Sesión web limpiada (logout=$isLogout, renewal=$isRenewalOnly). Esperando login fresco.")
                webView.loadUrl(Constants.EMINUS_LOGIN_URL)
                iniciarSondeoContinuo()
            }
        } else {
            gate.allowAnyToken()
            webView.loadUrl(Constants.EMINUS_LOGIN_URL)
            iniciarSondeoContinuo()
        }
    }

    private fun limpiarSesionWebCompleta(onDone: () -> Unit) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies {
                cookieManager.flush()
            }
            android.webkit.WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
        } catch (e: Exception) {
            Log.e("EMINUS_LOGIN", "Error limpiando almacenamiento web", e)
        }
        handler.postDelayed(onDone, 200)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AuthBridge(), "AndroidAuthBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                if (esUrlDeLogin(url)) {
                    // Ya pasó por el login institucional: a partir de aquí cualquier token nuevo es válido
                    if (gate.waitingForFreshLogin) Log.d("EMINUS_LOGIN", "Pantalla de login detectada — habilitando captura de token nuevo")
                    gate.onLoginPageSeen()
                }
                inyectarInterceptorDeStorage()
                inyectarCapturaDeCredenciales()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
                inyectarInterceptorDeStorage()
                inyectarCapturaDeCredenciales()
                verificarToken()
                // Elimina el espacio en blanco inferior que deja la página de Eminus
                view?.evaluateJavascript(
                    """
                    (function() {
                        var s = document.body.style;
                        s.height = '100%';
                        s.overflow = 'hidden';
                        s.margin = '0';
                        s.padding = '0';
                        var html = document.documentElement;
                        html.style.height = '100%';
                        html.style.overflow = 'hidden';
                    })();
                    """.trimIndent(), null
                )
            }
        }
    }

    private fun esUrlDeLogin(url: String?): Boolean {
        val lower = url?.lowercase() ?: return false
        return lower.contains("default.aspx") ||
            lower.contains("login") ||
            lower.contains("signin") ||
            lower.contains("adfs") ||
            lower.contains("sts.") ||
            lower.contains("identity") ||
            lower.contains("oauth") ||
            lower.contains("sso")
    }

    inner class AuthBridge {
        @JavascriptInterface
        fun onSessionCaptured(jsonStr: String) {
            runOnUiThread {
                procesarJsonDeSesion(jsonStr)
            }
        }

        /** Recibe usuario/contraseña al enviar el formulario de login (no se loguean). */
        @JavascriptInterface
        fun onCredenciales(usuario: String, clave: String) {
            if (usuario.isBlank() || clave.isEmpty()) return
            runOnUiThread {
                if (isSessionCaptured) return@runOnUiThread
                descartarCredenciales()
                credUsuario = usuario.trim()
                credClave = clave.toCharArray()
            }
        }

        @JavascriptInterface
        fun onDebug(msg: String) {
            Log.d("EMINUS_LOGIN", "JS: $msg")
        }
    }

    /**
     * Inyecta un interceptor para que en cuanto Angular guarde accessToken en localStorage,
     * se invoque inmediatamente el puente nativo sin esperar al sondeo.
     */
    private fun inyectarInterceptorDeStorage() {
        val hookJs = """
            (function() {
                try {
                    function pickToken() {
                        var keys = ['accessToken', 'AccessToken', 'token', 'jwt', 'id_token', 'authToken'];
                        for (var i = 0; i < keys.length; i++) {
                            var v = localStorage.getItem(keys[i]);
                            if (v && v !== 'null' && v.length > 20) return v;
                        }
                        // Buscar cualquier valor tipo JWT en localStorage
                        for (var j = 0; j < localStorage.length; j++) {
                            var k = localStorage.key(j);
                            var val = localStorage.getItem(k);
                            if (val && typeof val === 'string' && val.indexOf('eyJ') === 0 && val.length > 40) {
                                return val;
                            }
                        }
                        return null;
                    }

                    function reportarSesion() {
                        var token = pickToken();
                        var url = (window.location.href || '').toLowerCase();
                        if (!token) {
                            if (window.AndroidAuthBridge && window.AndroidAuthBridge.onDebug) {
                                window.AndroidAuthBridge.onDebug('sin token | keys=' + Object.keys(localStorage).join(','));
                            }
                            return;
                        }
                        // En páginas de login institucionales aún no reportamos
                        if (url.indexOf('default.aspx') !== -1 || url.indexOf('adfs') !== -1) return;

                        var payload = JSON.stringify({
                            token: token,
                            userName: localStorage.getItem('userName') || localStorage.getItem('UserName'),
                            userId: localStorage.getItem('userId') || localStorage.getItem('userIdentity'),
                            userEmail: localStorage.getItem('userEmail'),
                            userCampus: localStorage.getItem('userCampus'),
                            avatar: localStorage.getItem('avatar'),
                            tokenExpiration: localStorage.getItem('tokenExpiration'),
                            userRoles: localStorage.getItem('userRoles')
                        });
                        if (window.AndroidAuthBridge && window.AndroidAuthBridge.onSessionCaptured) {
                            window.AndroidAuthBridge.onSessionCaptured(payload);
                        }
                    }

                    reportarSesion();

                    if (!window.__authHooked) {
                        window.__authHooked = true;
                        // Limpia la clave basura que dejaba la versión anterior del hook
                        try { if (localStorage.getItem('setItem') !== null) localStorage.removeItem('setItem'); } catch (e) {}
                        // Se envuelve Storage.prototype: asignar localStorage.setItem = f
                        // crea una CLAVE llamada "setItem" en vez de reemplazar el método
                        var origSetItem = Storage.prototype.setItem;
                        Storage.prototype.setItem = function(k, v) {
                            origSetItem.apply(this, arguments);
                            if (this === window.localStorage &&
                                (String(k).toLowerCase().indexOf('token') !== -1 || k === 'userName')) {
                                setTimeout(reportarSesion, 150);
                            }
                        };
                    }
                } catch(e) {
                    if (window.AndroidAuthBridge && window.AndroidAuthBridge.onDebug) {
                        window.AndroidAuthBridge.onDebug('hook error: ' + e);
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(hookJs, null)
    }

    /**
     * Escucha el envío del formulario de login (submit, clic en botón o Enter) y pasa
     * usuario/contraseña a la app en memoria, para reutilizarlos UNA vez en MiUV.
     */
    private fun inyectarCapturaDeCredenciales() {
        if (isSessionCaptured) return
        val js = """
            (function() {
              if (window.__credHooked) return;
              window.__credHooked = true;
              function enviar() {
                try {
                  var p = document.querySelector('input[type=password]');
                  if (!p || !p.value) return;
                  var f = p.form || document;
                  var c = Array.prototype.slice.call(
                    f.querySelectorAll('input[type=text], input[type=email], input:not([type])'))
                    .filter(function(e) { return e.value; });
                  if (!c.length) return;
                  window.AndroidAuthBridge.onCredenciales(c[0].value, p.value);
                } catch (e) {}
              }
              document.addEventListener('submit', enviar, true);
              document.addEventListener('keydown', function(e) { if (e.key === 'Enter') enviar(); }, true);
              document.addEventListener('change', function(e) {
                if (e.target && e.target.type === 'password') enviar();
              }, true);
              document.addEventListener('click', function(e) {
                var t = e.target;
                if (t && (t.closest && t.closest('button, input[type=submit], input[type=image], input[type=button]'))) enviar();
              }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun iniciarSondeoContinuo() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isSessionCaptured && !isFinishing) {
                    verificarToken()
                    handler.postDelayed(this, 1000)
                }
            }
        }, 800)
    }

    private fun verificarToken() {
        if (isSessionCaptured || isFinishing) return

        val script = """
            (function() {
                try {
                    function pickToken() {
                        var keys = ['accessToken', 'AccessToken', 'token', 'jwt', 'id_token', 'authToken'];
                        for (var i = 0; i < keys.length; i++) {
                            var v = localStorage.getItem(keys[i]);
                            if (v && v !== 'null' && v.length > 20) return v;
                        }
                        for (var j = 0; j < localStorage.length; j++) {
                            var k = localStorage.key(j);
                            var val = localStorage.getItem(k);
                            if (val && typeof val === 'string' && val.indexOf('eyJ') === 0 && val.length > 40) {
                                return val;
                            }
                        }
                        return null;
                    }
                    var token = pickToken();
                    var url = (window.location.href || '').toLowerCase();
                    if (token && url.indexOf('default.aspx') === -1 && url.indexOf('adfs') === -1) {
                        return JSON.stringify({
                            token: token,
                            userName: localStorage.getItem('userName') || localStorage.getItem('UserName'),
                            userId: localStorage.getItem('userId') || localStorage.getItem('userIdentity'),
                            userEmail: localStorage.getItem('userEmail'),
                            userCampus: localStorage.getItem('userCampus'),
                            avatar: localStorage.getItem('avatar'),
                            tokenExpiration: localStorage.getItem('tokenExpiration'),
                            userRoles: localStorage.getItem('userRoles'),
                            debugKeys: Object.keys(localStorage)
                        });
                    }
                    return JSON.stringify({
                        token: null,
                        debugKeys: Object.keys(localStorage),
                        url: url
                    });
                } catch(e) {
                    return JSON.stringify({ token: null, error: String(e) });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (isSessionCaptured || isFinishing) return@evaluateJavascript
            val json = unwrapEvaluateJavascriptResult(result) ?: return@evaluateJavascript
            try {
                val obj = JsonParser.parseString(json).asJsonObject
                val token = obj.get("token")?.takeIf { !it.isJsonNull }?.asString
                if (token.isNullOrBlank() || token == "null") {
                    gate.onEmptyStorageSeen()
                    val keys = obj.get("debugKeys")?.toString() ?: "[]"
                    Log.d("EMINUS_LOGIN", "Sondeo sin token. keys=$keys url=${obj.get("url")}")
                    return@evaluateJavascript
                }
                procesarJsonDeSesion(json)
            } catch (e: Exception) {
                Log.e("EMINUS_LOGIN", "Error parseando sondeo (${result?.length ?: 0} car.)", e)
            }
        }
    }

    /**
     * evaluateJavascript envuelve el resultado en JSON string (comillas + escapes).
     */
    private fun unwrapEvaluateJavascriptResult(result: String?): String? {
        if (result.isNullOrBlank() || result == "null") return null
        return try {
            // El resultado ya es un string JSON literal: "\"{...}\"" o "{...}"
            val element = JsonParser.parseString(result)
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
                element.isJsonObject -> element.toString()
                else -> result.trim().trim('"')
            }
        } catch (_: Exception) {
            result.trim().removePrefix("\"").removeSuffix("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }

    private fun puedeAceptarToken(token: String): Boolean {
        if (isSessionCaptured) return false
        return when (gate.decide(token)) {
            FreshLoginGate.Decision.ACCEPT -> true
            FreshLoginGate.Decision.VALIDATE_WITH_SERVER -> { validarTokenEnServidor(token); false }
            FreshLoginGate.Decision.REJECT -> {
                Log.d("EMINUS_LOGIN", "Captura bloqueada: esperando login fresco (mismo token viejo o aún no hubo login)")
                false
            }
        }
    }

    /** El mismo token "caducado" reapareció sin pasar por el login: se pregunta al servidor si sigue sirviendo. */
    private fun validarTokenEnServidor(token: String) {
        val ahora = System.currentTimeMillis()
        if (validandoTokenViejo || ahora - ultimaValidacionMs < 15_000) return
        validandoTokenViejo = true
        ultimaValidacionMs = ahora
        lifecycleScope.launch {
            val valido = auth.isTokenValid(token)
            validandoTokenViejo = false
            if (valido && !isSessionCaptured && !isFinishing) {
                Log.i("EMINUS_LOGIN", "El token marcado como caducado sigue siendo válido: se acepta")
                gate.onTokenConfirmedValid()
                verificarToken()
            } else {
                Log.d("EMINUS_LOGIN", "El token viejo no es válido: se espera un login nuevo")
            }
        }
    }

    private fun procesarJsonDeSesion(jsonStr: String) {
        if (isSessionCaptured || isFinishing) return
        val session = CapturedSession.fromJson(unwrapEvaluateJavascriptResult(jsonStr) ?: jsonStr) ?: return
        if (!puedeAceptarToken(session.token)) return

        isSessionCaptured = true
        handler.removeCallbacksAndMessages(null)
        auth.start(session)
        // Si Eminus aún no escribió nombre/usuario, completarlos desde la API (no detiene la entrada)
        appContainer.applicationScope.launch {
            auth.completeProfileIfMissing(session)
                ?.onSuccess { Log.i("EMINUS_LOGIN", "Perfil completado desde la API") }
                ?.onFailure { Log.w("EMINUS_LOGIN", "No se pudo completar el perfil: ${it.message}") }
        }
        Log.i("EMINUS_LOGIN", "Sesión detectada y guardada")

        val usuario = credUsuario
        val clave = credClave
        credUsuario = null
        credClave = null   // la propiedad de la clave pasa a MiuvSync, que la borra al terminar
        val renovacion = intent.getBooleanExtra("RENEWAL_ONLY", false)
        val forzarMiuv = intent.getBooleanExtra("FORZAR_MIUV", false)
        when {
            usuario != null && clave != null && auth.canSkipMiuv(usuario, renovacion, forzarMiuv) -> {
                // Renovar la sesión no repite la carga de MiUV si ya hay datos recientes de esta cuenta
                clave.fill('\u0000')
                Log.i("EMINUS_LOGIN", "Renovación: datos de MiUV recientes, se omite la sincronización")
                entrarALaApp()
            }
            usuario != null && clave != null -> sincronizarConMiuv(usuario, clave, session.userName)
            else -> {
                Log.i("EMINUS_LOGIN", "Sin usuario/contraseña capturados (sesión recordada): se omite MiUV")
                entrarALaApp()
            }
        }
    }

    /** Pantalla "Preparando tu cuenta": paso actual con reloj, anteriores con palomita. */
    private fun pintarPasos(actual: Int) {
        listOf(R.id.pasoSync0, R.id.pasoSync1, R.id.pasoSync2, R.id.pasoSync3).forEachIndexed { i, id ->
            val t = findViewById<TextView>(id) ?: return@forEachIndexed
            val (icono, color) = when {
                i < actual -> R.drawable.ic_check_circle to R.color.badge_success_text
                i == actual -> R.drawable.ic_hourglass_top to R.color.brand_uv_green
                else -> R.drawable.ic_schedule to R.color.text_tertiary
            }
            t.setTextColor(ContextCompat.getColor(this, if (i <= actual) R.color.text_primary else R.color.text_tertiary))
            t.setTypeface(null, if (i == actual) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            t.iconoInicio(icono, ContextCompat.getColor(this, color), tamanoDp = 20, separacionDp = 12)
        }
    }

    /** Entra una vez a MiUV con los mismos datos, lee el programa educativo y sigue. */
    private fun sincronizarConMiuv(usuario: String, clave: CharArray, userName: String?) {
        // El usuario del login es la matrícula: sirve de respaldo si Eminus aún no reportó userId
        val matriculaLogin = usuario
        sessionStore.discardForeignCredential(matriculaLogin)
        findViewById<View>(R.id.layoutSyncMiuv).visibility = View.VISIBLE
        pintarPasos(0)
        val sync = MiuvSync(webView, java.io.File(filesDir, "credencial"), usuario, clave)
        sync.alAvanzar = { paso -> if (!isFinishing) pintarPasos(paso) }
        sync.iniciar { datos ->
            if (isFinishing || isDestroyed) return@iniciar
            if (datos != null) {
                sessionStore.applyMiuvData(datos, userName, matriculaLogin)
                // La hora de término de cada clase viene en el PDF de "Imprimir" de MiUV (en segundo plano)
                if (!datos.horario.isNullOrEmpty()) appContainer.scheduleRepository.completeEndTimesInBackground()
                Toast.makeText(this, "Credencial actualizada: ${datos.programa}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No se pudo leer MiUV; puedes editar tu programa en Perfil.", Toast.LENGTH_LONG).show()
            }
            entrarALaApp()
        }
    }

    private fun entrarALaApp() {
        if (intent.getBooleanExtra("RENEWAL_ONLY", false)) {
            finish()
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        descartarCredenciales()
        super.onDestroy()
    }
}
