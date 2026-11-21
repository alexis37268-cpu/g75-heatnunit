# Diagnóstico Auto

App para el **celu que proyecta Android Auto** (50 Ultra) al G75. Mide cada medio segundo la conexión
Wi-Fi Direct con el G75 y anota qué pasaba en cada corte, para saber por qué se corta.

- **Ping al G75** (192.168.49.1, la puerta de enlace de la red Wi-Fi Direct): si se pierde o tarda más de 300 ms, es un corte.
- En cada muestra: señal (dBm), velocidad del enlace, canal, estado térmico, temperatura de batería, ahorro de batería.
- Eventos: escaneos de WiFi del celu, cambios de red/canal, WiFi apagado/prendido, pantalla, ahorro de batería.
- **Informe** con los cortes, con qué coincidió cada uno y una conclusión automática. Se comparte como texto.

## Uso
1. Bajá `diagnostico-auto.apk` de **Releases → ultima** en el 50 Ultra e instalala.
2. Dale los permisos que marca la pantalla.
3. Conectá Android Auto al G75, tocá **EMPEZAR a medir** y manejá normal.
4. Al terminar: **Compartir informe**.

`firma.txt` es la firma fija (la misma que G75 Macro): no la borres.
