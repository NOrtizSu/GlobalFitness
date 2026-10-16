# Publicar GlobalFitness en Google Play

Lista de pasos para la primera versión (1.0.0, versionCode 1001) y las siguientes. Los datos que hay que
declarar salen del código actual; si cambia lo que la app recoge o envía, revisa las secciones 6 y 7.

Datos fijos:

| | |
|---|---|
| Paquete | `com.enjoyyourtime.globalfitness` |
| Desarrollador | Enjoy Your Time · enjoyyourtime.online@gmail.com |
| Web | https://globalfitness-app.web.app/ |
| Política de privacidad | https://globalfitness-app.web.app/privacy.html |
| Borrado de cuenta | https://globalfitness-app.web.app/delete-account.html |
| Código fuente (AGPL-3.0) | https://github.com/NOrtizSu/GlobalFitness |
| Firebase | proyecto `globalfitness-app`, app `1:1064750272721:android:85e513f9844a95e2a2b5f0` |
| Producto integrado | `remove_ads` («Quitar anuncios»), pago único |

## 0. Antes de empezar

1. **Datos del responsable**: `docs/privacy-policy.md` y `hosting/public/privacy.html` identifican al
   responsable como *Enjoy Your Time* con el email de contacto (decisión del titular: sin NIF ni
   dirección). Los dos ficheros deben decir lo mismo.
2. **Publica las páginas**: `firebase deploy --only hosting --project globalfitness-app` y comprueba que
   abren `privacy.html`, `delete-account.html` y `source.html`. (El botón «Google Play» de `index.html`
   apunta a la ficha `details?id=com.enjoyyourtime.globalfitness`, que solo funciona cuando la app es
   pública; los enlaces a GitHub, cuando el repositorio es público.)
3. **Código fuente (AGPL-3.0)**: haz público el repositorio de GitHub y etiqueta el commit publicado
   (`git tag v1.0.0 && git push origin v1.0.0`). Cada versión subida a Play debe tener su etiqueta: es el
   código fuente correspondiente que la licencia obliga a ofrecer a los usuarios.
4. **AdMob** (sección 10): crea la app y los bloques de anuncios y pon sus identificadores en
   `%USERPROFILE%\.gradle\gradle.properties` **antes** de compilar la versión que irá a producción. Sin
   ellos la release usa los identificadores de prueba de Google (vale para pruebas internas, no para
   producción).
5. **Compila** (con la clave de subida ya configurada, ver sección 9):

   ```bat
   gradlew assembleRelease bundleRelease
   copy app\build\outputs\bundle\release\app-release.aab dist\GlobalFitness-release.aab
   copy app\build\outputs\apk\release\app-release.apk dist\GlobalFitness-release.apk
   ```

   Para cada nueva subida, aumenta `globalfitness.buildNumber` en `gradle.properties`
   (versionCode = 1000 + buildNumber) y, si procede, `globalfitness.versionName`.

## 1. Crear la app en Play Console

1. Play Console → **Crear aplicación**.
2. Nombre: **GlobalFitness** · Idioma predeterminado: **Español (España) – es-ES** · Tipo: **Aplicación** ·
   **Gratis**.
3. Acepta las declaraciones (políticas del programa para desarrolladores y leyes de exportación de EE. UU.).
4. **Cuentas personales nuevas** (creadas después del 13-11-2023): Google exige una **prueba cerrada con al
   menos 12 testers durante 14 días seguidos** antes de poder pedir acceso a producción. Planifica la
   prueba interna (sección 11) y luego la cerrada.

## 2. Ficha de Play Store (Crecimiento → Presencia en Play Store → Ficha principal)

