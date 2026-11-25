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

#ifndef PLUGGIS_IDLE_MINUTES
#define PLUGGIS_IDLE_MINUTES 5   // stäng av efter så många minuter utan aktivitet (0 = aldrig)
#endif

static uint32_t lastActivity = 0;
static bool asleep = false, idleWarned = false;

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

// Allt ritas först i en bild i minnet (frame) och läggs sedan på skärmen i ett svep – inget blinkande.
static M5Canvas frame(&M5.Display);
static LovyanGFX* G = &M5.Display;
static void present() {
  if (G == &frame) frame.pushSprite(0, 0);
}
static constexpr int TOP_H = 96;
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
enum class Screen { Coach, OvaList, OvaCard, HwList, HwDetail, Plan, Game };
static Screen screen = Screen::Coach;
enum class Game { None, Explain, Exam, Match, Order };
static Game game = Game::None;
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
static bool pointsOn = false;    // poäng av/på för eleven (admin) – hämtas från /api/chat/me
static bool meLoaded = false;
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

// Ljudeffekter: en liten melodi spelas ton för ton från loop() (på en egen kanal, så den inte avbryter talet)
struct Note { uint16_t f, ms; };
static const Note MEL_OK[] = {{1047, 70}, {1319, 70}, {1568, 70}, {2093, 160}};
static const Note MEL_BAD[] = {{466, 150}, {440, 150}, {415, 150}, {392, 380}};
static const Note MEL_DONE[] = {{784, 110}, {1047, 110}, {1319, 110}, {1568, 180}, {1319, 90}, {1568, 360}};
static const Note MEL_TICK[] = {{1500, 40}};
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
static void sayAloud(const String& text, const char* lang = "");
static void sayCardSide(const String& text);
static const char* langFrom(String t);
static bool looksSwedish(const String& in);
static const char* guessLang(const String& in);
static void sayStop();
static int examSpoken = -1;
static void examSpeak();

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
  auto& d = *G;
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
  tabCoach = {12, 10, 186, 76, "Pluggis", PANEL};
  tabOva = {208, 10, 142, 76, "Öva", PANEL};
  tabHw = {360, 10, 166, 76, "Läxor", PANEL};
  tabPlan = {536, 10, 142, 76, "Plan", PANEL};
  btnVolDown = {W - 520, 14, 68, 68, "-", PANEL};
  btnVolUp = {W - 520 + 68 + 12 + 90 + 12, 14, 68, 68, "+", PANEL};
}

static bool inside(const Button& b, int x, int y) {
  return x >= b.x && x < b.x + b.w && y >= b.y && y < b.y + b.h;
}

static void drawButton(const Button& b, bool pressed = false, const char* label = nullptr) {
  auto& d = *G;
  d.fillRoundRect(b.x, b.y, b.w, b.h, 28, pressed ? WARN : b.color);
  d.setFont(F_BIG);
  d.setTextColor(INK);
  d.setTextDatum(middle_center);
  d.drawString(label ? label : b.label, b.x + b.w / 2, b.y + b.h / 2);
  d.setTextDatum(top_left);
}

static void drawTab(const Button& b, bool active) {
  auto& d = *G;
  d.fillRoundRect(b.x, b.y, b.w, b.h, 22, active ? ACCENT : PANEL);
  d.setFont(F_BIG);
  d.setTextColor(INK);
  d.setTextDatum(middle_center);
  d.drawString(b.label, b.x + b.w / 2, b.y + b.h / 2);
  d.setTextDatum(top_left);
}

static void drawSpeaker(int x, int y, uint32_t c) {
  auto& d = *G;
  d.fillRect(x, y + 12, 10, 16, c);
  d.fillTriangle(x + 10, y + 12, x + 24, y, x + 24, y + 40, c);
  d.fillTriangle(x + 10, y + 28, x + 24, y + 40, x + 10, y + 12, c);
}

static void drawBattery(int x, int y) {
  auto& d = *G;
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
  auto& d = *G;
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
  drawSpeaker(sx, 28, volume ? INK : MUTED);
  int bars = (volume + 50) / 51;   // 0–5
  for (int i = 0; i < 5; i++) {
    int bh = 8 + i * 6;
    d.fillRect(sx + 34 + i * 10, 28 + 40 - bh, 7, bh, i < bars ? ACCENT : PANEL);
  }
  drawBattery(btnVolUp.x + btnVolUp.w + 28, 34);
  if (weekPoints >= 0 && pointsOn) {
    d.setFont(F_MID);
    d.setTextColor(WARN);
    d.setTextDatum(top_right);
    d.drawString(String(weekPoints) + " p", W - 28, 30);
    d.setTextDatum(top_left);
  }
  d.drawFastHLine(0, TOP_H - 1, W, PANEL);
}

static void setVolume(int v) {
  volume = v < 0 ? 0 : v > 255 ? 255 : v;
  M5.Speaker.setVolume(volume);
  prefs.putInt("volume", volume);
  if (!M5.Speaker.isPlaying()) playMelody(MEL_TICK, 1);
  dirtyTop = true;
}

