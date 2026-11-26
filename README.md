# LAY móvil (Android)

LAY dentro de tu celular.
App nativa que controla **el propio celular** con tu voz, igual que LAY de escritorio controla la PC. Tiene dos cerebros: **reglas locales** (sin internet, sin cuenta, gratis) y una **IA opcional** que entiende órdenes libres y ejecuta varios pasos seguidos.

**Instalar:** sube este repo a GitHub → el workflow `build-apk` compila `LAY_<ver>.apk` (Actions → artefacto **LAY-android**, y en el Release). Instálalo en el celular (permite "instalar apps desconocidas"). Toca el círculo **LAY** y habla, escribe, o activa **«Oye LAY»**. Extra: en los ajustes rápidos (deslizar hacia abajo → lápiz → agregar) pon el mosaico **LAY** para hablarle sin abrir la app.

## 🧠 IA que ejecuta acciones (opcional)
Con la IA activada, LAY entiende frases libres y **encadena acciones** usando herramientas reales del teléfono: *"abre Spotify y busca salsa"*, *"dile a mamá que llego tarde y pon una alarma a las 6"*, *"qué batería tengo y sube el volumen"*. Si algo falla o no hay internet, vuelve solo a las reglas locales.

**Activarla (gratis con Gemini):**
1. Crea una clave gratis en <https://aistudio.google.com/apikey> (sin tarjeta).
2. En la app: **🧠 IA** → botón **Gemini** → pega la clave → **Guardar** → **Probar conexión** → activa **IA activada**.
3. Otros servicios compatibles con la API de OpenAI (Groq, OpenRouter, OpenAI…): escribe su dirección base, un modelo que soporte herramientas y tu clave. El modelo de Gemini es editable por si cambia el nombre.

**Herramientas que puede usar la IA:** abrir apps, llamar, WhatsApp/SMS, linterna, volumen, música (pausa/siguiente), alarmas, temporizadores, Google/YouTube/música, navegar y mapa, ajustes, cámara, hora/fecha/batería, orden a tu PC, y con control total: botones de sistema, tocar, escribir, desplazar, **leer pantalla** (opcional).

**Seguridad:** la IA **no puede saltarse la confirmación**: llamar y enviar mensajes siempre piden tu "sí" por voz. No hay herramientas para borrar datos, pagar ni instalar.

**Privacidad:** se envían al servicio elegido lo que dices o escribes y los resultados de las acciones (hora, batería, "no encontré a X"). No se envía tu agenda de contactos. Con "leer pantalla" activado, también el texto visible (sin contraseñas). Los planes gratis de algunos servicios pueden usar esos datos para mejorar sus productos: revisa sus términos. La clave queda guardada solo en el celular (almacenamiento privado de la app).

**Límites:** si la IA abre otra app y luego pide abrir una tercera, Android puede bloquear el segundo lanzamiento desde segundo plano (con el control total activo suele permitirlo). El plan gratis tiene un límite de peticiones por minuto/día.

## 🎙 «Oye LAY» (manos libres)
Dile **«Oye LAY»**, oirás un pitido, y di tu orden: *"Oye LAY… sube el volumen"*. Funciona con la app cerrada y con la pantalla apagada.

**Activarlo (una vez):**
1. En la app: **🎙 Voz** → **Descargar modelo** (≈ 40 MB, gratis, una sola vez; es el modelo español pequeño de Vosk, Apache 2.0).
2. Activa **Escuchar «Oye LAY»** y da los permisos de micrófono y notificaciones.
3. Aparece una notificación fija "LAY está escuchando" (con botón **Detener**). Mientras esté, LAY te oye.

**Cómo funciona:** la palabra de activación se detecta **sin internet y sin enviar audio a ningún lado** (Vosk corre dentro del celular). Al oírla, tu orden la entiende el reconocedor de voz de Google (necesita internet para mejor precisión), y la ejecuta el mismo cerebro de siempre: reglas locales o la IA si la activaste. Las confirmaciones ("¿Llamo a Mamá?") también se responden por voz.