- **Categoría**: Aplicación → **Salud y bienestar** (Health & Fitness).
- **Etiquetas** sugeridas: *Monitor de peso*, *Seguimiento de actividad física*, *Salud*.
- **Datos de contacto**: correo enjoyyourtime.online@gmail.com · sitio web https://globalfitness-app.web.app/
- **Política de privacidad** (Contenido de la aplicación): https://globalfitness-app.web.app/privacy.html
- **Gráficos**: icono 512×512 PNG (sale del icono adaptativo `ic_launcher`), gráfico destacado 1024×500 y
  al menos 2 capturas de teléfono. Las capturas del emulador (1080×2400) superan la proporción máxima 2:1:
  recórtalas (por ejemplo a 1080×2160) o usa un dispositivo con pantalla 9:18.
- Añade la traducción **inglés (en-US)** de la ficha con los textos de abajo.

### Descripción breve (máx. 80 caracteres)

- es-ES: `Peso y composición corporal con tu báscula Bluetooth, más actividad y sueño.` (76)
- en-US: `Weight and body composition from your Bluetooth scale, plus activity and sleep.` (79)

### Descripción completa (es-ES, máx. 4000 caracteres)

```text
GlobalFitness reúne en una sola app tu peso, tu composición corporal y tu actividad diaria.

BÁSCULAS BLUETOOTH
• Catálogo de 66 modelos de básculas con detección automática: súbete a la báscula con la app abierta y se reconoce.
• Las básculas que solo emiten anuncios se configuran solas; las que necesitan conexión se proponen con «¿Es tu báscula?» y solo se conectan cuando confirmas que es la tuya.
• Verificada con la Xiaomi Mi Body Composition Scale 2; el resto de modelos se marca como experimental.

COMPOSICIÓN CORPORAL
• IMC, grasa corporal, masa grasa, músculo, músculo esquelético, agua, hueso, grasa visceral, metabolismo basal, edad metabólica, proteína, peso ideal y tipo de cuerpo.
• Usa los valores que da la propia báscula o, si no los da, las fórmulas publicadas para cada familia de básculas.
• Son estimaciones orientativas, no un diagnóstico médico.

GRÁFICOS E HISTORIAL
• Media móvil de 7 días, rango normal, peso objetivo, ritmo semanal y proyección.
• Historial por meses y peso manual cuando no tienes la báscula a mano.
• Exporta e importa tus mediciones en CSV.

ACTIVIDAD, SUEÑO Y PULSO CON HEALTH CONNECT
• Pasos, distancia, calorías, pulso, pulso en reposo, sueño con fases, oxígeno en sangre y entrenamientos de tu pulsera o reloj.
• Opcional: guarda las mediciones de tu báscula en Health Connect para usarlas en otras apps.

XIAOMI SMART BAND 9 EN MODO DIRECTO (EXPERIMENTAL)
• Conexión Bluetooth sin Mi Fitness: pulso en directo, buscar la pulsera, alarmas y reenvío de notificaciones.

TUS DATOS, BAJO TU CONTROL
• Funciona sin cuenta: tus datos se quedan en tu teléfono.
• Copia en la nube opcional con tu cuenta de Google (servidores en la UE), que puedes borrar desde la app cuando quieras.
• Tus datos de salud nunca se usan para publicidad ni se venden.

ANUNCIOS
La versión gratuita muestra anuncios. Con un pago único («Quitar anuncios») desaparecen para siempre, sin suscripción.

SOFTWARE LIBRE
GlobalFitness es software libre (licencia AGPL-3.0). Incluye código de los proyectos openScale y Gadgetbridge. Código fuente: https://github.com/NOrtizSu/GlobalFitness

La interfaz está en español. GlobalFitness no está afiliada a Xiaomi ni a los fabricantes de las básculas compatibles; las marcas citadas pertenecen a sus dueños.
```

### Full description (en-US, max. 4000 characters)

