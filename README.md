# GlobalFitness

App Android (Kotlin + Jetpack Compose, Material You) para seguir tu **peso y composición corporal**
con básculas Bluetooth y tu **actividad, sueño y pulso** desde Health Connect o directamente desde una
Xiaomi Smart Band 9. Interfaz en 17 idiomas (inglés por defecto; sigue el idioma del sistema, ver
`docs/translations.md`), un solo perfil, y los datos se quedan en el teléfono salvo que actives la copia
en la nube.

## Funciones

- **Básculas Bluetooth (66 modelos)**: catálogo con detección automática. Al subirte a una báscula
  compatible con la app abierta, se reconoce: las de solo anuncios se configuran solas y se leen de forma
  pasiva; las que necesitan conexión (GATT) se proponen con «¿Es tu báscula?» (en *Hoy* y en
  *Dispositivos*) y solo se conectan cuando confirmas que es la tuya. También puedes elegirla en
  *Dispositivos → Añadir desde el catálogo*. Solo la Xiaomi Mi Body Composition Scale 2 está verificada
  con hardware real; el resto lleva la etiqueta **Experimental**.
- **Composición corporal**: IMC, grasa, masa grasa, músculo, agua, hueso, grasa visceral, metabolismo
  basal, edad metabólica, proteína, peso ideal y tipo de cuerpo, y músculo esquelético cuando la báscula
  o su fórmula lo estima. Se usan los valores que da la propia
  báscula y, si no los da, las fórmulas publicadas para cada familia (Mi Fit, Yunmai, Trisa…).
- **Gráficos**: media móvil de 7 días, rango normal, peso objetivo, ritmo semanal y proyección; historial
  agrupado por meses y peso manual.
- **Health Connect**: pasos, distancia, calorías, pulso (día y 30 días), sueño con hipnograma y calidad,
  oxígeno en sangre y entrenamientos de tu pulsera o reloj (p. ej. Mi Fitness). Opcionalmente envía tus
  mediciones de báscula a Health Connect.
- **Xiaomi Smart Band 9, modo directo (experimental)**: conexión Bluetooth sin Mi Fitness para pulso en
  directo, buscar la pulsera, alarmas y reenvío de notificaciones. El pulso registrado aparece también
  en *Actividad*.
- **Copia en la nube (opcional)**: inicio de sesión con Google y copia del perfil, el historial (con los
  datos de la báscula) y las básculas configuradas (sin claves ni direcciones) en Firebase; restauración
  al reinstalar o cambiar de móvil (también desde la bienvenida).
- **Anuncios y «Quitar anuncios»**: banner y un intersticial con límite de frecuencia (AdMob, con
  consentimiento UMP en la UE); nunca durante la bienvenida, el inicio de sesión, la compra o una
  medición. Pago único con Google Play Billing para quitarlos.
- **Exportar / importar CSV** (separador `;`, decimales con coma).

## Navegación

Cinco secciones en la barra inferior:

| Sección | Contenido |
|---|---|
| **Hoy** | Anillos de pasos, sueño y pulso en reposo (Health Connect), «¿Es tu báscula?» si hay una nueva cerca, medición en curso, último peso con tendencia de 30 días, composición destacada y estado de la báscula, la nube y la pulsera. |
| **Cuerpo** | Gráfico por métrica y periodo, resumen, proyección, composición e historial por meses (detalle y borrado). Botón «Añadir peso manual». |
| **Actividad** | Datos de Health Connect y sincronización. |
| **Dispositivos** | Básculas (configuradas, detectadas, catálogo), pulsera en modo directo, Health Connect y diagnóstico BLE. |
| **Perfil** | Perfil, cuenta y copia en la nube, quitar anuncios, CSV, envío a Health Connect, política de privacidad y avisos de código abierto. |

La primera vez se muestra una bienvenida: restaurar desde la nube o continuar sin cuenta, perfil,
permisos (Bluetooth y Health Connect) y listo.

## Compilar

Requisitos: JDK 17 y Android SDK (plataforma 36). No hace falta Android Studio.

1. Crea `local.properties` con la ruta del SDK (en Windows hay que escapar los dos puntos):

   ```properties
   sdk.dir=C\:/Users/<usuario>/AppData/Local/Android/Sdk
   ```

2. Copia tu `app/google-services.json` de Firebase (ver más abajo). Sin él el plugin de Google Services
   no puede compilar.

3. Compila, prueba y pasa lint:

   ```bat
   gradlew assembleDebug testDebugUnitTest lintDebug
   ```

El APK queda en `app\build\outputs\apk\debug\app-debug.apk`.

### Versión de publicación (Google Play)

```bat
gradlew assembleRelease bundleRelease
```

- **Firma**: la clave de subida (*upload key*) está fuera del repositorio. `app/build.gradle.kts` lee
  `GLOBALFITNESS_UPLOAD_STORE_FILE`, `GLOBALFITNESS_UPLOAD_STORE_PASSWORD`, `GLOBALFITNESS_UPLOAD_KEY_ALIAS`
  y `GLOBALFITNESS_UPLOAD_KEY_PASSWORD` de `%USERPROFILE%\.gradle\gradle.properties`; si faltan, la
  compilación de release sale **sin firmar** (con un aviso de Gradle).
- **R8**: la release se minimiza y reduce recursos (`isMinifyEnabled`, `isShrinkResources`); las reglas
  propias están en `app/proguard-rules.pro`. El `.aab` ya lleva el `mapping.txt` de R8, con el que Play
  traduce los informes de fallos (para el APK está en `app/build/outputs/mapping/release/`).