**Limitaciones honestas:**
- "LAY" no es una palabra del español: el modelo a veces la oye como "ley". Por eso, por defecto exige "**Oye** LAY" (opción en 🎙 Voz). Puede fallar con ruido o lejos del micrófono, o activarse por error con frases parecidas.
- Gasta más batería (el micrófono y el procesador siguen activos). Actívalo cuando lo necesites y detenlo desde la notificación.
- Si reinicias el celular hay que volver a activarlo (Android no permite arrancar el micrófono solo al encender).
- Con la pantalla apagada o en otra app, Android limita **abrir pantallas** (apps, alarmas con pantalla) desde segundo plano: activa también el **control total** (Accesibilidad), que suele permitirlo. Acciones sin pantalla (linterna, volumen, música, hora, mensajes con confirmación por voz) no tienen este problema.
- Los permisos de contactos y llamadas no se pueden pedir sin pantalla: acéptalos la primera vez desde la app.
- Algunos fabricantes (Xiaomi, Huawei, Samsung…) cierran servicios en segundo plano: quita a LAY de las "optimizaciones de batería" si se detiene solo.

## Órdenes con reglas locales (sin IA)

| Dices | LAY hace |
|---|---|
| "abre WhatsApp / la calculadora" | abre cualquier app instalada por su nombre |
| "llama a mi mamá" · "llama al 987654321" | pregunta "¿Llamo a Mamá?" → "sí" → llama |
| "mándale un WhatsApp a Juan que ya llegué" | pregunta antes de enviar; abre el chat con el texto (con control total lo envía) |
| "enciende / apaga la linterna" | linterna |
| "sube el volumen" · "volumen al 40" · "silencia" · "pausa" · "siguiente canción" | volumen y música |
| "alarma a las 7 y media de la mañana" · "temporizador de 5 minutos" | las pone sin abrir el reloj |
| "pon música de Chabuca Granda" · "busca recetas en YouTube" · "busca X en Google" | reproduce o busca |
| "cómo llego a Larcomar" · "farmacias cerca" | Google Maps |
| "qué hora es" · "cuánta batería tengo" | te responde en voz alta |
| "abre ajustes de wifi / bluetooth / pantalla" | abre el panel (Android no deja activarlos por voz sin root) |
| "en la PC modo trabajo" | manda la orden a LAY de tu computadora (si la vinculaste en **Mi PC**) |

**Control total (opcional):** toca "Activar control total" → Ajustes → Accesibilidad → **LAY** → Activar. Con eso LAY también hace: "ve al inicio", "atrás", "aplicaciones recientes", "abre las notificaciones", "captura de pantalla", "bloquea el celular", "baja / sube" (desplazar), "toca Aceptar", "escribe hola". Solo actúa cuando se lo pides y no guarda lo que hay en pantalla.

**Seguridad:** llamar y mandar mensajes siempre piden tu "sí" antes. Los permisos (contactos, llamadas) se piden la primera vez que los necesitas.

**Mi PC (control remoto de LAY de escritorio):** botón **💻 Mi PC** en la app. Requiere Tailscale en PC y celular + **⚙ Configuración → Remoto → Activar** en la PC → **Copiar enlace para la app** → pégalo en Mi PC → Vincular. También sirve la página `http://<ip-tailscale>:8765/remoto` desde cualquier navegador (iPhone incluido; el dictado por voz del navegador pide HTTPS).

**Firma estable** (para actualizar sin desinstalar): secrets `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Sin ellos se firma con clave de depuración. Compilar a mano: `gradle assembleRelease -PlayVersion=3.0.0` (JDK 17 + Android SDK).

**LAY de escritorio:** vive en su propio repo (LAY); este repo solo contiene la app del celular. "Mi PC" habla con su hub.

**Atajos / API de la PC (opcional):** `POST http://<pc>:8765/api/comando` · header `Authorization: Bearer <LAY_HUB_TOKEN>` · body `{"texto":"abre mi workspace de trabajo"}`. MCP remoto: `LAY_MCP_TOKEN` en `.env` y `python -m lay_mcp.server --http --port 8766`.
