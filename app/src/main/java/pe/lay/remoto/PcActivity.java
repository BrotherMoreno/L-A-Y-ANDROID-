package pe.lay.remoto;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Pantalla "Mi PC": envoltorio de la página /remoto de tu PC (control remoto de LAY de escritorio).
 *  - Primera vez: pantalla de vinculación (assets/setup.html) → guarda el enlace de tu PC (Tailscale + token).
 *  - Después: abre directo tu PC. Si no llega (Tailscale apagado), vuelve a la pantalla de vinculación con el aviso.
 *  - Micrófono: usa el reconocedor de voz del sistema (el del navegador no existe dentro de un WebView).
 */
public class PcActivity extends Activity {
    private static final int PEDIDO_VOZ = 7;
    private static final String SETUP = "file:///android_asset/setup.html";
    private static final String PREFS = "lay";
    private static final String CLAVE_URL = "url";

    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        web = new WebView(this);
        web.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(0xFF020B11);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); // solo se leen nuestros assets (file:///android_asset/)
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Puente(), "LayNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (esNuestra(u)) return false;
                // Cualquier otro sitio se abre en el navegador, nunca dentro de la app (el puente LayNative es solo nuestro)
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignorada) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !SETUP.equals(req.getUrl().toString().split("#")[0])) {
                    v.loadUrl(SETUP + "#sinconexion"); // PC apagada o Tailscale sin conectar
                }
            }
        });

        String guardada = prefs.getString(CLAVE_URL, null);
        web.loadUrl(guardada != null ? guardada : SETUP);
    }

    /** Solo se navega dentro de la app a nuestros assets o al servidor guardado. */
    private boolean esNuestra(Uri u) {
        if ("file".equals(u.getScheme())) return u.toString().startsWith("file:///android_asset/");
        String guardada = prefs.getString(CLAVE_URL, null);
        if (guardada == null) return false;
        Uri g = Uri.parse(guardada);
        return g.getHost() != null && g.getHost().equals(u.getHost()) && g.getPort() == u.getPort();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent datos) {
        super.onActivityResult(pedido, resultado, datos);
        if (pedido != PEDIDO_VOZ) return;
        String texto = "";
        if (resultado == RESULT_OK && datos != null) {
            ArrayList<String> r = datos.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) texto = r.get(0);
        }
        devolverDictado(texto);
    }

    private void devolverDictado(String texto) {
        web.evaluateJavascript("window.layDictado&&window.layDictado(" + JSONObject.quote(texto) + ")", null);
    }

    /** Lo que la página puede pedirle a la app. Solo se llama desde nuestras páginas (ver esNuestra). */
    private class Puente {
        @JavascriptInterface
        public String servidor() {
            return prefs.getString(CLAVE_URL, "");
        }

        @JavascriptInterface
        public boolean vincular(String url) {
            Uri u = Uri.parse(url == null ? "" : url.trim());
            boolean valida = ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null;
            if (!valida) return false;
            prefs.edit().putString(CLAVE_URL, u.toString()).apply();
            runOnUiThread(() -> web.loadUrl(u.toString()));
            return true;
        }

        @JavascriptInterface
        public void olvidar() {
            prefs.edit().remove(CLAVE_URL).apply();
            runOnUiThread(() -> web.loadUrl(SETUP));
        }

        @JavascriptInterface
        public void dictar() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dile algo a LAY");
                try {
                    startActivityForResult(i, PEDIDO_VOZ);
                } catch (ActivityNotFoundException e) {
                    devolverDictado(""); // el celular no trae reconocedor de voz
                }
            });
        }
    }
}