```text
GlobalFitness brings your weight, body composition and daily activity together in one app. The app interface is in Spanish.

BLUETOOTH SCALES
• A catalogue of 66 scale models with automatic detection: step on your scale with the app open and it is recognised.
• Broadcast-only scales set themselves up; scales that need a connection are suggested with "Is this your scale?" and only connect after you confirm.
• Verified with the Xiaomi Mi Body Composition Scale 2; other models are marked as experimental.

BODY COMPOSITION
• BMI, body fat, fat mass, muscle, skeletal muscle, water, bone, visceral fat, basal metabolism, metabolic age, protein, ideal weight and body type.
• Uses the values reported by the scale or, when it reports none, the published formulas for each scale family.
• These are estimates for guidance only, not a medical diagnosis.

CHARTS AND HISTORY
• 7-day moving average, normal range, target weight, weekly pace and projection.
• Monthly history and manual weight entries.
• CSV export and import.

ACTIVITY, SLEEP AND HEART RATE WITH HEALTH CONNECT
• Steps, distance, calories, heart rate, resting heart rate, sleep stages, blood oxygen and workouts from your band or watch.
• Optional: save your scale measurements to Health Connect to use them in other apps.

XIAOMI SMART BAND 9 DIRECT MODE (EXPERIMENTAL)
• Bluetooth connection without Mi Fitness: live heart rate, find your band, alarms and notification forwarding.

YOUR DATA, YOUR CONTROL
• Works without an account: your data stays on your phone.
• Optional cloud backup with your Google account (EU servers) that you can delete from the app at any time.
• Your health data is never used for advertising and never sold.

ADS
The free version shows ads. A one-time purchase ("Quitar anuncios") removes them for good, no subscription.

FREE SOFTWARE
GlobalFitness is free software (AGPL-3.0 license) and includes code from the openScale and Gadgetbridge projects. Source code: https://github.com/NOrtizSu/GlobalFitness

GlobalFitness is not affiliated with Xiaomi or with the makers of the supported scales; all trademarks belong to their owners.
```

## 3. Contenido de la aplicación (Política → Contenido de la aplicación)

### Política de privacidad
https://globalfitness-app.web.app/privacy.html

### Acceso a la aplicación
**Todas las funciones están disponibles sin acceso especial.** Nota para revisión (en inglés):

> No login is required. Google sign-in is optional and only enables the cloud backup. Scale and Xiaomi
> Smart Band features need the physical devices; without them, use Cuerpo → "Añadir peso manual" to add
> measurements. Health Connect data appears after granting its permissions in the onboarding or in
> Dispositivos → Health Connect.

### Anuncios
**Sí, mi aplicación contiene anuncios** (Google AdMob: banner e intersticial).

### ID de publicidad
**Sí**, la app usa el ID de publicidad (lo declara el SDK de Google Mobile Ads con
`com.google.android.gms.permission.AD_ID`). Finalidad: **Publicidad o marketing** (y analíticas/prevención
de fraude del propio SDK).

### Clasificación del contenido (cuestionario IARC)
- Correo de contacto: enjoyyourtime.online@gmail.com · Categoría: **Todos los demás tipos de aplicaciones**.
- Violencia, miedo, sexualidad, lenguaje soez, sustancias controladas, humor crudo: **No** en todo.
- Apuestas o juegos de azar simulados: **No**.
- ¿Los usuarios pueden interactuar o intercambiar contenido entre ellos?: **No**.
- ¿Comparte la ubicación del usuario con otros usuarios?: **No**.
- ¿Permite comprar productos digitales?: **Sí** (compra única «Quitar anuncios»).
- ¿Navegador web o motor de búsqueda sin restricciones?: **No** (los enlaces se abren en el navegador
  del sistema).
- Resultado esperado: PEGI 3 / Para todos.

### Público objetivo y contenido
- Edades objetivo: **18 años o más** (la política de privacidad dice que no está dirigida a menores de 16).
- ¿Puede atraer a niños sin querer?: **No**.

### Aplicaciones de salud (declaración de funciones de salud)
Marca: **Actividad física y bienestar** (seguimiento de actividad, sueño y pulso) y **Control del peso /
composición corporal**. No es un dispositivo médico ni ofrece diagnóstico o tratamiento.

### Aplicaciones de noticias, gubernamentales, financieras, COVID-19
**No** en todas.

### Seguridad de los datos
Ver sección 6.

