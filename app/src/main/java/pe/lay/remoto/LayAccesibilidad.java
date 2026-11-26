package pe.lay.remoto;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.function.Predicate;

/**
 * "Control total": las manos de LAY dentro del celular (equivale a pywinauto en la PC).
 * Solo actúa cuando Cerebro se lo pide por una orden del usuario; no lee ni guarda la pantalla.
 */
public class LayAccesibilidad extends AccessibilityService {
    private static LayAccesibilidad instancia;

    @Override
    protected void onServiceConnected() {
        instancia = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instancia = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instancia = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override
    public void onInterrupt() {
    }

    static boolean activo() {
        return instancia != null;
    }

    /** GLOBAL_ACTION_HOME, _BACK, _RECENTS, _NOTIFICATIONS, _QUICK_SETTINGS, _POWER_DIALOG, _LOCK_SCREEN, _TAKE_SCREENSHOT. */
    static boolean global(int accion) {
        return instancia != null && instancia.performGlobalAction(accion);
    }

    /** Toca el botón/elemento visible cuyo texto (o descripción) contiene lo dicho. Prefiere coincidencia exacta. */
    static boolean tocar(String texto) {
        if (instancia == null) return false;
        AccessibilityNodeInfo raiz = instancia.getRootInActiveWindow();
        if (raiz == null) return false;
        List<AccessibilityNodeInfo> lista = raiz.findAccessibilityNodeInfosByText(texto);
        if (lista == null || lista.isEmpty()) return false;
        AccessibilityNodeInfo elegido = lista.get(0);
        for (AccessibilityNodeInfo n : lista) {
            if (igual(n.getText(), texto) || igual(n.getContentDescription(), texto)) {
                elegido = n;
                break;
            }
        }
        AccessibilityNodeInfo x = elegido;
        while (x != null && !x.isClickable()) x = x.getParent();
        return (x != null ? x : elegido).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** Texto visible de la pantalla actual (sin campos de contraseña), para que la IA decida el siguiente paso. */
    static String leerPantalla() {
        if (instancia == null) return "";
        AccessibilityNodeInfo raiz = instancia.getRootInActiveWindow();
        if (raiz == null) return "No pude leer la pantalla.";
        StringBuilder b = new StringBuilder();
        if (raiz.getPackageName() != null) b.append("App: ").append(raiz.getPackageName()).append('\n');
        recolectar(raiz, b, new int[]{0});
        return b.length() == 0 ? "La pantalla no muestra texto legible." : b.toString();
    }

    private static void recolectar(AccessibilityNodeInfo n, StringBuilder b, int[] cuenta) {
        if (n == null || cuenta[0] >= 80 || b.length() > 2500) return;
        if (!n.isPassword() && n.isVisibleToUser()) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String s = t != null && t.length() > 0 ? t.toString() : d != null ? d.toString() : "";
            s = s.trim();
            if (!s.isEmpty()) {
                b.append(n.isEditable() ? "[campo] " : n.isClickable() ? "[botón] " : "").append(s.length() > 120 ? s.substring(0, 120) : s).append('\n');
                cuenta[0]++;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) recolectar(n.getChild(i), b, cuenta);
    }

    /** Abre una pantalla aunque LAY esté en segundo plano (Android 10+ lo bloquea a las apps comunes; a un servicio de accesibilidad activo, normalmente no). */
    static boolean iniciar(Intent i) {
        if (instancia == null) return false;
        try {
            instancia.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean igual(CharSequence a, String b) {
        return a != null && a.toString().trim().equalsIgnoreCase(b.trim());
    }

    static boolean desplazar(boolean adelante) {
        if (instancia == null) return false;
        AccessibilityNodeInfo raiz = instancia.getRootInActiveWindow();
        if (raiz == null) return false;
        AccessibilityNodeInfo n = primero(raiz, AccessibilityNodeInfo::isScrollable);
        if (n == null) return false;
        return n.performAction(adelante ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    /** Escribe en el campo de texto con foco (o en el primero editable de la pantalla). */
    static boolean escribir(String texto) {
        if (instancia == null) return false;
        AccessibilityNodeInfo raiz = instancia.getRootInActiveWindow();
        if (raiz == null) return false;
        AccessibilityNodeInfo campo = raiz.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (campo == null || !campo.isEditable()) campo = primero(raiz, AccessibilityNodeInfo::isEditable);
        if (campo == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto);
        return campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private static AccessibilityNodeInfo primero(AccessibilityNodeInfo n, Predicate<AccessibilityNodeInfo> cond) {
        if (n == null) return null;
        if (cond.test(n)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = primero(n.getChild(i), cond);
            if (r != null) return r;
        }
        return null;
    }
}
