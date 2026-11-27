// Simulator för Tab5-gränssnittet: ritar varje skärm med testdata till PNG-filer, utan hårdvara.
// Kör: ./sim/run.sh   (bilderna hamnar i sim/out/)
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <ctime>

#include "../src/ui.h"

static const int W = 1280, H = 720;
static uint16_t fb[W * H];
static uint32_t fakeMs = 0;
static uint32_t tick() { return fakeMs; }

static void flush(lv_display_t* d, const lv_area_t* a, uint8_t* px) {
  int w = a->x2 - a->x1 + 1;
  for (int y = a->y1; y <= a->y2; y++) memcpy(&fb[y * W + a->x1], px + (y - a->y1) * w * 2, w * 2);
  lv_display_flush_ready(d);
}

static std::vector<uint8_t> readFile(const char* path) {
  std::vector<uint8_t> v;
  FILE* f = fopen(path, "rb");
  if (!f) return v;
  uint8_t b[4096];
  size_t n;
  while ((n = fread(b, 1, sizeof(b), f)) > 0) v.insert(v.end(), b, b + n);
  fclose(f);
  return v;
}

static void run(uint32_t ms) {
  for (uint32_t t = 0; t < ms; t += 10) { fakeMs += 10; lv_timer_handler(); }
  lv_refr_now(nullptr);
}

static void save(const char* name) {
  run(400);
  std::string path = std::string(SIM_OUT) + "/" + name + ".ppm";
  FILE* f = fopen(path.c_str(), "wb");
  fprintf(f, "P6\n%d %d\n255\n", W, H);
  for (int i = 0; i < W * H; i++) {
    uint16_t c = fb[i];
    unsigned char rgb[3] = {(unsigned char)(((c >> 11) & 0x1F) * 255 / 31), (unsigned char)(((c >> 5) & 0x3F) * 255 / 63),
                            (unsigned char)((c & 0x1F) * 255 / 31)};
    fwrite(rgb, 1, 3, f);
  }
  fclose(f);
  printf("%s\n", path.c_str());
}

