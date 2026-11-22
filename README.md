# Pluggis

[![build](https://github.com/ostbergjohan/pluggis/actions/workflows/build.yml/badge.svg)](https://github.com/ostbergjohan/pluggis/actions/workflows/build.yml) [![docker](https://github.com/ostbergjohan/pluggis/actions/workflows/docker.yml/badge.svg)](https://github.com/ostbergjohan/pluggis/actions/workflows/docker.yml)

Backend för en fysisk studiekompis på skrivbordet: en **M5Stack Tab5** med kamera,
mikrofoner och högtalare, plus en **Dual Button** (U025) och eventuellt en LED-list som timer.
Hjärnan är Claude, som coachar med frågor i stället för färdiga svar och är byggd
för att hjälpa elever med ADHD att komma igång, hålla tiden och ta ett steg i taget.

## Vad som finns

| Del | Status |
|---|---|
| Coach med Claude + verktyg (starta/avsluta pass, spara/lista/klarmarkera läxor) | ✅ |
| Läxpass med timer som ägs av backenden, löpande `timer`-händelser till enheten | ✅ |
| Foto → Claude läser ut uppgifter som JSON → sparas som läxor | ✅ |
| Flashkort: generera från ämne, text eller foto, Leitner-repetition, röstförhör, Anki-export | ✅ |
| Poäng för pluggande med streak och valfri veckopeng (tak per vecka) | ✅ |
| Pluggplan dag för dag utifrån vad som ingår (sidor, kapitel, begrepp), omplanering | ✅ |
| Påminnelser på Tab5 (eftermiddag + kvällsknuff, "imorgon är det prov") | ✅ |
| Läxor in via InfoMentors kalenderprenumeration (webcals), .ics-fil, skärmdump, Word, PDF eller inklistrad text | ✅ |
| Material på en läxa/prov (skärmdump, Word, PDF, text) → innehåll, ny plan, valfria flashkort | ✅ |
| WebSocket för Tab5 (text, knappar, timer, foto, kort, poäng) | ✅ |
| Webbgränssnitt: föräldravy (läxor, plan, flashkort, poäng, statistik) + Tab5-simulator | ✅ |
| REST för test med curl / iOS-genväg | ✅ |
| Prometheus-metrik för Claude-latens och tokens | ✅ |
| Ljud: KB-Whisper (tal till text) + Piper (text till tal), uppläsning mening för mening | ✅ |
| Modell per jobb (Haiku 5.5 för röst, Sonnet 5.5 för planer/kort/foto), prompt caching | ✅ |
| Firmware för Tab5 | ⏳ eget repo eller `firmware/` senare |
| Föräldravy | ⏳ |

## Kom igång

**Nycklar, tokens och konton som behövs** (måste/valfritt, var du får dem, vad de kostar): se [docs/konfiguration.md](docs/konfiguration.md).

Kräver Java 21 och Maven.

```bash
cp .env.example .env        # fyll i ANTHROPIC_API_KEY och PLUGGIS_DEVICE_TOKEN
set -a; . ./.env; set +a
mvn spring-boot:run
```

Öppna sedan **http://localhost:8080** och ange token under *Inställningar*.

Data hamnar i en H2-fil under `./data`. Kör med Postgres via `SPRING_PROFILES_ACTIVE=postgres`.

### Testa coachen utan hårdvara

```bash
T=$PLUGGIS_DEVICE_TOKEN
curl -s -H "X-Pluggis-Token: $T" -H 'Content-Type: application/json' \
  -d '{"text":"(Knapptryck: jag vill börja plugga nu.)"}' \
  localhost:8080/api/devices/tab5-1/coach

curl -s -H "X-Pluggis-Token: $T" -F image=@stencil.jpg \
  "localhost:8080/api/homework/photo?deviceId=tab5-1"

curl -s -H "X-Pluggis-Token: $T" localhost:8080/api/homework
curl -s -H "X-Pluggis-Token: $T" localhost:8080/api/devices/tab5-1/sessions/active
```

Flashkort och poäng:

```bash
curl -s -H "X-Pluggis-Token: $T" -H 'Content-Type: application/json' \
  -d '{"topic":"Engelska glosor: djur","subject":"Engelska","count":10}' \
  localhost:8080/api/flashcards/generate
curl -s -H "X-Pluggis-Token: $T" -F image=@glosor.jpg "localhost:8080/api/flashcards/photo?deviceId=tab5-1"
curl -s -H "X-Pluggis-Token: $T" localhost:8080/api/flashcards/decks
curl -s -H "X-Pluggis-Token: $T" -o kort.tsv localhost:8080/api/flashcards/decks/1/export.tsv   # till Anki

curl -s -H "X-Pluggis-Token: $T" localhost:8080/api/devices/tab5-1/rewards            # veckans poäng och kronor
curl -s -H "X-Pluggis-Token: $T" -H 'Content-Type: application/json' \
  -d '{"points":50,"reason":"Bra jobbat på provet!"}' localhost:8080/api/devices/tab5-1/rewards/bonus
curl -s -H "X-Pluggis-Token: $T" -X POST localhost:8080/api/devices/tab5-1/rewards/payouts  # markera veckan utbetald
```

WebSocket (t.ex. med `websocat`):

```bash
websocat "ws://localhost:8080/ws/device?deviceId=tab5-1&token=$T"
{"type":"text","text":"jag har matteläxa till fredag"}
{"type":"button","button":"main","gesture":"long"}
```

## Arkitektur

```
Tab5 (firmware)                      Spring Boot "pluggis"
 ├─ mikrofon/högtalare ─┐            ├─ device/   WebSocket, knappar, händelser → enhet
 ├─ Dual Button (Grove) ├─ WS ──────▶├─ coach/    Claude-loop med verktyg + historik
 ├─ LED-list (sidoport) ┘            ├─ session/  pass + timer (@Scheduled)
 └─ kamera ── POST /api/homework/photo ─▶ homework/ läxor + fotoavläsning
iOS-genväg ── POST /api/homework/photo ─┘ claude/   tunn klient mot Messages API
                                     └─ /actuator/prometheus → Grafana
```

- **Prompterna** ligger i `src/main/resources/prompts/` – ändra coachens ton och regler där.
- **Knappar** översätts i `ButtonMapper` till syntetiska meddelanden som
  "(Knapptryck: jag vill börja plugga nu.)", så beteendet styrs av prompten.
- **Pass och läxor** skickas med i systemprompten varje anrop, så coachen vet läget
  även efter omstart. Själva samtalet hålls i minnet och nollställs efter 30 min tystnad.

## Köra på ditt Claude-abonnemang (Claude Code)

I stället för en API-nyckel kan Pluggis köra Claude via det officiella programmet `claude -p`
(Claude Code), som då drar från ditt Pro/Max-abonnemang. Claude Code finns redan i Docker-avbilden.

1. På din dator: installera Claude Code och kör `claude setup-token` (loggar in i webbläsaren och skriver ut en token, giltig ett år).
2. I `.env`: lämna `ANTHROPIC_API_KEY` tom och sätt
   ```
   PLUGGIS_CLAUDE_BACKEND=claude-code
   CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-...
   ```
3. `docker compose up --build`. Loggen visar "Claude körs via Claude Code".

**Hybrid (rekommenderas om du kör på abonnemanget):** `PLUGGIS_CLAUDE_BACKEND=hybrid` och fyll i
både `ANTHROPIC_API_KEY` och `CLAUDE_CODE_OAUTH_TOKEN`. Rösten (coachen) går då via API-nyckeln – snabb
och strömmad – medan foto, planer, flashkort och material går på abonnemanget. Går abonnemanget inte
(gräns nådd, utloggat) tas jobbet med API-nyckeln i stället. Vilka jobb som går via API styrs med
`PLUGGIS_CLAUDE_API_PURPOSES` (standard `coach`).

**Byta läge i admin:** under fliken **Kostnad** väljer du *Bara API-nyckel*, *Hybrid* eller *Bara abonnemanget*.
Valet gäller direkt och sparas (även efter omstart); `.env` är bara standardläget. Lägen som inte kan fungera
(t.ex. abonnemanget utan `CLAUDE_CODE_OAUTH_TOKEN`) nekas med en förklaring. Där syns också om nyckel, token och
`claude`-programmet finns, lyckade/misslyckade anrop via abonnemanget, senaste fel och hur ofta hybrid fallit tillbaka.

Att tänka på:
- I läget `claude-code` strömmas svaren inte, och varje anrop startar programmet – räkna med någon sekund längre väntan i röst.
- Pluggis delar abonnemangets gränser med din egen användning av Claude och Claude Code.
- Anthropics villkor: abonnemangets gränser gäller "vanlig, individuell användning", och tjänster för andra
  användare ska använda API-nyckel. Det här är tänkt för eget bruk hemma. Läs
  [villkoren](https://code.claude.com/docs/en/legal-and-compliance) och byt till API-nyckel om det ändras.
- Token är personlig – lägg den bara i `.env`, aldrig i git.

## Modeller och ljud

| Jobb | Standard | Varför |
|---|---|---|
| Röstcoachen | Haiku 5.5, `effort: low`, thinking av | Snabbast – varje sekund märks i röst |
| Foto/skärmdumpar | Sonnet 5.5, `effort: medium` | Handskrift och tabeller |
| Pluggplaner | Sonnet 5.5, `effort: medium` | Ingen väntar i realtid, kvalitet viktigare |
| Flashkort | Sonnet 5.5, `effort: medium` | Bra kort kräver omdöme |

Byt med `PLUGGIS_COACH_MODEL` m.fl. (se `.env.example`). Coachens fasta regler och verktygen
cachas (prompt caching); bara "läget just nu" och samtalet skickas fullt varje gång.
Svaret strömmas och delas upp i meningar som läses upp en i taget.

**Azures svenska röster (valfritt, gratis):** sätt `AZURE_SPEECH_KEY` och `AZURE_SPEECH_REGION` i `.env`
(en Speech-resurs med prisnivå *Free F0* – 0,5 miljoner tecken/månad, kan inte debiteras). Då finns
Sofie, Hillevi och Mattias under Inställningar → Röst, med Sofie som standard. Bara coachens svarstext
skickas till Azure. Går Azure inte (fel eller månadsgränsen nådd) tar Piper över automatiskt.

**Ljudtjänsten** (`speech/`) kör lokalt: KB-Whisper small (Kungliga bibliotekets svenska Whisper)
via faster-whisper, och Piper med svensk röst. Enklast:

```bash
docker compose up --build      # Pluggis + speech, öppna http://localhost:8080
```

I Tab5-simulatorn: kryssa i *Serverns röst* och håll inne *🎙 Håll och prata*.

## Webbgränssnitt

Serveras av samma tjänst (`src/main/resources/static`, inga beroenden eller byggsteg) och fungerar i mobilen.

| Flik | Vad |
|---|---|
| Översikt | Veckans poäng/kronor/streak, dagens plan att bocka av, kommande läxor och prov, pluggminuter och flashkort per dag, träffsäkerhet per kortlek |
| Läxor & prov | Lägg till, klistra in från InfoMentor, ladda upp fil (ny läxa eller material till en befintlig), redigera vad som ingår, planera, gör flashkort, markera klar, ta bort |
| Plan | Tre veckor framåt dag för dag, plus det som ligger efter. Dra ⠿ för att flytta moment mellan dagar eller ändra ordning (⋯ i mobilen); minuter mot max per dag visas. Flyttade läxor planeras inte om på natten förrän du trycker Planera igen |
| Flashkort | Generera med Claude, skriv egen lek (`fram ; bak ; ledtråd`), från foto, redigera kort, öva i webben, Anki-export |
| Poäng | Händelser, bonuspoäng, markera utbetalning |
| Tab5-simulator | Ansluter via samma WebSocket som Tab5: knappar, timer, kort, poäng, foto – med uppläsning och mikrofon (svenska) i webbläsaren |
| Logg | Backendens senaste 2 000 loggrader live, med nivåfilter, sökning och paus |
| Inställningar | Token, enhets-id, elevens namn och coachens ton, elevlänk till mobilen, röst och tempo, iOS-genvägen |

### Flera elever

Varje elev har egna läxor, plan, material, flashkort, kalender, namn, ton och poäng.

- **Inställningar → Elever:** lägg till elev (namn, och Tab5:ans enhets-id om eleven har en), byt kod eller ta bort.
- Välj vilken elev du tittar på i **elevväljaren** uppe till vänster i föräldravyn.
- Varje elev får en **fast kod på 5 tecken** (t.ex. `K7M2Q`, inga lättförväxlade tecken som 0/O och 1/I).
  Koden är elevens inloggning i mobilchatten. *Ny kod* gör den gamla ogiltig.
- Spärr mot gissning: högst 20 felaktiga försök per 10 minuter och adress.
- Befintliga data från före flera-elever-stödet hör till den första eleven.
- Tekniskt: Hibernate `@TenantId` (kolumnen `student_key`) filtrerar läxor, plan, material och flashkort
  per elev; pass, poäng och samtal hör till elevens enhets-id.

### Elevchatt i mobilen

`/chat.html` är en egen, mobilanpassad sida för eleven (byggd för iPhone): chatta med Pluggis (text eller
🎤), snabbknappar för att börja plugga/förhör/avsluta, se dagens plan och bocka av, se läxorna och ladda upp
material (foto, skärmdump, Word, PDF), öva flashkort själv (fliken **Kort**: visa svar, rätt/fel, uppläsning – ger poäng men syns inte på Tab5), och se poängen.

- Eleven öppnar `/chat.html` och skriver in sin kod – eller får länken med koden (`/chat.html#k=KOD`) via
  *Kopiera länk* / *Dela* under Inställningar → Elever. Öppna i Safari → Dela → *Lägg till på hemskärmen*.
- Med koden når eleven bara sitt eget: chatt, plan, läxor, uppladdning, kortlekar (öva, inte ändra) och poäng – inte inställningar,
  poängbonus, utbetalningar, kalender, loggar eller syskonens data.
- Tryck på Pluggis-namnet i chatten för att logga ut (t.ex. på en delad telefon).
- Tab5 och mobilen för samma elev kan vara anslutna samtidigt och ser samma samtal.

### Eleven och coachens ton

Under **Inställningar → Eleven och coachen**: elevens namn, coachens ton (lugn, rak, lekfull, som en äldre
kompis eller som en idrottscoach) och egna önskemål. Det läggs till efter coachens fasta regler – coachen
ger fortfarande inga färdiga svar.

Statistik finns även som JSON: `GET /api/devices/{id}/stats?days=14`.

## Läxor in, planering och påminnelser

**Skolan använder InfoMentor**, som har en kalenderprenumeration (`webcals://im2-api.infomentor.se/…`).
Under **Läxor & prov → 📅 Kalender** klistrar du in länken, väljer "bara läxor och prov" och trycker
**Spara och synka**. Den synkas sedan varje timme; ändrade datum ger ny plan. Länken är personlig –
behandla den som ett lösenord (den sparas bara i Pluggis databas).

Andra vägar in:

1. **.ics-fil:** samma flik → *Välj .ics-fil*, förhandsgranska och välj vilka händelser som ska med.
2. **Skärmdump av veckoplanen** → iOS-genväg "Pluggis: läxor"
   (*Dela-menyn → Hämta innehåll från URL*, POST `/api/homework/photo?deviceId=tab5-1`,
   formulär med fältet `image`, header `X-Pluggis-Token`).
3. **Klistra in text** från veckoplanen/veckobrevet: `POST /api/homework/text` med `{"text":"..."}`.
4. **Ladda upp fil** (bild, Word .docx, PDF, .txt): *📎 Ladda upp fil* och välj **Ny läxa eller prov**
   eller en befintlig läxa. Kopplat till en läxa blir filen *material*: Claude läser vad som ingår
   (avsnitt, sidor, begrepp), läxan uppdateras, planen görs om och – om du kryssar i – blir det flashkort.
   `POST /api/homework/upload` (fält `file`, upprepas för flera filer – högst 10; valfritt `homeworkId`,
   `flashcards=true`). Flera sidor läses av Claude tillsammans; foton förminskas i webbläsaren innan de skickas.
   Word-text läses lokalt och sparas även om Claude inte svarar. Gamla .doc stöds inte – spara som .docx.

Kalendern ger oftast bara titel och datum – saknas innehållet frågar coachen vad som ingår. Från skärmdump och text läser Claude ut läxa/prov, datum och innehåll.

**Planering:** när innehållet finns (`set_homework_content` via coachen, eller
`POST /api/plan/homework/{id}/content`) gör Claude en plan med små moment (max ~20 min) på
pluggdagarna fram till datumet. Inför prov är det nya materialet klart 1–2 dagar innan och sista
dagarna är repetition och flashkort. Avbockade moment ger poäng och ligger kvar vid omplanering.
**Max minuter per dag och pluggdagar** ställs per elev under **Inställningar → Planering** (max gäller allt
plugg den dagen, alla läxor och prov tillsammans). Kryssa i *Gör om befintliga planer* så planeras allt om.
Standard för nya elever: `PLUGGIS_MAX_MINUTES_PER_DAY` (40) och `PLUGGIS_STUDY_DAYS`.
**Vikt** (10–100 %, standard 50 %) sätts per läxa/prov i admin. Högre vikt ger mer tid, fler repetitioner och
tidigare start i planen, lägre vikt håller det kort. Max per dag gäller alltid; ändrad vikt gör om planen.

**Anpassat för ADHD:**
- **Ett block per ämne varje dag:** momenten grupperas per läxa/prov (närmast deadline först, sedan vikt) så att eleven inte hoppar mellan ämnen. Varje ämne har fast färg och symbol (🧪 NO, ➗ matte, 📖 svenska, 🇬🇧 engelska, 🗣️ språk, 🌍 SO …) i chatten, admin och kalendern. Dagar du ordnat för hand behåller din ordning.
- Varje dag börjar med ett **startsteg** på 2–5 minuter ("läs bara rubrikerna"), så det är lätt att komma igång.
- Aldrig två läsmoment i rad; repetition först med boken framme, sedan utan (som på provet).
- **Buffert:** planen fyller bara ~80 % av max per dag och lämnar en lätt reservdag före provet.
- **Varje natt** (03:30) planeras det som ligger efter om, inom max per dag – i stället för att staplas på dagen efter.
- **Hela dagen klar** ger +5 i bonus, och chatten visar nedräkning och "% klart" per prov/läxa (coachen nämner det också).
- **Pluggtid och om-så-plan** per elev (Inställningar → Planering), t.ex. "16:15, efter mellanmålet".

**Påminnelser:** vid elevens pluggtid (standard 16:30) säger Tab5/chatten vad som står först på dagens plan
och vad som ska in imorgon, och två timmar senare kommer en mjuk knuff om inget pass startats. Bara på pluggdagarna.

```bash
curl -s -H "X-Pluggis-Token: $T" -H 'Content-Type: application/json' \
  -d '{"text":"v.48: Engelska glosförhör fredag, kap 3 s. 42. NO-prov 4/12 fotosyntesen, Utkik s. 40–72"}' \
  "localhost:8080/api/homework/text?deviceId=tab5-1"
curl -s -H "X-Pluggis-Token: $T" -H 'Content-Type: application/json' \
  -d '{"content":"Utkik kap 4 s. 40–72 + begreppslistan","kind":"TEST"}' \
  localhost:8080/api/plan/homework/2/content
curl -s -H "X-Pluggis-Token: $T" localhost:8080/api/plan/today
curl -s -H "X-Pluggis-Token: $T" localhost:8080/api/plan/upcoming?days=14
```

## Google Kalender

Planen kan läggas in i en Google Kalender: **ett pass per dag** vid elevens pluggtid, lika långt som dagens
moment, med momenten (☐/✅) i beskrivningen och en notis när det är dags. Avbockningar syns efter ca 15 sekunder,
och varje natt rullas fönstret (3 veckor) fram. Pluggis rör bara sina egna pass.

Pluggis använder ett **tjänstekonto** och pratar själv med Google – ingenting behöver öppnas mot internet.

1. [console.cloud.google.com](https://console.cloud.google.com): skapa ett projekt (t.ex. *pluggis*).
2. *APIs & Services → Library*: aktivera **Google Calendar API**.
3. *IAM & Admin → Service Accounts → Create*: namn *pluggis*, inga roller behövs. Öppna kontot →
   *Keys → Add key → JSON*. Spara filen som `secrets/google-sa.json` bredvid `docker-compose.yml`.
4. `docker compose up -d` – loggen visar "Google Kalender påslagen (tjänstekonto …)".
5. I Google Kalender: skapa gärna en egen kalender (*Pluggis – Fred*), öppna dess inställningar →
   *Dela med specifika personer* → lägg till tjänstekontots adress med **Gör ändringar i händelser**.
   Kopiera **Kalender-id** under *Integrera kalender*.
6. Pluggis → Inställningar → **Google Kalender** → klistra in kalender-id → *Koppla och synka*.
   Dela kalendern med eleven så dyker passen upp i elevens telefon.

Kalender-id kopplas per elev. *Koppla bort* tar bort kommande pass ur kalendern.

## Flashkort

- Coachen skapar lekar med `create_flashcards` (från ämne eller elevens eget material),
  eller så fotar eleven glosor/anteckningar (långt tryck på röda knappen).
- Förhör sker med rösten: coachen läser framsidan (som också visas på skärmen), eleven svarar,
  coachen rättar generöst och kortet vänds på skärmen.
- **Leitner-repetition** i fem fack: rätt → nästa fack (10 min, 1, 3, 7, 21 dagar),
  fel → fack 1 och tillbaka om 2 minuter. Prompten finns i `prompts/flashcards.md`.

## Poäng och veckopeng

Grundtanken är att **belöna ansträngning mer än rätt svar** – det är vanan att sätta sig som ska byggas.

| Vad | Poäng |
|---|---|
| Försök på ett kort | 1 (+2 om rätt), max 2 gånger per kort och dag |
| Minut i ett pass | 1 per minut, bara verklig tid, högst planerad tid, minst 3 min |
| Höll hela passet | +5 |
| Läxa klar | +10 |
| Moment i planen avbockat | +3 |
| Streak (dagar i rad med pass) | 3 × antal dagar (max 7) |
| Bonus från förälder | valfritt |

- **Av/på per elev:** Inställningar → Eleven och coachen → *Använd poäng*. Av döljer poängknappen och fliken Poäng i elevchatten, poängnotiser i chatten och på Tab5, och coachen nämner inte poäng eller pengar. Poängen räknas ändå i bakgrunden.
- Poäng dras **aldrig** av.
- Kronor: `PLUGGIS_SEK_PER_POINT` (standard 0,10 = 10 poäng/krona) med tak `PLUGGIS_WEEKLY_CAP_SEK`
  (standard 50 kr/vecka). Sätt `PLUGGIS_REWARDS_ENABLED=false` om coachen inte ska prata om pengar.
- Reglerna ligger i `RewardRules` och är lätta att ändra.
- Pengar som belöning kan på sikt tränga undan den egna motivationen. Överväg att trappa ner
  pengadelen när vanan sitter, och fortsätt med poäng och streak.

## Kostnad

Fliken **Kostnad** i admin visar vad Claude kostar: denna månad, idag, staplar för de senaste 30 dagarna, per funktion (coach, planering, foto, flashkort …) och per elev. Varje anrop sparas per dag med tokens och kostnad enligt API-prislistan (`pluggis.costs` i `application.yml`, växelkurs med `PLUGGIS_USD_SEK`, standard 10 kr/USD). Anrop som gått via abonnemanget (Claude Code) kostar inget extra och visas separat som "sparat". Fliken visar också hur mycket av Azures 500 000 gratis tecken per månad som gått åt. Siffrorna är en uppskattning – den exakta fakturan finns i Claude Console.

## Säkerhet och integritet

Det här hanterar en minderårigs röst, bilder och skolarbete.

- Sätt alltid `PLUGGIS_DEVICE_TOKEN`. Kör på hemnätet eller bakom VPN.
  Ska något exponeras publikt (t.ex. som MCP-connector till Claude-appen) behövs riktig auth.
- Elevens innehåll loggas inte på INFO-nivå. Behåll det så.
- Material på en läxa sparas: texten (från Word/text, eller som Claude läst av bilden/PDF:en) och en förminskad
  sidbild för foton, så att eleven kan titta på sidan igen och få den uppläst. Det tas bort med läxan eller eleven.
  Bilder som laddas upp för att skapa *nya* läxor sparas inte.
- Prata igenom med eleven vad som sparas och vad en förälder kan se.

## Nästa steg

1. Kompilera och kör testerna lokalt (`mvn verify`) och prova coachen med curl.
2. Tab5: flasha färdig firmware och få upp WebSocket + knappar (text först, ljud sen).
3. Ljud på riktiga Tab5: tryck-och-prata enligt `docs/protokoll.md`, mät latens i Grafana.
4. Föräldravy: veckans pass, läxor, poäng och utbetalning (enkel sida eller Grafana-panel).
5. MCP-server så att samma läxlista nås från Claude-appen och hemagenten.
