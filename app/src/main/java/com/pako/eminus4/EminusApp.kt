package com.pako.eminus4

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import coil.Coil
import coil.ImageLoader
import com.pako.eminus4.di.AppContainer

class EminusApp : Application() {

    /** Dependencias compartidas de toda la app (inyección manual). */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applySavedTheme()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("visor_popup")
        }
        configureImageLoader()
    }

    /** El tema se aplica antes de que se cree cualquier pantalla. */
    private fun applySavedTheme() {
        AppCompatDelegate.setDefaultNightMode(
            if (container.sessionStore.isDarkModeEnabled()) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /** Coil usa el cliente con token y cookies: las miniaturas de Eminus requieren autenticación. */
    private fun configureImageLoader() {
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(container.imageClient)
                .crossfade(true)
                .build()
        )
    }
}

/** Acceso al contenedor desde cualquier Context (Activity, Fragment, Receiver). */
val Context.appContainer: AppContainer
    get() = (applicationContext as EminusApp).container
