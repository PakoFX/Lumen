package com.pako.eminus4.di

import android.content.Context
import com.pako.eminus4.core.Constants
import com.pako.eminus4.data.local.SessionStore
import com.pako.eminus4.data.remote.eminus.EminusClientFactory
import com.pako.eminus4.data.remote.eminus.AccountApi
import com.pako.eminus4.data.remote.eminus.ActivitiesApi
import com.pako.eminus4.data.remote.eminus.CourseSectionsApi
import com.pako.eminus4.data.remote.eminus.CoursesApi
import com.pako.eminus4.data.remote.eminus.EminusHttp
import com.pako.eminus4.data.remote.eminus.ExamsApi
import com.pako.eminus4.data.remote.eminus.ForumsApi
import com.pako.eminus4.data.remote.eminus.JsonMapper
import com.pako.eminus4.data.remote.eminus.MessagesApi
import com.pako.eminus4.data.repository.AgendaRepository
import com.pako.eminus4.data.repository.AuthRepository
import com.pako.eminus4.data.repository.CourseRepository
import com.pako.eminus4.data.repository.FileRepository
import com.pako.eminus4.data.repository.DeliveryRepository
import com.pako.eminus4.data.repository.ProfileRepository
import com.pako.eminus4.data.repository.CourseSectionsRepository
import com.pako.eminus4.data.repository.CredentialRepository
import com.pako.eminus4.data.repository.ExamRepository
import com.pako.eminus4.data.repository.ForumRepository
import com.pako.eminus4.data.repository.MessageRepository
import com.pako.eminus4.data.repository.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Inyección de dependencias manual: un solo lugar donde se crean los objetos compartidos
 * (clientes HTTP, almacenamiento, repositorios). Vive en [com.pako.eminus4.EminusApp].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Trabajo que debe terminar aunque se cierre la pantalla que lo pidió (p. ej. horas de término). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sessionStore: SessionStore by lazy { SessionStore(appContext) }

    val apiClient: OkHttpClient by lazy { EminusClientFactory.apiClient { sessionStore.getAccessToken() } }

    val uploadClient: OkHttpClient by lazy { EminusClientFactory.uploadClient(apiClient) }

    val imageClient: OkHttpClient by lazy { EminusClientFactory.imageClient { sessionStore.getAccessToken() } }

    val eminusHttp: EminusHttp by lazy { EminusHttp(apiClient, Constants.API_GLOBAL_URL, uploadClient) }

    val json: JsonMapper by lazy { JsonMapper() }

    val courseRepository: CourseRepository by lazy { CourseRepository(CoursesApi(eminusHttp, json), sessionStore) }

    val examRepository: ExamRepository by lazy { ExamRepository(ExamsApi(eminusHttp, json), courseRepository) }

    val forumRepository: ForumRepository by lazy { ForumRepository(ForumsApi(eminusHttp, json), courseRepository) }

    val messageRepository: MessageRepository by lazy { MessageRepository(MessagesApi(eminusHttp, json)) }

    val courseSectionsRepository: CourseSectionsRepository by lazy {
        CourseSectionsRepository(CourseSectionsApi(eminusHttp, json), ActivitiesApi(eminusHttp, json))
    }

    val agendaRepository: AgendaRepository by lazy {
        AgendaRepository(courseRepository, ActivitiesApi(eminusHttp, json), ExamsApi(eminusHttp, json), ForumsApi(eminusHttp, json), messageRepository)
    }

    val profileRepository: ProfileRepository by lazy { ProfileRepository(AccountApi(eminusHttp, json), sessionStore) }

    val deliveryRepository: DeliveryRepository by lazy { DeliveryRepository(ActivitiesApi(eminusHttp, json)) }

    val fileRepository: FileRepository by lazy { FileRepository(eminusHttp, appContext.cacheDir) }

    val authRepository: AuthRepository by lazy { AuthRepository(sessionStore, profileRepository, agendaRepository) }

    val scheduleRepository: ScheduleRepository by lazy { ScheduleRepository(appContext, sessionStore, applicationScope) }

    val credentialRepository: CredentialRepository by lazy { CredentialRepository(appContext, sessionStore) }
}
