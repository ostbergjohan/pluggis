// Pluggis på M5Stack Tab5 – skrivbordsskärmen.
//
// Pratar med Pluggis-servern över WebSocket enligt docs/protokoll.md:
//  - visar coachens svar, "tänker", timern för passet, flashkort och poäng
//  - knappar på pekskärmen: Börja/Hjälp, Avsluta, och håll in "Prata" för att prata
//  - rösten spelas in (16 kHz mono) och skickas som wav; svaret spelas upp mening för mening
//
// Version 1: ingen kamera ännu (fota med telefonen), ingen Dual Button.

#include <M5Unified.h>
#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <deque>

#if __has_include("config.h")
#include "config.h"
#else
#warning "include/config.h saknas – bygger med config.example.h (kopiera och fyll i innan du flashar)"
#include "config.example.h"
#endif

// ---------------------------------------------------------------- utseende
static constexpr uint32_t BG = 0x101820;      // mörkblå bakgrund
static constexpr uint32_t PANEL = 0x1C2733;
static constexpr uint32_t INK = 0xF2F5F8;
static constexpr uint32_t MUTED = 0x8FA1B3;
static constexpr uint32_t ACCENT = 0x4F8EF7;   // Pluggis-blå
static constexpr uint32_t GOOD = 0x34C77B;
static constexpr uint32_t BAD = 0xF0605D;
static constexpr uint32_t WARN = 0xF5B544;

static const lgfx::IFont* F_BIG = &fonts::lgfxJapanGothicP_40;
static const lgfx::IFont* F_MID = &fonts::lgfxJapanGothicP_32;
static const lgfx::IFont* F_SMALL = &fonts::lgfxJapanGothicP_24;

static int W = 1280, H = 720;
static constexpr int TOP_H = 76;
static constexpr int BTN_H = 130;
static constexpr int TIMER_H = 64;

struct Button {
  int x, y, w, h;
  const char* label;
  uint32_t color;
};
static Button btnStart, btnEnd, btnTalk;

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
static String pointsBanner;
static uint32_t pointsBannerUntil = 0;

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
};
static std::deque<Clip> playQueue;
static Clip playing{nullptr, 0};
// Ljudet kommer i bitar (servern delar upp det, se "audioChunk" i hello): samla tills hela meningen finns
static uint8_t* incoming = nullptr;
static size_t incomingLen = 0, incomingFill = 0;
static constexpr int AUDIO_CHUNK = 16384;

static bool dirtyAll = true, dirtyMain = true, dirtyTimer = true, dirtyTop = true;

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

/** Ritar text med radbrytning på ord inom rutan. Krymper typsnittet om texten inte får plats. */
static void drawWrapped(const String& text, int x, int y, int w, int h, uint32_t color,
                        const lgfx::IFont* first = F_BIG, bool center = false) {
  const lgfx::IFont* fonts[] = {F_BIG, F_MID, F_SMALL};
  int start = first == F_MID ? 1 : first == F_SMALL ? 2 : 0;
  auto& d = M5.Display;
  for (int fi = start; fi < 3; fi++) {
    d.setFont(fonts[fi]);
    int lineH = d.fontHeight() + 8;
    // dela upp i rader
    std::vector<String> lines;
    String line;
    int i = 0, len = text.length();
    while (i <= len) {
      int sp = text.indexOf(' ', i);
      int nl = text.indexOf('\n', i);
      int end = sp < 0 ? len : sp;
      bool hardBreak = false;
      if (nl >= 0 && nl < end) { end = nl; hardBreak = true; }
      String word = text.substring(i, end);
      String test = line.length() ? line + " " + word : word;
      if (d.textWidth(test) > w && line.length()) {
        lines.push_back(line);
        line = word;
      } else {
        line = test;
      }
      if (hardBreak) { lines.push_back(line); line = ""; }
      i = end + 1;
    }
    if (line.length()) lines.push_back(line);
    if ((int)lines.size() * lineH <= h || fi == 2) {
      d.setTextColor(color);
      int total = lines.size() * lineH;
      int yy = center ? y + (h - total) / 2 : y;
      for (auto& l : lines) {
        if (yy + lineH > y + h + 4) break;
        if (center) { d.setTextDatum(top_center); d.drawString(l, x + w / 2, yy); }
        else { d.setTextDatum(top_left); d.drawString(l, x, yy); }
        yy += lineH;
      }
      d.setTextDatum(top_left);
      return;
    }
  }
}

