package pe.lay.remoto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.util.ArrayList;
import java.util.Locale;

/**
 * "Oye LAY": escucha SIEMPRE la palabra de activación sin internet (Vosk, solo en el celular) y, al oírla, toma tu orden
 * con el reconocedor de voz de Google y la ejecuta como si la hubieras escrito en LAY. Servicio en primer plano
 * (notificación fija) para que Android no lo cierre. Consume batería: actívalo cuando lo necesites.
 */
public class EscuchaService extends Service implements Cerebro.Anfitrion {
    static final String ACCION_DETENER = "pe.lay.remoto.DETENER";
    static volatile boolean corriendo;

    private static final String CANAL = "lay_escucha";
    private static final String GRAMATICA = "[\"oye ley\", \"oye lay\", \"oye lai\", \"hey ley\", \"hey lay\", \"ok ley\", \"hola ley\", \"ley\", \"lay\", \"lai\", \"lei\", \"[unk]\"]";

    private enum Fase { INICIANDO, CLAVE, PREGUNTA, COMANDO, OCUPADO }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Cerebro cerebro = new Cerebro();
    private Fase fase = Fase.INICIANDO;
    private Model modelo;
    private SpeechService escucha;
    private SpeechRecognizer reconocedor;
    private TextToSpeech tts;
    private volatile boolean ttsListo;
    private volatile boolean hablando;
    private PowerManager.WakeLock wake;
    private boolean exigirOye = true;

    private final Runnable reanudar = new Runnable() {
        @Override
        public void run() {
            if (fase == Fase.COMANDO || fase == Fase.PREGUNTA) return;
            if (hablando) {
                programarReanudar(800);
                return;
            }
            escucharClave();
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACCION_DETENER.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (corriendo) return START_NOT_STICKY;
        corriendo = true;
        exigirOye = getSharedPreferences("lay_voz", MODE_PRIVATE).getBoolean("exigir", true);
        iniciarPrimerPlano("Cargando la voz…");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lay:escucha");
        wake.acquire(12L * 60 * 60 * 1000);

        tts = new TextToSpeech(this, estado -> {
            if (estado != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("es", "PE"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(new Locale("es"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    hablando = true;
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
        });

        new Thread(() -> {
            try {
                final Model m = new Model(ModeloVoz.ruta(this).getAbsolutePath());
                ui.post(() -> {
                    if (!corriendo) {
                        m.close();
                        return;
                    }
                    modelo = m;
                    escucharClave();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    actualizarNotificacion("No pude cargar el modelo de voz. Descárgalo de nuevo en «🎙 Voz».");
                    stopSelfDespues();
                });
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        corriendo = false;
        ui.removeCallbacksAndMessages(null);
        liberarClave();
        liberarReconocedor();
        if (modelo != null) modelo.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private void stopSelfDespues() {
        ui.postDelayed(this::stopSelf, 4000);
    }

    // ------------------------------------------------------------------ palabra de activación (Vosk)

    private void escucharClave() {
        if (modelo == null || escucha != null || !corriendo) return;
        try {
            Recognizer rec = new Recognizer(modelo, 16000.0f, GRAMATICA);
            escucha = new SpeechService(rec, 16000.0f);
            escucha.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String json) {
                    revisar(json, "partial");
                }

                @Override
                public void onResult(String json) {
                    revisar(json, "text");
                }

                @Override
                public void onFinalResult(String json) {
                }

                @Override
                public void onError(Exception e) {
                    actualizarNotificacion("Error de micrófono: " + e.getMessage());
                }

                @Override
                public void onTimeout() {
                }
            });
            fase = Fase.CLAVE;
            actualizarNotificacion("Di «Oye LAY»");
        } catch (Exception e) {
            escucha = null;
            actualizarNotificacion("No pude abrir el micrófono: " + e.getMessage());
        }
    }

    private void liberarClave() {
        if (escucha == null) return;
        try {
            escucha.cancel();
            escucha.shutdown();
        } catch (RuntimeException ignorada) {
        }
        escucha = null;
    }

    /** Extrae el texto del JSON de Vosk ({"text":"…"} o {"partial":"…"}) y mira si trae la palabra de activación. */
    private void revisar(String json, String clave) {
        if (fase != Fase.CLAVE) return;
        String t = json;
        int i = json.indexOf("\"" + clave + "\"");
        if (i >= 0) {
            int a = json.indexOf('"', json.indexOf(':', i) + 1);
            int b = a >= 0 ? json.indexOf('"', a + 1) : -1;
            t = a >= 0 && b > a ? json.substring(a + 1, b) : "";
        }
        if (esPalabraClave(t)) alOirPalabraClave();
    }

    private boolean esPalabraClave(String texto) {
        String n = Cerebro.limpiar(texto);
        if (!n.matches(".*\\b(?:ley|lei|lay|lai)\\b.*")) return false;
        return !exigirOye || n.matches(".*\\b(?:oye|hey|ok|hola)\\b.*");
    }

    private void alOirPalabraClave() {
        fase = Fase.PREGUNTA; // evita doble disparo mientras se libera el micrófono
        liberarClave();
        pitido();
        ui.postDelayed(this::escucharComando, 350);
    }

    private void pitido() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            ui.postDelayed(tg::release, 400);
        } catch (RuntimeException ignorada) {
        }
    }

    // ------------------------------------------------------------------ la orden (reconocedor de Google)

    private void escucharComando() {
        if (!corriendo) return;
        ui.removeCallbacks(reanudar);
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            decir("Tu celular no trae reconocimiento de voz.");
            programarReanudar(2500);
            return;
        }
        fase = Fase.COMANDO;
        liberarClave();
        liberarReconocedor();
        reconocedor = SpeechRecognizer.createSpeechRecognizer(this);
        reconocedor.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle b) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float v) {
            }

            @Override
            public void onBufferReceived(byte[] b) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int codigo) {
                liberarReconocedor();
                fase = Fase.OCUPADO;
                programarReanudar(300);
            }

            @Override
            public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                liberarReconocedor();
                fase = Fase.OCUPADO;
                if (l == null || l.isEmpty()) {
                    programarReanudar(300);
                    return;
                }
                try {
                    cerebro.ejecutar(l.get(0), EscuchaService.this);
                } catch (RuntimeException e) {
                    decir("Algo falló con esa orden.");
                }
                programarReanudar(4000);
            }

