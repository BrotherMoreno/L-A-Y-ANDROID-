package pe.lay.remoto;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

/** Busca un contacto por nombre (sin tildes ni mayúsculas). Requiere el permiso READ_CONTACTS. */
final class Contactos {
    private Contactos() {
    }

    /** @return {nombre, número} o null si no hay un contacto parecido. */
    static String[] buscar(Context ctx, String pedido) {
        String q = Cerebro.limpiar(pedido).trim().replaceFirst("^(?:mi|mis|el|la)\\s+", "");
        if (q.isEmpty()) return null;
        String[] tokens = q.split("\\s+");
        String[] mejor = null;
        int mejorPuntos = 0;
        try (Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String nombre = c.getString(0);
                String numero = c.getString(1);
                if (nombre == null || numero == null) continue;
                String n = Cerebro.limpiar(nombre).trim();
                int puntos = 0;
                if (n.equals(q)) puntos = 100;
                else if (n.startsWith(q)) puntos = 90;
                else {
                    boolean todos = true;
                    for (String t : tokens) {
                        if (!n.contains(t)) {
                            todos = false;
                            break;
                        }
                    }
                    if (todos) puntos = 70;
                }
                if (puntos > 0) puntos -= Math.min(20, Math.abs(n.length() - q.length()) / 2);
                if (puntos > mejorPuntos) {
                    mejorPuntos = puntos;
                    mejor = new String[]{nombre, numero};
                }
            }
        } catch (SecurityException e) {
            return null;
        }
        return mejor;
    }
}
