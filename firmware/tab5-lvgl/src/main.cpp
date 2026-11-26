// Pluggis på M5Stack Tab5 – skrivbordsskärmen.
//
// Pratar med Pluggis-servern över WebSocket enligt docs/protokoll.md:
//  - visar coachens svar, "tänker", timern för passet, flashkort och poäng
//  - knappar på pekskärmen: Börja/Hjälp, Avsluta, och håll in "Prata" för att prata
//  - rösten spelas in (16 kHz mono) och skickas som wav; svaret spelas upp mening för mening
//
// Gränssnittet ritas med LVGL 9 i ui.cpp (simulator i sim/). Ingen kamera ännu, ingen Dual Button.

#include <M5Unified.h>
#include <lvgl.h>
#include "ui.h"
#include "brand.h"   // loggan och figuren (PNG)
#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <deque>
#include <vector>
#include <algorithm>
#include <time.h>

#if __has_include("config.h")
#include "config.h"
#else
#warning "include/config.h saknas – bygger med config.example.h (kopiera och fyll i innan du flashar)"
#include "config.example.h"
#endif

#ifndef PLUGGIS_IDLE_MINUTES
#define PLUGGIS_IDLE_MINUTES 5   // stäng av efter så många minuter utan aktivitet (0 = aldrig)
#endif

static uint32_t lastActivity = 0;
static bool pendingPractice = false;   // coachen bad om en övning (startas från loop())
static String pendingKind;
static long pendingHw = 0;
static bool asleep = false, idleWarned = false;
static bool booting = true;   // startskärmen visas tills WiFi och läxorna är på plats
static String wsLast = "inte startad";   // senaste som hänt med WebSocket (visas när man trycker på "Ansluter")
static uint32_t wsLastAt = 0, wsTries = 0;
static long practicePlanItem = 0;      // planens moment vars övning pågår (bockas av när övningen är klar)
static String lastError;   // senaste felet från servern eller ett anrop – visas på startskärmen och i loggen

// ---------------------------------------------------------------- skärmen (LVGL)
// Allt som syns byggs i ui.cpp. Här finns bara tillståndet; redraw() bygger om den skärm som ändrats.
static int W = 1280, H = 720;
static lv_display_t* lvDisp = nullptr;
static bool touchDown = false, swallowTouch = false;
static int touchX = 0, touchY = 0;
static void redraw();

// Skärmar: coachen (chatt med röst) eller Öva (kortlekar på skärmen)
enum class Screen { Coach, OvaList, OvaCard, HwList, HwDetail, Plan, Game };
static Screen screen = Screen::OvaList;   // Tab5 startar på Öva
enum class Game { None, Explain, Exam, Match, Order };
static Game game = Game::None;

// Ljudvolym (sparas i enheten) och batteri
static Preferences prefs;
static int volume = PLUGGIS_VOLUME;
static int batteryLevel = -1;
static bool batteryCharging = false;

// ---------------------------------------------------------------- tillstånd
static WebSocketsClient ws;
static bool wsUp = false;
static bool audioOn = false;

enum class Mode { Coach, Card };
static Mode mode = Mode::Coach;
static String sayText = "Hej! Håll in Prata och säg vad du vill göra, eller tryck på Börja.";
static String heardText;
static bool thinking = false;
static uint32_t thinkingSince = 0;

static bool sessionActive = false;
static String sessionTask;
static int timerRemaining = 0, timerTotal = 0;
static uint32_t timerAt = 0;

static String cardFront, cardHint, cardBack;
static bool cardAnswered = false, cardCorrect = false;
static int cardBox = 0;

static int weekPoints = -1;
static bool pointsOn = false;    // poäng av/på för eleven (admin) – hämtas från /api/chat/me
static bool meLoaded = false;

// Inspelning (tryck-och-prata)
static constexpr uint32_t REC_RATE = 16000;
static constexpr size_t REC_MAX_SAMPLES = REC_RATE * 30;   // högst 30 s
static constexpr size_t REC_CHUNK = 800;                   // 50 ms per bit
static int16_t* recBuf = nullptr;                          // 44 byte wav-huvud + ljud, i PSRAM
static size_t recSamples = 0;
static bool recording = false;
static uint32_t recStarted = 0;

// Uppspelning: en kö med wav-meningar i seq-ordning
struct Clip {
  uint8_t* data;
  size_t len;
  int tag = -1;   // uppläsning av läxan: vilken mening
};
static std::deque<Clip> playQueue;
static Clip playing{nullptr, 0};
// Ljudet kommer i bitar (servern delar upp det, se "audioChunk" i hello): samla tills hela meningen finns
static uint8_t* incoming = nullptr;
static size_t incomingLen = 0, incomingFill = 0;
static constexpr int AUDIO_CHUNK = 8192;   // under bibliotekets gräns (15 kB per ram)

// Ljudeffekter: en liten melodi spelas ton för ton från loop() (på en egen kanal, så den inte avbryter talet)
struct Note { uint16_t f, ms; };
static const Note MEL_OK[] = {{523, 90}, {659, 90}, {784, 90}, {1047, 170}};   // mjukare, en oktav ner
static const Note MEL_BAD[] = {{466, 150}, {440, 150}, {415, 150}, {392, 380}};
static const Note MEL_DONE[] = {{392, 120}, {523, 120}, {659, 120}, {784, 200}, {659, 100}, {784, 380}};
static const Note MEL_TICK[] = {{880, 40}};
static const Note* mel = nullptr;
static int melN = 0, melI = 0;
static uint32_t melNext = 0;
static void playMelody(const Note* m, int n) { mel = m; melN = n; melI = 0; melNext = 0; }
#define SFX_OK() playMelody(MEL_OK, 4)
#define SFX_BAD() playMelody(MEL_BAD, 4)
#define SFX_DONE() playMelody(MEL_DONE, 6)
static void pumpMelody() {
  if (!mel || melI >= melN || millis() < melNext) return;
  M5.Speaker.tone(mel[melI].f, mel[melI].ms, 7, false);
  melNext = millis() + mel[melI].ms + 15;
  if (++melI >= melN) mel = nullptr;
}

// Uppläsning av frågor (Pluggis röst via servern, hämtas i bakgrundstråden)
static void showScreen(Screen s, bool force = false);
static void sayAloud(const String& text, const char* lang = "");
static void sayCardSide(const String& text);
static const char* langFrom(String t);
static bool looksSwedish(const String& in);
static const char* guessLang(const String& in);
static const char* deckGuessed = "";   // kortlekens språk enligt omröstning över korten
static void sayStop();
static int examSpoken = -1;
static void examSpeak();


static bool dirtyAll = true, dirtyMain = true, dirtyTimer = true, dirtyTop = true, dirtyOva = false;

// ---------------------------------------------------------------- text
/** Ersätter tecken som typsnittet inte har (emojis, långa streck …) med enkla motsvarigheter. */
static String clean(const char* s) {
  String out;
  if (!s) return out;
  const uint8_t* p = (const uint8_t*)s;
  while (*p) {
    uint32_t cp;
    int n;
    if (*p < 0x80) { cp = *p; n = 1; }
    else if ((*p & 0xE0) == 0xC0) { cp = *p & 0x1F; n = 2; }
    else if ((*p & 0xF0) == 0xE0) { cp = *p & 0x0F; n = 3; }
    else if ((*p & 0xF8) == 0xF0) { cp = *p & 0x07; n = 4; }
    else { p++; continue; }
    bool ok = true;
    for (int i = 1; i < n; i++) {
      if ((p[i] & 0xC0) != 0x80) { ok = false; break; }
      cp = (cp << 6) | (p[i] & 0x3F);
    }
    if (!ok) { p++; continue; }
    if (cp < 0x2000) {
      for (int i = 0; i < n; i++) out += (char)p[i];
    } else if (cp == 0x2013 || cp == 0x2014 || cp == 0x2212) out += '-';
    else if (cp == 0x2018 || cp == 0x2019) out += '\'';
    else if (cp == 0x201C || cp == 0x201D) out += '"';
    else if (cp == 0x2026) out += "...";
    else if (cp == 0x2713 || cp == 0x2714) out += "OK";
    // övrigt (emojis m.m.) hoppas över
    p += n;
  }
  out.trim();
  return out;
}

// ---------------------------------------------------------------- ljud
static void freeClip(Clip& c) {
  if (c.data) free(c.data);
  c = {nullptr, 0};
}

static void stopPlayback() {
  M5.Speaker.stop();
  freeClip(playing);
  for (auto& c : playQueue) free(c.data);
  playQueue.clear();
  if (incoming) free(incoming);
  incoming = nullptr;
  incomingLen = incomingFill = 0;
}

static void pumpPlayback() {
  if (recording) return;
  if (playing.data && !M5.Speaker.isPlaying(0)) freeClip(playing);
  if (!playing.data && !playQueue.empty()) {
    playing = playQueue.front();
    playQueue.pop_front();
    if (!M5.Speaker.playWav(playing.data, playing.len, 1, 0, true)) freeClip(playing);
  }
}

static void writeWavHeader(uint8_t* h, uint32_t dataBytes) {
  auto le32 = [](uint8_t* p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; };
  auto le16 = [](uint8_t* p, uint16_t v) { p[0] = v; p[1] = v >> 8; };
  memcpy(h, "RIFF", 4); le32(h + 4, 36 + dataBytes); memcpy(h + 8, "WAVEfmt ", 8);
  le32(h + 16, 16); le16(h + 20, 1); le16(h + 22, 1); le32(h + 24, REC_RATE);
  le32(h + 28, REC_RATE * 2); le16(h + 32, 2); le16(h + 34, 16);
  memcpy(h + 36, "data", 4); le32(h + 40, dataBytes);
}

static int16_t* recSamplesPtr() { return recBuf + 22; }   // efter 44 byte huvud

static void answerByVoice(bool send);

static void startRecording() {
  if (recording || !recBuf) return;
  sayStop();
  stopPlayback();
  M5.Speaker.end();
  M5.Mic.begin();
  recSamples = 0;
  recording = true;
  recStarted = millis();
  ui::setTalking(true);   // knappen byggs inte om medan den hålls in
}

static void pumpRecording() {
  if (!recording) return;
  // Mic-klassen har två platser i kö: fyll på så länge det finns plats
  while (M5.Mic.isRecording() < 2 && recSamples + REC_CHUNK <= REC_MAX_SAMPLES) {
    if (!M5.Mic.record(recSamplesPtr() + recSamples, REC_CHUNK, REC_RATE)) break;
    recSamples += REC_CHUNK;
  }
}

static void sendJson(JsonDocument& doc) {
  if (!wsUp) return;
  String s;
  serializeJson(doc, s);
  ws.sendTXT(s);
}

static void stopRecording(bool send) {
  if (!recording) return;
  recording = false;
  while (M5.Mic.isRecording()) delay(1);
  M5.Mic.end();
  M5.Speaker.begin();
  M5.Speaker.setVolume(volume);
  M5.Speaker.setChannelVolume(7, 110);   // effektljuden (pling) lägre än talet
  ui::setTalking(false);
  if (screen != Screen::Coach) { answerByVoice(send); return; }
  uint32_t ms = millis() - recStarted;
  if (!send || ms < 400 || recSamples < REC_RATE / 4) {
    if (send) { sayText = "Håll in knappen medan du pratar."; dirtyMain = true; }
    return;
  }
  if (!wsUp) { sayText = "Inte ansluten till Pluggis just nu."; dirtyMain = true; return; }
  uint32_t bytes = recSamples * 2;
  writeWavHeader((uint8_t*)recBuf, bytes);
  ws.sendBIN((uint8_t*)recBuf, 44 + bytes);
  thinking = true;
  thinkingSince = millis();
  heardText = "";
  dirtyMain = true;
}

// ---------------------------------------------------------------- protokoll
static void sendButton(const char* gesture) {
  if (!wsUp) { sayText = "Inte ansluten till Pluggis just nu. Vänta lite och försök igen."; thinking = false; dirtyMain = true; return; }
  JsonDocument doc;
  doc["type"] = "button";
  doc["button"] = "main";
  doc["gesture"] = gesture;
  sendJson(doc);
  thinking = true;
  thinkingSince = millis();
  dirtyMain = true;
}