static int mainTop() { return TOP_H + 12; }
static int mainBottom() { return H - BTN_H - 16 - (sessionActive ? TIMER_H + 12 : 12); }

static bool dirtyDots = false;
static void drawDots() {
  auto& d = *G;
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
  auto& d = *G;
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

static int timerDrawn = -2;

static void drawTimer() {
  auto& d = *G;
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
  auto& c = *G;
  float frac = timerTotal > 0 ? (float)left / timerTotal : 0;
  c.fillRect(0, y - 4, W, TIMER_H + 8, BG);
  c.fillRoundRect(x, y, w, TIMER_H, 18, PANEL);
  int fw = (int)(w * frac);
  if (fw > 36) c.fillRoundRect(x, y, fw, TIMER_H, 18, left < 120 ? WARN : GOOD);
  c.setFont(F_MID);
  c.setTextColor(INK);
  c.setTextDatum(middle_left);
  char mm[16];
  snprintf(mm, sizeof(mm), "%d:%02d", left / 60, left % 60);
  String label = String(mm);
  if (sessionTask.length()) label += "   " + sessionTask;
  if (c.textWidth(label) > w - 40) label = String(mm);
  c.drawString(label, x + 22, y + TIMER_H / 2);
  c.setTextDatum(top_left);
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
    G->fillScreen(BG);
    dirtyTop = true;
    timerDrawn = -2;
    if (screen == Screen::Coach) { dirtyMain = dirtyTimer = true; drawButtons(); }
    else dirtyOva = true;
    dirtyAll = false;
  }
  bool any = dirtyTop || dirtyMain || dirtyTimer || dirtyDots || dirtyOva;
  if (dirtyTop) { drawTop(); dirtyTop = false; }
  if (screen == Screen::Coach) {
    if (dirtyMain) { drawMain(); dirtyMain = false; }
    if (dirtyTimer) { drawTimer(); dirtyTimer = false; }
    if (dirtyDots) { if (thinking) drawDots(); dirtyDots = false; }
  } else {
    dirtyMain = dirtyTimer = dirtyDots = false;
    if (dirtyOva) { drawOva(); dirtyOva = false; }
  }
  if (any) present();
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
  if (screen == Screen::Coach) { drawButton(btnTalk, true, "Släpp när du är klar"); present(); }
  else dirtyOva = true;
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
  if (screen != Screen::Coach) { answerByVoice(send); return; }
  drawButton(btnTalk);
  present();
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
    if (delta > 0 && pointsOn) {
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
  String subject;
  int cards, due;
  long homeworkId;
};
static std::vector<DeckInfo> decks;
// "Ny övning": välj läxa/prov och gör ett snabbquiz eller en lucktext av materialet (som i appen)
struct MakeHw {
  long id;
  String label;
};
static std::vector<MakeHw> makeList;
static int makeIdx = 0;
static Button btnMkPrev, btnMkNext, btnMk[6];
static const char* MK_LABELS[6] = {"Snabbquiz", "Lucktext", "Förklara", "Testprov", "Matcha par", "Rätt ordning"};
static std::vector<long> allDeckIds, allDeckHw;   // alla kortlekar och deras läxa – för Matcha par
static String makeBusy;
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
static std::vector<String> deckBacks;   // svaren på lekens kort – blir felaktiga alternativ
static String spokenAnswer;
static bool isLanguageDeck();
static String jsonEscape(const String& t);
static int voiceVerdict = -1;           // 1 låter rätt, 0 låter fel
static Button btnSay;
static constexpr int ROUND = 10;
static Button optBtn[4], btnReveal, btnKnew, btnDidnt;

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
    d.subject = clean(x["deck"]["subject"] | "");
    d.cards = x["cards"] | 0;
    d.due = x["dueNow"] | 0;
    d.homeworkId = x["deck"]["homeworkId"] | 0L;
    if (d.id && d.cards > 0) decks.push_back(d);
  }
  std::sort(decks.begin(), decks.end(), [](const DeckInfo& a, const DeckInfo& b) { return a.due > b.due; });
  makeList.clear();
  JsonDocument hw;
  if (api("GET", "/api/homework", "", &hw) == 200) {
    for (JsonObject x : hw.as<JsonArray>()) {
      String kind = x["kind"] | "";
      if (kind == "OTHER") continue;
      String label = String(kind == "TEST" ? "Prov: " : "Läxa: ") + clean(x["title"] | "");
      makeList.push_back({x["id"] | 0L, label});
    }
  }
  if (makeIdx >= (int)makeList.size()) makeIdx = 0;
  allDeckIds.clear();
  allDeckHw.clear();
  for (auto& d : decks) { allDeckIds.push_back(d.id); allDeckHw.push_back(d.homeworkId); }
  if (decks.size() > 6) decks.resize(6);
  if (decks.empty() && makeList.empty()) ovaMessage = "Inga kortlekar än. Be Pluggis göra kort eller quiz av en läxa.";
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
  spokenAnswer = "";
  voiceVerdict = -1;
  options.clear();
  correctIdx = -1;
  if (answered >= ROUND) { noMoreCards = true; SFX_DONE(); return; }
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

