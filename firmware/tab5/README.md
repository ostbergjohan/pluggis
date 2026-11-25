# Pluggis på M5Stack Tab5

Firmware för skrivbordsskärmen (PlatformIO/Arduino). Pratar med servern enligt [docs/protokoll.md](../../docs/protokoll.md).

**Version 1:** coachens svar, "tänker", timern för passet, flashkort och poäng på skärmen; knapparna
*Börja/Hjälp*, *Avsluta* och *Håll in och prata* (rösten skickas som wav 16 kHz, svaret spelas upp mening för mening).
Inte än: kameran (fota med telefonen) och Dual Button.

## Flasha

1. Installera [PlatformIO](https://platformio.org/install/cli) (eller VS Code + PlatformIO).
2. `cp include/config.example.h include/config.h` och fyll i WiFi, serverns adress, elevens enhets-id
   (admin → Inställningar → Elever) och `PLUGGIS_DEVICE_TOKEN` från `.env`. `config.h` checkas aldrig in.
3. Koppla Tab5 med USB-C. Gå till nedladdningsläge: håll reset-knappen ~2 s tills den gröna lampan blinkar snabbt.
4. `pio run -t upload`, och `pio device monitor` för loggen.
5. I admin: Inställningar → Tab5 (hårdvara) för att visa enhetens status.

Första bygget laddar ner verktygskedjan för ESP32-P4 och tar några minuter.