- **Versiones**: `versionName` y el número de compilación están en `gradle.properties`
  (`globalfitness.versionName`, `globalfitness.buildNumber`); `versionCode = 1000 + buildNumber`. Sube
  `buildNumber` en 1 para cada subida a Play (también con `-Pglobalfitness.buildNumber=N`) y etiqueta el
  commit publicado (`v1.0.0`, …). Primera versión: 1.0.0 (versionCode 1001).
- Salidas: `app\build\outputs\bundle\release\app-release.aab` (para Play) y
  `app\build\outputs\apk\release\app-release.apk`; se copian a `dist\` (ignorado por git).

Pasos de publicación: [`docs/play-release.md`](docs/play-release.md).

## Instalar

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

O copia el APK al teléfono y ábrelo (hay que permitir «instalar apps desconocidas»). Requiere Android 8.0
o superior; Health Connect necesita Android 9 o superior (en Android 13 o anterior, la app de Health
Connect de Google Play).

## Configuración de servicios

- **Firebase** (cuenta y copia en la nube): crea un proyecto, añade la app Android
  `com.enjoyyourtime.globalfitness` con las huellas SHA-1/SHA-256 de tus claves de firma, activa
  *Authentication → Google* y *Cloud Firestore* (región UE) y descarga `google-services.json` a `app/`.
  Las reglas de seguridad están en `firestore.rules` (`firebase deploy --only firestore:rules`).
- **AdMob**: las compilaciones de depuración usan siempre los identificadores de prueba de Google. Para
  la versión publicada define `ADMOB_APP_ID`, `ADMOB_BANNER_ID` y `ADMOB_INTERSTITIAL_ID` como
  propiedades de Gradle (`-P…` o `~/.gradle/gradle.properties`) o en `local.properties`. Configura el
  mensaje de consentimiento (UMP) en la consola de AdMob.
- **Google Play**: crea en Play Console el producto de compra única `remove_ads` («Quitar anuncios») y
  completa las declaraciones de *Seguridad de los datos* y de Health Connect (pasos en
  `docs/play-release.md`). Con la firma de apps de Play, añade también en Firebase las huellas de la
  clave de firma de Play.
- **Páginas públicas** (Firebase Hosting, `hosting/public/`): inicio, política de privacidad
  (`privacy.html`), borrado de cuenta (`delete-account.html`, la URL que pide Play) y código fuente
  (`source.html`). Prueba local con `firebase emulators:start --only hosting` y publicación con
  `firebase deploy --only hosting` → `https://globalfitness-app.web.app/`.
- **Política de privacidad**: `docs/privacy-policy.md` es la fuente (en español e inglés); `privacy.html`
  debe mantenerse igual. `docs/privacy-policy.en.md` es la traducción completa al inglés. En español la
  app abre la versión web (*Perfil → Política de privacidad*) y, sin conexión, la copia incluida en la
  app; en los demás idiomas muestra la copia en inglés incluida en la app. Faltan los datos del
  responsable (marcados `[COMPLETAR: …]`).

## Herramientas de depuración

Solo en compilaciones de depuración (protegidas con el permiso `DUMP`, es decir, solo `adb`):

```bat
:: Historial de peso de ejemplo para el perfil actual
adb shell am broadcast -n com.enjoyyourtime.globalfitness/.debug.DebugMeasurementSeedReceiver --ei days 120
:: Datos de pulsera de ejemplo en Health Connect (ver DebugHealthSeedReceiver)
adb shell am broadcast -n com.enjoyyourtime.globalfitness/.health.debug.DebugHealthSeedReceiver --ei days 10
```

## Limitaciones conocidas

- Las básculas solo se leen **con la app abierta en primer plano** (no hay servicio en segundo plano) y no
  se sincroniza el historial interno que algunas básculas guardan sin teléfono cerca.
- Sin impedancia (calcetines, pies secos o muy fríos) se guarda solo el peso.
- Las métricas son **estimaciones**, no un diagnóstico médico.
- El modo directo de la pulsera es experimental: mientras está abierto, Mi Fitness no puede sincronizar.

## Licencia

GlobalFitness es **software libre** bajo la **GNU Affero General Public License v3.0 o posterior**
(`AGPL-3.0-or-later`): texto completo en [`LICENSE`](LICENSE), atribuciones en [`NOTICE`](NOTICE).
Se distribuye sin ninguna garantía.

- **Código portado**: los protocolos de las básculas y sus fórmulas de composición corporal vienen de
  [openScale](https://github.com/oliexdev/openScale) (**GPL-3.0-or-later**, © olie.xdev y colaboradores;
  `scale/drivers/`, `scale/libs/` y `domain/BodyMetrics.kt`), y el protocolo de la Xiaomi Smart Band de
  [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (**AGPL-3.0-or-later**, © colaboradores
  de Gadgetbridge; `band/xiaomi/` y `src/main/proto/xiaomi.proto`). La GPL-3.0 (sección 13) permite
  combinarlo con código AGPL-3.0; el conjunto se distribuye bajo la AGPL-3.0.
- **Código fuente para los usuarios**: la app enlaza el repositorio en *Perfil → Acerca de → Código fuente
  (AGPL-3.0)* y muestra la licencia, la ausencia de garantía y los avisos en *Perfil → Código abierto y
  licencias*. Cada versión publicada debe tener su etiqueta en el repositorio (`v1.0.0`, …) para que el
  código corresponda exactamente al binario distribuido.
- **Cabeceras**: los ficheros sin cabecera quedan cubiertos por `LICENSE`. Los ficheros nuevos pueden
  empezar por `// SPDX-License-Identifier: AGPL-3.0-or-later`; los que contienen código portado conservan
  la cabecera que cita su proyecto de origen.
- Las bibliotecas que usa la app (AndroidX, Kotlin, Protocol Buffers, Firebase, Google Mobile Ads, Play
  Billing…) mantienen sus propias licencias; ver `NOTICE`.
