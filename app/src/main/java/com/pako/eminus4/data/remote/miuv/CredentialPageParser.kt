package com.pako.eminus4.data.remote.miuv

/** Lectura de la credencial digital de MiUV (`szicred.aspx`). */
object CredentialPageParser {

    private val PHOTO_IMG = Regex("""<img[^>]*id="content_imgFoto2"[^>]*>""", RegexOption.IGNORE_CASE)
    private val ANY_PHOTO_IMG = Regex("""<img[^>]*id="[^"]*imgFoto[^"]*"[^>]*>""", RegexOption.IGNORE_CASE)
    // MiUV la manda como "data:image/jpeg;charset=utf-8;base64,…" (con charset en medio)
    private val DATA_URI = Regex("""src="data:image/[^";,]+(?:;[^";,]+)*;base64,([^"]+)"""", RegexOption.IGNORE_CASE)

    /** ¿Es la página de la credencial? (si no, MiUV mandó a otra parte: sesión caducada). */
    fun isCredentialPage(html: String) = html.contains("szicred", true) || html.contains("imgFoto", true)

    /** Foto oficial en base64, o null si la página no la trae. */
    fun photoBase64(html: String): String? {
        val img = PHOTO_IMG.find(html)?.value ?: ANY_PHOTO_IMG.find(html)?.value ?: return null
        return DATA_URI.find(img)?.groupValues?.get(1)
    }
}
