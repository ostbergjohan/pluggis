# Läxcoach

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
| Läxor in via skärmdump/foto, inklistrad text (InfoMentor) eller iCal-länk | ✅ |
| WebSocket för Tab5 (text, knappar, timer, foto, kort, poäng) | ✅ |
| REST för test med curl / iOS-genväg | ✅ |
| Prometheus-metrik för Claude-latens och tokens | ✅ |
| Ljud (tal till text / text till tal) | ⏳ fas 2, se `docs/protokoll.md` |
| Firmware för Tab5 | ⏳ eget repo eller `firmware/` senare |
| Föräldravy | ⏳ |

## Kom igång

Kräver Java 21 och Maven.

```bash
cp .env.example .env        # fyll i ANTHROPIC_API_KEY och LAXCOACH_DEVICE_TOKEN
set -a; . ./.env; set +a
mvn spring-boot:run
```

Data hamnar i en H2-fil under `./data`. Kör med Postgres via `SPRING_PROFILES_ACTIVE=postgres`.

### Testa coachen utan hårdvara

```bash
T=$LAXCOACH_DEVICE_TOKEN
curl -s -H "X-Laxcoach-Token: $T" -H 'Content-Type: application/json' \
  -d '{"text":"(Knapptryck: jag vill börja plugga nu.)"}' \
  localhost:8080/api/devices/tab5-1/coach

curl -s -H "X-Laxcoach-Token: $T" -F image=@stencil.jpg \
  "localhost:8080/api/homework/photo?deviceId=tab5-1"

curl -s -H "X-Laxcoach-Token: $T" localhost:8080/api/homework
curl -s -H "X-Laxcoach-Token: $T" localhost:8080/api/devices/tab5-1/sessions/active
```

Flashkort och poäng:

```bash
curl -s -H "X-Laxcoach-Token: $T" -H 'Content-Type: application/json' \
  -d '{"topic":"Engelska glosor: djur","subject":"Engelska","count":10}' \
  localhost:8080/api/flashcards/generate
curl -s -H "X-Laxcoach-Token: $T" -F image=@glosor.jpg "localhost:8080/api/flashcards/photo?deviceId=tab5-1"
curl -s -H "X-Laxcoach-Token: $T" localhost:8080/api/flashcards/decks
curl -s -H "X-Laxcoach-Token: $T" -o kort.tsv localhost:8080/api/flashcards/decks/1/export.tsv   # till Anki

curl -s -H "X-Laxcoach-Token: $T" localhost:8080/api/devices/tab5-1/rewards            # veckans poäng och kronor
curl -s -H "X-Laxcoach-Token: $T" -H 'Content-Type: application/json' \
  -d '{"points":50,"reason":"Bra jobbat på provet!"}' localhost:8080/api/devices/tab5-1/rewards/bonus
curl -s -H "X-Laxcoach-Token: $T" -X POST localhost:8080/api/devices/tab5-1/rewards/payouts  # markera veckan utbetald
```

WebSocket (t.ex. med `websocat`):

```bash
websocat "ws://localhost:8080/ws/device?deviceId=tab5-1&token=$T"
{"type":"text","text":"jag har matteläxa till fredag"}
{"type":"button","button":"main","gesture":"long"}
```

## Arkitektur

```
Tab5 (firmware)                      Spring Boot "laxcoach"
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

## Läxor in, planering och påminnelser

**Skolan använder InfoMentor**, som (såvitt vi hittat) saknar iCal-export och öppet API, och
inloggning sker oftast med BankID – därför hämtas inget automatiskt därifrån. I stället:

1. **Skärmdump av veckoplanen** i InfoMentor-appen → iOS-genväg "Läxcoach: läxor"
   (*Dela-menyn → Hämta innehåll från URL*, POST `/api/homework/photo?deviceId=tab5-1`,
   formulär med fältet `image`, header `X-Laxcoach-Token`).
2. **Klistra in text** från veckoplanen/veckobrevet: `POST /api/homework/text` med `{"text":"..."}`.
3. **iCal-länk** om skolan har en annan kalender: sätt `LAXCOACH_ICAL_URL` (synkas varje timme),
   eller ladda upp en .ics till `POST /api/calendar/import`.

Claude läser ut läxa/prov, datum och vad som ingår. Saknas innehållet frågar coachen efter det.

**Planering:** när innehållet finns (`set_homework_content` via coachen, eller
`POST /api/plan/homework/{id}/content`) gör Claude en plan med små moment (max ~20 min) på
pluggdagarna fram till datumet. Inför prov är det nya materialet klart 1–2 dagar innan och sista
dagarna är repetition och flashkort. Avbockade moment ger poäng och ligger kvar vid omplanering.
Pluggdagar och max minuter per dag: `LAXCOACH_STUDY_DAYS`, `LAXCOACH_MAX_MINUTES_PER_DAY`.

**Påminnelser:** kl. 16:30 (vardagar) säger Tab5 vad som står först på dagens plan och vad som
ska in imorgon, och kl. 18:30 kommer en mjuk knuff om inget pass startats. Tiderna ändras med
`laxcoach.reminders.start-cron` / `nudge-cron`.

```bash
curl -s -H "X-Laxcoach-Token: $T" -H 'Content-Type: application/json' \
  -d '{"text":"v.48: Engelska glosförhör fredag, kap 3 s. 42. NO-prov 4/12 fotosyntesen, Utkik s. 40–72"}' \
  "localhost:8080/api/homework/text?deviceId=tab5-1"
curl -s -H "X-Laxcoach-Token: $T" -H 'Content-Type: application/json' \
  -d '{"content":"Utkik kap 4 s. 40–72 + begreppslistan","kind":"TEST"}' \
  localhost:8080/api/plan/homework/2/content
curl -s -H "X-Laxcoach-Token: $T" localhost:8080/api/plan/today
curl -s -H "X-Laxcoach-Token: $T" localhost:8080/api/plan/upcoming?days=14
```

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

- Poäng dras **aldrig** av.
- Kronor: `LAXCOACH_SEK_PER_POINT` (standard 0,10 = 10 poäng/krona) med tak `LAXCOACH_WEEKLY_CAP_SEK`
  (standard 50 kr/vecka). Sätt `LAXCOACH_REWARDS_ENABLED=false` om coachen inte ska prata om pengar.
- Reglerna ligger i `RewardRules` och är lätta att ändra.
- Pengar som belöning kan på sikt tränga undan den egna motivationen. Överväg att trappa ner
  pengadelen när vanan sitter, och fortsätt med poäng och streak.

## Säkerhet och integritet

Det här hanterar en minderårigs röst, bilder och skolarbete.

- Sätt alltid `LAXCOACH_DEVICE_TOKEN`. Kör på hemnätet eller bakom VPN.
  Ska något exponeras publikt (t.ex. som MCP-connector till Claude-appen) behövs riktig auth.
- Elevens innehåll loggas inte på INFO-nivå. Behåll det så.
- Bilder sparas inte, bara de utlästa läxorna.
- Prata igenom med eleven vad som sparas och vad en förälder kan se.

## Nästa steg

1. Kompilera och kör testerna lokalt (`mvn verify`) och prova coachen med curl.
2. Tab5: flasha färdig firmware och få upp WebSocket + knappar (text först, ljud sen).
3. Fas 2 ljud: välj väg i `docs/protokoll.md`, mät latens, testa Haiku mot Sonnet.
4. Föräldravy: veckans pass, läxor, poäng och utbetalning (enkel sida eller Grafana-panel).
5. MCP-server så att samma läxlista nås från Claude-appen och hemagenten.
