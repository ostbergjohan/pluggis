# Pluggis på M5Stack Tab5

Firmware för skrivbordsskärmen (PlatformIO/Arduino). Pratar med servern enligt [docs/protokoll.md](../../docs/protokoll.md).

**Flikar högst upp (Tab5 startar på Öva):**
- **Öva** – läxor och prov till vänster (datumordning, *Snart* / *Längre fram*, sex rader med Upp/Ner, ämnesfärg,
  *börja nu* för det som ligger längre fram men har moment i planen). Till höger: vald läxa, *Öva på:* delområden och
  de sex övningarna – Snabbquiz, Lucktext, Förklara, Testprov, Matcha par, Rätt ordning. Längst ner *Senast* med
  *Kör igen*. Allt görs nytt varje gång (inga sparade kortlekar). Förklara och skrivfrågor besvaras med rösten.
- **Chat** – coachen: svaret på skärmen och i högtalaren, timer för passet, *Börja/Hjälp*, *Håll in och prata*, *Avsluta*.
- **Läxor** – läxor och prov; på en läxa läses allt material upp sida för sida (*Spela/Paus, Förra, Nästa sida*), och var man är sparas så att det går att fortsätta senare (även i appen).
- **Plan** – en dag i taget (< >); tryck på ett moment för att bocka av.
- Volym − / + (sparas) och batterinivå uppe till höger.

Ljudet från coachen kommer som 16 kHz mono i bitar om 8 kB (`audioChunk` i hello). Inte än: kameran (fota med telefonen) och Dual Button.

## Flasha

1. Installera [PlatformIO](https://platformio.org/install/cli) (eller VS Code + PlatformIO).
2. `cp include/config.example.h include/config.h` och fyll i WiFi, serverns adress, elevens enhets-id
   (admin → Inställningar → Elever) och elevens kod i `PLUGGIS_TOKEN` (föräldertoken finns inte längre). `config.h` checkas aldrig in.
3. Koppla Tab5 med USB-C. Gå till nedladdningsläge: håll reset-knappen ~2 s tills den gröna lampan blinkar snabbt.
4. `pio run -t upload`, och `pio device monitor` för loggen.
5. I admin: Inställningar → Tab5 (hårdvara) för att visa enhetens status.

Första bygget laddar ner verktygskedjan för ESP32-P4 och tar några minuter.
