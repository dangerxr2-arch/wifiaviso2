# Wifi Aviso

App Android (Kotlin) que vigila la señal del Wi-Fi y muestra una notificación
emergente cuando es baja y hay otro router más fuerte cerca. Al tocarla se
abren los ajustes de Wi-Fi.

## Obtener el APK (sin instalar nada)

1. Crea un repositorio en GitHub y sube el contenido de esta carpeta.
2. Ve a la pestaña **Actions** > **Compilar APK** > **Run workflow**.
3. Cuando termine (unos 3-5 min), descarga **WifiAviso-apk** y descomprime `app-debug.apk`.
4. Cópialo al teléfono e instálalo (permite "instalar apps desconocidas").

## Alternativa: Android Studio

Abre la carpeta, espera a que sincronice y usa **Build > Build APK(s)**.

## Notas

- Necesita permiso de ubicación (Android lo exige para leer redes cercanas) y que la ubicación esté activada.
- Android limita los escaneos de Wi-Fi a unos 4 cada 2 minutos.
- Quita la optimización de batería para esta app o el sistema puede detenerla.
