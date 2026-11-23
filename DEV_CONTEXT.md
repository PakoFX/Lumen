# Lumen (antes Bodrio-Nus) v2 (Arquitectura Nativa) - Guía Maestra de Contexto Técnico

> **⚠️ PROTOCOLO OBLIGATORIO PARA CUALQUIER IA QUE RETOME ESTE PROYECTO ⚠️**
>
> **NO GASTES TOKENS ESCANEANDO LA BASE DE CÓDIGO NI LEYENDO ARCHIVOS DESDE CERO.**
> Este documento contiene **toda la arquitectura, endpoints descubiertos, decisiones tomadas, estado actual del desarrollo y mapa exacto de archivos**. Léelo y pasa directamente a resolver la tarea del usuario.

---

## 🚨 REGLAS CRÍTICAS Y NO NEGOCIABLES

1. **NO HACER COMMITS NI PUSH HASTA QUE EL USUARIO LO ORDENE EXPLÍCITAMENTE.** Todo el trabajo se mantiene en local (`dev-v2`).
2. **ARQUITECTURA (refactor nov-2026, a petición del usuario: Clean Code, MVVM):** sigue sin Retrofit ni LiveData. Capas: `data/remote` (APIs por área con corrutinas sobre OkHttp) → `data/repository` (orquestan, caché, `Result`) → `domain` (reglas puras, con pruebas) → `ui` (ViewModel con `StateFlow` + Activity/Fragment que solo dibuja). Inyección manual en `di/AppContainer` (`context.appContainer`). Nombres de código en inglés; textos de UI y comentarios en español. Toda función `suspend` debe ser segura en el hilo principal (la red y el JSON van en `Dispatchers.IO`). Ver sección "🧱 REFACTOR".
3. **NO USAR `WindowCompat.setDecorFitsSystemWindows(false)`:** Rompe los WebViews en Android 14/HyperOS dejando pantalla negra. El manejo de insets se hace exclusivamente mediante `android:fitsSystemWindows="true"` en XML o listeners de `WindowInsetsCompat`.
4. **`windowSoftInputMode="adjustPan"` EN LOGIN:** Obligatorio en `AndroidManifest.xml` para evitar que el teclado redimensione agresivamente el WebView de login institucional.
5. **VISUALIZADOR OFFICE FIEL AL DISEÑO ORIGINAL (NO MONOLITO DE TEXTO):**
   - El usuario rechazó terminantemente extraer texto plano o listar párrafos/diapositivas en texto crudo.
   - DOCX debe renderizarse con diseño original (tablas, tipografías, encabezados, imágenes) usando `docx-preview.min.js`.
   - PPTX debe renderizarse con diapositivas completas (colores, fondos, formas, gráficos) usando `PptxViewJS.min.js` + `chart.umd.min.js` sobre `<canvas>` con controles Anterior/Siguiente y swipe táctil.
6. **ACTIVIDADES CERRADAS O CALIFICADAS NO ADMITEN ENTREGAS:** Si la actividad ya tiene calificación o pasó su `fechaTermino`, los botones de selección y envío de archivos DEBEN ocultarse y mostrarse el aviso con candado `🔒`.
7. **FEEDBACK DEL PROFESOR CON IMÁGENES/STICKERS:** Eminus usa Froala con imágenes públicas en `https://eminus.uv.mx/eminusapi/froala/...`. Deben cargarse mediante `webTeacherFeedback` transparente con `loadDataWithBaseURL("https://eminus.uv.mx", ...)`.
8. **IDIOMA DE LA UI:** Todo el texto visible para el usuario debe estar en **Español**.
10. **NOMBRE: la app se llama "Lumen" (antes Bodrio-Nus, renombrada en v2.12.0).** Textos visibles = Lumen. NO cambiar lo interno: `applicationId com.pako.eminus4` (cambiarlo impide actualizar), `bodrio.keystore`/alias `bodrio` (firma), URIs `bodrio://` de alarmas (cambiarlas dejaría alarmas viejas sin cancelar), prefijos CSS `bodrio-`.
9. **VERSIÓN DE LA APP:** Con cada sesión de trabajo que añada funcionalidad real, incrementar `versionCode` (+1) y `versionName` (semver) en `app/build.gradle.kts`. Esquema: `MAJOR.MINOR.PATCH` — MINOR para features nuevas, PATCH para bugfixes. **Versión actual: 2.12.0 (versionCode 21).** **Al publicar una versión, agregar su entrada ARRIBA en `ui/novedades/Changelog.kt`** (mismo `version` que `versionName`, frases cortas para el usuario): se muestra sola una vez al abrir la app tras actualizar. La UI nunca escribe la versión a mano: el splash (`SplashActivity`, `txtSplashVersion`) y `AppUpdater` leen `versionName` del paquete, así que basta con cambiarla en `build.gradle.kts`.
10. **MODO SIN CONEXIÓN Y RESILIENCIA DE SESIÓN (NO EXPULSIÓN ABRUPTA):**
    - Los errores 401 / caducidad de token **NUNCA DEBEN EXPULSAR AL USUARIO** a la pantalla de login ni borrar `SessionManager`.
    - `SplashActivity` permite entrar a `MainActivity` si hay datos en el teléfono (`hasSession()`), garantizando acceso offline a la credencial digital y cursos guardados.
    - Cuando el token expira, se muestra un banner superior discreto en `MainActivity` (`layoutOfflineBanner`) con el aviso y el botón "Renovar" respetando insets de la barra de estado.
    - Los cursos se guardan en caché local (`saveCachedCourses`) y se pintan de inmediato al abrir la app. Si no hay cursos en caché y la sesión caducó, se muestra el estado informativo adecuado invitando a renovar.

---

## 🧱 REFACTOR A MVVM (rama `refactor`, carpeta `Desktop/SS/Lumen-Refactor`, nov-2026)

Respaldo previo: rama `respaldo-v2.12-pre-refactor` (commit 5e25d0a). Sin push. Releases solo salen con tag `v*`.

**Hecho (cada etapa compilada, con pruebas y probada en el celular):**
- **Base:** `core/AppError` (SessionExpired, Network, Server(code), FileTooLarge, MissingData, Parsing) + `isSessionExpired()`/`isNetworkError()` + `resultOf {}` (no se traga la cancelación). `data/local/SessionStore` (antes `core/SessionManager`, mismas llaves). `di/AppContainer`. `data/remote/eminus/EminusClientFactory` (token + cookies + encabezados en un solo lugar; también el cliente de Coil). `EminusHttp` (suspend, nunca registra cuerpos de respuesta). Se quitaron logs con datos personales (cuerpos, nombre, payload de sesión con token).
- **APIs por área** (`data/remote/eminus`): `CoursesApi`, `ActivitiesApi`, `AccountApi`, `ExamsApi`, `ForumsApi`, `MessagesApi`, `CourseSectionsApi`, `JsonMapper`. `data/api/EminusApiClient` quedó como **fachada de transición** con las firmas de callbacks viejas (deprecated): borrarla cuando ninguna pantalla la use.
- **Dominio** (`domain`): `DueDates` (antes `util/Plazos`), `SubjectName` (antes `NombreMateria`; ahora abrevia palabras cortadas por Eminus, p. ej. "CONSTRUCCI"), `StudentStatus` (antes `EstadoAlumno`; ícono en `ui/common/StudentStatusUi`), `CourseCatalog` (apartados/buscador de cursos), `ExamRules`.
- **MiUV** (`data/remote/miuv`): `MiuvHttp` (único puente de cookies WebView→OkHttp, no sigue redirecciones fuera de MiUV), `HtmlElements`, `SchedulePageParser`, `SchedulePdfParser`, `CredentialPageParser` (puros, probados con HTML/PDF sintético). Repos `ScheduleRepository` (`updates: SharedFlow` reemplaza el callback estático `alActualizar`) y `CredentialRepository`. Se borraron `HorarioPdf`, `MiuvFoto`, `data/MiuvHttp`.
- **Pantallas en MVVM:** Cursos (`CoursesViewModel` + `CourseRepository`: varias pantallas comparten UNA petición de cursos, antes salían 3), Exámenes y Foros (base común `ui/common/SubjectBlocksViewModel` para listas "por materia, activas/pasadas"; `ExamRepository`/`ForumRepository` piden en paralelo con `async`; `ExamBlocks`/`ForumBlocks` puros). Mensajería (`MessagesViewModel` + `MessageRepository` + `MessageSections` puro; se quitó la copia de "nombre bonito" y se corrigió que las negritas de "sin leer" quedaban pegadas al reciclar filas). Detalle de foro (`ForumDetailViewModel` + `ForumCommentViews` + `domain/ForumThread`; publicar con adjuntos = `ForumRepository.publish`, que devuelve `Published`/`FilesFailed`). Detalle de mensaje y Redactar (`MessageDetailViewModel`, `ComposeMessageViewModel`, `domain/MessageRules`, `domain/MessageDraft`). `domain/StudentId` (normalizar matrícula; `SessionStore` delega). `util/PublicDownloads` (descarga a Descargas; antes copiada en 2 pantallas). Detalle de materia (`CourseDetailViewModel` con estado por pestaña `TabData` NotLoaded/Loading/Loaded/Failed; `CourseSectionsRepository` pide contenido por unidad y seguimiento+último acceso en paralelo; `SeccionesCurso` ya solo dibuja). Elemento de contenido (`ElementViewModel`: leído, me gusta optimista, comentar).
- **Pruebas:** 83 (JVM). `org.json` real solo para pruebas; MockWebServer; `kotlinx-coroutines-test`.

