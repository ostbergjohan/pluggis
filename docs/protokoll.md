# Protokoll: Tab5 ⇄ backend

Anslutning: `ws://<host>:8080/ws/device?deviceId=tab5-1&token=<PLUGGIS_DEVICE_TOKEN>`

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
Header: X-Pluggis-Token: <token>
Content-Type: multipart/form-data, fält "image" (image/jpeg, max 5 MB)
```

Svar: `{"saved":[...läxor...],"note":"..."}`. Om `deviceId` skickas med får enheten
dessutom ett `say`-meddelande om vad som lades till.

Samma endpoint används av iOS-genvägen: *Ta bild → Hämta innehåll från URL (POST, formulär, fält `image`)*.

## Flashkort från foto

```
POST /api/flashcards/photo?deviceId=tab5-1[&hint=...][&count=20]
Header: X-Pluggis-Token: <token>
Content-Type: multipart/form-data, fält "image"
```

Skapar en ny kortlek. Med `deviceId` får enheten ett `say` om hur många kort som skapades.

## Fas 2: ljud (inte byggt än)

Två vägar, välj efter hur xiaozhi-firmwaren fungerar på Tab5:

1. **Ljud direkt hit:** binära WebSocket-ramar med Opus (16 kHz mono) upp, och
   TTS-ljud ner. Backenden kör tal till text (t.ex. KB-Whisper lokalt) och text till tal
   (t.ex. Piper med svensk röst). Kräver `SpeechToText`/`TextToSpeech` i backenden.
2. **xiaozhi-protokollet:** implementera xiaozhis dokumenterade WebSocket-protokoll
   (hello/listen/stt/tts/llm-meddelanden + Opus) i en egen handler, och återanvänd
   `CoachService` som "LLM". Då kan färdig firmware användas oförändrad.

Mät latens per steg (STT → Claude → TTS). Claude-delen finns redan som
`pluggis_claude_request_seconds` i `/actuator/prometheus`.
