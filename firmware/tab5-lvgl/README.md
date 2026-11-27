# Pluggis på M5Stack Tab5 – LVGL-versionen

Samma firmware som [`../tab5`](../tab5) – samma server, protokoll, övningar och `config.h` – men gränssnittet är
omgjort med LVGL. Båda finns kvar sida vid sida, så att det går att flasha den gamla igen om något strular.
Kopiera `include/config.example.h` till `include/config.h` och fyll i innan du bygger.

Firmware för skrivbordsskärmen (PlatformIO/Arduino). Pratar med servern enligt [docs/protokoll.md](../../docs/protokoll.md).

Gränssnittet ritas med **LVGL 9** i `src/ui.cpp` (knappar som sjunker in, listor som rullas med fingret, animationer)
i loggans färger (mörkblått och gult), med loggan på startskärmen och figuren i chatten (`include/brand.h`),
med typsnittet Atkinson Hyperlegible – byggt för att vara lättläst – med å, ä, ö och ikoner (`src/fonts/`).
`src/main.cpp` sköter WiFi, ljud och servern och fyller i vad som ska synas; `ui.cpp` vet ingenting om hårdvaran.

**Flikar högst upp (Tab5 startar på Öva):**
- **Öva** – läxor och prov till vänster (datumordning, *Snart* / *Längre fram*, rullas med fingret, ämnesfärg,
  *börja nu* för det som ligger längre fram men har moment i planen). Till höger: vald läxa, *Öva på:* delområden och
  de sex övningarna – Snabbquiz, Lucktext, Förklara, Testprov, Matcha par, Rätt ordning. Längst ner *Senast* med
  *Kör igen*. Allt görs nytt varje gång (inga sparade kortlekar). Förklara och skrivfrågor besvaras med rösten.
- **Chat** – coachen: svaret på skärmen och i högtalaren, timer för passet, *Börja/Hjälp*, *Håll in och prata*, *Avsluta*.
- **Läxor** – läxor och prov; på en läxa läses allt material upp sida för sida (*Spela/Paus, Förra, Nästa sida*), och var man är sparas så att det går att fortsätta senare (även i appen).
- **Plan** – en dag i taget (< >); tryck på ett moment för att bocka av.
- Volym − / + (sparas) och batterinivå uppe till höger.

Ljudet från coachen kommer som 16 kHz mono i bitar om 8 kB (`audioChunk` i hello). Inte än: kameran (fota med telefonen) och Dual Button.

## Inställningar på skärmen

Tryck på ⚙ uppe till höger. Där finns **WiFi** (byt nätverk), **Server** (IP-adress, provas innan den sparas),
**Elev** (elevens kod, provas mot servern – koden bestämmer vilken elev skärmen visar), **Stäng av efter**
(Av, 2, 5, 10, 15 eller 30 minuter), **Ljusstyrka**, **Detaljer** (WiFi, server, chatten, senaste fel, minne) och
**Starta om**. Allt sparas i Tab5 och går före `config.h`, så `config.h` behövs bara första gången.

## Byta WiFi på skärmen

Tryck på WiFi-ikonen uppe till höger → **Byt WiFi** (eller **Välj WiFi** på startskärmen om den inte kommer upp
på nätet). Tab5 söker efter nätverk; tryck på ett och skriv lösenordet på skärmens tangentbord (**Visa** visar det
du skrivit). **Dolt nätverk** för ett nät som inte syns i listan. Nätverket sparas i Tab5 först när anslutningen
lyckats och går sedan före `PLUGGIS_WIFI_SSID`/`PLUGGIS_WIFI_PASS` i `config.h`. Blir det fel lösenord går Tab5
tillbaka till det nät den hade. Bara 2,4 GHz.

## Se skärmarna utan Tab5 (simulator)

`./sim/run.sh` bygger gränssnittet för datorn och ritar alla skärmar med testdata till `sim/out/*.png`
(kräver gcc, g++, git och python3 med Pillow; första gången hämtas och byggs LVGL, det tar några minuter).
Testdatan finns i `sim/sim_main.cpp` – ändra där för att prova långa texter, många läxor och liknande.

## Typsnitten

`src/fonts/*.c` är gjorda med [lv_font_conv](https://github.com/lvgl/lv_font_conv) från Atkinson Hyperlegible
(regular 24/30, bold 30/38/60, Latin-1) plus LVGL:s FontAwesome-ikoner och några extra (mikrofon, chatt, bok,
kalender, glödlampa, frågetecken, länk, ordning, pokal, klocka). Kommandot står överst i varje fil.

## Flasha

1. Installera [PlatformIO](https://platformio.org/install/cli) (eller VS Code + PlatformIO).
2. `cp include/config.example.h include/config.h` och fyll i WiFi, serverns IP-adress och **elevens kod**
   (`PLUGGIS_CODE`, samma 5 tecken som i elevchatten – admin → Inställningar → Elever). Koden räcker: servern vet
   vilken elev den hör till, och skärmen når bara den elevens läxor, plan och poäng. Byts koden i admin (*Ny kod*)
   måste den bytas här också. En äldre `config.h` med `PLUGGIS_DEVICE_ID` + `PLUGGIS_TOKEN` fungerar fortfarande.
   `config.h` checkas aldrig in.
3. Koppla Tab5 med USB-C. Gå till nedladdningsläge: håll reset-knappen ~2 s tills den gröna lampan blinkar snabbt.
4. `pio run -t upload`, och `pio device monitor` för loggen.
5. I admin: Inställningar → Tab5 (hårdvara) för att visa enhetens status.

Första bygget laddar ner verktygskedjan för ESP32-P4 och tar några minuter.
