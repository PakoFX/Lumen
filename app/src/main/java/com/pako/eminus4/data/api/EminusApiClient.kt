package com.pako.eminus4.data.api

import com.pako.eminus4.data.model.*

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import com.google.gson.Gson
import com.pako.eminus4.core.Constants
import com.pako.eminus4.core.SessionManager
import com.pako.eminus4.data.model.Activity
import com.pako.eminus4.data.model.Course
import com.pako.eminus4.data.model.CourseResponse
import com.pako.eminus4.data.model.ExamItem
import com.pako.eminus4.data.model.ForumComment
import com.pako.eminus4.data.model.ForumDetail
import com.pako.eminus4.data.model.ForumItem
import com.pako.eminus4.data.model.MessageDetail
import com.pako.eminus4.data.model.MessageItem
import com.pako.eminus4.data.model.NotificationSummary
import com.pako.eminus4.data.model.StudentDelivery
import com.pako.eminus4.data.model.UserProfile
import com.google.gson.reflect.TypeToken
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class EminusApiClient(private val sessionManager: SessionManager) {

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://eminus.uv.mx/eminus4/page/course/list")

            // 1. Adjuntar Token Bearer
            val token = sessionManager.getAccessToken()
            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            // 2. Adjuntar Cookies de sesión de la UV
            try {
                val cookies = CookieManager.getInstance().getCookie("https://eminus.uv.mx")
                if (!cookies.isNullOrBlank()) {
                    requestBuilder.header("Cookie", cookies)
                }
            } catch (e: Exception) {
                Log.w("EMINUS_API", "No se pudieron obtener cookies de CookieManager: ${e.message}")
            }

            chain.proceed(requestBuilder.build())
        }
        .build()

    /** Mismo cliente (interceptores, cookies) pero con tiempos amplios para subir archivos pesados. */
    private val uploadClient: OkHttpClient = client.newBuilder()
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /**
     * Obtiene todos los cursos del usuario autenticado de forma asíncrona.
     */
    fun getAllCourses(onResult: (Result<List<Course>>) -> Unit) {
        val url = "${Constants.API_BASE_URL}${Constants.ENDPOINT_ALL_COURSES}"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al consultar cursos", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        Log.e("EMINUS_API", "Error 401: Token o sesión expirada")
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        Log.e("EMINUS_API", "Error del servidor HTTP $code: $bodyString")
                        postResult(onResult, Result.failure(Exception("Error del servidor UV ($code)")))
                        return
                    }

                    val courseResponse = gson.fromJson(bodyString, CourseResponse::class.java)
                    val cursos = courseResponse.contenido?.map { it.curso } ?: emptyList()

                    Log.d("EMINUS_API", "Cursos obtenidos exitosamente: ${cursos.size}")
                    postResult(onResult, Result.success(cursos))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando respuesta de cursos", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene las actividades de un curso específico de forma asíncrona.
     */
    fun getActivities(idCurso: Long, onResult: (Result<List<Activity>>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}${Constants.ENDPOINT_ACTIVITIES}/$idCurso"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al consultar actividades del curso $idCurso", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error del servidor UV ($code)")))
                        return
                    }

                    val listType = object : com.google.gson.reflect.TypeToken<List<Activity>>() {}.type
                    val actividades: List<Activity> =
                        if (code == 204 || bodyString.isBlank()) emptyList()
                        else gson.fromJson<List<Activity>>(bodyString, listType) ?: emptyList()

                    Log.d("EMINUS_API", "Actividades del curso $idCurso: ${actividades.size}")
                    postResult(onResult, Result.success(actividades))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando actividades del curso $idCurso", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene los detalles completos de una actividad para el estudiante (instrucciones, fechas, ponderación, adjuntos).
     */
    fun getActivityDetail(idCurso: Long, idActividad: Long, onResult: (Result<com.pako.eminus4.data.model.ActivityDetail>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}Actividad/getActividadEstudiante/$idCurso/$idActividad"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al consultar detalle de actividad $idActividad", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error del servidor UV ($code)")))
                        return
                    }

                    val detail = gson.fromJson(bodyString, com.pako.eminus4.data.model.ActivityDetail::class.java)
                    postResult(onResult, Result.success(detail))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando detalle de actividad $idActividad", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene el resumen de notificaciones globales del usuario (mensajes, actividades pendientes, foros, exámenes).
     */
    fun getNotifications(onResult: (Result<NotificationSummary>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}${Constants.ENDPOINT_NOTIFICATIONS}"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al consultar notificaciones", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error del servidor UV ($code)")))
                        return
                    }

                    val notif = gson.fromJson(bodyString, NotificationSummary::class.java)
                    postResult(onResult, Result.success(notif))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando notificaciones", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene la información completa del perfil de usuario desde la API institucional.
     */
    fun getUserProfile(onResult: (Result<UserProfile>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}${Constants.ENDPOINT_GET_USER}"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al consultar perfil", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error del servidor UV ($code)")))
                        return
                    }

                    val profile = gson.fromJson(bodyString, UserProfile::class.java)
                    postResult(onResult, Result.success(profile))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando perfil de usuario", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene la revisión / entrega de una actividad por parte de un estudiante.
     * Incluye archivos entregados, archivos de revisión y comentarios.
     */
    fun getStudentDelivery(idCurso: Long, idActividad: Long, idUsuario: String, onResult: (Result<StudentDelivery>) -> Unit) {
        val url = "${Constants.API_BASE_URL}Activity/getActUsuarioRevision/$idCurso/$idActividad/$idUsuario"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al consultar entrega de usuario $idUsuario", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error al consultar entrega ($code)")))
                        return
                    }

                    val delivery = gson.fromJson(bodyString, StudentDelivery::class.java)
                    postResult(onResult, Result.success(delivery))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando entrega de usuario", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Descarga un archivo binario autenticado desde Eminus hacia un archivo de destino en disco.
     */
    fun downloadFile(url: String, destinationFile: File, onResult: (Result<File>) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error descargando archivo $url", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    postResult(onResult, Result.failure(Exception("Error del servidor UV (${response.code})")))
                    return
                }

                val body = response.body
                if (body == null) {
                    postResult(onResult, Result.failure(Exception("Respuesta vacía del servidor")))
                    return
                }

                try {
                    destinationFile.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        destinationFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    postResult(onResult, Result.success(destinationFile))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error guardando archivo en disco", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene el listado de mensajes institucionales del buzón (carpeta 1 = recibidos, 2 = enviados).
     */
    fun getMessages(folderId: Int = 1, onResult: (Result<List<MessageItem>>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}centromensajes/GetListadoMensajes/$folderId/0"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error al obtener mensajes", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error al consultar mensajes ($code)")))
                        return
                    }

                    val type = object : TypeToken<List<MessageItem>>() {}.type
                    val messages: List<MessageItem> = gson.fromJson(bodyString, type) ?: emptyList()
                    postResult(onResult, Result.success(messages))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando mensajes", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene el detalle completo de un mensaje institucional por su ID.
     */
    fun getMessageDetail(messageId: Long, onResult: (Result<MessageDetail>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}centromensajes/GetMensajeDetalle/$messageId"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error al obtener detalle de mensaje", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error al consultar detalle ($code)")))
                        return
                    }

                    val type = object : TypeToken<List<MessageDetail>>() {}.type
                    val list: List<MessageDetail> = gson.fromJson(bodyString, type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        postResult(onResult, Result.success(list[0]))
                    } else {
                        postResult(onResult, Result.failure(Exception("Mensaje no encontrado")))
                    }
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando detalle de mensaje", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene los exámenes/evaluaciones disponibles para un estudiante en un curso específico.
     */
    fun getExams(courseId: Long, onResult: (Result<List<ExamItem>>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}Examen/getExamenesEst/$courseId"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error al consultar exámenes", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    if (code == 204) {
                        postResult(onResult, Result.success(emptyList()))
                        return
                    }

                    val bodyString = response.body?.string() ?: ""
                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error al consultar exámenes ($code)")))
                        return
                    }

                    if (bodyString.isBlank() || bodyString.trim() == "[]") {
                        postResult(onResult, Result.success(emptyList()))
                        return
                    }

                    val type = object : TypeToken<List<ExamItem>>() {}.type
                    val exams: List<ExamItem> = gson.fromJson(bodyString, type) ?: emptyList()
                    postResult(onResult, Result.success(exams))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando exámenes", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /**
     * Obtiene los foros de discusión de un curso específico.
     */
    fun getForums(courseId: Long, onResult: (Result<List<ForumItem>>) -> Unit) {
        val url = "${Constants.API_GLOBAL_URL}Foro/getForos/$courseId/0"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error al consultar foros", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    if (code == 204) {
                        postResult(onResult, Result.success(emptyList()))
                        return
                    }

                    val bodyString = response.body?.string() ?: ""
                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }

                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Error al consultar foros ($code)")))
                        return
                    }

                    if (bodyString.isBlank() || bodyString.trim() == "[]") {
                        postResult(onResult, Result.success(emptyList()))
                        return
                    }

                    val type = object : TypeToken<List<ForumItem>>() {}.type
                    val forums: List<ForumItem> = gson.fromJson(bodyString, type) ?: emptyList()
                    // Solo NOMBRES de campos (para validar el mapeo de fechas), nunca valores
                    try {
                        com.google.gson.JsonParser.parseString(bodyString).asJsonArray.firstOrNull()?.asJsonObject
                            ?.let { Log.d("EMINUS_API", "Campos de foro: ${it.keySet()}") }
                    } catch (_: Exception) { }
                    postResult(onResult, Result.success(forums))
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando foros", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /*
     * ===================== ENTREGA DE ACTIVIDADES =====================
     * Flujo idéntico al de Eminus web (bundle eminus4 v1.0.37, componente de entrega del alumno):
     *  1. Si el alumno aún no tiene entrega (idActividadEntrega == 0) se crea un BORRADOR con
     *     POST Actividad/InsertaActividadEntrega → responde { "salida": <idActividadEntrega> }.
     *  2. Se sube el archivo: POST actividad/adjuntarEntrega?idCurso&idActividad&idActividadEntregas
     *     (multipart, campo "file").
     *  3. Se confirma la entrega: POST Actividad/InsertaActividadEntrega con visible = 1.
     */

    /** Cuerpo JSON que Eminus web envía a InsertaActividadEntrega (clase `co` del bundle). */
    private fun deliveryPayload(
        idCurso: Long,
        idActividad: Long,
        idActividadEntrega: Long,
        textoHtml: String,
        visible: Int,
        tieneAdjuntos: Int,
        fechaEntregaIso: String?
    ): RequestBody = JSONObject().apply {
        put("idActividadEntrega", idActividadEntrega)
        put("texto", textoHtml)
        put("idActividad", idActividad)
        put("idCurso", idCurso)
        put("visible", visible)
        put("tieneAdjuntos", tieneAdjuntos)
        put("idEquipo", 0)
        put("fechaEntrega", fechaEntregaIso ?: JSONObject.NULL)
    }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

    /**
     * Paso 1: crea el borrador de entrega y devuelve su idActividadEntrega.
     */
    fun createDeliveryDraft(idCurso: Long, idActividad: Long, onResult: (Result<Long>) -> Unit) {
        val isoNow = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        val request = Request.Builder()
            .url("${Constants.API_GLOBAL_URL}Actividad/InsertaActividadEntrega")
            .post(deliveryPayload(idCurso, idActividad, 0L, "", 0, 0, isoNow))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al crear borrador de entrega", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val code = response.code
                    val bodyString = response.body?.string() ?: ""
                    Log.d("EMINUS_API", "InsertaActividadEntrega (borrador) HTTP $code: $bodyString")

                    if (code == 401) {
                        postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                        return
                    }
                    if (!response.isSuccessful) {
                        postResult(onResult, Result.failure(Exception("Eminus rechazó crear la entrega ($code)")))
                        return
                    }

                    val id = parseSalida(bodyString)
                    if (id > 0) {
                        postResult(onResult, Result.success(id))
                    } else {
                        postResult(onResult, Result.failure(Exception("Eminus no devolvió el número de entrega")))
                    }
                } catch (e: Exception) {
                    Log.e("EMINUS_API", "Error parseando borrador de entrega", e)
                    postResult(onResult, Result.failure(e))
                }
            }
        })
    }

    /** Eminus responde `{ "salida": 123 }` (a veces como texto o dentro de un arreglo). */
    private fun parseSalida(body: String): Long {
        val trimmed = body.trim()
        trimmed.toLongOrNull()?.let { return it }
        return try {
            val obj = if (trimmed.startsWith("[")) org.json.JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
            obj.optString("salida", "0").trim().toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Paso 2: sube el archivo físico como multipart a la entrega indicada.
     */
    fun uploadDeliveryFile(
        idCurso: Long,
        idActividad: Long,
        idActividadEntregas: Long,
        archivo: File,
        nombreOriginal: String,
        mimeType: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val url = "${Constants.API_GLOBAL_URL}actividad/adjuntarEntrega" +
                "?idCurso=$idCurso&idActividad=$idActividad&idActividadEntregas=$idActividadEntregas"

        val multipartBody: RequestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", nombreOriginal, archivo.asRequestBody(mimeType.toMediaTypeOrNull()))
            .build()

        val request = Request.Builder().url(url).post(multipartBody).build()

        uploadClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al subir entrega", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val bodyString = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.d("EMINUS_API", "adjuntarEntrega HTTP $code: $bodyString")

                when {
                    code == 401 -> postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                    code == 413 -> postResult(onResult, Result.failure(Exception("El archivo es demasiado grande para Eminus")))
                    !response.isSuccessful -> postResult(onResult, Result.failure(Exception("Error al subir archivo ($code)")))
                    else -> postResult(onResult, Result.success(Unit))
                }
            }
        })
    }

    /**
     * Paso 3: envía (confirma) la entrega para que el docente la vea.
     */
    fun submitDelivery(
        idCurso: Long,
        idActividad: Long,
        idActividadEntrega: Long,
        textoHtml: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val request = Request.Builder()
            .url("${Constants.API_GLOBAL_URL}Actividad/InsertaActividadEntrega")
            .post(deliveryPayload(idCurso, idActividad, idActividadEntrega, textoHtml, 1, 1, null))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al confirmar entrega", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val bodyString = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.d("EMINUS_API", "InsertaActividadEntrega (envío) HTTP $code: $bodyString")

                when {
                    code == 401 -> postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                    !response.isSuccessful -> postResult(onResult, Result.failure(Exception("Error al confirmar entrega ($code)")))
                    else -> postResult(onResult, Result.success(Unit))
                }
            }
        })
    }

    /**
     * Quita un archivo ya subido de la entrega del alumno.
     * Igual que Eminus web: PUT actividad/eliminaArchivoEntrega { idArchivosEntregas, idcurso }.
     */
    fun deleteDeliveryFile(idCurso: Long, idArchivosEntregas: Long, onResult: (Result<Unit>) -> Unit) {
        val body = JSONObject().apply {
            put("idArchivosEntregas", idArchivosEntregas)
            put("idcurso", idCurso)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("${Constants.API_GLOBAL_URL}actividad/eliminaArchivoEntrega")
            .put(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al quitar archivo de entrega", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val bodyString = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.d("EMINUS_API", "eliminaArchivoEntrega HTTP $code: $bodyString")

                when {
                    code == 401 -> postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                    !response.isSuccessful -> postResult(onResult, Result.failure(Exception("Error al quitar archivo ($code)")))
                    else -> postResult(onResult, Result.success(Unit))
                }
            }
        })
    }

    // ===================== ADJUNTOS (mensajes y comentarios de foro) =====================

    /** Sube varios archivos en una sola petición multipart (campo `file`, como Eminus web). */
    private fun subirArchivos(url: String, archivos: List<com.pako.eminus4.util.Adjuntos.Listo>, etiqueta: String, onResult: (Result<Unit>) -> Unit) {
        if (archivos.isEmpty()) { onResult(Result.success(Unit)); return }
        val cuerpo = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            archivos.forEach { a -> addFormDataPart("file", a.nombre, a.archivo.asRequestBody(a.mime.toMediaTypeOrNull())) }
        }.build()
        uploadClient.newCall(Request.Builder().url(url).post(cuerpo).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red en $etiqueta", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.d("EMINUS_API", "$etiqueta HTTP $code (${archivos.size} archivo/s, ${body.length} bytes)")
                when {
                    code == 401 -> postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                    code == 413 -> postResult(onResult, Result.failure(Exception("Un archivo es demasiado grande para Eminus")))
                    !response.isSuccessful -> postResult(onResult, Result.failure(Exception("Error al subir archivos ($code)")))
                    else -> postResult(onResult, Result.success(Unit))
                }
            }
        })
    }

    /** POST Foro/adjuntarComentarioForo?idCurso&idForo&idComentario (el comentario ya debe existir). */
    fun uploadForumCommentFiles(
        idCurso: Long, idForo: Long, idComentario: Long,
        archivos: List<com.pako.eminus4.util.Adjuntos.Listo>, onResult: (Result<Unit>) -> Unit
    ) = subirArchivos(
        "${Constants.API_GLOBAL_URL}Foro/adjuntarComentarioForo?idCurso=$idCurso&idForo=$idForo&idComentario=$idComentario",
        archivos, "adjuntarComentarioForo", onResult
    )

    /** POST centromensajes/adjuntarCentroMensajes/{idMensaje} (sobre el borrador, antes de enviarlo). */
    private fun uploadMessageFiles(idMensaje: Long, archivos: List<com.pako.eminus4.util.Adjuntos.Listo>, onResult: (Result<Unit>) -> Unit) =
        subirArchivos("${Constants.API_GLOBAL_URL}centromensajes/adjuntarCentroMensajes/$idMensaje", archivos, "adjuntarCentroMensajes", onResult)

    // ===================== MENSAJES: REDACTAR Y RESPONDER =====================
    // Mismo flujo que Eminus web (servicio centromensajes, API global).

    /**
     * Destinatarios posibles de un curso (profesor y compañeros).
     * GET centromensajes/getDestinatariosPorCurso/{idCurso} → { destinatarios: [ {idUsuario, nombre, correo, …} ] }.
     * Se devuelven los objetos tal cual: al enviar, Eminus espera esos mismos objetos.
     */
    fun getMessageRecipients(idCurso: Long, onResult: (Result<List<com.google.gson.JsonObject>>) -> Unit) {
        val req = Request.Builder().url("${Constants.API_GLOBAL_URL}centromensajes/getDestinatariosPorCurso/$idCurso").get().build()
        llamarJson(req, "getDestinatariosPorCurso") { r ->
            onResult(r.mapCatching { body ->
                if (body.isBlank()) return@mapCatching emptyList()
                val raiz = com.google.gson.JsonParser.parseString(body)
                val arr = when {
                    raiz.isJsonArray -> raiz.asJsonArray
                    raiz.isJsonObject && raiz.asJsonObject.has("destinatarios") -> raiz.asJsonObject.getAsJsonArray("destinatarios")
                    else -> com.google.gson.JsonArray()
                }
                val lista = arr.filter { it.isJsonObject }.map { it.asJsonObject }
                // Solo NOMBRES de campos al log, nunca valores
                lista.firstOrNull()?.let { Log.d("EMINUS_API", "Campos de destinatario: ${it.keySet()}") }
                lista
            })
        }
    }

    private fun cuerpoMensaje(
        idMensaje: Long, asunto: String?, html: String?,
        destinatarios: List<com.google.gson.JsonObject>?, carpeta: Int, idPadre: Long
    ) = com.google.gson.JsonObject().apply {
        if (asunto == null) add("asunto", com.google.gson.JsonNull.INSTANCE) else addProperty("asunto", asunto)
        if (destinatarios == null) add("destinatarios", com.google.gson.JsonNull.INSTANCE)
        else add("destinatarios", com.google.gson.JsonArray().apply { destinatarios.forEach { add(it) } })
        add("destinatarioscc", if (destinatarios == null) com.google.gson.JsonNull.INSTANCE else com.google.gson.JsonArray())
        addProperty("idMensaje", idMensaje)
        if (html == null) add("mensaje", com.google.gson.JsonNull.INSTANCE) else addProperty("mensaje", html)
        addProperty("idMensajeCarpeta", carpeta)
        addProperty("idMensajePadre", idPadre)
    }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

    /**
     * Envía un mensaje como Eminus web (centromensajes/insertaActualizaMensaje en 2 pasos):
     *  1. Crea un borrador vacío → Eminus devuelve su idMensaje.
     *  2. Lo actualiza con asunto, mensaje (HTML) y destinatarios, con idMensajeCarpeta = 1 (enviado).
     * Si el paso 2 falla, se borra el borrador para no dejar basura en "Borradores".
     * @param idPadre 0 = mensaje nuevo; id del mensaje original = respuesta.
     */
    fun sendMessage(
        asunto: String, html: String, destinatarios: List<com.google.gson.JsonObject>,
        idPadre: Long, adjuntos: List<com.pako.eminus4.util.Adjuntos.Listo> = emptyList(),
        onResult: (Result<Unit>) -> Unit
    ) {
        val url = "${Constants.API_GLOBAL_URL}centromensajes/insertaActualizaMensaje"
        val crear = Request.Builder().url(url).post(cuerpoMensaje(0, null, null, null, 0, idPadre)).build()
        llamarJson(crear, "insertaActualizaMensaje (borrador)") { r1 ->
            val idMensaje = r1.getOrNull()?.trim()?.trim('"')?.toLongOrNull()
            if (idMensaje == null || idMensaje <= 0) {
                onResult(Result.failure(r1.exceptionOrNull() ?: Exception("Eminus no creó el mensaje")))
                return@llamarJson
            }
            fun borrarBorrador() {
                val borrar = JSONObject().apply { put("idMensaje", idMensaje) }.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                llamarJson(Request.Builder().url("${Constants.API_GLOBAL_URL}centromensajes/EliminaMensajeBorrador").put(borrar).build(),
                    "EliminaMensajeBorrador") { }
            }
            // Adjuntos sobre el borrador (como Eminus web); si fallan, no se envía nada
            uploadMessageFiles(idMensaje, adjuntos) { rAdj ->
                if (rAdj.isFailure) {
                    borrarBorrador()
                    onResult(Result.failure(rAdj.exceptionOrNull() ?: Exception("No se pudieron adjuntar los archivos")))
                    return@uploadMessageFiles
                }
                val enviar = Request.Builder().url(url)
                    .post(cuerpoMensaje(idMensaje, asunto, html, destinatarios, 1, idPadre)).build()
                llamarJson(enviar, "insertaActualizaMensaje (enviar)") { r2 ->
                    if (r2.isSuccess) {
                        onResult(Result.success(Unit))
                    } else {
                        borrarBorrador()
                        onResult(Result.failure(r2.exceptionOrNull() ?: Exception("No se pudo enviar")))
                    }
                }
            }
        }
    }

    // ===================== FOROS: LEER Y PARTICIPAR =====================
    // Mismos endpoints que Eminus web (servicio de comentarios de foro, API global).

    /** GET/PUT/POST genérico que devuelve el cuerpo (o error con el código HTTP). */
    // ===================== DENTRO DE UNA MATERIA: CONTENIDO, MIEMBROS, SEGUIMIENTO =====================

    private inline fun <reified T> lista(body: String): List<T> =
        if (body.isBlank()) emptyList()
        else gson.fromJson<List<T>>(body, object : TypeToken<List<T>>() {}.type) ?: emptyList()

    private fun getJson(ruta: String, etiqueta: String, onResult: (Result<String>) -> Unit) =
        llamarJson(Request.Builder().url(Constants.API_GLOBAL_URL + ruta).get().build(), etiqueta, onResult)

    /** GET Contenido/getUnidades/{idCurso}/0 */
    fun getContentUnits(idCurso: Long, onResult: (Result<List<ContentUnit>>) -> Unit) =
        getJson("Contenido/getUnidades/$idCurso/0", "getUnidades") { r ->
            onResult(r.mapCatching { lista<ContentUnit>(it).filter { u -> u.visible == 1 }.sortedBy { u -> u.posicion } })
        }

    /** GET Contenido/getElementos/{idCurso}/{idUnidad} */
    fun getContentElements(idCurso: Long, idUnidad: Long, onResult: (Result<List<ContentElement>>) -> Unit) =
        getJson("Contenido/getElementos/$idCurso/$idUnidad", "getElementos") { r ->
            onResult(r.mapCatching { lista<ContentElement>(it).filter { e -> e.visible == 1 }.sortedBy { e -> e.posicion } })
        }

    /** PUT Contenido/insertaLeido {idElemento, idcurso} (botón "Leído" de Eminus web). */
    fun markElementRead(idCurso: Long, idElemento: Long, onResult: (Result<Unit>) -> Unit) {
        val cuerpo = JSONObject().apply { put("idElemento", idElemento); put("idcurso", idCurso) }.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        llamarJson(Request.Builder().url("${Constants.API_GLOBAL_URL}Contenido/insertaLeido").put(cuerpo).build(), "insertaLeido") { r ->
            onResult(r.map { })
        }
    }

    /** PUT Contenido/insertaLike {idElemento, idcurso} (alterna). */
    fun likeElement(idCurso: Long, idElemento: Long, onResult: (Result<Unit>) -> Unit) {
        val cuerpo = JSONObject().apply { put("idElemento", idElemento); put("idcurso", idCurso) }.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        llamarJson(Request.Builder().url("${Constants.API_GLOBAL_URL}Contenido/insertaLike").put(cuerpo).build(), "insertaLike") { r ->
            onResult(r.map { })
        }
    }

    /**
     * POST Contenido/InsertaComentariosElemento {Texto, IdElemento, IdCurso} — comentario en un elemento
     * del Contenido. Mismo cuerpo que arma Eminus web (`prepareComment` → `elementService.insertComment`).
     * Texto plano: se escapa y los saltos de línea van como <br>.
     */
    fun commentElement(idCurso: Long, idElemento: Long, texto: String, onResult: (Result<Unit>) -> Unit) {
        val html = android.text.TextUtils.htmlEncode(texto.trim()).replace("\n", "<br>")
        val cuerpo = JSONObject().apply { put("Texto", html); put("IdElemento", idElemento); put("IdCurso", idCurso) }.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        llamarJson(Request.Builder().url("${Constants.API_GLOBAL_URL}Contenido/InsertaComentariosElemento").post(cuerpo).build(),
            "InsertaComentariosElemento") { r -> onResult(r.map { }) }
    }

    /** Vuelve a pedir un elemento (para ver comentarios nuevos): se busca en getElementos de su unidad. */
    fun getContentElement(idCurso: Long, idUnidad: Long, idElemento: Long, onResult: (Result<ContentElement?>) -> Unit) =
        getContentElements(idCurso, idUnidad) { r -> onResult(r.map { l -> l.firstOrNull { it.idElemento == idElemento } }) }

    /** GET Usuario/getIntegrantes/{idCurso} */
    fun getCourseMembers(idCurso: Long, onResult: (Result<List<CourseMember>>) -> Unit) =
        getJson("Usuario/getIntegrantes/$idCurso", "getIntegrantes") { r -> onResult(r.mapCatching { lista<CourseMember>(it) }) }

    /** GET Seguimiento/SegDetalle/{idCurso}/{idUsuario} (Eminus devuelve una lista; se juntan las del usuario). */
    fun getTracking(idCurso: Long, idUsuario: String, onResult: (Result<TrackingDetail?>) -> Unit) =
        getJson("Seguimiento/SegDetalle/$idCurso/$idUsuario", "SegDetalle") { r ->
            onResult(r.mapCatching { body ->
                val todos = lista<TrackingDetail>(body)
                val mios = todos.filter { it.idUsuario.equals(idUsuario, ignoreCase = true) }.ifEmpty { todos }
                val base = mios.firstOrNull() ?: return@mapCatching null
                fun junta(f: (TrackingGroups) -> List<TrackingItem>?) =
                    mios.flatMap { m -> m.detalle?.let(f).orEmpty() }.distinctBy { it.id to it.titulo }
                base.copy(detalle = TrackingGroups(
                    actividades = junta { it.actividades }, foros = junta { it.foros }, examenes = junta { it.examenes }))
            })
        }

    /** GET Seguimiento/SegUltimoAccesoCurso/{idCurso}/{idUsuario} → {ultimoAcceso} */
    fun getLastAccess(idCurso: Long, idUsuario: String, onResult: (Result<String?>) -> Unit) =
        getJson("Seguimiento/SegUltimoAccesoCurso/$idCurso/$idUsuario", "SegUltimoAccesoCurso") { r ->
            onResult(r.mapCatching { body ->
                if (body.isBlank()) null
                else com.google.gson.JsonParser.parseString(body).asJsonObject.get("ultimoAcceso")?.takeIf { !it.isJsonNull }?.asString
            })
        }

    private fun llamarJson(request: Request, etiqueta: String, onResult: (Result<String>) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red en $etiqueta", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.d("EMINUS_API", "$etiqueta HTTP $code (${body.length} bytes)")
                when {
                    code == 401 -> postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                    code == 204 -> postResult(onResult, Result.success(""))
                    !response.isSuccessful -> postResult(onResult, Result.failure(Exception("Error de Eminus ($code)")))
                    else -> postResult(onResult, Result.success(body))
                }
            }
        })
    }

    private fun parsearComentarios(body: String): List<ForumComment> {
        if (body.isBlank() || body.trim() == "[]") return emptyList()
        val lista: List<ForumComment> = gson.fromJson(body, object : TypeToken<List<ForumComment>>() {}.type) ?: emptyList()
        // Solo NOMBRES de campos al log (para validar el mapeo), nunca valores
        try {
            val primero = com.google.gson.JsonParser.parseString(body).asJsonArray.firstOrNull()?.asJsonObject
            if (primero != null) Log.d("EMINUS_API", "Campos de comentario de foro: ${primero.keySet()}")
        } catch (_: Exception) { }
        return lista
    }

    fun getForumDetail(idCurso: Long, idForo: Long, onResult: (Result<ForumDetail>) -> Unit) {
        val req = Request.Builder().url("${Constants.API_GLOBAL_URL}Foro/getForo/$idCurso/$idForo").get().build()
        llamarJson(req, "getForo") { r ->
            onResult(r.mapCatching { gson.fromJson(it, ForumDetail::class.java) ?: ForumDetail() })
        }
    }

    /** Primera página de comentarios raíz (con algunas respuestas cada uno). */
    fun getForumComments(idCurso: Long, idForo: Long, onResult: (Result<List<ForumComment>>) -> Unit) {
        val req = Request.Builder().url("${Constants.API_GLOBAL_URL}Foro/getComentariosForo/$idCurso/$idForo").get().build()
        llamarJson(req, "getComentariosForo") { r -> onResult(r.mapCatching { parsearComentarios(it) }) }
    }

    /** Resto de comentarios raíz (Eminus web: botón "ver más comentarios"). */
    fun getMoreForumComments(idCurso: Long, idForo: Long, onResult: (Result<List<ForumComment>>) -> Unit) {
        val req = Request.Builder().url("${Constants.API_GLOBAL_URL}Foro/getMasComentarios/$idCurso/$idForo").get().build()
        llamarJson(req, "getMasComentarios") { r -> onResult(r.mapCatching { parsearComentarios(it) }) }
    }

    /** Todas las respuestas de un comentario (Eminus web: "ver más respuestas"). */
    fun getMoreForumAnswers(idCurso: Long, idForo: Long, idComentario: Long, onResult: (Result<List<ForumComment>>) -> Unit) {
        val req = Request.Builder().url("${Constants.API_GLOBAL_URL}Foro/getMasRespuestas/$idCurso/$idForo/$idComentario").get().build()
        llamarJson(req, "getMasRespuestas") { r -> onResult(r.mapCatching { parsearComentarios(it) }) }
    }

    /**
     * Publica un comentario (idPadre = 0) o una respuesta (idPadre = comentario al que responde).
     * Igual que Eminus web: POST Foro/InsertaComentarioForo
     * { IdComentarioForo: 0, Descripcion: <html>, IdPadre, IdCurso, IdForo }.
     */
    fun postForumComment(idCurso: Long, idForo: Long, html: String, idPadre: Long, onResult: (Result<Long?>) -> Unit) {
        val body = JSONObject().apply {
            put("IdComentarioForo", 0)
            put("Descripcion", html)
            put("IdPadre", idPadre)
            put("IdCurso", idCurso)
            put("IdForo", idForo)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val req = Request.Builder().url("${Constants.API_GLOBAL_URL}Foro/InsertaComentarioForo").post(body).build()
        // Eminus responde con el/los comentario(s) guardados: el id más alto es el nuevo
        llamarJson(req, "InsertaComentarioForo") { r ->
            onResult(r.map { body ->
                try {
                    val raiz = com.google.gson.JsonParser.parseString(body)
                    val lista = when {
                        raiz.isJsonArray -> raiz.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                        raiz.isJsonObject -> listOf(raiz.asJsonObject)
                        else -> emptyList()
                    }
                    lista.mapNotNull { it.get("idComentarioForo")?.takeIf { v -> v.isJsonPrimitive }?.asLong }.maxOrNull()
                } catch (_: Exception) { null }
            })
        }
    }

    /** Da o quita "me gusta" (Eminus lo alterna). PUT Foro/insertaComentarioLike { IdComentarioForo, IdCurso }. */
    fun likeForumComment(idCurso: Long, idComentario: Long, onResult: (Result<Unit>) -> Unit) {
        val body = JSONObject().apply {
            put("IdComentarioForo", idComentario)
            put("IdCurso", idCurso)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val req = Request.Builder().url("${Constants.API_GLOBAL_URL}Foro/insertaComentarioLike").put(body).build()
        llamarJson(req, "insertaComentarioLike") { r -> onResult(r.map { }) }
    }

    /**
     * Archiva (o desarchiva) un curso en la lista del alumno.
     * Igual que Eminus web: PUT Cursos/ActualizaArchivado { idCurso, archivado: 1|0 }.
     * Solo cambia en qué apartado aparece el curso; no afecta actividades ni calificaciones.
     */
    fun setCourseArchived(idCurso: Long, archivar: Boolean, onResult: (Result<Unit>) -> Unit) {
        val body = JSONObject().apply {
            put("idCurso", idCurso)
            put("archivado", if (archivar) 1 else 0)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("${Constants.API_GLOBAL_URL}Cursos/ActualizaArchivado")
            .put(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMINUS_API", "Error de red al archivar curso", e)
                postResult(onResult, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val bodyString = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                Log.d("EMINUS_API", "ActualizaArchivado HTTP $code: ${bodyString.take(200)}")

                when {
                    code == 401 -> postResult(onResult, Result.failure(Exception("Sesión expirada (401)")))
                    !response.isSuccessful -> postResult(onResult, Result.failure(Exception("Error al archivar ($code)")))
                    else -> postResult(onResult, Result.success(Unit))
                }
            }
        })
    }

    private fun <T> postResult(callback: (T) -> Unit, data: T) {
        mainHandler.post { callback(data) }
    }
}