// ---------------------------------------------------------------- ritning
static void layout() {
  W = M5.Display.width();
  H = M5.Display.height();
  int gap = 20, by = H - BTN_H - 16;
  int talkW = W / 2 - gap;
  int sideW = (W - talkW - 4 * gap) / 2;
  btnStart = {gap, by, sideW, BTN_H, "Börja", PANEL};
  btnTalk = {gap * 2 + sideW, by, talkW, BTN_H, "Håll in och prata", ACCENT};
  btnEnd = {gap * 3 + sideW + talkW, by, sideW, BTN_H, "Avsluta", PANEL};
}

static bool inside(const Button& b, int x, int y) {
  return x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h;
}

static void drawButton(const Button& b, bool pressed = false, const char* label = nullptr) {
  auto& d = M5.Display;
  d.fillRoundRect(b.x, b.y, b.w, b.h, 28, pressed ? WARN : b.color);
  d.setFont(F_BIG);
  d.setTextColor(INK);
  d.setTextDatum(middle_center);
  d.drawString(label ? label : b.label, b.x + b.w / 2, b.y + b.h / 2);
  d.setTextDatum(top_left);
}

static void drawTop() {
  auto& d = M5.Display;
  d.fillRect(0, 0, W, TOP_H, BG);
  d.setFont(F_BIG);
  d.setTextColor(INK);
  d.drawString("Pluggis", 28, 16);
  d.setFont(F_SMALL);
  const char* status = WiFi.status() != WL_CONNECTED ? "Inget WiFi" : !wsUp ? "Ansluter till Pluggis ..." : "";
  d.setTextColor(wsUp ? GOOD : WARN);
  if (*status) d.drawString(status, 230, 30);
  if (weekPoints >= 0) {
    d.setFont(F_MID);
    d.setTextColor(WARN);
    d.setTextDatum(top_right);
    d.drawString(String(weekPoints) + " p", W - 28, 20);
    d.setTextDatum(top_left);
  }
  d.drawFastHLine(0, TOP_H - 1, W, PANEL);
}

static int mainTop() { return TOP_H + 12; }
static int mainBottom() { return H - BTN_H - 16 - (sessionActive ? TIMER_H + 12 : 12); }

static bool dirtyDots = false;
static void drawDots() {
  auto& d = M5.Display;
  int y = mainTop() + 8 + (heardText.length() ? 64 : 0) + 20;
  d.fillRect(48, y, 700, 56, BG);
  d.setFont(F_BIG);
  d.setTextColor(ACCENT);
  int dots = ((millis() - thinkingSince) / 400) % 4;
  String t = "Pluggis tänker";
  for (int i = 0; i < dots; i++) t += " .";
  d.drawString(t, 48, y);
}

