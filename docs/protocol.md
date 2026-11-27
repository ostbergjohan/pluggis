# Protocol: Tab5 ⇄ backend

Connection: `ws://<host>:8080/ws/device?deviceId=tab5-1&token=<the student's code>` (the server switches to the
student's own device id; the parent view connects with its login)

All messages are JSON text with the field `type`.

## Device → server

| type | Fields | Meaning |
|---|---|---|
| `text` | `text` | What the student said (after speech to text) or typed on the keyboard |
| `button` | `button` (`main`/`photo`), `gesture` (`click`/`long`/`double`) | Button press from the Dual Button (U025) |
| `ping` | – | Keep the connection alive, answered with `pong` |

Button layout: **red** click = photograph a task, red long = photograph vocabulary/notes → flashcards.
**Blue** click = start / need help, blue long = end the session, blue double = need help.

Examples:

```json
{"type":"text","text":"jag ska göra matteläxan"}
{"type":"button","button":"main","gesture":"long"}
```

## Server → device

| type | Fields | The device should |
|---|---|---|
| `thinking` | – | Show a "thinking" animation |
| `say` | `text` | Show the text and read it aloud |
| `timer` | `remainingSeconds`, `totalSeconds`, `task` | Draw a time bar / LED strip (sent every 10 seconds during a session) |
| `session` | `state` (`started`/`time_up`/`finished`), `task`, `minutes` | Change mode on the screen and LEDs |
| `take_photo` | `countdownSeconds`, `uploadPath`, `purpose` | Show a camera preview, count down, take a JPEG and POST it to `uploadPath` |
| `card` | `cardId`, `front`, `hint` | Show the flashcard's front large on the screen |
| `card_answer` | `cardId`, `back`, `correct`, `box` | Flip the card: show the answer, green/red, and box 1–5 |
| `points` | `delta`, `weekTotal`, `reason` | Show "+3" with a small animation and the week's total |
| `reminder` | `text` | Reminder: a clear signal (sound/light), show and read aloud |
| `error` | `message` | Log |

## Photo

```
POST /api/homework/photo?deviceId=tab5-1[&hint=...]
Header: X-Pluggis-Token: <the student's code>
Content-Type: multipart/form-data, field "image" (image/jpeg, max 5 MB)
```

Response: `{"saved":[...homework...],"note":"..."}`. If `deviceId` is sent, the device also gets a `say` message
about what was added.

## Flashcards from a photo

```
POST /api/flashcards/photo?deviceId=tab5-1[&hint=...][&count=20]
Header: X-Pluggis-Token: <the student's code>
Content-Type: multipart/form-data, field "image"
```

Creates a new deck. With `deviceId` the device gets a `say` about how many cards were created.

## Speech to text for answers

```
POST /api/speech/transcribe?deviceId=tab5-1[&lang=en][&hint=...]
Header: X-Pluggis-Token: <the student's code>
Content-Type: audio/wav
```

Response: `{"text":"..."}`. Without `lang` the student's language is used (Swedish → KB-Whisper, English →
multilingual Whisper); `lang=es|de|fr|en` for vocabulary answered in the language being learned, and `hint` is the
expected answer, which helps Whisper with short words.

## Audio

Requires Pluggis to have a speech service (`PLUGGIS_SPEECH_URL`, see `speech/`).

1. After connecting the device sends
   `{"type":"hello","audio":true,"audioFormat":"wav"}` (the student chat sends `webm`).
   Small devices (Tab5) add `"audioChunk":8192`: the read-aloud is then converted to wav 16 kHz mono and sent as
   several binary frames of at most that many bytes after the `audio` message (`bytes` = the whole sentence's length).
   The device collects the frames until `bytes` is reached. Without `audioChunk` the whole wav file comes in one frame.
   A long press (`"gesture":"long"`) on the main button ends a running session directly on the server.
   Response: `{"type":"hello_ack","audio":true}`.
2. **Push-to-talk:** when the button is released the device sends the whole utterance as **one binary frame**
   (wav 16 kHz mono 16-bit, or webm/opus). Max about 4 MB.
3. The server answers in order:
   - `{"type":"thinking"}`
   - `{"type":"heard","text":"jag ska göra matteläxan"}` – what Whisper heard
   - for each sentence, as soon as it's ready:
     `{"type":"audio","format":"wav","seq":0,"text":"Okej!","bytes":48210}` **followed by a binary frame** with the
     wav audio. Play in `seq` order. (Other text messages, e.g. `timer`, can come in between.)
   - `{"type":"say","text":"…the whole reply…"}` – for the screen
   - `{"type":"audio_end","count":3}`
4. Text and buttons (`text`, `button`) also give a spoken reply when `audio` is on.

Without `hello`/`audio:true` – or without a speech service – everything works as before with text only.

**Latency to keep an eye on** (`:9091/actuator/prometheus`):

| Metric | What |
|---|---|
| `pluggis_speech_stt_seconds` | speech to text |
| `pluggis_claude_first_text_seconds{purpose="coach"}` | until Claude starts answering |
| `pluggis_voice_first_audio_seconds` | from utterance to the first audio – what you notice |
| `pluggis_speech_tts_seconds` | text to speech per sentence |
| `pluggis_claude_tokens_total{kind="cache_read"}` | that prompt caching works |
