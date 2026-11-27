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
  Status,         // tryck på WiFi-ikonen/"Ansluter" uppe till höger: visa anslutningen
  // WiFi
  WifiOpen,       // öppna WiFi-skärmen (från statusrutan eller startskärmen)
  WifiScan,       // sök efter nätverk igen
  WifiPick,       // a = nätverk i listan
  WifiManual,     // dolt nätverk: skriv namnet själv
  WifiConnect,    // anslut med lösenordet i rutan
  WifiBack,       // tillbaka (från lösenordet till listan, från listan till Öva)
  // Inställningar
  SetOpen,        // öppna inställningarna (ikonen uppe till höger)
  SetWifi, SetServer, SetCode,
  SetIdle,        // a = minuter (0 = aldrig)
  SetClock,       // klockläget på laddaren: a = 0 av, 1 pluggtid (15–21), 2 hela dagen (7–22)
  SetBright,      // a = ljusstyrka 30–255 medan reglaget dras
  SetBrightDone,  // a = ljusstyrka när reglaget släpps (sparas)
  SetDetails,     // visa anslutningsdetaljer
  SetFwCheck,     // leta efter ny firmware
  SetFwUpdate,    // installera den nya firmwaren
  SetRestart,
  SetPowerOff,
  SetBack,
  EntryOk, EntryBack,   // textrutan (elevkod, serveradress)
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
  PlanPrev, PlanNext, PlanRow, PlanCheck,
  PlanTimer,      // a = rad: starta fokustimern för momentet
  // Fokustimern
  FocusTop,       // timern i toppraden: visa den som går, annars välj längd
  FocusStart,     // a = minuter (fritt pass)
  FocusPause,     // paus / fortsätt
  FocusEnd,       // avsluta passet i förtid
  FocusBreak,     // 5 minuters paus efter passet
  FocusAgain,     // ett pass till (samma sak)
  FocusDone,      // klar: tillbaka
  // Chat
  CoachStart, CoachEnd,
};

struct Action {
  Act act = Act::None;
  int a = 0;
};

/** Engelska: texterna översätts när de ritas (elevens språk från servern). Svenska är standard. */
void setEnglish(bool on);
bool isEnglish();
/** Svenska → engelska om engelska är på, annars samma text. */
std::string tr(const std::string& s);

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
  int focusLeft = -1;        // fokustimern: sekunder kvar (-1 = ingen timer igång)
  bool focusPaused = false;
};
void setTop(const Top& t);
/** Bara timern i toppraden (varje sekund, utan att resten ritas om). */
void topTimer(int left, bool paused);

// ---------------------------------------------------------------- startskärm
void showBoot(const std::string& line, const std::string& warn, bool wifiButton = false);
void hideBoot();

// ---------------------------------------------------------------- gemensamt
/** "Hämtar …", "Pluggis gör ett snabbquiz …" eller ett fel: en ruta mitt på skärmen. busy = snurra. */
void buildMessage(const std::string& text, bool busy, const std::string& hint = "");
/** Liten notis uppe till höger som glider in och försvinner ("+5"). */
void toast(const std::string& text);
/** En ruta med text över allt annat (t.ex. anslutningsstatus); stängs med ett tryck (eller info("", "")). */
void info(const std::string& title, const std::string& text, const std::string& button = "", Act act = Act::None);
/** "Stänger av om 30 s" över allt annat. */
void idleWarning(bool show);
/** Håll-in-knappen på skärmen just nu: byt text och färg utan att bygga om skärmen (trycket pågår). */
void setTalking(bool on);