static void drawMain() {
  auto& d = M5.Display;
  int top = mainTop(), bottom = mainBottom();
  d.fillRect(0, top, W, bottom - top + 2, BG);
  int x = 48, w = W - 96;
  if (mode == Mode::Card) {
    int cardH = bottom - top - 10;
    uint32_t edge = !cardAnswered ? ACCENT : cardCorrect ? GOOD : BAD;
    d.fillRoundRect(x, top, w, cardH, 24, PANEL);
    d.drawRoundRect(x, top, w, cardH, 24, edge);
    d.drawRoundRect(x + 1, top + 1, w - 2, cardH - 2, 23, edge);
    d.setFont(F_SMALL);
    d.setTextColor(MUTED);
    d.drawString(cardAnswered ? (cardCorrect ? "Rätt!" : "Inte riktigt") : "Flashkort", x + 28, top + 18);
    if (cardAnswered && cardBox > 0) {
      d.setTextDatum(top_right);
      d.drawString("Fack " + String(cardBox) + " av 5", x + w - 28, top + 18);
      d.setTextDatum(top_left);
    }
    String body = cardAnswered ? cardFront + "\n\n" + cardBack : cardFront;
    drawWrapped(body, x + 28, top + 64, w - 56, cardH - (cardHint.length() && !cardAnswered ? 130 : 90),
                cardAnswered ? (cardCorrect ? GOOD : INK) : INK, F_BIG, true);
    if (cardHint.length() && !cardAnswered) {
      drawWrapped("Ledtråd: " + cardHint, x + 28, top + cardH - 60, w - 56, 50, MUTED, F_SMALL, true);
    }
  } else {
    int y = top + 8;
    if (heardText.length()) {
      drawWrapped("Du: " + heardText, x, y, w, 70, MUTED, F_SMALL);
      y += 64;
    }
    if (thinking) {
      drawDots();
    } else {
      drawWrapped(sayText, x, y, w, bottom - y, INK, F_BIG);
    }
  }
  if (pointsBanner.length() && millis() < pointsBannerUntil) {
    d.setFont(F_BIG);
    int bw = d.textWidth(pointsBanner) + 60;
    d.fillRoundRect(W - bw - 28, top + 6, bw, 64, 32, WARN);
    d.setTextColor(BG);
    d.setTextDatum(middle_center);
    d.drawString(pointsBanner, W - 28 - bw / 2, top + 38);
    d.setTextDatum(top_left);
  }
}

static int timerLeftNow() {
  if (!sessionActive || !timerAt) return timerRemaining;
  int left = timerRemaining - (int)((millis() - timerAt) / 1000);
  return left < 0 ? 0 : left;
}

static void drawTimer() {
  auto& d = M5.Display;
  int y = H - BTN_H - 16 - TIMER_H - 6;
  d.fillRect(0, y - 4, W, TIMER_H + 8, BG);
  if (!sessionActive) return;
  int x = 48, w = W - 96;
  int left = timerLeftNow();
  float frac = timerTotal > 0 ? (float)left / timerTotal : 0;
  d.fillRoundRect(x, y, w, TIMER_H, 18, PANEL);
  int fw = (int)(w * frac);
  if (fw > 36) d.fillRoundRect(x, y, fw, TIMER_H, 18, left < 120 ? WARN : GOOD);
  d.setFont(F_MID);
  d.setTextColor(INK);
  d.setTextDatum(middle_left);
  char mm[16];
  snprintf(mm, sizeof(mm), "%d:%02d", left / 60, left % 60);
  String label = String(mm);
  if (sessionTask.length()) label += "   " + sessionTask;
  if (d.textWidth(label) > w - 40) label = String(mm);
  d.drawString(label, x + 22, y + TIMER_H / 2);
  d.setTextDatum(top_left);
}

static void drawButtons() {
  btnStart.label = sessionActive ? "Hjälp" : "Börja";
  drawButton(btnStart);
  drawButton(btnTalk, recording, recording ? "Släpp när du är klar" : nullptr);
  drawButton(btnEnd);
}

