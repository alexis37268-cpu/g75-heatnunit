package ar.esqr.diagauto;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Pantalla única: empezar/parar, estado en vivo y compartir el informe. */
public class MainActivity extends Activity {
    private LinearLayout raiz;
    private TextView vivo;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            if (vivo != null) vivo.setText(Registro.estado());
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#1F2A36"));
        raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        raiz.setPadding(p, p, p, p);
        scroll.addView(raiz);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        dibujar();
        h.post(refresco);
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(refresco);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        dibujar();
    }

    private void dibujar() {
        raiz.removeAllViews();
        titulo("Diagnóstico Auto");
        texto("Usala en el celu que proyecta (el 50 Ultra). Conectá Android Auto al G75, "
                + "tocá Empezar y manejá normal. Cada medio segundo mide la conexión con el G75 "
                + "y anota qué pasaba en cada corte.");

        boolean midiendo = Registro.midiendo;
        Button b = boton(midiendo ? "PARAR la medición" : "EMPEZAR a medir");
        b.setBackgroundColor(Color.parseColor(midiendo ? "#C62828" : "#2E7D32"));
        b.setOnClickListener(v -> {
            if (Registro.midiendo) {
                stopService(new Intent(this, MedidorService.class));
                h.postDelayed(this::dibujar, 300);
            } else {
                MedidorService.arrancar(this);
                h.postDelayed(this::dibujar, 300);
            }
        });

        subtitulo("En vivo");
        vivo = texto(Registro.estado());
        vivo.setTypeface(Typeface.MONOSPACE);

        if (midiendo) {
            boton("Marcar: ¡cortó ahora!").setOnClickListener(v -> Registro.marcar());
        }

        subtitulo("Informe");
        boton("Compartir informe (WhatsApp, mail…)").setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Informe Diagnóstico Auto");
            i.putExtra(Intent.EXTRA_TEXT, Registro.informe());
            startActivity(Intent.createChooser(i, "Compartir informe"));
        });
        boton("Ver informe acá").setOnClickListener(v -> {
            raiz.removeAllViews();
            boton("← Volver").setOnClickListener(x -> dibujar());
            TextView t = texto(Registro.informe());
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(12);
            t.setTextIsSelectable(true);
        });

        subtitulo("Permisos (una sola vez)");
        permiso("Ubicación (Android la pide para avisar los escaneos de WiFi)",
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                () -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 1));
        if (Build.VERSION.SDK_INT >= 33) {
            permiso("Notificaciones (botón ¡Cortó! y Parar)",
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2));
        }
        PowerManager pm = getSystemService(PowerManager.class);
        permiso("Batería sin restricciones (que no la corte con la pantalla apagada)",
                pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()),
                () -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()))));

        texto("\nUn \"corte\" es un ping al G75 perdido o de más de " + Registro.UMBRAL_MS
                + " ms. Al empezar una medición nueva se borra la anterior.");
    }

    private void permiso(String nombre, boolean ok, Runnable pedir) {
        if (ok) {
            texto("✓ " + nombre);
        } else {
            boton("Falta: " + nombre).setOnClickListener(v -> pedir.run());
        }
    }

    private void titulo(String s) {
        TextView t = texto(s);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#FFB74D"));
    }

    private void subtitulo(String s) {
        TextView t = texto(s);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
    }

    private TextView texto(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(Color.parseColor("#ECEFF1"));
        t.setPadding(0, dp(4), 0, dp(4));
        raiz.addView(t);
        return t;
    }

    private Button boton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#37474F"));
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        raiz.addView(b);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