static void onText(const char* json, size_t len) {
  JsonDocument doc;
  if (deserializeJson(doc, json, len)) return;
  String type = doc["type"] | "";

  if (type == "hello_ack") {
    audioOn = doc["audio"] | false;
    Serial.printf("[pluggis] ansluten, ljud %s\n", audioOn ? "på" : "av (ingen ljudtjänst)");
  } else if (type == "thinking") {
    thinking = true;
    thinkingSince = millis();
    dirtyMain = true;
  } else if (type == "heard") {
    heardText = clean(doc["text"] | "");
    dirtyMain = true;
  } else if (type == "say" || type == "reminder") {
    if (asleep) { asleep = false; M5.Display.setBrightness(200); dirtyAll = true; }
    lastActivity = millis();
    thinking = false;
    sayText = clean(doc["text"] | "");
    if (mode == Mode::Card && cardAnswered) mode = Mode::Coach;
    if (type == "reminder" && (screen == Screen::OvaList || screen == Screen::HwList || screen == Screen::Plan)) showScreen(Screen::Coach);
    if (type == "reminder") {
      M5.Speaker.tone(880, 150);
      delay(180);
      M5.Speaker.tone(1320, 200);
      M5.Display.setBrightness(255);
    }
    dirtyMain = true;
  } else if (type == "audio") {
    if (incoming) free(incoming);
    incomingLen = doc["bytes"] | 0;
    incomingFill = 0;
    incoming = incomingLen && !recording
        ? (uint8_t*)heap_caps_malloc(incomingLen, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT) : nullptr;
  } else if (type == "timer") {
    timerRemaining = doc["remainingSeconds"] | 0;
    timerTotal = doc["totalSeconds"] | timerTotal;
    timerAt = millis();
    if (doc["task"].is<const char*>()) sessionTask = clean(doc["task"].as<const char*>());
    if (!sessionActive) { sessionActive = true; dirtyAll = true; }
    dirtyTimer = true;
  } else if (type == "session") {
    String state = doc["state"] | "";
    bool was = sessionActive;
    sessionActive = state == "started";
    if (doc["task"].is<const char*>()) sessionTask = clean(doc["task"].as<const char*>());
    if (sessionActive && doc["minutes"].is<int>()) {
      timerTotal = timerRemaining = doc["minutes"].as<int>() * 60;
      timerAt = millis();
    }
    if (state == "time_up") { M5.Speaker.tone(660, 300); }
    if (was != sessionActive) dirtyAll = true;
  } else if (type == "card") {
    if (screen != Screen::Coach) return;
    mode = Mode::Card;
    thinking = false;
    cardFront = clean(doc["front"] | "");
    cardHint = clean(doc["hint"] | "");
    cardBack = "";
    cardAnswered = false;
    dirtyMain = true;
  } else if (type == "card_answer") {
    if (screen != Screen::Coach) return;
    mode = Mode::Card;
    cardBack = clean(doc["back"] | "");
    cardCorrect = doc["correct"] | false;
    cardBox = doc["box"] | 0;
    cardAnswered = true;
    dirtyMain = true;
  } else if (type == "points") {
    int delta = doc["delta"] | 0;
    if (doc["weekTotal"].is<int>()) weekPoints = doc["weekTotal"].as<int>();
    if (delta > 0 && pointsOn) ui::toast("+" + std::to_string(delta) + " p");
    dirtyTop = true;
    if (screen != Screen::Coach) dirtyOva = true;
  } else if (type == "open_practice") {
    // Coachen öppnar en övning: gå till Öva och starta den där
    practicePlanItem = 0;   // coachen startade övningen, inte planen
    pendingKind = doc["kind"] | "quiz";
    pendingHw = doc["homeworkId"] | 0L;
    pendingPractice = true;
  } else if (type == "take_photo") {
    thinking = false;
    sayText = "Kameran på skärmen kommer snart. Fota med telefonen i Pluggis-appen så länge.";
    dirtyMain = true;
  } else if (type == "error") {
    String msg = doc["message"] | "";
    Serial.printf("[pluggis] fel från servern: %s\n", msg.c_str());
    lastError = msg;
    if (!booting && msg.length()) ui::toast(std::string("Servern: ") + msg.c_str());
    thinking = false;
    dirtyMain = true;
  }
}

static void onBinary(uint8_t* data, size_t len) {
  if (!incoming || recording || screen != Screen::Coach) return;
  size_t n = len > incomingLen - incomingFill ? incomingLen - incomingFill : len;
  memcpy(incoming + incomingFill, data, n);
  incomingFill += n;
  if (incomingFill >= incomingLen) {
    playQueue.push_back({incoming, incomingLen});
    incoming = nullptr;
    incomingLen = incomingFill = 0;
    if (thinking) { thinking = false; dirtyMain = true; }
  }
}

static void onWs(WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED: {
      wsLast = "ansluten";
      wsLastAt = millis();
      Serial.printf("[pluggis] ansluten till %s:%d som %s\n", PLUGGIS_HOST, PLUGGIS_PORT, PLUGGIS_DEVICE_ID);
      wsUp = true;
      dirtyTop = true;
      JsonDocument doc;
      doc["type"] = "hello";
      doc["audio"] = true;
      doc["audioFormat"] = "wav";
      doc["audioChunk"] = AUDIO_CHUNK;   // ljudet i små bitar, 16 kHz mono
      sendJson(doc);
      break;
    }
    case WStype_DISCONNECTED:
      wsTries++;
      wsLast = wsUp ? String("tappade kopplingen") : String("kom inte fram") + (length ? String(": ") + String((const char*)payload).substring(0, length) : String());
      wsLastAt = millis();
      if (wsUp) Serial.println("[pluggis] frånkopplad, försöker igen");
      else Serial.printf("[pluggis] får ingen WebSocket mot %s:%d%s\n", PLUGGIS_HOST, PLUGGIS_PORT,
                         WiFi.status() == WL_CONNECTED ? "" : " (inget WiFi)");
      wsUp = false;
      thinking = false;
      dirtyTop = dirtyMain = true;
      break;
    case WStype_TEXT:
      onText((const char*)payload, length);
      break;
    case WStype_BIN:
      onBinary(payload, length);
      break;
    case WStype_ERROR:
      wsLast = String("fel") + (length ? String(": ") + String((const char*)payload).substring(0, length) : String());
      wsLastAt = millis();
      Serial.printf("[pluggis] WebSocket-fel: %s\n", wsLast.c_str());
      break;
    default:
      break;
  }
}


// ---------------------------------------------------------------- Öva: kortlekar på skärmen
// Samma API som elevchatten (?deviceId= väljer elev). Flerval och lucktext med alternativ trycker man på;
// vanliga kort vänds med "Visa svar" och man säger själv om man kunde. Tiden räknas som övning.

struct DeckInfo {
  long id;
  String name;
  String subject;
  int cards, due;
  long homeworkId;
};
static std::vector<DeckInfo> decks;
// "Ny övning": välj läxa/prov och gör ett snabbquiz eller en lucktext av materialet (som i appen)
struct MakeHw {
  long id;
  String label;      // "Prov: Glosförhör kap 3" (för titlar i övningarna)
  String title, subject, due;
  bool test = false, startNow = false;
};
// Senast: det som gjordes sist, för "Kör igen"
static String lastText, lastFocus;
static int lastKind = -1;
static long lastHw = 0;
static void setLast(int kind, const String& result);
static int daysUntil(const String& iso);
static String dateLabel(const String& iso);
static uint32_t subjectColor(const String& subject, const String& title);
static std::vector<MakeHw> makeList;
static int makeIdx = 0;
static const char* MK_LABELS[6] = {"Snabbquiz", "Lucktext", "Förklara", "Testprov", "Matcha par", "Rätt ordning"};
static std::vector<long> allDeckIds, allDeckHw;
// Fokus: delområden i vald läxa ("Siffror 1–100", "Veckodagar" …) – välj ett eller Allt
static std::vector<String> mkTopics;
static long mkTopicsFor = -1;
static int mkFocus = 0;   // 0 = Allt
static String currentFocus() { return mkFocus > 0 && mkFocus <= (int)mkTopics.size() ? mkTopics[mkFocus - 1] : String(); }   // alla kortlekar och deras läxa – för Matcha par
static String makeBusy;
static bool decksLoaded = false;
static String ovaMessage;

static DeckInfo cur;
static long cardId = 0;
static String qFront, qBack, qHint;
static std::vector<String> options;
static int correctIdx = -1, chosen = -1;
static bool revealed = false, noMoreCards = false, practiceAnyway = false;
static int answered = 0, rightCount = 0;
static std::vector<String> deckBacks;   // svaren på lekens kort – blir felaktiga alternativ
static String spokenAnswer;
static bool isLanguageDeck();
static String jsonEscape(const String& t);
static int voiceVerdict = -1;           // 1 låter rätt, 0 låter fel
static constexpr int ROUND = 10;

static String practiceId;
static uint32_t practiceActive = 0, lastTouchAt = 0, lastActiveTick = 0;

static int api(const char* method, const String& path, const String& body, JsonDocument* out, uint32_t timeoutMs = 10000) {
  if (WiFi.status() != WL_CONNECTED) return -1;
  WiFiClient client;
  HTTPClient http;
  String url = String("http://") + PLUGGIS_HOST + ":" + PLUGGIS_PORT + path +
               (path.indexOf('?') < 0 ? "?" : "&") + "deviceId=" + PLUGGIS_DEVICE_ID;
  if (!http.begin(client, url)) return -1;
  http.setTimeout(timeoutMs);
  http.addHeader("X-Pluggis-Token", PLUGGIS_TOKEN);
  http.addHeader("Content-Type", "application/json");
  int code = strcmp(method, "GET") == 0 ? http.GET() : http.sendRequest(method, body);
  if (code <= 0 || code >= 400) {
    String what = code <= 0 ? String("ingen kontakt med ") + PLUGGIS_HOST + ":" + String(PLUGGIS_PORT) + " (" + http.errorToString(code) + ")"
                : code == 401 || code == 403 ? String("nekad (") + String(code) + ") – kolla PLUGGIS_TOKEN i config.h"
                : String("svarade ") + String(code);
    Serial.printf("[pluggis] %s %s: %s\n", method, path.c_str(), what.c_str());
    lastError = String(method) + " " + path + ": " + what;
  }
  if (code > 0 && out) {
    String resp = http.getString();
    if (resp.length()) deserializeJson(*out, resp);
  }
  http.end();
  return code;
}

static void loadDecks() {
  decks.clear();   // kortlekar sparas inte längre – varje övning görs ny
  ovaMessage = "";
  long selected = makeIdx < (int)makeList.size() ? makeList[makeIdx].id : 0;
  makeList.clear();
  JsonDocument hw;
  if (api("GET", "/api/homework", "", &hw) == 200) {
    for (JsonObject x : hw.as<JsonArray>()) {
      String kind = x["kind"] | "";
      if (kind == "OTHER") continue;
      MakeHw m;
      m.id = x["id"] | 0L;
      m.title = clean(x["title"] | "");
      m.subject = clean(x["subject"] | "");
      m.due = x["dueDate"] | "";
      m.test = kind == "TEST";
      m.label = String(m.test ? "Prov: " : "Läxa: ") + m.title;
      makeList.push_back(m);
    }
  }
  // Datum först (utan datum sist)
  std::stable_sort(makeList.begin(), makeList.end(), [](const MakeHw& a, const MakeHw& b) {
    String da = a.due.length() ? a.due : String("9999"), db = b.due.length() ? b.due : String("9999");
    return da < db;
  });
  // "börja nu": längre fram (mer än en vecka) men har moment i planen de närmaste dagarna
  JsonDocument plan;
  if (api("GET", "/api/plan/upcoming?days=3", "", &plan) == 200) {
    for (JsonObject it : plan.as<JsonArray>()) {
      long hid = it["homeworkId"] | 0L;
      for (auto& m : makeList) if (m.id == hid && m.due.length() && daysUntil(m.due) > 7) m.startNow = true;
    }
  }
  makeIdx = 0;
  for (size_t i = 0; i < makeList.size(); i++) if (makeList[i].id == selected) makeIdx = i;
  allDeckIds.clear();
  allDeckHw.clear();
  for (auto& d : decks) { allDeckIds.push_back(d.id); allDeckHw.push_back(d.homeworkId); }
  if (decks.size() > 6) decks.resize(6);
  if (makeList.empty()) ovaMessage = "Inga läxor eller prov att öva på just nu. Lägg till en i Pluggis-appen.";
  decksLoaded = true;
}

static void practiceEnd();

static void practiceBegin() {
  if (practiceId.length()) practiceEnd();   // en övning åt gången
  JsonDocument doc;
  String name = cur.name;
  name.replace("\"", "'");
  name.replace("\\", "/");
  String body = String("{\"what\":\"") + (name.startsWith("Lyssnar") ? "" : "Kort: ") + name + "\"" + (cur.homeworkId ? ",\"homeworkId\":" + String(cur.homeworkId) : "") + "}";
  body.replace("\n", " ");
  practiceId = api("POST", "/api/practice/start", body, &doc) == 200 ? String((const char*)(doc["id"] | "")) : "";
  practiceActive = 0;
  lastTouchAt = lastActiveTick = millis();
}

static void practiceEnd() {
  if (!practiceId.length()) return;
  JsonDocument r;
  String id = practiceId;
  practiceId = "";
  if (api("POST", "/api/practice/" + id + "/end", "{\"activeSeconds\":" + String(practiceActive) + "}", &r) == 200) {
    int min = r["minutes"] | 0;
    Serial.printf("[pluggis] övningstid: %u s aktiv, %d min räknades\n", (unsigned)practiceActive, min);
    if (min > 0 && lastText.length() && !lastText.endsWith(" min")) { lastText += " · " + String(min) + " min"; dirtyOva = true; }
  }
  practiceActive = 0;
}

static void nextCard() {
  chosen = -1;
  revealed = false;
  spokenAnswer = "";
  voiceVerdict = -1;
  options.clear();
  correctIdx = -1;
  if (answered >= ROUND) {
    noMoreCards = true;
    SFX_DONE();
    setLast(cur.name.startsWith("Lucktext") || cur.name.indexOf("Lucktext") >= 0 ? 1 : 0, String(rightCount) + " av " + String(answered) + " rätt");
    return;
  }
  for (int attempt = 0; attempt < 2; attempt++) {
    JsonDocument doc;
    int code = api("GET", "/api/flashcards/decks/" + String(cur.id) + "/next?announce=false&practiceAnyway=" +
                   (practiceAnyway ? "true" : "false"), "", &doc);
    if (code == 200) {
      cardId = doc["id"] | 0L;
      qFront = clean(doc["front"] | "");
      qBack = clean(doc["back"] | "");
      qHint = clean(doc["hint"] | "");
      JsonArray wrong = doc["wrongOptions"].as<JsonArray>();
      if (!wrong.isNull() && wrong.size() > 0) {
        options.push_back(qBack);
        for (JsonVariant w : wrong) {
          if (options.size() >= 4) break;
          options.push_back(clean(w.as<const char*>()));
        }
        for (int i = options.size() - 1; i > 0; i--) std::swap(options[i], options[esp_random() % (i + 1)]);
        for (int i = 0; i < (int)options.size(); i++) if (options[i] == qBack) correctIdx = i;
      }
      // Inga egna felaktiga svar: ta tre svar från andra kort i leken (om svaren är korta)
      if (options.empty() && qBack.length() && qBack.length() <= (isLanguageDeck() ? 120 : 70)) {
        std::vector<String> pool;
        for (auto& b : deckBacks)
          if (b != qBack && b.length() <= 90 && std::find(pool.begin(), pool.end(), b) == pool.end()) pool.push_back(b);
        if (pool.size() >= 2) {
          for (int i = pool.size() - 1; i > 0; i--) std::swap(pool[i], pool[esp_random() % (i + 1)]);
          options.push_back(qBack);
          for (size_t i = 0; i < pool.size() && options.size() < 4; i++) options.push_back(pool[i]);
          for (int i = options.size() - 1; i > 0; i--) std::swap(options[i], options[esp_random() % (i + 1)]);
          for (int i = 0; i < (int)options.size(); i++) if (options[i] == qBack) correctIdx = i;
        }
      }
      noMoreCards = false;
      sayCardSide(qFront);
      return;
    }
    if (code == 404 && !practiceAnyway) { practiceAnyway = true; continue; }
    break;
  }
  noMoreCards = true;
}

