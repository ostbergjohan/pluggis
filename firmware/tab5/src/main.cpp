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
static Button tabCoach, tabOva, tabHw, tabPlan, btnVolDown, btnVolUp;

// Skärmar: coachen (chatt med röst) eller Öva (kortlekar på skärmen)
enum class Screen { Coach, OvaList, OvaCard, HwList, HwDetail, Plan };
static Screen screen = Screen::Coach;
static bool dirtyOva = false;

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
  int tag = -1;   // uppläsning av läxan: vilken mening
};
static std::deque<Clip> playQueue;
static Clip playing{nullptr, 0};
// Ljudet kommer i bitar (servern delar upp det, se "audioChunk" i hello): samla tills hela meningen finns
static uint8_t* incoming = nullptr;
static size_t incomingLen = 0, incomingFill = 0;
static constexpr int AUDIO_CHUNK = 8192;   // under bibliotekets gräns (15 kB per ram)

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
  tabCoach = {16, 10, 160, 56, "Pluggis", PANEL};
  tabOva = {186, 10, 110, 56, "Öva", PANEL};
  tabHw = {306, 10, 140, 56, "Läxor", PANEL};
  tabPlan = {456, 10, 120, 56, "Plan", PANEL};
  btnVolDown = {W - 560, 10, 64, 56, "-", PANEL};
  btnVolUp = {W - 560 + 64 + 12 + 90 + 12, 10, 64, 56, "+", PANEL};
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

static void drawTab(const Button& b, bool active) {
  auto& d = M5.Display;
  d.fillRoundRect(b.x, b.y, b.w, b.h, 22, active ? ACCENT : PANEL);
  d.setFont(F_MID);
  d.setTextColor(INK);
  d.setTextDatum(middle_center);
  d.drawString(b.label, b.x + b.w / 2, b.y + b.h / 2);
  d.setTextDatum(top_left);
}

static void drawSpeaker(int x, int y, uint32_t c) {
  auto& d = M5.Display;
  d.fillRect(x, y + 12, 10, 16, c);
  d.fillTriangle(x + 10, y + 12, x + 24, y, x + 24, y + 40, c);
  d.fillTriangle(x + 10, y + 28, x + 24, y + 40, x + 10, y + 12, c);
}

static void drawBattery(int x, int y) {
  auto& d = M5.Display;
  if (batteryLevel < 0) return;
  uint32_t c = batteryLevel <= 15 ? BAD : batteryLevel <= 30 ? WARN : GOOD;
  d.drawRoundRect(x, y, 52, 28, 5, INK);
  d.fillRect(x + 52, y + 9, 5, 10, INK);
  int fw = 46 * batteryLevel / 100;
  if (fw > 0) d.fillRoundRect(x + 3, y + 3, fw, 22, 3, c);
  if (batteryCharging) {   // blixt
    d.fillTriangle(x + 28, y + 2, x + 18, y + 16, x + 27, y + 16, INK);
    d.fillTriangle(x + 25, y + 12, x + 34, y + 12, x + 24, y + 26, INK);
  }
  d.setFont(F_SMALL);
  d.setTextColor(MUTED);
  d.drawString(String(batteryLevel) + "%", x + 64, y + 1);
}

static void drawTop() {
  auto& d = M5.Display;
  d.fillRect(0, 0, W, TOP_H, BG);
  drawTab(tabCoach, screen == Screen::Coach);
  drawTab(tabOva, screen == Screen::OvaList || screen == Screen::OvaCard);
  drawTab(tabHw, screen == Screen::HwList || screen == Screen::HwDetail);
  drawTab(tabPlan, screen == Screen::Plan);
  d.setFont(F_SMALL);
  const char* status = WiFi.status() != WL_CONNECTED ? "Inget WiFi" : !wsUp ? "Ansluter" : "";
  d.setTextColor(WARN);
  if (*status) d.drawString(status, tabPlan.x + tabPlan.w + 14, 26);
  // volym: [-] högtalare nivå [+]
  int vx = btnVolDown.x;
  d.fillRoundRect(btnVolDown.x, btnVolDown.y, btnVolDown.w, btnVolDown.h, 18, PANEL);
  d.fillRoundRect(btnVolUp.x, btnVolUp.y, btnVolUp.w, btnVolUp.h, 18, PANEL);
  d.setFont(F_BIG);
  d.setTextColor(INK);
  d.setTextDatum(middle_center);
  d.drawString("-", btnVolDown.x + btnVolDown.w / 2, btnVolDown.y + btnVolDown.h / 2);
  d.drawString("+", btnVolUp.x + btnVolUp.w / 2, btnVolUp.y + btnVolUp.h / 2);
  d.setTextDatum(top_left);
  int sx = vx + btnVolDown.w + 12;
  drawSpeaker(sx, 18, volume ? INK : MUTED);
  int bars = (volume + 50) / 51;   // 0–5
  for (int i = 0; i < 5; i++) {
    int bh = 8 + i * 6;
    d.fillRect(sx + 34 + i * 10, 18 + 40 - bh, 7, bh, i < bars ? ACCENT : PANEL);
  }
  drawBattery(btnVolUp.x + btnVolUp.w + 28, 24);
  if (weekPoints >= 0) {
    d.setFont(F_MID);
    d.setTextColor(WARN);
    d.setTextDatum(top_right);
    d.drawString(String(weekPoints) + " p", W - 28, 20);
    d.setTextDatum(top_left);
  }
  d.drawFastHLine(0, TOP_H - 1, W, PANEL);
}