int main() {
  lv_init();
  lv_tick_set_cb(tick);
  lv_display_t* d = lv_display_create(W, H);
  static uint8_t buf[W * 80 * 2];
  lv_display_set_buffers(d, buf, nullptr, sizeof(buf), LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(d, flush);
  ui::init();
  static std::vector<uint8_t> logo = readFile("build/logo.png"), avatar = readFile("build/avatar.png");
  ui::setBrand(logo.data(), logo.size(), avatar.data(), avatar.size());

  ui::Top top;
  top.volume = 160;
  top.battery = 72;
  top.points = 145;

  // startskärm
  ui::showBoot("Ansluter till WiFi \"Hemma\" ...", "");
  save("00-start");
  ui::hideBoot();

  // Öva
  top.tab = 0;
  ui::setTop(top);
  ui::Ova o;
  o.rows = {{"Prov: Fotosyntesen", "Imorgon", ui::Tone::Warn, 0x2f6f5e, true},
            {"Glosförhör kap 3", "Fredag 28/11", ui::Tone::Muted, 0xc8453b, true},
            {"Matte: bråk och procent", "Måndag 1/12", ui::Tone::Muted, 0x2c63c9, true},
            {"Prov: Vikingatiden", "börja nu", ui::Tone::Good, 0xb7862a, false},
            {"Spanska: veckodagar och tid", "Torsdag 11/12", ui::Tone::Muted, 0x7a4fb5, false},
            {"Läsförståelse: novellen", "Fredag 12/12", ui::Tone::Muted, 0x1f8a9e, false}};
  o.selected = 0;
  o.title = "Prov: Fotosyntesen";
  o.meta = "NO  ·  Imorgon";
  o.color = 0x2f6f5e;
  o.topics = {"Klorofyll", "Ljus och energi", "Cellandning", "Begreppen"};
  o.focus = 0;
  o.last = "Snabbquiz · Fotosyntesen · 8 av 10 rätt · 6 min";
  o.canAgain = true;
  ui::buildOva(o);
  save("01-ova");

  ui::buildMessage("Pluggis gör ett snabbquiz av materialet ...", true, "Det kan ta en halv minut.");
  save("02-vantar");

  // ett kort med alternativ
  ui::Card k;
  k.deck = "Snabbquiz: Fotosyntesen";
  k.answered = 3;
  k.right = 2;
  k.question = "Vad behöver växten för att göra socker i fotosyntesen?";
  k.options = {"Solljus, vatten och koldioxid", "Syre och socker", "Kväve och jord", "Bara vatten"};
  k.correct = 0;
  ui::buildCard(k);
  save("03-quiz");
  k.chosen = 1;
  ui::buildCard(k);
  save("04-quiz-svar");

  ui::Card g;
  g.deck = "Glosförhör kap 3";
  g.answered = 5;
  g.right = 4;
  g.question = "el martes";
  g.answer = "tisdag";
  g.hint = "en veckodag";
  g.revealed = true;
  g.spoken = "tisdag";
  g.verdict = 1;
  ui::buildCard(g);
  save("05-kort-svar");

  ui::Card done;
  done.deck = "Snabbquiz: Fotosyntesen";
  done.done = true;
  done.answered = 10;
  done.right = 8;
  done.doneText = "Bra jobbat! 8 av 10 rätt.";
  ui::buildCard(done);
  save("06-quiz-klart");

  // Förklara
  ui::Game e;
  e.kind = 2;
  e.title = "Förklara: Prov: Fotosyntesen";
  e.term = "Klorofyll";
  e.heard = "det är det gröna i bladen som tar upp solljuset";
  e.reply = "Spännande! Men vad gör växten med ljuset sen, när klorofyllet har fångat det?";
  ui::buildGame(e);
  save("07-forklara");

  // Testprov
  ui::Game x;
  x.kind = 3;
  x.title = "Testprov: Prov: Fotosyntesen";
  x.qIndex = 4;
  x.qCount = 10;
  x.question = "Var i cellen sker fotosyntesen?";
  x.choices = {"I kloroplasterna", "I cellkärnan", "I mitokondrierna", "I cellväggen"};
  x.answer = "I kloroplasterna";
  ui::buildGame(x);
  save("08-testprov");
  x.done = true;
  x.points = 15;
  x.maxPoints = 20;
  x.percent = 75;
  x.review = {"Skillnaden mellan fotosyntes och cellandning", "Vad glukos används till", "Formeln: koldioxid + vatten ger socker + syre"};
  ui::buildGame(x);
  save("09-testprov-klart");

  // Matcha par
  ui::Game mp;
  mp.kind = 4;
  mp.title = "Matcha par: Glosförhör kap 3";
  mp.left = {"el lunes", "el martes", "el miércoles", "el jueves", "el viernes"};
  mp.right = {"torsdag", "måndag", "fredag", "tisdag", "onsdag"};
  mp.leftState = {ui::CellState::Matched, ui::CellState::Selected, ui::CellState::Normal, ui::CellState::Normal, ui::CellState::Normal};
  mp.rightState = {ui::CellState::Normal, ui::CellState::Matched, ui::CellState::Normal, ui::CellState::Normal, ui::CellState::Normal};
  ui::buildGame(mp);
  save("10-matcha");

  // Rätt ordning
  ui::Game ro;
  ro.kind = 5;
  ro.title = "Rätt ordning: Prov: Fotosyntesen";
  ro.seqTitle = "Från ljus till socker";
  ro.prompt = "Tryck på stegen i rätt ordning.";
  ro.seqIndex = 0;
  ro.seqCount = 3;
  ro.placed = {"Ljuset träffar bladet"};
  ro.steps = {"Sockret transporteras ut i växten", "Klorofyllet fångar ljusenergin", "Vatten och koldioxid blir socker", "Syre släpps ut"};
  ro.wrongStep = 0;
  ui::buildGame(ro);
  save("11-ordning");

  // Läxor
  top.tab = 2;
  ui::setTop(top);
  ui::buildHwList({{"Prov · NO", true, "Fotosyntesen", "Imorgon", 0x2f6f5e},
                   {"Läxa · Spanska", false, "Glosförhör kap 3", "Fredag 28/11", 0xc8453b},
                   {"Läxa · Matte", false, "Bråk och procent, s. 40–45", "Måndag 1/12", 0x2c63c9},
                   {"Prov · SO", true, "Vikingatiden", "Torsdag 11/12", 0xb7862a},
                   {"Läxa · Svenska", false, "Läsförståelse: novellen", "Fredag 12/12", 0x1f8a9e}});
  save("12-laxor");

  ui::HwDetail hd;
  hd.meta = "Prov · NO · Imorgon";
  hd.title = "Fotosyntesen";
  hd.about = "Utkik kap 4, s. 40–72 och begreppslistan";
  hd.test = true;
  hd.hasMaterial = true;
  hd.head = "Sida 42  ·  2 av 5";
  hd.percent = 34;
  hd.line = "Växter tillverkar sin egen näring. Med hjälp av solljus omvandlar de vatten och koldioxid till socker och syre.";
  hd.listening = true;
  hd.playLabel = "Paus";
  ui::buildHwDetail(hd);
  save("13-laxa-lyssna");

  // Plan
  top.tab = 3;
  ui::setTop(top);
  ui::Plan p;
  p.day = "Idag";
  p.summary = "25 min kvar av 60";
  p.doneMinutes = 35;
  p.totalMinutes = 60;
  p.hasNext = true;
  p.items = {{"NO", "Läs s. 46–52 om cellandning", 0x2f6f5e, 20, false, 1},
             {"NO", "Läs s. 40–45 (går att lyssna på)", 0x2f6f5e, 20, true, 1},
             {"NO", "Snabbquiz: Fotosyntesen", 0x2f6f5e, 15, true},
             {"Spanska", "Öva glosorna kap 3 – Matcha par", 0xc8453b, 15, false, 2},
             {"Matte", "Bråk: uppgift 12–18", 0x2c63c9, 10, false},
             {"Prov: Vikingatiden – handel, resor och vardagsliv", "Läs s. 112–118 om handelsresorna österut och gör tankekartan över de viktigaste orterna", 0xb7862a, 20, false},
             {"Engelska", "Repetera glosorna med boken framme, sedan utan, som på provet", 0x1f8a9e, 15, false}};
  ui::buildPlan(p);
  save("14-plan");
  p.items.erase(p.items.begin(), p.items.begin() + 3);
  ui::buildPlan(p);
  save("14b-plan-lang-text");

  // Chat
  top.tab = 1;
  ui::setTop(top);
  ui::Coach co;
  co.heard = "jag vill börja med NO-provet";
  co.say = "Bra val! Vi kör 20 minuter på fotosyntesen. Börja med att läsa sidan 40 och 41. Säg till när du är klar, så förhör jag dig.";
  co.session = true;
  ui::buildCoach(co);
  ui::coachTimer(true, 14 * 60 + 32, 20 * 60, "Läs s. 40–41");
  save("15-chat");
  co.thinking = true;
  ui::buildCoach(co);
  ui::coachTimer(true, 14 * 60 + 30, 20 * 60, "Läs s. 40–41");
  save("16-chat-tanker");
  top.status = "Ansluter";
  ui::setTop(top);
  ui::info("Anslutning", "WiFi: ansluten till \"femtifem\" (192.168.1.42, signal -61 dBm)\nServer: debian:8080 som \"fred\"\n"
           "Chatten (WebSocket): nere – kom inte fram för 3 s sedan, 12 försök\nLäxor hämtade: ja\n"
           "Ledigt minne: 214 kB internt, 27 400 kB PSRAM");
  save("18-anslutning");
  ui::info("", "");
  save("19-toppraden-ansluter");
  ui::info("", "");
  top.status = "";
  ui::setTop(top);
  // WiFi
  ui::Wifi wf;
  wf.status = "6 nätverk";
  wf.nets = {{"femtifem", -48, true, true}, {"Telia-7C21", -63, true, false}, {"Granne_2.4G", -71, true, false},
             {"Gäst", -74, false, false}, {"TP-Link_9A", -82, true, false}, {"Skrivare-HP", -88, true, false}};
  top.tab = -1;
  ui::setTop(top);
  ui::buildWifi(wf);
  save("20-wifi");
  wf.ssid = "Telia-7C21";
  wf.status = "";
  ui::buildWifi(wf);
  save("21-wifi-losenord");
  ui::showBoot("Ansluter till WiFi \"femtifem\" ...", "Hittar inget WiFi. Kolla namn och lösenord i include/config.h och att nätet är 2,4 GHz. (WiFi-status 6, 214 kB ledigt)", true);
  save("22-start-utan-wifi");
  ui::hideBoot();
  top.tab = 1;
  ui::setTop(top);
  // Inställningar
  ui::Settings st;
  st.wifi = "femtifem";
  st.rssi = -52;
  st.server = "192.168.1.20:8080";
  st.serverOk = true;
  st.serverNote = "Ansluten";
  st.code = "YQE89";
  st.student = "Fred";
  st.idleMin = 5;
  st.brightness = 200;
  st.version = "Pluggis Tab5 · Nov 27 2026";
  top.tab = -1;
  ui::setTop(top);
  ui::buildSettings(st);
  save("23-installningar");
  // Fokustimern
  ui::Focus fo;
  fo.choose = true;
  ui::buildFocus(fo);
  save("30-fokus-valj");
  fo = ui::Focus();
  fo.subject = "NO";
  fo.color = 0x2f6f5e;
  fo.title = "Läs s. 46–52 om cellandning";
  fo.total = 20 * 60;
  fo.left = 13 * 60 + 27;
  top.tab = -1;
  top.focusLeft = fo.left;
  ui::setTop(top);
  ui::buildFocus(fo);
  save("31-fokus");
  fo.paused = true;
  top.focusPaused = true;
  ui::setTop(top);
  ui::buildFocus(fo);
  save("32-fokus-pausad");
  fo.paused = false;
  fo.done = true;
  fo.reward = "20 min plugg · bockad i planen";
  top.focusLeft = -1;
  top.focusPaused = false;
  ui::setTop(top);
  ui::buildFocus(fo);
  save("33-fokus-klar");
  fo.done = false;
  fo.onBreak = true;
  fo.total = 5 * 60;
  fo.left = 3 * 60 + 12;
  ui::buildFocus(fo);
  save("34-fokus-paus");
  top.focusLeft = 13 * 60 + 27;
  top.tab = 3;
  ui::setTop(top);
  {
    ui::Plan p2;
    p2.day = "Idag";
    p2.summary = "25 min kvar av 60";
    p2.doneMinutes = 35;
    p2.totalMinutes = 60;
    p2.items = {{"NO", "Läs s. 46–52 om cellandning", 0x2f6f5e, 20, false, 1},
                {"NO", "Snabbquiz: Fotosyntesen", 0x2f6f5e, 15, true},
                {"Spanska", "Öva glosorna kap 3 – Matcha par", 0xc8453b, 15, false, 2},
                {"Matte", "Bråk: uppgift 12–18", 0x2c63c9, 10, false}};
    ui::buildPlan(p2);
    save("35-plan-med-timer");
  }
  top.focusLeft = -1;
  ui::setTop(top);
  // Klockläget
  ui::Clock ck;
  ck.time = "16:42";
  ck.date = "Fredag 27 november";
  ck.next = "NO: Fotosyntesen och cellandning";
  ck.nextWhen = "om 3 dagar";
  ck.today = "2 av 4 kvar · 35 min";
  ck.items = {"Läs s. 46–52 om cellandning", "Öva glosorna kap 3 – Matcha par"};
  ui::clockShow(&ck);
  save("36-klocka");
  ck.next = "Engelska: glosor kap 4";
  ck.nextWhen = "imorgon";
  ck.nextSoon = true;
  ck.today = "Allt klart idag!";
  ck.items.clear();
  ui::clockShow(&ck);
  save("37-klocka-klart");
  ui::clockShow(nullptr);
  top.tab = -1;
  ui::setTop(top);
  ui::buildSettings(st);
  save("23-installningar");
  ui::Entry en;
  en.title = "Elevens kod";
  en.code = true;
  en.value = "YQE8";
  en.placeholder = "5 tecken";
  en.hint = "Samma kod som eleven loggar in med i Pluggis-appen (föräldravyn: Inställningar > Elever). Koden bestämmer vilken elev skärmen visar.";
  ui::buildEntry(en);
  save("24-elevkod");
  top.tab = 1;
  ui::setTop(top);
  ui::toast("+5 poäng");
  run(500);
  save("17-notis");
  return 0;
}