            @Override
            public void onPartialResults(Bundle b) {
            }

            @Override
            public void onEvent(int tipo, Bundle b) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        reconocedor.startListening(i);
        actualizarNotificacion("Te escucho…");
    }

    private void liberarReconocedor() {
        if (reconocedor == null) return;
        try {
            reconocedor.cancel();
            reconocedor.destroy();
        } catch (RuntimeException ignorada) {
        }
        reconocedor = null;
    }

    private void programarReanudar(long ms) {
        ui.removeCallbacks(reanudar);
        ui.postDelayed(reanudar, ms);
    }

    private void terminoDeHablar(String id) {
        hablando = false;
        if ("q".equals(id)) {
            ui.post(this::escucharComando);
        } else {
            ui.post(() -> programarReanudar(600));
        }
    }

    private void hablar(String texto, String id) {
        if (ttsListo) tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    // ------------------------------------------------------------------ notificación

    private void iniciarPrimerPlano(String texto) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "LAY escucha", NotificationManager.IMPORTANCE_LOW));
        Notification n = construirNotificacion(texto);
        if (Build.VERSION.SDK_INT >= 30) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(7, n);
    }

    private Notification construirNotificacion(String texto) {
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent parar = PendingIntent.getService(this, 1, new Intent(this, EscuchaService.class).setAction(ACCION_DETENER), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL)
                .setContentTitle("LAY está escuchando")
                .setContentText(texto)
                .setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), "Detener", parar).build())
                .build();
    }

    private void actualizarNotificacion(String texto) {
        getSystemService(NotificationManager.class).notify(7, construirNotificacion(texto));
    }

    // ------------------------------------------------------------------ Cerebro.Anfitrion (sin pantalla)

    @Override
    public Context ctx() {
        return this;
    }

    @Override
    public void decir(String texto) {
        ui.post(() -> {
            actualizarNotificacion(texto.length() > 90 ? texto.substring(0, 90) : texto);
            hablar(texto, "d");
        });
    }

    @Override
    public void decirYEscuchar(String texto) {
        ui.post(() -> {
            fase = Fase.PREGUNTA;
            ui.removeCallbacks(reanudar);
            actualizarNotificacion(texto.length() > 90 ? texto.substring(0, 90) : texto);
            if (ttsListo) hablar(texto, "q");
            else ui.postDelayed(this::escucharComando, 1200);
        });
    }

    /** Sin pantalla encendida, Android limita abrir pantallas desde segundo plano; el control total (Accesibilidad) suele permitirlo. */
    @Override
    public boolean iniciar(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (LayAccesibilidad.activo() && LayAccesibilidad.iniciar(i)) return true;
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void enSegundoPlano(Runnable r) {
        ui.postDelayed(r, 250); // ya estamos en segundo plano: no hay que esconder LAY
    }

    @Override
    public void permiso(String permiso, Runnable concedido, Runnable negado) {
        if (checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED) {
            concedido.run();
        } else {
            decir("Me falta un permiso. Abre la app LAY una vez, pídeme eso ahí y acepta el permiso.");
        }
    }

    @Override
    public void detenerVoz() {
        if (ttsListo) tts.stop();
    }

    @Override
    public void enUi(Runnable r) {
        ui.post(r);
    }

    @Override
    public void estado(String texto) {
    }
}
