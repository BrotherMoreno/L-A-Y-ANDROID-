package pe.lay.remoto;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El cerebro de LAY móvil: entiende órdenes en español y las ejecuta en ESTE celular.
 * Funciona sin internet y sin cuenta ni pago (reglas locales). Lo que cambia el mundo real
 * (llamar, mandar mensajes) siempre pide confirmación antes: "¿Llamo a Mamá?" → "sí".
 * Reglas del proyecto LAY: respuestas cortas, sin markdown, español.
 */
final class Cerebro {

    /** Lo que Cerebro necesita de la pantalla (lo implementa MainActivity). */
    interface Anfitrion {
        Context ctx();

        void decir(String texto);

        /** Dice la pregunta y vuelve a abrir el micrófono para oír la respuesta. */
        void decirYEscuchar(String texto);

        boolean iniciar(Intent i);

        /** Manda LAY al fondo y ejecuta r cuando ya se ve la app de abajo (para tocar, desplazar, capturar). */
        void enSegundoPlano(Runnable r);

        void permiso(String permiso, Runnable concedido, Runnable negado);

        void detenerVoz();

        /** Ejecuta r en el hilo de la pantalla (la IA trabaja en otro hilo). */
        void enUi(Runnable r);

        /** Línea de estado ("pensando…"); texto vacío la limpia. No habla. */
        void estado(String texto);
    }

    private static final Locale PE = new Locale("es", "PE");
    private static final String[] NUM = {"cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
            "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve", "veinte"};
    private static final String NUMRE = "\\d{1,3}|una|un|veinticinco|treinta|cuarenta|cincuenta|sesenta|" + String.join("|", NUM);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendiente;
    private boolean linterna;
    private final Agente agente = new Agente(this);

    // ------------------------------------------------------------------ texto

    /** Minúsculas, sin tildes y todo lo que no es letra/número/":"/"%" pasa a espacio (misma longitud que el original). */
    static String limpiar(String s) {
        String d = Normalizer.normalize(Normalizer.normalize(s.toLowerCase(PE), Normalizer.Form.NFC), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            b.append(Character.isLetterOrDigit(c) || c == ':' || c == '%' ? c : ' ');
        }
        return b.toString();
    }

    /** raw conserva tildes/ñ (para mensajes y búsquedas); n es la versión limpia para entender la orden. Misma longitud. */
    private static final class T {
        final String r;
        final String n;

        T(String r, String n) {
            this.r = r;
            this.n = n;
        }

        T desde(int i) {
            return new T(r.substring(i), n.substring(i));
        }

        T recortar() {
            int i = 0;
            int f = n.length();
            while (i < f && n.charAt(i) == ' ') i++;
            while (f > i && n.charAt(f - 1) == ' ') f--;
            return new T(r.substring(i, f), n.substring(i, f));
        }

        /** Texto original (con tildes) del grupo g de un match sobre n. */
        String grupo(Matcher m, int g) {
            return r.substring(m.start(g), m.end(g)).trim();
        }
    }

    private static Pattern p(String re) {
        return Pattern.compile(re);
    }

    /** Primer patrón que coincide (o null). Evita llamar find() dos veces sobre el mismo Matcher. */
    private static Matcher uno(String n, String... patrones) {
        for (String re : patrones) {
            Matcher m = p(re).matcher(n);
            if (m.find()) return m;
        }
        return null;
    }

    private static int numero(String s) {
        if (s.matches("\\d{1,3}")) return Integer.parseInt(s);
        if (s.equals("un") || s.equals("una")) return 1;
        for (int i = 0; i < NUM.length; i++) if (NUM[i].equals(s)) return i;
        switch (s) {
            case "veinticinco":
                return 25;
            case "treinta":
                return 30;
            case "cuarenta":
                return 40;
            case "cincuenta":
                return 50;
            case "sesenta":
                return 60;
            default:
                return -1;
        }
    }

    // ------------------------------------------------------------------ entrada

    void ejecutar(String crudo, Anfitrion a) {
        final T t = preparar(crudo, a);
        if (t == null) return;
        if (confirmacion(t, a)) return;
        if (control(t, a)) return;
        if (Agente.activo(a.ctx())) {
            // Con IA: entiende órdenes libres y encadena acciones. Si no hay red, cae a las reglas locales.
            agente.preguntar(t.r, a, () -> reglasOFallo(t, a));
            return;
        }
        reglasOFallo(t, a);
    }

