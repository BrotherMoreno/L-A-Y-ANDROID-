package pe.lay.remoto;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

/** Encuentra una app instalada por su nombre hablado ("whatsapp", "la calculadora"). */
final class Apps {
    private Apps() {
    }

    static final class Hallazgo {
        final String nombre;
        final Intent intent;

        Hallazgo(String nombre, Intent intent) {
            this.nombre = nombre;
            this.intent = intent;
        }
    }

    private static String alias(String q) {
        switch (q) {
            case "wasap":
            case "guasap":
            case "wsp":
            case "whats":
                return "whatsapp";
            case "face":
                return "facebook";
            case "insta":
                return "instagram";
            case "mapas":
                return "maps";
            case "tienda":
            case "play":
                return "play store";
            case "telefono":
                return "telefono";
            default:
                return q;
        }
    }

    static Hallazgo buscar(Context ctx, String pedido) {
        String q = alias(Cerebro.limpiar(pedido).trim().replaceAll("\\s+", " "));
        if (q.length() < 2) return null;
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> lista = pm.queryIntentActivities(main, 0);
        ResolveInfo mejor = null;
        String mejorNombre = null;
        int mejorPuntos = 0;
        for (ResolveInfo ri : lista) {
            String nombre = String.valueOf(ri.loadLabel(pm));
            String l = Cerebro.limpiar(nombre).trim().replaceAll("\\s+", " ");
            int puntos = 0;
            if (l.equals(q)) puntos = 100;
            else if (l.startsWith(q)) puntos = 80 - Math.min(30, l.length() - q.length());
            else if (l.length() >= 3 && q.contains(l)) puntos = 60;
            else if (q.length() >= 3 && l.contains(q)) puntos = 50 - Math.min(30, l.length() - q.length());
            if (puntos > mejorPuntos) {
                mejorPuntos = puntos;
                mejor = ri;
                mejorNombre = nombre;
            }
        }
        if (mejor == null) return null;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(mejor.activityInfo.packageName, mejor.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return new Hallazgo(mejorNombre, i);
    }
}
