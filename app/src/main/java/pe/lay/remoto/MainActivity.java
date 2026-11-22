package pe.lay.remoto;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * LAY móvil: tu asistente dentro del celular. Toca el círculo, di lo que quieres y LAY lo hace en ESTE teléfono
 * (abrir apps, llamar, mensajes, linterna, volumen, alarmas, tocar la pantalla…). "Mi PC" abre el control remoto de LAY de escritorio.
 */
public class MainActivity extends Activity implements Cerebro.Anfitrion, TextToSpeech.OnInitListener {
    private static final int PEDIDO_VOZ = 7;
    private static final int PEDIDO_PERMISO = 42;
    private static final int CIAN = 0xFF65F4FF;
    private static final int FONDO = 0xFF020B11;
    private static final int HIELO = 0xFFD9FBFF;
    private static final int VERDE = 0xFF5DFFB0;
    private static final int AMBAR = 0xFFFFB547;

    private final Cerebro cerebro = new Cerebro();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsListo;
    private Runnable alTerminarDeHablar;
    private Runnable permisoOk;
    private Runnable permisoNo;

    private LinearLayout chat;
    private ScrollView scroll;
    private EditText entrada;
    private TextView btnControl;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tts = new TextToSpeech(this, this);
        construirPantalla();
        burbuja("Hola, soy LAY. Toca el círculo y dime qué hacer con tu celular. Di «ayuda» para ver mis órdenes.", false);
        revisarIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        revisarIntent(i);
    }

    private void revisarIntent(Intent i) {
        if (i != null && i.getBooleanExtra("escuchar", false)) {
            i.removeExtra("escuchar");
            ui.postDelayed(this::escuchar, 600);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean activo = LayAccesibilidad.activo();
        btnControl.setText(activo ? "● Control total activo" : "○ Activar control total");
        btnControl.setTextColor(activo ? VERDE : AMBAR);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ voz

    @Override
    public void onInit(int estado) {
        if (estado != TextToSpeech.SUCCESS) return;
        int r = tts.setLanguage(new Locale("es", "PE"));
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(new Locale("es"));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                terminoDeHablar(id);
            }

            @Override
            public void onError(String id) {
                terminoDeHablar(id);
            }
        });
        ttsListo = true;
    }

    private void terminoDeHablar(String id) {
        if (!"q".equals(id)) return;
        Runnable r = alTerminarDeHablar;
        alTerminarDeHablar = null;
        if (r != null) ui.post(r);
    }

    private void hablar(String texto, String id) {
        if (ttsListo) tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void escuchar() {
        if (ttsListo) tts.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime qué hago");
        try {
            startActivityForResult(i, PEDIDO_VOZ);
        } catch (ActivityNotFoundException e) {
            decir("Tu celular no trae reconocimiento de voz. Escríbeme la orden abajo.");
        }
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        super.onActivityResult(pedido, resultado, datos);
        if (pedido != PEDIDO_VOZ || resultado != RESULT_OK || datos == null) return;
        ArrayList<String> r = datos.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r != null && !r.isEmpty()) procesar(r.get(0));
    }

    private void procesar(String texto) {
        texto = texto.trim();
        if (texto.isEmpty()) return;
        burbuja(texto, true);
        try {
            cerebro.ejecutar(texto, this);
        } catch (RuntimeException e) {
            decir("Algo falló con esa orden.");
        }
    }

    // ------------------------------------------------------------------ Cerebro.Anfitrion

    @Override
    public Context ctx() {
        return this;
    }

    @Override
    public void decir(String texto) {
        runOnUiThread(() -> {
            burbuja(texto, false);
            hablar(texto, "d");
        });
    }

    @Override
    public void decirYEscuchar(String texto) {
        runOnUiThread(() -> {
            burbuja(texto, false);
            if (ttsListo) {
                alTerminarDeHablar = this::escuchar;
                hablar(texto, "q");
            } else {
                ui.postDelayed(this::escuchar, 1200);
            }
        });
    }

    @Override
    public boolean iniciar(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    @Override
    public void enSegundoPlano(Runnable r) {
        moveTaskToBack(true);
        ui.postDelayed(r, 900);
    }

    @Override
    public void permiso(String permiso, Runnable concedido, Runnable negado) {
        if (checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED) {
            concedido.run();
            return;
        }
        permisoOk = concedido;
        permisoNo = negado;
        requestPermissions(new String[]{permiso}, PEDIDO_PERMISO);
    }

    @Override
    public void onRequestPermissionsResult(int pedido, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(pedido, permisos, resultados);
        if (pedido != PEDIDO_PERMISO) return;
        Runnable r = resultados.length > 0 && resultados[0] == PackageManager.PERMISSION_GRANTED ? permisoOk : permisoNo;
        permisoOk = null;
        permisoNo = null;
        if (r != null) r.run();
    }

    @Override
    public void detenerVoz() {
        alTerminarDeHablar = null;
        if (ttsListo) tts.stop();
    }

    // ------------------------------------------------------------------ pantalla (sin XML: todo en código)

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable fondo(int color, int radioDp, int bordeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radioDp));
        if (bordeColor != 0) g.setStroke(dp(1), bordeColor);
        return g;
    }

    private void burbuja(String texto, boolean yo) {
        TextView v = new TextView(this);
        v.setText(texto);
        v.setTextColor(HIELO);
        v.setTextSize(15);
        v.setPadding(dp(13), dp(10), dp(13), dp(10));
        v.setBackground(yo ? fondo(0x591485A3, 14, 0x5965F4FF) : fondo(0xD905232F, 14, 0x2E65F4FF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = yo ? Gravity.END : Gravity.START;
        lp.setMargins(yo ? dp(56) : 0, dp(4), yo ? 0 : dp(56), dp(4));
        v.setLayoutParams(lp);
        chat.addView(v);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView boton(String texto, int colorTexto) {
        TextView t = new TextView(this);
        t.setText(texto);
        t.setTextColor(colorTexto);
        t.setTextSize(13);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(fondo(0x990A2733, 18, 0x5965F4FF));
        return t;
    }

    private void construirPantalla() {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(FONDO);
        raiz.setPadding(dp(14), dp(14), dp(14), dp(10));

        // Encabezado: círculo LAY (toca para hablar) + estado + Mi PC
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        TextView orbe = new TextView(this);
        orbe.setText("LAY");
        orbe.setTextColor(0xFFE9FFFF);
        orbe.setTextSize(15);
        orbe.setTypeface(Typeface.DEFAULT_BOLD);
        orbe.setGravity(Gravity.CENTER);
        GradientDrawable o = new GradientDrawable();
        o.setShape(GradientDrawable.OVAL);
        o.setColor(0x331485A3);
        o.setStroke(dp(2), CIAN);
        orbe.setBackground(o);
        orbe.setOnClickListener(v -> escuchar());
        cab.addView(orbe, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout centro = new LinearLayout(this);
        centro.setOrientation(LinearLayout.VERTICAL);
        centro.setPadding(dp(12), 0, 0, 0);
        TextView titulo = new TextView(this);
        titulo.setText("LAY MÓVIL");
        titulo.setTextColor(HIELO);
        titulo.setTextSize(15);
        titulo.setLetterSpacing(0.2f);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        centro.addView(titulo);
        btnControl = new TextView(this);
        btnControl.setTextSize(12);
        btnControl.setPadding(0, dp(4), 0, dp(4));
        btnControl.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        centro.addView(btnControl);
        cab.addView(centro, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pc = boton("💻 Mi PC", CIAN);
        pc.setOnClickListener(v -> startActivity(new Intent(this, PcActivity.class)));
        cab.addView(pc);
        raiz.addView(cab);

        // Conversación
        scroll = new ScrollView(this);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setPadding(0, dp(10), 0, dp(6));
        scroll.addView(chat);
        raiz.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Atajos
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout atajos = new LinearLayout(this);
        atajos.setOrientation(LinearLayout.HORIZONTAL);
        String[] frases = {"Enciende la linterna", "¿Qué hora es?", "¿Cuánta batería tengo?", "Sube el volumen", "Abre WhatsApp", "Captura de pantalla", "Ayuda"};
        for (String f : frases) {
            TextView c = boton(f, HIELO);
            c.setOnClickListener(v -> procesar(f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            atajos.addView(c, lp);
        }
        hs.addView(atajos);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(6), 0, dp(8));
        raiz.addView(hs, hlp);

        // Escribir o hablar
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        entrada = new EditText(this);
        entrada.setHint("Escribe una orden…");
        entrada.setHintTextColor(0x88BAFAFF);
        entrada.setTextColor(HIELO);
        entrada.setTextSize(16);
        entrada.setSingleLine(true);
        entrada.setInputType(InputType.TYPE_CLASS_TEXT);
        entrada.setImeOptions(EditorInfo.IME_ACTION_SEND);
        entrada.setPadding(dp(16), dp(10), dp(16), dp(10));
        entrada.setBackground(fondo(0xCC05232F, 24, 0x5965F4FF));
        entrada.setOnEditorActionListener((v, acc, ev) -> {
            enviarEscrito();
            return true;
        });
        fila.addView(entrada, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mic = boton("🎤", HIELO);
        mic.setTextSize(20);
        mic.setOnClickListener(v -> escuchar());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(dp(8), 0, 0, 0);
        fila.addView(mic, mlp);
        raiz.addView(fila);

        setContentView(raiz);
    }

    private void enviarEscrito() {
        String t = entrada.getText().toString();
        entrada.setText("");
        procesar(t);
    }
}
