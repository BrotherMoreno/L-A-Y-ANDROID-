package pe.lay.remoto;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Pantalla «🧠 IA»: eliges el servicio (Gemini tiene plan gratis), el modelo y tu clave. Se guarda solo en este celular. */
public class AjustesIaActivity extends Activity {
    private static final int CIAN = 0xFF65F4FF;
    private static final int HIELO = 0xFFD9FBFF;

    private SharedPreferences p;
    private Switch activa;
    private Switch pantalla;
    private EditText url;
    private EditText modelo;
    private EditText clave;
    private TextView resultado;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences(Agente.PREFS, Context.MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(18), dp(24));

        col.addView(texto("🧠 IA DE LAY", 17, CIAN, true));
        col.addView(texto("Con la IA, LAY entiende órdenes libres y encadena acciones: «abre Spotify y busca salsa», «dile a mamá que llego tarde». "
                + "Sin IA o sin internet siguen funcionando mis reglas locales.", 14, HIELO, false));

        activa = new Switch(this);
        activa.setText("IA activada");
        activa.setTextColor(HIELO);
        activa.setChecked(p.getBoolean("activa", false));
        col.addView(activa, espacio(14));

        col.addView(texto("SERVICIO", 11, CIAN, true));
        TextView gemini = boton("Gemini (plan gratis, sin tarjeta)");
        gemini.setOnClickListener(v -> {
            url.setText(Agente.URL_GEMINI);
            modelo.setText(Agente.MODELO_GEMINI);
        });
        col.addView(gemini, espacio(4));
        col.addView(texto("Gemini: crea la clave gratis en aistudio.google.com/apikey. Otros servicios compatibles con la API de OpenAI "
                + "(Groq, OpenRouter, OpenAI…): escribe su dirección base, un modelo con herramientas y tu clave.", 12, 0xAABAFAFF, false));

        col.addView(texto("DIRECCIÓN BASE", 11, CIAN, true));
        url = campo(p.getString("url", ""), "https://…/v1beta/openai", false);
        col.addView(url);
        col.addView(texto("MODELO", 11, CIAN, true));
        modelo = campo(p.getString("modelo", ""), "gemini-3.8-flash", false);
        col.addView(modelo);
        col.addView(texto("CLAVE (API KEY)", 11, CIAN, true));
        clave = campo(p.getString("clave", ""), "pega tu clave aquí", true);
        col.addView(clave);

        pantalla = new Switch(this);
        pantalla.setText("Dejar que la IA lea el texto de la pantalla (necesita control total)");
        pantalla.setTextColor(HIELO);
        pantalla.setChecked(p.getBoolean("pantalla", false));
        col.addView(pantalla, espacio(14));

        TextView guardar = boton("Guardar");
        guardar.setOnClickListener(v -> guardar());
        col.addView(guardar, espacio(14));
        TextView probar = boton("Probar conexión");
        probar.setOnClickListener(v -> {
            guardar();
            resultado.setText("Probando…");
            Agente.probar(this, m -> resultado.setText(m));
        });
        col.addView(probar, espacio(8));
        resultado = texto("", 13, HIELO, false);
        col.addView(resultado, espacio(8));

        col.addView(texto("PRIVACIDAD: con la IA activada, lo que dices o escribes y los resultados de las acciones (hora, batería, «no encontré a X») "
                + "se envían al servicio que elijas. Tu agenda de contactos y el texto de los mensajes guardados NO se envían. "
                + "Si activas «leer pantalla», el texto visible (sin contraseñas) también se envía. En planes gratis algunos servicios pueden usar esos datos "
                + "para mejorar sus productos: revisa sus términos. La clave se guarda solo en este celular.", 12, 0xAABAFAFF, false), espacio(14));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF020B11);
        sv.addView(col);
        setContentView(sv);
    }

    private void guardar() {
        p.edit()
                .putBoolean("activa", activa.isChecked())
                .putBoolean("pantalla", pantalla.isChecked())
                .putString("url", url.getText().toString().trim())
                .putString("modelo", modelo.getText().toString().trim())
                .putString("clave", clave.getText().toString().trim())
                .apply();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams espacio(int arribaDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(arribaDp), 0, 0);
        return lp;
    }

    private TextView texto(String t, int sp, int color, boolean negrita) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(0, dp(10), 0, dp(2));
        if (negrita) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    private EditText campo(String valor, String pista, boolean secreto) {
        EditText e = new EditText(this);
        e.setText(valor);
        e.setHint(pista);
        e.setHintTextColor(0x66BAFAFF);
        e.setTextColor(HIELO);
        e.setSingleLine(true);
        e.setInputType(secreto ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xCC05232F);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0x5965F4FF);
        e.setBackground(g);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        return e;
    }

    private TextView boton(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(CIAN);
        v.setTextSize(14);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x990A2733);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), 0x5965F4FF);
        v.setBackground(g);
        return v;
    }
}
