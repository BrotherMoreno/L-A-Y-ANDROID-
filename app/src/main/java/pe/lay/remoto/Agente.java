package pe.lay.remoto;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * El modelo de IA de LAY móvil: entiende órdenes libres y las ejecuta con herramientas (abrir apps, llamar, tocar la pantalla...).
 * Habla con cualquier servicio compatible con la API de chat de OpenAI (Gemini tiene plan gratis, Groq, OpenRouter, OpenAI...).
 * Tú eliges el servicio, el modelo y la clave en la pantalla «🧠 IA». Sin IA o sin internet, siguen funcionando las reglas locales.
 */
final class Agente {
    static final String PREFS = "lay_ia";
    static final String URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai";
    static final String MODELO_GEMINI = "gemini-3.8-flash";

    private static final int MAX_RONDAS = 8;
    private static final int MAX_HISTORIAL = 8;

    private final Herramientas herramientas;
    private final ArrayList<JSONObject> historial = new ArrayList<>();

    Agente(Cerebro cerebro) {
        this.herramientas = new Herramientas(cerebro);
    }

    static boolean activo(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean("activa", false) && !p.getString("clave", "").trim().isEmpty()
                && !p.getString("url", "").trim().isEmpty() && !p.getString("modelo", "").trim().isEmpty();
    }

    interface Resultado {
        void listo(String mensaje);
    }

    // ------------------------------------------------------------------ una orden

