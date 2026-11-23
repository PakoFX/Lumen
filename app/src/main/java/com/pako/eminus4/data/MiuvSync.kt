package com.pako.eminus4.data

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lee los datos académicos oficiales del alumno desde MiUV (portal ASP.NET con login propio,
 * distinto de Eminus) usando el MISMO usuario y contraseña que el alumno escribió en el login.
 *
 * SEGURIDAD:
 *  - La contraseña solo vive en memoria (CharArray) durante esta sincronización.
 *  - Se sobrescribe con ceros al terminar (éxito, error o tiempo agotado). Nunca se guarda ni se loguea.
 *  - El usuario/contraseña se inyectan en el formulario de MiUV como lo haría la persona a mano.
 *
 * Flujo: login.aspx (SIN ReturnUrl: con él MiUV redirige a uv.mx) → llenar y enviar →
 * escritorio → enlace al portal de estudiantes → szigral.aspx → extraer datos →
 * szicred.aspx (credencial digital de MiUV) → foto en HD →
 * Escolaridad (smustiles.aspx, el enlace lleva parámetros) → Horario de clases (szihorc.aspx).
 *
 * La foto de szigral.aspx es una miniatura de 60×80 px; la credencial digital trae la misma
 * foto incrustada en base64 (~480×640). Si la credencial falla, se usa la miniatura.
 */
