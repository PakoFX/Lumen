package com.pako.eminus4.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.model.ClaseHorario
import com.pako.eminus4.data.model.SesionClase
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * La página del horario de MiUV (szihorc.aspx) solo dice a qué hora EMPIEZA cada clase.
 * Su botón "Imprimir" genera un PDF (Reporting Services, `Reportes/SZEVIEW.aspx?…&rep=horCreditos`)
 * que sí trae el rango completo ("16:00-17:59"). Aquí se repite ese botón con la sesión de MiUV que
 * dejó el login (cookies del WebView), se lee el PDF y se completa la hora de término.
 *
 * Corre en segundo plano DESPUÉS del inicio de sesión: no lo hace más lento. Si falla, el horario
 * se queda solo con la hora de inicio (y la persona puede indicar la de término a mano).
 */
object HorarioPdf {

    private const val TAG = "MIUV_SYNC"
    /** La pantalla del horario se suscribe mientras está visible. */
    @Volatile var alActualizar: (() -> Unit)? = null

    private const val URL_HORARIO = "https://dsiapes.uv.mx/MIUV/portales/estudiantes/szihorc.aspx"

    /** Las cookies de MiUV viven en el WebView: se comparten con OkHttp. */
    private object CookiesDelWebView : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            CookieManager.getInstance().getCookie(url.toString())?.split(";")
                ?.mapNotNull { Cookie.parse(url, it.trim()) }.orEmpty()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
        }
    }

    fun completarEnSegundoPlano(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val resultado = try { completar(app) } catch (e: Exception) { Log.w(TAG, "Horario PDF: ${e.message}"); null }
            if (resultado != null) Handler(Looper.getMainLooper()).post {
                SessionManager(app).guardarHorario(resultado)
                alActualizar?.invoke()   // si la pantalla del horario está abierta, se repinta
            }
        }.start()
    }

    enum class Resultado { OK, SIN_SESION, SIN_CLASES, ERROR }

    /**
     * "Volver a cargar mi horario" (pantalla del horario): vuelve a leer `szihorc.aspx` con la sesión de
     * MiUV del último inicio de sesión (sin WebView) y luego completa las horas de término con el PDF.
     * Si la sesión de MiUV ya caducó → [Resultado.SIN_SESION] (la app pide entrar otra vez a MiUV).
     */
    fun recargar(ctx: Context, listo: (Resultado) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val r = try { recargarTodo(app) } catch (e: Exception) { Log.w(TAG, "Recargar horario: ${e.message}"); Resultado.ERROR }
            Handler(Looper.getMainLooper()).post { listo(r) }
        }.start()
    }

    private fun recargarTodo(ctx: Context): Resultado {
        val cliente = OkHttpClient.Builder().cookieJar(CookiesDelWebView)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        // Sin seguir redirecciones: sin sesión, MiUV manda al login o a la portada uv.mx (que desde el
        // celular puede ni contestar y tardaba 2×15 s). Una redirección ya basta para saber que caducó.
        val html = MiuvHttp.traerPagina(cliente, URL_HORARIO) ?: return Resultado.SIN_SESION
        if (!html.contains("__VIEWSTATE")) return Resultado.SIN_SESION
        val horario = leerHtml(html)
        Log.i(TAG, "Recargar horario: ${horario.size} materias, ${horario.sumOf { it.sesiones.size }} sesiones")
        if (horario.isEmpty()) {
            Log.i(TAG, "Recargar horario: la página no trae materias (largo ${html.length})")
            return Resultado.SIN_CLASES
        }
        // Horas de término (PDF de "Imprimir"); si falla, se guarda igual con las de inicio
        val completo = try { completar(ctx, horario) } catch (e: Exception) { Log.w(TAG, "Horario PDF: ${e.message}"); null }
        val listo = java.util.concurrent.CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { SessionManager(ctx).guardarHorario(completo ?: horario); listo.countDown() }
        listo.await(5, TimeUnit.SECONDS)
        return Resultado.OK
    }

    /**
     * Lee el horario del HTML de `szihorc.aspx` (misma lógica que el JS de [MiuvSync.leerHorario]):
     * por materia `content_ctl00_dlHorario_*_{i}` y sus días en `dlHorariosDias_{i}`.
     */
    fun leerHtml(html: String): List<ClaseHorario> {
        // Prefijo de los ids ("content_ctl00_dlHorario_"), tomado de la propia página por si MiUV lo cambia
        val p = Regex("""\bid="(\w*?_dlHorario_)divEncabezadoTile_\d+"""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        // Todo elemento con id (no solo <span> simples): su texto completo, como textContent en el navegador
        val elementos = elementosConId(html)
        val spans = elementos.filter { it.tag == "span" }
        val porId = elementos.associateBy { it.id }
        fun valor(id: String) = porId[id]?.valor.orEmpty()
        val reDia = Regex("""_lblCampo(Lunes|Martes|Miercoles|Jueves|Viernes|Sabado|Domingo)_""")
        val reHora = Regex("""\d{1,2}:\d{2}""")

        val out = mutableListOf<ClaseHorario>()
        for (i in 0 until 80) {
            if (!html.contains("${p}divEncabezadoTile_$i\"")) break
            val creditos = listOf(valor("${p}lblCampoCreditos_$i"), valor("${p}lblCreditos_$i")).firstOrNull { it.any(Char::isDigit) }
            var profesor: String? = null; var edificio: String? = null; var salon: String? = null
            val sesiones = mutableListOf<SesionClase>()
            spans.filter { it.id.contains("${p}dlHorariosDias_${i}_") }.forEach { sp ->
                val dia = reDia.find(sp.id)?.groupValues?.get(1)
                val ultima = sesiones.lastOrNull()
                when {
                    dia != null -> sesiones.add(SesionClase(dia = dia, hora = sp.valor))
                    ultima != null && sp.clase.contains("lblDiaSalon") -> {
                        val hs = reHora.findAll(sp.valor).map { it.value }.toList()
                        val resto = sp.valor.replace(reHora, "").replace(Regex("""^[\s\-–a]+|[\s\-–]+$"""), "").trim()
                        sesiones[sesiones.lastIndex] = ultima.copy(
                            fin = hs.lastOrNull() ?: ultima.fin, salon = resto.ifBlank { ultima.salon })
                    }
                    ultima != null && Regex("""^\d{1,2}:\d{2}$""").matches(sp.valor) && ultima.fin.isNullOrBlank() ->
                        sesiones[sesiones.lastIndex] = ultima.copy(fin = sp.valor)
                    sp.id.contains("_lblCampoAcademico_") && profesor == null -> profesor = sp.valor
                    sp.id.contains("_lblCampoEdificio_") && edificio == null -> edificio = sp.valor
                    sp.id.contains("_lblCampoSalon_") && salon == null -> salon = sp.valor
                }
            }
            val materia = valor("${p}lblCampoExp_$i")
            if (materia.isBlank()) continue
            out.add(ClaseHorario(materia = materia, nrc = valor("${p}lblCampoNRC_$i").ifBlank { null },
                profesor = profesor, edificio = edificio, salon = salon, creditos = creditos,
                modalidad = valor("${p}lblModalidad_$i").ifBlank { null }, sesiones = sesiones))
        }
        return out
    }

    private data class Elemento(val id: String, val tag: String, val clase: String, val valor: String)

    /** Cada elemento con `id="…"`: etiqueta, clase y su texto (hasta su cierre real, contando anidados). */
    private fun elementosConId(html: String): List<Elemento> {
        fun texto(f: String) = desescapar(f.replace(Regex("<[^>]+>"), " ")).replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
        val apertura = Regex("""<([a-zA-Z][a-zA-Z0-9]*)(\s[^>]*?)?\sid="([^"]+)"([^>]*)>""")
        val vacias = setOf("input", "img", "br", "hr", "meta", "link")
        return apertura.findAll(html).map { m ->
            val tag = m.groupValues[1].lowercase()
            val attrs = m.groupValues[2] + " " + m.groupValues[4]
            val clase = Regex("""\bclass="([^"]*)"""").find(attrs)?.groupValues?.get(1).orEmpty()
            val ini = m.range.last + 1
            val valor = if (tag in vacias || m.value.endsWith("/>")) "" else texto(html.substring(ini, cierre(html, tag, ini)))
            Elemento(m.groupValues[3], tag, clase, valor)
        }.toList()
    }

    /** Posición del `</tag>` que cierra el elemento abierto antes de [desde] (respeta anidados del mismo tipo). */
    private fun cierre(html: String, tag: String, desde: Int): Int {
        val re = Regex("""<(/?)$tag\b[^>]*?(/?)>""", RegexOption.IGNORE_CASE)
        var nivel = 1
        var pos = desde
        while (true) {
            val m = re.find(html, pos) ?: return html.length
            if (m.groupValues[1] == "/") { if (--nivel == 0) return m.range.first }
            else if (m.groupValues[2] != "/") nivel++
            pos = m.range.last + 1
        }
    }

    private fun completar(ctx: Context, dado: List<ClaseHorario>? = null): List<ClaseHorario>? {
        val base = dado ?: SessionManager(ctx).getHorario().orEmpty()
        if (base.isEmpty()) return null

        val cliente = OkHttpClient.Builder()
            .cookieJar(CookiesDelWebView)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)   // el reporte puede tardar
            .build()

        // 1) La página del horario: sus campos ocultos (__VIEWSTATE, etc.) son necesarios para "Imprimir"
        val html = MiuvHttp.traerPagina(cliente, URL_HORARIO) ?: throw Exception("sesión de MiUV caducada")
        val ocultos = Regex("""<input[^>]*type="hidden"[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { m ->
            val tag = m.value
            val nombre = Regex("""name="([^"]*)"""").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
            val valor = Regex("""value="([^"]*)"""").find(tag)?.groupValues?.get(1).orEmpty()
            nombre to desescapar(valor)
        }.toList()
        if (ocultos.none { it.first == "__VIEWSTATE" }) throw Exception("sin __VIEWSTATE (¿sesión de MiUV caducada?)")

        // 2) "Imprimir" → MiUV redirige al reporte, que devuelve el PDF
        val form = FormBody.Builder().apply {
            ocultos.forEach { (n, v) -> add(n, v) }
            add("ctl00\$content\$btnDetalle", "Imprimir")
        }.build()
        var pdf: ByteArray? = null
        for (intento in 1..2) {
            pdf = cliente.newCall(Request.Builder().url(URL_HORARIO).post(form).build()).execute().use { r ->
                val b = r.body?.bytes()
                if (r.isSuccessful && b != null && b.size > 4 && String(b, 0, 5) == "%PDF-") b else {
                    Log.w(TAG, "Horario PDF: intento $intento → HTTP ${r.code}")
                    null
                }
            }
            if (pdf != null) break
            Thread.sleep(2500)
        }
        val bytes = pdf ?: return null

        // 3) Texto del PDF
        PDFBoxResourceLoader.init(ctx)
        val texto = PDDocument.load(bytes).use { doc -> PDFTextStripper().getText(doc) }
        val tabla = texto.substringBefore("Total de cr")   // la segunda tabla (direcciones) no interesa

        // 4) Hora de término por NRC y día
        val nrcs = base.mapNotNull { it.nrc?.takeIf { n -> n.isNotBlank() } }.distinct()
        val posiciones = nrcs.mapNotNull { n -> Regex("""\b$n\b""").find(tabla)?.range?.first?.let { n to it } }
            .sortedBy { it.second }
        var completadas = 0
        var total = 0
        val nuevo = base.map { clase ->
            val n = clase.nrc ?: return@map clase
            val i = posiciones.indexOfFirst { it.first == n }
            if (i < 0) return@map clase
            val segmento = tabla.substring(posiciones[i].second, posiciones.getOrNull(i + 1)?.second ?: tabla.length)
            val rangos = leerRangos(segmento)
            clase.copy(sesiones = clase.sesiones.map { s ->
                total++
                val ini = s.inicioMin ?: return@map s
                val dia = s.numeroDia - 1   // 0 = lunes
                val fin = (rangos.firstOrNull { it.dia == dia && it.inicio == ini } ?: rangos.firstOrNull { it.inicio == ini })?.fin
                if (fin != null) { completadas++; s.copy(fin = hhmm(fin)) } else s
            })
        }
        Log.i(TAG, "Horario PDF: hora de término en $completadas/$total sesiones")
        return if (completadas > 0) nuevo else null
    }

    private data class Rango(val dia: Int, val inicio: Int, val fin: Int)

    /**
     * En la tabla cada materia tiene renglones de 6 casillas (LUN…SAB): "16:00-17:59" o " - ".
     * Se respeta esa posición para saber el día; si el conteo no cuadra, el día queda en -1
     * y se empareja solo por hora de inicio.
     */
    private fun leerRangos(seg: String): List<Rango> {
        val tokens = Regex("""(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})|(?:^|\s)-(?=\s|$)""").findAll(seg).toList()
        val cuadra = tokens.isNotEmpty() && tokens.size % 6 == 0
        return tokens.mapIndexedNotNull { k, m ->
            val g = m.groupValues
            if (g[1].isEmpty()) return@mapIndexedNotNull null
            val ini = g[1].toInt() * 60 + g[2].toInt()
            val fin = g[3].toInt() * 60 + g[4].toInt()   // tal cual lo da MiUV (p. ej. 17:59)
            Rango(if (cuadra) k % 6 else -1, ini, fin)
        }
    }

    private fun hhmm(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)

    private fun desescapar(v: String) = v.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