### Permisos de Health Connect
Ver sección 7.

### Borrado de cuenta
URL: https://globalfitness-app.web.app/delete-account.html (también se borra desde la app: Perfil → Cuenta
y copia en la nube → «Borrar mis datos de la nube y la cuenta»).

## 4. Producto integrado `remove_ads`

Requiere haber subido antes un `.aab` con el permiso de facturación a cualquier pista (sección 11).

1. Monetizar → Productos → **Productos de compra única** (Productos integrados en la aplicación) → Crear.
2. ID del producto: **`remove_ads`** (exacto; la app lo busca por este ID).
3. Nombre: **Quitar anuncios** · Descripción: *Elimina para siempre los banners y los anuncios a pantalla
   completa. Pago único, sin suscripción.* (en-US: *Remove ads* · *Removes banners and full-screen ads for
   good. One-time payment, no subscription.*)
4. Precio: **2,99 €** (precio base en EUR; deja que Play calcule el resto de países).
5. Opción de compra: una sola, no consumible. **Activar** el producto.
6. La app reconoce la compra (acknowledge) al recibirla; no hace falta servidor.

## 5. Testers de licencias (compras de prueba)

1. Play Console (nivel de cuenta) → **Configuración → Pruebas de licencias**: añade las cuentas de Gmail de
   los testers (la tuya incluida) y deja la respuesta en **RESPOND_NORMALLY**.
2. Esas cuentas ven «Tarjeta de prueba, siempre se aprueba» al comprar `remove_ads` y no se les cobra.
3. Para probar de nuevo: Play Console → Pedidos → reembolsa/revoca el pedido de prueba, o desinstala y usa
   «Restaurar compras».

## 6. Seguridad de los datos (formulario)

Respuestas derivadas del código actual (cuenta y copia en Firebase opcionales; AdMob; Play Billing; sin
analíticas ni informes de fallos propios).

**Preguntas generales**
- ¿Recoge o comparte alguno de los tipos de datos obligatorios?: **Sí**.
- ¿Todos los datos recogidos se cifran en tránsito?: **Sí** (TLS en Firebase, AdMob y Play).
- ¿Ofreces una forma de solicitar la eliminación de los datos?: **Sí** — en la app y en
  https://globalfitness-app.web.app/delete-account.html

**Tipos de datos**

| Tipo (categoría Play) | ¿Recogido? | ¿Compartido? | Opcional | Finalidad | Origen en la app |
|---|---|---|---|---|---|
| Información personal → **Nombre** | Sí | No | Sí | Funcionalidad de la app, Gestión de la cuenta | Nombre del perfil (copia en la nube) y nombre/foto de la cuenta de Google (Firebase Auth) |
| Información personal → **Dirección de correo electrónico** | Sí | No | Sí | Gestión de la cuenta | Cuenta de Google en Firebase Authentication |
| Información personal → **ID de usuario** | Sí | No | Sí | Funcionalidad de la app, Gestión de la cuenta | UID de Firebase e ID aleatorio del conjunto de datos |
| Información personal → **Otra información** | Sí | No | Sí | Funcionalidad de la app | Sexo, fecha de nacimiento, altura y peso objetivo del perfil (copia en la nube) |
| Salud y forma física → **Información de salud** | Sí | No | Sí | Funcionalidad de la app | Mediciones (peso, impedancia, composición corporal) en la copia en la nube |
| Información financiera → **Historial de compras** | Sí | No | Sí | Funcionalidad de la app | Indicador `premium` en la copia en la nube (la compra la procesa Google Play) |
| Actividad en la aplicación → **Otro contenido generado por el usuario** | Sí | No | Sí | Funcionalidad de la app | Básculas configuradas (modelo, nombre, número de usuario) en la copia en la nube |
| Ubicación → **Ubicación aproximada** | Sí | Sí | No | Publicidad o marketing, Analíticas, Prevención de fraude | Dirección IP tratada por el SDK de AdMob |
| Actividad en la aplicación → **Interacciones con la aplicación** | Sí | Sí | No | Publicidad o marketing, Analíticas, Prevención de fraude | Interacciones con los anuncios (SDK de AdMob) |
| Información y rendimiento de la aplicación → **Diagnóstico** | Sí | Sí | No | Analíticas, Prevención de fraude | Diagnóstico del SDK de AdMob |
| **ID de dispositivo u otros IDs** | Sí | Sí | No | Publicidad o marketing, Analíticas, Prevención de fraude | ID de publicidad / App Set ID (SDK de AdMob) |

