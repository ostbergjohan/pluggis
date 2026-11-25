# Pluggis på M5Stack Tab5

Firmware för skrivbordsskärmen (PlatformIO/Arduino). Pratar med servern enligt [docs/protokoll.md](../../docs/protokoll.md).

**Flikar högst upp:**
- **Pluggis** – coachen: svaret på skärmen och i högtalaren, timer för passet, *Börja/Hjälp*, *Håll in och prata*, *Avsluta*.
- **Öva** – *Ny övning* som i appen: välj läxa/prov med < > och tryck Snabbquiz, Lucktext, Förklara, Testprov, Matcha par
  eller Rätt ordning. Under finns kortlekarna: flerval att trycka på (kort utan egna alternativ får svar från andra kort i
  leken), vanliga kort med *Visa svar* eller *Håll in och svara* (inte i språk – där trycker man). Förklara och skrivfrågor
  i testprovet besvaras med rösten. Ger poäng och övningstid.
- **Läxor** – läxor och prov; på en läxa läses allt material upp sida för sida (*Spela/Paus, Förra, Nästa sida*), och var man är sparas så att det går att fortsätta senare (även i appen).
- **Plan** – en dag i taget (< >); tryck på ett moment för att bocka av.
- Volym − / + (sparas) och batterinivå uppe till höger.

Ljudet från coachen kommer som 16 kHz mono i bitar om 8 kB (`audioChunk` i hello). Inte än: kameran (fota med telefonen) och Dual Button.

## Flasha

1. Installera [PlatformIO](https://platformio.org/install/cli) (eller VS Code + PlatformIO).
2. `cp include/config.example.h include/config.h` och fyll i WiFi, serverns adress, elevens enhets-id
   (admin → Inställningar → Elever) och `PLUGGIS_DEVICE_TOKEN` från `.env`. `config.h` checkas aldrig in.
3. Koppla Tab5 med USB-C. Gå till nedladdningsläge: håll reset-knappen ~2 s tills den gröna lampan blinkar snabbt.
4. `pio run -t upload`, och `pio device monitor` för loggen.
5. I admin: Inställningar → Tab5 (hårdvara) för att visa enhetens status.

Första bygget laddar ner verktygskedjan för ESP32-P4 och tar några minuter.