    /** Lo usa la IA: corre una frase canónica por las reglas locales (las "herramientas" del agente). */
    boolean ejecutarRegla(String frase, Anfitrion a) {
        T t = preparar(frase, a);
        return t != null && reglas(t, a);
    }

    private void reglasOFallo(T t, Anfitrion a) {
        if (reglas(t, a)) return;
        a.decir("No entendí «" + t.r + "». "
                + (Agente.activo(a.ctx()) ? "Prueba de otra forma o di «ayuda»." : "Activa la IA en «🧠 IA» para órdenes libres, o di «ayuda»."));
    }

    /** Limpia la frase (sin tildes/mayúsculas para entender, con ellas para repetir). null si ya se respondió (saludo/vacío). */
    private T preparar(String crudo, Anfitrion a) {
        String raw = Normalizer.normalize(crudo.trim(), Normalizer.Form.NFC);
        String n = limpiar(raw);
        if (n.length() != raw.length()) raw = n;
        T t = new T(raw, n).recortar();

        if (t.n.matches("(?:hola|oye|hey|buenas|ok|okay)(?: lay)?|lay")) {
            pendiente = null;
            a.decir("Dime, ¿qué hago?");
            return null;
        }
        // Muletillas del inicio: "oye lay, por favor ..."
        Matcher w = p("^(?:(?:oye|hola|ok|okay|hey)\\b\\s*)?(?:lay\\b\\s*)?(?:(?:por favor|porfa)\\b\\s*)?").matcher(t.n);
        if (w.find() && w.end() > 0) t = t.desde(w.end()).recortar();
        t = new T(t.r.replaceAll("\\s+(?:[Pp]or favor|[Pp]orfa)\\s*$", ""), t.n.replaceAll("\\s+(?:por favor|porfa)\\s*$", "")).recortar();
        if (t.n.isEmpty()) {
            a.decir("Dime, ¿qué hago?");
            return null;
        }
        return t;
    }

    /** Reglas locales (sin internet). true si alguna entendió la orden. */
    private boolean reglas(T t, Anfitrion a) {
        if (pc(t, a)) return true;
        if (infoTelefono(t, a)) return true;
        if (linternaCmd(t, a)) return true;
        if (sonido(t, a)) return true;
        if (alarmaYTemporizador(t, a)) return true;
        if (llamar(t, a)) return true;
        if (mensaje(t, a)) return true;
        if (ajustes(t, a)) return true;
        if (camara(t, a)) return true;
        if (navegacion(t, a)) return true;
        if (youtubeMusicaBusqueda(t, a)) return true;
        if (globales(t, a)) return true;
        return abrirApp(t, a);
    }

    // ------------------------------------------------------------------ confirmación y control

    private boolean confirmacion(T t, Anfitrion a) {
        if (pendiente == null) return false;
        Runnable r = pendiente;
        if (t.n.matches("(?:si|claro|dale|ok|okay|confirmo|confirmado|hazlo|adelante|correcto|por supuesto|yes)(?: .*)?")) {
            pendiente = null;
            r.run();
            return true;
        }
        pendiente = null;
        if (t.n.matches("(?:no|cancela|cancelar|olvidalo|mejor no|nada)(?: .*)?")) {
            a.decir("Cancelado.");
            return true;
        }
        return false; // era otra orden: se descartó la pregunta y se sigue
    }

    private boolean control(T t, Anfitrion a) {
        String n = t.n;
        if (n.matches("para|parar|detente|calla|callate|basta|ya|silencio")) {
            a.detenerVoz();
            return true;
        }
        if (n.matches("gracias|muchas gracias|gracias lay")) {
            a.decir("De nada.");
            return true;
        }
        if (n.matches("ayuda|que puedes hacer|que sabes hacer|comandos|que haces")) {
            a.decir("Puedo abrir apps, llamar y mandar WhatsApp o SMS con tu confirmación, prender la linterna, subir o bajar el volumen, "
                    + "poner alarmas y temporizadores, buscar en Google o YouTube, llevarte a un lugar, decirte la hora y la batería. "
                    + "Con el control total activado también toco botones, bajo la pantalla, vuelvo atrás, capturo y bloqueo. "
                    + "Y si dices «en la PC» más una orden, se la mando a LAY de tu computadora. "
                    + "Con la IA activada entiendo órdenes libres y encadeno pasos, por ejemplo: abre Spotify y busca salsa.");
            return true;
        }
        return false;
    }