static void redraw() {
  if (dirtyAll) {
    M5.Display.fillScreen(BG);
    dirtyTop = dirtyMain = dirtyTimer = true;
    drawButtons();
    dirtyAll = false;
  }
  M5.Display.startWrite();
  if (dirtyTop) { drawTop(); dirtyTop = false; }
  if (dirtyMain) { drawMain(); dirtyMain = false; }
  if (dirtyTimer) { drawTimer(); dirtyTimer = false; }
  if (dirtyDots) { if (thinking) drawDots(); dirtyDots = false; }
  M5.Display.endWrite();
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

static void startRecording() {
  if (recording || !recBuf) return;
  stopPlayback();
  M5.Speaker.end();
  M5.Mic.begin();
  recSamples = 0;
  recording = true;
  recStarted = millis();
  drawButton(btnTalk, true, "Släpp när du är klar");
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
  M5.Speaker.setVolume(PLUGGIS_VOLUME);
  drawButton(btnTalk);
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
    thinking = false;
    sayText = clean(doc["text"] | "");
    if (mode == Mode::Card && cardAnswered) mode = Mode::Coach;
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
    mode = Mode::Card;
    thinking = false;
    cardFront = clean(doc["front"] | "");
    cardHint = clean(doc["hint"] | "");
    cardBack = "";
    cardAnswered = false;
    dirtyMain = true;
  } else if (type == "card_answer") {
    mode = Mode::Card;
    cardBack = clean(doc["back"] | "");
    cardCorrect = doc["correct"] | false;
    cardBox = doc["box"] | 0;
    cardAnswered = true;
    dirtyMain = true;
  } else if (type == "points") {
    int delta = doc["delta"] | 0;
    if (doc["weekTotal"].is<int>()) weekPoints = doc["weekTotal"].as<int>();
    if (delta > 0) {
      pointsBanner = "+" + String(delta);
      pointsBannerUntil = millis() + 3500;
    }
    dirtyTop = dirtyMain = true;
  } else if (type == "take_photo") {
    thinking = false;
    sayText = "Kameran på skärmen kommer snart. Fota med telefonen i Pluggis-appen så länge.";
    dirtyMain = true;
  } else if (type == "error") {
    Serial.printf("[pluggis] fel från servern: %s\n", (const char*)(doc["message"] | ""));
    thinking = false;
    dirtyMain = true;
  }
}

static void onBinary(uint8_t* data, size_t len) {
  if (!incoming || recording) return;
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
      if (wsUp) Serial.println("[pluggis] frånkopplad, försöker igen");
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
    default:
      break;
  }
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
  layout();
  dirtyAll = true;
  redraw();

  recBuf = (int16_t*)heap_caps_malloc(44 + REC_MAX_SAMPLES * 2, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (!recBuf) Serial.println("[pluggis] kunde inte reservera minne för inspelning");

  M5.Mic.end();
  M5.Speaker.begin();
  M5.Speaker.setVolume(PLUGGIS_VOLUME);

  connectWifi();
  String url = String("/ws/device?deviceId=") + PLUGGIS_DEVICE_ID + "&token=" + PLUGGIS_TOKEN;
  ws.begin(PLUGGIS_HOST, PLUGGIS_PORT, url.c_str());
  ws.onEvent(onWs);
  ws.setReconnectInterval(3000);
  ws.enableHeartbeat(20000, 5000, 2);
}

void loop() {
  M5.update();
  ws.loop();

  static wl_status_t lastWifi = WL_IDLE_STATUS;
  if (WiFi.status() != lastWifi) { lastWifi = WiFi.status(); dirtyTop = true; }

  // Pekskärmen
  auto t = M5.Touch.getDetail();
  if (t.wasPressed()) {
    if (inside(btnTalk, t.x, t.y)) startRecording();
    else if (inside(btnStart, t.x, t.y)) { drawButton(btnStart, true); sendButton(sessionActive ? "double" : "click"); delay(80); drawButton(btnStart); }
    else if (inside(btnEnd, t.x, t.y)) {
      drawButton(btnEnd, true, "Avslutar ...");
      stopPlayback();
      sendButton("long");
      delay(150);
      drawButton(btnEnd);
    }
    else if (mode == Mode::Card && cardAnswered) { mode = Mode::Coach; dirtyMain = true; }
  }
  if (recording) {
    pumpRecording();
    bool released = t.isReleased() || t.wasReleased();
    if (released || millis() - recStarted > 30000) stopRecording(true);
  }

  pumpPlayback();

  // Animationer och nedräkning
  static uint32_t lastTick = 0;
  if (millis() - lastTick > 400) {
    lastTick = millis();
    if (thinking && mode == Mode::Coach) dirtyDots = true;
    if (sessionActive) dirtyTimer = true;
    if (pointsBanner.length() && millis() > pointsBannerUntil) { pointsBanner = ""; dirtyMain = true; }
  }
  if (dirtyAll || dirtyTop || dirtyMain || dirtyTimer || dirtyDots) redraw();
  delay(2);
}