static void grade(bool correct) {
  answered++;
  if (correct) rightCount++;
  api("POST", "/api/flashcards/cards/" + String(cardId) + "/grade?quiet=true",
      correct ? "{\"correct\":true}" : "{\"correct\":false}", nullptr);
}

static void openDeck(const DeckInfo& d) {
  cur = d;
  answered = rightCount = 0;
  practiceAnyway = d.due == 0;
  screen = Screen::OvaCard;
  dirtyAll = true;
  redraw();   // direkt, innan korten hämtas
  practiceBegin();
  deckBacks.clear();
  JsonDocument all;
  if (api("GET", "/api/flashcards/decks/" + String(d.id) + "/cards", "", &all) == 200)
    for (JsonObject c : all.as<JsonArray>()) { String b = clean(c["back"] | ""); if (b.length()) deckBacks.push_back(b); }
  // Språket i leken: räkna vad korten (som inte är svenska) ser ut att vara
  {
    int votes[4] = {0, 0, 0, 0};
    const char* names[4] = {"es", "de", "fr", "en"};
    for (auto& b : deckBacks) {
      if (looksSwedish(b)) continue;
      const char* g = guessLang(b);
      for (int i = 0; i < 4; i++) if (!strcmp(g, names[i])) votes[i]++;
    }
    int bi = 0;
    for (int i = 1; i < 4; i++) if (votes[i] > votes[bi]) bi = i;
    deckGuessed = votes[bi] >= 2 ? names[bi] : "";
  }
  nextCard();
  dirtyAll = true;
}

static void loadHomework();
static void loadPlan();
static void listenStop(bool save);

static void showScreen(Screen s, bool force) {
  if (s == screen && !force) return;
  if (screen == Screen::OvaCard || screen == Screen::Game) practiceEnd();
  if (screen == Screen::Game) game = Game::None;
  if (screen == Screen::HwDetail) listenStop(true);
  if (recording) stopRecording(false);
  if (s != Screen::Coach) stopPlayback();
  sayStop();
  screen = s;
  if (s == Screen::OvaList || s == Screen::HwList || s == Screen::Plan) {
    decksLoaded = false;
    dirtyAll = true;
    redraw();   // "Hämtar …"
    if (s == Screen::OvaList) loadDecks();
    else if (s == Screen::HwList) loadHomework();
    else loadPlan();
  }
  dirtyAll = true;
}


/** Språk (glosor): rösten (svensk KB-Whisper) hör inte spanska ord bra – där trycker man i stället. */
static bool isLanguageDeck() {
  String t = cur.subject + " " + cur.name;
  t.toLowerCase();
  const char* words[] = {"spansk", "engelsk", "fransk", "tysk", "språk", "sprak", "glos", "italiensk", "kinesisk", "english", "español", "espanol"};
  for (auto w : words) if (t.indexOf(w) >= 0) return true;
  return false;
}

/** Språket i en språk-kortlek: "es", "de", "fr", "en" – annars "". */
static const char* langFrom(String t) {
  t.toLowerCase();
  if (t.indexOf("spansk") >= 0 || t.indexOf("españ") >= 0 || t.indexOf("espan") >= 0) return "es";
  if (t.indexOf("tysk") >= 0 || t.indexOf("deutsch") >= 0) return "de";
  if (t.indexOf("fransk") >= 0) return "fr";
  if (t.indexOf("engelsk") >= 0 || t.indexOf("english") >= 0) return "en";
  return "";
}
/** Gissar språket på en icke-svensk text: tecken (ñ ¿ ß ç …) och vanliga småord. */
static const char* guessLang(const String& in) {
  String t = " " + in + " ";
  t.toLowerCase();
  for (const char* p : {".", ",", "!", "?", ";", ":", "\"", "(", ")"}) t.replace(p, " ");
  auto words = [&](std::initializer_list<const char*> ws) {
    int n = 0;
    for (auto w : ws) { String k = String(" ") + w + " "; int i = 0; while ((i = t.indexOf(k, i)) >= 0) { n++; i += k.length() - 1; } }
    return n;
  };
  auto chars = [&](std::initializer_list<const char*> cs) { int n = 0; for (auto c : cs) if (t.indexOf(c) >= 0) n += 3; return n; };
  int es = chars({"ñ", "¿", "¡"}) + words({"el", "la", "los", "las", "un", "una", "que", "y", "es", "por", "para", "con", "muy", "soy", "estoy", "tengo", "hola", "gracias", "de", "del", "mi", "tu", "su"});
  int de = chars({"ß"}) + words({"der", "die", "das", "und", "ist", "ich", "nicht", "mit", "ein", "eine", "zu", "sie", "wir", "auf", "für", "bin", "habe"});
  int fr = chars({"ç", "œ", "è", "ê", "à", "ù"}) + words({"le", "les", "et", "est", "je", "vous", "une", "pour", "avec", "pas", "ne", "bonjour", "merci", "des", "du", "au", "nous", "suis"});
  int en = words({"the", "and", "is", "are", "you", "of", "to", "with", "this", "that", "it", "have", "has", "was", "what", "my", "your"});
  int best = std::max(std::max(es, de), std::max(fr, en));
  if (best == 0) return "";
  return best == es ? "es" : best == de ? "de" : best == fr ? "fr" : "en";
}

// Kortlekens språk: ämnet/namnet, annars det som flest kort i leken ser ut att vara (räknas när leken öppnas)
static const char* deckLang() {
  const char* l = langFrom(cur.subject + " " + cur.name);
  return *l ? l : deckGuessed;
}

/** Ser texten svensk ut? (å/ä/ö eller vanliga svenska småord) */
static bool looksSwedish(const String& in) {
  String t = " " + in + " ";
  t.toLowerCase();
  if (t.indexOf("å") >= 0 || t.indexOf("ä") >= 0 || t.indexOf("ö") >= 0 || t.indexOf("Å") >= 0 || t.indexOf("Ä") >= 0 || t.indexOf("Ö") >= 0) return true;
  const char* words[] = {" och ", " att ", " jag ", " är ", " en ", " ett ", " det ", " med ", " för ", " inte ", " som ", " har ", " vi ", " du ", " han ", " hon ", " min ", " vad "};
  for (auto w : words) if (t.indexOf(w) >= 0) return true;
  return false;
}

/** Läs upp en text från ett kort: utländsk röst för den utländska sidan i språk-kortlekar. */
static void sayCardSide(const String& text) {
  // Språk-kortlek: alltid språkets röst, även de svenska delarna
  const char* lang = deckLang();
  if (*lang) { sayAloud(text, lang); return; }
  sayAloud(text, looksSwedish(text) ? "" : guessLang(text));
}

/** Gör om till små bokstäver utan skiljetecken, för att jämföra ett talat svar med kortets. */
static String normalize(const String& in) {
  String s = in;
  s.toLowerCase();
  s.replace("Å", "å"); s.replace("Ä", "ä"); s.replace("Ö", "ö");
  String out;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';' || c == '"' || c == '\'' || c == '(' || c == ')' || c == '-') c = ' ';
    if (c == ' ' && (out.endsWith(" ") || !out.length())) continue;
    out += c;
  }
  out.trim();
  return out;
}

static int judge(const String& said, const String& answer) {
  String a = normalize(said), b = normalize(answer);
  if (!a.length() || !b.length()) return -1;
  if (a.indexOf(b) >= 0 || (a.length() >= 3 && b.indexOf(a) >= 0)) return 1;
  // andel av svarets ord (längre än 2 tecken) som sades
  int words = 0, hits = 0, start = 0;
  while (start <= (int)b.length()) {
    int sp = b.indexOf(' ', start);
    String w = b.substring(start, sp < 0 ? b.length() : sp);
    if (w.length() > 2) { words++; if (a.indexOf(w) >= 0) hits++; }
    if (sp < 0) break;
    start = sp + 1;
  }
  return words && hits * 10 >= words * 6 ? 1 : 0;
}

/** Inspelningen på ett kort: skicka till servern (KB-Whisper), visa vad som hördes och rätt svar. */
static void gameVoice(const String& said);
static void gameBusy(const String& what);

/** Skickar inspelningen till servern (lokal KB-Whisper) och ger tillbaka texten. */
static String transcribeRecording() {
  uint32_t bytes = recSamples * 2;
  writeWavHeader((uint8_t*)recBuf, bytes);
  String said;
  if (WiFi.status() == WL_CONNECTED) {
    WiFiClient client;
    HTTPClient http;
    String url = String("http://") + PLUGGIS_HOST + ":" + PLUGGIS_PORT + "/api/speech/transcribe?deviceId=" + PLUGGIS_DEVICE_ID;
    if (http.begin(client, url)) {
      http.setTimeout(20000);
      http.addHeader("X-Pluggis-Token", PLUGGIS_TOKEN);
      http.addHeader("Content-Type", "audio/wav");
      int code = http.POST((uint8_t*)recBuf, 44 + bytes);
      if (code == 200) {
        JsonDocument doc;
        if (!deserializeJson(doc, http.getString())) said = clean(doc["text"] | "");
      }
      http.end();
    }
  }
  return said;
}

static void answerByVoice(bool send) {
  uint32_t ms = millis() - recStarted;
  bool ok = send && ms >= 400 && recSamples >= REC_RATE / 4;
  if (screen == Screen::Game) {
    if (!ok) { dirtyOva = true; return; }
    gameBusy("Lyssnar på vad du sa ...");
    gameVoice(transcribeRecording());
    return;
  }
  if (!ok || screen != Screen::OvaCard) { dirtyOva = true; return; }
  spokenAnswer = "...";
  dirtyOva = true;
  redraw();
  String said = transcribeRecording();
  spokenAnswer = said.length() ? said : "(hörde inget – visa svaret och bedöm själv)";
  voiceVerdict = said.length() ? judge(said, qBack) : -1;
  revealed = true;
  if (voiceVerdict == 1) SFX_OK();
  dirtyOva = true;
}


/** Pluggis (Claude) gör frågorna av läxans material – tar en stund – och övningen startar direkt. */
static void makeQuiz(long homeworkId, const char* mode) {
  makeBusy = strcmp(mode, "cloze") == 0 ? "Pluggis gör en lucktext av materialet ...\n\nDet kan ta en halv minut."
                                       : "Pluggis gör ett snabbquiz av materialet ...\n\nDet kan ta en halv minut.";
  dirtyOva = true;
  redraw();
  JsonDocument doc;
  String focus = mkTopicsFor == homeworkId ? currentFocus() : String();
  int code = api("POST", "/api/flashcards/quiz", "{\"homeworkId\":" + String(homeworkId) + ",\"mode\":\"" + mode +
                 "\",\"count\":10" + (focus.length() ? ",\"focus\":\"" + jsonEscape(focus) + "\"" : String()) + "}", &doc, 120000);
  makeBusy = "";
  if (code != 200) {
    ovaMessage = String("Det gick inte att göra frågor just nu (") + code + "). Tryck för att försöka igen.";
    dirtyOva = true;
    return;
  }
  JsonObject deck = doc["deck"].is<JsonObject>() ? doc["deck"].as<JsonObject>() : doc.as<JsonObject>();
  DeckInfo d;
  d.id = deck["id"] | 0L;
  d.name = clean(deck["name"] | "Snabbquiz");
  d.subject = clean(deck["subject"] | "");
  d.cards = 10;
  d.due = 10;
  d.homeworkId = homeworkId;
  d.name = String(strcmp(mode, "cloze") == 0 ? "Lucktext: " : "Snabbquiz: ") + d.name;
  lastHw = homeworkId;
  lastFocus = focus;
  if (!d.id) { loadDecks(); dirtyOva = true; return; }
  openDeck(d);
}


// ---------------------------------------------------------------- Förklara, Testprov, Matcha par, Rätt ordning
// Samma övningar som i appen. Förklara och skrivfrågor i Testprovet besvaras med rösten (KB-Whisper).
static long gameHw = 0;
static String gameTitle, gameMsg, gameHeard;
static bool gameDone = false;
static uint32_t gWrongUntil = 0;
static int gWrongA = -1, gWrongB = -1;

// Förklara
static String exId, exConcept, exReply;
static std::vector<String> exCovered, exMissing;
// Testprov
struct ExamQ { int n; String type, question; std::vector<String> choices; };
static String examId;
static std::vector<ExamQ> examQs;
static std::vector<String> examAns;
static int examI = 0, examPoints = 0, examMax = 0, examPct = 0;
static std::vector<String> examReview;
// Matcha par
struct Pair { String a, b; };
static std::vector<Pair> pairs;
static std::vector<int> rightOrder;
static std::vector<bool> matched;
static int selL = -1;
static uint32_t matchStart = 0;
// Rätt ordning
struct Seq { String title, prompt, why; std::vector<String> steps; };
static std::vector<Seq> seqs;
static int seqI = 0, placed = 0;
static std::vector<int> shownSteps;

static void gameBusy(const String& what) {
  gameMsg = what;
  dirtyOva = true;
  redraw();
}


static void gameDoneReport(const char* kind) {
  api("POST", "/api/games/done", String("{\"kind\":\"") + kind + "\",\"what\":\"" + jsonEscape(gameTitle) + "\"}", nullptr);
}

static void explainApply(JsonDocument& doc) {
  exId = (const char*)(doc["id"] | exId.c_str());
  if (doc["concept"].is<const char*>()) exConcept = clean(doc["concept"]);
  exReply = clean(doc["reply"] | "");
  gameDone = doc["done"] | false;
  exCovered.clear();
  exMissing.clear();
  for (JsonVariant v : doc["covered"].as<JsonArray>()) exCovered.push_back(clean(v.as<const char*>()));
  for (JsonVariant v : doc["missing"].as<JsonArray>()) exMissing.push_back(clean(v.as<const char*>()));
}

