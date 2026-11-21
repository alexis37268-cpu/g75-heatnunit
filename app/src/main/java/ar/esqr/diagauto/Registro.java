package ar.esqr.diagauto;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guarda todo lo que mide la app (en memoria) y arma el informe.
 * Un "corte" es un tramo seguido de pings perdidos o lentos (más de UMBRAL_MS).
 */
final class Registro {
    /** Ping más lento que esto ya se nota como corte en el audio/video. */
    static final int UMBRAL_MS = 300;
    /** Eventos que cuentan como "coincidentes" si pasan hasta 5 s antes del corte o 2 s después. */
    static final long VENTANA_ANTES_MS = 5000, VENTANA_DESPUES_MS = 2000;
    private static final int MAX_MUESTRAS = 20000; // ~2,7 h a 2 por segundo
    private static final int MAX_EVENTOS = 3000;

    static final class Muestra {
        long t;
        int pingMs;      // -1 = se perdió
        int rssi;        // dBm (-127 = sin conexión)
        int txMbps, rxMbps, freq, termico;
        boolean ahorro;
        float tempBat;
    }

    static final class Evento {
        final long t;
        final String texto;
        final boolean escaneo;

        Evento(long t, String texto, boolean escaneo) {
            this.t = t;
            this.texto = texto;
            this.escaneo = escaneo;
        }
    }

    static final class Corte {
        long inicio, fin;
        int peorRssi = 0, termico;
        boolean ahorro;
        final List<String> coincide = new ArrayList<>();
        boolean conEscaneo, conCambioRed;
    }

    private static final List<Muestra> muestras = new ArrayList<>();
    private static final List<Evento> eventos = new ArrayList<>();
    private static final List<Corte> cortes = new ArrayList<>();
    private static final List<Long> marcas = new ArrayList<>();
    private static Corte abierto;
    private static long inicio;
    static volatile boolean midiendo;

    private Registro() {}

    static synchronized void empezar() {
        muestras.clear();
        eventos.clear();
        cortes.clear();
        marcas.clear();
        abierto = null;
        inicio = System.currentTimeMillis();
        midiendo = true;
    }

    static synchronized void parar() {
        cerrarCorte(System.currentTimeMillis());
        midiendo = false;
    }

    static synchronized void agregar(Muestra m) {
        if (muestras.size() >= MAX_MUESTRAS) muestras.remove(0);
        Muestra anterior = muestras.isEmpty() ? null : muestras.get(muestras.size() - 1);
        muestras.add(m);

        if (anterior != null && anterior.freq > 0 && m.freq > 0 && anterior.freq != m.freq) {
            evento("Cambió de canal: " + canal(anterior.freq) + " → " + canal(m.freq), false);
        }

        boolean malo = m.pingMs < 0 || m.pingMs > UMBRAL_MS;
        if (malo) {
            if (abierto == null) {
                abierto = new Corte();
                abierto.inicio = m.t;
                abierto.peorRssi = m.rssi;
            }
            abierto.peorRssi = Math.min(abierto.peorRssi, m.rssi);
            abierto.termico = Math.max(abierto.termico, m.termico);
            abierto.ahorro |= m.ahorro;
        } else {
            cerrarCorte(m.t);
        }
    }

    private static void cerrarCorte(long fin) {
        if (abierto == null) return;
        abierto.fin = fin;
        Set<String> vistos = new LinkedHashSet<>();
        for (Evento e : eventos) {
            if (e.t >= abierto.inicio - VENTANA_ANTES_MS && e.t <= fin + VENTANA_DESPUES_MS) {
                vistos.add(e.texto);
                if (e.escaneo) abierto.conEscaneo = true;
                if (e.texto.startsWith("WiFi:") || e.texto.startsWith("Cambió de canal")) abierto.conCambioRed = true;
            }
        }
        abierto.coincide.addAll(vistos);
        cortes.add(abierto);
        abierto = null;
    }

    static synchronized void evento(String texto, boolean escaneo) {
        if (eventos.size() >= MAX_EVENTOS) eventos.remove(0);
        eventos.add(new Evento(System.currentTimeMillis(), texto, escaneo));
    }

    static synchronized void marcar() {
        marcas.add(System.currentTimeMillis());
        evento("Marcado a mano: ¡cortó!", false);
    }