// ---------------------------------------------------------------- WiFi
struct WifiNet {
  std::string ssid;
  int rssi = -100;           // signal i dBm
  bool secure = true;        // kräver lösenord
  bool current = false;      // det nätverk Tab5 är ansluten till nu
};
struct Wifi {
  bool scanning = false;
  std::vector<WifiNet> nets;
  std::string status;        // "Ansluten till femtifem", "Ansluter …", "Fel lösenord?"
  Tone statusTone = Tone::Muted;
  bool busy = false;         // ansluter just nu
  // lösenordsvyn (visas när ssid inte är tomt, eller manual)
  std::string ssid;
  bool manual = false;       // dolt nätverk: namnet skrivs i en egen ruta
};
void buildWifi(const Wifi& m);
/** Det eleven skrivit i lösenordsrutan (och namnrutan för ett dolt nätverk). */
std::string wifiPassword();
std::string wifiSsidInput();
/** Byter statusraden på WiFi-skärmen utan att bygga om (tangentbordet och texten ligger kvar). */
void wifiStatus(const std::string& text, Tone tone, bool busy);

// ---------------------------------------------------------------- inställningar
struct Settings {
  std::string wifi;          // nätverkets namn ("" = inte ansluten)
  int rssi = -100;
  std::string server;        // "192.168.1.20:8080"
  bool serverOk = false;     // chatten (WebSocket) uppe
  std::string serverNote;    // "Ansluten" eller senaste fel
  std::string code, student; // "YQE89", "Fred"
  int idleMin = 5;           // 0 = aldrig
  int brightness = 200;      // 30–255
  int clockMode = 1;         // 0 av, 1 pluggtid, 2 hela dagen
  // firmware (uppdatering över WiFi)
  std::string fwVersion;     // den som körs, t.ex. "2026.11.27.1430-ab12cd3"
  std::string fwNote;        // "Du har den senaste", "Ny version: …", fel
  Tone fwTone = Tone::Muted;
  bool fwBusy = false;       // letar eller laddar ner
  std::string fwNew;         // ny version som kan installeras ("" = ingen)
  std::string version;
};
void buildSettings(const Settings& m);
/** Nedladdningen av ny firmware: förloppet på inställningsskärmen (0–100) och en rad text. */
void firmwareProgress(int percent, const std::string& text);

/** En textruta med tangentbord (elevkod, serveradress). */
struct Entry {
  std::string title, hint, value, placeholder;
  bool code = false;         // elevkod: 5 tecken, stora bokstäver och siffror
};
void buildEntry(const Entry& m);
std::string entryText();
/** Statusraden på WiFi- och textskärmen. */
void entryStatus(const std::string& text, Tone tone, bool busy);

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
  bool language = false;             // språkläxa: Glosförhör i stället för Förklara
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
  std::string talk;          // texten på håll-in-knappen ("" = "Håll in och svara")
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
  int action = 0;            // tryck på raden: 0 bockar av, 1 läser upp sidorna, 2 startar övningen
};
struct Plan {
  std::string day, summary;  // "Imorgon", "25 min kvar av 40"
  int doneMinutes = 0, totalMinutes = 0;
  bool hasPrev = false, hasNext = false;
  std::vector<PlanItem> items;
};
void buildPlan(const Plan& m);

// ---------------------------------------------------------------- Fokustimern
struct Focus {
  bool choose = false;       // välj längd (fritt pass)
  std::string subject, title;
  uint32_t color = ACCENT;
  int total = 0, left = 0;   // sekunder
  bool paused = false;
  bool done = false;         // passet är slut: belöning
  bool onBreak = false;      // 5 minuters paus pågår (eller är slut)
  std::string reward;        // "25 min plugg · bockad i planen"
};
void buildFocus(const Focus& m);
/** Nedräkningen uppdateras på plats (varje sekund). */
void focusTick(int left, int total, bool paused, bool onBreak);

// ---------------------------------------------------------------- Klockläget (på laddaren, när ingen rör den)
struct Clock {
  std::string time, date;    // "16:42", "Fredag 27 november"
  std::string next, nextWhen;// "NO: Fotosyntesen", "om 3 dagar"
  bool nextSoon = false;     // idag eller imorgon (gul)
  std::string today;         // "2 av 4 kvar · 25 min" eller "Allt klart idag!"
  std::vector<std::string> items;   // det som är kvar idag (högst 3)
};
/** Visar klockan över allt annat (nullptr = stäng). Kan anropas varje minut. */
void clockShow(const Clock* m);

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