static void startGame(int which, long hw, const String& title) {
  game = which == 2 ? Game::Explain : which == 3 ? Game::Exam : which == 4 ? Game::Match : Game::Order;
  gameHw = hw;
  gameTitle = title;
  gameDone = false;
  gameHeard = "";
  screen = Screen::Game;
  dirtyAll = true;
  String focus = mkTopicsFor == hw ? currentFocus() : String();
  String focusJson = focus.length() ? ",\"focus\":\"" + jsonEscape(focus) + "\"" : String();
  lastHw = hw;
  lastFocus = focus;
  if (focus.length()) gameTitle = focus + " · " + title;
  cur.name = String(MK_LABELS[which]) + ": " + (focus.length() ? focus + " · " : "") + title;
  cur.homeworkId = hw;
  JsonDocument doc;
  if (game == Game::Explain) {
    gameBusy("Pluggis väljer ett begrepp ur materialet ...");
    int code = api("POST", "/api/explain/start", "{\"homeworkId\":" + String(hw) + focusJson + "}", &doc, 120000);
    gameMsg = code == 200 ? "" : "Det gick inte att starta just nu (" + String(code) + ").";
    if (code == 200) explainApply(doc);
  } else if (game == Game::Exam) {
    gameBusy("Pluggis gör ett testprov av materialet ...\n\nDet kan ta en minut.");
    int code = api("POST", "/api/exam/start", "{\"homeworkId\":" + String(hw) + ",\"count\":10" + focusJson + "}", &doc, 180000);
    gameMsg = code == 200 ? "" : "Det gick inte att göra ett testprov just nu (" + String(code) + ").";
    examQs.clear();
    examAns.clear();
    examI = 0;
    examId = (const char*)(doc["id"] | "");
    for (JsonObject q : doc["questions"].as<JsonArray>()) {
      ExamQ e{q["n"] | 0, q["type"] | "choice", clean(q["question"] | ""), {}};
      for (JsonVariant c : q["choices"].as<JsonArray>()) e.choices.push_back(clean(c.as<const char*>()));
      examQs.push_back(e);
      examAns.push_back("");
    }
    if (code == 200 && examQs.empty()) gameMsg = "Testprovet blev tomt – prova igen.";
  } else if (game == Game::Match) {
    gameBusy("Pluggis gör par av materialet ...");
    pairs.clear();
    JsonDocument qd;
    String body = "{\"homeworkId\":" + String(hw) + ",\"mode\":\"quiz\",\"count\":10" + focusJson + "}";
    if (api("POST", "/api/flashcards/quiz", body, &qd, 120000) == 200) {
      long deckId = qd["deck"]["id"] | 0L;
      JsonDocument cards;
      if (deckId && api("GET", "/api/flashcards/decks/" + String(deckId) + "/cards", "", &cards) == 200) {
        for (JsonObject c : cards.as<JsonArray>()) {
          String a2 = clean(c["front"] | ""), b2 = clean(c["back"] | "");
          a2.replace("___", "...");
          if (a2.length() && b2.length() && a2.length() <= 80 && b2.length() <= 60) pairs.push_back({a2, b2});
        }
      }
    }
    for (int i = pairs.size() - 1; i > 0; i--) std::swap(pairs[i], pairs[esp_random() % (i + 1)]);
    // Fokus: kort som nämner delområdets ord först
    if (focus.length()) {
      std::vector<String> fw;
      String f = focus;
      f.toLowerCase();
      int st = 0;
      while (st <= (int)f.length()) {
        int sp = f.indexOf(' ', st);
        String w = f.substring(st, sp < 0 ? f.length() : sp);
        if (w.length() > 3) fw.push_back(w);
        if (sp < 0) break;
        st = sp + 1;
      }
      std::stable_partition(pairs.begin(), pairs.end(), [&](const Pair& p) {
        String t = p.a + " " + p.b;
        t.toLowerCase();
        for (auto& w : fw) if (t.indexOf(w) >= 0) return true;
        return false;
      });
    }
    // Variation: kort som var med de senaste gångerna hamnar sist
    static std::vector<String> recentMatch;
    std::stable_partition(pairs.begin(), pairs.end(), [](const Pair& p) {
      return std::find(recentMatch.begin(), recentMatch.end(), p.a) == recentMatch.end();
    });
    if (pairs.size() > 5) pairs.resize(5);
    for (auto& p : pairs) recentMatch.insert(recentMatch.begin(), p.a);
    if (recentMatch.size() > 15) recentMatch.resize(15);
    gameMsg = pairs.size() < 3 ? "Pluggis hittade inte tillräckligt med par i materialet. Prova Snabbquiz." : "";
    rightOrder.clear();
    for (size_t i = 0; i < pairs.size(); i++) rightOrder.push_back(i);
    for (int i = rightOrder.size() - 1; i > 0; i--) std::swap(rightOrder[i], rightOrder[esp_random() % (i + 1)]);
    matched.assign(pairs.size(), false);
    selL = -1;
    matchStart = millis();
  } else {
    gameBusy("Pluggis gör ordningar ur materialet ...");
    int code = api("POST", "/api/games/order", "{\"homeworkId\":" + String(hw) + focusJson + "}", &doc, 120000);
    seqs.clear();
    for (JsonObject q : doc["sequences"].as<JsonArray>()) {
      Seq sq{clean(q["title"] | ""), clean(q["prompt"] | ""), clean(q["why"] | ""), {}};
      for (JsonVariant st : q["steps"].as<JsonArray>()) sq.steps.push_back(clean(st.as<const char*>()));
      if (sq.steps.size() >= 2) seqs.push_back(sq);
    }
    gameMsg = code != 200 ? "Det gick inte just nu (" + String(code) + ")." : seqs.empty() ? "Hittade inget att ordna i materialet." : "";
    seqI = 0;
  }
  if (game == Game::Order && !seqs.empty()) {
    placed = 0;
    shownSteps.clear();
    for (size_t i = 0; i < seqs[0].steps.size(); i++) shownSteps.push_back(i);
    for (int i = shownSteps.size() - 1; i > 0; i--) std::swap(shownSteps[i], shownSteps[esp_random() % (i + 1)]);
  }
  if (!gameMsg.length()) practiceBegin();
  examSpoken = -1;
  if (!gameMsg.length()) {
    if (game == Game::Explain) sayAloud(exReply);
    else if (game == Game::Order) sayAloud(seqs[0].title + ". " + seqs[0].prompt);
    else if (game == Game::Exam) examSpeak();
  }
  dirtyAll = true;
}

static void gameVoice(const String& said) {
  gameMsg = "";
  gameHeard = said;
  if (!said.length()) { gameHeard = "(hörde inget – försök igen)"; dirtyOva = true; return; }
  if (game == Game::Explain) {
    gameBusy("Pluggis tänker ...");
    JsonDocument doc;
    int code = api("POST", "/api/explain/" + exId + "/answer", "{\"text\":\"" + jsonEscape(said) + "\"}", &doc, 60000);
    gameMsg = "";
    if (code == 200) explainApply(doc);
    else exReply = "Det gick inte att skicka just nu. Försök igen.";
    if (gameDone) { SFX_DONE(); gameDoneReport("explain"); setLast(2, "klart"); }
    sayAloud(exReply);
  } else if (game == Game::Exam && examI < (int)examQs.size()) {
    examAns[examI] = said;
  }
  dirtyOva = true;
}

static void examSpeak() {
  if (game != Game::Exam || gameDone || examI >= (int)examQs.size() || examSpoken == examI) return;
  examSpoken = examI;
  auto& q = examQs[examI];
  String t = q.question;
  const char* l = langFrom(gameTitle);
  if (*l) { sayAloud(t, l); return; }   // språkprov: allt med språkets röst
  if (q.choices.size() >= 2) {
    const char* letters[] = {"A", "B", "C", "D"};
    for (size_t i = 0; i < q.choices.size() && i < 4; i++) t += String(". ") + letters[i] + ": " + q.choices[i];
  }
  sayAloud(t);
}

static void examSubmit() {
  gameBusy("Pluggis rättar provet ...");
  JsonDocument body;
  JsonObject a = body["answers"].to<JsonObject>();
  for (size_t i = 0; i < examQs.size(); i++) a[String(examQs[i].n)] = examAns[i];
  String json;
  serializeJson(body, json);
  JsonDocument doc;
  int code = api("POST", "/api/exam/" + examId + "/submit", json, &doc, 120000);
  gameMsg = "";
  gameDone = true;
  if (code != 200) { gameMsg = "Det gick inte att rätta just nu (" + String(code) + ")."; return; }
  SFX_DONE();
  examPoints = doc["points"] | 0;
  setLast(3, String((int)(doc["percent"] | 0)) + " %");
  examMax = doc["maxPoints"] | 0;
  examPct = doc["percent"] | 0;
  examReview.clear();
  for (JsonVariant v : doc["review"].as<JsonArray>()) examReview.push_back(clean(v.as<const char*>()));
}

static void gameExit() {
  if (recording) stopRecording(false);
  practiceEnd();
  game = Game::None;
  screen = Screen::Coach;
  showScreen(Screen::OvaList);
}

// ---------------------------------------------------------------- Läxor och plan
static String dateLabel(const String& iso);   // "Idag", "Imorgon", "Fredag 28/11"

struct HwInfo {
  long id;
  String title, subject, kind, due, about;
  long listenMat;
  int listenSent;
};
static std::vector<HwInfo> hws;
static HwInfo hwCur;
static std::vector<HwInfo> planHws;   // läxorna bakom planens moment (för att kunna läsa upp dem)
static Screen hwBackTo = Screen::HwList;   // vart Tillbaka går från en läxa (Läxor eller Plan)

// Uppläsning av läxans material, mening för mening (som en ljudbok), sparar var man är på läxan
struct Sent {
  long matId;
  int idx;   // mening inom materialet
  String text;
};
static std::vector<Sent> sents;
static std::vector<String> chapterLabel;
static std::vector<long> chapterIds;
static std::vector<int> chapterPage;   // sidnumret för varje del (0 = okänt)
static int listenEnd = -1;             // läs bara upp hit (meningens index, ej med); -1 = till slutet
static long readingPlanItem = 0;       // planens moment som läses upp just nu (bockas av när sidorna är klara)

/** Övningen ett moment i planen gäller ("Gör ett snabbquiz" → "quiz"), eller tom. Samma namn som open_practice. */
static String exerciseFor(const String& task) {
  String t = task;
  t.toLowerCase();
  if (t.indexOf("testprov") >= 0) return "exam";
  if (t.indexOf("snabbquiz") >= 0 || t.indexOf("quiz") >= 0) return "quiz";
  if (t.indexOf("lucktext") >= 0) return "cloze";
  if (t.indexOf("förklara för pluggis") >= 0 || t.indexOf("förklara i pluggis") >= 0) return "explain";
  if (t.indexOf("matcha par") >= 0 || t.indexOf("matcha") >= 0) return "match";
  if (t.indexOf("rätt ordning") >= 0) return "order";
  return "";
}
static int listenPos = 0, fetchPos = 0, nowPlaying = -1, sinceSave = 0;
// Nästa mening hämtas i en egen tråd, så att skärmen svarar på tryck medan den laddas
static SemaphoreHandle_t fetchLock = nullptr;
static volatile int fetchGen = 0;          // ökas vid paus/hopp – gamla hämtningar kastas
static int fetchWantIdx = -1, fetchWantGen = -1;
static String fetchWantText, fetchWantLang;
static void fetchReset();
static uint8_t* fetchedBuf = nullptr;
static size_t fetchedLen = 0;
static int fetchedIdx = -1, fetchedGen = -1, fetchedCode = 0;
static bool listening = false, listenDone = false;

// Plan: en dag i taget
struct PlanRow {
  long id, hwId;
  String date, task, subject, type;
  int minutes;
  bool done;
  int pageFrom, pageTo;   // "Läs s. 40–45" → 40, 45 (0 = inga sidor i texten)
};

/** Sidorna i ett läsmoment: "s. 40–45", "sid 40-45", "sidan 12", "sidorna 3 och 4". */
static void parsePages(const String& task, int& from, int& to) {
  from = to = 0;
  String t = task;
  t.toLowerCase();
  int at = -1;
  for (const char* key : {"sidorna", "sidan", "sid.", "sid ", "s. ", "s."}) {
    at = t.indexOf(key);
    if (at >= 0) { at += strlen(key); break; }
  }
  if (at < 0) return;
  auto num = [&](int& i) {
    while (i < (int)t.length() && (t[i] == ' ' || t[i] == '\xA0')) i++;
    int v = 0, start = i;
    while (i < (int)t.length() && t[i] >= '0' && t[i] <= '9') v = v * 10 + (t[i++] - '0');
    return i > start ? v : 0;
  };
  int i = at;
  from = num(i);
  if (!from) return;
  to = from;
  // "–" (en dash, 3 byte i UTF-8), "-", "till" eller "och" före sista sidan
  String rest = t.substring(i, i + 8);
  int skip = rest.startsWith("-") ? 1 : rest.startsWith("\xE2\x80\x93") ? 3 : rest.startsWith(" - ") ? 3
           : rest.startsWith(" till ") ? 6 : rest.startsWith(" och ") ? 5 : 0;
  if (skip) {
    int j = i + skip;
    int v = num(j);
    if (v >= from) to = v;
  }
}

static std::vector<PlanRow> planRows;
static std::vector<String> planDays;
static int planDay = 0;
static std::vector<int> planShown;

