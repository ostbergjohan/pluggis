# Protokoll: Tab5 ⇄ backend

Anslutning: `ws://<host>:8080/ws/device?deviceId=tab5-1&token=<elevens kod>` (servern byter till elevens eget enhets-id; föräldravyn ansluter med sin inloggning)

Alla meddelanden är JSON-text med fältet `type`.

## Enhet → server

| type | Fält | Betydelse |
|---|---|---|
| `text` | `text` | Det eleven sa (efter tal till text) eller skrev på tangentbordet |
| `button` | `button` (`main`/`photo`), `gesture` (`click`/`long`/`double`) | Knapptryck från Dual Button (U025) |

Knapplayout: **röd** klick = fota uppgift, röd långt = fota glosor/anteckningar → flashkort.
**Blå** klick = börja / behöver hjälp, blå långt = avsluta passet, blå dubbel = behöver hjälp.
| `ping` | – | Håll liv i anslutningen, svaras med `pong` |

Exempel:

```json
{"type":"text","text":"jag ska göra matteläxan"}
{"type":"button","button":"main","gesture":"long"}
```

## Server → enhet

| type | Fält | Enheten ska |
|---|---|---|
| `thinking` | – | Visa "tänker"-animation |
| `say` | `text` | Visa texten och läsa upp den |
| `timer` | `remainingSeconds`, `totalSeconds`, `task` | Rita tidsstapel / LED-list (skickas var 10:e sekund under pass) |
| `session` | `state` (`started`/`time_up`/`finished`), `task`, `minutes` | Byta läge på skärm och LED |
| `take_photo` | `countdownSeconds`, `uploadPath`, `purpose` | Visa kameraförhandsbild, räkna ner, ta JPEG och POST:a till `uploadPath` |
| `card` | `cardId`, `front`, `hint` | Visa flashkortets framsida stort på skärmen |
| `card_answer` | `cardId`, `back`, `correct`, `box` | Vänd kortet: visa svaret, grönt/rött, och fack 1–5 |
| `points` | `delta`, `weekTotal`, `reason` | Visa "+3" med en liten animation och veckans summa |
| `reminder` | `text` | Påminnelse: tydlig signal (ljud/ljus), visa och läs upp |
| `error` | `message` | Logga |

## Foto

```
POST /api/homework/photo?deviceId=tab5-1[&hint=...]
Header: X-Pluggis-Token: <elevens kod>
Content-Type: multipart/form-data, fält "image" (image/jpeg, max 5 MB)
```

Svar: `{"saved":[...läxor...],"note":"..."}`. Om `deviceId` skickas med får enheten
dessutom ett `say`-meddelande om vad som lades till.

Samma endpoint används av iOS-genvägen: *Ta bild → Hämta innehåll från URL (POST, formulär, fält `image`)*.

## Flashkort från foto

```
POST /api/flashcards/photo?deviceId=tab5-1[&hint=...][&count=20]
Header: X-Pluggis-Token: <elevens kod>
Content-Type: multipart/form-data, fält "image"
```

Skapar en ny kortlek. Med `deviceId` får enheten ett `say` om hur många kort som skapades.

## Ljud

Kräver att Pluggis har en ljudtjänst (`PLUGGIS_SPEECH_URL`, se `speech/`).

1. Efter anslutning skickar enheten
   `{"type":"hello","audio":true,"audioFormat":"wav"}` (simulatorn skickar `webm`).
   Små enheter (Tab5) lägger till `"audioChunk":8192`: då görs uppläsningen om till wav 16 kHz mono och skickas
   som flera binära ramar om högst så många byte efter `audio`-meddelandet (`bytes` = hela meningens längd).
   Enheten samlar ramarna tills `bytes` är nådd. Utan `audioChunk` kommer hela wav-filen i en ram.
   Långt tryck (`"gesture":"long"`) på huvudknappen avslutar ett pågående pass direkt på servern.
   Svar: `{"type":"hello_ack","audio":true}`.
2. **Tryck-och-prata:** när knappen släpps skickar enheten hela yttrandet som **en binär ram**
   (wav 16 kHz mono 16-bit, eller webm/opus). Max ca 4 MB.
3. Servern svarar i ordning:
   - `{"type":"thinking"}`
   - `{"type":"heard","text":"jag ska göra matteläxan"}` – det KB-Whisper hörde
   - för varje mening, så fort den är klar:
     `{"type":"audio","format":"wav","seq":0,"text":"Okej!","bytes":48210}` **följt av en binär ram** med wav-ljudet.
     Spela upp i `seq`-ordning. (Andra textmeddelanden, t.ex. `timer`, kan komma emellan.)
   - `{"type":"say","text":"…hela svaret…"}` – för skärmen
   - `{"type":"audio_end","count":3}`
4. Text och knappar (`text`, `button`) ger också uppläst svar när `audio` är på.

Utan `hello`/`audio:true` – eller utan ljudtjänst – fungerar allt som tidigare med bara text.

**Latens att hålla koll på** (`/actuator/prometheus`):

| Mätvärde | Vad |
|---|---|
| `pluggis_speech_stt_seconds` | tal till text |
| `pluggis_claude_first_text_seconds{purpose="coach"}` | tills Claude börjar svara |
| `pluggis_voice_first_audio_seconds` | från yttrande till första ljudet – det som känns |
| `pluggis_speech_tts_seconds` | text till tal per mening |
| `pluggis_claude_tokens_total{kind="cache_read"}` | att prompt caching fungerar |