**Hecho (21-nov, tarde):** Avisos (`NotificationsViewModel` + `AgendaRepository`, que sustituye a `AgendaLoader` y `AvisosDatos`; la "foto" para Pendientes/Mis actividades vive en `agendaRepository.snapshot`; recordatorios vía interfaz `ReminderUpdater`), Pendientes y Mis actividades leen esa foto; Horario (`ScheduleViewModel` + `domain/ScheduleDay` puro + `data/local/SchedulePrefs`, mismas llaves `horario_fines`). 94 pruebas OK; verificado en el celular (Avisos 6/1/16, Pendientes, contadores del menú, Horario).

**Hecho (22-nov):** Perfil (`ProfileViewModel` + `ProfileRepository` + `domain/ProfileCardBuilder` puro: qué mostrar y cuándo completar la credencial); Detalle de actividad (`ActivityDetailViewModel` + `DeliveryRepository`: verificar→borrador→subir→confirmar y quitar archivo, con `Outcome` Done/Blocked/Failed(step); `domain/DeliveryRules` + `HtmlText.escape` compartido). 104 pruebas OK (incluye flujo de entrega contra MockWebServer). Verificado en el celular sin enviar nada.

**Hecho (22-nov):** Visor de archivos (`FileViewerViewModel` + `FileRepository`: caché `temp_docs`, borra descargas fallidas). 106 pruebas OK. Solo `LoginWebViewActivity` usa ya `EminusApiClient`.

**Hecho (22-nov):** Recordatorios: `data/model/Reminder.kt` (`ReminderSource`/`ScheduledAlarm`/`ReminderType`, con @SerializedName de los nombres viejos), `domain/ReminderPlanner` puro (fuentes y alarmas; mismo código `"clave|momento".hashCode()`), `data/local/ReminderStore` (prefs `recordatorios`, mismas llaves), `notificaciones/ReminderScheduler` (PendingIntent idéntico), `NotifPrefs.toSettings()`. `Recordatorios` queda como fachada + armado de la notificación; el receptor conserva su nombre. 112 pruebas OK (incluye JSON viejo → modelo nuevo). En el celular, instalado encima: 20 alarmas, sin duplicados.

**Hecho (22-nov):** Login (`FreshLoginGate` puro + `AuthRepository` + `CapturedSession`; sin `Thread {}`; ya no registra la respuesta del sondeo, que podía traer el token). **Fachada `EminusApiClient` borrada.** Mapa de archivos actualizado. 116 pruebas OK.

**Decisión:** el paquete `notificaciones` NO se renombra: las alarmas ya programadas en los teléfonos apuntan a `com.pako.eminus4.notificaciones.RecordatorioReceiver` y dejarían de sonar hasta reprogramarse.

**Pendiente:** probar en el celular cerrar sesión → iniciar sesión (lo hace el usuario: escribe su contraseña) y "Volver a cargar mi foto" (MiUV). `MiuvSync` sigue con callbacks/WebView (funciona; candidato a ViewModel si se toca).

**Lecciones:** (1) Android prohíbe red en el hilo principal: leer el cuerpo de OkHttp también es red; las pruebas JVM no lo detectan → probar en el celular. (2) El Python de Microsoft Store a veces no guarda escrituras: verificar con `git diff`/releer. (3) En `launch {}` dentro de una Activity, `this` es la corrutina: usar `this@Activity`.

---

## 📍 1. ESTADO ACTUAL Y TAREA ACTIVA (DÓNDE ESTAMOS)