static void setVolume(int v) {
  volume = v < 0 ? 0 : v > 255 ? 255 : v;
  M5.Speaker.setVolume(volume);
  prefs.putInt("volume", volume);
  if (!M5.Speaker.isPlaying()) M5.Speaker.tone(1000, 60);
  drawTop();
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

static M5Canvas timerCanvas(&M5.Display);
static int timerDrawn = -2;

static void drawTimer() {
  auto& d = M5.Display;
  int y = H - BTN_H - 16 - TIMER_H - 6;
  int x = 48, w = W - 96;
  if (!sessionActive) {
    if (timerDrawn != -1) d.fillRect(0, y - 4, W, TIMER_H + 8, BG);
    timerDrawn = -1;
    return;
  }
  int left = timerLeftNow();
  if (left == timerDrawn) return;
  timerDrawn = left;
  if (!timerCanvas.getBuffer()) {
    timerCanvas.setPsram(true);
    timerCanvas.setColorDepth(16);
    timerCanvas.createSprite(w, TIMER_H);
  }
  auto& c = timerCanvas;
  float frac = timerTotal > 0 ? (float)left / timerTotal : 0;
  c.fillScreen(BG);
  c.fillRoundRect(0, 0, w, TIMER_H, 18, PANEL);
  int fw = (int)(w * frac);
  if (fw > 36) c.fillRoundRect(0, 0, fw, TIMER_H, 18, left < 120 ? WARN : GOOD);
  c.setFont(F_MID);
  c.setTextColor(INK);
  c.setTextDatum(middle_left);
  char mm[16];
  snprintf(mm, sizeof(mm), "%d:%02d", left / 60, left % 60);
  String label = String(mm);
  if (sessionTask.length()) label += "   " + sessionTask;
  if (c.textWidth(label) > w - 40) label = String(mm);
  c.drawString(label, 22, TIMER_H / 2);
  c.pushSprite(x, y);
}

static void drawButtons() {
  btnStart.label = sessionActive ? "Hjälp" : "Börja";
  drawButton(btnStart);
  drawButton(btnTalk, recording, recording ? "Släpp när du är klar" : nullptr);
  drawButton(btnEnd);
}

static void drawOva();

static void redraw() {
  if (dirtyAll) {
    M5.Display.fillScreen(BG);
    dirtyTop = true;
    timerDrawn = -2;
    if (screen == Screen::Coach) { dirtyMain = dirtyTimer = true; drawButtons(); }
    else dirtyOva = true;
    dirtyAll = false;
  }
  M5.Display.startWrite();
  if (dirtyTop) { drawTop(); dirtyTop = false; }
  if (screen == Screen::Coach) {
    if (dirtyMain) { drawMain(); dirtyMain = false; }
    if (dirtyTimer) { drawTimer(); dirtyTimer = false; }
    if (dirtyDots) { if (thinking) drawDots(); dirtyDots = false; }
  } else {
    dirtyMain = dirtyTimer = dirtyDots = false;
    if (dirtyOva) { drawOva(); dirtyOva = false; }
  }
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
  M5.Speaker.setVolume(volume);
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
    if (delta > 0) {
      pointsBanner = "+" + String(delta);
      pointsBannerUntil = millis() + 3500;
    }
    dirtyTop = dirtyMain = true;
    if (screen != Screen::Coach) dirtyOva = true;
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


// ---------------------------------------------------------------- Öva: kortlekar på skärmen
// Samma API som elevchatten (?deviceId= väljer elev). Flerval och lucktext med alternativ trycker man på;
// vanliga kort vänds med "Visa svar" och man säger själv om man kunde. Tiden räknas som övning.

struct DeckInfo {
  long id;
  String name;
  int cards, due;
  long homeworkId;
};
static std::vector<DeckInfo> decks;
static Button deckTiles[6];
static bool decksLoaded = false;
static String ovaMessage;

static DeckInfo cur;
static long cardId = 0;
static String qFront, qBack, qHint;
static std::vector<String> options;
static int correctIdx = -1, chosen = -1;
static bool revealed = false, noMoreCards = false, practiceAnyway = false;
static int answered = 0, rightCount = 0;
static constexpr int ROUND = 10;
static Button optBtn[4], btnReveal, btnKnew, btnDidnt;

static String practiceId;
static uint32_t practiceActive = 0, lastTouchAt = 0, lastActiveTick = 0;

static int api(const char* method, const String& path, const String& body, JsonDocument* out) {
  if (WiFi.status() != WL_CONNECTED) return -1;
  WiFiClient client;
  HTTPClient http;
  String url = String("http://") + PLUGGIS_HOST + ":" + PLUGGIS_PORT + path +
               (path.indexOf('?') < 0 ? "?" : "&") + "deviceId=" + PLUGGIS_DEVICE_ID;
  if (!http.begin(client, url)) return -1;
  http.setTimeout(10000);
  http.addHeader("X-Pluggis-Token", PLUGGIS_TOKEN);
  http.addHeader("Content-Type", "application/json");
  int code = strcmp(method, "GET") == 0 ? http.GET() : http.sendRequest(method, body);
  if (code > 0 && out) {
    String resp = http.getString();
    if (resp.length()) deserializeJson(*out, resp);
  }
  http.end();
  return code;
}

static void loadDecks() {
  decks.clear();
  ovaMessage = "";
  JsonDocument doc;
  int code = api("GET", "/api/flashcards/decks", "", &doc);
  if (code != 200) {
    ovaMessage = "Kunde inte hämta kortlekarna (" + String(code) + "). Är Pluggis igång?";
    decksLoaded = true;
    return;
  }
  for (JsonObject x : doc.as<JsonArray>()) {
    DeckInfo d;
    d.id = x["deck"]["id"] | 0L;
    d.name = clean(x["deck"]["name"] | "Kortlek");
    d.cards = x["cards"] | 0;
    d.due = x["dueNow"] | 0;
    d.homeworkId = x["deck"]["homeworkId"] | 0L;
    if (d.id && d.cards > 0) decks.push_back(d);
  }
  std::sort(decks.begin(), decks.end(), [](const DeckInfo& a, const DeckInfo& b) { return a.due > b.due; });
  if (decks.size() > 6) decks.resize(6);
  if (decks.empty()) ovaMessage = "Inga kortlekar än. Be Pluggis göra kort eller quiz av en läxa.";
  decksLoaded = true;
}

static void practiceBegin() {
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
  api("POST", "/api/practice/" + practiceId + "/end", "{\"activeSeconds\":" + String(practiceActive) + "}", nullptr);
  practiceId = "";
}

static void nextCard() {
  chosen = -1;
  revealed = false;
  options.clear();
  correctIdx = -1;
  if (answered >= ROUND) { noMoreCards = true; return; }
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
      noMoreCards = false;
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
  drawOva();   // "Hämtar …" direkt
  practiceBegin();
  nextCard();
  dirtyAll = true;
}

static void loadHomework();
static void loadPlan();
static void listenStop(bool save);

static void showScreen(Screen s, bool force = false) {
  if (s == screen && !force) return;
  if (screen == Screen::OvaCard) practiceEnd();
  if (screen == Screen::HwDetail) listenStop(true);
  if (recording) stopRecording(false);
  if (s != Screen::Coach) stopPlayback();
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

static void layoutOva() {
  int top = TOP_H + 110, gap = 20;
  int tw = (W - 4 * gap) / 3, th = (H - top - 3 * gap) / 2;
  for (int i = 0; i < 6; i++) deckTiles[i] = {gap + (i % 3) * (tw + gap), top + (i / 3) * (th + gap), tw, th, "", PANEL};
  int oh = 112, ow = (W - 3 * gap) / 2, oy = H - 2 * oh - 2 * gap;
  for (int i = 0; i < 4; i++) optBtn[i] = {gap + (i % 2) * (ow + gap), oy + (i / 2) * (oh + gap), ow, oh, "", PANEL};
  btnReveal = {gap, H - oh - gap, W - 2 * gap, oh, "Visa svar", ACCENT};
  btnDidnt = {gap, H - oh - gap, ow, oh, "Kunde inte", BAD};
  btnKnew = {2 * gap + ow, H - oh - gap, ow, oh, "Kunde", GOOD};
}

static void drawTextButton(const Button& b, uint32_t color, const String& text) {
  auto& d = M5.Display;
  d.fillRoundRect(b.x, b.y, b.w, b.h, 24, color);
  drawWrapped(text, b.x + 16, b.y + 10, b.w - 32, b.h - 20, INK, F_MID, true);
}

static void drawHwPlan();

static void drawOva() {
  auto& d = M5.Display;
  d.fillRect(0, TOP_H, W, H - TOP_H, BG);
  if (screen != Screen::OvaList && screen != Screen::OvaCard) { drawHwPlan(); return; }
  if (screen == Screen::OvaList) {
    d.setFont(F_BIG);
    d.setTextColor(INK);
    d.drawString("Vad vill du öva på?", 28, TOP_H + 28);
    if (!decksLoaded) { drawWrapped("Hämtar kortlekar ...", 28, TOP_H + 120, W - 56, 80, MUTED, F_MID); return; }
    if (ovaMessage.length()) { drawWrapped(ovaMessage, 28, TOP_H + 120, W - 56, 200, MUTED, F_MID); return; }
    for (size_t i = 0; i < decks.size(); i++) {
      auto& b = deckTiles[i];
      d.fillRoundRect(b.x, b.y, b.w, b.h, 24, PANEL);
      if (decks[i].due > 0) d.fillRoundRect(b.x, b.y, 14, b.h, 7, ACCENT);
      drawWrapped(decks[i].name, b.x + 30, b.y + 20, b.w - 50, b.h - 80, INK, F_MID);
      d.setFont(F_SMALL);
      d.setTextColor(decks[i].due > 0 ? ACCENT : MUTED);
      d.drawString(decks[i].due > 0 ? String(decks[i].due) + " att öva nu" : String(decks[i].cards) + " kort", b.x + 30, b.y + b.h - 44);
    }
    return;
  }
  // Ett kort
  d.setFont(F_SMALL);
  d.setTextColor(MUTED);
  d.drawString(cur.name, 28, TOP_H + 16);
  d.setTextDatum(top_right);
  d.drawString(String(answered) + " av " + String(ROUND) + "  ·  " + String(rightCount) + " rätt", W - 28, TOP_H + 16);
  d.setTextDatum(top_left);
  if (noMoreCards) {
    String msg = answered ? "Bra jobbat! " + String(rightCount) + " av " + String(answered) + " rätt." : "Inga kort att öva just nu.";
    drawWrapped(msg + "\n\nTryck för att välja en annan kortlek.", 48, TOP_H + 120, W - 96, 300, answered ? GOOD : INK, F_BIG, true);
    return;
  }
  bool hasOptions = options.size() >= 2;
  int qBottom = hasOptions ? optBtn[0].y - 20 : btnReveal.y - 20;
  String q = qFront;
  if (revealed && !hasOptions) q += "\n\n" + qBack;
  drawWrapped(q, 48, TOP_H + 60, W - 96, qBottom - TOP_H - 60 - (qHint.length() ? 40 : 0),
              revealed && !hasOptions ? GOOD : INK, F_BIG, true);
  if (qHint.length() && !revealed && chosen < 0) drawWrapped("Ledtråd: " + qHint, 48, qBottom - 44, W - 96, 40, MUTED, F_SMALL, true);
  if (hasOptions) {
    for (size_t i = 0; i < options.size() && i < 4; i++) {
      uint32_t c = PANEL;
      if (chosen >= 0) c = (int)i == correctIdx ? GOOD : (int)i == chosen ? BAD : PANEL;
      drawTextButton(optBtn[i], c, options[i]);
    }
    if (chosen >= 0) drawWrapped("Tryck för nästa", 48, qBottom - 44, W - 96, 40, MUTED, F_SMALL, true);
  } else if (!revealed) {
    drawTextButton(btnReveal, ACCENT, "Visa svar");
  } else {
    drawTextButton(btnDidnt, BAD, "Kunde inte");
    drawTextButton(btnKnew, GOOD, "Kunde");
  }
}

static void ovaTouch(int x, int y) {
  lastTouchAt = millis();
  if (screen == Screen::OvaList) {
    for (size_t i = 0; i < decks.size(); i++) if (inside(deckTiles[i], x, y)) { openDeck(decks[i]); return; }
    if (ovaMessage.length()) { decksLoaded = false; dirtyOva = true; redraw(); loadDecks(); dirtyOva = true; }
    return;
  }
  if (noMoreCards) { showScreen(Screen::OvaList, true); return; }
  bool hasOptions = options.size() >= 2;
  if (hasOptions) {
    if (chosen >= 0) { nextCard(); dirtyOva = true; return; }
    for (size_t i = 0; i < options.size() && i < 4; i++) {
      if (inside(optBtn[i], x, y)) {
        chosen = i;
        bool ok = (int)i == correctIdx;
        M5.Speaker.tone(ok ? 1320 : 330, 120);
        dirtyOva = true;
        redraw();
        grade(ok);
        dirtyOva = true;
        return;
      }
    }
  } else if (!revealed) {
    if (inside(btnReveal, x, y)) { revealed = true; dirtyOva = true; }
  } else {
    bool knew = inside(btnKnew, x, y), didnt = inside(btnDidnt, x, y);
    if (knew || didnt) { grade(knew); nextCard(); dirtyOva = true; }
  }
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
static Button hwTiles[6];
static HwInfo hwCur;

// Uppläsning av läxans material, mening för mening (som en ljudbok), sparar var man är på läxan
struct Sent {
  long matId;
  int idx;   // mening inom materialet
  String text;
};
static std::vector<Sent> sents;
static std::vector<String> chapterLabel;
static std::vector<long> chapterIds;
static int listenPos = 0, fetchPos = 0, nowPlaying = -1, sinceSave = 0;
static bool listening = false, listenDone = false;
static Button btnPlay, btnPrev, btnNextCh, btnBack;

// Plan: en dag i taget
struct PlanRow {
  long id;
  String date, task, subject;
  int minutes;
  bool done;
};
static std::vector<PlanRow> planRows;
static std::vector<String> planDays;
static int planDay = 0;
static Button planRowBtn[7], btnDayPrev, btnDayNext;
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

static void loadHomework() {
  hws.clear();
  ovaMessage = "";
  JsonDocument doc;
  int code = api("GET", "/api/homework", "", &doc);
  if (code != 200) { ovaMessage = "Kunde inte hämta läxorna (" + String(code) + ")."; decksLoaded = true; return; }
  for (JsonObject x : doc.as<JsonArray>()) {
    HwInfo h;
    h.id = x["id"] | 0L;
    h.title = clean(x["title"] | "");
    h.subject = clean(x["subject"] | "");
    h.kind = x["kind"] | "";
    h.due = x["dueDate"] | "";
    h.about = clean(x["content"].is<const char*>() ? x["content"].as<const char*>() : (x["description"] | ""));
    h.listenMat = x["listenMaterialId"] | 0L;
    h.listenSent = x["listenSentence"] | 0;
    if (h.id) hws.push_back(h);
    if (hws.size() >= 6) break;
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
  stopPlayback();
  fetchPos = listenPos;
  nowPlaying = -1;
  if (save) listenSave();
  practiceEnd();
}

static void listenStart() {
  if (sents.empty()) return;
  if (listenDone) { listenDone = false; listenPos = 0; }
  stopPlayback();
  fetchPos = listenPos;
  listening = true;
  cur.name = "Lyssnar: " + hwCur.title;
  cur.homeworkId = hwCur.id;
  practiceBegin();
}

/** Anropas i loop(): hämta nästa mening medan den förra spelas, och håll reda på var vi är. */
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
  if (!playing.data && playQueue.empty() && fetchPos >= (int)sents.size()) {
    listening = false;
    listenDone = true;
    listenPos = sents.size();
    listenSave();
    practiceEnd();
    dirtyOva = true;
    return;
  }
  if (playQueue.size() < 1 && fetchPos < (int)sents.size()) {
    uint8_t* buf;
    size_t len;
    int code = apiBinary("/api/speech/preview?small=true", "{\"text\":\"" + jsonEscape(sents[fetchPos].text) + "\"}", &buf, &len);
    if (!listening) { if (buf) free(buf); return; }
    if (buf) {
      Clip c{buf, len, fetchPos};
      playQueue.push_back(c);
      fetchPos++;
    } else {
      listening = false;
      ovaMessage = code == 503 ? "Uppläsningen är inte påslagen på servern." : "Uppläsningen stannade. Tryck Spela igen.";
      dirtyOva = true;
    }
  }
}

static void loadPlan() {
  planRows.clear();
  planDays.clear();
  ovaMessage = "";
  JsonDocument hwDoc;
  api("GET", "/api/homework?openOnly=false", "", &hwDoc);
  JsonDocument doc;
  int code = api("GET", "/api/plan/upcoming?days=14", "", &doc);
  if (code != 200) { ovaMessage = "Kunde inte hämta planen (" + String(code) + ")."; decksLoaded = true; return; }
  for (JsonObject x : doc.as<JsonArray>()) {
    PlanRow r;
    r.id = x["id"] | 0L;
    r.date = x["planDate"] | "";
    r.task = clean(x["task"] | "");
    r.minutes = x["minutes"] | 0;
    r.done = !x["doneAt"].isNull();
    long hwId = x["homeworkId"] | 0L;
    for (JsonObject h : hwDoc.as<JsonArray>()) if ((h["id"] | 0L) == hwId) { r.subject = clean(h["subject"] | (h["title"] | "")); break; }
    planRows.push_back(r);
    if (std::find(planDays.begin(), planDays.end(), r.date) == planDays.end()) planDays.push_back(r.date);
  }
  std::sort(planDays.begin(), planDays.end());
  planDay = 0;
  if (planDays.empty()) ovaMessage = "Inget inplanerat de närmaste två veckorna.";
  decksLoaded = true;
}

static void layoutHwPlan() {
  int gap = 20, top = TOP_H + 110;
  int tw = (W - 4 * gap) / 3, th = (H - top - 3 * gap) / 2;
  for (int i = 0; i < 6; i++) hwTiles[i] = {gap + (i % 3) * (tw + gap), top + (i / 3) * (th + gap), tw, th, "", PANEL};
  int bh = 110, by = H - bh - gap;
  btnBack = {gap, by, 220, bh, "Tillbaka", PANEL};
  btnPrev = {gap * 2 + 220, by, 200, bh, "Förra", PANEL};
  btnPlay = {gap * 3 + 420, by, W - 4 * gap - 420 - 240, bh, "Spela", ACCENT};
  btnNextCh = {W - gap - 240, by, 240, bh, "Nästa sida", PANEL};
  int rh = 66, ry = TOP_H + 100;
  for (int i = 0; i < 7; i++) planRowBtn[i] = {gap, ry + i * (rh + 10), W - 2 * gap, rh, "", PANEL};
  btnDayPrev = {gap, TOP_H + 16, 90, 70, "<", PANEL};
  btnDayNext = {W - gap - 90, TOP_H + 16, 90, 70, ">", PANEL};
}

static void drawHwPlan() {
  auto& d = M5.Display;
  if (!decksLoaded && screen != Screen::HwDetail) { drawWrapped("Hämtar ...", 28, TOP_H + 60, W - 56, 80, MUTED, F_MID); return; }
  if (screen == Screen::HwList) {
    d.setFont(F_BIG);
    d.setTextColor(INK);
    d.drawString("Läxor och prov", 28, TOP_H + 28);
    if (ovaMessage.length()) { drawWrapped(ovaMessage, 28, TOP_H + 120, W - 56, 200, MUTED, F_MID); return; }
    for (size_t i = 0; i < hws.size(); i++) {
      auto& b = hwTiles[i];
      bool test = hws[i].kind == "TEST";
      d.fillRoundRect(b.x, b.y, b.w, b.h, 24, PANEL);
      d.fillRoundRect(b.x, b.y, 14, b.h, 7, test ? WARN : ACCENT);
      d.setFont(F_SMALL);
      d.setTextColor(test ? WARN : ACCENT);
      d.drawString((test ? "Prov" : "Läxa") + (hws[i].subject.length() ? String(" · ") + hws[i].subject : String()), b.x + 30, b.y + 16);
      drawWrapped(hws[i].title, b.x + 30, b.y + 52, b.w - 50, b.h - 110, INK, F_MID);
      d.setFont(F_SMALL);
      d.setTextColor(MUTED);
      if (hws[i].due.length()) d.drawString(dateLabel(hws[i].due), b.x + 30, b.y + b.h - 44);
    }
    return;
  }
  if (screen == Screen::HwDetail) {
    d.setFont(F_SMALL);
    d.setTextColor(hwCur.kind == "TEST" ? WARN : ACCENT);
    d.drawString((hwCur.kind == "TEST" ? "Prov" : "Läxa") + (hwCur.subject.length() ? String(" · ") + hwCur.subject : String()) +
                 (hwCur.due.length() ? String(" · ") + dateLabel(hwCur.due) : String()), 28, TOP_H + 14);
    drawWrapped(hwCur.title, 28, TOP_H + 46, W - 56, 60, INK, F_BIG);
    int y = TOP_H + 110;
    if (hwCur.about.length()) { drawWrapped(hwCur.about, 28, y, W - 56, 76, MUTED, F_SMALL); y += 86; }
    // Spelaren
    int boxH = btnBack.y - 20 - y;
    d.fillRoundRect(20, y, W - 40, boxH, 24, PANEL);
    if (sents.empty()) {
      drawWrapped("Det finns inget inläst material att lyssna på. Fota sidorna i Pluggis-appen.", 48, y + 20, W - 96, boxH - 40, MUTED, F_MID);
    } else {
      int ch = chapterOf(listenDone ? -1 : listenPos);
      d.setFont(F_MID);
      d.setTextColor(INK);
      String head = listenDone ? "Allt uppläst!" : chapterLabel[ch] + "  ·  " + String(ch + 1) + " av " + String(chapterIds.size());
      d.drawString(head, 48, y + 18);
      int pct = listenDone ? 100 : 100 * listenPos / sents.size();
      d.setFont(F_SMALL);
      d.setTextColor(MUTED);
      d.setTextDatum(top_right);
      d.drawString(String(pct) + " %", W - 48, y + 24);
      d.setTextDatum(top_left);
      d.fillRoundRect(48, y + 64, W - 96, 12, 6, BG);
      if (pct > 0) d.fillRoundRect(48, y + 64, (W - 96) * pct / 100, 12, 6, ACCENT);
      String line = listenDone ? "Tryck Spela för att lyssna igen från början."
                  : ovaMessage.length() ? ovaMessage
                  : listening || listenPos > 0 ? sents[std::min(listenPos, (int)sents.size() - 1)].text
                  : "Tryck Spela för att lyssna på allt material.";
      drawWrapped(line, 48, y + 92, W - 96, boxH - 110, listening ? INK : MUTED, F_MID);
    }
    drawTextButton(btnBack, PANEL, "Tillbaka");
    if (!sents.empty()) {
      drawTextButton(btnPrev, PANEL, "Förra");
      drawTextButton(btnPlay, listening ? WARN : ACCENT, listening ? "Paus" : listenPos > 0 && !listenDone ? "Fortsätt" : "Spela");
      drawTextButton(btnNextCh, PANEL, "Nästa sida");
    }
    return;
  }
  // Planen
  if (ovaMessage.length()) { drawWrapped(ovaMessage, 28, TOP_H + 60, W - 56, 200, MUTED, F_MID); return; }
  String day = planDays[planDay];
  int total = 0, left = 0;
  planShown.clear();
  for (size_t i = 0; i < planRows.size(); i++) if (planRows[i].date == day) {
    planShown.push_back(i);
    total += planRows[i].minutes;
    if (!planRows[i].done) left += planRows[i].minutes;
  }
  if (planDay > 0) drawTextButton(btnDayPrev, PANEL, "<");
  if (planDay + 1 < (int)planDays.size()) drawTextButton(btnDayNext, PANEL, ">");
  d.setFont(F_BIG);
  d.setTextColor(INK);
  d.setTextDatum(top_center);
  d.drawString(dateLabel(day), W / 2, TOP_H + 18);
  d.setFont(F_SMALL);
  d.setTextColor(MUTED);
  d.drawString(left ? String(left) + " min kvar av " + String(total) : "Allt klart!", W / 2, TOP_H + 66);
  d.setTextDatum(top_left);
  for (size_t k = 0; k < planShown.size() && k < 7; k++) {
    auto& r = planRows[planShown[k]];
    auto& b = planRowBtn[k];
    d.fillRoundRect(b.x, b.y, b.w, b.h, 18, PANEL);
    // kryssruta
    d.drawRoundRect(b.x + 16, b.y + 13, 40, 40, 8, r.done ? GOOD : INK);
    if (r.done) {
      d.fillRoundRect(b.x + 16, b.y + 13, 40, 40, 8, GOOD);
      d.drawLine(b.x + 26, b.y + 33, b.x + 34, b.y + 42, BG);
      d.drawLine(b.x + 34, b.y + 42, b.x + 48, b.y + 22, BG);
      d.drawLine(b.x + 26, b.y + 34, b.x + 34, b.y + 43, BG);
      d.drawLine(b.x + 34, b.y + 43, b.x + 48, b.y + 23, BG);
    }
    d.setFont(F_SMALL);
    d.setTextColor(ACCENT);
    int tx = b.x + 76;
    if (r.subject.length()) { d.drawString(r.subject, tx, b.y + 19); tx += d.textWidth(r.subject) + 18; }
    d.setTextDatum(top_right);
    d.setTextColor(MUTED);
    d.drawString(String(r.minutes) + " min", b.x + b.w - 20, b.y + 19);
    d.setTextDatum(top_left);
    String task = r.task;
    int maxW = b.x + b.w - 130 - tx;
    d.setFont(F_SMALL);
    while (task.length() > 4 && d.textWidth(task) > maxW) task = task.substring(0, task.length() - 4) + "...";
    d.setTextColor(r.done ? MUTED : INK);
    d.drawString(task, tx, b.y + 19);
  }
  if (planShown.size() > 7) {
    d.setFont(F_SMALL);
    d.setTextColor(MUTED);
    d.drawString("+ " + String(planShown.size() - 7) + " till i Pluggis-appen", 28, planRowBtn[6].y + 76);
  }
}

static void hwPlanTouch(int x, int y) {
  if (screen == Screen::HwList) {
    for (size_t i = 0; i < hws.size(); i++) if (inside(hwTiles[i], x, y)) { openHomework(hws[i]); return; }
    return;
  }
  if (screen == Screen::HwDetail) {
    if (inside(btnBack, x, y)) { showScreen(Screen::HwList); return; }
    if (sents.empty()) return;
    if (inside(btnPlay, x, y)) {
      ovaMessage = "";
      if (listening) listenStop(true); else listenStart();
      dirtyOva = true;
    } else if (inside(btnPrev, x, y)) {
      bool was = listening;
      listenStop(false);
      listenDone = false;
      if (listenPos > 0) listenPos--;
      fetchPos = listenPos;
      if (was) listenStart();
      dirtyOva = true;
    } else if (inside(btnNextCh, x, y)) {
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
    }
    return;
  }
  // Planen
  if (planDays.empty()) return;
  if (planDay > 0 && inside(btnDayPrev, x, y)) { planDay--; dirtyOva = true; return; }
  if (planDay + 1 < (int)planDays.size() && inside(btnDayNext, x, y)) { planDay++; dirtyOva = true; return; }
  for (size_t k = 0; k < planShown.size() && k < 7; k++) {
    if (inside(planRowBtn[k], x, y)) {
      auto& r = planRows[planShown[k]];
      if (r.done) return;
      if (api("POST", "/api/plan/items/" + String(r.id) + "/done", "", nullptr) == 200) {
        r.done = true;
        M5.Speaker.tone(1320, 100);
      }
      dirtyOva = true;
      return;
    }
  }
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

static void tickPractice() {
  if (screen != Screen::OvaCard || !practiceId.length()) return;
  if (millis() - lastActiveTick >= 1000) {
    lastActiveTick += 1000;
    if (millis() - lastTouchAt < 120000) practiceActive++;
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
  prefs.begin("pluggis", false);
  volume = prefs.getInt("volume", PLUGGIS_VOLUME);
  layout();
  layoutOva();
  layoutHwPlan();
  dirtyAll = true;
  redraw();

  recBuf = (int16_t*)heap_caps_malloc(44 + REC_MAX_SAMPLES * 2, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (!recBuf) Serial.println("[pluggis] kunde inte reservera minne för inspelning");

  M5.Mic.end();
  M5.Speaker.begin();
  M5.Speaker.setVolume(volume);

  connectWifi();
  configTzTime("CET-1CEST,M3.5.0,M10.5.0/3", "pool.ntp.org", "time.google.com");   // för "Idag"/"Imorgon"
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
  if (t.wasPressed() && t.y < TOP_H) {
    if (inside(tabCoach, t.x, t.y)) showScreen(Screen::Coach);
    else if (inside(tabOva, t.x, t.y)) showScreen(Screen::OvaList);
    else if (inside(tabHw, t.x, t.y)) showScreen(Screen::HwList);
    else if (inside(tabPlan, t.x, t.y)) showScreen(Screen::Plan);
    else if (inside(btnVolDown, t.x, t.y)) setVolume(volume - 32);
    else if (inside(btnVolUp, t.x, t.y)) setVolume(volume + 32);
  } else if (t.wasPressed() && (screen == Screen::OvaList || screen == Screen::OvaCard)) {
    ovaTouch(t.x, t.y);
  } else if (t.wasPressed() && screen != Screen::Coach) {
    hwPlanTouch(t.x, t.y);
  } else if (t.wasPressed()) {
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
  pumpListening();
  tickPractice();

  static uint32_t lastBattery = 0;
  if (!lastBattery || millis() - lastBattery > 30000) {
    lastBattery = millis();
    int lvl = M5.Power.getBatteryLevel();
    bool ch = M5.Power.isCharging() == m5::Power_Class::is_charging;
    if (lvl > 100) lvl = 100;
    if (lvl != batteryLevel || ch != batteryCharging) { batteryLevel = lvl; batteryCharging = ch; dirtyTop = true; }
  }

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
