package ar.esqr.diagauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio que mide cada medio segundo: ping al G75, señal, canal, temperatura.
 * Escucha también los escaneos y cambios de WiFi del celu.
 */
public class MedidorService extends Service {
    static final String CANAL = "medidor";
    static final int ID = 1;
    static final String MARCAR = "ar.esqr.diagauto.MARCAR";
    static final String PARAR = "ar.esqr.diagauto.PARAR";
    /** El G75 arma la red Wi-Fi Direct y queda como puerta de enlace en esta IP. */
    static final String G75_POR_DEFECTO = "192.168.49.1";
    private static final Pattern TIEMPO = Pattern.compile("time[=<]([0-9.]+)");

    private Thread hilo;
    private volatile boolean corriendo;
    private PowerManager.WakeLock wakeLock;
    private long ultimaNotif;

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            switch (a) {
                case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION:
                    Registro.evento("Escaneo de WiFi del celu", true);
                    break;
                case WifiManager.NETWORK_STATE_CHANGED_ACTION: {
                    NetworkInfo ni = i.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (ni != null) Registro.evento("WiFi: " + ni.getDetailedState(), false);
                    break;
                }
                case WifiManager.WIFI_STATE_CHANGED_ACTION: {
                    int st = i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                    if (st == WifiManager.WIFI_STATE_DISABLED) Registro.evento("WiFi: APAGADO", false);
                    else if (st == WifiManager.WIFI_STATE_ENABLED) Registro.evento("WiFi: encendido", false);
                    break;
                }
                case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED: {
                    PowerManager pm = getSystemService(PowerManager.class);
                    Registro.evento("Ahorro de batería: " + (pm != null && pm.isPowerSaveMode() ? "ON" : "OFF"), false);
                    break;
                }
                case Intent.ACTION_SCREEN_OFF:
                    Registro.evento("Pantalla del celu apagada", false);
                    break;
                case Intent.ACTION_SCREEN_ON:
                    Registro.evento("Pantalla del celu encendida", false);
                    break;
            }
        }
    };

    static void arrancar(Context c) {
        c.startForegroundService(new Intent(c, MedidorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal();
        Notification n = notificacion("Arrancando…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID, n);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receptor, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receptor, f);
        }

        // Que el celu no se duerma con la pantalla apagada mientras mide
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diagauto:medir");
            wakeLock.acquire(4 * 60 * 60 * 1000L);
        }

        Registro.empezar();
        Registro.evento("Empezó la medición", false);
        corriendo = true;
        hilo = new Thread(this::bucle, "medidor");
        hilo.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (MARCAR.equals(a)) {
            Registro.marcar();
            actualizarNotif(true);
        } else if (PARAR.equals(a)) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        corriendo = false;
        if (hilo != null) hilo.interrupt();
        Registro.evento("Terminó la medición", false);
        Registro.parar();
        try {
            unregisterReceiver(receptor);
        } catch (Exception ignored) {
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void bucle() {
        while (corriendo) {
            long t0 = SystemClock.elapsedRealtime();
            try {
                Registro.agregar(medir());
                actualizarNotif(false);
            } catch (Exception e) {
                Registro.evento("Error midiendo: " + e.getMessage(), false);
            }
            long resto = 500 - (SystemClock.elapsedRealtime() - t0);
            if (resto > 0) {
                try {
                    Thread.sleep(resto);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Registro.Muestra medir() {
        Registro.Muestra m = new Registro.Muestra();
        m.t = System.currentTimeMillis();

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        String destino = G75_POR_DEFECTO;
        m.rssi = -127;
        if (wifi != null) {
            WifiInfo wi = wifi.getConnectionInfo();
            if (wi != null && wi.getNetworkId() != -1) {
                m.rssi = wi.getRssi();
                m.txMbps = wi.getTxLinkSpeedMbps();
                m.rxMbps = wi.getRxLinkSpeedMbps();
                m.freq = wi.getFrequency();
            }
            DhcpInfo d = wifi.getDhcpInfo();
            if (d != null && d.gateway != 0) destino = ip(d.gateway);
        }
        m.pingMs = ping(destino);

        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            m.termico = pm.getCurrentThermalStatus();
            m.ahorro = pm.isPowerSaveMode();
        }
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) m.tempBat = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        return m;
    }

    /** Un ping de 1 segundo máximo. Devuelve los ms, o -1 si se perdió. */
    private static int ping(String ip) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String linea;
                while ((linea = r.readLine()) != null) out.append(linea).append('\n');
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) return -1;
            Matcher mt = TIEMPO.matcher(out);
            if (p.exitValue() == 0 && mt.find()) return Math.round(Float.parseFloat(mt.group(1)));
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** El gateway viene como entero en orden de bytes al revés (little endian). */
    private static String ip(int v) {
        return String.format(Locale.US, "%d.%d.%d.%d", v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff);
    }

    private void actualizarNotif(boolean ya) {
        long ahora = SystemClock.elapsedRealtime();
        if (!ya && ahora - ultimaNotif < 2000) return;
        ultimaNotif = ahora;
        Registro.Muestra m = Registro.ultima();
        String txt = m == null ? "Midiendo…" : String.format(Locale.US, "Ping %s · señal %d dBm · cortes %d",
                m.pingMs < 0 ? "PERDIDO" : m.pingMs + " ms", m.rssi, Registro.cantidadCortes());
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID, notificacion(txt));
    }

    private void crearCanal() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Medición", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notificacion(String texto) {
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), fl);
        PendingIntent marcar = PendingIntent.getService(this, 1,
                new Intent(this, MedidorService.class).setAction(MARCAR), fl);
        PendingIntent parar = PendingIntent.getService(this, 2,
                new Intent(this, MedidorService.class).setAction(PARAR), fl);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Diagnóstico Auto: midiendo")
                .setContentText(texto)
                .setContentIntent(abrir)
                .addAction(new Notification.Action.Builder(null, "¡Cortó!", marcar).build())
                .addAction(new Notification.Action.Builder(null, "Parar", parar).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