    private boolean pc(T t, Anfitrion a) {
        Matcher m = p("^(?:(?:en|a|desde) (?:la|mi) (?:pc|compu|computadora|laptop)|dile a (?:la|mi) (?:pc|compu|computadora))\\b\\s*(?:que\\s+)?(.+)$").matcher(t.n);
        if (!m.find()) return false;
        if (!PcRemoto.vinculada(a.ctx())) {
            a.decir("Aún no vinculaste tu PC. Toca «Mi PC» y pega el enlace.");
            return true;
        }
        String orden = t.grupo(m, 1);
        PcRemoto.enviar(a.ctx(), orden, a::decir);
        return true;
    }

    // ------------------------------------------------------------------ información

    private boolean infoTelefono(T t, Anfitrion a) {
        String n = t.n;
        if (p("\\bque hora es\\b|\\bdime la hora\\b|\\bme das la hora\\b|^la hora$").matcher(n).find()) {
            Calendar c = Calendar.getInstance();
            int h = c.get(Calendar.HOUR_OF_DAY);
            a.decir(((h % 12 == 1) ? "Es " : "Son ") + hablaHora(h, c.get(Calendar.MINUTE)) + ".");
            return true;
        }
        if (p("\\bque (?:dia|fecha) (?:es|estamos)\\b|\\ba cuanto estamos\\b|\\bque dia es hoy\\b").matcher(n).find()) {
            a.decir("Hoy es " + new SimpleDateFormat("EEEE d 'de' MMMM", PE).format(new Date()) + ".");
            return true;
        }
        if (n.contains("bateria") && !p("\\b(?:ajustes|configuracion|ahorro)\\b").matcher(n).find()) {
            Intent b = a.ctx().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) {
                a.decir("No pude leer la batería.");
                return true;
            }
            int nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int escala = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int estado = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean carga = estado == BatteryManager.BATTERY_STATUS_CHARGING || estado == BatteryManager.BATTERY_STATUS_FULL;
            a.decir("Tienes " + (nivel * 100 / Math.max(1, escala)) + " por ciento de batería" + (carga ? " y está cargando." : "."));
            return true;
        }
        return false;
    }

    static String hablaHora(int h, int m) {
        int h12 = h % 12 == 0 ? 12 : h % 12;
        String periodo = h < 6 ? "de la madrugada" : h < 12 ? "de la mañana" : h < 19 ? "de la tarde" : "de la noche";
        return (h12 == 1 ? "la " : "las ") + h12 + (m > 0 ? ":" + (m < 10 ? "0" : "") + m : " en punto") + " " + periodo;
    }

    // ------------------------------------------------------------------ linterna, volumen, música

    private boolean linternaCmd(T t, Anfitrion a) {
        String n = t.n;
        if (!p("\\b(?:linterna|flash)\\b").matcher(n).find()) return false;
        boolean apagar = p("\\b(?:apaga|apagar|desactiva|quita|corta)\\b").matcher(n).find();
        boolean prender = p("\\b(?:enciende|encender|prende|prender|activa|pon|ponme)\\b").matcher(n).find();
        boolean quiere = apagar ? false : prender ? true : !linterna;
        try {
            CameraManager cm = (CameraManager) a.ctx().getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cm.setTorchMode(id, quiere);
                    linterna = quiere;
                    a.decir(quiere ? "Linterna encendida." : "Linterna apagada.");
                    return true;
                }
            }
            a.decir("Este celular no tiene linterna.");
        } catch (CameraAccessException | IllegalArgumentException e) {
            a.decir("No pude usar la linterna ahora.");
        }
        return true;
    }

    private boolean sonido(T t, Anfitrion a) {
        String n = t.n;
        AudioManager am = (AudioManager) a.ctx().getSystemService(Context.AUDIO_SERVICE);
        // Música: pausa, sigue, siguiente, anterior
        if (n.matches("pausa|pausar|pon pausa|pausa la musica|deten la musica|detene la musica|continua|continuar|reanuda|reanudar|play|reproduce|sigue")) {
            tecla(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            a.decir("Listo.");
            return true;
        }
        if (n.matches("siguiente|next|salta|siguiente cancion|pasa la cancion|cambia la cancion|salta la cancion|proxima cancion")) {
            tecla(am, KeyEvent.KEYCODE_MEDIA_NEXT);
            return true;
        }
        if (n.matches("anterior|previous|cancion anterior|regresa la cancion|la anterior")) {
            tecla(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return true;
        }
        // Volumen
        Matcher pct = p("\\bvolumen\\b.*?\\b(?:al?|en)\\s+(\\d{1,3})\\b").matcher(n);
        if (pct.find()) {
            int v = Math.min(100, Integer.parseInt(pct.group(1)));
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max / 100f), AudioManager.FLAG_SHOW_UI);
            a.decir("Volumen al " + v + " por ciento.");
            return true;
        }
        if (p("\\bvolumen\\b.*\\b(?:maximo|full|al tope)\\b|\\bvolumen al maximo\\b").matcher(n).find()) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI);
            a.decir("Volumen al máximo.");
            return true;
        }
        if (p("\\b(?:sube|subir|aumenta|aumentar|mas)\\b.*\\bvolumen\\b|\\bvolumen\\b.*\\b(?:arriba|mas alto)\\b|^mas (?:fuerte|alto)$").matcher(n).find()) {
            for (int i = 0; i < 3; i++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return true;
        }
        if (p("\\b(?:baja|bajar|reduce|reducir|disminuye|menos)\\b.*\\bvolumen\\b|\\bvolumen\\b.*\\b(?:abajo|mas bajo)\\b|^mas (?:bajo|despacio)$").matcher(n).find()) {
            for (int i = 0; i < 3; i++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return true;
        }
        if (p("\\b(?:quita|desactiva|saca)\\b.*\\bsilencio\\b|\\bactiva el sonido\\b|\\bdessilencia\\b").matcher(n).find()) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
            a.decir("Sonido activado.");
            return true;
        }
        if (p("\\b(?:silencia|silenciar|mutea|mutear|quita el sonido)\\b").matcher(n).find()) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
            a.decir("Silenciado.");
            return true;
        }
        return false;
    }

    private static void tecla(AudioManager am, int codigo) {
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, codigo));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, codigo));
    }

    // ------------------------------------------------------------------ alarmas y temporizadores

    private boolean alarmaYTemporizador(T t, Anfitrion a) {
        String n = t.n;
        if (p("\\b(?:borra|elimina|quita|cancela|apaga|ver|muestra|muestrame)\\b.*\\balarmas?\\b").matcher(n).find()) {
            a.iniciar(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            return true;
        }
        // Temporizador
        if (p("\\b(?:temporizador|timer|cuenta regresiva)\\b|\\bavisame en\\b").matcher(n).find()) {
            int seg = 0;
            StringBuilder dicho = new StringBuilder();
            if (n.contains("media hora")) {
                seg += 1800;
                dicho.append("media hora");
            }
            Matcher m = p("\\b(" + NUMRE + ")\\s+(horas?|minutos?|min|segundos?|seg)\\b").matcher(n);
            while (m.find()) {
                int v = numero(m.group(1));
                if (v < 0) continue;
                String u = m.group(2);
                seg += u.startsWith("h") ? v * 3600 : u.startsWith("m") ? v * 60 : v;
                if (dicho.length() > 0) dicho.append(" y ");
                dicho.append(v).append(' ').append(u.startsWith("h") ? (v == 1 ? "hora" : "horas") : u.startsWith("m") ? (v == 1 ? "minuto" : "minutos") : (v == 1 ? "segundo" : "segundos"));
            }
            if (seg <= 0) {
                a.decir("¿De cuánto? Por ejemplo: temporizador de cinco minutos.");
                return true;
            }
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seg)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "LAY");
            a.decir(a.iniciar(i) ? "Temporizador de " + dicho + "." : "No pude poner el temporizador.");
            return true;
        }
        // Alarma
        if (!p("\\b(?:alarma|despiertame)\\b").matcher(n).find()) return false;
        int h = -1;
        int min = 0;
        Matcher m = p("\\ba las? (" + NUMRE + ")(?:[: ](\\d{2}))?(?: (y media|y cuarto|menos cuarto|en punto))?(?: (?:de la |del |de )?(manana|tarde|noche|madrugada|mediodia))?").matcher(n);
        if (m.find()) {
            h = numero(m.group(1));
            if (m.group(2) != null) min = Integer.parseInt(m.group(2));
            String mod = m.group(3);
            if ("y media".equals(mod)) min = 30;
            else if ("y cuarto".equals(mod)) min = 15;
            else if ("menos cuarto".equals(mod)) {
                min = 45;
                h = (h + 23) % 24;
            }
            String per = m.group(4);
            if (per != null) {
                if ((per.equals("tarde") || per.equals("noche")) && h < 12 && h > 0) h += 12;
                else if ((per.equals("noche") || per.equals("manana") || per.equals("madrugada")) && h == 12) h = 0;
            }
        } else {
            Matcher e = p("\\ben (" + NUMRE + ") (horas?|minutos?)\\b").matcher(n);
            if (e.find()) {
                int v = numero(e.group(1));
                Calendar c = Calendar.getInstance();
                c.add(e.group(2).startsWith("h") ? Calendar.HOUR_OF_DAY : Calendar.MINUTE, Math.max(0, v));
                h = c.get(Calendar.HOUR_OF_DAY);
                min = c.get(Calendar.MINUTE);
            }
        }
        if (h < 0 || h > 23 || min > 59) {
            a.decir("¿A qué hora? Por ejemplo: alarma a las 7 y media de la mañana.");
            return true;
        }
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "LAY");
        a.decir(a.iniciar(i) ? "Alarma puesta para " + hablaHora(h, min) + "." : "No pude poner la alarma.");
        return true;
    }

    // ------------------------------------------------------------------ llamadas y mensajes (siempre con confirmación)

    private static String soloDigitos(String n) {
        String compacto = n.replaceAll("(?<=\\d)\\s+(?=\\d)", "");
        Matcher m = p("\\d{6,}").matcher(compacto);
        return m.find() ? m.group() : null;
    }

    private static String aInternacional(String numero) {
        String d = numero.replaceAll("\\D", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.length() == 9 && d.startsWith("9")) d = "51" + d; // celular de Perú sin código de país
        return d;
    }

    private boolean llamar(T t, Anfitrion a) {
        Matcher m = p("^(?:(?:puedes|quiero|necesito|podrias)\\s+)?(?:llama|llamar|llamame|marca|telefonea)\\s+(?:a\\s+|al\\s+|a la\\s+)?(.+)$").matcher(t.n);
        if (!m.find()) return false;
        final String quien = t.grupo(m, 1);
        final String digitos = soloDigitos(t.n);
        if (digitos != null) {
            preguntarLlamada(a, "el " + digitos, digitos);
            return true;
        }
        a.permiso(Manifest.permission.READ_CONTACTS, () -> {
            String[] c = Contactos.buscar(a.ctx(), quien);
            if (c == null) a.decir("No encontré a «" + quien + "» en tus contactos.");
            else preguntarLlamada(a, c[0], c[1]);
        }, () -> a.decir("Sin permiso para leer tus contactos no sé a quién llamar. Dime el número."));
        return true;
    }

    private void preguntarLlamada(Anfitrion a, String nombre, String numero) {
        pendiente = () -> a.permiso(Manifest.permission.CALL_PHONE, () -> {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(numero)));
            if (!a.iniciar(i)) a.decir("No pude iniciar la llamada.");
            else a.decir("Llamando a " + nombre + ".");
        }, () -> {
            a.iniciar(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero))));
            a.decir("Sin permiso de llamadas te dejo el marcador listo.");
        });
        a.decirYEscuchar("¿Llamo a " + nombre + "?");
    }

    private boolean mensaje(T t, Anfitrion a) {
        Matcher m = uno(t.n,
                "^(?:(?:puedes|quiero|podrias|necesito)\\s+)?(?:manda|mandame|mandale|mandar|envia|enviale|enviar|escribe|escribele)\\s+(?:un\\s+|una\\s+)?(?:mensaje|whatsapp|wasap|wsp|guasap|sms|texto)?\\s*(?:de\\s+(?:whatsapp|wasap|wsp|texto)\\s+)?(?:a|al)\\s+(.+?)\\s+(?:diciendo|que diga|que dice|con el mensaje|que)\\s+(.+)$",
                "^dile\\s+(?:a\\s+)?(.+?)\\s+(?:por\\s+(?:whatsapp|wasap|wsp|sms|mensaje)\\s+)?(?:que|diciendo)\\s+(.+)$");
        if (m == null) return false;
        final String quien = t.grupo(m, 1);
        final String texto = t.grupo(m, 2);
        final boolean sms = p("\\b(?:sms|mensaje de texto|texto)\\b").matcher(t.n.substring(0, m.start(2))).find();
        final String digitos = soloDigitos(quien.toLowerCase(PE));
        if (digitos != null) {
            preguntarMensaje(a, digitos, digitos, texto, sms);
            return true;
        }
        a.permiso(Manifest.permission.READ_CONTACTS, () -> {
            String[] c = Contactos.buscar(a.ctx(), quien);
            if (c == null) a.decir("No encontré a «" + quien + "» en tus contactos.");
            else preguntarMensaje(a, c[0], c[1], texto, sms);
        }, () -> a.decir("Sin permiso para leer tus contactos no sé a quién escribirle. Dime el número."));
        return true;
    }

    private void preguntarMensaje(Anfitrion a, String nombre, String numero, String texto, boolean sms) {
        pendiente = () -> {
            Intent i;
            if (sms) {
                i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(numero))).putExtra("sms_body", texto);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + aInternacional(numero) + "?text=" + Uri.encode(texto)));
                if (a.ctx().getPackageManager().getLaunchIntentForPackage("com.whatsapp") != null) i.setPackage("com.whatsapp");
            }
            if (!a.iniciar(i)) {
                a.decir(sms ? "No encontré la app de mensajes." : "No encontré WhatsApp en este celular.");
                return;
            }
            if (LayAccesibilidad.activo()) {
                ui.postDelayed(() -> a.decir(LayAccesibilidad.tocar("Enviar") ? "Enviado a " + nombre + "." : "Dejé el mensaje listo; toca enviar."), 3500);
            } else {
                a.decir("Mensaje listo para " + nombre + ". Toca enviar.");
            }
        };
        a.decirYEscuchar("¿Le mando " + (sms ? "un SMS" : "un WhatsApp") + " a " + nombre + " que dice: " + texto + "?");
    }

    // ------------------------------------------------------------------ mapas, YouTube, música, búsqueda

    private boolean navegacion(T t, Anfitrion a) {
        Matcher m = uno(t.n, "^(?:como llego a|como llegar a|llevame a|navega a|navegar a|ruta a|ruta hacia|vamos a|indicaciones para llegar a)\\s+(.+)$");
        if (m != null) {
            String lugar = t.grupo(m, 1);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(lugar)));
            if (!a.iniciar(i)) a.iniciar(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(lugar))));
            a.decir("Te llevo a " + lugar + ".");
            return true;
        }
        m = uno(t.n,
                "^(?:donde queda|donde esta|muestrame en el mapa|busca en el mapa|mapa de)\\s+(.+)$",
                "^(?:busca |buscame |donde hay )?(.+?) (?:cerca de mi|cerca de aqui|cerca|por aqui)$");
        if (m != null) {
            String lugar = t.grupo(m, 1);
            a.iniciar(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(lugar))));
            a.decir("Buscando " + lugar + " en el mapa.");
            return true;
        }
        return false;
    }

    private boolean youtubeMusicaBusqueda(T t, Anfitrion a) {
        String n = t.n;
        Matcher m = uno(n,
                "^(?:pon|ponme|reproduce|busca|buscame|buscar|muestrame|ver)\\s+(.+?)\\s+en\\s+youtube$",
                "^(?:pon|ponme|reproduce|busca|buscar)\\s+en\\s+youtube\\s+(.+)$",
                "^youtube\\s+(?:busca\\s+|buscar\\s+|pon\\s+)?(.+)$");
        if (m != null) {
            abrirYoutube(a, t.grupo(m, 1));
            return true;
        }
        m = uno(n, "^(?:pon|ponme|reproduce|escuchar|quiero escuchar)\\s+(?:la cancion\\s+|musica de\\s+|musica\\s+|algo de\\s+|el album\\s+)?(.+)$");
        if (m != null) {
            String q = t.grupo(m, 1);
            Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(SearchManager.QUERY, q);
            if (a.iniciar(i)) a.decir("Poniendo " + q + ".");
            else abrirYoutube(a, q);
            return true;
        }
        m = uno(n,
                "^(?:busca|buscame|buscar|googlea|investiga)\\s+(?:en google\\s+|en internet\\s+|sobre\\s+)?(.+)$",
                "^((?:que es|quien es|quien fue|que significa|cuanto cuesta|cuantos|cual es).+)$");
        if (m != null) {
            String q = t.grupo(m, 1);
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q);
            if (!a.iniciar(i)) a.iniciar(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))));
            a.decir("Buscando " + q + ".");
            return true;
        }
        return false;
    }

    private void abrirYoutube(Anfitrion a, String q) {
        a.iniciar(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))));
        a.decir("Buscando " + q + " en YouTube.");
    }

    // ------------------------------------------------------------------ control total (Accesibilidad)

    private boolean necesitaControl(Anfitrion a) {
        if (LayAccesibilidad.activo()) return false;
        a.decir("Para eso necesito el control total. Te abro Accesibilidad: busca LAY y actívalo.");
        a.iniciar(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        return true;
    }

    private void accion(Anfitrion a, int global, String ok) {
        a.enSegundoPlano(() -> {
            if (LayAccesibilidad.global(global)) {
                if (ok != null) a.decir(ok);
            } else {
                a.decir("No pude hacerlo.");
            }
        });
    }

    private boolean globales(T t, Anfitrion a) {
        String n = t.n;
        Matcher m;
        if (n.matches("(?:ve |ir |vuelve |regresa |llevame )?(?:a la |al )?(?:pantalla de |pantalla )?(?:inicio|principal|home)")) {
            if (necesitaControl(a)) return true;
            LayAccesibilidad.global(AccessibilityService.GLOBAL_ACTION_HOME);
            return true;
        }
        if (n.matches("(?:ve |ir |vuelve |regresa |retrocede )?(?:atras|regresar|volver|regresa|vuelve|retroceder|retrocede)")) {
            if (necesitaControl(a)) return true;
            accion(a, AccessibilityService.GLOBAL_ACTION_BACK, null);
            return true;
        }
        if (p("\\b(?:recientes|multitarea|apps abiertas|aplicaciones abiertas)\\b").matcher(n).find()) {
            if (necesitaControl(a)) return true;
            LayAccesibilidad.global(AccessibilityService.GLOBAL_ACTION_RECENTS);
            return true;
        }
        if (p("\\b(?:ajustes rapidos|panel rapido)\\b").matcher(n).find()) {
            if (necesitaControl(a)) return true;
            LayAccesibilidad.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
            return true;
        }
        if (p("\\bnotificaciones\\b").matcher(n).find() && p("\\b(?:abre|abrir|muestra|muestrame|baja|ver|ve)\\b").matcher(n).find()) {
            if (necesitaControl(a)) return true;
            LayAccesibilidad.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            return true;
        }
        if (p("\\b(?:captura|screenshot|pantallazo)\\b").matcher(n).find()) {
            if (necesitaControl(a)) return true;
            if (Build.VERSION.SDK_INT < 28) {
                a.decir("La captura por voz necesita Android 9 o más.");
                return true;
            }
            accion(a, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Captura tomada.");
            return true;
        }
        if (p("\\b(?:bloquea|bloquear)\\b.*\\b(?:celular|telefono|pantalla|movil)\\b|\\bapaga la pantalla\\b").matcher(n).find()) {
            if (necesitaControl(a)) return true;
            if (Build.VERSION.SDK_INT < 28) {
                a.decir("Bloquear por voz necesita Android 9 o más.");
                return true;
            }
            LayAccesibilidad.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            return true;
        }
        if (p("\\b(?:apaga|apagar)\\b.*\\b(?:celular|telefono|movil)\\b|\\bmenu de apagado\\b").matcher(n).find()) {
            if (necesitaControl(a)) return true;
            LayAccesibilidad.global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
            return true;
        }
        m = p("^(?:desplaza(?:te)?|scroll|baja|sube)(?: la pantalla| la pagina)?(?: hacia)?(?: (abajo|arriba))?$").matcher(n);
        if (m.find()) {
            if (necesitaControl(a)) return true;
            final boolean adelante = m.group(1) != null ? m.group(1).equals("abajo") : !n.contains("sube");
            a.enSegundoPlano(() -> {
                if (!LayAccesibilidad.desplazar(adelante)) a.decir("No encontré nada que desplazar.");
            });
            return true;
        }
        m = p("^(?:toca|pulsa|presiona|selecciona|clic en|haz clic en|dale a|dale clic a)\\s+(?:el boton\\s+|el\\s+|la\\s+)?(.+)$").matcher(n);
        if (m.find()) {
            if (necesitaControl(a)) return true;
            final String objetivo = t.grupo(m, 1);
            a.enSegundoPlano(() -> {
                if (!LayAccesibilidad.tocar(objetivo)) a.decir("No veo «" + objetivo + "» en la pantalla.");
            });
            return true;
        }
        m = p("^(?:escribe|escribir|teclea|dicta)\\s+(.+)$").matcher(n);
        if (m.find()) {
            if (necesitaControl(a)) return true;
            final String texto = t.grupo(m, 1);
            a.enSegundoPlano(() -> {
                if (!LayAccesibilidad.escribir(texto)) a.decir("No encontré dónde escribir. Toca primero el campo de texto.");
            });
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ajustes, cámara, apps

    private boolean ajustes(T t, Anfitrion a) {
        String n = t.n;
        boolean ajuste = p("\\b(?:ajustes|configuracion|configuraciones|panel)\\b").matcher(n).find();
        boolean toggle = p("\\b(?:activa|activar|desactiva|desactivar|enciende|encender|prende|prender|apaga|apagar|pon|quita)\\b").matcher(n).find();
        boolean abre = p("\\b(?:abre|abrir|muestra|muestrame|entra|ve a|ir a)\\b").matcher(n).find();
        Intent i = null;
        String que = null;
        if (n.contains("wifi") || n.contains("wi fi")) {
            i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_WIFI) : new Intent(Settings.ACTION_WIFI_SETTINGS);
            que = "el wifi";
        } else if (n.contains("bluetooth")) {
            i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            que = "el bluetooth";
        } else if (p("\\b(?:datos moviles|datos|internet)\\b").matcher(n).find() && (ajuste || toggle)) {
            i = Build.VERSION.SDK_INT >= 29 ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) : new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
            que = "los datos";
        } else if (n.contains("modo avion")) {
            i = new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
            que = "el modo avión";
        } else if (n.contains("no molestar") || (n.contains("sonido") && ajuste)) {
            i = new Intent(Settings.ACTION_SOUND_SETTINGS);
            que = "el sonido";
        } else if (p("\\b(?:ubicacion|gps)\\b").matcher(n).find()) {
            i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            que = "la ubicación";
        } else if (n.contains("nfc")) {
            i = new Intent(Settings.ACTION_NFC_SETTINGS);
            que = "el NFC";
        } else if (n.contains("brillo") || (n.contains("pantalla") && ajuste)) {
            i = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
            que = "la pantalla";
        } else if (n.contains("bateria") && ajuste) {
            i = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
            que = "la batería";
        } else if (n.contains("almacenamiento") && (ajuste || abre)) {
            i = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
            que = "el almacenamiento";
        } else if (n.contains("accesibilidad")) {
            i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            que = "accesibilidad";
        } else if (n.matches("(?:abre |abrir |ve a |ir a )?(?:los |las |mis )?(?:ajustes|configuracion|configuraciones)(?: del telefono| del celular)?")) {
            i = new Intent(Settings.ACTION_SETTINGS);
            que = "los ajustes";
        }
        if (i == null || !(ajuste || toggle || abre || n.contains("brillo"))) return false;
        boolean cambia = toggle && !ajuste && !abre;
        a.decir(cambia ? "Android no me deja cambiar " + que + " directamente. Te abro el panel." : "Abriendo " + que + ".");
        if (!a.iniciar(i)) a.decir("No pude abrir ese ajuste.");
        return true;
    }

    private boolean camara(T t, Anfitrion a) {
        String n = t.n;
        if (!(p("\\b(?:abre|abrir|enciende|prende)\\b.*\\bcamara\\b").matcher(n).find() || p("\\btoma(?:r)? (?:una )?foto\\b|\\bselfie\\b").matcher(n).find())) return false;
        a.decir(a.iniciar(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) ? "Abriendo la cámara." : "No encontré la cámara.");
        return true;
    }

    private boolean abrirApp(T t, Anfitrion a) {
        Matcher m = p("^(?:abre|abrir|abreme|inicia|iniciar|lanza|entra a|entra en|ve a|ir a|ejecuta|open)\\s+(?:la |el |mi |las |los )?(?:app |aplicacion |aplicacion de |app de )?(.+)$").matcher(t.n);
        if (!m.find()) return false;
        String pedido = t.grupo(m, 1);
        Apps.Hallazgo h = Apps.buscar(a.ctx(), pedido);
        if (h == null) {
            a.decir("No encontré una app llamada «" + pedido + "».");
            return true;
        }
        a.decir(a.iniciar(h.intent) ? "Abriendo " + h.nombre + "." : "No pude abrir " + h.nombre + ".");
        return true;
    }
}
