// Pluggis på Tab5 – gränssnittet (LVGL 9).
//
// Den här delen vet ingenting om WiFi, ljud eller servern. main.cpp fyller i en modell per skärm
// (vad som ska synas) och anropar build…(); det eleven trycker på kommer tillbaka som Action i en kö
// som main.cpp läser efter lv_timer_handler(). Samma kod körs i simulatorn (tools/sim) på datorn.
#pragma once

#include <lvgl.h>
#include <string>
#include <vector>

namespace ui {

// ---------------------------------------------------------------- färger (Pluggis-loggan: mörkblått och gult)
constexpr uint32_t BG = 0x021A44;
constexpr uint32_t SURFACE = 0x0D2C63;
constexpr uint32_t SURFACE2 = 0x173C7A;
constexpr uint32_t LINE = 0x2A5096;
constexpr uint32_t INK = 0xF2F5F8;
constexpr uint32_t MUTED = 0x9DB0CC;
constexpr uint32_t ACCENT = 0x2F62C8;   // knappar och vald flik
constexpr uint32_t ACCENT2 = 0x1E4699;
constexpr uint32_t YELLOW = 0xFEC434;   // loggans gula: framsteg, markeringar
constexpr uint32_t GOOD = 0x34C77B;
constexpr uint32_t BAD = 0xF0605D;
constexpr uint32_t WARN = 0xF5B544;

enum class Tone { Ink, Muted, Accent, Good, Bad, Warn };

// ---------------------------------------------------------------- vad eleven gjorde
enum class Act : uint16_t {
  None,
  Tab,            // a = 0 Öva, 1 Chat, 2 Läxor, 3 Plan
  VolDown, VolUp,
  Background,     // tryck på en tom yta (t.ex. "Tryck för att gå tillbaka")
  Status,         // tryck på "Ansluter"/"Inget WiFi" uppe till höger: visa varför
  TalkPress,      // håll in och prata/svara (släppet läses direkt från pekskärmen)
  // Öva
  OvaSelect,      // a = rad i läxlistan
  OvaFocus,       // a = delområde (0 = Allt)
  OvaExercise,    // a = 0 Snabbquiz … 5 Rätt ordning
  OvaAgain,
  // Ett kort
  CardSpeak, CardOption, CardNext, CardReveal, CardKnew, CardDidnt,
  // Förklara, Testprov, Matcha par, Rätt ordning
  GameSpeak, GameChoice, GameSkip, GameLeft, GameRight, GameStep,
  // Läxor och plan
  HwOpen, HwBack, HwPrev, HwPlay, HwNext,
  PlanPrev, PlanNext, PlanRow,
  // Chat
  CoachStart, CoachEnd,
};

struct Action {
  Act act = Act::None;
  int a = 0;
};

/** Sätter upp tema, typsnitt och den fasta toppraden. Anropas en gång efter lv_init() och skärmen. */
void init();
/** Loggan (startskärmen) och figuren (chatten) som PNG i minnet, från include/brand.h. */
void setBrand(const uint8_t* logo, uint32_t logoLen, const uint8_t* avatar, uint32_t avatarLen);
/** Nästa tryck i kön (false när kön är tom). */
bool poll(Action& out);
/** Tar bort allt som ligger i kön (t.ex. när ett tryck bara ska väcka skärmen). */
void clearQueue();

// ---------------------------------------------------------------- toppraden (finns alltid utom på startskärmen)
struct Top {
  int tab = 0;               // aktiv flik: 0 Öva, 1 Chat, 2 Läxor, 3 Plan
  std::string status;        // "Inget WiFi", "Ansluter" – tomt när allt är bra
  int volume = 128;          // 0–255
  int battery = -1;          // -1 = okänt
  bool charging = false;
  int points = -1;           // -1 = visa inte
};
void setTop(const Top& t);

// ---------------------------------------------------------------- startskärm
void showBoot(const std::string& line, const std::string& warn);
void hideBoot();

// ---------------------------------------------------------------- gemensamt
/** "Hämtar …", "Pluggis gör ett snabbquiz …" eller ett fel: en ruta mitt på skärmen. busy = snurra. */
void buildMessage(const std::string& text, bool busy, const std::string& hint = "");
/** Liten notis uppe till höger som glider in och försvinner ("+5"). */
void toast(const std::string& text);
/** En ruta med text över allt annat (t.ex. anslutningsstatus); stängs med ett tryck (eller info("", "")). */
void info(const std::string& title, const std::string& text);
/** "Stänger av om 30 s" över allt annat. */
void idleWarning(bool show);
/** Håll-in-knappen på skärmen just nu: byt text och färg utan att bygga om skärmen (trycket pågår). */
void setTalking(bool on);

// ---------------------------------------------------------------- Öva
struct OvaRow {
  std::string title;         // "Prov: Glosförhör kap 3"
  std::string when;          // "Imorgon", "börja nu", "försenad"
  Tone whenTone = Tone::Muted;
  uint32_t color = ACCENT;   // ämnets färg
  bool soon = true;          // under "Snart" (annars "Längre fram")
};
struct Ova {
  std::vector<OvaRow> rows;
  int selected = 0;
  std::string title;         // "Prov: Fotosyntesen"
  std::string meta;          // "NO · Fredag 28/11"
  uint32_t color = ACCENT;
  bool topicsLoading = false;
  std::vector<std::string> topics;   // utan "Allt"
  int focus = 0;                     // 0 = Allt
  std::string last;                  // "Snabbquiz · Fotosyntesen · 8 av 10 rätt"
  bool canAgain = false;
};
void buildOva(const Ova& m);

// ---------------------------------------------------------------- ett kort (snabbquiz, lucktext, flashkort)
struct Card {
  std::string deck;          // "Snabbquiz: Fotosyntesen"
  int answered = 0, round = 10, right = 0;
  bool done = false;
  std::string doneText;      // "Bra jobbat! 8 av 10 rätt."
  std::string question, hint, answer;
  std::vector<std::string> options;
  int chosen = -1, correct = -1;
  bool revealed = false;
  bool languageDeck = false; // bara "Visa svar" (rösten hör inte utländska ord)
  std::string spoken;        // vad eleven sa
  int verdict = -1;          // 1 låter rätt, 0 jämför själv
  bool recording = false;
};
void buildCard(const Card& m);

// ---------------------------------------------------------------- Förklara, Testprov, Matcha par, Rätt ordning
enum class CellState { Normal, Selected, Matched, Wrong };
struct Game {
  int kind = 0;              // 2 Förklara, 3 Testprov, 4 Matcha par, 5 Rätt ordning
  std::string title;
  bool recording = false;
  // Förklara
  std::string term, heard, reply;
  bool done = false;
  std::vector<std::string> covered, missing;
  // Testprov
  int qIndex = 0, qCount = 0;
  std::string question;
  std::vector<std::string> choices;
  std::string answer;        // valt alternativ eller det eleven sa
  bool lastQuestion = false;
  int points = 0, maxPoints = 0, percent = 0;
  std::vector<std::string> review;
  // Matcha par
  std::vector<std::string> left, right;
  std::vector<CellState> leftState, rightState;
  std::string doneText;      // "Klart på 23 s!"
  // Rätt ordning
  std::string seqTitle, prompt, why;
  int seqIndex = 0, seqCount = 0;
  std::vector<std::string> placed, steps;
  int wrongStep = -1;
};
void buildGame(const Game& m);

// ---------------------------------------------------------------- Läxor
struct HwTile {
  std::string kind;          // "Prov · NO"
  bool test = false;
  std::string title, due;
  uint32_t color = ACCENT;
};
void buildHwList(const std::vector<HwTile>& tiles);

struct HwDetail {
  std::string meta, title, about;
  bool test = false;
  bool hasMaterial = false;
  std::string head;          // "Sida 42 · 2 av 5" eller "Allt uppläst!"
  int percent = 0;
  std::string line;          // meningen som läses (eller en hjälptext)
  bool listening = false;
  std::string playLabel;     // "Spela", "Fortsätt", "Paus"
};
void buildHwDetail(const HwDetail& m);

// ---------------------------------------------------------------- Plan
struct PlanItem {
  std::string subject, task;
  uint32_t color = ACCENT;
  int minutes = 0;
  bool done = false;
};
struct Plan {
  std::string day, summary;  // "Imorgon", "25 min kvar av 40"
  int doneMinutes = 0, totalMinutes = 0;
  bool hasPrev = false, hasNext = false;
  std::vector<PlanItem> items;
};
void buildPlan(const Plan& m);

// ---------------------------------------------------------------- Chat (coachen)
struct Coach {
  bool cardMode = false;
  std::string say, heard;
  bool thinking = false;
  // kort från coachen
  std::string front, hint, back;
  bool answered = false, correct = false;
  int box = 0;
  // passet
  bool session = false;
  bool recording = false;
};
void buildCoach(const Coach& m);
/** Timern för passet uppdateras på plats (varje sekund). */
void coachTimer(bool visible, int left, int total, const std::string& task);

}  // namespace ui