**No se declaran** (se tratan solo en el teléfono o no se recogen):
- Datos de **Health Connect** (pasos, sueño, pulso, oxígeno, entrenamientos…): se leen y se guardan solo en
  el teléfono; nunca se suben.
- **Contenido de notificaciones** (reenvío a la pulsera): se procesa en el teléfono y solo se envía por
  Bluetooth a la pulsera del usuario.
- **Pulso** del modo directo de la pulsera, claves de la pulsera y de las básculas, direcciones Bluetooth:
  solo en el teléfono.
- **Ubicación precisa**: el permiso de ubicación solo existe en Android ≤ 11 para buscar Bluetooth; no se lee
  la ubicación.
- **Informes de fallos / analíticas propias**: la app no incluye Crashlytics ni Analytics.

Notas:
- Los datos de la copia en la nube solo se recogen si el usuario inicia sesión (por eso son **opcionales**)
  y se guardan en Firestore (europe-southwest1); Google actúa como proveedor de servicios, así que no
  cuentan como «compartidos».
- Contrasta las filas de AdMob con la guía vigente de Google:
  https://developers.google.com/admob/android/privacy/play-data-disclosure

## 7. Permisos de Health Connect (declaración)

Contenido de la aplicación → **Permisos de Health Connect**. Para cada permiso, la finalidad (texto en
inglés para pegar en el formulario). Uso común: la app muestra los datos de la pulsera o reloj en *Hoy* y
*Actividad* y guarda las mediciones de la báscula; los datos de Health Connect no salen del teléfono, no se
usan para publicidad y cumplen los requisitos de uso limitado. La pantalla de justificación es
`HealthPermissionsRationaleActivity` (enlaza la política de privacidad).

| Permiso | Para qué se usa |
|---|---|
| `READ_STEPS` | Show today's steps, the daily goal ring and the 7-day / 30-day step history from the user's band or watch. |
| `READ_DISTANCE` | Show the distance walked per day next to the steps. |
| `READ_ACTIVE_CALORIES_BURNED` | Show active calories per day in the activity summary. |
| `READ_TOTAL_CALORIES_BURNED` | Show total daily calories next to the active calories. |
| `READ_HEART_RATE` | Show the day's heart-rate chart and the 30-day heart-rate trend. |
| `READ_RESTING_HEART_RATE` | Show resting heart rate on the Today screen and its 7-day average. |
| `READ_HEART_RATE_VARIABILITY` ⚠ | Show heart-rate variability (RMSSD) in the activity details. |
| `READ_SLEEP` | Show last night's sleep with its stages (hypnogram) and sleep quality. |
| `READ_OXYGEN_SATURATION` | Show blood-oxygen readings recorded by the user's band or watch. |
| `READ_EXERCISE` | List the workouts recorded by the user's band or watch. |
| `READ_RESPIRATORY_RATE` ⚠ | Show the respiratory rate recorded by the user's band or watch. |
| `READ_WEIGHT` | Show the latest weight recorded by other apps (Activity screen, data sources card). |
| `READ_BODY_FAT` ⚠ | Show body fat recorded by other apps next to the scale history. |
| `READ_HEALTH_DATA_HISTORY` | Import up to one year of past activity, sleep and heart-rate data on first connection, so charts are not empty. |
| `WRITE_WEIGHT` | Save weight measurements from the user's Bluetooth scale (or manual entries) to Health Connect, if the user enables it. |
| `WRITE_BODY_FAT` | Save the body-fat percentage measured by the scale. |
| `WRITE_BONE_MASS` | Save the bone mass estimated from the scale measurement. |
| `WRITE_LEAN_BODY_MASS` | Save the lean body mass estimated from the scale measurement. |
| `WRITE_BASAL_METABOLIC_RATE` | Save the basal metabolic rate estimated from the scale measurement. |
| `WRITE_BODY_WATER_MASS` | Save the body water estimated from the scale measurement. |

