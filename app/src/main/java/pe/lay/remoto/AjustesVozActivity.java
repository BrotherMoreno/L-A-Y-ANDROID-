package pe.lay.remoto;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Pantalla «🎙 Voz»: descarga el modelo de voz (una vez) y activa la palabra «Oye LAY» para hablarle sin tocar el celular. */
public class AjustesVozActivity extends Activity {
    private static final int CIAN = 0xFF65F4FF;
    private static final int HIELO = 0xFFD9FBFF;
    private static final int PEDIDO = 11;

    private SharedPreferences p;
    private TextView estadoModelo;
    private TextView btnDescargar;
    private Switch activar;
    private Switch exigir;
    private boolean ajustando;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = getSharedPreferences("lay_voz", Context.MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(18), dp(24));

        col.addView(texto("🎙 «OYE LAY»", 17, CIAN, true));
        col.addView(texto("Dile «Oye LAY» y luego tu orden, sin tocar el celular (también con la pantalla apagada). "
                + "La palabra de activación se detecta SIN internet, solo en tu celular; tu orden la entiende el reconocedor de voz de Google.", 14, HIELO, false));

        col.addView(texto("1. MODELO DE VOZ", 11, CIAN, true));
        estadoModelo = texto("", 14, HIELO, false);
        col.addView(estadoModelo);
        btnDescargar = boton("Descargar modelo (≈ 40 MB, una sola vez)");
        btnDescargar.setOnClickListener(v -> descargar());
        col.addView(btnDescargar, espacio(6));

        col.addView(texto("2. ACTIVAR", 11, CIAN, true));
        activar = new Switch(this);
        activar.setText("Escuchar «Oye LAY»");
        activar.setTextColor(HIELO);
        activar.setOnCheckedChangeListener((v, marcado) -> {
            if (ajustando) return;
            if (marcado) intentarActivar();
            else stopService(new Intent(this, EscuchaService.class));
        });
        col.addView(activar, espacio(6));

        exigir = new Switch(this);
        exigir.setText("Exigir decir «Oye» (menos activaciones por error)");
        exigir.setTextColor(HIELO);
        exigir.setChecked(p.getBoolean("exigir", true));
        exigir.setOnCheckedChangeListener((v, marcado) -> p.edit().putBoolean("exigir", marcado).apply());
        col.addView(exigir, espacio(10));

        col.addView(texto("Requisitos y avisos: da permiso de micrófono y de notificaciones cuando lo pida. Mientras está activo se ve una notificación fija "
                + "(puedes detenerlo desde ahí) y gasta más batería. Si se reinicia el celular hay que volver a activarlo aquí. "
                + "«LAY» no es una palabra del español: el modelo a veces la oye como «ley». Di «Oye LAY» claro y a distancia normal. "
                + "Para abrir apps con la pantalla apagada o en otra app, activa también el control total (Accesibilidad): "
                + "Android limita abrir pantallas desde segundo plano.", 12, 0xAABAFAFF, false), espacio(14));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF020B11);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refrescar();
    }

    private void refrescar() {
        boolean listo = ModeloVoz.listo(this);
        estadoModelo.setText(listo ? "Modelo de voz: listo ✔" : "Modelo de voz: no descargado");
        btnDescargar.setVisibility(listo ? android.view.View.GONE : android.view.View.VISIBLE);
        ajustando = true;
        activar.setChecked(EscuchaService.corriendo);
        ajustando = false;
    }

    private void descargar() {
        btnDescargar.setEnabled(false);
        estadoModelo.setText("Descargando…");
        ModeloVoz.descargar(this, (msg, fin) -> {
            estadoModelo.setText(msg);
            if (fin) {
                btnDescargar.setEnabled(true);
                refrescar();
            }
        });
    }

    private void intentarActivar() {
        if (!ModeloVoz.listo(this)) {
            estadoModelo.setText("Primero descarga el modelo de voz.");
            ajustar(false);
            return;
        }
        boolean micro = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!micro) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, PEDIDO);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PEDIDO);
            }
            return;
        }
        arrancar();
    }

    @Override
    public void onRequestPermissionsResult(int pedido, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(pedido, permisos, resultados);
        if (pedido != PEDIDO) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            arrancar();
        } else {
            estadoModelo.setText("Sin permiso de micrófono no puedo escucharte.");
            ajustar(false);
        }
    }

    private void arrancar() {
        startForegroundService(new Intent(this, EscuchaService.class));
        ajustar(true);
        estadoModelo.setText("Activo. Di «Oye LAY» y tu orden.");
    }

    private void ajustar(boolean valor) {
        ajustando = true;
        activar.setChecked(valor);
        ajustando = false;
    }

    // ------------------------------------------------------------------ UI

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
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView boton(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(CIAN);
        v.setTextSize(14);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x990A2733);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), 0x5965F4FF);
        v.setBackground(g);
        return v;
    }
}
