package pe.lay.remoto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Modelo de voz en español de Vosk (≈40 MB, Apache 2.0). Se descarga una sola vez y queda en el almacenamiento privado de la app. */
final class ModeloVoz {
    static final String URL_MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";

    interface Progreso {
        void avance(String mensaje, boolean terminado);
    }

    private ModeloVoz() {
    }

    private static File base(Context c) {
        return new File(c.getFilesDir(), "vosk");
    }

    /** Carpeta del modelo (la que contiene "am"), o null si aún no está descargado. */
    static File ruta(Context c) {
        File[] hijos = base(c).listFiles();
        if (hijos == null) return null;
        for (File f : hijos) {
            if (f.isDirectory() && new File(f, "am").isDirectory()) return f;
        }
        return null;
    }

    static boolean listo(Context c) {
        return ruta(c) != null;
    }

    static void descargar(Context ctx, Progreso r) {
        final Context app = ctx.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File zip = new File(app.getCacheDir(), "modelo-voz.zip");
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(URL_MODELO).openConnection();
                h.setConnectTimeout(15000);
                h.setReadTimeout(30000);
                if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
                long total = h.getContentLengthLong();
                long leido = 0;
                int ultimo = -1;
                try (InputStream in = new BufferedInputStream(h.getInputStream()); OutputStream out = new FileOutputStream(zip)) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        leido += n;
                        int pct = total > 0 ? (int) (leido * 100 / total) : -1;
                        if (pct != ultimo && pct % 5 == 0) {
                            ultimo = pct;
                            final String m = "Descargando… " + (pct >= 0 ? pct + " %" : (leido / 1048576) + " MB");
                            ui.post(() -> r.avance(m, false));
                        }
                    }
                }
                ui.post(() -> r.avance("Descomprimiendo…", false));
                File destino = base(app);
                destino.mkdirs();
                String raiz = destino.getCanonicalPath() + File.separator;
                try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
                    ZipEntry e;
                    byte[] b = new byte[16384];
                    while ((e = zin.getNextEntry()) != null) {
                        File f = new File(destino, e.getName());
                        if (!f.getCanonicalPath().startsWith(raiz)) throw new SecurityException("ruta no válida en el zip");
                        if (e.isDirectory()) {
                            f.mkdirs();
                            continue;
                        }
                        f.getParentFile().mkdirs();
                        try (OutputStream out = new FileOutputStream(f)) {
                            int n;
                            while ((n = zin.read(b)) > 0) out.write(b, 0, n);
                        }
                    }
                }
                zip.delete();
                final boolean ok = listo(app);
                ui.post(() -> r.avance(ok ? "Modelo listo ✔" : "El archivo descargado no parece un modelo de voz.", true));
            } catch (Exception ex) {
                zip.delete();
                final String m = "No pude descargar el modelo (" + ex.getMessage() + "). Revisa tu internet y vuelve a intentar.";
                ui.post(() -> r.avance(m, true));
            }
        }).start();
    }
}
