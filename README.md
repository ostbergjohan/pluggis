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
| WebSocket för Tab5 (text, knappar, timer, foto-begäran) | ✅ |
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
4. Föräldravy: veckans pass och läxor (enkel sida eller Grafana-panel).
5. MCP-server så att samma läxlista nås från Claude-appen och hemagenten.
