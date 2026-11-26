package pe.lay.remoto;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Las "manos" que la IA puede usar. Cada herramienta se traduce a una frase canónica que ejecutan las reglas
 * locales de Cerebro (las mismas que funcionan sin internet) o, en pocos casos, código propio (leer_pantalla, esperar).
 * Llamar y mandar mensajes siguen pidiendo tu "sí": la IA no puede saltarse esa confirmación.
 */
final class Herramientas {

    private final Cerebro cerebro;

    Herramientas(Cerebro cerebro) {
        this.cerebro = cerebro;
    }

    // ------------------------------------------------------------------ definiciones para el modelo

    private static JSONObject s(String desc) throws JSONException {
        return new JSONObject().put("type", "string").put("description", desc);
    }

    private static JSONObject e(String desc, String... valores) throws JSONException {
        return s(desc).put("enum", new JSONArray(Arrays.asList(valores)));
    }

    private static JSONObject n(String desc) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", desc);
    }

    private static JSONObject b(String desc) throws JSONException {
        return new JSONObject().put("type", "boolean").put("description", desc);
    }

    private static JSONObject fn(String nombre, String desc, JSONObject props, String... requeridos) throws JSONException {
        JSONObject f = new JSONObject().put("name", nombre).put("description", desc);
        if (props != null) {
            JSONObject params = new JSONObject().put("type", "object").put("properties", props);
            if (requeridos.length > 0) params.put("required", new JSONArray(Arrays.asList(requeridos)));
            f.put("parameters", params);
        }
        return new JSONObject().put("type", "function").put("function", f);
    }

    static JSONArray definiciones(boolean permitirLeerPantalla) throws JSONException {
        JSONArray t = new JSONArray();
        t.put(fn("abrir_app", "Abre una app instalada por su nombre (WhatsApp, YouTube, Calculadora, Spotify...).",
                new JSONObject().put("nombre", s("Nombre de la app")), "nombre"));
        t.put(fn("llamar", "Llama por teléfono a un contacto o número. El sistema pide confirmación al usuario antes de llamar.",
                new JSONObject().put("contacto", s("Nombre del contacto tal como lo dijo el usuario, o un número")), "contacto"));
        t.put(fn("enviar_mensaje", "Prepara un mensaje de WhatsApp o SMS. El sistema pide confirmación al usuario antes de enviarlo.",
                new JSONObject().put("contacto", s("Nombre del contacto o número"))
                        .put("texto", s("Texto exacto del mensaje, en primera persona del usuario"))
                        .put("canal", e("whatsapp (por defecto) o sms", "whatsapp", "sms")), "contacto", "texto"));
        t.put(fn("linterna", "Enciende o apaga la linterna.", new JSONObject().put("encender", b("true enciende, false apaga")), "encender"));
        t.put(fn("volumen", "Cambia el volumen multimedia.",
                new JSONObject().put("accion", e("Qué hacer", "subir", "bajar", "maximo", "silenciar", "activar_sonido", "porcentaje"))
                        .put("porcentaje", n("0 a 100, solo si accion=porcentaje")), "accion"));
        t.put(fn("control_musica", "Controla la música o el video que suena.",
                new JSONObject().put("accion", e("Qué hacer", "pausa_o_reanudar", "siguiente", "anterior")), "accion"));
        t.put(fn("alarma", "Pone una alarma (hora en formato 24 h).",
                new JSONObject().put("hora", n("0 a 23")).put("minuto", n("0 a 59")), "hora", "minuto"));
        t.put(fn("temporizador", "Pone un temporizador.", new JSONObject().put("segundos", n("Duración total en segundos")), "segundos"));
        t.put(fn("buscar_en_google", "Busca en Google.", new JSONObject().put("consulta", s("Qué buscar")), "consulta"));
        t.put(fn("buscar_en_youtube", "Busca videos en YouTube.", new JSONObject().put("consulta", s("Qué buscar")), "consulta"));
        t.put(fn("poner_musica", "Reproduce una canción, artista o álbum.", new JSONObject().put("consulta", s("Qué poner")), "consulta"));
        t.put(fn("navegar_a", "Abre la navegación paso a paso hasta un lugar.", new JSONObject().put("lugar", s("Destino")), "lugar"));
        t.put(fn("buscar_en_mapa", "Muestra un lugar o negocios cercanos en el mapa.", new JSONObject().put("lugar", s("Qué o dónde buscar")), "lugar"));
        t.put(fn("abrir_ajustes", "Abre una sección de ajustes (Android no permite activar wifi o bluetooth sin que el usuario toque).",
                new JSONObject().put("seccion", e("Sección", "wifi", "bluetooth", "datos", "pantalla", "sonido", "ubicacion", "bateria", "almacenamiento", "accesibilidad", "general")), "seccion"));
        t.put(fn("pantalla", "Acciones del sistema sobre la pantalla actual (necesita el control total activo).",
                new JSONObject().put("accion", e("Qué hacer", "inicio", "atras", "recientes", "notificaciones", "captura", "bloquear", "desplazar_abajo", "desplazar_arriba")), "accion"));
        t.put(fn("tocar", "Toca un botón o elemento visible por su texto (necesita el control total activo).",
                new JSONObject().put("texto", s("Texto visible del botón")), "texto"));
        t.put(fn("escribir", "Escribe texto en el campo de texto de la pantalla actual (necesita el control total activo).",
                new JSONObject().put("texto", s("Texto a escribir")), "texto"));
        t.put(fn("esperar", "Espera unos segundos (por ejemplo a que cargue una app recién abierta).",
                new JSONObject().put("segundos", n("1 a 5")), "segundos"));
        t.put(fn("info_telefono", "Consulta un dato real del teléfono. No inventes estos datos.",
                new JSONObject().put("dato", e("Dato", "hora", "fecha", "bateria")), "dato"));
        t.put(fn("abrir_camara", "Abre la cámara.", null));
        t.put(fn("orden_en_pc", "Manda una orden a LAY de la computadora del usuario (si la vinculó). La respuesta llega aparte.",
                new JSONObject().put("orden", s("Orden en español para la PC")), "orden"));
        if (permitirLeerPantalla) {
            t.put(fn("leer_pantalla", "Lee el texto visible de la pantalla actual para decidir el siguiente paso (necesita el control total activo).", null));
        }
        return t;
    }

    // ------------------------------------------------------------------ ejecución (hilo del agente)

    /** @return el resultado en texto para el modelo. Bloquea hasta que la acción terminó (máx. ~10 s). */
    String ejecutar(String nombre, JSONObject a, Captura cap) {
        try {
            if (nombre.equals("esperar")) {
                Thread.sleep(Math.max(1, Math.min(5, a.optInt("segundos", 2))) * 1000L);
                return "Listo.";
            }
            boolean leer = nombre.equals("leer_pantalla");
            String frase = leer ? null : frase(nombre, a);
            if (!leer && frase == null) return "Herramienta desconocida o datos incompletos.";
            String res = correr(cap, frase, leer);
            if (cap.esperando()) return "Se le preguntó al usuario y se espera su respuesta. No repitas la pregunta.";
            return res;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "Interrumpido.";
        }
    }

    private String correr(Captura cap, String frase, boolean leer) throws InterruptedException {
        final String[] res = {null};
        final CountDownLatch ui = new CountDownLatch(1);
        cap.reiniciar();
        cap.enUi(() -> {
            try {
                if (leer) {
                    cap.enSegundoPlano(() -> res[0] = LayAccesibilidad.activo()
                            ? LayAccesibilidad.leerPantalla() : "El control total no está activo, no puedo leer la pantalla.");
                } else if (!cerebro.ejecutarRegla(frase, cap)) {
                    res[0] = "Esa acción no se pudo interpretar.";
                }
            } catch (RuntimeException ex) {
                res[0] = "Error: " + ex.getMessage();
            } finally {
                ui.countDown();
            }
        });
        ui.await(10, TimeUnit.SECONDS);
        if (cap.usoFondo()) cap.esperarFondo(10);
        if (res[0] != null) return res[0];
        String dicho = cap.texto();
        return dicho.isEmpty() ? "Hecho." : dicho;
    }

    private static String frase(String nombre, JSONObject a) {
        switch (nombre) {
            case "abrir_app":
                return pide(a, "nombre") ? "abre " + a.optString("nombre") : null;
            case "llamar":
                return pide(a, "contacto") ? "llama a " + a.optString("contacto") : null;
            case "enviar_mensaje":
                if (!pide(a, "contacto") || !pide(a, "texto")) return null;
                return "mandale un " + ("sms".equals(a.optString("canal")) ? "sms" : "whatsapp") + " a " + a.optString("contacto") + " que " + a.optString("texto");
            case "linterna":
                return a.optBoolean("encender", true) ? "enciende la linterna" : "apaga la linterna";
            case "volumen":
                switch (a.optString("accion")) {
                    case "subir":
                        return "sube el volumen";
                    case "bajar":
                        return "baja el volumen";
                    case "maximo":
                        return "volumen al maximo";
                    case "silenciar":
                        return "silencia el celular";
                    case "activar_sonido":
                        return "activa el sonido";
                    case "porcentaje":
                        return "pon el volumen al " + Math.max(0, Math.min(100, a.optInt("porcentaje", 50)));
                    default:
                        return null;
                }
            case "control_musica":
                switch (a.optString("accion")) {
                    case "pausa_o_reanudar":
                        return "pausa";
                    case "siguiente":
                        return "siguiente";
                    case "anterior":
                        return "anterior";
                    default:
                        return null;
                }
            case "alarma": {
                int h = a.optInt("hora", -1);
                int m = a.optInt("minuto", 0);
                if (h < 0 || h > 23 || m < 0 || m > 59) return null;
                return "alarma a las " + h + ":" + (m < 10 ? "0" : "") + m;
            }
            case "temporizador": {
                int seg = a.optInt("segundos", 0);
                if (seg <= 0 || seg > 99 * 3600) return null;
                StringBuilder b = new StringBuilder("temporizador de");
                if (seg / 3600 > 0) b.append(' ').append(seg / 3600).append(" horas");
                if (seg % 3600 / 60 > 0) b.append(' ').append(seg % 3600 / 60).append(" minutos");
                if (seg % 60 > 0) b.append(' ').append(seg % 60).append(" segundos");
                return b.toString();
            }
            case "buscar_en_google":
                return pide(a, "consulta") ? "busca " + a.optString("consulta") : null;
            case "buscar_en_youtube":
                return pide(a, "consulta") ? "busca " + a.optString("consulta") + " en youtube" : null;
            case "poner_musica":
                return pide(a, "consulta") ? "pon " + a.optString("consulta") : null;
            case "navegar_a":
                return pide(a, "lugar") ? "como llego a " + a.optString("lugar") : null;
            case "buscar_en_mapa":
                return pide(a, "lugar") ? "busca en el mapa " + a.optString("lugar") : null;
            case "abrir_ajustes": {
                String sec = a.optString("seccion");
                if (sec.equals("general")) return "abre ajustes";
                if (sec.equals("accesibilidad")) return "abre accesibilidad";
                return sec.isEmpty() ? null : "abre ajustes de " + sec;
            }
            case "pantalla":
                switch (a.optString("accion")) {
                    case "inicio":
                        return "ve al inicio";
                    case "atras":
                        return "atras";
                    case "recientes":
                        return "aplicaciones recientes";
                    case "notificaciones":
                        return "abre las notificaciones";
                    case "captura":
                        return "captura de pantalla";
                    case "bloquear":
                        return "bloquea el celular";
                    case "desplazar_abajo":
                        return "baja";
                    case "desplazar_arriba":
                        return "sube";
                    default:
                        return null;
                }
            case "tocar":
                return pide(a, "texto") ? "toca " + a.optString("texto") : null;
            case "escribir":
                return pide(a, "texto") ? "escribe " + a.optString("texto") : null;
            case "info_telefono":
                switch (a.optString("dato")) {
                    case "hora":
                        return "que hora es";
                    case "fecha":
                        return "que dia es hoy";
                    case "bateria":
                        return "cuanta bateria tengo";
                    default:
                        return null;
                }
            case "abrir_camara":
                return "abre la camara";
            case "orden_en_pc":
                return pide(a, "orden") ? "en la pc " + a.optString("orden") : null;
            default:
                return null;
        }
    }

    private static boolean pide(JSONObject a, String clave) {
        return !a.optString(clave, "").trim().isEmpty();
    }

    // ------------------------------------------------------------------ Anfitrión "grabadora"

    /**
     * Mientras la IA trabaja, lo que las reglas "dirían" se guarda como resultado de la herramienta (el modelo redacta la respuesta final).
     * Las preguntas de confirmación y los permisos sí llegan al usuario. Al terminar, pasa a modo directo.
     */
    static final class Captura implements Cerebro.Anfitrion {
        private final Cerebro.Anfitrion host;
        private final StringBuilder buf = new StringBuilder();
        private volatile boolean esperando;
        private volatile boolean directo;
        private volatile boolean usoFondo;
        private volatile CountDownLatch fondo = new CountDownLatch(1);

        Captura(Cerebro.Anfitrion host) {
            this.host = host;
        }

        void reiniciar() {
            synchronized (buf) {
                buf.setLength(0);
            }
            usoFondo = false;
            fondo = new CountDownLatch(1);
        }

        boolean esperando() {
            return esperando;
        }

        boolean usoFondo() {
            return usoFondo;
        }

        void esperarFondo(int segundos) throws InterruptedException {
            fondo.await(segundos, TimeUnit.SECONDS);
        }

        String texto() {
            synchronized (buf) {
                return buf.toString().trim();
            }
        }

        /** Ya no hay IA esperando: lo que llegue tarde (un permiso concedido, la respuesta de la PC) se dice normal. */
        void terminar() {
            directo = true;
        }

        @Override
        public Context ctx() {
            return host.ctx();
        }

        @Override
        public void decir(String t) {
            if (directo) {
                host.decir(t);
                return;
            }
            synchronized (buf) {
                buf.append(t).append(' ');
            }
        }

        @Override
        public void decirYEscuchar(String t) {
            esperando = true;
            host.decirYEscuchar(t);
        }

        @Override
        public boolean iniciar(Intent i) {
            return host.iniciar(i);
        }

        @Override
        public void enSegundoPlano(Runnable r) {
            usoFondo = true;
            final CountDownLatch f = fondo;
            host.enSegundoPlano(() -> {
                try {
                    r.run();
                } finally {
                    f.countDown();
                }
            });
        }

        @Override
        public void permiso(String permiso, Runnable concedido, Runnable negado) {
            if (host.ctx().checkSelfPermission(permiso) != PackageManager.PERMISSION_GRANTED) esperando = true;
            host.permiso(permiso, concedido, negado);
        }

        @Override
        public void detenerVoz() {
            host.detenerVoz();
        }

        @Override
        public void enUi(Runnable r) {
            host.enUi(r);
        }

        @Override
        public void estado(String t) {
            host.estado(t);
        }
    }
}