static int apiBinary(const String& path, const String& body, uint8_t** out, size_t* outLen) {
  *out = nullptr;
  *outLen = 0;
  if (WiFi.status() != WL_CONNECTED) return -1;
  WiFiClient client;
  HTTPClient http;
  String url = String("http://") + PLUGGIS_HOST + ":" + PLUGGIS_PORT + path +
               (path.indexOf('?') < 0 ? "?" : "&") + "deviceId=" + PLUGGIS_DEVICE_ID;
  if (!http.begin(client, url)) return -1;
  http.setTimeout(15000);
  http.addHeader("X-Pluggis-Token", PLUGGIS_TOKEN);
  http.addHeader("Content-Type", "application/json");
  int code = http.POST(body);
  if (code == 200) {
    int len = http.getSize();
    if (len > 0 && len < 4 * 1024 * 1024) {
      uint8_t* buf = (uint8_t*)heap_caps_malloc(len, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
      WiFiClient* st = http.getStreamPtr();
      size_t got = 0;
      uint32_t t0 = millis();
      while (buf && got < (size_t)len && millis() - t0 < 15000) {
        size_t avail = st->available();
        if (avail) got += st->readBytes(buf + got, std::min(avail, (size_t)len - got));
        else delay(2);
      }
      if (buf && got == (size_t)len) { *out = buf; *outLen = len; }
      else if (buf) free(buf);
    }
  }
  http.end();
  return code;
}

static String jsonEscape(const String& t) {
  String o;
  for (size_t i = 0; i < t.length(); i++) {
    char c = t[i];
    if (c == '"' || c == '\\') { o += '\\'; o += c; }
    else if (c == '\n' || c == '\r' || c == '\t') o += ' ';
    else o += c;
  }
  return o;
}

static HwInfo hwFromJson(JsonObject x) {
  HwInfo h;
  h.id = x["id"] | 0L;
  h.title = clean(x["title"] | "");
  h.subject = clean(x["subject"] | "");
  h.kind = x["kind"] | "";
  h.due = x["dueDate"] | "";
  h.about = clean(x["content"].is<const char*>() ? x["content"].as<const char*>() : (x["description"] | ""));
  h.listenMat = x["listenMaterialId"] | 0L;
  h.listenSent = x["listenSentence"] | 0;
  return h;
}

static void loadHomework() {
  hws.clear();
  ovaMessage = "";
  JsonDocument doc;
  int code = api("GET", "/api/homework", "", &doc);
  if (code != 200) { ovaMessage = "Kunde inte hämta läxorna (" + String(code) + ")."; decksLoaded = true; return; }
  for (JsonObject x : doc.as<JsonArray>()) {
    HwInfo h = hwFromJson(x);
    if (h.id) hws.push_back(h);
  }
  if (hws.empty()) ovaMessage = "Inga läxor eller prov just nu.";
  decksLoaded = true;
}

/** Delar texten i meningar som läses upp en i taget (korta slås ihop, högst ~280 tecken). */
static void splitSentences(const String& text, long matId) {
  String cur;
  int idx = 0;
  auto push = [&](String t) {
    t.trim();
    if (!t.length()) return;
    if (cur.length() && cur.length() + t.length() < 280 && cur.length() < 120) cur += " " + t;
    else { if (cur.length()) sents.push_back({matId, idx++, cur}); cur = t; }
  };
  String part;
  for (size_t i = 0; i < text.length(); i++) {
    char c = text[i];
    part += c == '\n' ? ' ' : c;
    bool end = (c == '.' || c == '!' || c == '?') && (i + 1 >= text.length() || text[i + 1] == ' ' || text[i + 1] == '\n');
    if (end || c == '\n' && part.length() > 60 || part.length() > 260) { push(part); part = ""; }
  }
  push(part);
  if (cur.length()) sents.push_back({matId, idx++, cur});
}

static void openHomework(const HwInfo& h) {
  hwCur = h;
  screen = Screen::HwDetail;
  sents.clear();
  chapterLabel.clear();
  chapterIds.clear();
  chapterPage.clear();
  listenEnd = -1;
  readingPlanItem = 0;
  listening = listenDone = false;
  listenPos = fetchPos = 0;
  nowPlaying = -1;
  dirtyAll = true;
  redraw();
  JsonDocument doc;
  if (api("GET", "/api/homework/" + String(h.id) + "/material", "", &doc) == 200) {
    std::vector<JsonObject> mats;
    for (JsonObject m : doc.as<JsonArray>()) if (m["text"].is<const char*>() && strlen(m["text"].as<const char*>()) > 2) mats.push_back(m);
    std::sort(mats.begin(), mats.end(), [](JsonObject a, JsonObject b) {
      int pa = a["page"] | 100000, pb = b["page"] | 100000;
      return pa != pb ? pa < pb : (a["id"] | 0L) < (b["id"] | 0L);
    });
    for (auto& m : mats) {
      long id = m["id"] | 0L;
      int page = m["page"] | 0;
      chapterIds.push_back(id);
      chapterPage.push_back(page);
      chapterLabel.push_back(page ? "Sida " + String(page) : "Del " + String(chapterIds.size()));
      splitSentences(clean(m["text"].as<const char*>()), id);
    }
  }
  // Fortsätt där eleven slutade
  for (size_t i = 0; i < sents.size(); i++)
    if (sents[i].matId == hwCur.listenMat && sents[i].idx == hwCur.listenSent) { listenPos = i; break; }
  fetchPos = listenPos;
  dirtyAll = true;
}

static int chapterOf(int pos) {
  if (pos < 0 || pos >= (int)sents.size()) return chapterIds.size() - 1;
  for (size_t c = 0; c < chapterIds.size(); c++) if (chapterIds[c] == sents[pos].matId) return c;
  return 0;
}

static void listenSave() {
  sinceSave = 0;
  String body;
  if (listenDone || listenPos >= (int)sents.size()) body = "{\"materialId\":null,\"sentence\":0}";
  else body = "{\"materialId\":" + String(sents[listenPos].matId) + ",\"sentence\":" + String(sents[listenPos].idx) + "}";
  api("PUT", "/api/homework/" + String(hwCur.id) + "/listen", body, nullptr);
}

static void listenStop(bool save) {
  if (!listening) return;
  listening = false;
  fetchReset();
  stopPlayback();
  fetchPos = listenPos;
  nowPlaying = -1;
  if (save) listenSave();
  practiceEnd();
}

static void listenStart() {
  if (sents.empty()) return;
  if (listenDone) { listenDone = false; listenPos = 0; }
  fetchReset();
  stopPlayback();
  fetchPos = listenPos;
  listening = true;
  cur.name = "Lyssnar: " + hwCur.title;
  cur.homeworkId = hwCur.id;
  practiceBegin();
}

/** Anropas i loop(): hämta nästa mening medan den förra spelas, och håll reda på var vi är. */
/** Läsmomentet i planen är uppläst: bocka av det (ger poäng som vanligt). */
static void readingDone() {
  if (!readingPlanItem) return;
  if (api("POST", "/api/plan/items/" + String(readingPlanItem) + "/done", "", nullptr) == 200) {
    for (auto& r : planRows) if (r.id == readingPlanItem) r.done = true;
    SFX_DONE();
    ovaMessage = "Klart! Det är uppläst och bockat i planen.";
  }
  readingPlanItem = 0;
}

static void listenStart();

/** Ett läsmoment i planen: öppna läxans material och läs upp just de sidorna. */
static void openReading(const PlanRow& r) {
  HwInfo h;
  bool found = false;
  for (auto& x : planHws) if (x.id == r.hwId) { h = x; found = true; }
  if (!found) return;
  openHomework(h);
  hwBackTo = Screen::Plan;
  if (sents.empty()) return;   // inget inläst material: läxans sida säger det
  auto firstSentence = [](size_t ch) {
    for (size_t i = 0; i < sents.size(); i++) if (sents[i].matId == chapterIds[ch]) return (int)i;
    return 0;
  };
  bool pages = r.pageFrom && std::any_of(chapterPage.begin(), chapterPage.end(), [](int p) { return p > 0; });
  if (pages) {
    // Börja på sidan (eller närmaste sida före), sluta före första sidan efter momentets sista
    int start = -1;
    for (size_t c = 0; c < chapterPage.size(); c++) if (chapterPage[c] && chapterPage[c] <= r.pageFrom) start = c;
    if (start < 0) for (size_t c = 0; c < chapterPage.size(); c++) if (chapterPage[c] >= r.pageFrom) { start = c; break; }
    listenPos = firstSentence(start < 0 ? 0 : start);
    listenEnd = -1;
    for (size_t c = 0; c < chapterPage.size(); c++)
      if (chapterPage[c] > r.pageTo) { listenEnd = firstSentence(c); break; }
    if (listenEnd >= 0 && listenEnd <= listenPos) listenEnd = -1;
  }
  listenDone = false;
  fetchPos = listenPos;
  readingPlanItem = r.id;
  listenStart();
  dirtyOva = true;
}

static void pumpListening() {
  if (!listening) return;
  lastTouchAt = millis();   // lyssnande räknas som aktiv övningstid
  int playingTag = playing.data ? playing.tag : -1;
  if (playingTag >= 0 && playingTag != nowPlaying) {
    nowPlaying = playingTag;
    listenPos = playingTag;
    if (++sinceSave >= 4) listenSave();
    dirtyOva = true;
  }
  int end = listenEnd >= 0 && listenEnd < (int)sents.size() ? listenEnd : sents.size();
  if (!playing.data && playQueue.empty() && fetchPos >= end && fetchedIdx < 0) {
    listening = false;
    if (end < (int)sents.size()) {
      // Sidorna i planens moment är upplästa: spara var eleven är och bocka av momentet
      listenPos = end;
      listenSave();
      practiceEnd();
      readingDone();
      listenEnd = -1;
      dirtyOva = true;
      return;
    }
    listenDone = true;
    listenPos = sents.size();
    listenSave();
    practiceEnd();
    readingDone();
    dirtyOva = true;
    return;
  }
  xSemaphoreTake(fetchLock, portMAX_DELAY);
  bool ready = fetchedIdx >= 0 && fetchedGen == fetchGen;
  uint8_t* buf = ready ? fetchedBuf : nullptr;
  size_t len = fetchedLen;
  int idx = fetchedIdx, code = fetchedCode;
  if (ready) { fetchedBuf = nullptr; fetchedIdx = -1; }
  bool idle = fetchWantIdx < 0 && fetchedIdx < 0;
  if (!ready && idle && playQueue.size() < 1 && fetchPos < end) {
    fetchWantIdx = fetchPos;
    fetchWantGen = fetchGen;
    fetchWantText = sents[fetchPos].text;
    {
      const char* l = langFrom(hwCur.subject + " " + hwCur.title);
      fetchWantLang = *l ? l : (looksSwedish(fetchWantText) ? "" : guessLang(fetchWantText));   // språkläxa: alltid språkets röst
    }
  }
  xSemaphoreGive(fetchLock);
  if (ready) {
    if (buf && idx == fetchPos) {
      Clip c{buf, len, idx};
      playQueue.push_back(c);
      fetchPos++;
    } else if (buf) {
      free(buf);
    } else {
      listening = false;
      ovaMessage = code == 503 ? "Uppläsningen är inte påslagen på servern." : "Uppläsningen stannade. Tryck Spela igen.";
      practiceEnd();
      dirtyOva = true;
    }
  }
}

// En fråga att läsa upp (vid sidan av läxans uppläsning)
static String sayWant, sayWantLang;
static int sayGen = 0, sayWantGen = -1, sayBufGen = -1;
static uint8_t* sayBuf = nullptr;
static size_t sayLen = 0;

static void fetchTask(void*) {
  for (;;) {
    int idx = -1, gen = -1;
    bool isSay = false;
    String text, lang;
    xSemaphoreTake(fetchLock, portMAX_DELAY);
    if (fetchWantIdx >= 0 && fetchedIdx < 0) { idx = fetchWantIdx; gen = fetchWantGen; text = fetchWantText; lang = fetchWantLang; fetchWantIdx = -1; }
    else if (sayWant.length()) { isSay = true; gen = sayWantGen; text = sayWant; lang = sayWantLang; sayWant = ""; }
    xSemaphoreGive(fetchLock);
    if (idx < 0 && !isSay) { vTaskDelay(pdMS_TO_TICKS(20)); continue; }
    uint8_t* buf;
    size_t len;
    String path = "/api/speech/preview?small=true";
    if (lang.length()) path += "&lang=" + lang;
    int code = apiBinary(path, "{\"text\":\"" + jsonEscape(text) + "\"}", &buf, &len);
    xSemaphoreTake(fetchLock, portMAX_DELAY);
    if (isSay) {
      if (gen == sayGen && buf) { if (sayBuf) free(sayBuf); sayBuf = buf; sayLen = len; sayBufGen = gen; }
      else if (buf) free(buf);
    } else if (gen == fetchGen) { fetchedBuf = buf; fetchedLen = len; fetchedIdx = idx; fetchedGen = gen; fetchedCode = code; }
    else if (buf) free(buf);
    xSemaphoreGive(fetchLock);
  }
}

static void sayAloud(const String& text, const char* lang) {
  if (!text.length() || !fetchLock) return;
  String t = text;
  t.replace("___", ", lucka, ");
  if (t.length() > 290) t = t.substring(0, 290);
  stopPlayback();
  xSemaphoreTake(fetchLock, portMAX_DELAY);
  sayGen++;
  sayWant = t;
  sayWantLang = lang ? lang : "";
  sayWantGen = sayGen;
  if (sayBuf) { free(sayBuf); sayBuf = nullptr; }
  xSemaphoreGive(fetchLock);
}

static void sayStop() {
  if (!fetchLock) return;
  xSemaphoreTake(fetchLock, portMAX_DELAY);
  sayGen++;
  sayWant = "";
  if (sayBuf) { free(sayBuf); sayBuf = nullptr; }
  xSemaphoreGive(fetchLock);
}

static void pumpSay() {
  if (!fetchLock || recording) return;
  xSemaphoreTake(fetchLock, portMAX_DELAY);
  uint8_t* buf = sayBuf && sayBufGen == sayGen ? sayBuf : nullptr;
  size_t len = sayLen;
  if (buf) sayBuf = nullptr;
  xSemaphoreGive(fetchLock);
  if (buf) { stopPlayback(); Clip c{buf, len, -1}; playQueue.push_back(c); }
}

static void fetchReset() {
  xSemaphoreTake(fetchLock, portMAX_DELAY);
  fetchGen++;
  fetchWantIdx = -1;
  if (fetchedBuf) free(fetchedBuf);
  fetchedBuf = nullptr;
  fetchedIdx = -1;
  xSemaphoreGive(fetchLock);
}

static void loadPlan() {
  planRows.clear();
  planDays.clear();
  planHws.clear();
  ovaMessage = "";
  JsonDocument hwDoc;
  api("GET", "/api/homework?openOnly=false", "", &hwDoc);
  JsonDocument doc;
  int code = api("GET", "/api/plan/upcoming?days=14", "", &doc);
  if (code != 200) { ovaMessage = "Kunde inte hämta planen (" + String(code) + ")."; decksLoaded = true; return; }
  for (JsonObject h : hwDoc.as<JsonArray>()) planHws.push_back(hwFromJson(h));
  for (JsonObject x : doc.as<JsonArray>()) {
    PlanRow r;
    r.id = x["id"] | 0L;
    r.date = x["planDate"] | "";
    r.task = clean(x["task"] | "");
    r.minutes = x["minutes"] | 0;
    r.done = !x["doneAt"].isNull();
    long hwId = x["homeworkId"] | 0L;
    r.hwId = hwId;
    r.type = x["type"] | "";
    parsePages(r.task, r.pageFrom, r.pageTo);
    for (JsonObject h : hwDoc.as<JsonArray>()) if ((h["id"] | 0L) == hwId) { r.subject = clean(h["subject"] | (h["title"] | "")); break; }
    planRows.push_back(r);
    if (std::find(planDays.begin(), planDays.end(), r.date) == planDays.end()) planDays.push_back(r.date);
  }
  std::sort(planDays.begin(), planDays.end());
  planDay = 0;
  if (planDays.empty()) ovaMessage = "Inget inplanerat de närmaste två veckorna.";
  decksLoaded = true;
}

static const char* WEEKDAYS[] = {"Söndag", "Måndag", "Tisdag", "Onsdag", "Torsdag", "Fredag", "Lördag"};

static String dateLabel(const String& iso) {
  if (iso.length() < 10) return iso;
  struct tm t = {};
  t.tm_year = iso.substring(0, 4).toInt() - 1900;
  t.tm_mon = iso.substring(5, 7).toInt() - 1;
  t.tm_mday = iso.substring(8, 10).toInt();
  t.tm_hour = 12;
  time_t when = mktime(&t);
  struct tm now;
  if (getLocalTime(&now, 0)) {
    now.tm_hour = 12; now.tm_min = 0; now.tm_sec = 0;
    long days = lround(difftime(when, mktime(&now)) / 86400.0);
    if (days == 0) return "Idag";
    if (days == 1) return "Imorgon";
    if (days == -1) return "Igår";
  }
  struct tm* lt = localtime(&when);
  return String(WEEKDAYS[lt->tm_wday]) + " " + String(t.tm_mday) + "/" + String(t.tm_mon + 1);
}


static void setLast(int kind, const String& result) {
  // Övningen startades från planen: bocka av momentet (poäng som vanligt)
  if (practicePlanItem && api("POST", "/api/plan/items/" + String(practicePlanItem) + "/done", "", nullptr) == 200) {
    for (auto& r : planRows) if (r.id == practicePlanItem) r.done = true;
    ui::toast("Bockat i planen");
  }
  practicePlanItem = 0;
  lastKind = kind;
  String name;
  for (auto& m : makeList) if (m.id == lastHw) name = m.title;
  lastText = String(MK_LABELS[kind]) + (lastFocus.length() ? " · " + lastFocus : String()) +
             (name.length() ? " · " + name : String()) + (result.length() ? " · " + result : String());
}

/** Dagar från idag till ett datum (YYYY-MM-DD); 0 = idag, negativt = passerat. Utan klocka: 999. */
static int daysUntil(const String& iso) {
  if (iso.length() < 10) return 999;
  struct tm t = {};
  t.tm_year = iso.substring(0, 4).toInt() - 1900;
  t.tm_mon = iso.substring(5, 7).toInt() - 1;
  t.tm_mday = iso.substring(8, 10).toInt();
  t.tm_hour = 12;
  struct tm now;
  if (!getLocalTime(&now, 0)) return 999;
  now.tm_hour = 12; now.tm_min = 0; now.tm_sec = 0;
  return (int)lround(difftime(mktime(&t), mktime(&now)) / 86400.0);
}

/** Ämnets färg – samma färger som i appen (färgen bestäms av ämnets namn). */
static uint32_t subjectColor(const String& subject, const String& title) {
  static const uint32_t COLORS[] = {0x2f6f5e, 0x2c63c9, 0xb7862a, 0xc8453b, 0x7a4fb5, 0x1f8a9e, 0xc2569b, 0x5c7d2e};
  String key = subject.length() ? subject : title;
  key.trim();
  key.toLowerCase();
  // samma hash som appen (h = h*31 + tecken), över Unicode-tecknen
  uint32_t h = 0;
  const uint8_t* p = (const uint8_t*)key.c_str();
  while (*p) {
    uint32_t cp; int n;
    if (*p < 0x80) { cp = *p; n = 1; }
    else if ((*p & 0xE0) == 0xC0) { cp = *p & 0x1F; n = 2; }
    else if ((*p & 0xF0) == 0xE0) { cp = *p & 0x0F; n = 3; }
    else { cp = *p & 0x07; n = 4; }
    for (int i = 1; i < n && p[i]; i++) cp = (cp << 6) | (p[i] & 0x3F);
    h = h * 31 + cp;
    p += n;
  }
  return COLORS[h % 8];
}

static void tickPractice() {
  // Övningstid: kort, Förklara/Testprov/Matcha par/Rätt ordning och uppläsning av läxor
  bool practicing = screen == Screen::OvaCard || screen == Screen::Game || (screen == Screen::HwDetail && listening);
  if (!practicing || !practiceId.length()) { lastActiveTick = millis(); return; }
  if (millis() - lastActiveTick >= 1000) {
    lastActiveTick += 1000;
    if (millis() - lastTouchAt < 120000) practiceActive++;
  }
}

// ---------------------------------------------------------------- vad som syns (modeller till ui.cpp)
static std::string S(const String& s) { return std::string(s.c_str()); }

static uint32_t bootStart = 0;
static int timerDrawn = -2;

static int timerLeftNow() {
  if (!sessionActive || !timerAt) return timerRemaining;
  int left = timerRemaining - (int)((millis() - timerAt) / 1000);
  return left < 0 ? 0 : left;
}

static void drawBoot() {
  bool wifi = WiFi.status() == WL_CONNECTED;
  String line = !wifi ? String("Ansluter till WiFi \"") + PLUGGIS_WIFI_SSID + "\" ..." : String("Hämtar läxor ...");
  String warn;
  if (millis() - bootStart > 30000) {
    warn = !wifi ? String("Hittar inget WiFi. Kolla namn och lösenord i include/config.h och att nätet är 2,4 GHz. (WiFi-status ") +
                       String((int)WiFi.status()) + ", " + String((unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024)) + " kB ledigt)"
                 : String("Får inget svar från Pluggis på ") + PLUGGIS_HOST + ":" + String(PLUGGIS_PORT) + ". Är servern igång?" +
                       (lastError.length() ? "\n" + lastError : String());
  }
  ui::showBoot(S(line), S(warn));
}

static void drawTop() {
  ui::Top t;
  t.tab = screen == Screen::Coach ? 1 : (screen == Screen::HwList || screen == Screen::HwDetail) ? 2 : screen == Screen::Plan ? 3 : 0;
  t.status = WiFi.status() != WL_CONNECTED ? "Inget WiFi" : !wsUp ? "Ansluter" : "";
  t.volume = volume;
  t.battery = batteryLevel;
  t.charging = batteryCharging;
  t.points = weekPoints >= 0 && pointsOn ? weekPoints : -1;
  ui::setTop(t);
}

/** "Pluggis gör ...\n\nDet kan ta en halv minut." → rubrik och liten text under. */
static void drawMessage(const String& msg, bool busy, const char* fallbackHint = "") {
  int br = msg.indexOf("\n\n");
  String text = br >= 0 ? msg.substring(0, br) : msg;
  String hint = br >= 0 ? msg.substring(br + 2) : String(fallbackHint);
  ui::buildMessage(S(text), busy, S(hint));
}

static void drawCoach() {
  ui::Coach m;
  m.cardMode = mode == Mode::Card;
  m.say = S(sayText);
  m.heard = S(heardText);
  m.thinking = thinking;
  m.front = S(cardFront);
  m.hint = S(cardHint);
  m.back = S(cardBack);
  m.answered = cardAnswered;
  m.correct = cardCorrect;
  m.box = cardBox;
  m.session = sessionActive;
  m.recording = recording;
  ui::buildCoach(m);
  timerDrawn = -2;
}

static void drawTimer() {
  int left = timerLeftNow();
  if (left == timerDrawn) return;
  timerDrawn = left;
  ui::coachTimer(sessionActive, left, timerTotal, S(sessionTask));
}

static void drawOvaList() {
  if (!decksLoaded) { ui::buildMessage("Hämtar läxor ...", true); return; }
  if (makeBusy.length()) { drawMessage(makeBusy, true); return; }
  if (ovaMessage.length()) { drawMessage(ovaMessage, false); return; }
  ui::Ova m;
  for (auto& x : makeList) {
    int days = x.due.length() ? daysUntil(x.due) : 999;
    ui::OvaRow r;
    r.title = S((x.test ? "Prov: " : "") + x.title);
    r.when = S(!x.due.length() ? String() : x.startNow ? String("börja nu") : days < 0 ? String("försenad") : dateLabel(x.due));
    r.whenTone = x.startNow ? ui::Tone::Good : days < 0 ? ui::Tone::Bad : days <= 2 ? ui::Tone::Warn : ui::Tone::Muted;
    r.color = subjectColor(x.subject, x.title);
    r.soon = days <= 7;
    m.rows.push_back(r);
  }
  auto& sel = makeList[makeIdx];
  m.selected = makeIdx;
  m.title = S((sel.test ? "Prov: " : "Läxa: ") + sel.title);
  m.meta = S((sel.subject.length() ? sel.subject : String("Ämne saknas")) + (sel.due.length() ? "  ·  " + dateLabel(sel.due) : String()));
  m.color = subjectColor(sel.subject, sel.title);
  m.topicsLoading = mkTopicsFor != sel.id;
  if (!m.topicsLoading) for (auto& t : mkTopics) m.topics.push_back(S(t));
  m.focus = mkFocus;
  m.last = S(lastText);
  m.canAgain = lastKind >= 0;
  ui::buildOva(m);
}

static void drawCard() {
  ui::Card m;
  m.deck = S(cur.name);
  m.answered = answered;
  m.round = ROUND;
  m.right = rightCount;
  m.done = noMoreCards;
  m.doneText = answered ? S("Bra jobbat! " + String(rightCount) + " av " + String(answered) + " rätt.") : "Inga kort att öva just nu.";
  m.question = S(qFront);
  m.hint = S(qHint);
  m.answer = S(qBack);
  for (auto& o : options) m.options.push_back(S(o));
  m.chosen = chosen;
  m.correct = correctIdx;
  m.revealed = revealed;
  m.languageDeck = isLanguageDeck();
  m.spoken = S(spokenAnswer);
  m.verdict = voiceVerdict;
  m.recording = recording;
  ui::buildCard(m);
}

static uint32_t matchSecs = 0;   // tiden när alla par var klara (fryses)

static void drawGame() {
  if (gameMsg.length()) {
    bool busy = gameMsg.endsWith("...") || gameMsg.indexOf("...\n") >= 0;
    drawMessage(gameMsg, busy, busy ? "" : "Tryck för att gå tillbaka");
    return;
  }
  ui::Game m;
  m.title = S(gameTitle);
  m.recording = recording;
  m.done = gameDone;
  if (game == Game::Explain) {
    m.kind = 2;
    m.term = S(exConcept);
    m.heard = S(gameHeard);
    m.reply = S(exReply);
    for (auto& c : exCovered) m.covered.push_back(S(c));
    for (auto& c : exMissing) m.missing.push_back(S(c));
  } else if (game == Game::Exam) {
    m.kind = 3;
    if (!gameDone && examI < (int)examQs.size()) {
      auto& q = examQs[examI];
      m.qIndex = examI;
      m.qCount = examQs.size();
      m.question = S(q.question);
      for (auto& c : q.choices) m.choices.push_back(S(c));
      m.answer = S(examAns[examI]);
      m.lastQuestion = examI + 1 >= (int)examQs.size();
    }
    m.points = examPoints;
    m.maxPoints = examMax;
    m.percent = examPct;
    for (auto& r : examReview) m.review.push_back(S(r));
  } else if (game == Game::Match) {
    m.kind = 4;
    bool wrongNow = millis() < gWrongUntil;
    bool all = std::all_of(matched.begin(), matched.end(), [](bool b) { return b; });
    for (size_t i = 0; i < pairs.size(); i++) {
      int j = rightOrder[i];
      m.left.push_back(S(pairs[i].a));
      m.right.push_back(S(pairs[j].b));
      m.leftState.push_back(matched[i] ? ui::CellState::Matched : selL == (int)i ? ui::CellState::Selected
                            : wrongNow && gWrongA == (int)i ? ui::CellState::Wrong : ui::CellState::Normal);
      m.rightState.push_back(matched[j] ? ui::CellState::Matched : wrongNow && gWrongB == (int)i ? ui::CellState::Wrong
                             : ui::CellState::Normal);
    }
    if (all && !pairs.empty()) m.doneText = S("Klart på " + String(matchSecs) + " s!");
  } else {
    m.kind = 5;
    auto& sq = seqs[seqI];
    m.seqTitle = S(sq.title);
    m.prompt = S(sq.prompt);
    m.why = S(sq.why);
    m.seqIndex = seqI;
    m.seqCount = seqs.size();
    for (int k = 0; k < placed && k < (int)sq.steps.size(); k++) m.placed.push_back(S(sq.steps[k]));
    if (placed < (int)sq.steps.size())
      for (int s : shownSteps) m.steps.push_back(S(sq.steps[s]));
    m.wrongStep = millis() < gWrongUntil ? gWrongA : -1;
  }
  ui::buildGame(m);
}

static void drawHwList() {
  if (!decksLoaded) { ui::buildMessage("Hämtar läxor ...", true); return; }
  if (ovaMessage.length()) { drawMessage(ovaMessage, false); return; }
  std::vector<ui::HwTile> tiles;
  for (auto& h : hws) {
    bool test = h.kind == "TEST";
    tiles.push_back({S((test ? "Prov" : "Läxa") + (h.subject.length() ? String(" · ") + h.subject : String())), test, S(h.title),
                     S(h.due.length() ? dateLabel(h.due) : String()), subjectColor(h.subject, h.title)});
  }
  ui::buildHwList(tiles);
}

static void drawHwDetail() {
  ui::HwDetail m;
  bool test = hwCur.kind == "TEST";
  m.test = test;
  m.meta = S((test ? "Prov" : "Läxa") + (hwCur.subject.length() ? String(" · ") + hwCur.subject : String()) +
             (hwCur.due.length() ? String(" · ") + dateLabel(hwCur.due) : String()));
  m.title = S(hwCur.title);
  m.about = S(hwCur.about);
  m.hasMaterial = !sents.empty();
  if (m.hasMaterial) {
    int ch = chapterOf(listenDone ? -1 : listenPos);
    m.head = S(listenDone ? String("Allt uppläst!") : chapterLabel[ch] + "  ·  " + String(ch + 1) + " av " + String(chapterIds.size()));
    m.percent = listenDone ? 100 : 100 * listenPos / sents.size();
    m.line = S(listenDone ? String("Tryck Spela för att lyssna igen från början.")
               : ovaMessage.length() ? ovaMessage
               : listening || listenPos > 0 ? sents[std::min(listenPos, (int)sents.size() - 1)].text
               : String("Tryck Spela för att lyssna på allt material."));
  }
  m.listening = listening;
  m.playLabel = listening ? "Paus" : listenPos > 0 && !listenDone ? "Fortsätt" : "Spela";
  ui::buildHwDetail(m);
}

static void drawPlan() {
  if (!decksLoaded) { ui::buildMessage("Hämtar planen ...", true); return; }
  if (ovaMessage.length()) { drawMessage(ovaMessage, false); return; }
  String day = planDays[planDay];
  ui::Plan m;
  planShown.clear();
  for (size_t i = 0; i < planRows.size(); i++) {
    auto& r = planRows[i];
    if (r.date != day) continue;
    planShown.push_back(i);
    m.totalMinutes += r.minutes;
    if (r.done) m.doneMinutes += r.minutes;
    int action = !r.hwId ? 0 : r.type == "READ" ? 1 : exerciseFor(r.task).length() ? 2 : 0;
    m.items.push_back({S(r.subject), S(r.task), subjectColor(r.subject, r.task), r.minutes, r.done, action});
  }
  int left = m.totalMinutes - m.doneMinutes;
  m.day = S(dateLabel(day));
  m.summary = left ? S(String(left) + " min kvar av " + String(m.totalMinutes)) : std::string("Allt klart!");
  m.hasPrev = planDay > 0;
  m.hasNext = planDay + 1 < (int)planDays.size();
  ui::buildPlan(m);
}

static void drawScreen() {
  switch (screen) {
    case Screen::Coach: drawCoach(); break;
    case Screen::OvaList: drawOvaList(); break;
    case Screen::OvaCard: drawCard(); break;
    case Screen::Game: drawGame(); break;
    case Screen::HwList: drawHwList(); break;
    case Screen::HwDetail: drawHwDetail(); break;
    case Screen::Plan: drawPlan(); break;
  }
}

/** Bygger om det som ändrats och ritar direkt (så att "Hämtar …" syns innan ett långt anrop). */
static void redraw() {
  if (booting) { drawBoot(); lv_refr_now(lvDisp); return; }
  if (dirtyAll) {
    dirtyTop = true;
    if (screen == Screen::Coach) dirtyMain = true; else dirtyOva = true;
    dirtyAll = false;
  }
  bool any = dirtyTop || dirtyMain || dirtyTimer || dirtyOva;
  if (dirtyTop) { drawTop(); dirtyTop = false; }
  if (screen == Screen::Coach) {
    if (dirtyMain) { drawCoach(); dirtyMain = false; dirtyTimer = true; }
    if (dirtyTimer) { drawTimer(); dirtyTimer = false; }
  } else {
    dirtyMain = dirtyTimer = false;
    if (dirtyOva) { drawScreen(); dirtyOva = false; }
  }
  if (any) lv_refr_now(lvDisp);
}

static void setVolume(int v) {
  volume = v < 0 ? 0 : v > 255 ? 255 : v;
  M5.Speaker.setVolume(volume);
  prefs.putInt("volume", volume);
  if (!M5.Speaker.isPlaying()) playMelody(MEL_TICK, 1);
  dirtyTop = true;
}

// ---------------------------------------------------------------- vad eleven tryckte på (från ui.cpp)
static void startExercise(int i, long hw) {
  if (i == 0) makeQuiz(hw, "quiz");
  else if (i == 1) makeQuiz(hw, "cloze");
  else startGame(i, hw, makeList[makeIdx].label);
}

static void examNext() {
  if (++examI >= (int)examQs.size()) { examI = examQs.size() - 1; examSubmit(); }
  dirtyOva = true;
}

static void orderNextSeq() {
  if (++seqI >= (int)seqs.size()) { gameDoneReport("order"); setLast(5, "klart"); gameExit(); return; }
  placed = 0;
  shownSteps.clear();
  for (size_t i = 0; i < seqs[seqI].steps.size(); i++) shownSteps.push_back(i);
  for (int i = shownSteps.size() - 1; i > 0; i--) std::swap(shownSteps[i], shownSteps[esp_random() % (i + 1)]);
  sayAloud(seqs[seqI].title + ". " + seqs[seqI].prompt);
  dirtyOva = true;
}

/** Tryck på en tom yta: gå tillbaka, nästa, eller försök igen – beroende på skärmen. */
static void onBackground() {
  switch (screen) {
    case Screen::Coach:
      if (mode == Mode::Card && cardAnswered) { mode = Mode::Coach; dirtyMain = true; }
      break;
    case Screen::OvaList:
      if (ovaMessage.length()) { decksLoaded = false; dirtyOva = true; redraw(); loadDecks(); dirtyOva = true; }
      break;
    case Screen::OvaCard:
      if (noMoreCards) showScreen(Screen::OvaList, true);
      else if (options.size() >= 2 && chosen >= 0) { nextCard(); dirtyOva = true; }
      break;
    case Screen::Game:
      if (gameMsg.length()) { if (!gameMsg.endsWith("...")) gameExit(); break; }
      if (game == Game::Order && placed >= (int)seqs[seqI].steps.size()) { orderNextSeq(); break; }
      if (gameDone || (game == Game::Match && std::all_of(matched.begin(), matched.end(), [](bool b) { return b; }))) gameExit();
      break;
    default:
      if (ovaMessage.length()) showScreen(screen, true);   // försök hämta igen
      break;
  }
}

static void onAction(const ui::Action& a) {
  lastTouchAt = millis();
  switch (a.act) {
    case ui::Act::Tab: {
      static const Screen tabs[4] = {Screen::OvaList, Screen::Coach, Screen::HwList, Screen::Plan};
      showScreen(tabs[a.a & 3]);
      break;
    }
    case ui::Act::VolDown: setVolume(volume - 32); break;
    case ui::Act::VolUp: setVolume(volume + 32); break;
    case ui::Act::Background: onBackground(); break;
    case ui::Act::Status: {
      bool wifi = WiFi.status() == WL_CONNECTED;
      String t = wifi ? "WiFi: ansluten till \"" + String(PLUGGIS_WIFI_SSID) + "\" (" + WiFi.localIP().toString() + ", signal " +
                            String(WiFi.RSSI()) + " dBm)"
                      : "WiFi: inte ansluten (status " + String((int)WiFi.status()) + ") till \"" + String(PLUGGIS_WIFI_SSID) + "\"";
      t += "\nServer: " + String(PLUGGIS_HOST) + ":" + String(PLUGGIS_PORT) + " som \"" + PLUGGIS_DEVICE_ID + "\"";
      t += "\nChatten (WebSocket): " + String(wsUp ? "uppe" : "nere") + " – " + wsLast +
           (wsLastAt ? " för " + String((millis() - wsLastAt) / 1000) + " s sedan" : String()) + ", " + String(wsTries) + " försök";
      t += "\nLäxor hämtade: " + String(decksLoaded ? "ja" : "nej");
      if (lastError.length()) t += "\nSenaste fel: " + lastError;
      t += "\nLedigt minne: " + String((unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024)) + " kB internt, " +
           String((unsigned)(heap_caps_get_free_size(MALLOC_CAP_SPIRAM) / 1024)) + " kB PSRAM";
      Serial.printf("[pluggis] status:\n%s\n", t.c_str());
      ui::info("Anslutning", S(t));
      break;
    }
    case ui::Act::TalkPress: startRecording(); break;

    // --- Öva
    case ui::Act::OvaSelect:
      if (a.a >= 0 && a.a < (int)makeList.size() && !makeBusy.length()) { makeIdx = a.a; mkFocus = 0; dirtyOva = true; }
      break;
    case ui::Act::OvaFocus: mkFocus = a.a; dirtyOva = true; break;
    case ui::Act::OvaExercise:
      if (!makeList.empty() && !makeBusy.length()) startExercise(a.a, makeList[makeIdx].id);
      break;
    case ui::Act::OvaAgain:
      if (lastKind < 0) break;
      for (size_t i = 0; i < makeList.size(); i++) if (makeList[i].id == lastHw) makeIdx = i;
      mkFocus = 0;
      for (size_t i = 0; i < mkTopics.size(); i++) if (mkTopicsFor == lastHw && mkTopics[i] == lastFocus) mkFocus = i + 1;
      startExercise(lastKind, lastHw);
      break;

    // --- ett kort
    case ui::Act::CardSpeak:
      if (noMoreCards) break;
      if (options.size() >= 2 && chosen >= 0) { nextCard(); dirtyOva = true; break; }
      if (revealed && *deckLang()) sayCardSide(looksSwedish(qFront) ? qBack : qFront);
      else sayCardSide(revealed ? qFront + ". " + qBack : qFront);
      break;
    case ui::Act::CardOption: {
      if (chosen >= 0 || a.a >= (int)options.size()) break;
      chosen = a.a;
      bool ok = a.a == correctIdx;
      if (ok) SFX_OK(); else SFX_BAD();
      dirtyOva = true;
      redraw();
      if (*deckLang()) { delay(450); sayCardSide(looksSwedish(qFront) ? qBack : qFront); }
      grade(ok);
      dirtyOva = true;
      break;
    }
    case ui::Act::CardNext: nextCard(); dirtyOva = true; break;
    case ui::Act::CardReveal:
      revealed = true;
      dirtyOva = true;
      if (isLanguageDeck()) sayCardSide(looksSwedish(qFront) ? qBack : qFront);
      break;
    case ui::Act::CardKnew:
    case ui::Act::CardDidnt:
      grade(a.act == ui::Act::CardKnew);
      nextCard();
      dirtyOva = true;
      break;

    // --- Förklara, Testprov, Matcha par, Rätt ordning
    case ui::Act::GameSpeak:
      if (game == Game::Exam) { examSpoken = -1; examSpeak(); }
      else if (game == Game::Explain) sayAloud(exReply);
      break;
    case ui::Act::GameChoice: {
      if (game != Game::Exam || gameDone || examI >= (int)examQs.size()) break;
      auto& q = examQs[examI];
      if (a.a >= (int)q.choices.size()) break;
      examAns[examI] = q.choices[a.a];
      dirtyOva = true;
      redraw();
      delay(250);
      examNext();
      break;
    }
    case ui::Act::GameSkip:
      if (game == Game::Exam && !gameDone) examNext();
      break;
    case ui::Act::GameLeft:
      if (game == Game::Match && a.a < (int)matched.size() && !matched[a.a]) { selL = a.a; dirtyOva = true; }
      break;
    case ui::Act::GameRight: {
      if (game != Game::Match || a.a >= (int)rightOrder.size()) break;
      int j = rightOrder[a.a];
      if (matched[j] || selL < 0) break;
      if (j == selL) {
        matched[j] = true;
        SFX_OK();
        if (std::all_of(matched.begin(), matched.end(), [](bool b) { return b; })) {
          matchSecs = (millis() - matchStart) / 1000;
          SFX_DONE();
          gameDoneReport("match");
          setLast(4, String(matchSecs) + " s");
        }
      } else {
        gWrongA = selL;
        gWrongB = a.a;
        gWrongUntil = millis() + 600;
        SFX_BAD();
      }
      selL = -1;
      dirtyOva = true;
      break;
    }
    case ui::Act::GameStep: {
      if (game != Game::Order || seqs.empty()) break;
      auto& sq = seqs[seqI];
      size_t k = a.a;
      if (placed >= (int)sq.steps.size() || k >= shownSteps.size()) break;
      if (shownSteps[k] == placed) {
        placed++;
        shownSteps.erase(shownSteps.begin() + k);
        if (placed >= (int)sq.steps.size()) { SFX_DONE(); sayAloud("Rätt ordning! " + sq.why); } else SFX_OK();
      } else {
        gWrongA = k;
        gWrongUntil = millis() + 600;
        SFX_BAD();
      }
      dirtyOva = true;
      break;
    }

    // --- Läxor
    case ui::Act::HwOpen:
      if (a.a < (int)hws.size()) { openHomework(hws[a.a]); hwBackTo = Screen::HwList; }
      break;
    case ui::Act::HwBack:
      if (listening) listenStop(true);
      showScreen(hwBackTo);
      break;
    case ui::Act::HwPlay:
      if (sents.empty()) break;
      ovaMessage = "";
      if (listening) listenStop(true); else listenStart();
      dirtyOva = true;
      break;
    case ui::Act::HwPrev: {
      if (sents.empty()) break;
      bool was = listening;
      listenStop(false);
      listenDone = false;
      int ch = chapterOf(listenPos);
      int first = 0;
      for (size_t i = 0; i < sents.size(); i++) if (sents[i].matId == chapterIds[ch]) { first = i; break; }
      if (listenPos - first <= 1 && ch > 0) {   // redan i början: sidan innan
        for (size_t i = 0; i < sents.size(); i++) if (sents[i].matId == chapterIds[ch - 1]) { first = i; break; }
      }
      listenPos = first;
      fetchPos = listenPos;
      listenSave();
      if (was) listenStart();
      dirtyOva = true;
      break;
    }
    case ui::Act::HwNext: {
      if (sents.empty()) break;
      bool was = listening;
      listenStop(false);
      int ch = chapterOf(listenPos);
      if (ch + 1 < (int)chapterIds.size()) {
        for (size_t i = 0; i < sents.size(); i++) if (sents[i].matId == chapterIds[ch + 1]) { listenPos = i; break; }
        listenDone = false;
      }
      fetchPos = listenPos;
      listenSave();
      if (was) listenStart();
      dirtyOva = true;
      break;
    }

    // --- Plan
    case ui::Act::PlanPrev: if (planDay > 0) { planDay--; dirtyOva = true; } break;
    case ui::Act::PlanNext: if (planDay + 1 < (int)planDays.size()) { planDay++; dirtyOva = true; } break;
    case ui::Act::PlanRow:
    case ui::Act::PlanCheck: {
      if (a.a >= (int)planShown.size()) break;
      auto& r = planRows[planShown[a.a]];
      // Tryck på ett läsmoment: läs upp sidorna. Bocken bockar alltid av.
      if (a.act == ui::Act::PlanRow && r.type == "READ" && r.hwId) { openReading(r); break; }
      // Tryck på en övning ("Gör ett snabbquiz"): starta den för läxan
      if (a.act == ui::Act::PlanRow && r.hwId && !r.done && exerciseFor(r.task).length()) {
        pendingKind = exerciseFor(r.task);
        pendingHw = r.hwId;
        pendingPractice = true;
        practicePlanItem = r.id;
        break;
      }
      if (r.done) break;
      if (api("POST", "/api/plan/items/" + String(r.id) + "/done", "", nullptr) == 200) {
        r.done = true;
        SFX_OK();
      }
      dirtyOva = true;
      break;
    }

    // --- Chat
    case ui::Act::CoachStart: sendButton(sessionActive ? "double" : "click"); break;
    case ui::Act::CoachEnd: stopPlayback(); sendButton("long"); break;
    default: break;
  }
}

// ---------------------------------------------------------------- LVGL: skärm och pekskärm
static void lvFlush(lv_display_t* d, const lv_area_t* a, uint8_t* px) {
  int w = a->x2 - a->x1 + 1, h = a->y2 - a->y1 + 1;
  M5.Display.startWrite();
  M5.Display.pushImage(a->x1, a->y1, w, h, (const lgfx::rgb565_t*)px);
  M5.Display.endWrite();
  lv_display_flush_ready(d);
}

static void lvTouch(lv_indev_t*, lv_indev_data_t* data) {
  data->point.x = touchX;
  data->point.y = touchY;
  data->state = touchDown && !swallowTouch ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
}

static uint32_t lvTick() { return millis(); }

static void setupLvgl() {
  lv_init();
  lv_tick_set_cb(lvTick);
  W = M5.Display.width();
  H = M5.Display.height();
  lvDisp = lv_display_create(W, H);
  // Två bitar på 1/10 av skärmen i PSRAM. Det interna minnet behövs till WiFi (ESP32-C6 över SDIO).
  size_t bytes = W * (H / 10) * 2;
  uint8_t* b1 = (uint8_t*)heap_caps_malloc(bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  uint8_t* b2 = (uint8_t*)heap_caps_malloc(bytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  lv_display_set_buffers(lvDisp, b1, b2, bytes, LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(lvDisp, lvFlush);
  lv_indev_t* touch = lv_indev_create();
  lv_indev_set_type(touch, LV_INDEV_TYPE_POINTER);
  lv_indev_set_read_cb(touch, lvTouch);
  ui::init();
  ui::setBrand(LOGO_PNG, LOGO_PNG_LEN, AVATAR_PNG, AVATAR_PNG_LEN);
}

// ---------------------------------------------------------------- start
static void connectWifi() {
#if defined(BOARD_SDIO_ESP_HOSTED_CLK)
  WiFi.setPins(BOARD_SDIO_ESP_HOSTED_CLK, BOARD_SDIO_ESP_HOSTED_CMD, BOARD_SDIO_ESP_HOSTED_D0,
               BOARD_SDIO_ESP_HOSTED_D1, BOARD_SDIO_ESP_HOSTED_D2, BOARD_SDIO_ESP_HOSTED_D3,
               BOARD_SDIO_ESP_HOSTED_RESET);
#else
  // Tab5: ESP32-C6 (WiFi) sitter på SDIO2
  WiFi.setPins(GPIO_NUM_12, GPIO_NUM_13, GPIO_NUM_11, GPIO_NUM_10, GPIO_NUM_9, GPIO_NUM_8, GPIO_NUM_15);
#endif
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(PLUGGIS_WIFI_SSID, PLUGGIS_WIFI_PASS);
}

void setup() {
  auto cfg = M5.config();
  cfg.serial_baudrate = 115200;
  M5.begin(cfg);
  M5.Display.setRotation(1);   // liggande
  M5.Display.setBrightness(200);
  prefs.begin("pluggis", false);
  volume = prefs.getInt("volume", PLUGGIS_VOLUME);
  setupLvgl();
  fetchLock = xSemaphoreCreateMutex();
  xTaskCreatePinnedToCore(fetchTask, "pluggis-fetch", 12288, nullptr, 1, nullptr, 0);
  bootStart = millis();
  dirtyAll = true;
  redraw();

  recBuf = (int16_t*)heap_caps_malloc(44 + REC_MAX_SAMPLES * 2, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (!recBuf) Serial.println("[pluggis] kunde inte reservera minne för inspelning");

  M5.Mic.end();
  M5.Speaker.begin();
  M5.Speaker.setVolume(volume);
  M5.Speaker.setChannelVolume(7, 110);   // effektljuden (pling) lägre än talet

  lastActivity = millis();
  bootStart = millis();
  Serial.printf("[pluggis] startar: WiFi \"%s\", server %s:%d, enhet %s\n", PLUGGIS_WIFI_SSID, PLUGGIS_HOST, PLUGGIS_PORT,
                PLUGGIS_DEVICE_ID);
  connectWifi();   // sist, som i den gamla firmware: WiFi-chippet (ESP32-C6) behöver hinna starta efter M5.begin()
  configTzTime("CET-1CEST,M3.5.0,M10.5.0/3", "pool.ntp.org", "time.google.com");   // för "Idag"/"Imorgon"
  String url = String("/ws/device?deviceId=") + PLUGGIS_DEVICE_ID + "&token=" + PLUGGIS_TOKEN;
  ws.begin(PLUGGIS_HOST, PLUGGIS_PORT, url.c_str());
  ws.onEvent(onWs);
  ws.setReconnectInterval(3000);
  ws.enableHeartbeat(20000, 5000, 2);
}

// ---------------------------------------------------------------- stäng av när den inte används
static void markActive() {
  lastActivity = millis();
  if (idleWarned) { idleWarned = false; M5.Display.setBrightness(200); ui::idleWarning(false); }
}

static void goToSleep() {
  // Spara det som pågår innan strömmen går
  if (screen == Screen::HwDetail) listenStop(true);
  if (screen == Screen::OvaCard || screen == Screen::Game) practiceEnd();
  stopPlayback();
  sayStop();
  Serial.println("[pluggis] inaktiv – stänger av");
  M5.Display.setBrightness(0);
  delay(200);
  M5.Power.powerOff();
  // Går det inte att stänga av (t.ex. på USB-ström): släck skärmen och vakna vid tryck
  delay(1500);
  asleep = true;
  idleWarned = false;
  ui::idleWarning(false);
}

static void checkIdle(bool touched) {
  if (PLUGGIS_IDLE_MINUTES <= 0) return;
  if (asleep) {
    if (touched) { asleep = false; M5.Display.setBrightness(200); markActive(); dirtyAll = true; }
    return;
  }
  bool busy = sessionActive || recording || thinking || listening || M5.Speaker.isPlaying() || !playQueue.empty() ||
              makeBusy.length() || (gameMsg.length() && gameMsg.endsWith("..."));
  if (touched || busy) { markActive(); return; }
  uint32_t idle = millis() - lastActivity, limit = (uint32_t)PLUGGIS_IDLE_MINUTES * 60000;
  if (!idleWarned && idle > limit - 30000) {
    idleWarned = true;
    M5.Display.setBrightness(60);
    ui::idleWarning(true);
  }
  if (idle > limit) goToSleep();
}

void loop() {
  M5.update();
  ws.loop();

  // Pekskärmen: LVGL läser läget härifrån (lvTouch)
  auto t = M5.Touch.getDetail();
  touchDown = t.isPressed();
  if (touchDown) { touchX = t.x; touchY = t.y; }
  if (swallowTouch && !touchDown) swallowTouch = false;

  // Kommer WiFi inte upp: börja om anslutningen var 20:e sekund
  static uint32_t wifiTry = millis();
  if (WiFi.status() == WL_CONNECTED) wifiTry = millis();
  else if (millis() - wifiTry > 20000) {
    wifiTry = millis();
    Serial.println("[pluggis] inget WiFi – försöker igen");
    WiFi.disconnect();
    WiFi.begin(PLUGGIS_WIFI_SSID, PLUGGIS_WIFI_PASS);
  }

  static wl_status_t lastWifi = WL_IDLE_STATUS;
  if (WiFi.status() != lastWifi) {
    lastWifi = WiFi.status();
    dirtyTop = true;
    Serial.printf("[pluggis] WiFi-status %d, ledigt internt minne %u byte\n", (int)lastWifi,
                  (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL));
  }

  static bool firstOva = false;
  if (!firstOva && WiFi.status() == WL_CONNECTED) {
    firstOva = true;
    if (screen == Screen::OvaList) showScreen(Screen::OvaList, true);
  }
  if (booting) {
    // klar när WiFi är uppe och läxorna hämtats (eller det gått att svara att något är fel)
    if (WiFi.status() == WL_CONNECTED && decksLoaded) { booting = false; ui::hideBoot(); dirtyAll = true; }
    else {
      static uint32_t lastBootText = 0;
      if (millis() - lastBootText > 500) { lastBootText = millis(); drawBoot(); }
      // försök hämta läxorna igen om första försöket misslyckades
      static uint32_t lastRetry = 0;
      if (WiFi.status() == WL_CONNECTED && firstOva && !decksLoaded && millis() - lastRetry > 5000) { lastRetry = millis(); showScreen(Screen::OvaList, true); }
      lv_timer_handler();
      delay(2);
      return;
    }
  }

  if (t.wasPressed() && (asleep || idleWarned)) {
    swallowTouch = true;   // trycket väcker bara, gör inget annat
    checkIdle(true);
    ui::clearQueue();
    return;
  }
  checkIdle(t.wasPressed() || t.isPressed());

  lv_timer_handler();
  ui::Action act;
  while (ui::poll(act)) onAction(act);

  if (recording) {
    pumpRecording();
    bool released = t.isReleased() || t.wasReleased() || !touchDown;
    if (released || millis() - recStarted > 30000) stopRecording(true);
  }

  pumpPlayback();
  pumpListening();
  pumpSay();
  pumpMelody();
  if (screen == Screen::OvaList && decksLoaded && !makeBusy.length() && !makeList.empty() && mkTopicsFor != makeList[makeIdx].id) {
    long id = makeList[makeIdx].id;
    redraw();   // "Letar delområden ..."
    JsonDocument t;
    mkTopics.clear();
    if (api("GET", "/api/homework/" + String(id) + "/topics", "", &t, 60000) == 200)
      for (JsonVariant v : t["topics"].as<JsonArray>()) mkTopics.push_back(clean(v.as<const char*>()));
    mkTopicsFor = id;
    mkFocus = 0;
    dirtyOva = true;
  }
  if (pendingPractice) {
    pendingPractice = false;
    markActive();
    showScreen(Screen::OvaList, true);   // laddar läxor och kortlekar
    int idx = -1;
    for (size_t i = 0; i < makeList.size(); i++) if (makeList[i].id == pendingHw) idx = i;
    if (idx >= 0) {
      makeIdx = idx;
      if (pendingKind == "quiz" || pendingKind == "cloze") makeQuiz(pendingHw, pendingKind.c_str());
      else startGame(pendingKind == "explain" ? 2 : pendingKind == "exam" ? 3 : pendingKind == "match" ? 4 : 5, pendingHw, makeList[idx].label);
    }
    dirtyAll = true;
  }
  examSpeak();
  if (wsUp && !meLoaded) {
    meLoaded = true;
    JsonDocument me;
    if (api("GET", "/api/chat/me", "", &me) == 200) pointsOn = me["pointsEnabled"] | false;
    dirtyTop = true;
  }
  tickPractice();

  static uint32_t lastBattery = 0;
  if (!lastBattery || millis() - lastBattery > 30000) {
    lastBattery = millis();
    int lvl = M5.Power.getBatteryLevel();
    bool ch = M5.Power.isCharging() == m5::Power_Class::is_charging;
    if (lvl > 100) lvl = 100;
    if (lvl != batteryLevel || ch != batteryCharging) { batteryLevel = lvl; batteryCharging = ch; dirtyTop = true; }
  }

  // Nedräkning och "fel"-blinkningar i spelen
  static uint32_t lastTick = 0;
  if (millis() - lastTick > 400) {
    lastTick = millis();
    static bool wasWrong = false;
    bool wrongNow = millis() < gWrongUntil;
    if (wasWrong && !wrongNow && screen == Screen::Game) dirtyOva = true;
    wasWrong = wrongNow;
    if (sessionActive) dirtyTimer = true;
  }
  if (dirtyAll || dirtyTop || dirtyMain || dirtyTimer || dirtyOva) redraw();
  delay(2);
}
