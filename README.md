# GlobalFitness

App Android de uso personal que escucha **de forma pasiva** los anuncios Bluetooth LE de la báscula
**Xiaomi Mi Body Composition Scale 2 (XMTZC05HM)**, guarda las mediciones por perfil, calcula la
composición corporal y muestra un panel con gráficos.

- Sin cuenta, sin nube y sin emparejar: la báscula emite el peso y la impedancia en sus anuncios
  (servicio `0x181B`) y la app solo los escucha.
- Varios perfiles con asignación automática por peso (y diálogo «¿De quién es esta medición?» si hay duda).
- Métricas: IMC, grasa %, masa grasa, músculo, agua, hueso, grasa visceral, metabolismo basal,
  edad metabólica, proteína, peso ideal y tipo de cuerpo (fórmulas de Mi Fit obtenidas por ingeniería inversa).
- Gráficos con media móvil de 7 días, rango normal, objetivo, ritmo semanal y proyección.
- Exportar / importar CSV (separador `;`, decimales con coma).

## Compilar

Requisitos: JDK 17 y Android SDK (plataforma 36). No hace falta Android Studio.

```bat
gradlew assembleDebug
gradlew testDebugUnitTest
```

El APK queda en `app\build\outputs\apk\debug\app-debug.apk` (copia lista en `dist\GlobalFitness-debug.apk`).
Si `local.properties` no apunta a tu SDK, edita `sdk.dir` o define `ANDROID_HOME`.

## Instalar

```bat
adb install -r dist\GlobalFitness-debug.apk
```

O copia el APK al teléfono y ábrelo (hay que permitir «instalar apps desconocidas»). Requiere Android 8.0 o superior.

## Primer uso

1. Abre la app y crea tu perfil (nombre, sexo, fecha de nacimiento, altura y, si quieres, peso objetivo).
2. Concede el permiso de **Dispositivos cercanos** (Android 12+) o de **Ubicación** (Android 11 o anterior;
   en esas versiones la ubicación del sistema también debe estar activada). Activa el Bluetooth.
3. Crea los perfiles del resto de la familia en **Perfiles**.
4. Con la app **abierta en primer plano**, súbete a la báscula **descalzo y con los pies secos**.
   Verás el peso en directo, luego «Estable – midiendo impedancia…» y por último «Guardado ✓ para …».
5. Opcional: en **Perfiles → Báscula vinculada** pulsa «Vincular última detectada» para ignorar básculas de vecinos.

## Limitaciones conocidas

- Solo captura mediciones **mientras la app está abierta en primer plano** (no hay servicio en segundo plano).
- No sincroniza el historial interno que la báscula guarda cuando no hay ningún teléfono escuchando.
- Si la báscula no consigue medir la impedancia (calcetines, pies secos o muy fríos), se guarda solo el peso.
- Las métricas son **estimaciones** basadas en fórmulas de Xiaomi obtenidas por ingeniería inversa
  (las mismas que usa openScale). No son un diagnóstico médico.
- El reloj interno de la báscula puede ir mal; la app usa la hora del teléfono para las mediciones.
