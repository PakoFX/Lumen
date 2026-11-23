plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.pako.eminus4"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pako.eminus4"
        minSdk = 24
        targetSdk = 35
        versionCode = 22
        versionName = "2.12.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("common") {
            storeFile = file("bodrio.keystore")
            storePassword = "android"
            keyAlias = "bodrio"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("common")
        }
        release {
            signingConfig = signingConfigs.getByName("common")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.material)

    // Arquitectura: ViewModel + corrutinas (MVVM)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Red y Serialización JSON
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Carga de imágenes nativa y eficiente
    implementation(libs.coil)
    implementation(libs.coil.gif)   // GIF animado de fondo de la credencial

    // WebViewAssetLoader para servir archivos locales al WebView
    implementation(libs.androidx.webkit)

    // Generación offline de códigos QR y códigos de barras (Credencial)
    implementation("com.google.zxing:core:3.5.3")
    // Leer el PDF del horario de MiUV (trae la hora de término de cada clase)
    // Sin BouncyCastle (~11 MB): solo sirve para PDF cifrados y el de MiUV no lo está
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // org.json real para pruebas locales (en el JVM la de Android es solo un esqueleto)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}