static void showScreen(Screen s, bool force = false) {
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

static void layoutOva() {
  int top = TOP_H + 300, gap = 20;
  int ry = TOP_H + 70, rh = 80;
  btnMkPrev = {gap, ry, 100, rh, "<", PANEL};
  btnMkNext = {W - gap - 100, ry, 100, rh, ">", PANEL};
  int bw = (W - 7 * gap) / 6;
  for (int i = 0; i < 6; i++) btnMk[i] = {gap + i * (bw + gap), ry + rh + 16, bw, 92, MK_LABELS[i], ACCENT};
  int tw = (W - 4 * gap) / 3, th = (H - top - 3 * gap) / 2;
  for (int i = 0; i < 6; i++) deckTiles[i] = {gap + (i % 3) * (tw + gap), top + (i / 3) * (th + gap), tw, th, "", PANEL};
  int oh = 112, ow = (W - 3 * gap) / 2, oy = H - 2 * oh - 2 * gap;
  for (int i = 0; i < 4; i++) optBtn[i] = {gap + (i % 2) * (ow + gap), oy + (i / 2) * (oh + gap), ow, oh, "", PANEL};
  btnReveal = {gap, H - oh - gap, ow, oh, "Visa svar", PANEL};
  btnSay = {2 * gap + ow, H - oh - gap, ow, oh, "Håll in och svara", ACCENT};
  btnDidnt = {gap, H - oh - gap, ow, oh, "Kunde inte", BAD};
  btnKnew = {2 * gap + ow, H - oh - gap, ow, oh, "Kunde", GOOD};
}

static void drawTextButton(const Button& b, uint32_t color, const String& text) {
  auto& d = *G;
  d.fillRoundRect(b.x, b.y, b.w, b.h, 24, color);
  drawWrapped(text, b.x + 16, b.y + 10, b.w - 32, b.h - 20, INK, F_MID, true);
}

static void drawHwPlan();
static void drawGame();

static void drawOva() {
  auto& d = *G;
  d.fillRect(0, TOP_H, W, H - TOP_H, BG);
  if (screen == Screen::Game) { drawGame(); return; }
  if (screen != Screen::OvaList && screen != Screen::OvaCard) { drawHwPlan(); return; }
  if (screen == Screen::OvaList) {
    d.setFont(F_BIG);
    d.setTextColor(INK);
    d.drawString("Ny övning", 28, TOP_H + 24);
    if (!decksLoaded) { drawWrapped("Hämtar ...", 28, TOP_H + 120, W - 56, 80, MUTED, F_MID); return; }
    if (makeBusy.length()) { drawWrapped(makeBusy, 28, TOP_H + 120, W - 56, 300, ACCENT, F_BIG); return; }
    if (ovaMessage.length()) { drawWrapped(ovaMessage, 28, TOP_H + 120, W - 56, 200, MUTED, F_MID); return; }
    if (makeList.empty()) {
      drawWrapped("Lägg till en läxa eller ett prov för att göra snabbquiz och lucktext.", 28, btnMkPrev.y + 20, W - 56, 60, MUTED, F_SMALL);
    } else {
      if (makeList.size() > 1) { drawTextButton(btnMkPrev, PANEL, "<"); drawTextButton(btnMkNext, PANEL, ">"); }
      int lx = btnMkPrev.x + btnMkPrev.w + 16;
      drawWrapped(makeList[makeIdx].label, lx, btnMkPrev.y + 8, btnMkNext.x - 16 - lx, btnMkPrev.h - 16, INK, F_MID, true);
      for (int i = 0; i < 6; i++) drawTextButton(btnMk[i], i < 2 ? ACCENT : 0x2D5BA8, MK_LABELS[i]);
    }
    if (decks.empty()) return;
    d.setFont(F_SMALL);
    d.setTextColor(MUTED);
    d.drawString("Dina kortlekar", 28, deckTiles[0].y - 34);
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
  int qBottom = hasOptions ? optBtn[0].y - 20 : btnReveal.y - (spokenAnswer.length() ? 90 : 20);
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
    if (isLanguageDeck()) {
      Button full = {btnReveal.x, btnReveal.y, btnSay.x + btnSay.w - btnReveal.x, btnReveal.h, "Visa svar", ACCENT};
      drawTextButton(full, ACCENT, "Visa svar");
    } else {
      drawTextButton(btnReveal, PANEL, "Visa svar");
      drawTextButton(btnSay, recording ? WARN : ACCENT, recording ? "Släpp när du är klar" : "Håll in och svara");
    }
  } else {
    if (spokenAnswer.length()) {
      String v = voiceVerdict == 1 ? "Låter rätt!  " : voiceVerdict == 0 ? "Jämför med svaret.  " : "";
      drawWrapped(v + "Du sa: " + spokenAnswer, 48, btnDidnt.y - 70, W - 96, 60, voiceVerdict == 1 ? GOOD : WARN, F_SMALL, true);
    }
    drawTextButton(btnDidnt, BAD, "Kunde inte");
    drawTextButton(btnKnew, GOOD, "Kunde");
  }
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
static const char* deckGuessed = "";
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
  if (looksSwedish(text)) { sayAloud(text, ""); return; }
  const char* lang = deckLang();
  sayAloud(text, *lang ? lang : guessLang(text));
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
  int code = api("POST", "/api/flashcards/quiz", "{\"homeworkId\":" + String(homeworkId) + ",\"mode\":\"" + mode + "\",\"count\":10}", &doc, 120000);
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
  if (!d.id) { loadDecks(); dirtyOva = true; return; }
  openDeck(d);
}


// ---------------------------------------------------------------- Förklara, Testprov, Matcha par, Rätt ordning
// Samma övningar som i appen. Förklara och skrivfrågor i Testprovet besvaras med rösten (KB-Whisper).
static long gameHw = 0;
static String gameTitle, gameMsg, gameHeard;
static bool gameDone = false;
static Button gBtn[10], gSay, gNext;
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

static void layoutGame() {
  int gap = 20;
  gSay = {gap, H - 112 - gap, W - 2 * gap, 112, "", ACCENT};
  gNext = gSay;
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
  cur.name = String(MK_LABELS[which]) + ": " + title;
  cur.homeworkId = hw;
  JsonDocument doc;
  if (game == Game::Explain) {
    gameBusy("Pluggis väljer ett begrepp ur materialet ...");
    int code = api("POST", "/api/explain/start", "{\"homeworkId\":" + String(hw) + "}", &doc, 120000);
    gameMsg = code == 200 ? "" : "Det gick inte att starta just nu (" + String(code) + ").";
    if (code == 200) explainApply(doc);
  } else if (game == Game::Exam) {
    gameBusy("Pluggis gör ett testprov av materialet ...\n\nDet kan ta en minut.");
    int code = api("POST", "/api/exam/start", "{\"homeworkId\":" + String(hw) + ",\"count\":10}", &doc, 180000);
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
    gameBusy("Hämtar kort ...");
    pairs.clear();
    for (size_t i = 0; i < allDeckIds.size() && pairs.size() < 12; i++) {
      if (allDeckHw[i] != hw) continue;
      JsonDocument cards;
      if (api("GET", "/api/flashcards/decks/" + String(allDeckIds[i]) + "/cards", "", &cards) != 200) continue;
      for (JsonObject c : cards.as<JsonArray>()) {
        String a = clean(c["front"] | ""), b = clean(c["back"] | "");
        if (a.length() && b.length() && a.length() <= 70 && b.length() <= 70 && a.indexOf("___") < 0) pairs.push_back({a, b});
      }
    }
    for (int i = pairs.size() - 1; i > 0; i--) std::swap(pairs[i], pairs[esp_random() % (i + 1)]);
    // Variation: kort som var med de senaste gångerna hamnar sist
    static std::vector<String> recentMatch;
    std::stable_partition(pairs.begin(), pairs.end(), [](const Pair& p) {
      return std::find(recentMatch.begin(), recentMatch.end(), p.a) == recentMatch.end();
    });
    if (pairs.size() > 5) pairs.resize(5);
    for (auto& p : pairs) recentMatch.insert(recentMatch.begin(), p.a);
    if (recentMatch.size() > 15) recentMatch.resize(15);
    gameMsg = pairs.size() < 3 ? "Det behövs minst tre kort till läxan. Gör ett snabbquiz först." : "";
    rightOrder.clear();
    for (size_t i = 0; i < pairs.size(); i++) rightOrder.push_back(i);
    for (int i = rightOrder.size() - 1; i > 0; i--) std::swap(rightOrder[i], rightOrder[esp_random() % (i + 1)]);
    matched.assign(pairs.size(), false);
    selL = -1;
    matchStart = millis();
  } else {
    gameBusy("Pluggis gör ordningar ur materialet ...");
    int code = api("POST", "/api/games/order", "{\"homeworkId\":" + String(hw) + "}", &doc, 120000);
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
    if (gameDone) { SFX_DONE(); gameDoneReport("explain"); }
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
  if (*l) { sayAloud(t, looksSwedish(t) ? "" : l); return; }
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
  examMax = doc["maxPoints"] | 0;
  examPct = doc["percent"] | 0;
  examReview.clear();
  for (JsonVariant v : doc["review"].as<JsonArray>()) examReview.push_back(clean(v.as<const char*>()));
}

static void drawGame() {
  auto& d = *G;
  d.setFont(F_SMALL);
  d.setTextColor(MUTED);
  d.drawString(gameTitle, 28, TOP_H + 14);
  if (gameMsg.length()) { drawWrapped(gameMsg + (gameMsg.endsWith("...") || gameMsg.indexOf("...\n") >= 0 ? "" : "\n\nTryck för att gå tillbaka."), 48, TOP_H + 90, W - 96, 400, ACCENT, F_BIG, true); return; }
  int top = TOP_H + 56;
  bool talk = false;
  String talkLabel = "Håll in och svara";

  if (game == Game::Explain) {
    d.setFont(F_MID);
    d.setTextColor(ACCENT);
    d.drawString("Förklara: " + exConcept, 28, top);
    int y = top + 52;
    if (gameHeard.length()) { drawWrapped("Du: " + gameHeard, 28, y, W - 56, 64, MUTED, F_SMALL); y += 68; }
    if (!gameDone) {
      drawWrapped(exReply, 28, y, W - 56, gSay.y - y - 20, INK, F_BIG);
      talk = true;
      talkLabel = "Håll in och förklara";
    } else {
      drawWrapped(exReply, 28, y, W - 56, 110, INK, F_MID);
      y += 120;
      for (auto& c : exCovered) { if (y > H - 60) break; drawWrapped("OK  " + c, 48, y, W - 96, 40, GOOD, F_SMALL); y += 40; }
      for (auto& m : exMissing) { if (y > H - 60) break; drawWrapped("+  " + m, 48, y, W - 96, 40, WARN, F_SMALL); y += 40; }
      drawWrapped("Tryck för att gå tillbaka.", 28, H - 50, W - 56, 40, MUTED, F_SMALL, true);
    }
  } else if (game == Game::Exam) {
    if (gameDone) {
      drawWrapped("Testprov klart!\n" + String(examPoints) + " av " + String(examMax) + " poäng (" + String(examPct) + " %)", 48, top + 10, W - 96, 150, GOOD, F_BIG, true);
      int y = top + 180;
      if (!examReview.empty()) { drawWrapped("Det här ska du kolla igen:", 48, y, W - 96, 40, INK, F_MID); y += 50; }
      for (auto& r : examReview) { if (y > H - 80) break; drawWrapped("-  " + r, 68, y, W - 136, 40, WARN, F_SMALL); y += 40; }
      drawWrapped("Rätta svar finns i Pluggis-appen. Tryck för att gå tillbaka.", 28, H - 50, W - 56, 40, MUTED, F_SMALL, true);
      return;
    }
    auto& q = examQs[examI];
    d.setFont(F_SMALL);
    d.setTextColor(MUTED);
    d.setTextDatum(top_right);
    d.drawString("Fråga " + String(examI + 1) + " av " + String(examQs.size()), W - 28, TOP_H + 14);
    d.setTextDatum(top_left);
    bool hasChoices = q.choices.size() >= 2;
    int gap = 20, oh = 104, ow = (W - 3 * gap) / 2, oy = H - 2 * oh - 2 * gap;
    drawWrapped(q.question, 48, top, W - 96, (hasChoices ? oy : gSay.y - 80) - top - 10, INK, F_BIG, true);
    if (hasChoices) {
      for (size_t i = 0; i < q.choices.size() && i < 4; i++) {
        gBtn[i] = {gap + (int)(i % 2) * (ow + gap), oy + (int)(i / 2) * (oh + gap), ow, oh, "", PANEL};
        drawTextButton(gBtn[i], examAns[examI] == q.choices[i] ? ACCENT : PANEL, q.choices[i]);
      }
    } else {
      if (examAns[examI].length()) drawWrapped("Ditt svar: " + examAns[examI], 48, gSay.y - 76, W - 96, 60, GOOD, F_SMALL, true);
      int half = (W - 3 * gap) / 2;
      gBtn[0] = {gap, gSay.y, half, gSay.h, "", ACCENT};
      gBtn[1] = {2 * gap + half, gSay.y, half, gSay.h, "", PANEL};
      drawTextButton(gBtn[0], recording ? WARN : ACCENT, recording ? "Släpp när du är klar" : "Håll in och svara");
      drawTextButton(gBtn[1], PANEL, examAns[examI].length() ? (examI + 1 < (int)examQs.size() ? "Nästa" : "Lämna in") : "Hoppa över");
    }
    return;
  } else if (game == Game::Match) {
    int n = pairs.size(), gap = 14, colW = (W - 3 * 20) / 2;
    int rowH = (H - top - 20 - (n - 1) * gap) / n;
    bool all = std::all_of(matched.begin(), matched.end(), [](bool b) { return b; });
    for (int i = 0; i < n; i++) {
      gBtn[i] = {20, top + i * (rowH + gap), colW, rowH, "", PANEL};
      int j = rightOrder[i];
      gBtn[5 + i] = {40 + colW, top + i * (rowH + gap), colW, rowH, "", PANEL};
      bool wrongNow = millis() < gWrongUntil;
      uint32_t cl = matched[i] ? 0x1E5A3C : (selL == i ? ACCENT : (wrongNow && gWrongA == i) ? BAD : PANEL);
      uint32_t cr = matched[j] ? 0x1E5A3C : (wrongNow && gWrongB == i) ? BAD : PANEL;
      drawTextButton(gBtn[i], cl, pairs[i].a);
      drawTextButton(gBtn[5 + i], cr, pairs[j].b);
    }
    if (all) {
      d.fillRoundRect(W / 2 - 300, H / 2 - 70, 600, 140, 28, GOOD);
      drawWrapped("Klart på " + String((millis() - matchStart) / 1000) + " s!", W / 2 - 280, H / 2 - 50, 560, 100, BG, F_BIG, true);
    }
    return;
  } else if (game == Game::Order) {
    auto& sq = seqs[seqI];
    d.setFont(F_MID);
    d.setTextColor(ACCENT);
    d.drawString(sq.title + "  (" + String(seqI + 1) + " av " + String(seqs.size()) + ")", 28, top);
    drawWrapped(sq.prompt, 28, top + 46, W - 56, 50, MUTED, F_SMALL);
    int y = top + 100;
    // de som är på plats
    for (int k = std::max(0, placed - 3); k < placed; k++) { drawWrapped(String(k + 1) + ". " + sq.steps[k], 48, y, W - 96, 38, GOOD, F_SMALL); y += 38; }
    if (placed >= (int)sq.steps.size()) {
      drawWrapped("Rätt ordning!\n" + sq.why, 48, y + 10, W - 96, H - y - 80, INK, F_MID, true);
      drawWrapped("Tryck för att fortsätta.", 28, H - 50, W - 56, 40, MUTED, F_SMALL, true);
      return;
    }
    int gap = 16, cols = 2, bw = (W - 3 * 20) / 2;
    int rows = (shownSteps.size() + 1) / 2;
    int bh = std::min(100, (H - y - 30 - (rows - 1) * gap) / std::max(1, rows));
    for (size_t k = 0; k < shownSteps.size() && k < 10; k++) {
      gBtn[k] = {20 + (int)(k % cols) * (bw + 20), y + 10 + (int)(k / cols) * (bh + gap), bw, bh, "", PANEL};
      bool wrongNow = millis() < gWrongUntil && gWrongA == (int)k;
      drawTextButton(gBtn[k], wrongNow ? BAD : PANEL, sq.steps[shownSteps[k]]);
    }
    return;
  }
  if (talk) drawTextButton(gSay, recording ? WARN : ACCENT, recording ? "Släpp när du är klar" : talkLabel);
}

static void gameExit() {
  if (recording) stopRecording(false);
  practiceEnd();
  game = Game::None;
  screen = Screen::Coach;
  showScreen(Screen::OvaList);
}

static void gameTouch(int x, int y) {
  lastTouchAt = millis();
  bool onText = y > TOP_H + 40 && y < TOP_H + 260 && !gameMsg.length() && !gameDone;
  if (onText && game == Game::Exam) { examSpoken = -1; examSpeak(); return; }
  if (onText && game == Game::Explain) { sayAloud(exReply); return; }
  if (gameMsg.length()) { if (!gameMsg.endsWith("...")) gameExit(); return; }
  if (game == Game::Explain) {
    if (gameDone) { gameExit(); return; }
    if (inside(gSay, x, y)) startRecording();
  } else if (game == Game::Exam) {
    if (gameDone) { gameExit(); return; }
    auto& q = examQs[examI];
    if (q.choices.size() >= 2) {
      for (size_t i = 0; i < q.choices.size() && i < 4; i++) {
        if (!inside(gBtn[i], x, y)) continue;
        examAns[examI] = q.choices[i];
        dirtyOva = true;
        redraw();
        delay(250);
        if (++examI >= (int)examQs.size()) { examI = examQs.size() - 1; examSubmit(); }
        dirtyOva = true;
        return;
      }
    } else if (inside(gBtn[0], x, y)) {
      startRecording();
    } else if (inside(gBtn[1], x, y)) {
      if (++examI >= (int)examQs.size()) { examI = examQs.size() - 1; examSubmit(); }
      dirtyOva = true;
    }
  } else if (game == Game::Match) {
    if (std::all_of(matched.begin(), matched.end(), [](bool b) { return b; })) { gameExit(); return; }
    int n = pairs.size();
    for (int i = 0; i < n; i++) {
      if (inside(gBtn[i], x, y) && !matched[i]) { selL = i; dirtyOva = true; return; }
      if (inside(gBtn[5 + i], x, y)) {
        int j = rightOrder[i];
        if (matched[j] || selL < 0) return;
        if (j == selL) {
          matched[j] = true;
          SFX_OK();
          if (std::all_of(matched.begin(), matched.end(), [](bool b) { return b; })) { SFX_DONE(); gameDoneReport("match"); }
        } else {
          gWrongA = selL; gWrongB = i; gWrongUntil = millis() + 600;
          SFX_BAD();
        }
        selL = -1;
        dirtyOva = true;
        return;
      }
    }
  } else if (game == Game::Order) {
    auto& sq = seqs[seqI];
    if (placed >= (int)sq.steps.size()) {
      if (++seqI >= (int)seqs.size()) { gameDoneReport("order"); gameExit(); return; }
      placed = 0;
      shownSteps.clear();
      for (size_t i = 0; i < seqs[seqI].steps.size(); i++) shownSteps.push_back(i);
      for (int i = shownSteps.size() - 1; i > 0; i--) std::swap(shownSteps[i], shownSteps[esp_random() % (i + 1)]);
      sayAloud(seqs[seqI].title + ". " + seqs[seqI].prompt);
      dirtyOva = true;
      return;
    }
    for (size_t k = 0; k < shownSteps.size() && k < 10; k++) {
      if (!inside(gBtn[k], x, y)) continue;
      if (shownSteps[k] == placed) {
        placed++;
        shownSteps.erase(shownSteps.begin() + k);
        if (placed >= (int)sq.steps.size()) { SFX_DONE(); sayAloud("Rätt ordning! " + sq.why); } else SFX_OK();
      } else {
        gWrongA = k; gWrongUntil = millis() + 600;
        SFX_BAD();
      }
      dirtyOva = true;
      return;
    }
  }
}

static void ovaTouch(int x, int y) {
  lastTouchAt = millis();
  if (screen == Screen::OvaCard && !noMoreCards && chosen < 0 && y > TOP_H + 40 && y < TOP_H + 260) {
    if (revealed && *deckLang()) sayCardSide(looksSwedish(qFront) ? qBack : qFront);
    else sayCardSide(revealed ? qFront + ". " + qBack : qFront);
    return;
  }
  if (screen == Screen::OvaList) {
    if (!makeList.empty() && !makeBusy.length()) {
      if (makeList.size() > 1 && inside(btnMkPrev, x, y)) { makeIdx = (makeIdx + makeList.size() - 1) % makeList.size(); dirtyOva = true; return; }
      bool onLabel = y >= btnMkPrev.y && y < btnMkPrev.y + btnMkPrev.h && x > btnMkPrev.x + btnMkPrev.w && x < btnMkNext.x;
      if (makeList.size() > 1 && inside(btnMkPrev, x, y)) { makeIdx = (makeIdx + makeList.size() - 1) % makeList.size(); dirtyOva = true; return; }
      if (makeList.size() > 1 && (inside(btnMkNext, x, y) || onLabel)) { makeIdx = (makeIdx + 1) % makeList.size(); dirtyOva = true; return; }
      for (int i = 0; i < 6; i++) {
        if (!inside(btnMk[i], x, y)) continue;
        long hw = makeList[makeIdx].id;
        if (i == 0) makeQuiz(hw, "quiz");
        else if (i == 1) makeQuiz(hw, "cloze");
        else startGame(i, hw, makeList[makeIdx].label);
        return;
      }
    }
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
        if (ok) SFX_OK(); else SFX_BAD();
        if (*deckLang()) { delay(450); sayCardSide(looksSwedish(qFront) ? qBack : qFront); }
        dirtyOva = true;
        redraw();
        grade(ok);
        dirtyOva = true;
        return;
      }
    }
  } else if (!revealed) {
    if (isLanguageDeck()) { if (y >= btnReveal.y) { revealed = true; dirtyOva = true; sayCardSide(looksSwedish(qFront) ? qBack : qFront); } }
    else if (inside(btnReveal, x, y)) { revealed = true; dirtyOva = true; }
    else if (inside(btnSay, x, y)) startRecording();
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
  if (!playing.data && playQueue.empty() && fetchPos >= (int)sents.size() && fetchedIdx < 0) {
    listening = false;
    listenDone = true;
    listenPos = sents.size();
    listenSave();
    practiceEnd();
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
  if (!ready && idle && playQueue.size() < 1 && fetchPos < (int)sents.size()) {
    fetchWantIdx = fetchPos;
    fetchWantGen = fetchGen;
    fetchWantText = sents[fetchPos].text;
    {
      const char* l = langFrom(hwCur.subject + " " + hwCur.title);
      fetchWantLang = looksSwedish(fetchWantText) ? "" : (*l ? l : guessLang(fetchWantText));
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
  btnPrev = {gap * 2 + 220, by, 220, bh, "Förra sida", PANEL};
  btnPlay = {gap * 3 + 440, by, W - 5 * gap - 440 - 240, bh, "Spela", ACCENT};
  btnNextCh = {W - gap - 240, by, 240, bh, "Nästa sida", PANEL};
  int rh = 66, ry = TOP_H + 100;
  for (int i = 0; i < 7; i++) planRowBtn[i] = {gap, ry + i * (rh + 10), W - 2 * gap, rh, "", PANEL};
  btnDayPrev = {gap, TOP_H + 16, 90, 70, "<", PANEL};
  btnDayNext = {W - gap - 90, TOP_H + 16, 90, 70, ">", PANEL};
}

static void drawHwPlan() {
  auto& d = *G;
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
      drawTextButton(btnPrev, PANEL, "Förra sida");
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
        SFX_OK();
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
  frame.setPsram(true);
  frame.setColorDepth(16);
  if (frame.createSprite(M5.Display.width(), M5.Display.height())) G = &frame;
  else Serial.println("[pluggis] ingen bild i minnet – ritar direkt");
  prefs.begin("pluggis", false);
  volume = prefs.getInt("volume", PLUGGIS_VOLUME);
  layout();
  layoutOva();
  layoutHwPlan();
  layoutGame();
  fetchLock = xSemaphoreCreateMutex();
  xTaskCreatePinnedToCore(fetchTask, "pluggis-fetch", 12288, nullptr, 1, nullptr, 0);
  dirtyAll = true;
  redraw();

  recBuf = (int16_t*)heap_caps_malloc(44 + REC_MAX_SAMPLES * 2, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (!recBuf) Serial.println("[pluggis] kunde inte reservera minne för inspelning");

  M5.Mic.end();
  M5.Speaker.begin();
  M5.Speaker.setVolume(volume);

  lastActivity = millis();
  connectWifi();
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
  if (idleWarned) { idleWarned = false; M5.Display.setBrightness(200); dirtyAll = true; }
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
    auto& d = *G;
    d.fillRoundRect(W / 2 - 360, H / 2 - 80, 720, 160, 28, WARN);
    d.setFont(F_BIG);
    d.setTextColor(BG);
    d.setTextDatum(middle_center);
    d.drawString("Stänger av om 30 s", W / 2, H / 2 - 24);
    d.setFont(F_MID);
    d.drawString("Tryck för att fortsätta", W / 2, H / 2 + 30);
    d.setTextDatum(top_left);
    present();
  }
  if (idle > limit) goToSleep();
}

void loop() {
  M5.update();
  ws.loop();

  static wl_status_t lastWifi = WL_IDLE_STATUS;
  if (WiFi.status() != lastWifi) { lastWifi = WiFi.status(); dirtyTop = true; }

  // Pekskärmen
  auto t = M5.Touch.getDetail();
  if (t.wasPressed() && (asleep || idleWarned)) {
    checkIdle(true);
    return;   // trycket väcker bara, gör inget annat
  }
  checkIdle(t.wasPressed() || t.isPressed());
  if (t.wasPressed() && t.y < TOP_H) {
    if (inside(tabCoach, t.x, t.y)) showScreen(Screen::Coach);
    else if (inside(tabOva, t.x, t.y)) showScreen(Screen::OvaList);
    else if (inside(tabHw, t.x, t.y)) showScreen(Screen::HwList);
    else if (inside(tabPlan, t.x, t.y)) showScreen(Screen::Plan);
    else if (inside(btnVolDown, t.x, t.y)) setVolume(volume - 32);
    else if (inside(btnVolUp, t.x, t.y)) setVolume(volume + 32);
  } else if (t.wasPressed() && screen == Screen::Game) {
    gameTouch(t.x, t.y);
  } else if (t.wasPressed() && (screen == Screen::OvaList || screen == Screen::OvaCard)) {
    ovaTouch(t.x, t.y);
  } else if (t.wasPressed() && screen != Screen::Coach) {
    hwPlanTouch(t.x, t.y);
  } else if (t.wasPressed()) {
    if (inside(btnTalk, t.x, t.y)) startRecording();
    else if (inside(btnStart, t.x, t.y)) { drawButton(btnStart, true); present(); sendButton(sessionActive ? "double" : "click"); delay(80); drawButton(btnStart); present(); }
    else if (inside(btnEnd, t.x, t.y)) {
      drawButton(btnEnd, true, "Avslutar ...");
      present();
      stopPlayback();
      sendButton("long");
      delay(150);
      drawButton(btnEnd);
      present();
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
  pumpSay();
  pumpMelody();
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

  // Animationer och nedräkning
  static uint32_t lastTick = 0;
  if (millis() - lastTick > 400) {
    lastTick = millis();
    if (thinking && mode == Mode::Coach) dirtyDots = true;
    static bool wasWrong = false;
    bool wrongNow = millis() < gWrongUntil;
    if (wasWrong && !wrongNow && screen == Screen::Game) dirtyOva = true;
    wasWrong = wrongNow;
    if (sessionActive) dirtyTimer = true;
    if (pointsBanner.length() && millis() > pointsBannerUntil) { pointsBanner = ""; dirtyMain = true; }
  }
  if (dirtyAll || dirtyTop || dirtyMain || dirtyTimer || dirtyDots || dirtyOva) redraw();
  delay(2);
}