class MiuvSync(
    private val webView: WebView,
    private val dirFotos: File,
    usuario: String,
    clave: CharArray
) {
    /** Matrícula (usuario del login) para nombrar la foto: nunca se comparte archivo entre personas. */
    private val idFoto: String = usuario.trim().uppercase().replace(Regex("[^A-Z0-9]"), "_")

    data class DatosMiuv(
        val programa: String?,
        val campus: String?,
        val facultad: String?,
        val area: String?,
        val nivel: String?,
        /** Ruta local (file://) de la fotografía oficial, si se pudo descargar. */
        val fotoLocal: String?,
        /** Contenido exacto del QR de la credencial digital de MiUV (el que verifica la UV). */
        val qrOficial: String? = null,
        /** Horario de clases (szihorc.aspx). null = no se pudo leer (se conserva el guardado). */
        val horario: List<com.pako.eminus4.data.model.ClaseHorario>? = null
    )

    companion object {
        const val TAG = "MIUV_SYNC"
        const val URL_LOGIN = "https://dsia.uv.mx/miuv/escritorio/login.aspx"
        const val URL_DATOS = "https://dsiapes.uv.mx/MIUV/portales/estudiantes/szigral.aspx"
        const val URL_CREDENCIAL = "https://dsiapes.uv.mx/MIUV/portales/estudiantes/szicred.aspx?params=E"
        const val URL_ESCOLARIDAD = "https://dsiapes.uv.mx/MIUV/Escritorio/smustiles.aspx"
        private const val TIMEOUT_MS = 55_000L
        /** Tiempo extra máximo para el horario (después de datos y foto). */
        private const val TIMEOUT_HORARIO_MS = 14_000L
    }

    /** Paso actual para la pantalla de carga: 0 entrar, 1 datos, 2 credencial, 3 horario. */
    var alAvanzar: ((Int) -> Unit)? = null
    private fun avanzar(paso: Int) { handler.post { alAvanzar?.invoke(paso) } }

    private var usuario: String? = usuario
    private var clave: CharArray? = clave
    private val handler = Handler(Looper.getMainLooper())
    private var terminado = false
    private var intentosLogin = 0
    private var irADatosTrasLogin = false
    private var yaPediDatos = false
    private lateinit var alTerminar: (DatosMiuv?) -> Unit

    /** Datos ya leídos de szigral.aspx mientras se busca la foto HD en la credencial. */
    private var datosLeidos: DatosMiuv? = null
    /** URL de la miniatura de szigral.aspx, como respaldo si la credencial no da la foto. */
    private var urlMiniatura: String? = null
    private var buscandoFoto = false
    @Volatile private var qrOficial: String? = null

    /** Borra la contraseña (y el usuario) de memoria. Idempotente. */
    private fun descartarCredenciales() {
        clave?.fill('\u0000')
        clave = null
        usuario = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun iniciar(alTerminar: (DatosMiuv?) -> Unit) {
        this.alTerminar = alTerminar
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                Log.d(TAG, "Cargando ${url?.substringBefore('?')}")
            }

            override fun onReceivedError(
                view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "Error de red ${error?.errorCode} en ${request.url?.toString()?.substringBefore('?')}")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (terminado) return
                CookieManager.getInstance().flush()
                val u = url?.lowercase() ?: return
                if (pasoHorario > 0) { paginaHorario(u); return }
                // Ya con los datos: solo falta la foto. Cualquier página que no sea la credencial
                // (p. ej. MiUV pide login otra vez) → quedarse con la miniatura.
                if (datosLeidos != null) {
                    if (u.contains("szicred.aspx")) extraerFotoHD() else usarMiniatura("credencial no disponible")
                    return
                }
                // Escritorio de MiUV: guardar (solo en memoria) el enlace a "Escolaridad", que lleva parámetros
                if (u.contains("smutiles.aspx")) recordarEscolaridad()
                when {
                    u.contains("login.aspx") -> llenarLogin()
                    u.contains("szigral.aspx") -> { avanzar(1); extraerDatos() }
                    // MiUV manda a la portada de la UV cuando rechaza la navegación
                    !u.contains("dsia") -> terminar(null, "MiUV redirigió fuera del portal (${u.substringBefore('?')})")
                    irADatosTrasLogin -> {
                        irADatosTrasLogin = false
                        irAlPortalDeEstudiantes()
                    }
                    !yaPediDatos -> irAlPortalDeEstudiantes()
                }
            }
        }
        handler.postDelayed({
            // Si los datos ya se leyeron, no perderlos por la foto: terminar sin foto nueva
            val datos = datosConFoto ?: datosLeidos
            if (datos != null) terminar(datos, null) else terminar(null, "tiempo agotado")
        }, TIMEOUT_MS)
        webView.loadUrl(URL_LOGIN)
    }

    private fun llenarLogin() {
        val u = usuario
        val c = clave
        if (u == null || c == null || intentosLogin >= 1) {
            // Ya se intentó y MiUV volvió a pedir login: credenciales rechazadas o captcha
            terminar(null, "MiUV rechazó el inicio de sesión")
            return
        }
        intentosLogin++
        irADatosTrasLogin = true
        // Se pasan como JSON para escapar correctamente cualquier carácter
        val usuarioJs = com.google.gson.Gson().toJson(u)
        val claveJs = com.google.gson.Gson().toJson(String(c))
        val js = """
            (function() {
              try {
                var pass = document.querySelector('input[type=password]');
                if (!pass) return 'sin_campo_clave';
                var form = pass.form || document;
                var candidatos = Array.prototype.slice.call(
                  form.querySelectorAll('input[type=text], input[type=email], input:not([type])'));
                var user = candidatos.filter(function(e){ return e.offsetParent !== null; })[0] || candidatos[0];
                if (!user) return 'sin_campo_usuario';
                function poner(el, v) {
                  el.focus(); el.value = v;
                  el.dispatchEvent(new Event('input', {bubbles:true}));
                  el.dispatchEvent(new Event('change', {bubbles:true}));
                }
                poner(user, $usuarioJs);
                poner(pass, $claveJs);
                var boton = form.querySelector('input[type=submit], button[type=submit], input[type=image], button:not([type])')
                         || document.querySelector('input[type=submit], button');
                if (boton) { boton.click(); return 'enviado_boton'; }
                if (pass.form) { pass.form.submit(); return 'enviado_form'; }
                return 'sin_boton';
              } catch (e) { return 'error:' + e; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r -> Log.d(TAG, "Login MiUV: $r") }
        // El script ya tiene la clave: borrarla de inmediato de este objeto
        descartarCredenciales()
    }

    /**
     * Ya dentro de MiUV: seguir el enlace del escritorio al portal de estudiantes
     * (puede llevar un token de paso entre dsia y dsiapes). Si no hay, ir directo a szigral.
     */
    private fun irAlPortalDeEstudiantes() {
        if (yaPediDatos) return
        yaPediDatos = true
        val js = """
            (function() {
              var a = document.querySelector('a[href*="szigral"]')
                   || document.querySelector('a[href*="portales/estudiantes"]')
                   || document.querySelector('a[href*="Estudiante"], a[href*="estudiante"]');
              return a ? a.href : '';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r ->
            val href = try { JsonParser.parseString(r).asString } catch (_: Exception) { "" }
            Log.d(TAG, "Enlace al portal: ${if (href.isBlank()) "no encontrado, voy directo" else href.substringBefore('?')}")
            webView.loadUrl(href.ifBlank { URL_DATOS })
        }
    }

    private fun recordarEscolaridad() {
        webView.evaluateJavascript(
            "(function(){var a=Array.prototype.slice.call(document.querySelectorAll('a')).filter(function(x){return /escolaridad/i.test(x.innerText||'')})[0];return a?a.href:'';})();"
        ) { r ->
            val h = try { JsonParser.parseString(r).asString } catch (_: Exception) { "" }
            if (h.startsWith("http")) urlEscolaridad = h
        }
    }

    private fun extraerDatos() {
        val js = """
            (function() {
              // Solo los campos académicos que usa la app: los demás (CURP, datos bancarios,
              // datos personales…) ni siquiera salen de la página hacia la app.
              var permitidos = ['programa', 'campus', 'facultad', 'área', 'area', 'nivel'];
              var pares = {};
              var ps = document.querySelectorAll('#div-iAcademica p, #div-iPersonal p, .bloques p');
              for (var i = 0; i < ps.length; i++) {
                var t = (ps[i].innerText || '').replace(/\s+/g, ' ').trim();
                var k = t.indexOf(':');
                if (k <= 0) continue;
                var clave = t.substring(0, k).trim().toLowerCase();
                var sirve = permitidos.some(function (p) { return clave.indexOf(p) === 0; });
                if (sirve) pares[clave] = t.substring(k + 1).trim();
              }
              var prog = document.getElementById('content_wucDatosGralAlum1_lblProg');
              if (prog) pares['programa'] = (prog.innerText || '').trim();
              var imgs = [];
              var im = document.querySelectorAll('#div-iPersonal img, img[id*=oto], img[src*=oto]');
              for (var j = 0; j < im.length; j++) if (im[j].src) imgs.push(im[j].src);
              return JSON.stringify({ pares: pares, imgs: imgs });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { resultado ->
            try {
                val texto = JsonParser.parseString(resultado).asString
                val obj = JsonParser.parseString(texto).asJsonObject
                val pares = obj.getAsJsonObject("pares").entrySet().associate { it.key to it.value.asString }
                val imgs = obj.getAsJsonArray("imgs").map { it.asString }
                // Solo nombres de campos al log: nunca valores personales
                Log.i(TAG, "Campos leídos: ${pares.keys} · imágenes candidatas: ${imgs.size}")

                fun campo(vararg nombres: String) = nombres.firstNotNullOfOrNull { n ->
                    pares.entries.firstOrNull { it.key.startsWith(n) }?.value?.takeIf { it.isNotBlank() }
                }

                val base = DatosMiuv(
                    programa = campo("programa")?.let(::capitalizar),
                    campus = campo("campus")?.let(::capitalizar),
                    facultad = campo("facultad")?.let(::capitalizar),
                    area = campo("área", "area")?.let(::capitalizar),
                    nivel = campo("nivel")?.let(::capitalizar),
                    fotoLocal = null
                )
                if (base.programa == null) {
                    terminar(null, "szigral.aspx sin programa")
                    return@evaluateJavascript
                }
                // Ir por la foto HD a la credencial digital; la miniatura queda de respaldo
                datosLeidos = base
                urlMiniatura = imgs.firstOrNull()
                avanzar(2)
                webView.loadUrl(URL_CREDENCIAL)
            } catch (e: Exception) {
                terminar(null, "error leyendo datos: ${e.message}")
            }
        }
    }

    /**
     * Lee de la credencial digital de MiUV la foto (data URI base64) y el código QR oficial.
     * El QR se obtiene decodificando con ZXing las imágenes/canvas cuadrados de la página,
     * así la app muestra exactamente el mismo contenido que verifica la UV.
     */
    private fun extraerFotoHD() {
        if (buscandoFoto) return
        buscandoFoto = true
        val js = """
            (function() {
              var img = document.getElementById('content_imgFoto2')
                     || document.querySelector('img[id*=imgFoto], img[alt*=Foto]');
              var qrs = [];
              var todas = document.querySelectorAll('img');
              for (var i = 0; i < todas.length; i++) {
                var e = todas[i];
                if (e === img || !e.src) continue;
                var w = e.naturalWidth, h = e.naturalHeight;
                if (w >= 60 && h >= 60 && Math.abs(w - h) <= w * 0.15) qrs.push(e.src);
              }
              var cs = document.querySelectorAll('canvas');
              for (var j = 0; j < cs.length; j++) {
                try { if (cs[j].width >= 60) qrs.push(cs[j].toDataURL('image/png')); } catch (x) {}
              }
              return JSON.stringify({ foto: img && img.src ? img.src : '', qrs: qrs });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r ->
            val (src, qrs) = try {
                val obj = JsonParser.parseString(JsonParser.parseString(r).asString).asJsonObject
                obj.get("foto").asString to obj.getAsJsonArray("qrs").map { it.asString }
            } catch (_: Exception) { "" to emptyList() }
            Log.i(TAG, "Credencial: foto=${src.isNotBlank()} · candidatos a QR: ${qrs.size}")
            val cookies = CookieManager.getInstance().getCookie(URL_CREDENCIAL)
            Thread {
                qrOficial = qrs.firstNotNullOfOrNull { leerQr(it, cookies) }
                if (qrOficial == null) Log.w(TAG, "No se encontró el QR oficial en la credencial")
                handler.post {
                    when {
                        src.startsWith("data:image") && src.contains("base64,") -> guardarFotoBase64(src)
                        src.startsWith("http") -> descargarFoto(src) { foto -> terminarConFoto(foto) }
                        else -> { buscandoFoto = false; usarMiniatura("credencial sin foto") }
                    }
                }
            }.start()
        }
    }

    /** Decodifica un QR desde un data URI o una URL (con la sesión de MiUV). Hilo de fondo. */
    private fun leerQr(fuente: String, cookies: String?): String? = try {
        val bytes = when {
            fuente.startsWith("data:image") && fuente.contains("base64,") ->
                android.util.Base64.decode(fuente.substringAfter("base64,"), android.util.Base64.DEFAULT)
            fuente.startsWith("http") -> OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(fuente).apply {
                    if (!cookies.isNullOrBlank()) header("Cookie", cookies)
                }.build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            else -> null
        }
        val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        bmp?.let { b ->
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val fuenteLum = com.google.zxing.RGBLuminanceSource(b.width, b.height, px)
            val texto = com.google.zxing.qrcode.QRCodeReader().decode(
                com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(fuenteLum)),
                mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)
            ).text
            // Solo el destino al log (sin parámetros: pueden llevar datos personales)
            Log.i(TAG, "QR oficial leído → ${texto.substringBefore('?').take(80)}")
            texto
        }
    } catch (e: com.google.zxing.NotFoundException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "Candidato a QR ilegible: ${e.javaClass.simpleName}")
        null
    }

    private fun guardarFotoBase64(dataUri: String) {
        Thread {
            val ruta = try {
                val bytes = android.util.Base64.decode(dataUri.substringAfter("base64,"), android.util.Base64.DEFAULT)
                // Validar que sea una imagen real y registrar SOLO su tamaño
                val op = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
                if (op.outWidth <= 0 || op.outHeight <= 0) {
                    Log.w(TAG, "La foto de la credencial no es una imagen válida")
                    null
                } else {
                    Log.i(TAG, "Foto HD de la credencial: ${op.outWidth}×${op.outHeight}")
                    guardarFoto(bytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo decodificar la foto HD: ${e.message}")
                null
            }
            handler.post {
                if (ruta != null) terminarConFoto(ruta)
                else { buscandoFoto = false; usarMiniatura("foto HD inválida") }
            }
        }.start()
    }

    /** Respaldo: la miniatura de szigral.aspx (como antes de la foto HD). */
    private fun usarMiniatura(motivo: String) {
        if (buscandoFoto || terminado) return
        buscandoFoto = true
        Log.w(TAG, "Sin foto HD ($motivo), uso la miniatura")
        descargarFoto(urlMiniatura) { foto -> terminarConFoto(foto) }
    }

    private fun terminarConFoto(foto: String?) {
        val datos = datosLeidos ?: return terminar(null, "sin datos")
        datosConFoto = datos.copy(fotoLocal = foto, qrOficial = qrOficial)
        // Último paso: el horario (Escolaridad → Horario de clases). Si tarda o falla, se entra igual.
        pasoHorario = 1
        avanzar(3)
        handler.postDelayed({ terminar(datosConFoto, null) }, TIMEOUT_HORARIO_MS)
        webView.loadUrl(urlEscolaridad.ifBlank { URL_ESCOLARIDAD })
    }

    // ===================== HORARIO DE CLASES =====================

    private var datosConFoto: DatosMiuv? = null
    /** 0 = no iniciado, 1 = menú Escolaridad, 2 = página del horario. */
    private var pasoHorario = 0
    private var urlEscolaridad = ""
    private var intentosMenu = 0

    private fun paginaHorario(u: String) {
        when {
            pasoHorario == 2 && u.contains("szihorc.aspx") -> leerHorario()
            pasoHorario == 1 && u.contains("smustiles.aspx") -> buscarEnlaceHorario()
            else -> {
                Log.w(TAG, "Horario: página inesperada ${u.substringBefore('?')}")
                terminar(datosConFoto, null)
            }
        }
    }

    /** El menú de Escolaridad dibuja sus opciones unos segundos después de cargar: se reintenta. */
    private fun buscarEnlaceHorario() {
        webView.evaluateJavascript(
            "(function(){var a=Array.prototype.slice.call(document.querySelectorAll('a[href]')).filter(function(x){return /szihorc/i.test(x.getAttribute('href'))})[0];return a?a.href:'';})();"
        ) { r ->
            if (terminado) return@evaluateJavascript
            val h = try { JsonParser.parseString(r).asString } catch (_: Exception) { "" }
            when {
                h.startsWith("http") -> { pasoHorario = 2; webView.loadUrl(h) }
                ++intentosMenu < 16 -> handler.postDelayed({ if (!terminado) buscarEnlaceHorario() }, 500)
                else -> { Log.w(TAG, "Horario: no apareció la opción en Escolaridad"); terminar(datosConFoto, null) }
            }
        }
    }

    private fun leerHorario() {
        val js = """
            (function(){
              function tx(e){ return e ? (e.textContent||'').replace(/\s+/g,' ').trim() : ''; }
              function porId(id){ return tx(document.getElementById(id)); }
              var p = 'content_ctl00_dlHorario_', out = [];
              for (var i = 0; i < 80; i++) {
                if (!document.getElementById(p + 'divEncabezadoTile_' + i)) break;
                var c = { materia: porId(p+'lblCampoExp_'+i), nrc: porId(p+'lblCampoNRC_'+i),
                          creditos: '', modalidad: porId(p+'lblModalidad_'+i), sesiones: [] };
                var cr = [porId(p+'lblCampoCreditos_'+i), porId(p+'lblCreditos_'+i)].filter(function(v){ return /\d/.test(v); });
                c.creditos = cr.length ? cr[0] : '';
                var bloque = document.getElementById(p + 'dlHorariosDias_' + i);
                if (bloque) {
                  var dia = null;
                  bloque.querySelectorAll('span[id]').forEach(function(sp){
                    var id = sp.id, v = tx(sp);
                    var m = id.match(/_lblCampo(Lunes|Martes|Miercoles|Jueves|Viernes|Sabado|Domingo)_/);
                    if (m) { dia = { dia: m[1], hora: v, salon: '', fin: '' }; c.sesiones.push(dia); return; }
                    // Lo que sigue a la hora (hasta el siguiente día): hora de fin y/o salón
                    if (dia && /lblDiaSalon/.test(sp.className)) {
                      var hs = v.match(/\d{1,2}:\d{2}/g);
                      if (hs) dia.fin = hs[hs.length - 1];
                      var resto = v.replace(/\d{1,2}:\d{2}/g, '').replace(/^[\s\-–a]+|[\s\-–]+$/g, '').trim();
                      if (resto) dia.salon = resto;
                      return;
                    }
                    if (dia && /^\d{1,2}:\d{2}$/.test(v) && !dia.fin) { dia.fin = v; return; }
                    if (/_lblCampoAcademico_/.test(id) && !c.profesor) c.profesor = v;
                    if (/_lblCampoEdificio_/.test(id) && !c.edificio) c.edificio = v;
                    if (/_lblCampoSalon_/.test(id) && !c.salon) c.salon = v;
                  });
                }
                out.push(c);
              }
              return JSON.stringify(out);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { r ->
            if (terminado) return@evaluateJavascript
            val horario = try {
                val texto = JsonParser.parseString(r).asString
                val tipo = object : com.google.gson.reflect.TypeToken<List<com.pako.eminus4.data.model.ClaseHorario>>() {}.type
                com.google.gson.Gson().fromJson<List<com.pako.eminus4.data.model.ClaseHorario>>(texto, tipo)
                    .filter { it.materia.isNotBlank() }
            } catch (e: Exception) {
                Log.w(TAG, "Horario: no se pudo leer (${e.message})"); null
            }
            // Al log solo la forma (cuántas materias/sesiones y si se entendieron las horas), nunca nombres
            horario?.let { h ->
                val ses = h.flatMap { it.sesiones }
                Log.i(TAG, "Horario: ${h.size} materias, ${ses.size} sesiones, con hora de inicio ${ses.count { it.inicioMin != null }}/${ses.size}")
            }
            terminar(datosConFoto?.copy(horario = horario), null)
        }
    }

    /**
     * Un archivo nuevo por persona y por sincronización: la caché de Coil (por ruta)
     * nunca puede mostrar la foto de otra cuenta. Devuelve la ruta file://.
     */
    private fun guardarFoto(bytes: ByteArray): String {
        dirFotos.mkdirs()
        dirFotos.listFiles()?.forEach { it.delete() }
        val destino = File(dirFotos, "foto_${idFoto}_${System.currentTimeMillis()}.jpg")
        destino.writeBytes(bytes)
        Log.i(TAG, "Foto oficial guardada (${destino.length()} bytes)")
        return "file://" + destino.absolutePath
    }

    /** Descarga la foto con las cookies de la sesión de MiUV (en segundo plano). */
    private fun descargarFoto(url: String?, listo: (String?) -> Unit) {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            Log.w(TAG, "Sin foto en la página de MiUV")
            listo(null); return
        }
        val cookies = CookieManager.getInstance().getCookie(url)
        Thread {
            val ruta = try {
                val cliente = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS).build()
                val req = Request.Builder().url(url).apply {
                    if (!cookies.isNullOrBlank()) header("Cookie", cookies)
                }.build()
                cliente.newCall(req).execute().use { r ->
                    val tipo = r.header("Content-Type") ?: ""
                    if (!r.isSuccessful || !tipo.startsWith("image")) {
                        Log.w(TAG, "Foto no disponible: HTTP ${r.code}, tipo '$tipo'")
                        null
                    } else {
                        r.body?.bytes()?.let { guardarFoto(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo descargar la foto: ${e.message}")
                null
            }
            handler.post { listo(ruta) }
        }.start()
    }

    private fun terminar(datos: DatosMiuv?, motivoError: String?) {
        if (terminado) return
        terminado = true
        handler.removeCallbacksAndMessages(null)
        descartarCredenciales()
        if (motivoError != null) Log.w(TAG, "Sin datos de MiUV: $motivoError") else Log.i(TAG, "Datos de MiUV obtenidos")
        alTerminar(datos)
    }

    /** "INGENIERÍA DE SOFTWARE" / "ingeniería de software" → "Ingeniería de Software" */
    private fun capitalizar(s: String): String {
        val menores = setOf("de", "del", "la", "las", "los", "y", "e", "en", "para", "con")
        return s.lowercase().split(" ").filter { it.isNotBlank() }.mapIndexed { i, w ->
            if (i > 0 && w in menores) w else w.replaceFirstChar { it.uppercase() }
        }.joinToString(" ")
    }
}