> ⚠ **Revisar antes de enviar**: hoy la app sincroniza la variabilidad del pulso, la frecuencia
> respiratoria y la grasa corporal de otras apps en su caché local, pero **ninguna pantalla las muestra**.
> Google rechaza permisos de Health Connect que la app no usa de forma visible: muestra esos datos (por
> ejemplo en *Actividad*) o quita los tres permisos del manifiesto y de `HealthDataType` antes de rellenar
> esta declaración.

## 8. Otros permisos (por si los piden en revisión)

No requieren formulario de declaración, pero conviene tener la justificación:

- **Bluetooth** (`BLUETOOTH_SCAN` con `neverForLocation`, `BLUETOOTH_CONNECT`; en Android ≤ 11
  `BLUETOOTH`, `BLUETOOTH_ADMIN` y `ACCESS_FINE_LOCATION`): *The app reads measurements from the user's
  Bluetooth bathroom scale and connects to the user's Xiaomi Smart Band. Location is only requested on
  Android 11 and lower because the system requires it for Bluetooth scanning; the app never reads or stores
  the location.*
- **Acceso a notificaciones** (`BandNotificationListener`, `BIND_NOTIFICATION_LISTENER_SERVICE`): *Optional
  feature of the experimental direct band mode: forwards notifications from apps the user selects to the
  user's Xiaomi Smart Band over Bluetooth, only while the band is connected. Notification content is not
  stored, logged or sent anywhere else. The user enables it explicitly from the band screen and grants
  access in Android settings.*
- **Internet / estado de red**: copia en la nube opcional, anuncios y Google Play Billing.
- `FOREGROUND_SERVICE`, `WAKE_LOCK`, `USE_BIOMETRIC`, `ACCESS_ADSERVICES_*`: los añaden las bibliotecas
  (WorkManager del SDK de anuncios, Credential Manager, AdMob); la app no declara servicios en primer plano
  propios.

## 9. Firma de la app

- **Clave de subida** (ya creada): `%USERPROFILE%\.android\globalfitness-upload.jks`, alias `upload`.
  Su contraseña y la ruta están en `%USERPROFILE%\.gradle\gradle.properties`
  (`GLOBALFITNESS_UPLOAD_STORE_FILE`, `GLOBALFITNESS_UPLOAD_STORE_PASSWORD`,
  `GLOBALFITNESS_UPLOAD_KEY_ALIAS`, `GLOBALFITNESS_UPLOAD_KEY_PASSWORD`). **Haz una copia de seguridad del
  `.jks` y de esas líneas** en un lugar seguro (gestor de contraseñas). Si se pierde, se puede pedir a
  Google el cambio de clave de subida, pero no se puede firmar mientras tanto.
- Huellas de la clave de subida (ya registradas en Firebase):
  - SHA-1 `9F:14:D6:BC:8C:39:6A:BE:77:68:15:FE:6D:51:25:95:11:A2:A8:73`
  - SHA-256 `98:0E:E6:79:97:72:B0:45:D2:A4:92:2D:F1:53:B8:6E:EF:22:0F:3D:7E:85:5F:B8:4A:C0:79:70:61:AA:2E:26`
- **Firma de apps de Play**: al subir el primer `.aab`, acepta *Firma de apps de Google Play* (Google genera
  y guarda la clave de firma).