    static synchronized int cantidadCortes() {
        return cortes.size() + (abierto != null ? 1 : 0);
    }

    static synchronized Muestra ultima() {
        return muestras.isEmpty() ? null : muestras.get(muestras.size() - 1);
    }

    /** Resumen corto para la pantalla. */
    static synchronized String estado() {
        Muestra m = ultima();
        if (m == null) return midiendo ? "Midiendo… (esperando el primer dato)" : "Sin datos. Tocá Empezar.";
        StringBuilder s = new StringBuilder();
        s.append("Ping al G75: ").append(m.pingMs < 0 ? "PERDIDO" : m.pingMs + " ms").append('\n');
        s.append("Señal: ").append(m.rssi <= -127 ? "sin conexión" : m.rssi + " dBm")
                .append("   Canal: ").append(canal(m.freq)).append('\n');
        s.append("Enlace: ").append(m.txMbps).append(" / ").append(m.rxMbps).append(" Mbps\n");
        s.append("Temp. batería: ").append(String.format(Locale.US, "%.1f", m.tempBat)).append(" °C   ")
                .append("Térmico: ").append(termico(m.termico)).append('\n');
        s.append("Cortes detectados: ").append(cantidadCortes())
                .append("   Escaneos WiFi: ").append(contarEscaneos());
        return s.toString();
    }

    private static int contarEscaneos() {
        int n = 0;
        for (Evento e : eventos) if (e.escaneo) n++;
        return n;
    }

    /** Informe completo para compartir. */
    static synchronized String informe() {
        SimpleDateFormat hora = new SimpleDateFormat("HH:mm:ss", Locale.US);
        StringBuilder s = new StringBuilder();
        s.append("DIAGNÓSTICO AUTO — informe\n");
        if (muestras.isEmpty()) return s.append("Sin datos.\n").toString();

        long fin = muestras.get(muestras.size() - 1).t;
        int perdidos = 0, lentos = 0, nPing = 0, rssiMin = 0, nRssi = 0;
        long sumaPing = 0, sumaRssi = 0, sumaTx = 0;
        int pingMax = 0, termicoMax = 0;
        float tempMax = 0;
        Set<String> canales = new LinkedHashSet<>();
        for (Muestra m : muestras) {
            if (m.pingMs < 0) perdidos++;
            else {
                nPing++;
                sumaPing += m.pingMs;
                pingMax = Math.max(pingMax, m.pingMs);
                if (m.pingMs > UMBRAL_MS) lentos++;
            }
            if (m.rssi > -127) {
                nRssi++;
                sumaRssi += m.rssi;
                sumaTx += m.txMbps;
                rssiMin = Math.min(rssiMin, m.rssi);
            }
            if (m.freq > 0) canales.add(canal(m.freq));
            termicoMax = Math.max(termicoMax, m.termico);
            tempMax = Math.max(tempMax, m.tempBat);
        }
        long durS = Math.max(1, (fin - inicio) / 1000);
        int escaneos = contarEscaneos();

        s.append("Inicio: ").append(hora.format(new Date(inicio)))
                .append("   Duración: ").append(durS / 60).append(" min ").append(durS % 60).append(" s\n");
        s.append("Muestras: ").append(muestras.size())
                .append("   Perdidos: ").append(perdidos)
                .append(" (").append(perdidos * 100 / muestras.size()).append("%)")
                .append("   Lentos >").append(UMBRAL_MS).append(" ms: ").append(lentos).append('\n');
        if (nPing > 0) {
            s.append("Ping promedio: ").append(sumaPing / nPing).append(" ms   máximo: ").append(pingMax).append(" ms\n");
        }
        if (nRssi > 0) {
            s.append("Señal promedio: ").append(sumaRssi / nRssi).append(" dBm   peor: ").append(rssiMin).append(" dBm\n");
            s.append("Enlace TX promedio: ").append(sumaTx / nRssi).append(" Mbps\n");
        }
        s.append("Canales usados: ").append(canales).append('\n');
        s.append("Escaneos de WiFi del celu: ").append(escaneos);
        if (escaneos > 0) s.append(" (uno cada ").append(durS / escaneos).append(" s)");
        s.append('\n');
        s.append("Temp. batería máx: ").append(String.format(Locale.US, "%.1f", tempMax))
                .append(" °C   Estado térmico máx: ").append(termico(termicoMax)).append("\n\n");

        List<Corte> todos = new ArrayList<>(cortes);
        long totalCorteMs = 0;
        int conEscaneo = 0, conCambio = 0, senalDebil = 0, conCalor = 0, conAhorro = 0;
        for (Corte c : todos) {
            totalCorteMs += c.fin - c.inicio;
            if (c.conEscaneo) conEscaneo++;
            if (c.conCambioRed) conCambio++;
            if (c.peorRssi < -75) senalDebil++;
            if (c.termico >= 2) conCalor++;
            if (c.ahorro) conAhorro++;
        }
        s.append("CORTES: ").append(todos.size())
                .append("   (en total ").append(totalCorteMs / 1000).append(" s sin conexión)\n");
        if (!todos.isEmpty()) {
            s.append("  con escaneo de WiFi cerca: ").append(conEscaneo).append('\n');
            s.append("  con cambio de red/canal cerca: ").append(conCambio).append('\n');
            s.append("  con señal débil (< -75 dBm): ").append(senalDebil).append('\n');
            s.append("  con el celu caliente: ").append(conCalor).append('\n');
            s.append("  con ahorro de batería: ").append(conAhorro).append('\n');
        }
        s.append('\n').append("CONCLUSIÓN: ").append(conclusion(todos.size(), conEscaneo, conCambio,
                senalDebil, conCalor, conAhorro)).append("\n\n");

        s.append("DETALLE (últimos 40 cortes)\n");
        int desde = Math.max(0, todos.size() - 40);
        for (int i = desde; i < todos.size(); i++) {
            Corte c = todos.get(i);
            s.append(hora.format(new Date(c.inicio)))
                    .append("  ").append(String.format(Locale.US, "%.1f", (c.fin - c.inicio) / 1000f)).append(" s")
                    .append("  señal ").append(c.peorRssi).append(" dBm");
            if (c.termico > 0) s.append("  térmico ").append(termico(c.termico));
            if (c.ahorro) s.append("  AHORRO");
            if (!c.coincide.isEmpty()) s.append("\n    coincide con: ").append(String.join("; ", c.coincide));
            s.append('\n');
        }

        if (!marcas.isEmpty()) {
            s.append("\nMARCADOS A MANO\n");
            for (Long t : marcas) s.append(hora.format(new Date(t))).append('\n');
        }

        s.append("\nÚLTIMOS EVENTOS\n");
        for (int i = Math.max(0, eventos.size() - 40); i < eventos.size(); i++) {
            Evento e = eventos.get(i);
            s.append(hora.format(new Date(e.t))).append("  ").append(e.texto).append('\n');
        }
        return s.toString();
    }

