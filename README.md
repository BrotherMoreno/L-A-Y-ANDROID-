# LAY móvil (Android)

LAY dentro de tu celular.
App nativa que controla **el propio celular** con tu voz, igual que LAY de escritorio controla la PC. Sin internet, sin cuenta y sin pago: las órdenes se entienden en el teléfono.

**Instalar:** sube este repo a GitHub → el workflow `build-apk` compila `LAY_<ver>.apk` (Actions → artefacto **LAY-android**, y en el Release). Instálalo en el celular (permite "instalar apps desconocidas"). Toca el círculo **LAY** y habla, o escribe. Extra: en los ajustes rápidos (deslizar hacia abajo → lápiz → agregar) pon el mosaico **LAY** para hablarle sin abrir la app.

**Qué entiende (español peruano):**
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