### ¿Qué se completó exitosamente?
- ✅ **Login SSO UV:** Extracción de JWT desde `localStorage`, persistencia en SharedPreferences y prevención del bucle de sesión expirada.
- ✅ **Foros por materia (v2.11.0, `ForumsActivity`):** lista agrupada por materia (`FilaForo.Encabezado` + `FilaForo.Foro`); solo aparecen materias con foros. Filtros: estado (Abiertos [predeterminado] / Con aportes nuevos / Todos) y materia (chips "Todas las materias · N" + cada materia con su número). `ForumItem.estaAbierto()` = curso vigente + `estado == 1` + sin pasar `fechaProrroga`/`fechaTermino` (validado: `getForos` sí trae `fechaInicio`, `fechaTermino`, `fechaProrroga` y `fechaProrrogaEpoch`, además de `totalComentariosRaiz`, `visible`, `idUnidad`).
- ✅ **"¿Qué hay de nuevo?" (v2.12.0, `ui/novedades/`):** `Changelog.kt` = lista de versiones con puntos (ícono, título, detalle). `QueHayDeNuevo.mostrarSiHayNovedades` en `MainActivity` (una vez por versión, prefs "que_hay_de_nuevo"; muestra las versiones no vistas; instalación nueva → solo la actual) y al cerrarla encadena la pregunta de recordatorios. Perfil → botón discreto "Qué hay de nuevo · vX" → historial completo (`mostrarTodo`).
- ✅ **Recordatorios locales (v2.12.0, paquete `notificaciones/`):** al cargar Avisos con datos completos (no caché, sin errores ni sesión caducada) se guardan las FUENTES con fecha (`Recordatorios.actualizarTareas/Examenes/Foros`: tareas pendientes, exámenes sin calificar no cerrados, foros abiertos) y se programan alarmas (`AlarmManager.setExactAndAllowWhileIdle`, o inexactas si no hay permiso). Suenan sin sesión ni internet. Momentos según `NotifPrefs` (Perfil → Notificaciones, `NotificacionesActivity`): minutos antes (predeterminado 24 h, 5 h, 1 h), "venció y no entregué" (+1 min tras el cierre), examen "cuando abre". Al sonar (`RecordatorioReceiver`) se revisa que la fuente siga guardada (si ya se entregó y se recargó, no avisa). `ArranqueReceiver` reprograma al reiniciar/actualizar. Entregar desde la app → `quitarTarea`. Cerrar sesión → `borrarTodo`. Primera vez: diálogo para activar + permiso `POST_NOTIFICATIONS`. Canales: tareas, vencidas, examenes, foros. Máx. 400 alarmas. **Diseño del aviso** (`notificarFuente`): ícono grande con el color de la materia + ícono del tipo; color de urgencia (rojo ≤1 h/vencida, naranja <24 h, verde); cuenta regresiva en vivo (`setUsesChronometer` + `setChronometerCountDown` hasta el cierre, `setTimeoutAfter`); vista expandida `InboxStyle` (materia, cierra, faltan, pts, en equipo); botones "Recordar en 1 h" (`posponer`, alarma suelta) y "Ya la entregué" (`marcarEntregada` → `quitarTarea`). Una notificación por tarea (cada recordatorio reemplaza al anterior).
- ✅ **Novedades desde la última visita (v2.12.0, `notificaciones/Novedades.kt`):** foto por cuenta (SharedPreferences "novedades") de calificaciones por actividad, exámenes, comentarios por foro y mensajes no leídos vistos. Al cargar Avisos se compara: te calificaron / cambió tu calificación / nueva actividad / nuevo examen / nuevo foro o +N comentarios / mensaje nuevo. La 1.ª vez de cada fuente solo guarda la foto. "Marcar como visto" actualiza la foto. Sección arriba de Avisos (respeta el filtro de materia) y contador en "Avisos" del menú (`Contador.NOVEDADES`). **Fase 2 (notificar novedades en segundo plano) DESCARTADA** por el usuario (21-nov-2026): las novedades (calificaciones, tareas nuevas, mensajes…) se muestran al abrir la app comparando contra la sesión anterior; no se notifican.
- ✅ **Mensajes por materia (v2.12.0, `MessagesActivity`):** dos niveles. (1) Secciones: "Todos los mensajes" + una tarjeta por materia (`item_materia_mensajes.xml`; de `MessageItem.curso`, sin curso → "General") con color del curso, "N mensajes", badge "N sin leer" (solo Recibidos) y el último mensaje (remitente: asunto · hace X). Orden: más sin leer, luego más reciente. (2) Al tocar: los mensajes de esa materia (título = materia, subtítulo = conteo). Atrás/flecha vuelve a las secciones. Recibidos/Enviados aplican en ambos niveles.
- ✅ **Redactar y responder mensajes (v2.11.0, `ui/messages/ComposeMessageActivity.kt`):** botón "Redactar" en Mensajes y "Responder" en el detalle (solo si lo envió otra persona y su curso sigue vigente). Materia (cursos vigentes) → destinatarios del curso (`getDestinatariosPorCurso`, diálogo con "Todos los participantes"; sin uno mismo) → asunto y mensaje (texto plano → `<p>…</p>`). Envío en 2 pasos como Eminus web; si falla el 2.º se borra el borrador. **Validado en vivo el 20-nov-2026** (borrador HTTP 200 → id; envío HTTP 200, cuerpo vacío). Destinatario trae `idUsuario, nombre, correo, cursos, foto, idCurso, nombreCurso`.
- ✅ **Exámenes por materia (v2.11.0, `ExamsActivity`):** igual que Foros: lista agrupada por materia (`FilaExamen`), solo materias con exámenes. Estado: Disponibles [predeterminado: ya abrió, no cerró, sin calificar] / Próximos [aún no abre] / Calificados / Todos (`ExamsActivity.estaDisponible` / `esProximo`). Chips de materia con conteo. La etiqueta superior de cada tarjeta muestra el plazo ("VENCE MAÑANA, 23:59", "ABRE EL 3 DIC", "CERRADO") en vez de la materia.
- ✅ **Avisos por materia (v2.11.0, `NotificationsFragment`):** chips de materia arriba ("Todas · N" + cada materia activa con su total de pendientes). Con "Todas" aparece la sección POR MATERIA (`item_materia_resumen.xml`): color del curso, píldoras (por hacer [rojo si vence en ≤72 h], exámenes, foros nuevos, mensajes, en revisión, o "Al día") y lo próximo que vence; tocarla filtra esa materia. Con una materia elegida, resumen/por hacer/calificaciones/mensajes (por `MessageItem.curso`)/exámenes/foros se filtran. Los datos se guardan sin filtrar y `repintar()` aplica el filtro; los contadores del menú/barra siguen siendo globales.
- ✅ **Retro de usuarios (v2.12.0):**
  - **Emojis opcionales en notificaciones:** interruptor "Usar emojis en las notificaciones" (Perfil → Notificaciones; `NotifPrefs.conEmojis`, predeterminado sí). `Recordatorios` arma título y renglones con `e("⏰")`, que devuelve "" si están apagados.
  - **"¿No se ve tu foto? Volver a cargarla"** (Perfil, bajo el botón de la credencial) → `data/MiuvFoto.kt`: GET `szicred.aspx?params=E` con las cookies de MiUV del WebView (OkHttp `CookieJar`) y saca `img#content_imgFoto2`. **Ojo: MiUV la manda como `data:image/jpeg;charset=utf-8;base64,…`** (con charset en medio). Si la sesión de MiUV caducó (redirige a login) → diálogo → `LoginWebViewActivity` con `RENEWAL_ONLY` + `FORZAR_MIUV` (fuerza la sincronización aunque los datos sean recientes). Validado en vivo.
  - **Estado de la sesión** (Perfil): solo "Activa" (verde) o "Fuera de línea" (sin internet), con `NetworkCallback` para actualizarse en vivo. Antes decía "Sesión activa con token JWT institucional".
  - **Contenido incompleto (corregido, revisado contra las 28 materias de la cuenta, 207 elementos):**
    - **Elementos sin unidad:** muchos profes publican directo en la materia (`idUnidad = 0`); solo llegan con `GET Contenido/getElementos/{idCurso}/0`, aunque `getUnidades` venga vacío o la materia también tenga unidades (15 materias, 66 elementos se perdían; p. ej. Bases de Datos 137933: `getUnidades` = `[]`). `SeccionesCurso.cargarContenido` siempre pide la unidad 0 y la pinta primero ("General", o "Publicado en la materia" si es lo único).
    - **Tipos (`idTipoElemento`):** 1 = texto (HTML de Froala), 2 = galería de imágenes (adjuntos), 5 = página web: ZIP que Eminus sirve público en `eminusapi/drive/<ruta del adjunto con / >/<paginaInicio>` (también `GET contenido/getRutaHTML/{idCurso}/{idElemento}` → `{ruta}`); botón "Abrir página" (`ContentElement.urlPaginaWeb()`), y no se listan sus "archivos" (la ruta es una carpeta).
    - **Texto del profe:** era texto plano (se perdían imágenes `eminusapi/froala/...` —públicas—, `<iframe>` de YouTube y `<audio>`). Ahora `util/HtmlEminus` (WebView con colores del tema; enlaces se abren fuera). `archivosFroala` = enlaces del texto → sección "ENLACES" (toque abre, mantener copia). `spsComentariosElementos` → sección "COMENTARIOS" + caja para escribir: `POST Contenido/InsertaComentariosElemento` con `{Texto, IdElemento, IdCurso}` (sacado del bundle web: `prepareComment`/`elementService.insertComment`; texto escapado, saltos → `<br>`); después se vuelve a pedir `getElementos/{idCurso}/{idUnidad}` para pintar el comentario nuevo (`EminusApiClient.commentElement/getContentElement`). **Integrado pero NUNCA enviado en vivo** (a petición del usuario, para no publicar comentarios de prueba): validar con el primer comentario real.
    - `HtmlEminus` también en detalle de actividad (instrucciones y retro) y mensajes: antes el HTML llevaba color fijo `#1E293B` (ilegible en modo oscuro) y solo usaba WebView con `<img>`/`<table>`; ahora también con `<iframe>/<audio>/<video>` (`necesitaWeb`). Foros (descripción y comentarios) usan `HtmlEminus.enTextView`: imágenes **dentro del texto** (Coil + `Html.ImageGetter`, ajustadas al ancho; tocar → `FileViewerActivity`). **Videos:** YouTube (`<iframe>` embed) → en WebView se cambia por miniatura `img.youtube.com/vi/<id>/hqdefault.jpg` con ▶ que abre la app de YouTube; en TextView, enlace "▶ Ver en YouTube". Videos/audios subidos a Eminus (`<video>`, `<audio>`, enlaces .mp4/.mp3…; `eminusapi/drive|froala` son públicos) → `ui/viewer/ReproductorActivity` (WebView negro con `<video controls>`, gira con el celular, pantalla completa vía `onShowCustomView`). Todo enlace pasa por `HtmlEminus.abrirEnlace`. Los videos **adjuntos** como archivo (requieren token) van al visor/descarga; se decidió no hacer más (21-nov-2026).
  - **Buscador de cursos:** en Cursos, al elegir "Todos" o "Archivados" aparece `layoutBuscarCurso` (debajo de los chips). Filtra por nombre/NRC, sin acentos ni mayúsculas, todas las palabras deben coincidir (`CoursesFragment.normalizar`); en "Activos" se oculta y se limpia.
  - **"Volver a cargar horario"** (Horario: ícono ↻ en la barra; si no hay horario, botón "Cargar mi horario de MiUV"): `HorarioPdf.recargar` → GET `szihorc.aspx` con las cookies de MiUV del WebView, lo lee en Kotlin (`leerHtml`, misma lógica que el JS de `MiuvSync.leerHorario`; `elementosConId` saca el texto de cualquier elemento por id contando anidados — con un regex de `<span>…</span>` simple las materias salían vacías) y completa horas de término con el PDF. Validado en vivo 21-nov-2026: 6 materias/18 sesiones/18 términos, idéntico campo por campo a la carga normal.
  - **Sesión de MiUV caducada en recargas (foto/horario/PDF):** `data/MiuvHttp.traerPagina` NO sigue redirecciones fuera de MiUV: sin sesión, MiUV manda a la portada `uv.mx`, que desde el celular no contesta (antes se esperaban 2×15 s). Redirección al login/fuera → SIN_SESION al instante → diálogo "Entrar otra vez a MiUV".
  - **Bug corregido en "Entrar otra vez a MiUV" (`FORZAR_MIUV`):** si Eminus aún recordaba la sesión, el login entraba sin pedir contraseña y MiUV se omitía ("Sin usuario/contraseña capturados"), así que el respaldo de la foto y del horario nunca funcionaba. Ahora con `FORZAR_MIUV` se limpia la sesión web (no los datos de la app) para que pida y capture usuario/contraseña. Validado en vivo.
  - Ojo al editar con scripts de Python: dos veces quedaron caracteres de control literales en el código (NUL en `LoginWebViewActivity`, backspace `\x08` en un regex de `HorarioPdf`) que no rompen la compilación pero sí la lógica. Revisar con `grep -P "[\x00-\x08]"`.
  - **Privacidad y aviso legal:** `allowBackup="false"` + `data_extraction_rules.xml`/`backup_rules.xml` excluyen todo (en Android 12+ `allowBackup=false` NO bloquea la transferencia entre dispositivos; por eso las reglas). Aviso "app no oficial, no afiliada a la UV" fijo bajo el login (`txtAvisoNoOficial`, `@string/aviso_no_oficial_corto`) y al pie de Perfil (`@string/aviso_no_oficial`). Perfil → "Privacidad y aviso legal" (`ui/profile/AvisoPrivacidad.kt`, diálogo); mismo texto en el README (mantenerlos iguales). **Cerrar sesión (a propósito) borra todo lo de la cuenta** con `SessionManager.borrarDatosDeCuenta()` (21-nov-2026): prefs de sesión, credencial y fotos `files/credencial/`, caché `temp_docs`/`entregas`/`adjuntos`, prefs `novedades` y horas de término a mano (`horario_fines`, conserva `mostrar_libre`). Se quedan tema, emojis y filtros. Cuando la sesión **solo caduca** o se **renueva** no se borra nada (`clearSession(preserveCredential = true)`).
  - **Avatar sin foto visible en modo claro:** `ic_nav_profile` es blanco (se perdía sobre fondo claro). Nuevo `drawable/avatar_vacio.xml` (círculo `avatar_vacio_fondo` + silueta `avatar_vacio_silueta`, con modo oscuro) como placeholder/error en barra superior, Perfil, comentarios de foro y miembros.