- **Después**, en Play Console → Configuración → **Integridad de la aplicación → Firma de apps**, copia el
  SHA-1 y el SHA-256 del **certificado de la clave de firma de apps** y regístralos en Firebase (sin esto,
  el inicio de sesión con Google falla en las instalaciones desde Play):

  ```bat
  firebase apps:android:sha:create 1:1064750272721:android:85e513f9844a95e2a2b5f0 <SHA-1> --project globalfitness-app
  firebase apps:android:sha:create 1:1064750272721:android:85e513f9844a95e2a2b5f0 <SHA-256> --project globalfitness-app
  del app\google-services.json
  firebase apps:sdkconfig ANDROID 1:1064750272721:android:85e513f9844a95e2a2b5f0 --project globalfitness-app -o app/google-services.json
  ```

  y haz commit del `google-services.json` actualizado.

## 10. AdMob

1. https://admob.google.com con la cuenta del desarrollador → **Apps → Añadir app** → Android → «¿Está en
   una tienda de aplicaciones?»: **No** (de momento) → nombre **GlobalFitness**. Cuando la app esté publicada,
   **vincúlala** con la ficha de Google Play (Configuración de la app → Tiendas de aplicaciones).
2. **Bloques de anuncios**:
   - **Banner** (la app usa banner adaptativo anclado): nombre `GlobalFitness banner`.
   - **Intersticial**: nombre `GlobalFitness intersticial` (la app limita su frecuencia por su cuenta).
3. Copia el **ID de aplicación** (`ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN`) y los **IDs de bloque**
   (`ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN`) y añádelos a `%USERPROFILE%\.gradle\gradle.properties`:

   ```properties
   ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~NNNNNNNNNN
   ADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN
   ADMOB_INTERSTITIAL_ID=ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN
   ```

   Las compilaciones de depuración siguen usando siempre los IDs de prueba. Vuelve a compilar la release.
4. **Mensaje de RGPD**: Privacidad y mensajes → **Normativa europea** → Crear mensaje → app GlobalFitness,
   idiomas **español** e **inglés**, URL de privacidad https://globalfitness-app.web.app/privacy.html,
   botones «Consentir», «No consentir» y «Gestionar opciones» → Publicar. (La app ya integra UMP y muestra
   «Opciones de privacidad de anuncios» en Perfil cuando hace falta.) Opcional: mensaje de **normativas
   estatales de EE. UU.**
5. **app-ads.txt**: cuando tengas el ID de editor (`pub-…`), crea `hosting/public/app-ads.txt` con la línea
   que da AdMob (`google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0`), despliega el hosting y pon
   https://globalfitness-app.web.app/ como sitio web en la ficha de Play.
6. Para probar con anuncios reales en tus móviles, añádelos como **dispositivos de prueba** en AdMob
   (Configuración → Dispositivos de prueba) y no pulses tus propios anuncios.

## 11. Prueba interna y siguientes pistas

1. Prueba y publica → Pruebas → **Prueba interna** → Testers: crea una lista de correo con los testers
   (máx. 100) → copia el **enlace de participación**.
2. **Crear versión** → acepta la firma de apps de Play → sube `dist\GlobalFitness-release.aab` → nombre de
   versión `1.0.0 (1001)` → notas de la versión, por ejemplo:

   ```text
   <es-ES>
   Primera versión de GlobalFitness.
   </es-ES>
   <en-US>
   First release of GlobalFitness.
   </en-US>
   ```

3. Revisar versión → **Publicar en prueba interna**. Los testers instalan desde el enlace de participación.
4. Haz el paso 9 (huellas de la clave de firma de Play en Firebase) y comprueba en un móvil instalado desde
   Play: bienvenida, inicio de sesión con Google y copia en la nube, Health Connect, compra de prueba de
   `remove_ads` (con un tester de licencias), anuncios y consentimiento.
5. Después: **Prueba cerrada** (12 testers durante 14 días si la cuenta es personal y nueva) → solicitar
   acceso a **Producción** → enviar a revisión.
6. Para cada versión nueva: sube `globalfitness.buildNumber`, compila, etiqueta el commit (`vX.Y.Z`) y sube
   el `.aab`.