    void preguntar(final String orden, final Cerebro.Anfitrion host, final Runnable alternativaLocal) {
        final SharedPreferences p = host.ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        host.estado("pensando…");
        new Thread(() -> {
            final Herramientas.Captura cap = new Herramientas.Captura(host);
            int usadas = 0;
            try {
                JSONArray msgs = new JSONArray();
                msgs.put(mensaje("system", sistema()));
                synchronized (historial) {
                    for (JSONObject h : historial) msgs.put(h);
                }
                msgs.put(mensaje("user", orden));
                JSONArray tools = Herramientas.definiciones(p.getBoolean("pantalla", false));

                String respuesta = null;
                String ultimoResultado = "";
                for (int ronda = 0; ronda < MAX_RONDAS; ronda++) {
                    JSONObject resp = llamar(p, msgs, tools);
                    JSONObject m = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                    JSONArray calls = m.optJSONArray("tool_calls");
                    if (calls == null || calls.length() == 0) {
                        respuesta = m.isNull("content") ? "" : m.getString("content").trim();
                        break;
                    }
                    msgs.put(m);
                    boolean cortar = false;
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.getJSONObject(i);
                        JSONObject f = call.getJSONObject("function");
                        String nombre = f.getString("name");
                        String res = herramientas.ejecutar(nombre, argumentos(f.optString("arguments", "{}")), cap);
                        usadas++;
                        ultimoResultado = res;
                        msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", res));
                        if (cap.esperando()) cortar = true;
                    }
                    if (cortar) {
                        cap.terminar();
                        guardar(orden, null);
                        host.estado("");
                        return;
                    }
                }
                cap.terminar();
                host.estado("");
                if (respuesta == null) respuesta = "Me enredé con esa orden. Dímela más corta.";
                if (respuesta.isEmpty()) respuesta = usadas > 0 ? "Listo." : "No entendí.";
                guardar(orden, respuesta);
                host.decir(respuesta);
            } catch (Exception e) {
                cap.terminar();
                final int hechas = usadas;
                final String motivo = explicar(e);
                host.enUi(() -> {
                    host.estado("");
                    if (hechas == 0) {
                        host.decir("No pude usar la IA (" + motivo + "). Uso mis reglas locales.");
                        alternativaLocal.run();
                    } else {
                        host.decir("Se cortó la IA a la mitad (" + motivo + "). Revisa si se hizo todo.");
                    }
                });
            }
        }).start();
    }

    /** Prueba de la pantalla de ajustes: una pregunta simple, sin herramientas. */
    static void probar(Context ctx, Resultado r) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray m = new JSONArray().put(mensaje("user", "Responde solo con la palabra: OK"));
                JSONObject resp = llamar(p, m, null);
                JSONObject mm = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                String t = mm.isNull("content") ? "" : mm.getString("content").trim();
                msg = "Conectado ✔ La IA respondió: " + (t.length() > 60 ? t.substring(0, 60) : t);
            } catch (Exception e) {
                msg = "Falló: " + explicar(e);
            }
            final String fin = msg;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> r.listo(fin));
        }).start();
    }

    // ------------------------------------------------------------------ piezas

    private String sistema() {
        String ahora = new SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm", new Locale("es", "PE")).format(new Date());
        return "Eres LAY, el asistente de voz de este celular Android; el usuario está en Perú. "
                + "Ejecutas lo que pide usando las herramientas: no expliques cómo hacerlo, hazlo. "
                + "Para varios pasos llama las herramientas en orden (puedes usar esperar y leer_pantalla entre pasos). "
                + "Llamar y mandar mensajes piden la confirmación del usuario por su cuenta: llama la herramienta directamente, sin preguntar tú antes. "
                + "Datos del teléfono (hora, fecha, batería) se consultan con info_telefono, nunca se inventan. "
                + "Si algo no se puede (por ejemplo Android no deja activar el wifi por voz), dilo en una frase y ofrece la alternativa. "
                + "Responde en español, MUY breve (una frase, máximo dos), hablado: sin markdown, sin listas, sin emojis. "
                + "Ahora es " + ahora + ".";
    }

    private static JSONObject mensaje(String rol, String texto) throws JSONException {
        return new JSONObject().put("role", rol).put("content", texto);
    }

    private static JSONObject argumentos(String json) {
        try {
            return new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void guardar(String orden, String respuesta) {
        try {
            synchronized (historial) {
                historial.add(mensaje("user", orden));
                historial.add(mensaje("assistant", respuesta == null || respuesta.isEmpty() ? "(acción hecha)" : respuesta));
                while (historial.size() > MAX_HISTORIAL) historial.remove(0);
            }
        } catch (JSONException ignorada) {
        }
    }

    private static JSONObject llamar(SharedPreferences p, JSONArray msgs, JSONArray tools) throws IOException, JSONException {
        String base = p.getString("url", "").trim().replaceAll("/+$", "");
        JSONObject cuerpo = new JSONObject().put("model", p.getString("modelo", "").trim()).put("messages", msgs);
        if (tools != null) cuerpo.put("tools", tools).put("tool_choice", "auto");
        HttpURLConnection h = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        h.setRequestMethod("POST");
        h.setConnectTimeout(15000);
        h.setReadTimeout(60000);
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        h.setRequestProperty("Authorization", "Bearer " + p.getString("clave", "").trim());
        try (OutputStream o = h.getOutputStream()) {
            o.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
        }
        int codigo = h.getResponseCode();
        InputStream in = codigo < 400 ? h.getInputStream() : h.getErrorStream();
        String texto = "";
        if (in != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            in.close();
            texto = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
        if (codigo >= 400) throw new IOException("HTTP " + codigo + " " + (texto.length() > 160 ? texto.substring(0, 160) : texto));
        return new JSONObject(texto);
    }

    static String explicar(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.startsWith("HTTP 401") || m.startsWith("HTTP 403")) return "la clave no es válida o no tiene permiso";
        if (m.startsWith("HTTP 429")) return "llegaste al límite gratis por ahora, espera un momento";
        if (m.startsWith("HTTP 404")) return "el modelo o la dirección no existen; revisa «🧠 IA»";
        if (m.startsWith("HTTP 400")) return "el servicio rechazó la petición; revisa el nombre del modelo";
        if (m.startsWith("HTTP ")) return "el servicio respondió con error";
        if (e instanceof JSONException) return "respuesta inesperada del servicio";
        return "sin conexión a internet";
    }
}