- ✅ **Pantalla "Preparando tu cuenta" (login, v2.12.0, `layoutSyncMiuv` en `activity_login.xml`):** aviso destacado (tarjeta ámbar: puede tardar hasta un minuto, qué se trae y que al renovar no se repite) + 4 pasos con ícono (Entrando a MiUV · Programa y datos · Foto y QR · Horario) que avanzan con `MiuvSync.alAvanzar`. **Renovar sesión** (`RENEWAL_ONLY`) ya NO repite MiUV si es la misma cuenta, la credencial se actualizó hace < 7 días y hay horario guardado (`renovacionConDatosRecientes`); antes sí la repetía cuando se escribía la contraseña (si Microsoft recordaba la cuenta, ya se omitía).
- ✅ **Tiempo libre entre clases (horario):** botón de café en la barra (`menu_horario.xml`, se recuerda en prefs `horario_fines`/`mostrar_libre`); inserta recuadros negros (`item_tiempo_libre.xml`) entre clases con ≥ 10 min libres. Libre desde el fin de la anterior (##:59 → se cuenta desde la hora en punto) hasta el inicio de la siguiente; si una clase no tiene hora de término, no se calcula ese hueco.
- ✅ **Horario de clases (v2.12.0, `ui/horario/HorarioActivity.kt`, botón de reloj junto a Mensajes en la barra superior):** se lee de MiUV al iniciar sesión, como último paso de `MiuvSync` (después de datos y foto; máx. 14 s extra, si falla se entra igual y se conserva el horario anterior). Ruta: en el escritorio `smutiles.aspx` se guarda en memoria el enlace "Escolaridad" (`smustiles.aspx` **con parámetros**; sin ellos la página sale vacía) → ese menú dibuja sus opciones tarde (se reintenta cada 500 ms) → "Horario de clases" `portales/estudiantes/szihorc.aspx`. Estructura: por materia `content_ctl00_dlHorario_*_{i}` (`lblCampoExp` materia, `lblCampoNRC`, `lblCampoCreditos`, `lblModalidad`) y dentro `dlHorariosDias_{i}` (`lblCampoAcademico` profe, `lblCampo{Lunes..Sabado}` = **solo hora de inicio** "16:00", `lblCampoL/II/J…` vacíos, `lblCampoEdificio`, `lblCampoSalon`). **La página no trae la hora de término**, pero su botón "Imprimir" (`ctl00$content$btnDetalle`, postback) redirige a `portales/Generales/Reportes/SZEVIEW.aspx?pidm=…&rep=horCreditos`, que devuelve un **PDF** (Reporting Services) con el rango completo por NRC y día ("16:00-17:59", casillas LUN…SAB, " - " vacías). `data/HorarioPdf.kt` lo repite en segundo plano tras el login (OkHttp con las cookies del WebView vía `CookieJar`): GET szihorc → campos ocultos (`__VIEWSTATE`…) → POST con el botón → PDF (~270 KB, ~2 s) → texto con **pdfbox-android** (sin BouncyCastle, excluida: solo sirve para PDF cifrados; APK 18.2 → 14.5 MB) → empareja por NRC + día + hora de inicio → `SesionClase.fin` (tal cual, "17:59"). Validado en vivo 18/18. Si al abrir el horario falta alguna hora de término, se reintenta (máx. cada 10 min, mientras siga viva la sesión de MiUV); `HorarioPdf.alActualizar` repinta la pantalla si está abierta. Respaldo manual: la persona puede indicar la hora de término (MaterialTimePicker; prefs `horario_fines`). "En clase ahora" cuenta hasta el minuto ##:59 inclusive. Otras opciones del mismo menú, por si se integran: Calificaciones `szipaca2.aspx`, Calendario de exámenes `szibec.aspx`, Avance `szipalc.aspx`. Guardado en `SessionManager.guardarHorario/getHorario` (se borra al cerrar sesión). Modelo `data/model/Horario.kt` (sin `by lazy`: Gson lo serializaba y luego no podía leer el horario). Pantalla: un día por página con `ViewPager2` (se desliza entre días; `item_pagina_dia.xml`), sincronizado con los chips de arriba (tocar chip → página; deslizar → chip). Lunes a viernes siempre; sábado/domingo solo si alguna materia tiene clase ese día. Abre en hoy (fin de semana sin clases → primer día con clases), tarjeta "En clase ahora / Siguiente", clases con color del curso de Eminus (por nombre) y hoja de detalle.
- ✅ **Avisos rediseñado (v2.12.0):** (1) "Marcar como visto" no funcionaba: `Novedades` guardaba la foto con Gson sin `serializeNulls`, así que las actividades sin calificar (valor null) se perdían y salían siempre como "Nueva actividad"; corregido con `GsonBuilder().serializeNulls()`. (2) El resumen "Tienes N por hacer" se cambió por 3 números (Por hacer · Vencen pronto [≤96 h] · En revisión) + "Lo próximo"; cada número abre `PendientesActivity` con su enfoque. (3) Se quitó el filtro de materia de Avisos: tocar una tarjeta de "Por materia" abre `PendientesActivity` (pantalla aparte con tareas por hacer, exámenes disponibles, foros con aportes, mensajes y en revisión de esa materia). Los datos se comparten por `ui/notifications/AvisosDatos` (lo último que cargó Avisos, sin volver a pedir a Eminus). La tarjeta "N entregas esperando calificación" se eliminó (ya es un número del resumen), igual que el filtro de materias de Avisos. `bg_stat_pill` ahora usa colores con modo oscuro (`stat_pill_bg/stroke`).
- ✅ **Exámenes y Foros en bloques (v2.12.0):** filtros "Materias activas · N / Materias pasadas · N" (reemplazan Disponibles/Próximos/… y Abiertos/Nuevos/Todos, y el chip "Materia ▾"). Cada materia es un bloque (`ui/common/BloqueMateria.kt`, `item_bloque_materia.xml`) con color, resumen y aviso ("2 disponibles", "1 con novedades"); al tocarlo se despliegan sus exámenes/foros. Si hay un solo bloque se abre solo; si no hay nada en activas la primera vez, abre en pasadas.
- ✅ **Dentro de una materia: pestañas (v2.12.0, `CourseDetailActivity` + `SeccionesCurso.kt`):** Actividades N · Contenido · Miembros N · Seguimiento (reemplazan las pastillas de estadísticas; la barra tenía altura fija de 200dp que las tapaba). Validado en vivo el 21-nov-2026. Endpoints (API Global):
  - Contenido: `GET Contenido/getUnidades/{idCurso}/0` → unidades; `GET Contenido/getElementos/{idCurso}/{idUnidad}` (con `/0` devuelve vacío) → `nombre, texto(HTML), lecturaObligatoria, usuarioLeido, usuarioLike, likes, archivosElementosConTamano[idArchivosElementos, nombreOriginal, peso]`. Descarga `GET contenido/descargaElementoArchivo/{idCurso}/{idElemento}/{idArchivosElementos}`. `PUT Contenido/insertaLeido` y `PUT Contenido/insertaLike` con `{idElemento, idcurso}` (pendiente validar en vivo). Pantalla `ElementoActivity`.
  - Miembros: `GET Usuario/getIntegrantes/{idCurso}` → `idUsuario, idTipoPerfil (1 facilitador, 2 estudiante, 3 apoyo), tipoPerfil, nombre, paterno, materno, correo, ultimoAcceso, ruta`. También trae teléfono, redes, etc.: **no se muestran**. Hoja con correo, último acceso, "Escribirle un mensaje" (`ComposeMessageActivity.startPara`) y "Copiar correo".
  - Seguimiento: `GET Seguimiento/SegDetalle/{idCurso}/{idUsuario}` → lista con `totalRecursos "22/27", totalPuntos "149/270", detalle{actividades|foros|examenes[id, titulo, valor "9.5 / 10 pts.", realizado, revisado]}`; `GET Seguimiento/SegUltimoAccesoCurso/{idCurso}/{idUsuario}` → `{ultimoAcceso}`. `SeguimientoConcentradoCal` da 403 al alumno (es del profe).
- ✅ **Ahorro de pantalla (v2.12.0):** Cursos: "¡Hola, nombre!" pasó a la barra superior junto a la foto (solo en Cursos) y se quitó la región; los filtros Activos/Todos/Archivados se pliegan con "Activos · N ▾" (se recuerda en prefs `ui`/`cursos_filtros_abiertos`). Avisos y Cursos: la zona de filtros se esconde al bajar (`scroll|enterAlways|snap`). Exámenes y Foros: después pasaron a bloques por materia (ver abajo); el chip "Materia ▾" (`SelectorMateria`) y las filas de chips de materias se eliminaron en la limpieza. Nombres de materia cortos con `util/NombreMateria.corto()` ("Admón. Proyectos"). Chips compactos: estilo `Lumen.Chip.Filtro` (32dp).
- ✅ **Adjuntos (v2.12.0):** `util/Adjuntos.kt` (leer Uri, copiar a caché, máx. 5 archivos de 20 MB) + `ui/common/ListaAdjuntos.kt` (lista con quitar). **Descargar:** archivos del foro (`ForumDetail.archivos` ← `listaArchivos`, `Foro/descargaForoArchivo/{idCurso}/{idForo}/{idArchivosForos}`) y de cada comentario (`Foro/descargaComentarioArchivo/{idComentario}/{idArchivosForos}`), se abren en `FileViewerActivity`. **Subir:** comentario de foro = publicar → id (respuesta de `InsertaComentarioForo`, o el más nuevo propio con el mismo texto) → `POST Foro/adjuntarComentarioForo?idCurso&idForo&idComentario` multipart `file`; mensaje = borrador → `POST centromensajes/adjuntarCentroMensajes/{idMensaje}` multipart `file` → enviar (si falla, se borra el borrador). Pendiente validar en vivo.
- ✅ **Ícono de Lumen (v2.12.0):** "L" blanca de barras rectas + barrita ámbar con brillo (la luz) sobre azul plano #146CD0, en la línea visual del logo de Eminus sin copiarlo. Vectores `drawable/ic_launcher_background|foreground|monochrome.xml` (adaptativo + temático Android 13); `mipmap-*dpi/*.webp` para Android 7 generados con PIL (mismo diseño). El splash usa `drawable/splash_logo.xml` (fondo + dibujo, esquinas redondeadas).
- ✅ **Foros dentro de la app (v2.11.0, `ui/forums/ForumDetailActivity.kt`):** al tocar un foro (lista de Foros o Avisos) se abre: estado Abierto/Cerrado + cierre (`fechaProrroga` o `fechaTermino`), instrucciones HTML, comentarios con autor/foto/fecha/adjuntos, respuestas anidadas ("Ver N respuestas más"), "Ver más comentarios", me gusta (optimista) y caja para comentar o responder (texto plano → `<p>…<br></p>`). Si el foro está cerrado se oculta la caja. Leer validado: pendiente; publicar: pendiente de validar en vivo (lo publicado lo ve todo el grupo).
- ✅ **Cursos archivados (v2.10.0):** chips Activos / Todos los Cursos / Archivados en `CoursesFragment` con los criterios de Eminus web: Activos = vigentes y no archivados; Todos = activos → cerrados → archivados; Archivados = `archivado == 1`. Mantener presionado un curso → archivar (con confirmación) o restaurar (directo); actualización optimista que se revierte si Eminus falla. Tarjeta muestra "ARCHIVADO". Avisos/Exámenes/Foros NO excluyen archivados (archivar solo oculta de la lista, como en Eminus).
- ✅ **Navegación (v2.9.0, reemplaza al BottomNav):** barra superior en `MainActivity` (☰ · título · ícono de Mensajes con contador · avatar de Eminus → Perfil) + menú lateral `NavigationView` (`menu/drawer_menu.xml`): Cursos y Avisos (fragmentos; Cursos es el inicio) y Mis actividades / Foros / Exámenes (abren su Activity). Atrás: cierra el menú → vuelve a Cursos → sale. Los fragmentos ya NO aplican el inset de la barra de estado (lo hace la barra superior) ni tienen título propio.
- ✅ **Detalle de Curso y Lista de Actividades:** Consumo correcto de `GET Actividad/getActividadesEstudiante/{idCurso}`.
- ✅ **Detalle de Actividad (`ActivityDetailActivity`):**
  - Instrucciones enriquecidas con HTML y enlaces interactivos.
  - Lista de adjuntos del docente (`archivosActsConTamano`) con botones "Ver en línea" y "Descargar".
  - Lista de archivos entregados por el estudiante (`archivosEntregaConTamano`) con botones "Ver en línea" y "Descargar".
  - Retroalimentación del docente con soporte para stickers Froala (imágenes cargadas vía WebView con `loadDataWithBaseURL`).
  - Bloqueo automático de entregas en actividades cerradas/calificadas (`estaCerrada`).
  - Selector de archivos nativo con vista previa del archivo listo para subir.
- ✅ **Visor Integrado de Archivos In-App (`FileViewerActivity`):**
  - **PDF:** `PdfRenderer` nativo de Android en `RecyclerView` con scroll fluido y contador de página.
  - **Imágenes:** Coil a pantalla completa con soporte de zoom/escalado.
  - **DOCX:** WebView local cargando `file:///android_asset/viewer/docx_viewer.html` con `docx-preview.min.js` → renderizado con diseño real (tablas, fuentes, imágenes).
  - **PPTX:** WebView local cargando `file:///android_asset/viewer/pptx_viewer.html` con `PptxViewJS.min.js` + `chart.umd.min.js` → diapositivas reales con navegación y swipe.
  - Botones superiores en visor: Compartir y Guardar en Descargas.

- ✅ **Criterio único de estado (v2.7.0) — `data/model/EstadoAlumno.kt` + `Activity.estadoAlumno`:**
  - POR_HACER (abierta, `estado` null) · BORRADOR (abierta, `estado` 0) · ESPERANDO (`estado` 1/2) · CALIFICADA (`calificacion` != null o `estado` 3/4) · NO_ENTREGADA (cerrada y no enviada).
  - "Abierta" = `estadoAct` 2/4; si no viene, por `fechaTermino`. **`Activity.estado` es `Int?` a propósito** (Gson sin constructor convertiría null en 0 y borraría la diferencia entre "nunca tocada" y "borrador").
  - Úsalo en TODAS las pantallas (Avisos, lista del curso `ActivityAdapter`, detalle). Pruebas: `app/src/test/.../EstadoAlumnoTest.kt`.
  - `util/Plazos.kt`: textos de fecha humanos ("Vence mañana, 23:45", "Vence en 3 días", "hace 2 h") con nivel de urgencia.
- ✅ **Avisos como agenda (v2.7.0) — `NotificationsFragment`:** resumen ("Tienes N por hacer · X vencen en 3 días · K en equipo"), lista POR HACER con las tarjetas reales ordenadas por fecha (máx. 5 + "Ver todas"), y novedades que solo aparecen si hay algo: calificaciones de los últimos 14 días, mensajes sin leer, exámenes disponibles, foros con aportes nuevos; atajo "N entregas esperando calificación" y botones "Ir a". Datos vía `data/AgendaLoader.kt` (cursos activos + actividades; guarda `AgendaLoader.ultimo` en memoria para pintar al instante).
  - Tarjeta compartida: `item_all_activity.xml` + `ui/courses/ActividadCardBinder.kt` (franja de color del curso, etiqueta 👥 En equipo, píldora de estado, plazo coloreado por urgencia; modo `compacto` en listas agrupadas).
- ✅ **Sin emojis — íconos vectoriales (v2.7.0):** toda la UI usa **Material Icons (outlined, Apache 2.0)** convertidos a VectorDrawable en `res/drawable/ic_*.xml` (descargados de `@material-design-icons/svg@0.14.15`). **No volver a usar emojis en la UI.**
  - Texto con ícono: `TextView.iconoInicio(R.drawable.ic_x, color)` (`util/Iconos.kt`). Ícono de archivo por extensión: `ImageView.mostrarIconoArchivo(nombre)` / `iconoArchivo()`.
  - Cada `EstadoAlumno` trae su `icono`. Para agregar un ícono nuevo: bajar el SVG de ese paquete y convertirlo igual (un `<path>` por figura, `android:tint="?attr/colorControlNormal"`).
- ✅ **Contadores de navegación:** `NotificationsFragment` calcula por hacer, mensajes sin leer, foros con aportes nuevos y exámenes disponibles y los publica con `MainActivity.ponerContador(Contador.X, n)` → ícono de Mensajes de la barra y opciones del menú lateral (`item_drawer_badge.xml`). La antigua fila de accesos rápidos de Avisos se quitó (era redundante).
- ✅ **`ActivityDetail.estado` también es `Int?`** (mismo motivo que en `Activity`): antes una actividad nunca tocada aparecía como "Borrador" en el detalle.
- ✅ **Credencial con datos oficiales de MiUV (v2.8.0) — `data/MiuvSync.kt`:**
  - Eminus NO expone la carrera (`Usuario/getUsuario` solo trae `idDependencia`). Está en MiUV `dsiapes.uv.mx/MIUV/portales/estudiantes/szigral.aspx` (`span#content_wucDatosGralAlum1_lblProg` + bloques `#div-iAcademica p` "Etiqueta: valor").
  - MiUV es un **login aparte** (ASP.NET Forms) con el mismo usuario/contraseña. El JWT de Eminus no sirve ahí.
  - Flujo: en `LoginWebViewActivity` un script captura usuario/contraseña al enviar el formulario (**solo en memoria**). Tras capturar el token de Eminus, `MiuvSync` usa el mismo WebView (oculto tras `layoutSyncMiuv`): `login.aspx` **sin `ReturnUrl`** (con él MiUV redirige a uv.mx) → llena y envía → `smutiles.aspx` → enlace al portal → `szigral.aspx` → extrae datos → descarga la foto con las cookies de MiUV. Límite 45 s; si falla, se entra a la app igual.
  - **La contraseña se sobrescribe con ceros justo después de llenar el formulario** y otra vez al terminar/`onDestroy`. Nunca se guarda ni se loguea. Al log (`MIUV_SYNC`) solo van NOMBRES de campos.
  - Solo se guardan programa, campus, facultad, área, nivel y foto (`SessionManager.aplicarDatosMiuv`). La página también trae CURP, NSS, dirección, etc.: **no se guardan**.
  - Si Eminus aún no reportó `userId` al capturar el token, se usa la matrícula escrita en el login como respaldo.
  - Foto: `files/credencial/foto_<MATRICULA>_<ts>.jpg`, un archivo por persona y sincronización (evita que la caché de Coil muestre la foto de otra cuenta).
  - **Dos fotos distintas:** la tarjeta del Perfil usa el **avatar de Eminus** (el que elige la persona); la **foto oficial de MiUV** va SOLO en la credencial. `StudentCredential.esAvatarDeEminus()` compara contra `http…eminus.uv.mx`: NO buscar "eminus" a secas, porque la ruta local `com.pako.eminus4` también lo contiene.
- ✅ **Credencial por usuario:** `SessionManager.getCredencialDelUsuarioActual()` (pantalla completa y Perfil) y `descartarCredencialAjena()` al iniciar sesión (borra credencial y fotos de la persona anterior). Sin datos personales por defecto en código/layout; `foto_credencial_oficial.png` solo para su dueño (`StudentCredential.MATRICULA_FOTO_EMPAQUETADA`, temporal).
- ✅ **Fondo animado oficial:** `res/raw/credencial_fondo_animado.gif` (de MiUV `Images/credencial/CRED_GIF_01.gif`, requiere sesión para bajarlo) cargado con `coil-gif` en `imgFondoAnimado`.
- ✅ **"Mis actividades" (`AllActivitiesActivity`, v2.7.0):** pestañas Por hacer / Esperando calificación / Calificadas / No entregadas con conteo. "Por hacer" ordenada por fecha; las demás agrupadas por curso (`item_section_header.xml`). Abre en la pestaña indicada con `AllActivitiesActivity.start(ctx, Pestana.X)`.
- ✅ **Módulo Avisos — pantallas secundarias:**
  - **Exámenes (`ExamsActivity`):** Lista todos los exámenes de todos los cursos que tengan exámenes (`isActivo() || examenes > 0`). Filtros: Todos / Disponibles / Calificados. Click muestra dialog informativo "Accede desde Eminus Web".
  - **Foros (`ForumsActivity`):** Lista todos los foros de todos los cursos que tengan foros (`isActivo() || foros > 0`). Filtros: Todos / Con aportes nuevos. Badge de nuevos comentarios. Click muestra dialog informativo.
  - **Mensajería (`MessagesActivity` + `MessageDetailActivity`):** Bandeja Recibidos/Enviados (77+ mensajes reales). Click en mensaje abre detalle completo con cuerpo HTML, remitente, fecha, lista de adjuntos con botones Descargar/Ver.
- ✅ **Credencial Digital UV Offline (Perfil):**
  - Tarjeta en `ProfileFragment` con franja verde institucional UV (`#00897B`).
  - **Reloj antifraude dinámico en tiempo real (`TextClock`)** con formato oficial (`dd MMM, hh:mm:ss a`), que corre localmente con el reloj del teléfono (funciona 100% sin internet).
  - Foto oficial institucional, nombre completo, matrícula en badge `#0284C7`, carrera y campus.
  - Generación offline de **Código QR oficial** y **Código de barras lineal (Code 128)** con ZXing (`BarcodeGenerator.kt`).
  - **Modo Pantalla Completa (`CredentialFullscreenActivity`):** Eleva automáticamente el brillo de la pantalla al 100% para escaneo en torniquetes/bibliotecas y mantiene la pantalla encendida.
  - Persistencia permanente en `SessionManager.saveStudentCredential` (nunca se borra al estar sin conexión).
  - Diálogo de edición para personalizar la carrera/licenciatura mediante click sostenido en la credencial.

- ✅ **Entrega real de archivos (v2.6.0, `ActivityDetailActivity.enviarEntrega`)** — ver sección 5b. **Validada en vivo el 16-nov-2026** (caso "entrega existente": `adjuntarEntrega` 200 + `InsertaActividadEntrega` 200; el archivo aparece, `idEstado` sigue en 1 y el `descEntrega` HTML original se conserva). Falta probar en vivo el caso "sin entrega previa" (creación de borrador).
- ✅ **Actualización automática** (`AppUpdater.kt`): consulta GitHub Releases al abrir `MainActivity` (silencioso) y desde Perfil (manual).

### 🚀 Checklist de la PRÓXIMA PUBLICACIÓN (v2.12.0 "Lumen") — solo cuando el usuario lo ordene
1. Renombrar el repo en GitHub: `gh repo rename Lumen` (PakoFX/Bodrio-Nus → PakoFX/Lumen). GitHub redirige el nombre viejo: las apps v2.8.1 siguen recibiendo actualizaciones. **Nunca volver a crear un repo llamado `Bodrio-Nus`** (rompería la redirección).
2. `AppUpdater.GITHUB_REPO = "PakoFX/Lumen"` y `git remote set-url origin https://github.com/PakoFX/Lumen.git`.
3. Revisar que `Changelog.kt` tenga la entrada de la versión y que `versionName` coincida.
4. Commit en `dev-v2` (sin coautor de Claude), merge a `main`, tag `v2.12.0` → el workflow publica `Lumen.apk`.
5. Verificar la release (Latest, con APK) y que `releases/latest` responda por el nombre nuevo y por el viejo.

### ¿Qué falta por hacer / Tarea activa?
1. **Probar en vivo la entrega sin entrega previa** (paso de borrador) y revisar `adb logcat -s EMINUS_API`.
2. **Foros:** ✅ ya se leen y se participa dentro de la app. **Exámenes: DESCARTADO presentarlos dentro de la app** (decisión del usuario, 20-nov-2026: un examen mal enviado afecta la calificación). Siguen abriéndose en Eminus web.
2b. ✅ **Foto de la credencial en HD (v2.8.2, validada en vivo el 20-nov-2026: 480×640, ~144 KB):** `MiuvSync`, tras leer `szigral.aspx`, abre la credencial digital `szicred.aspx?params=E`, toma la foto en base64 de `img#content_imgFoto2` (~480×640 JPEG) y la guarda. Si falla (sin foto, redirección, imagen inválida) usa la miniatura de 60×80 de `szigral.aspx`; si se agota el tiempo conserva los datos y la foto previa. Validar con `adb logcat -s MIUV_SYNC` (debe decir "Foto HD de la credencial: 480×640").
2c. ✅ **QR oficial de la credencial (v2.8.2, validado el 20-nov-2026):** antes el QR apuntaba a una URL inventada (`dsia.uv.mx/credencial/validar?u=…`, inexistente → portada de la UV). Ahora `MiuvSync.extraerFotoHD()` decodifica con ZXing (`leerQr`) las imágenes/canvas cuadrados de `szicred.aspx` y guarda el contenido real en `DatosMiuv.qrOficial` → `StudentCredential.qrCode` (`SessionManager.aplicarDatosMiuv`). Destino real: `https://dsia.uv.mx/urls/v.aspx?…` (no loguear los parámetros). Editar la credencial a mano conserva el QR oficial. Quien no vuelva a iniciar sesión sigue con el QR viejo hasta la siguiente sincronización con MiUV.
3. **Planes futuros (Solicitados por el usuario):**
   - ✅ **Buzón de sugerencias (v2.12.0, `ui/profile/SugerenciasActivity.kt`):** Perfil → "Buzón de sugerencias". Tipo (Sugerencia / Algo falla / Otro), mensaje, contacto opcional y datos técnicos opcionales (versión, modelo, Android; se muestran antes de enviar). POST `formResponse` al Google Form "Buzón Bodrio-Nus" (cuenta del desarrollador; ids `entry.*` en la clase) con un OkHttp LIMPIO (sin token ni cookies de Eminus). Nunca se manda matrícula ni datos de Eminus.
   - **Contador / Telemetría de usuarios activos:** Implementar un mecanismo anónimo y ligero para monitorear cuántas personas están usando la app realmente día a día (ej. ping telemetry o endpoint analítico).
   - **Notificaciones / recordatorios en el teléfono** (parte D de la propuesta de Avisos, quedó pendiente):
     - Avisar antes de que venza algo **Por hacer** (p. ej. 24 h y 3 h antes), calificaciones nuevas, mensajes sin leer nuevos y foros con aportes nuevos.
     - Implementación sugerida: **WorkManager** (`androidx.work:work-runtime-ktx`, dependencia nueva) con un trabajo periódico (~cada 1–3 h) que reutilice `AgendaLoader` + el criterio `EstadoAlumno`/`Plazos`; recordatorios exactos de vencimiento con trabajos únicos programados a la hora límite menos 24 h / 3 h.
     - Canal de notificaciones propio; pedir el permiso `POST_NOTIFICATIONS` en Android 13+ (ya está declarado en el manifest, falta pedirlo en tiempo de ejecución).
     - Evitar duplicados guardando en SharedPreferences los ids ya notificados (por actividad + tipo de aviso).
     - Si el token caducó (401): no avisar en silencio de cosas viejas; como mucho una notificación "Renueva tu sesión para seguir recibiendo avisos".
     - Interruptor en Perfil para activarlas/desactivarlas (y quizá elegir qué tipos).
     - Tocar la notificación abre la actividad / mensaje correspondiente (`ActividadCardBinder.abrirDetalle`, `MessageDetailActivity.start`).

---

## 🗺️ 2. MAPA MAESTRO DE ARCHIVOS (UBICACIÓN EXACTA)

| Componente | Archivo | Responsabilidad Principal |
|---|---|---|
| **Núcleo & Sesión** | `core/Constants.kt` | URLs base de APIs (`eminusapi8`, `eminusapi`, estáticos), endpoints y keys de SharedPreferences. |
| | `core/AppError.kt` | Errores tipados (`SessionExpired`, `Network`, `Server`, `FileTooLarge`, `MissingData`, `Parsing`) y `resultOf {}`. |
| | `data/local/SessionStore.kt` | (ex `SessionManager`) SharedPreferences: token JWT, matrícula, caché de cursos, credencial, horario, logout. Mismas llaves que antes. |
| **Inyección** | `di/AppContainer.kt` | Crea OkHttp, `EminusHttp`, APIs y repositorios (inyección manual). Acceso: `context.appContainer`. |
| **Modelos de Datos** | `data/model/Course.kt` | Modelo de curso, thumbnail público, color de tarjeta y estado activo/cerrado. |
| | `data/model/Activity.kt` | Item de actividad en lista de curso, ponderación, fecha límite y estado. |
| | `data/model/ActivityDetail.kt` | Detalle completo de actividad, instrucciones HTML, `estaCerrada`, `estaCalificada`. |
| | `data/model/ActivityAttachment.kt` | Adjunto del profesor (`idArchivosActividades`, `nombreOriginal`, `peso`, `getDownloadUrl`). |
| | `data/model/StudentDelivery.kt` | Datos de entrega del alumno: `idActividadEntrega`, `descEntrega`, `descRevision`, lista de archivos. |
| | `data/model/DeliveryAttachment.kt` | Archivo entregado por alumno (`idArchivosEntregas`, `nombreOriginal`, `peso`, `getDownloadUrl`). |
| **Red Eminus** | `data/remote/eminus/EminusHttp.kt` | `suspend` GET/POST/PUT/upload/download, main-safe (IO). Nunca registra cuerpos. `EminusClientFactory`: token, cookies, cabeceras. |
| | `data/remote/eminus/*Api.kt` | Endpoints por área: `CoursesApi`, `ActivitiesApi`, `AccountApi`, `ExamsApi`, `ForumsApi`, `MessagesApi`, `CourseSectionsApi`. |
| **Red MiUV** | `data/remote/miuv/` | `MiuvHttp` (único puente de cookies del WebView), parsers de horario (HTML y PDF) y credencial. |
| **Repositorios** | `data/repository/` | `Course`, `Agenda`, `Exam`, `Forum`, `Message`, `CourseSections`, `Delivery`, `File`, `Profile`, `Credential`, `Schedule`, `Auth`. Devuelven `Result<T>`. |
| **Dominio (puro)** | `domain/` | Reglas sin Android, con pruebas: `DueDates`, `SubjectName`, `StudentStatus`, `ExamRules`, `ScheduleDay`, `ProfileCardBuilder`, `DeliveryRules`, `ReminderPlanner`, `FreshLoginGate`… |
| **Pantallas** | `ui/<área>/` | Cada pantalla = vista (`Activity`/`Fragment`, solo dibuja) + `XxxViewModel` (`StateFlow` de estado + `Channel` de eventos). |
| **Autenticación** | `ui/auth/LoginWebViewActivity.kt` | WebView SSO institucional y captura del token en `localStorage`. La decisión de aceptar el token está en `domain/FreshLoginGate`; guardar la sesión en `AuthRepository`. |
| **Pantalla Principal** | `ui/main/MainActivity.kt` | `DrawerLayout` + barra superior propia + `NavigationView`; fragmentos `Courses`/`Notifications`/`Profile` con hide/show. |
| **Cursos** | `ui/courses/CoursesFragment.kt` | Lista de cursos (pestaña). La antigua `CoursesActivity` se eliminó en v2.6.0. |
| **Actualizador** | `AppUpdater.kt` | Busca la última release en GitHub, descarga el APK e invoca el instalador. |
| **Detalle Actividad** | `ui/courses/ActivityDetailActivity.kt` | Visualización de instrucciones, bloqueo de entregas, lista de adjuntos del profe y del alumno, feedback con imágenes, selector de archivo. |
| **Visor en Línea** | `ui/viewer/FileViewerActivity.kt` | Orquestador de visualización in-app (PDF, DOCX, PPTX, imágenes, texto). |
| | `ui/viewer/PdfPageAdapter.kt` | Adapter de `PdfRenderer` para scroll vertical fluido de páginas PDF. |
| | `ui/viewer/OfficeDocumentRenderer.kt` | Fallback para formatos legacy binarios (`.doc`, `.ppt`, `.xls`). |
| **Librerías Webview** | `assets/viewer/docx_viewer.html` | Plantilla HTML + `docx-preview.min.js` para renderizar Word con diseño real. |
| | `assets/viewer/pptx_viewer.html` | Plantilla HTML + `PptxViewJS.min.js` + `chart.umd.min.js` para renderizar PowerPoint con diapositivas reales y swipe. |
| | `assets/viewer/jszip.min.js` | Dependencia de descompresión OpenXML en cliente. |
| **Layouts Clave** | `res/layout/activity_detail.xml` | Pantalla de detalle de actividad (tarjetas de adjuntos, entrega, retroalimentación). |
| | `res/layout/activity_file_viewer.xml` | Layout del visor con Toolbar, `RecyclerView` PDF, `WebView` Office y `ImageView`. |
| | `res/layout/item_attachment.xml` | Ítem para cada archivo adjunto con botones "Ver en línea" y "Descargar". |

---

## 🗺️ 2b. ARCHIVOS DEL MÓDULO AVISOS (creados en sesión Nov 2026)

| Componente | Archivo | Responsabilidad |
|---|---|---|
| **Modelos** | `data/model/ExamItem.kt` | Examen: `idExamen`, `titulo`, `valor`, fechas, `calificacion`, vars mutables `idCurso`/`nombreCurso`. Props: `estaCalificado`, `estaVencido`. |
| | `data/model/ForumItem.kt` | Foro: `idForo`, `titulo`, `descripcion`, `totalComentarios`, `nuevosComentarios`, `calificacion`, vars mutables `idCurso`/`nombreCurso`. |
| | `data/model/MessageItem.kt` | Item en lista de mensajes: `idMensaje`, `asunto`, `remitente`, `fecha`, `leido`, `tieneAdjuntos`. |
| | `data/model/MessageDetail.kt` | Detalle completo + `MessageSender`, `MessageRecipient`, `MessageAttachment`. Método `getDownloadUrl(idCurso, idMensaje)`. |
| | `data/model/CourseActivityItem.kt` | Wrapper de `Activity` con `courseId`, `courseName`, `courseColor`. |
| **API** | `data/remote/eminus/ExamsApi.kt`, `ForumsApi.kt`, `MessagesApi.kt` | Exámenes, foros y mensajes (antes en el `EminusApiClient` ya borrado). |
| **Pantallas** | `ui/courses/AllActivitiesActivity.kt` | Actividades de todos los cursos activos. Filtros: Todas/Pendientes/Entregadas/Calificadas. Click → `ActivityDetailActivity`. |
| | `ui/exams/ExamsActivity.kt` | Exámenes de todos los cursos con exámenes. Filtros: Todos/Disponibles/Calificados. Click → dialog informativo. |
| | `ui/forums/ForumsActivity.kt` | Foros de todos los cursos con foros. Filtros: Todos/Con aportes nuevos. Badge de nuevos comentarios. |
| | `ui/messages/MessagesActivity.kt` | Bandeja de mensajes (Recibidos/Enviados). `MessageAdapter` decodifica HTML entities con `HtmlCompat`. |
| | `ui/messages/MessageDetailActivity.kt` | Detalle de mensaje con cuerpo HTML, adjuntos con botones Descargar/Ver. |
| **Credencial UV** | `data/model/StudentCredential.kt` | Modelo de la credencial: matrícula, nombre, apellidos, carrera, campus, vigencia, foto, qrCode y barCode. |
| | `util/BarcodeGenerator.kt` | Generador offline de QR (512x512) y Código de barras Code 128 usando ZXing core nativo. |
| | `ui/profile/CredentialFullscreenActivity.kt` | Pantalla completa de la credencial con brillo al 100%, reloj en vivo y layout institucional. |
| | `res/layout/activity_credential_fullscreen.xml` | Layout con tarjeta oficial UV, degradado verde, QR y barras para escáneres. |

---

## 🌐 3. ARQUITECTURA DE APIS Y ENDPOINTS DE EMINUS 4

Eminus 4 no tiene una sola API; está dividido en **dos backends y un servidor de archivos**:

### 3.1. Las Tres URLs Base
1. **API v8:** `https://eminus.uv.mx/eminusapi8/api/` (`Constants.API_BASE_URL`)
2. **API Global:** `https://eminus.uv.mx/eminusapi/api/` (`Constants.API_GLOBAL_URL`)
3. **Repositorio Estático Público:** `https://eminus.uv.mx/eminusapi/` (sin auth requerida)
   - Fotos de perfil: `https://eminus.uv.mx/eminusapi/profiles/{avatarHash}.png`
   - Portadas de cursos: `https://eminus.uv.mx/eminusapi/thumbnail/{ruta}`
   - Sellos/stickers Froala: `https://eminus.uv.mx/eminusapi/froala/...`

### 3.2. Tabla de Endpoints Comprobados

| Acción | Método | URL Base | Endpoint | Auth | Notas |
|---|---|---|---|---|---|
| Lista de Cursos | GET | v8 | `Course/getAllCourses` | Bearer | Array de cursos inscritos. |
| Actividades de Curso | GET | Global | `Actividad/getActividadesEstudiante/{idCurso}` | Bearer | **Nota:** No usar `Activity/getActivities` de v8 (da 400). |
| Detalle de Actividad | GET | Global | `Actividad/getActividadEstudiante/{idCurso}/{idActividad}` | Bearer | Retorna instrucciones y `archivosActsConTamano` del profesor. |
| Estado de Entrega Alumno | GET | v8 | `Activity/getActUsuarioRevision/{idCurso}/{idActividad}/{idUsuario}` | Bearer | **Clave:** Retorna `archivosEntregaConTamano`, calificación y feedback docente. |
| Descargar Adjunto Profesor | GET | Global | `actividad/descargaActividadArchivo/{idCurso}/{idActividad}/{idArchivo}` | Bearer | Descarga binaria con token. |
| Descargar Archivo Entregado | GET | Global | `actividad/descargaEntregaArchivo/{idCurso}/{idActividad}/{idArchivosEntregas}` | Bearer | Descarga binaria de lo que entregó el alumno. |
| Notificaciones | GET | Global | `Global/NotificacionesUsuario` | Bearer | Contadores de mensajes, tareas, foros. |
| Perfil de Usuario | GET | Global | `Usuario/getUsuario` | Bearer | Datos personales, correo, campus. |
| Crear borrador / Confirmar Entrega | POST | Global | `Actividad/InsertaActividadEntrega` | Bearer | Mismo endpoint para borrador (`visible=0`) y envío (`visible=1`). Ver 5b. |
| Quitar Archivo de Entrega | PUT | Global | `actividad/eliminaArchivoEntrega` | Bearer | JSON `{idArchivosEntregas, idcurso}` (sic, `idcurso` en minúsculas). Responde 200 con cuerpo vacío. Validado en vivo. |
| Archivar / Desarchivar Curso | PUT | Global | `Cursos/ActualizaArchivado` | Bearer | JSON `{idCurso, archivado: 1|0}`. Igual que el botón de Eminus web (`onArchiveClick`, chunk de la lista de cursos). `Course/getAllCourses` ya trae `curso.archivado`. Pendiente validar en vivo. |
| Destinatarios por Curso | GET | Global | `centromensajes/getDestinatariosPorCurso/{idCurso}` | Bearer | `{ destinatarios: [ {idUsuario, nombre, correo, …} ] }`. Esos objetos se mandan tal cual al enviar. |
| Enviar Mensaje | POST | Global | `centromensajes/insertaActualizaMensaje` | Bearer | **Validado en vivo.** 2 pasos (Eminus web `onSaveMessage`): (1) `{idMensaje:0, asunto:null, mensaje:null, destinatarios:null, destinatarioscc:null, idMensajeCarpeta:0, idMensajePadre}` → devuelve idMensaje; (2) mismo con datos y `idMensajeCarpeta:1` (4 = borrador). Si (2) falla: `PUT centromensajes/EliminaMensajeBorrador {idMensaje}`. |
| Detalle de Foro | GET | Global | `Foro/getForo/{idCurso}/{idForo}` | Bearer | `descripcion`, `estado`, `fechaInicio/Termino/Prorroga`, `totalComentariosRaiz`. |
| Comentarios de Foro | GET | Global | `Foro/getComentariosForo/{idCurso}/{idForo}` | Bearer | Lista de raíz con `respuestas`; campos `idComentarioForo, idPadre, nombreCompleto, ruta, descripcion(HTML), fechaPublicacion, likes, tieneLike("Si"/"No"), totalRespuestas, archivoForoConTamano`. Más: `getMasComentarios/{c}/{f}`, `getMasRespuestas/{c}/{f}/{idComentario}`. |
| Publicar en Foro | POST | Global | `Foro/InsertaComentarioForo` | Bearer | JSON `{IdComentarioForo:0, Descripcion:<html>, IdPadre:0|idRaíz, IdCurso, IdForo}` (igual que Eminus web, chunk 17 `prepareComment`). |
| Me gusta en Foro | PUT | Global | `Foro/insertaComentarioLike` | Bearer | JSON `{IdComentarioForo, IdCurso}`; alterna. |
| Subir Adjunto de Entrega | POST | Global | `actividad/adjuntarEntrega?idCurso={c}&idActividad={a}&idActividadEntregas={e}` | Bearer | Multipart con clave `file`. Requiere `idActividadEntrega` > 0. 413 = archivo muy grande. |
| **Lista de Exámenes** | GET | Global | `Examen/getExamenesEst/{idCurso}` | Bearer | 200 con array o 204 sin exámenes. Filtrar cursos con `isActivo() \|\| examenes > 0`. |
| **Lista de Foros** | GET | Global | `Foro/getForos/{idCurso}/0` | Bearer | 200 siempre (array vacío si no hay). Filtrar cursos con `isActivo() \|\| foros > 0`. |
| **Lista de Mensajes** | GET | Global | `centromensajes/GetListadoMensajes/{folderId}/0` | Bearer | folderId=1 Recibidos, folderId=2 Enviados. |
| **Detalle de Mensaje** | GET | Global | `centromensajes/GetMensajeDetalle/{idMensaje}` | Bearer | Incluye cuerpo HTML, remitente, lista de adjuntos. |
| **Descargar Adjunto Mensaje** | GET | Global | `centromensajes/descargaCentroMensajesArchivo/{idCurso}/{idMensaje}/{idArchivosMensajes}` | Bearer | idCurso viene de `remitente.idCurso` en el detalle. |

---

## 🎨 4. FUNCIONAMIENTO DEL VISOR DE DOCUMENTOS OFFICE (DISEÑO REAL)

Para evitar la apariencia de texto plano que el usuario descartó:

### DOCX (`docx_viewer.html`):
1. `FileViewerActivity` descarga el archivo a `cacheDir/temp_docs/{nombre}`.
2. Lee los bytes y los codifica en Base64 (`Base64.NO_WRAP`).
3. Carga `file:///android_asset/viewer/docx_viewer.html` en `webOfficeViewer`.
4. En `onPageFinished`, invoca `renderDocxBase64(base64Data)`.
5. `docx-preview.min.js` decodifica a `ArrayBuffer` y renderiza el documento completo con CSS de hoja de papel, respetando fuentes, alineación, tablas e imágenes incrustadas.

### PPTX (`pptx_viewer.html`) — rediseñado v2.12.0:
- Ya no es "una diapositiva + botones": todas las diapositivas en lista vertical a todo el ancho (como PDF), con contador flotante "3 / 30" y zoom con dos dedos. Se dibujan una por una en un solo `<canvas>` (`visor.renderSlide(i, lienzo)`, ancho = pantalla × dpr × 1.5, mín. 1200 px) y se guardan como JPEG `blob:` en `<img>` (poca memoria). Proporción real con `processor.getSlideDimensions()`.
- Botón "Girar pantalla" en la barra del visor (`action_girar`, para cualquier archivo): en horizontal se ocultan la barra y las barras del sistema, el contenido se suelta del `ScrollingViewBehavior`, cada diapositiva cabe completa (`height: 100vh`) con `scroll-snap`; Atrás regresa a vertical. Al girar se redibuja con más resolución.

### PPTX (`pptx_viewer.html`):
1. `FileViewerActivity` lee los bytes del PPTX en Base64.
2. Carga `file:///android_asset/viewer/pptx_viewer.html`.
3. Requiere `jszip.min.js`, `chart.umd.min.js` y `PptxViewJS.min.js`.
4. En `onPageFinished`, invoca `renderPptxBase64(base64Data)`.
5. `PptxViewJS` crea una instancia `PPTXViewer` sobre un `<canvas>` HTML5.
6. Permite navegar entre diapositivas completas con botones "◀ Anterior" / "Siguiente ▶" y gestos táctiles de deslizamiento (swipe horizontal).

---

## 🔒 5. REGLAS DE NEGOCIO: CIERRE DE ACTIVIDADES

En `ActivityDetail.kt` la propiedad calculada `estaCerrada` gobierna la interfaz:
```kotlin
val estaCerrada: Boolean
    get() {
        if (calificacion != null) return true // Ya calificada
        if (estado == 2 || estado == 3 || estadoEntrega == 2 || estadoEntrega == 3) return true
        val fechaTerminoMillis = parseIsoToMillis(fechaTermino) ?: return false
        return System.currentTimeMillis() > fechaTerminoMillis
    }
```
- **Si `estaCerrada == true`:**
  - Se ocultan `btnSelectFile`, `btnSubmitDelivery` y `layoutSelectedFile`.
  - Se hace visible `layoutClosedNotice` con título y descripción contextual (verde si fue calificada con su nota, rojo si venció el plazo).
- **Si `estaCerrada == false`:**
  - Se habilitan los botones de selección y entrega.
  - El botón adapta su texto dinámicamente: "Seleccionar archivo para entrega…" (si es nueva) o "Modificar archivo de entrega…" (si ya tiene entrega previa).

- Valores observados en vivo: `estadoAct` 1 = cerrada, 2 = abierta. `estado`/`idEstado` 0 = sin enviar (borrador), 1 = enviada sin revisar, 3 = calificada. `porEquipos` llega en el detalle.
- Si la API envía `estadoAct`, **manda sobre la fecha** (regla de Eminus web): solo se entrega si `estadoAct` es 2 (abierta) o 4 (prórroga). `estado == 3` = calificada.
- Actividades `porEquipos == 1` se bloquean en la app (no manejamos `idEquipo`; se entregan desde Eminus web).

---

## 📤 5b. FLUJO DE ENTREGA (extraído del bundle web `eminus4` v1.0.37)

1. **Verificación:** se vuelve a pedir `Actividad/getActividadEstudiante/{c}/{a}` y se revisa `estaCerrada` / `esPorEquipos`.
2. **Borrador (solo si no hay entrega, `idActividadEntrega == 0`):** `POST Actividad/InsertaActividadEntrega` con
   `{idActividadEntrega:0, texto:"", idActividad, idCurso, visible:0, tieneAdjuntos:0, idEquipo:0, fechaEntrega:<ISO ahora>}` → responde `{"salida": <idActividadEntrega>}`.
3. **Archivo:** `POST actividad/adjuntarEntrega?idCurso&idActividad&idActividadEntregas=<id>` multipart `file`. Los archivos se **agregan** a la entrega (no reemplazan).
4. **Envío:** `POST Actividad/InsertaActividadEntrega` con `{idActividadEntrega:<id>, texto:<HTML>, idActividad, idCurso, visible:1, tieneAdjuntos:1, idEquipo:0, fechaEntrega:null}`.
   - `texto` es obligatorio en el web (editor Froala). Si el alumno no modifica el comentario precargado se reenvía el `descEntrega` HTML original (conserva enlaces); si escribe otro, se manda el suyo; si no hay nada, `<p>Entrega: nombre</p>`.
   - Respuestas reales: `adjuntarEntrega` → `[{"estado":"El archivo se adjuntó","idArchivosEntregas":…,"idActividadEntrega":…}]`; envío → `{"fechaEntrega":"…","estado":0,"salida":""}` (ese `estado:0` NO significa borrador: el `idEstado` real sigue en 1).
- Código: `EminusApiClient.createDeliveryDraft / uploadDeliveryFile / submitDelivery`.
- **Modo edición (UX):** si la entrega ya fue enviada (`estaEntregada`), los controles quedan ocultos tras el botón **"Editar entrega"** (`modoEdicion`). Al editar aparece un aviso (`txtEditHint`), los controles y "Cancelar edición". Tras enviar o al cambiar de actividad vuelve a solo lectura. Lógica centralizada en `actualizarControlesEntrega()`. Si no hay entrega, los controles se muestran directo.
- **Quitar archivo:** botón "Quitar" en cada archivo de "Tu entrega" (`item_attachment.xml` → `btnDeleteAttachment`), visible solo si `puedeModificarEntrega()` (actividad abierta, individual y, si ya está entregada, en modo edición). Revalida la actividad y llama `EminusApiClient.deleteDeliveryFile`. No reenvía la entrega (igual que el web).

---

## 🛠️ 6. COMANDOS ÚTILES PARA EL ENTORNO (COPIAR Y PEGAR)

Ruta del ejecutable ADB en la máquina del usuario:
`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`

```powershell
# Compilar e instalar rápidamente en el dispositivo Xiaomi/Poco (d79c00ce)
cd BodrioNus-Native
.\gradlew assembleDebug 2>&1 | Select-Object -Last 4; & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"

# Tomar screenshot y descargar para inspección visual
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell screencap -p /sdcard/screen.png
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pull /sdcard/screen.png screen_check.png

# Ver logs de JavaScript en WebView de Office
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -d -s chromium

# Ver si el dispositivo sigue conectado
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices

# Ver respuestas de la API (incluye los 3 pasos de entrega)
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s EMINUS_API
```

Organización local (ignorada por git): scripts de ingeniería inversa en `tools/reverse-engineering/`, capturas en `screenshots/`.
