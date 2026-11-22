# Lumen 🎓📱 (antes Bodrio-Nus)

Cliente móvil no oficial y optimizado para **Eminus 4** de la Universidad Veracruzana (UV), diseñado para transformar la experiencia de escritorio en una aplicación Android nativa, fluida y moderna.

![Android](https://img.shields.io/badge/Android-14%2B-green.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)
![UV](https://img.shields.io/badge/UV-Eminus%204-blue.svg)

---

> ⚠️ **Aviso:** Lumen es una app independiente y no oficial. No está afiliada, patrocinada ni respaldada por la Universidad Veracruzana. Eminus y MiUV son servicios de la UV.

## 🔒 Privacidad

- **Tu contraseña no se guarda.** Solo se usa para entrar a Eminus y MiUV y se borra de la memoria en cuanto termina.
- Tu sesión, credencial, foto, matrícula y horario se guardan **solo en tu celular**, dentro de la app. No se respaldan en la nube ni se copian a otro teléfono.
- La app solo se conecta a **Eminus y MiUV** (servidores de la UV) y a **GitHub** (para buscar actualizaciones). No hay servidor propio, publicidad ni estadísticas de uso.
- El **buzón de sugerencias** envía solo lo que escribes (y, si lo marcas, versión de la app y modelo del celular). Nunca tu nombre, matrícula ni datos de Eminus.
- **Borrar tus datos:** al cerrar sesión se borra todo lo de tu cuenta (sesión, credencial y foto, horario, recordatorios y las copias de archivos que abriste o subiste desde la app; lo guardado en Descargas no se toca); si la sesión solo caduca, no se borra nada. También puedes desinstalar la app o usar *Ajustes → Apps → Lumen → Borrar datos*.

---

## 🛠️ Tecnologías

- **Lenguaje:** Kotlin
- **Plataforma:** Android SDK (API 34 / Android 14 HyperOS)
- **Componentes:** AndroidX, Material Design Components, WebView, FileProvider
- **Estilos:** CSS3 adaptativo con soporte para selectores modernos (`:has()`, flexbox, touch-action)

---

## 📦 Compilación

Para compilar el APK de depuración:

```bash
./gradlew assembleDebug
```

El archivo generado estará disponible en:
`app/build/outputs/apk/debug/app-debug.apk`

---

Desarrollado para la comunidad universitaria de la UV.