    private static String conclusion(int n, int escaneo, int cambio, int debil, int calor, int ahorro) {
        if (n == 0) return "No hubo cortes en el enlace WiFi mientras se midió.";
        List<String> c = new ArrayList<>();
        if (escaneo * 2 >= n) c.add("la mayoría coincide con escaneos de WiFi del celu (algo en el celu está buscando redes)");
        if (cambio * 2 >= n) c.add("la mayoría coincide con cambios de red o canal");
        if (debil * 2 >= n) c.add("la mayoría pasa con señal débil (ubicación del celu, o canal/banda)");
        if (calor * 2 >= n) c.add("la mayoría pasa con el celu caliente");
        if (ahorro * 2 >= n) c.add("la mayoría pasa con ahorro de batería activo");
        if (c.isEmpty()) {
            return "los cortes no coinciden con nada que informe el celu: probable interferencia de radio "
                    + "o que el problema esté del lado del G75.";
        }
        return String.join("; ", c) + ".";
    }

    static String canal(int freq) {
        if (freq <= 0) return "-";
        int ch;
        if (freq >= 2412 && freq <= 2484) ch = freq == 2484 ? 14 : (freq - 2407) / 5;
        else if (freq >= 5000 && freq < 5950) ch = (freq - 5000) / 5;
        else if (freq >= 5950) ch = (freq - 5950) / 5;
        else return freq + " MHz";
        return ch + " (" + freq + " MHz)";
    }

    static String termico(int t) {
        switch (t) {
            case 0: return "normal";
            case 1: return "tibio";
            case 2: return "moderado";
            case 3: return "caliente";
            case 4: return "crítico";
            default: return "extremo";
        }
    }
}
