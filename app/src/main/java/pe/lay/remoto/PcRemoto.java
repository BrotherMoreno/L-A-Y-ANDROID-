package pe.lay.remoto;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Manda una orden a LAY de tu PC (si la vinculaste desde "Mi PC"). Ej.: "en la PC modo trabajo". */
final class PcRemoto {
    interface Resultado {
        void listo(String mensaje);
    }

    private PcRemoto() {
    }

    static boolean vinculada(Context c) {
        return !c.getSharedPreferences("lay", Context.MODE_PRIVATE).getString("url", "").isEmpty();
    }

    static void enviar(Context c, String texto, Resultado r) {
        final String url = c.getSharedPreferences("lay", Context.MODE_PRIVATE).getString("url", "");
        new Thread(() -> {
            String msg;
            try {
                Uri u = Uri.parse(url);
                String token = null;
                String frag = u.getEncodedFragment();
                if (frag != null) {
                    for (String p : frag.split("&")) {
                        if (p.startsWith("token=")) token = Uri.decode(p.substring(6));
                    }
                }
                URL destino = new URL(u.getScheme() + "://" + u.getAuthority() + "/api/comando");
                HttpURLConnection h = (HttpURLConnection) destino.openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(5000);
                h.setReadTimeout(8000);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (token != null) h.setRequestProperty("Authorization", "Bearer " + token);
                byte[] cuerpo = new JSONObject().put("texto", texto).toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream o = h.getOutputStream()) {
                    o.write(cuerpo);
                }
                int codigo = h.getResponseCode();
                if (codigo == 200) msg = "Listo, se lo mandé a tu PC.";
                else if (codigo == 403) msg = "Tu PC rechazó el comando. Revisa el token o que Tailscale esté activo.";
                else msg = "Tu PC respondió con error " + codigo + ".";
            } catch (Exception e) {
                msg = "No llego a tu PC. ¿Tailscale está encendido en los dos?";
            }
            final String m = msg;
            new Handler(Looper.getMainLooper()).post(() -> r.listo(m));
        }).start();
    }
}
