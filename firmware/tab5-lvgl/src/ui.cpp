// Pluggis på Tab5 – gränssnittet (LVGL 9). Se ui.h.
#include "ui.h"

#include <deque>
#include <cstring>
#include <cstdio>
#include <algorithm>

LV_FONT_DECLARE(font_reg_24);
LV_FONT_DECLARE(font_reg_30);
LV_FONT_DECLARE(font_bold_30);
LV_FONT_DECLARE(font_bold_38);
LV_FONT_DECLARE(font_bold_60);

// Egna ikoner (FontAwesome 5, inbakade i typsnitten)
#define SYM_MIC "\xEF\x84\xB0"
#define SYM_CHAT "\xEF\x82\x86"
#define SYM_BOOK "\xEF\x80\xAD"
#define SYM_CALENDAR "\xEF\x81\xB3"
#define SYM_BULB "\xEF\x83\xAB"
#define SYM_QUESTION "\xEF\x84\xA8"
#define SYM_LINK "\xEF\x83\x81"
#define SYM_ORDER "\xEF\x85\xA2"
#define SYM_TROPHY "\xEF\x82\x91"
#define SYM_CLOCK "\xEF\x80\x97"

namespace ui {

static const lv_font_t* F_S = &font_reg_24;    // små texter, rubriker ovanför
static const lv_font_t* F_M = &font_reg_30;    // brödtext
static const lv_font_t* F_MB = &font_bold_30;  // knappar, rader
static const lv_font_t* F_L = &font_bold_38;   // frågor, titlar
static const lv_font_t* F_XL = &font_bold_60;  // resultat, startskärmen

static constexpr int W = 1280, H = 720, TOP_H = 96, CH = H - TOP_H;   // CH = höjden under toppraden
static constexpr int PAD = 20, GAP = 16;

static lv_color_t C(uint32_t hex) { return lv_color_hex(hex); }
static uint32_t toneHex(Tone t) {
  switch (t) {
    case Tone::Muted: return MUTED;
    case Tone::Accent: return ACCENT;
    case Tone::Good: return GOOD;
    case Tone::Bad: return BAD;
    case Tone::Warn: return WARN;
    default: return INK;
  }
}

// ---------------------------------------------------------------- kön med tryck
static std::deque<Action> queue;
static void* pack(Act act, int a) { return (void*)(intptr_t)(((int)act << 16) | (a & 0xFFFF)); }
static void onClick(lv_event_t* e) {
  intptr_t v = (intptr_t)lv_event_get_user_data(e);
  queue.push_back({(Act)(v >> 16), (int)(int16_t)(v & 0xFFFF)});
}
bool poll(Action& out) {
  if (queue.empty()) return false;
  out = queue.front();
  queue.pop_front();
  return true;
}
void clearQueue() { queue.clear(); }

// ---------------------------------------------------------------- loggan och figuren (PNG, avkodas av LVGL)
static lv_image_dsc_t logoImg, avatarImg;
static bool hasLogo = false, hasAvatar = false;

static bool pngDsc(lv_image_dsc_t& d, const uint8_t* png, uint32_t len) {
  if (!png || len < 24 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return false;
  memset(&d, 0, sizeof(d));
  d.header.magic = LV_IMAGE_HEADER_MAGIC;
  d.header.cf = LV_COLOR_FORMAT_RAW_ALPHA;
  d.header.w = (png[16] << 24) | (png[17] << 16) | (png[18] << 8) | png[19];   // ur PNG-huvudet (IHDR)
  d.header.h = (png[20] << 24) | (png[21] << 16) | (png[22] << 8) | png[23];
  d.data = png;
  d.data_size = len;
  return true;
}

void setBrand(const uint8_t* logo, uint32_t logoLen, const uint8_t* avatar, uint32_t avatarLen) {
  hasLogo = pngDsc(logoImg, logo, logoLen);
  hasAvatar = pngDsc(avatarImg, avatar, avatarLen);
}

// ---------------------------------------------------------------- byggklossar
static lv_obj_t *root, *top, *content, *boot;
static lv_style_t stTrans;
static lv_obj_t *talkBtn = nullptr, *talkLbl = nullptr;
static std::string talkIdle;

/** En tom låda utan tema: inte klickbar och inte rullbar. */
static lv_obj_t* box(lv_obj_t* p) {
  lv_obj_t* o = lv_obj_create(p);
  lv_obj_remove_style_all(o);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
  return o;
}

static lv_obj_t* panel(lv_obj_t* p, uint32_t bg = SURFACE, int radius = 24) {
  lv_obj_t* o = box(p);
  lv_obj_set_style_bg_color(o, C(bg), 0);
  lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(o, radius, 0);
  return o;
}

static void shadow(lv_obj_t* o, int w = 22, int y = 6) {
  lv_obj_set_style_shadow_width(o, w, 0);
  lv_obj_set_style_shadow_offset_y(o, y, 0);
  lv_obj_set_style_shadow_color(o, lv_color_black(), 0);
  lv_obj_set_style_shadow_opa(o, LV_OPA_40, 0);
}

static lv_obj_t* label(lv_obj_t* p, const std::string& t, const lv_font_t* f, uint32_t color,
                       lv_text_align_t align = LV_TEXT_ALIGN_LEFT) {
  lv_obj_t* l = lv_label_create(p);
  lv_label_set_text(l, t.c_str());
  lv_obj_set_style_text_font(l, f, 0);
  lv_obj_set_style_text_color(l, C(color), 0);
  lv_obj_set_style_text_align(l, align, 0);
  lv_obj_set_style_text_line_space(l, 6, 0);
  return l;
}

/** Största typsnittet där texten får plats i w × h (radbrytning på ord). */
static const lv_font_t* fit(const std::string& t, int w, int h, std::initializer_list<const lv_font_t*> fonts) {
  const lv_font_t* last = F_S;
  for (auto f : fonts) {
    lv_point_t sz;
    lv_text_get_size(&sz, t.c_str(), f, 0, 6, w, LV_TEXT_FLAG_NONE);
    last = f;
    if (sz.y <= h) return f;
  }
  return last;
}

/** Text i en ruta: krymper från stort till litet typsnitt tills den får plats. */
static lv_obj_t* textIn(lv_obj_t* p, const std::string& t, int x, int y, int w, int h, uint32_t color,
                        std::initializer_list<const lv_font_t*> fonts, lv_text_align_t align = LV_TEXT_ALIGN_LEFT,
                        bool vcenter = false) {
  const lv_font_t* f = fit(t, w, h, fonts);
  lv_obj_t* l = label(p, t, f, color, align);
  lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
  lv_obj_set_width(l, w);
  if (vcenter) {
    lv_point_t sz;
    lv_text_get_size(&sz, t.c_str(), f, 0, 6, w, LV_TEXT_FLAG_NONE);
    int hh = std::min<int>(sz.y, h);
    lv_obj_set_pos(l, x, y + (h - hh) / 2);
  } else {
    lv_obj_set_pos(l, x, y);
  }
  if (h > 0) lv_obj_set_style_max_height(l, h, 0);
  return l;
}

static lv_obj_t* icon(lv_obj_t* p, const char* sym, uint32_t color, const lv_font_t* f = nullptr) {
  return label(p, sym, f ? f : F_MB, color);
}

enum class Kind { Primary, Secondary, Good, Bad, Warn, Ghost, Dim };

static uint32_t kindBg(Kind k) {
  switch (k) {
    case Kind::Primary: return ACCENT;
    case Kind::Good: return GOOD;
    case Kind::Bad: return BAD;
    case Kind::Warn: return WARN;
    case Kind::Ghost: return BG;
    case Kind::Dim: return 0x0A2352;
    default: return SURFACE2;
  }
}

/** En knapp som sjunker in lite när man trycker. Texten radbryts och krymper om den är lång. */
static lv_obj_t* button(lv_obj_t* p, int x, int y, int w, int h, const std::string& text, Kind kind, Act act, int a = 0,
                        const char* sym = nullptr, int radius = 26) {
  lv_obj_t* b = box(p);
  lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_pos(b, x, y);
  lv_obj_set_size(b, w, h);
  uint32_t bg = kindBg(kind);
  lv_obj_set_style_bg_color(b, C(bg), 0);
  lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(b, radius, 0);
  if (kind == Kind::Primary) {
    lv_obj_set_style_bg_grad_color(b, C(ACCENT2), 0);
    lv_obj_set_style_bg_grad_dir(b, LV_GRAD_DIR_VER, 0);
  }
  if (kind == Kind::Ghost) {
    lv_obj_set_style_border_width(b, 2, 0);
    lv_obj_set_style_border_color(b, C(LINE), 0);
  }
  lv_obj_set_style_bg_color(b, lv_color_darken(C(bg), 45), LV_STATE_PRESSED);
  lv_obj_set_style_bg_grad_color(b, lv_color_darken(C(kind == Kind::Primary ? ACCENT2 : bg), 45), LV_STATE_PRESSED);
  lv_obj_set_style_transform_width(b, -4, LV_STATE_PRESSED);
  lv_obj_set_style_transform_height(b, -4, LV_STATE_PRESSED);
  lv_obj_add_style(b, &stTrans, 0);
  uint32_t ink = (kind == Kind::Good || kind == Kind::Warn) ? BG : kind == Kind::Dim ? MUTED : INK;
  std::string t = sym ? std::string(sym) + "  " + text : text;
  const lv_font_t* f = fit(t, w - 36, h - 16, {F_MB, F_M, F_S});
  lv_obj_t* l = label(b, t, f, ink, LV_TEXT_ALIGN_CENTER);
  lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
  lv_obj_set_width(l, w - 36);
  lv_obj_center(l);
  if (act != Act::None) lv_obj_add_event_cb(b, onClick, act == Act::TalkPress ? LV_EVENT_PRESSED : LV_EVENT_CLICKED, pack(act, a));
  return b;
}

static lv_obj_t* talkButton(lv_obj_t* p, int x, int y, int w, int h, const std::string& idle, bool recording) {
  talkIdle = idle;
  lv_obj_t* b = button(p, x, y, w, h, recording ? "Släpp när du är klar" : idle, recording ? Kind::Warn : Kind::Primary,
                       Act::TalkPress, 0, SYM_MIC);
  talkBtn = b;
  talkLbl = lv_obj_get_child(b, 0);
  return b;
}

void setTalking(bool on) {
  if (!talkBtn || !talkLbl) return;
  lv_obj_set_style_bg_color(talkBtn, C(on ? WARN : ACCENT), 0);
  lv_obj_set_style_bg_grad_color(talkBtn, C(on ? 0xD9A11F : ACCENT2), 0);
  lv_obj_set_style_text_color(talkLbl, C(on ? BG : INK), 0);
  std::string t = std::string(SYM_MIC) + "  " + (on ? std::string("Släpp när du är klar") : talkIdle);
  lv_label_set_text(talkLbl, t.c_str());
}

/** Tunn förloppsstapel. */
static lv_obj_t* bar(lv_obj_t* p, int x, int y, int w, int h, int value, int max, uint32_t color, uint32_t track = SURFACE2) {
  lv_obj_t* b = lv_bar_create(p);
  lv_obj_remove_style_all(b);
  lv_obj_set_pos(b, x, y);
  lv_obj_set_size(b, w, h);
  lv_bar_set_range(b, 0, std::max(1, max));
  lv_bar_set_value(b, std::min(value, std::max(1, max)), LV_ANIM_OFF);
  lv_obj_set_style_bg_color(b, C(track), LV_PART_MAIN);
  lv_obj_set_style_bg_opa(b, LV_OPA_COVER, LV_PART_MAIN);
  lv_obj_set_style_radius(b, h / 2, LV_PART_MAIN);
  lv_obj_set_style_bg_color(b, C(color), LV_PART_INDICATOR);
  lv_obj_set_style_bg_opa(b, LV_OPA_COVER, LV_PART_INDICATOR);
  lv_obj_set_style_radius(b, h / 2, LV_PART_INDICATOR);
  return b;
}

/** Ring med procent i mitten (resultat). */
static lv_obj_t* ring(lv_obj_t* p, int x, int y, int size, int pct, uint32_t color) {
  lv_obj_t* a = lv_arc_create(p);
  lv_obj_set_pos(a, x, y);
  lv_obj_set_size(a, size, size);
  lv_arc_set_rotation(a, 270);
  lv_arc_set_bg_angles(a, 0, 360);
  lv_arc_set_range(a, 0, 100);
  lv_arc_set_value(a, std::max(0, std::min(100, pct)));
  lv_obj_remove_flag(a, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_width(a, 18, LV_PART_MAIN);
  lv_obj_set_style_arc_color(a, C(SURFACE2), LV_PART_MAIN);
  lv_obj_set_style_arc_width(a, 18, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(a, C(color), LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(a, true, LV_PART_INDICATOR);
  lv_obj_set_style_bg_opa(a, LV_OPA_TRANSP, LV_PART_KNOB);
  lv_obj_set_style_pad_all(a, 0, LV_PART_KNOB);
  char t[16];
  snprintf(t, sizeof(t), "%d%%", pct);
  lv_obj_t* l = label(a, t, F_XL, INK);
  lv_obj_center(l);
  return a;
}

static lv_obj_t* spinner(lv_obj_t* p, int size) {
  lv_obj_t* s = lv_spinner_create(p);
  lv_spinner_set_anim_params(s, 1000, 240);
  lv_obj_set_size(s, size, size);
  lv_obj_set_style_arc_width(s, 10, LV_PART_MAIN);
  lv_obj_set_style_arc_color(s, C(SURFACE2), LV_PART_MAIN);
  lv_obj_set_style_arc_width(s, 10, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(s, C(YELLOW), LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(s, true, LV_PART_INDICATOR);
  return s;
}

/** Tre punkter som pulserar ("Pluggis tänker"). */
static void dotAnim(void* o, int32_t v) { lv_obj_set_style_bg_opa((lv_obj_t*)o, (lv_opa_t)v, 0); }
static lv_obj_t* thinkingDots(lv_obj_t* p, int x, int y) {
  lv_obj_t* row = box(p);
  lv_obj_set_pos(row, x, y);
  lv_obj_set_size(row, 120, 28);
  for (int i = 0; i < 3; i++) {
    lv_obj_t* d = panel(row, YELLOW, 14);
    lv_obj_set_size(d, 24, 24);
    lv_obj_set_pos(d, i * 38, 2);
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, d);
    lv_anim_set_exec_cb(&a, dotAnim);
    lv_anim_set_values(&a, 60, 255);
    lv_anim_set_duration(&a, 420);
    lv_anim_set_playback_duration(&a, 420);
    lv_anim_set_delay(&a, i * 160);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_start(&a);
  }
  return row;
}

/** "Du: …" – det eleven sa, i en bubbla till höger (en rad, kapas med …). */
static lv_obj_t* heardBubble(lv_obj_t* p, const std::string& t, int y, int maxW) {
  lv_point_t sz;
  lv_text_get_size(&sz, t.c_str(), F_M, 0, 6, LV_COORD_MAX, LV_TEXT_FLAG_NONE);
  int w = std::min<int>(sz.x + 4, maxW);
  lv_obj_t* b = panel(p, SURFACE2, 24);
  lv_obj_set_size(b, w + 44, lv_font_get_line_height(F_M) + 24);
  lv_obj_t* l = label(b, t, F_M, 0xC9D6E8);
  lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
  lv_obj_set_size(l, w, lv_font_get_line_height(F_M));
  lv_obj_center(l);
  lv_obj_align(b, LV_ALIGN_TOP_RIGHT, -PAD, y);
  return b;
}

/** En rad text som kapas med … om den inte får plats. */
static lv_obj_t* oneLine(lv_obj_t* p, const std::string& t, const lv_font_t* f, uint32_t color, int w) {
  lv_obj_t* l = label(p, t, f, color);
  lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
  lv_obj_set_size(l, w, lv_font_get_line_height(f));
  return l;
}

/** Ämnesfärgad pill med text. */
static lv_obj_t* chip(lv_obj_t* p, const std::string& t, uint32_t bg, uint32_t ink, const lv_font_t* f = nullptr) {
  lv_obj_t* c = panel(p, bg, 999);
  lv_obj_set_size(c, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
  lv_obj_set_style_pad_hor(c, 18, 0);
  lv_obj_set_style_pad_ver(c, 8, 0);
  label(c, t, f ? f : F_S, ink);
  return c;
}

static int32_t ovaScroll = 0;   // hur långt läxlistan på Öva är rullad (behålls när skärmen byggs om)
static lv_obj_t* ovaList = nullptr;
static lv_obj_t *timerBox = nullptr, *timerBar = nullptr, *timerLbl = nullptr;
static lv_obj_t *wifiPass = nullptr, *wifiSsid = nullptr, *wifiStatusLbl = nullptr, *wifiSpin = nullptr;
static std::string wifiTyped;   // behålls om skärmen byggs om medan man skriver

/** Tömmer ytan under toppraden inför en ny skärm. */
static lv_obj_t* fresh(Act background = Act::None) {
  if (ovaList) ovaScroll = lv_obj_get_scroll_y(ovaList);
  ovaList = nullptr;
  timerBox = timerBar = timerLbl = nullptr;
  talkBtn = talkLbl = nullptr;
  wifiPass = wifiSsid = wifiStatusLbl = wifiSpin = nullptr;
  lv_obj_clean(content);
  lv_obj_remove_event_cb(content, onClick);
  if (background != Act::None) {
    lv_obj_add_flag(content, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(content, onClick, LV_EVENT_CLICKED, pack(background, 0));
  } else {
    lv_obj_remove_flag(content, LV_OBJ_FLAG_CLICKABLE);
  }
  return content;
}

// ---------------------------------------------------------------- toppraden
static lv_obj_t* tabBtn[4];
static lv_obj_t *statusLbl, *volBars[5], *volIcon, *batBox, *batFill, *batLbl, *batBolt, *pointsChip, *pointsLbl;

static void buildTop() {
  top = panel(root, BG, 0);
  lv_obj_set_size(top, W, TOP_H);
  lv_obj_set_pos(top, 0, 0);
  lv_obj_set_style_border_side(top, LV_BORDER_SIDE_BOTTOM, 0);
  lv_obj_set_style_border_width(top, 2, 0);
  lv_obj_set_style_border_color(top, C(SURFACE), 0);

  // flikarna i en "segmenterad" ruta
  lv_obj_t* seg = panel(top, SURFACE, 999);
  lv_obj_set_pos(seg, 16, 14);
  lv_obj_set_size(seg, 4 * 150 + 12, 68);
  static const char* names[4] = {"Öva", "Chat", "Läxor", "Plan"};
  static const char* syms[4] = {LV_SYMBOL_EDIT, SYM_CHAT, SYM_BOOK, SYM_CALENDAR};
  for (int i = 0; i < 4; i++) {
    lv_obj_t* b = box(seg);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_pos(b, 6 + i * 150, 6);
    lv_obj_set_size(b, 150, 56);
    lv_obj_set_style_radius(b, 999, 0);
    lv_obj_set_style_bg_color(b, C(ACCENT), 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_TRANSP, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_30, LV_STATE_PRESSED);
    lv_obj_add_style(b, &stTrans, 0);
    lv_obj_t* l = label(b, std::string(syms[i]) + "  " + names[i], F_MB, MUTED);
    lv_obj_center(l);
    lv_obj_add_event_cb(b, onClick, LV_EVENT_CLICKED, pack(Act::Tab, i));
    tabBtn[i] = b;
  }

  // höger: status · volym · batteri · poäng (från höger till vänster)
  lv_obj_t* right = box(top);
  lv_obj_set_size(right, W - 16 - (16 + 4 * 150 + 12) - 16, TOP_H);
  lv_obj_align(right, LV_ALIGN_RIGHT_MID, -16, 0);
  lv_obj_set_flex_flow(right, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(right, LV_FLEX_ALIGN_END, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(right, 14, 0);

  statusLbl = label(right, "", F_S, WARN);
  lv_obj_add_flag(statusLbl, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_ext_click_area(statusLbl, 24);
  lv_obj_add_event_cb(statusLbl, onClick, LV_EVENT_CLICKED, pack(Act::SetOpen, 0));

  lv_obj_t* vol = panel(right, SURFACE, 999);
  lv_obj_set_size(vol, 214, 64);
  button(vol, 4, 4, 56, 56, LV_SYMBOL_MINUS, Kind::Ghost, Act::VolDown, 0, nullptr, 999);
  button(vol, 154, 4, 56, 56, LV_SYMBOL_PLUS, Kind::Ghost, Act::VolUp, 0, nullptr, 999);
  volIcon = icon(vol, LV_SYMBOL_VOLUME_MAX, INK, F_S);
  lv_obj_set_pos(volIcon, 66, 18);
  for (int i = 0; i < 5; i++) {
    int bh = 8 + i * 5;
    volBars[i] = panel(vol, ACCENT, 3);
    lv_obj_set_size(volBars[i], 6, bh);
    lv_obj_set_pos(volBars[i], 98 + i * 10, 42 - bh);
  }

  lv_obj_t* bat = box(right);
  lv_obj_set_size(bat, 112, 40);
  batBox = box(bat);
  lv_obj_set_size(batBox, 50, 28);
  lv_obj_set_pos(batBox, 0, 6);
  lv_obj_set_style_border_width(batBox, 3, 0);
  lv_obj_set_style_border_color(batBox, C(MUTED), 0);
  lv_obj_set_style_radius(batBox, 7, 0);
  lv_obj_t* nub = panel(bat, MUTED, 2);
  lv_obj_set_size(nub, 5, 12);
  lv_obj_set_pos(nub, 51, 14);
  batFill = panel(batBox, GOOD, 3);
  lv_obj_set_pos(batFill, 4, 4);
  lv_obj_set_size(batFill, 36, 14);
  batBolt = label(bat, LV_SYMBOL_CHARGE, F_S, INK);
  lv_obj_set_pos(batBolt, 15, 4);
  batLbl = label(bat, "", F_S, MUTED);
  lv_obj_set_pos(batLbl, 64, 6);

  pointsChip = panel(right, YELLOW, 999);
  lv_obj_set_size(pointsChip, LV_SIZE_CONTENT, 52);
  lv_obj_set_style_pad_hor(pointsChip, 20, 0);
  pointsLbl = label(pointsChip, "", F_MB, BG);
  lv_obj_center(pointsLbl);
}

void setTop(const Top& t) {
  for (int i = 0; i < 4; i++) {
    bool on = i == t.tab;
    lv_obj_set_style_bg_opa(tabBtn[i], on ? LV_OPA_COVER : LV_OPA_TRANSP, 0);
    lv_obj_set_style_text_color(lv_obj_get_child(tabBtn[i], 0), C(on ? INK : MUTED), 0);
  }
  // Alltid en WiFi-ikon (tryck för anslutningen och för att byta nätverk); text bara när något är fel
  lv_label_set_text(statusLbl, t.status.empty() ? LV_SYMBOL_WIFI "  " LV_SYMBOL_SETTINGS
                                                : (std::string(LV_SYMBOL_WARNING) + " " + t.status + "  " + LV_SYMBOL_SETTINGS).c_str());
  lv_obj_set_style_text_color(statusLbl, C(t.status.empty() ? MUTED : WARN), 0);
  int bars = (t.volume + 50) / 51;
  lv_label_set_text(volIcon, t.volume == 0 ? LV_SYMBOL_MUTE : LV_SYMBOL_VOLUME_MAX);
  lv_obj_set_style_text_color(volIcon, C(t.volume ? INK : MUTED), 0);
  for (int i = 0; i < 5; i++) lv_obj_set_style_bg_color(volBars[i], C(i < bars ? ACCENT : SURFACE2), 0);
  lv_obj_t* bat = lv_obj_get_parent(batBox);
  if (t.battery < 0) {
    lv_obj_add_flag(bat, LV_OBJ_FLAG_HIDDEN);
  } else {
    lv_obj_remove_flag(bat, LV_OBJ_FLAG_HIDDEN);
    int lvl = std::min(100, t.battery);
    uint32_t c = lvl <= 15 ? BAD : lvl <= 30 ? WARN : GOOD;
    lv_obj_set_width(batFill, std::max(2, 36 * lvl / 100));
    lv_obj_set_style_bg_color(batFill, C(c), 0);
    char s[16];
    snprintf(s, sizeof(s), "%d%%", lvl);
    lv_label_set_text(batLbl, s);
    if (t.charging) lv_obj_remove_flag(batBolt, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_add_flag(batBolt, LV_OBJ_FLAG_HIDDEN);
  }
  if (t.points >= 0) {
    lv_obj_remove_flag(pointsChip, LV_OBJ_FLAG_HIDDEN);
    char s[24];
    snprintf(s, sizeof(s), "%d p", t.points);
    lv_label_set_text(pointsLbl, s);
  } else {
    lv_obj_add_flag(pointsChip, LV_OBJ_FLAG_HIDDEN);
  }
}

// ---------------------------------------------------------------- start
void init() {
  lv_style_init(&stTrans);
  static const lv_style_prop_t props[] = {LV_STYLE_BG_COLOR, LV_STYLE_TRANSFORM_WIDTH, LV_STYLE_TRANSFORM_HEIGHT,
                                          LV_STYLE_BG_OPA, (lv_style_prop_t)0};
  static lv_style_transition_dsc_t tr;
  lv_style_transition_dsc_init(&tr, props, lv_anim_path_ease_out, 90, 0, nullptr);
  lv_style_set_transition(&stTrans, &tr);

  root = lv_screen_active();
  lv_obj_remove_style_all(root);
  lv_obj_set_style_bg_color(root, C(BG), 0);
  lv_obj_set_style_bg_opa(root, LV_OPA_COVER, 0);
  lv_obj_set_style_text_font(root, F_M, 0);
  lv_obj_set_style_text_color(root, C(INK), 0);
  lv_obj_remove_flag(root, LV_OBJ_FLAG_SCROLLABLE);

  content = box(root);
  lv_obj_set_pos(content, 0, TOP_H);
  lv_obj_set_size(content, W, CH);
  buildTop();
  setTop(Top());
}

// ---------------------------------------------------------------- startskärm
static lv_obj_t *bootLine, *bootWarn;
static lv_obj_t* bootWifi = nullptr;
void showBoot(const std::string& line, const std::string& warn, bool wifiButton) {
  if (!boot) {
    boot = panel(root, BG, 0);
    lv_obj_set_size(boot, W, H);
    lv_obj_add_flag(boot, LV_OBJ_FLAG_CLICKABLE);   // inget under syns eller går att trycka på
    if (hasLogo) {
      lv_obj_t* logo = lv_image_create(boot);
      lv_image_set_src(logo, &logoImg);
      lv_obj_align(logo, LV_ALIGN_TOP_MID, 0, 36);
    } else {
      lv_obj_t* title = label(boot, "Pluggis", F_XL, INK);
      lv_obj_align(title, LV_ALIGN_CENTER, 0, -150);
    }
    lv_obj_t* s = spinner(boot, 80);
    lv_obj_align(s, LV_ALIGN_CENTER, 0, 80);
    bootLine = label(boot, "", F_M, MUTED, LV_TEXT_ALIGN_CENTER);
    lv_obj_align(bootLine, LV_ALIGN_CENTER, 0, 150);
    bootWarn = label(boot, "", F_S, WARN, LV_TEXT_ALIGN_CENTER);
    lv_label_set_long_mode(bootWarn, LV_LABEL_LONG_WRAP);
    lv_obj_set_width(bootWarn, W - 200);
    lv_obj_align(bootWarn, LV_ALIGN_CENTER, 0, 210);
  }
  if (wifiButton && !bootWifi) {
    bootWifi = button(boot, W / 2 - 170, H - 92, 340, 78, "Välj WiFi", Kind::Primary, Act::WifiOpen, 0, LV_SYMBOL_WIFI);
  } else if (!wifiButton && bootWifi) {
    lv_obj_delete(bootWifi);
    bootWifi = nullptr;
  }
  lv_label_set_text(bootLine, line.c_str());
  lv_label_set_text(bootWarn, warn.c_str());
  lv_obj_align(bootLine, LV_ALIGN_CENTER, 0, 150);
  lv_obj_align(bootWarn, LV_ALIGN_TOP_MID, 0, H / 2 + 180);
}

void hideBoot() {
  if (boot) lv_obj_delete(boot);
  boot = nullptr;
  bootWifi = nullptr;
}

// ---------------------------------------------------------------- meddelande, notis, avstängning
void buildMessage(const std::string& text, bool busy, const std::string& hint) {
  lv_obj_t* c = fresh(busy ? Act::None : Act::Background);
  int y = busy ? 90 : 120;
  if (busy) {
    lv_obj_t* s = spinner(c, 110);
    lv_obj_align(s, LV_ALIGN_TOP_MID, 0, y);
    y += 150;
  }
  textIn(c, text, 120, y, W - 240, 220, busy ? INK : INK, {F_L, F_MB, F_M}, LV_TEXT_ALIGN_CENTER);
  if (!hint.empty()) {
    lv_obj_t* h = label(c, hint, F_M, MUTED, LV_TEXT_ALIGN_CENTER);
    lv_obj_align(h, LV_ALIGN_BOTTOM_MID, 0, -60);
  }
}

static void toastY(void* o, int32_t v) { lv_obj_set_y((lv_obj_t*)o, v); }
static void toastDone(lv_anim_t* a) { lv_obj_delete((lv_obj_t*)a->var); }
void toast(const std::string& text) {
  lv_obj_t* t = panel(lv_layer_top(), YELLOW, 999);
  lv_obj_set_size(t, LV_SIZE_CONTENT, 72);
  lv_obj_set_style_pad_hor(t, 34, 0);
  shadow(t, 30, 8);
  lv_obj_t* l = label(t, text, F_L, BG);
  lv_obj_center(l);
  lv_obj_update_layout(t);
  lv_obj_set_x(t, W - lv_obj_get_width(t) - 28);
  lv_obj_set_y(t, -90);
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, t);
  lv_anim_set_exec_cb(&a, toastY);
  lv_anim_set_values(&a, -90, TOP_H + 16);
  lv_anim_set_duration(&a, 320);
  lv_anim_set_path_cb(&a, lv_anim_path_overshoot);
  lv_anim_set_playback_delay(&a, 2600);
  lv_anim_set_playback_duration(&a, 260);
  lv_anim_set_completed_cb(&a, toastDone);
  lv_anim_start(&a);
}

static lv_obj_t* infoBox = nullptr;
static void closeInfo(lv_event_t*) {
  if (infoBox) lv_obj_delete_async(infoBox);
  infoBox = nullptr;
}
void info(const std::string& title, const std::string& text, const std::string& buttonText, Act act) {
  if (infoBox) lv_obj_delete(infoBox);
  infoBox = nullptr;
  if (title.empty() && text.empty()) return;   // tom = stäng
  infoBox = box(lv_layer_top());
  lv_obj_add_flag(infoBox, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_size(infoBox, W, H);
  lv_obj_set_style_bg_color(infoBox, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(infoBox, LV_OPA_60, 0);
  lv_obj_add_event_cb(infoBox, closeInfo, LV_EVENT_CLICKED, nullptr);
  lv_obj_t* c = panel(infoBox, SURFACE, 30);
  lv_obj_set_size(c, W - 160, H - 140);
  lv_obj_center(c);
  shadow(c, 40, 10);
  lv_obj_t* t = label(c, title, F_L, YELLOW);
  lv_obj_set_pos(t, 36, 28);
  textIn(c, text, 36, 90, W - 160 - 72, H - 140 - 160, INK, {F_M, F_S});
  if (!buttonText.empty()) {
    lv_obj_t* b = button(c, W - 160 - 36 - 300, H - 140 - 100, 300, 76, buttonText, Kind::Primary, act, 0, LV_SYMBOL_WIFI);
    lv_obj_add_event_cb(b, closeInfo, LV_EVENT_CLICKED, nullptr);
  }
  lv_obj_t* h = label(c, "Tryck för att stänga", F_S, MUTED);
  lv_obj_align(h, LV_ALIGN_BOTTOM_LEFT, 36, -36);
}

static lv_obj_t* idle = nullptr;
void idleWarning(bool show) {
  if (!show) {
    if (idle) lv_obj_delete(idle);
    idle = nullptr;
    return;
  }
  if (idle) return;
  idle = box(lv_layer_top());
  lv_obj_set_size(idle, W, H);
  lv_obj_set_style_bg_color(idle, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(idle, LV_OPA_60, 0);
  lv_obj_t* c = panel(idle, WARN, 32);
  lv_obj_set_size(c, 720, 180);
  lv_obj_center(c);
  shadow(c, 40, 10);
  lv_obj_t* a = label(c, "Stänger av om 30 s", F_L, BG);
  lv_obj_align(a, LV_ALIGN_CENTER, 0, -28);
  lv_obj_t* b = label(c, "Tryck för att fortsätta", F_M, BG);
  lv_obj_align(b, LV_ALIGN_CENTER, 0, 32);
}

// ---------------------------------------------------------------- WiFi

std::string wifiPassword() { return wifiPass ? lv_textarea_get_text(wifiPass) : wifiTyped; }
std::string wifiSsidInput() { return wifiSsid ? lv_textarea_get_text(wifiSsid) : ""; }

void wifiStatus(const std::string& text, Tone tone, bool busy) {
  if (wifiStatusLbl) {
    lv_label_set_text(wifiStatusLbl, text.c_str());
    lv_obj_set_style_text_color(wifiStatusLbl, C(toneHex(tone)), 0);
  }
  if (wifiSpin) {
    if (busy) lv_obj_remove_flag(wifiSpin, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_add_flag(wifiSpin, LV_OBJ_FLAG_HIDDEN);
  }
}

/** Signalstyrka som 1–4 staplar. */
static void signalBars(lv_obj_t* p, int rssi, int x, int y) {
  int n = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -78 ? 2 : 1;
  for (int i = 0; i < 4; i++) {
    lv_obj_t* b = panel(p, i < n ? INK : LINE, 2);
    lv_obj_set_size(b, 8, 10 + i * 8);
    lv_obj_set_pos(b, x + i * 13, y + 34 - (10 + i * 8));
  }
}

static void onTextareaFocus(lv_event_t* e) {
  lv_obj_t* kb = (lv_obj_t*)lv_event_get_user_data(e);
  lv_keyboard_set_textarea(kb, (lv_obj_t*)lv_event_get_target(e));
}

static void onShowPassword(lv_event_t* e) {
  lv_obj_t* sw = (lv_obj_t*)lv_event_get_target(e);
  if (wifiPass) lv_textarea_set_password_mode(wifiPass, !lv_obj_has_state(sw, LV_STATE_CHECKED));
}

static lv_obj_t* textarea(lv_obj_t* p, int x, int y, int w, const char* placeholder) {
  lv_obj_t* t = lv_textarea_create(p);
  lv_obj_set_pos(t, x, y);
  lv_obj_set_size(t, w, 76);
  lv_textarea_set_one_line(t, true);
  lv_textarea_set_placeholder_text(t, placeholder);
  lv_obj_set_style_text_font(t, F_M, 0);
  lv_obj_set_style_bg_color(t, C(BG), 0);
  lv_obj_set_style_text_color(t, C(INK), 0);
  lv_obj_set_style_border_color(t, C(LINE), 0);
  lv_obj_set_style_border_color(t, C(YELLOW), LV_STATE_FOCUSED);
  lv_obj_set_style_border_width(t, 3, 0);
  lv_obj_set_style_radius(t, 18, 0);
  lv_obj_set_style_pad_hor(t, 20, 0);
  lv_obj_set_style_pad_ver(t, 16, 0);
  lv_obj_set_style_text_color(t, C(MUTED), LV_PART_TEXTAREA_PLACEHOLDER);
  lv_obj_set_style_bg_color(t, C(YELLOW), LV_PART_CURSOR);
  return t;
}

void buildWifi(const Wifi& m) {
  bool password = !m.ssid.empty() || m.manual;
  if (wifiPass) wifiTyped = lv_textarea_get_text(wifiPass);
  if (!password) wifiTyped.clear();
  lv_obj_t* c = fresh();
  wifiPass = wifiSsid = wifiStatusLbl = wifiSpin = nullptr;

  button(c, PAD, 14, 200, 72, "Tillbaka", Kind::Ghost, Act::WifiBack, 0, LV_SYMBOL_LEFT);
  lv_obj_t* t = label(c, password ? (m.manual ? "Dolt nätverk" : "Lösenord för " + m.ssid) : std::string("Välj WiFi"), F_L, INK);
  lv_label_set_long_mode(t, LV_LABEL_LONG_DOT);
  lv_obj_set_size(t, W - 2 * PAD - 460, lv_font_get_line_height(F_L));
  lv_obj_set_pos(t, PAD + 230, 26);

  wifiSpin = spinner(c, 40);
  lv_obj_set_pos(wifiSpin, W - PAD - 40, 30);
  wifiStatusLbl = label(c, m.status, F_S, toneHex(m.statusTone), LV_TEXT_ALIGN_RIGHT);
  lv_label_set_long_mode(wifiStatusLbl, LV_LABEL_LONG_DOT);
  lv_obj_set_size(wifiStatusLbl, 360, lv_font_get_line_height(F_S));
  lv_obj_set_pos(wifiStatusLbl, W - PAD - 60 - 360, 36);
  if (!(m.scanning || m.busy)) lv_obj_add_flag(wifiSpin, LV_OBJ_FLAG_HIDDEN);

  if (!password) {
    // listan med nätverk, starkast först
    lv_obj_t* list = box(c);
    lv_obj_add_flag(list, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_scroll_dir(list, LV_DIR_VER);
    lv_obj_set_pos(list, PAD, 104);
    lv_obj_set_size(list, W - 2 * PAD - 300 - GAP, CH - 104);
    lv_obj_set_flex_flow(list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(list, 10, 0);
    lv_obj_set_style_pad_bottom(list, GAP, 0);
    int lw = W - 2 * PAD - 300 - GAP;
    if (m.nets.empty()) {
      label(list, m.scanning ? "Söker efter nätverk ..." : "Hittade inga nätverk. Tryck Sök igen.", F_M, MUTED);
    }
    for (size_t i = 0; i < m.nets.size(); i++) {
      auto& n = m.nets[i];
      lv_obj_t* b = box(list);
      lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
      lv_obj_set_size(b, lw, 84);
      lv_obj_set_style_radius(b, 20, 0);
      lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
      lv_obj_set_style_bg_color(b, C(n.current ? 0x1A4390 : SURFACE), 0);
      lv_obj_set_style_bg_color(b, C(SURFACE2), LV_STATE_PRESSED);
      if (n.current) {
        lv_obj_set_style_border_width(b, 3, 0);
        lv_obj_set_style_border_color(b, C(YELLOW), 0);
      }
      lv_obj_add_style(b, &stTrans, 0);
      lv_obj_add_event_cb(b, onClick, LV_EVENT_CLICKED, pack(Act::WifiPick, i));
      signalBars(b, n.rssi, 24, 25);
      lv_obj_t* nl = oneLine(b, n.ssid, F_MB, INK, lw - 300);
      lv_obj_set_pos(nl, 92, 22);
      std::string right = n.current ? std::string(LV_SYMBOL_OK) + " Ansluten" : n.secure ? std::string("Lösenord") : std::string("Öppet");
      lv_obj_t* rl = label(b, right, F_S, n.current ? GOOD : MUTED);
      lv_obj_align(rl, LV_ALIGN_RIGHT_MID, -24, 0);
    }
    // höger: sök igen och dolt nätverk
    int bx = W - PAD - 300;
    button(c, bx, 104, 300, 100, "Sök igen", Kind::Secondary, Act::WifiScan, 0, LV_SYMBOL_REFRESH);
    button(c, bx, 104 + 100 + GAP, 300, 100, "Dolt nätverk", Kind::Secondary, Act::WifiManual, 0, LV_SYMBOL_EDIT);
    lv_obj_t* hint = label(c, "Tab5 sparar nätverket och använder det nästa gång. Bara 2,4 GHz.", F_S, MUTED);
    lv_label_set_long_mode(hint, LV_LABEL_LONG_WRAP);
    lv_obj_set_width(hint, 300);
    lv_obj_set_pos(hint, bx, 104 + 2 * (100 + GAP) + 10);
    return;
  }

  // lösenordsvyn: ruta(or) överst, tangentbordet under
  lv_obj_t* kb = lv_keyboard_create(c);
  lv_obj_set_size(kb, W, 360);
  lv_obj_align(kb, LV_ALIGN_BOTTOM_MID, 0, 0);
  lv_obj_set_style_bg_color(kb, C(SURFACE), 0);
  lv_obj_set_style_bg_opa(kb, LV_OPA_COVER, 0);
  lv_obj_set_style_pad_all(kb, 8, 0);
  lv_obj_set_style_pad_gap(kb, 8, 0);
  lv_obj_set_style_text_font(kb, F_MB, LV_PART_ITEMS);
  lv_obj_set_style_text_color(kb, C(INK), LV_PART_ITEMS);
  lv_obj_set_style_bg_color(kb, C(SURFACE2), LV_PART_ITEMS);
  lv_obj_set_style_bg_color(kb, C(LINE), (lv_style_selector_t)LV_PART_ITEMS | LV_STATE_PRESSED);
  lv_obj_set_style_bg_color(kb, C(ACCENT2), (lv_style_selector_t)LV_PART_ITEMS | LV_STATE_CHECKED);
  lv_obj_set_style_radius(kb, 12, LV_PART_ITEMS);
  lv_obj_set_style_border_width(kb, 0, LV_PART_ITEMS);
  lv_obj_add_event_cb(kb, onClick, LV_EVENT_READY, pack(Act::WifiConnect, 0));   // ✓ på tangentbordet

  int y = 104, fw = W - 2 * PAD - 300 - GAP;
  if (m.manual) {
    wifiSsid = textarea(c, PAD, y, fw, "Nätverkets namn");
    lv_obj_add_event_cb(wifiSsid, onTextareaFocus, LV_EVENT_FOCUSED, kb);
    y += 76 + 12;
  }
  wifiPass = textarea(c, PAD, y, fw, "Lösenord");
  lv_textarea_set_password_mode(wifiPass, true);
  lv_textarea_set_text(wifiPass, wifiTyped.c_str());
  lv_obj_add_event_cb(wifiPass, onTextareaFocus, LV_EVENT_FOCUSED, kb);
  lv_keyboard_set_textarea(kb, m.manual ? wifiSsid : wifiPass);
  lv_obj_add_state(m.manual ? wifiSsid : wifiPass, LV_STATE_FOCUSED);

  // visa lösenordet
  lv_obj_t* sw = lv_switch_create(c);
  lv_obj_set_size(sw, 84, 44);
  lv_obj_set_pos(sw, W - PAD - 300, y + 16);
  lv_obj_set_style_bg_color(sw, C(SURFACE2), 0);
  lv_obj_set_style_bg_color(sw, C(ACCENT), (lv_style_selector_t)LV_PART_INDICATOR | LV_STATE_CHECKED);
  lv_obj_add_event_cb(sw, onShowPassword, LV_EVENT_VALUE_CHANGED, nullptr);
  lv_obj_t* sl = label(c, "Visa", F_S, MUTED);
  lv_obj_set_pos(sl, W - PAD - 300 + 98, y + 24);
  button(c, W - PAD - 130, y, 130, 76, "Anslut", Kind::Primary, Act::WifiConnect, 0, nullptr, 18);
}

// ---------------------------------------------------------------- inställningar
void entryStatus(const std::string& text, Tone tone, bool busy) { wifiStatus(text, tone, busy); }
std::string entryText() { return wifiPassword(); }

static void onBright(lv_event_t* e) {
  lv_obj_t* s = (lv_obj_t*)lv_event_get_target(e);
  bool done = lv_event_get_code(e) == LV_EVENT_RELEASED;
  queue.push_back({done ? Act::SetBrightDone : Act::SetBright, (int)lv_slider_get_value(s)});
}

/** En ruta i inställningarna: ikon, rubrik, värde, liten text och en knapp till höger. */
static lv_obj_t* settingCard(lv_obj_t* c, int x, int y, int w, int h, const char* sym, const std::string& title,
                             const std::string& value, const std::string& note, uint32_t noteColor,
                             const std::string& btn, Act act) {
  lv_obj_t* p = panel(c, SURFACE, 26);
  lv_obj_set_pos(p, x, y);
  lv_obj_set_size(p, w, h);
  lv_obj_t* ic = panel(p, SURFACE2, 999);
  lv_obj_set_size(ic, 60, 60);
  lv_obj_set_pos(ic, 20, 20);
  lv_obj_t* il = icon(ic, sym, YELLOW);
  lv_obj_center(il);
  lv_obj_t* t = label(p, title, F_S, MUTED);
  lv_obj_set_pos(t, 98, 16);
  lv_obj_t* v = oneLine(p, value, F_MB, INK, w - 98 - (btn.empty() ? 24 : 240));
  lv_obj_set_pos(v, 98, 46);
  if (!note.empty()) {
    lv_obj_t* n = oneLine(p, note, F_S, noteColor, w - 98 - (btn.empty() ? 24 : 240));
    lv_obj_set_pos(n, 98, 86);
  }
  if (!btn.empty()) button(p, w - 220, (h - 72) / 2, 200, 72, btn, Kind::Secondary, act, 0, nullptr, 20);
  return p;
}

void buildSettings(const Settings& m) {
  lv_obj_t* c = fresh();
  button(c, PAD, 14, 200, 72, "Tillbaka", Kind::Ghost, Act::SetBack, 0, LV_SYMBOL_LEFT);
  lv_obj_t* t = label(c, "Inställningar", F_L, INK);
  lv_obj_set_pos(t, PAD + 230, 26);
  if (!m.version.empty()) {
    lv_obj_t* v = label(c, m.version, F_S, MUTED);
    lv_obj_align(v, LV_ALIGN_TOP_RIGHT, -PAD, 36);
  }
  const int y0 = 104, CW = (W - 2 * PAD - GAP) / 2, CHH = 128;
  // vänster: anslutning och elev
  std::string sig = m.rssi >= -55 ? "stark signal" : m.rssi >= -67 ? "bra signal" : m.rssi >= -78 ? "svag signal" : "mycket svag signal";
  settingCard(c, PAD, y0, CW, CHH, LV_SYMBOL_WIFI, "WiFi", m.wifi.empty() ? std::string("Inte ansluten") : m.wifi,
              m.wifi.empty() ? std::string("Tryck Byt WiFi") : sig + " (" + std::to_string(m.rssi) + " dBm)",
              m.wifi.empty() ? WARN : MUTED, "Byt WiFi", Act::SetWifi);
  settingCard(c, PAD, y0 + CHH + GAP, CW, CHH, LV_SYMBOL_DRIVE, "Server", m.server,
              (m.serverOk ? std::string(LV_SYMBOL_OK) + " " : std::string(LV_SYMBOL_WARNING) + " ") + m.serverNote,
              m.serverOk ? GOOD : WARN, "Ändra", Act::SetServer);
  settingCard(c, PAD, y0 + 2 * (CHH + GAP), CW, CHH, SYM_BOOK, "Elev",
              m.student.empty() ? (m.code.empty() ? std::string("Ingen elev vald") : "Kod " + m.code) : m.student,
              m.code.empty() ? std::string("Skriv elevens kod (föräldravyn: Inställningar > Elever)") : "Elevkod " + m.code,
              m.code.empty() ? WARN : MUTED, "Byt elev", Act::SetCode);

  // höger: avstängning och ljusstyrka
  int rx = PAD + CW + GAP;
  lv_obj_t* idleBox = panel(c, SURFACE, 26);
  lv_obj_set_pos(idleBox, rx, y0);
  lv_obj_set_size(idleBox, CW, CHH);
  lv_obj_t* it = label(idleBox, std::string(LV_SYMBOL_POWER) + "  Stäng av efter, minuter (Av = aldrig)", F_S, MUTED);
  lv_obj_set_pos(it, 24, 16);
  static const int mins[6] = {0, 2, 5, 10, 15, 30};
  int bw = (CW - 48 - 5 * 10) / 6;
  for (int i = 0; i < 6; i++) {
    bool on = m.idleMin == mins[i];
    std::string l = mins[i] ? std::to_string(mins[i]) : "Av";
    lv_obj_t* b = button(idleBox, 24 + i * (bw + 10), 52, bw, 60, l, on ? Kind::Primary : Kind::Secondary, Act::SetIdle, mins[i], nullptr, 18);
    if (on) { lv_obj_set_style_border_width(b, 3, 0); lv_obj_set_style_border_color(b, C(YELLOW), 0); }
  }
  lv_obj_t* bright = panel(c, SURFACE, 26);
  lv_obj_set_pos(bright, rx, y0 + CHH + GAP);
  lv_obj_set_size(bright, CW, CHH);
  lv_obj_t* bt = label(bright, std::string(LV_SYMBOL_IMAGE) + "  Ljusstyrka", F_S, MUTED);
  lv_obj_set_pos(bt, 24, 16);
  lv_obj_t* sl = lv_slider_create(bright);
  lv_obj_set_size(sl, CW - 80, 18);
  lv_obj_set_pos(sl, 40, 78);
  lv_slider_set_range(sl, 30, 255);
  lv_slider_set_value(sl, std::max(30, std::min(255, m.brightness)), LV_ANIM_OFF);
  lv_obj_set_style_bg_color(sl, C(SURFACE2), LV_PART_MAIN);
  lv_obj_set_style_bg_opa(sl, LV_OPA_COVER, LV_PART_MAIN);
  lv_obj_set_style_bg_color(sl, C(YELLOW), LV_PART_INDICATOR);
  lv_obj_set_style_bg_color(sl, C(INK), LV_PART_KNOB);
  lv_obj_set_style_pad_all(sl, 10, LV_PART_KNOB);
  lv_obj_set_ext_click_area(sl, 30);
  lv_obj_add_event_cb(sl, onBright, LV_EVENT_VALUE_CHANGED, nullptr);
  lv_obj_add_event_cb(sl, onBright, LV_EVENT_RELEASED, nullptr);

  int by = y0 + 2 * (CHH + GAP);
  int hw = (CW - GAP) / 2;
  button(c, rx, by, hw, CHH, "Detaljer", Kind::Secondary, Act::SetDetails, 0, LV_SYMBOL_LIST);
  button(c, rx + hw + GAP, by, hw, CHH, "Starta om", Kind::Secondary, Act::SetRestart, 0, LV_SYMBOL_REFRESH);
}

void buildEntry(const Entry& m) {
  lv_obj_t* c = fresh();
  wifiTyped.clear();
  button(c, PAD, 14, 200, 72, "Tillbaka", Kind::Ghost, Act::EntryBack, 0, LV_SYMBOL_LEFT);
  lv_obj_t* t = label(c, m.title, F_L, INK);
  lv_obj_set_pos(t, PAD + 230, 26);
  wifiSpin = spinner(c, 40);
  lv_obj_set_pos(wifiSpin, W - PAD - 40, 30);
  lv_obj_add_flag(wifiSpin, LV_OBJ_FLAG_HIDDEN);
  wifiStatusLbl = label(c, "", F_S, MUTED, LV_TEXT_ALIGN_RIGHT);
  lv_label_set_long_mode(wifiStatusLbl, LV_LABEL_LONG_DOT);
  lv_obj_set_size(wifiStatusLbl, 480, lv_font_get_line_height(F_S));
  lv_obj_set_pos(wifiStatusLbl, W - PAD - 60 - 480, 36);

  lv_obj_t* kb = lv_keyboard_create(c);
  lv_obj_set_size(kb, W, 360);
  lv_obj_align(kb, LV_ALIGN_BOTTOM_MID, 0, 0);
  lv_obj_set_style_bg_color(kb, C(SURFACE), 0);
  lv_obj_set_style_pad_all(kb, 8, 0);
  lv_obj_set_style_pad_gap(kb, 8, 0);
  lv_obj_set_style_text_font(kb, F_MB, LV_PART_ITEMS);
  lv_obj_set_style_text_color(kb, C(INK), LV_PART_ITEMS);
  lv_obj_set_style_bg_color(kb, C(SURFACE2), LV_PART_ITEMS);
  lv_obj_set_style_bg_color(kb, C(LINE), (lv_style_selector_t)LV_PART_ITEMS | LV_STATE_PRESSED);
  lv_obj_set_style_bg_color(kb, C(ACCENT2), (lv_style_selector_t)LV_PART_ITEMS | LV_STATE_CHECKED);
  lv_obj_set_style_radius(kb, 12, LV_PART_ITEMS);
  lv_obj_set_style_border_width(kb, 0, LV_PART_ITEMS);
  lv_obj_add_event_cb(kb, onClick, LV_EVENT_READY, pack(Act::EntryOk, 0));

  int fw = W - 2 * PAD - 200 - GAP;
  wifiPass = textarea(c, PAD, 104, fw, m.placeholder.c_str());
  lv_textarea_set_text(wifiPass, m.value.c_str());
  if (m.code) {
    lv_textarea_set_max_length(wifiPass, 5);
    lv_textarea_set_accepted_chars(wifiPass, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789abcdefghjklmnpqrstuvwxyz");
    lv_keyboard_set_mode(kb, LV_KEYBOARD_MODE_TEXT_UPPER);
    lv_obj_set_style_text_letter_space(wifiPass, 12, 0);
  } else {
    lv_keyboard_set_mode(kb, LV_KEYBOARD_MODE_SPECIAL);   // siffror, punkt och kolon
  }
  lv_keyboard_set_textarea(kb, wifiPass);
  lv_obj_add_state(wifiPass, LV_STATE_FOCUSED);
  button(c, W - PAD - 200, 104, 200, 76, "Spara", Kind::Primary, Act::EntryOk, 0, LV_SYMBOL_OK, 18);
  lv_obj_t* h = label(c, m.hint, F_S, MUTED);
  lv_label_set_long_mode(h, LV_LABEL_LONG_WRAP);
  lv_obj_set_width(h, W - 2 * PAD);
  lv_obj_set_pos(h, PAD + 4, 194);
}

// ---------------------------------------------------------------- Öva

static const char* EX_NAMES[6] = {"Snabbquiz", "Lucktext", "Förklara", "Testprov", "Matcha par", "Rätt ordning"};
static const char* EX_SUBS[6] = {"fyra alternativ", "fyll i ordet", "lär ut till Pluggis", "som på riktigt", "slå rekordet", "steg i rad"};
static const char* EX_SYMS[6] = {SYM_QUESTION, LV_SYMBOL_EDIT, SYM_BULB, LV_SYMBOL_FILE, SYM_LINK, SYM_ORDER};
static const uint32_t EX_TINT[6] = {0x4F8EF7, 0x3FA7D6, 0x9A6AF0, 0xE0794A, 0x2FB58C, 0xD45C9A};

void buildOva(const Ova& m) {
  lv_obj_t* c = fresh();

  // --- vänster: läxor och prov (rullas med fingret)
  const int LW = 430;
  lv_obj_t* left = panel(c, SURFACE, 28);
  lv_obj_set_pos(left, PAD, GAP);
  lv_obj_set_size(left, LW, CH - 2 * GAP);

  ovaList = box(left);
  lv_obj_add_flag(ovaList, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(ovaList, LV_DIR_VER);
  lv_obj_set_pos(ovaList, 10, 10);
  lv_obj_set_size(ovaList, LW - 20, CH - 2 * GAP - 20);
  lv_obj_set_flex_flow(ovaList, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(ovaList, 8, 0);
  lv_obj_set_style_pad_ver(ovaList, 8, 0);
  lv_obj_set_style_bg_color(ovaList, C(LINE), LV_PART_SCROLLBAR);
  lv_obj_set_style_bg_opa(ovaList, LV_OPA_COVER, LV_PART_SCROLLBAR);
  lv_obj_set_style_width(ovaList, 6, LV_PART_SCROLLBAR);
  lv_obj_set_style_radius(ovaList, 3, LV_PART_SCROLLBAR);

  const int ROWW = LW - 34;
  bool soonHdr = false, laterHdr = false;
  for (size_t i = 0; i < m.rows.size(); i++) {
    auto& r = m.rows[i];
    if ((r.soon && !soonHdr) || (!r.soon && !laterHdr)) {
      if (r.soon) soonHdr = true; else laterHdr = soonHdr = true;
      lv_obj_t* h = label(ovaList, r.soon ? "SNART" : "LÄNGRE FRAM", F_S, r.soon ? WARN : MUTED);
      lv_obj_set_style_text_letter_space(h, 2, 0);
      lv_obj_set_style_pad_left(h, 12, 0);
      lv_obj_set_style_pad_top(h, i ? 14 : 4, 0);
    }
    bool sel = (int)i == m.selected;
    lv_obj_t* row = box(ovaList);
    lv_obj_add_flag(row, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_size(row, ROWW, 84);
    lv_obj_set_style_radius(row, 18, 0);
    lv_obj_set_style_bg_color(row, C(sel ? 0x1A4390 : SURFACE2), 0);
    lv_obj_set_style_bg_opa(row, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(row, C(0x24509E), LV_STATE_PRESSED);
    lv_obj_add_style(row, &stTrans, 0);
    if (sel) {
      lv_obj_set_style_border_width(row, 3, 0);
      lv_obj_set_style_border_color(row, C(YELLOW), 0);
    }
    lv_obj_add_event_cb(row, onClick, LV_EVENT_CLICKED, pack(Act::OvaSelect, i));
    lv_obj_t* strip = panel(row, r.color, 999);
    lv_obj_set_size(strip, 10, 52);
    lv_obj_align(strip, LV_ALIGN_LEFT_MID, 14, 0);
    lv_obj_t* t = oneLine(row, r.title, F_MB, INK, ROWW - 60);
    lv_obj_set_pos(t, 38, 8);
    lv_obj_t* when = label(row, r.when, F_S, toneHex(r.whenTone));
    lv_obj_set_pos(when, 38, 46);
  }
  lv_obj_update_layout(ovaList);
  lv_obj_scroll_to_y(ovaList, ovaScroll, LV_ANIM_OFF);

  // --- höger: vald läxa, delområden, övningarna
  const int RX = PAD + LW + 24, RW = W - RX - PAD;
  lv_obj_t* meta = chip(c, m.meta, m.color, INK);
  lv_obj_set_pos(meta, RX, GAP + 4);
  lv_obj_t* title = oneLine(c, m.title, F_L, INK, RW);
  lv_obj_set_pos(title, RX, GAP + 62);

  // delområden: en rad som går att rulla i sidled
  lv_obj_t* topics = box(c);
  lv_obj_add_flag(topics, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(topics, LV_DIR_HOR);
  lv_obj_set_scrollbar_mode(topics, LV_SCROLLBAR_MODE_OFF);
  lv_obj_set_pos(topics, RX, GAP + 122);
  lv_obj_set_size(topics, RW, 58);
  lv_obj_set_flex_flow(topics, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(topics, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(topics, 10, 0);
  if (m.topicsLoading) {
    spinner(topics, 34);
    label(topics, "Letar delområden ...", F_S, MUTED);
  } else if (!m.topics.empty()) {
    label(topics, "Öva på:", F_S, MUTED);
    for (int i = 0; i <= (int)m.topics.size() && i < 9; i++) {
      bool on = i == m.focus;
      lv_obj_t* b = box(topics);
      lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
      lv_obj_set_size(b, LV_SIZE_CONTENT, 54);
      lv_obj_set_style_pad_hor(b, 22, 0);
      lv_obj_set_style_radius(b, 999, 0);
      lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
      lv_obj_set_style_bg_color(b, C(on ? ACCENT : SURFACE2), 0);
      lv_obj_set_style_bg_color(b, C(on ? ACCENT2 : LINE), LV_STATE_PRESSED);
      lv_obj_add_style(b, &stTrans, 0);
      std::string t = i == 0 ? std::string("Allt") : m.topics[i - 1];
      if (on) t = std::string(LV_SYMBOL_OK) + " " + t;
      lv_obj_t* l = label(b, t, F_S, on ? INK : 0xC9D6E8);
      lv_obj_set_align(l, LV_ALIGN_LEFT_MID);
      lv_obj_add_event_cb(b, onClick, LV_EVENT_CLICKED, pack(Act::OvaFocus, i));
    }
  }

  // sex övningar i 3 × 2: ikon, namn och vad det är
  const int LASTH = 64, TW = (RW - 2 * GAP) / 3;
  int ty = GAP + 196, TH = (CH - GAP - LASTH - GAP - ty - GAP) / 2;
  for (int i = 0; i < 6; i++) {
    int x = RX + (i % 3) * (TW + GAP), y = ty + (i / 3) * (TH + GAP);
    lv_obj_t* b = box(c);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_pos(b, x, y);
    lv_obj_set_size(b, TW, TH);
    lv_obj_set_style_radius(b, 26, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(b, C(SURFACE2), 0);
    lv_obj_set_style_bg_grad_color(b, C(0x0F2F68), 0);
    lv_obj_set_style_bg_grad_dir(b, LV_GRAD_DIR_VER, 0);
    lv_obj_set_style_border_width(b, 2, 0);
    lv_obj_set_style_border_color(b, C(EX_TINT[i]), 0);
    lv_obj_set_style_border_opa(b, LV_OPA_40, 0);
    lv_obj_set_style_bg_color(b, lv_color_mix(C(EX_TINT[i]), C(SURFACE2), 90), LV_STATE_PRESSED);
    lv_obj_set_style_transform_width(b, -5, LV_STATE_PRESSED);
    lv_obj_set_style_transform_height(b, -5, LV_STATE_PRESSED);
    lv_obj_add_style(b, &stTrans, 0);
    shadow(b, 18, 5);
    lv_obj_add_event_cb(b, onClick, LV_EVENT_CLICKED, pack(Act::OvaExercise, i));
    lv_obj_t* ic = panel(b, EX_TINT[i], 999);
    lv_obj_set_size(ic, 56, 56);
    lv_obj_set_pos(ic, 18, 16);
    lv_obj_t* il = icon(ic, EX_SYMS[i], INK);
    lv_obj_center(il);
    lv_obj_t* go = icon(b, LV_SYMBOL_RIGHT, EX_TINT[i], F_S);
    lv_obj_align(go, LV_ALIGN_TOP_RIGHT, -20, 30);
    lv_obj_t* n = oneLine(b, EX_NAMES[i], F_MB, INK, TW - 36);
    lv_obj_set_pos(n, 20, TH - 76);
    lv_obj_t* s = oneLine(b, EX_SUBS[i], F_S, 0xB4C3D6, TW - 36);
    lv_obj_set_pos(s, 20, TH - 40);
  }

  // längst ner: Senast + Kör igen
  lv_obj_t* last = panel(c, SURFACE, 20);
  lv_obj_set_pos(last, RX, CH - GAP - LASTH);
  lv_obj_set_size(last, RW, LASTH);
  std::string lt = m.last.empty() ? std::string("Välj en läxa till vänster och en övning.") : "Senast: " + m.last;
  lv_obj_t* ll = oneLine(last, lt, F_S, MUTED, RW - (m.canAgain ? 250 : 40));
  lv_obj_align(ll, LV_ALIGN_LEFT_MID, 20, 0);
  if (m.canAgain) button(last, RW - 212, 8, 204, LASTH - 16, "Kör igen", Kind::Secondary, Act::OvaAgain, 0, LV_SYMBOL_LOOP, 18);
}

// ---------------------------------------------------------------- ett kort
static lv_obj_t* header(lv_obj_t* c, const std::string& left, const std::string& right, int value = -1, int max = 0) {
  lv_obj_t* l = oneLine(c, left, F_S, MUTED, W - 2 * PAD - 360);
  lv_obj_set_pos(l, PAD + 8, 14);
  if (!right.empty()) {
    lv_obj_t* r = label(c, right, F_S, INK);
    lv_obj_align(r, LV_ALIGN_TOP_RIGHT, -PAD - 8, 14);
  }
  if (value >= 0) bar(c, PAD, 52, W - 2 * PAD, 10, value, max, YELLOW);
  return l;
}

/** Den stora frågerutan. Tryck på den för att höra frågan igen. */
static lv_obj_t* questionCard(lv_obj_t* c, int y, int h, const std::string& q, const std::string& below, uint32_t belowColor,
                              Act speak, uint32_t edge = 0) {
  lv_obj_t* card = panel(c, SURFACE, 30);
  lv_obj_add_flag(card, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_pos(card, PAD, y);
  lv_obj_set_size(card, W - 2 * PAD, h);
  shadow(card, 26, 8);
  if (edge) {
    lv_obj_set_style_border_width(card, 3, 0);
    lv_obj_set_style_border_color(card, C(edge), 0);
  }
  lv_obj_set_style_bg_color(card, C(0x143672), LV_STATE_PRESSED);
  lv_obj_add_style(card, &stTrans, 0);
  lv_obj_add_event_cb(card, onClick, LV_EVENT_CLICKED, pack(speak, 0));
  lv_obj_t* sp = panel(card, SURFACE2, 999);
  lv_obj_set_size(sp, 56, 56);
  lv_obj_align(sp, LV_ALIGN_TOP_RIGHT, -16, 16);
  lv_obj_t* si = icon(sp, LV_SYMBOL_VOLUME_MAX, MUTED, F_S);
  lv_obj_center(si);
  int bh = below.empty() ? 0 : 56;
  textIn(card, q, 60, 24, W - 2 * PAD - 120, h - 48 - bh, INK, {F_XL, F_L, F_MB, F_M}, LV_TEXT_ALIGN_CENTER, true);
  if (bh) textIn(card, below, 60, h - 24 - bh + 8, W - 2 * PAD - 120, bh - 8, belowColor, {F_M, F_S}, LV_TEXT_ALIGN_CENTER, true);
  return card;
}

/** Fyra alternativ i 2 × 2 med bokstäver A–D. state: 0 vanlig, 1 rätt, 2 fel, 3 vald, 4 nedtonad. */
static void options(lv_obj_t* c, int y, int h, const std::vector<std::string>& opts, const std::vector<int>& state, Act act) {
  int ow = (W - 2 * PAD - GAP) / 2, oh = (h - GAP) / 2;
  static const char* L[4] = {"A", "B", "C", "D"};
  for (size_t i = 0; i < opts.size() && i < 4; i++) {
    int st = i < state.size() ? state[i] : 0;
    Kind k = st == 1 ? Kind::Good : st == 2 ? Kind::Bad : st == 3 ? Kind::Primary : st == 4 ? Kind::Dim : Kind::Secondary;
    int x = PAD + (i % 2) * (ow + GAP), yy = y + (i / 2) * (oh + GAP);
    lv_obj_t* b = button(c, x, yy, ow, oh, "", k, act, i, nullptr, 26);
    lv_obj_t* lbl = lv_obj_get_child(b, 0);
    lv_obj_delete(lbl);
    uint32_t ink = (st == 1) ? BG : st == 4 ? MUTED : INK;
    lv_obj_t* badge = panel(b, st == 1 ? 0x228A54 : st == 2 ? 0xB8403D : st == 3 ? ACCENT2 : LINE, 999);
    lv_obj_set_size(badge, 54, 54);
    lv_obj_align(badge, LV_ALIGN_LEFT_MID, 18, 0);
    const char* mark = st == 1 ? LV_SYMBOL_OK : st == 2 ? LV_SYMBOL_CLOSE : L[i];
    lv_obj_t* bl = label(badge, mark, F_MB, st == 1 ? INK : INK);
    lv_obj_center(bl);
    textIn(b, opts[i], 88, 10, ow - 110, oh - 20, ink, {F_MB, F_M, F_S}, LV_TEXT_ALIGN_LEFT, true);
  }
}

void buildCard(const Card& m) {
  char right[48];
  snprintf(right, sizeof(right), "%d av %d  ·  %d rätt", m.answered, m.round, m.right);
  if (m.done) {
    lv_obj_t* c = fresh(Act::Background);
    header(c, m.deck, "");
    int pct = m.answered ? 100 * m.right / m.answered : 0;
    if (m.answered) {
      ring(c, W / 2 - 150, 60, 300, pct, pct >= 70 ? GOOD : pct >= 40 ? YELLOW : ACCENT);
      textIn(c, m.doneText, 100, 390, W - 200, 110, INK, {F_L, F_MB}, LV_TEXT_ALIGN_CENTER);
    } else {
      textIn(c, m.doneText, 100, 180, W - 200, 200, INK, {F_L, F_MB}, LV_TEXT_ALIGN_CENTER);
    }
    lv_obj_t* h = label(c, "Tryck för att välja en annan övning", F_M, MUTED);
    lv_obj_align(h, LV_ALIGN_BOTTOM_MID, 0, -36);
    return;
  }
  bool hasOptions = m.options.size() >= 2;
  lv_obj_t* c = fresh(hasOptions && m.chosen >= 0 ? Act::CardNext : Act::None);
  header(c, m.deck, right, m.answered, m.round);
  const int qy = 80, BH = 118;
  if (hasOptions) {
    int oh = 2 * 112 + GAP, oy = CH - GAP - oh;
    std::string below = m.chosen >= 0 ? std::string("Tryck för nästa  ") + LV_SYMBOL_RIGHT : m.hint.empty() ? "" : "Ledtråd: " + m.hint;
    questionCard(c, qy, oy - GAP - qy, m.question, below, m.chosen >= 0 ? YELLOW : MUTED, Act::CardSpeak);
    std::vector<int> st(m.options.size(), 0);
    if (m.chosen >= 0)
      for (size_t i = 0; i < st.size(); i++) st[i] = (int)i == m.correct ? 1 : (int)i == m.chosen ? 2 : 4;
    options(c, oy, oh, m.options, st, m.chosen >= 0 ? Act::CardNext : Act::CardOption);
    return;
  }
  int by = CH - GAP - BH;
  int bw = (W - 2 * PAD - GAP) / 2;
  if (!m.revealed) {
    questionCard(c, qy, by - GAP - qy, m.question, m.hint.empty() ? "" : "Ledtråd: " + m.hint, MUTED, Act::CardSpeak);
    if (m.languageDeck) {
      button(c, PAD, by, W - 2 * PAD, BH, "Visa svar", Kind::Primary, Act::CardReveal, 0, LV_SYMBOL_EYE_OPEN);
    } else {
      button(c, PAD, by, bw, BH, "Visa svar", Kind::Secondary, Act::CardReveal, 0, LV_SYMBOL_EYE_OPEN);
      talkButton(c, PAD + bw + GAP, by, bw, BH, "Håll in och svara", m.recording);
    }
    return;
  }
  // svaret visas: frågan överst, svaret i grönt under en linje
  int spokenH = m.spoken.empty() ? 0 : 64;
  int ch = by - GAP - qy - spokenH;
  lv_obj_t* card = panel(c, SURFACE, 30);
  lv_obj_add_flag(card, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_add_event_cb(card, onClick, LV_EVENT_CLICKED, pack(Act::CardSpeak, 0));
  lv_obj_set_pos(card, PAD, qy);
  lv_obj_set_size(card, W - 2 * PAD, ch);
  shadow(card, 26, 8);
  int half = (ch - 40) / 2;
  textIn(card, m.question, 60, 20, W - 2 * PAD - 120, half - 10, MUTED, {F_L, F_MB, F_M}, LV_TEXT_ALIGN_CENTER, true);
  lv_obj_t* line = panel(card, LINE, 2);
  lv_obj_set_size(line, W - 2 * PAD - 200, 3);
  lv_obj_set_pos(line, 100, 20 + half);
  textIn(card, m.answer, 60, 30 + half, W - 2 * PAD - 120, half, GOOD, {F_XL, F_L, F_MB, F_M}, LV_TEXT_ALIGN_CENTER, true);
  if (spokenH) {
    std::string v = m.verdict == 1 ? std::string(LV_SYMBOL_OK) + "  Låter rätt!  " : m.verdict == 0 ? "Jämför med svaret.  " : "";
    textIn(c, v + "Du sa: " + m.spoken, PAD, by - spokenH - 4, W - 2 * PAD, spokenH - 8, m.verdict == 1 ? GOOD : WARN,
           {F_M, F_S}, LV_TEXT_ALIGN_CENTER, true);
  }
  button(c, PAD, by, bw, BH, "Kunde inte", Kind::Bad, Act::CardDidnt, 0, LV_SYMBOL_CLOSE);
  button(c, PAD + bw + GAP, by, bw, BH, "Kunde", Kind::Good, Act::CardKnew, 0, LV_SYMBOL_OK);
}

// ---------------------------------------------------------------- Förklara, Testprov, Matcha par, Rätt ordning
static lv_obj_t* bulletList(lv_obj_t* c, int x, int y, int w, int h) {
  lv_obj_t* l = box(c);
  lv_obj_set_pos(l, x, y);
  lv_obj_set_size(l, w, h);
  lv_obj_set_flex_flow(l, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(l, 10, 0);
  return l;
}
static void bullet(lv_obj_t* list, const char* sym, uint32_t color, const std::string& t, int w) {
  lv_obj_t* r = box(list);
  lv_obj_set_size(r, w, LV_SIZE_CONTENT);
  lv_obj_t* d = panel(r, color, 999);
  lv_obj_set_size(d, 40, 40);
  lv_obj_t* dl = label(d, sym, F_S, BG);
  lv_obj_center(dl);
  lv_obj_t* l = label(r, t, F_M, INK);
  lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
  lv_obj_set_width(l, w - 60);
  lv_obj_set_pos(l, 58, 2);
}

void buildGame(const Game& m) {
  const int BH = 118;
  int by = CH - GAP - BH;
  if (m.kind == 2) {   // Förklara
    lv_obj_t* c = fresh(m.done ? Act::Background : Act::None);
    header(c, m.title, "");
    lv_obj_t* cc = chip(c, std::string("Förklara: ") + m.term, 0x9A6AF0, INK, F_MB);
    lv_obj_set_pos(cc, PAD, 52);
    int y = 124;
    if (!m.heard.empty()) {
      heardBubble(c, "Du: " + m.heard, y, W - 2 * PAD - 200);
      y += 84;
    }
    if (!m.done) {
      lv_obj_t* bub = panel(c, SURFACE, 30);
      lv_obj_add_flag(bub, LV_OBJ_FLAG_CLICKABLE);
      lv_obj_add_event_cb(bub, onClick, LV_EVENT_CLICKED, pack(Act::GameSpeak, 0));
      lv_obj_set_pos(bub, PAD, y);
      lv_obj_set_size(bub, W - 2 * PAD, by - GAP - y);
      textIn(bub, m.reply, 36, 24, W - 2 * PAD - 72, by - GAP - y - 48, INK, {F_L, F_MB, F_M}, LV_TEXT_ALIGN_LEFT, true);
      talkButton(c, PAD, by, W - 2 * PAD, BH, "Håll in och förklara", m.recording);
    } else {
      textIn(c, m.reply, PAD + 8, y, W - 2 * PAD - 16, 100, INK, {F_MB, F_M, F_S});
      lv_obj_t* l = bulletList(c, PAD + 8, y + 110, W - 2 * PAD - 16, CH - y - 110 - 70);
      for (auto& s : m.covered) bullet(l, LV_SYMBOL_OK, GOOD, s, W - 2 * PAD - 16);
      for (auto& s : m.missing) bullet(l, LV_SYMBOL_PLUS, WARN, s, W - 2 * PAD - 16);
      lv_obj_t* h = label(c, "Tryck för att gå tillbaka", F_M, MUTED);
      lv_obj_align(h, LV_ALIGN_BOTTOM_MID, 0, -24);
    }
    return;
  }
  if (m.kind == 3) {   // Testprov
    if (m.done) {
      lv_obj_t* c = fresh(Act::Background);
      header(c, m.title, "");
      ring(c, PAD + 40, 80, 280, m.percent, m.percent >= 70 ? GOOD : m.percent >= 40 ? YELLOW : ACCENT);
      char s[64];
      snprintf(s, sizeof(s), "%d av %d poäng", m.points, m.maxPoints);
      lv_obj_t* t1 = label(c, std::string(SYM_TROPHY) + "  Testprov klart!", F_L, GOOD);
      lv_obj_set_pos(t1, PAD + 360, 86);
      lv_obj_t* t2 = label(c, s, F_M, INK);
      lv_obj_set_pos(t2, PAD + 360, 140);
      if (!m.review.empty()) {
        lv_obj_t* t3 = label(c, "Det här ska du kolla igen:", F_MB, INK);
        lv_obj_set_pos(t3, PAD + 360, 200);
        lv_obj_t* l = bulletList(c, PAD + 360, 250, W - PAD - 360 - PAD, CH - 250 - 70);
        for (auto& r : m.review) bullet(l, LV_SYMBOL_RIGHT, WARN, r, W - PAD - 360 - PAD);
      }
      lv_obj_t* h = label(c, "Rätta svar finns i Pluggis-appen. Tryck för att gå tillbaka.", F_S, MUTED);
      lv_obj_align(h, LV_ALIGN_BOTTOM_MID, 0, -24);
      return;
    }
    lv_obj_t* c = fresh();
    char r[32];
    snprintf(r, sizeof(r), "Fråga %d av %d", m.qIndex + 1, m.qCount);
    header(c, m.title, r, m.qIndex, m.qCount);
    bool hasChoices = m.choices.size() >= 2;
    if (hasChoices) {
      int oh = 2 * 108 + GAP, oy = CH - GAP - oh;
      questionCard(c, 80, oy - GAP - 80, m.question, "", MUTED, Act::GameSpeak);
      std::vector<int> st;
      for (auto& ch : m.choices) st.push_back(ch == m.answer ? 3 : 0);
      options(c, oy, oh, m.choices, st, Act::GameChoice);
    } else {
      int ah = m.answer.empty() ? 0 : 64;
      questionCard(c, 80, by - GAP - 80 - ah, m.question, "", MUTED, Act::GameSpeak);
      if (ah) textIn(c, "Ditt svar: " + m.answer, PAD, by - ah - 4, W - 2 * PAD, ah - 8, GOOD, {F_M, F_S}, LV_TEXT_ALIGN_CENTER, true);
      int bw = (W - 2 * PAD - GAP) / 2;
      talkButton(c, PAD, by, bw, BH, "Håll in och svara", m.recording);
      const char* next = m.answer.empty() ? "Hoppa över" : m.lastQuestion ? "Lämna in" : "Nästa";
      button(c, PAD + bw + GAP, by, bw, BH, next, Kind::Secondary, Act::GameSkip, 0, LV_SYMBOL_RIGHT);
    }
    return;
  }
  if (m.kind == 4) {   // Matcha par
    bool all = !m.doneText.empty();
    lv_obj_t* c = fresh(all ? Act::Background : Act::None);
    header(c, m.title, "Tryck på ett ord och sedan det som hör ihop");
    int n = m.left.size(), top = 64;
    int colW = (W - 2 * PAD - 80) / 2, rowH = std::min(110, (CH - top - GAP - (n - 1) * 14) / std::max(1, n));
    auto kindOf = [](CellState s) {
      return s == CellState::Matched ? Kind::Dim : s == CellState::Selected ? Kind::Primary : s == CellState::Wrong ? Kind::Bad : Kind::Secondary;
    };
    for (int i = 0; i < n; i++) {
      int y = top + i * (rowH + 14);
      lv_obj_t* a = button(c, PAD, y, colW, rowH, m.left[i], kindOf(m.leftState[i]), Act::GameLeft, i, nullptr, 22);
      lv_obj_t* b = button(c, PAD + colW + 80, y, colW, rowH, m.right[i], kindOf(m.rightState[i]), Act::GameRight, i, nullptr, 22);
      if (m.leftState[i] == CellState::Matched) { lv_obj_set_style_border_width(a, 2, 0); lv_obj_set_style_border_color(a, C(GOOD), 0); }
      if (m.rightState[i] == CellState::Matched) { lv_obj_set_style_border_width(b, 2, 0); lv_obj_set_style_border_color(b, C(GOOD), 0); }
      lv_obj_t* link = icon(c, m.leftState[i] == CellState::Matched ? LV_SYMBOL_OK : SYM_LINK,
                            m.leftState[i] == CellState::Matched ? GOOD : LINE);
      lv_obj_set_pos(link, PAD + colW + 26, y + rowH / 2 - 16);
    }
    if (all) {
      lv_obj_t* d = panel(c, GOOD, 32);
      lv_obj_set_size(d, 640, 180);
      lv_obj_center(d);
      shadow(d, 40, 10);
      lv_obj_t* l1 = label(d, m.doneText, F_XL, BG);
      lv_obj_align(l1, LV_ALIGN_CENTER, 0, -18);
      lv_obj_t* l2 = label(d, "Tryck för att gå tillbaka", F_S, BG);
      lv_obj_align(l2, LV_ALIGN_CENTER, 0, 52);
    }
    return;
  }
  // Rätt ordning
  bool seqDone = m.steps.empty();
  lv_obj_t* c = fresh(seqDone ? Act::Background : Act::None);
  char r[32];
  snprintf(r, sizeof(r), "%d av %d", m.seqIndex + 1, m.seqCount);
  header(c, m.title, r);
  lv_obj_t* t = label(c, m.seqTitle, F_L, ACCENT);
  lv_obj_set_pos(t, PAD + 8, 50);
  textIn(c, m.prompt, PAD + 8, 102, W - 2 * PAD - 16, 40, MUTED, {F_M, F_S});
  int y = 150;
  for (size_t k = 0; k < m.placed.size(); k++) {
    if (m.placed.size() - k > 3) continue;   // de tre senaste
    int num = (int)k + 1;
    lv_obj_t* row = panel(c, 0x0E3B3F, 16);
    lv_obj_set_pos(row, PAD, y);
    lv_obj_set_size(row, W - 2 * PAD, 48);
    char nb[16];
    snprintf(nb, sizeof(nb), "%d", num);
    lv_obj_t* n = label(row, nb, F_MB, GOOD);
    lv_obj_align(n, LV_ALIGN_LEFT_MID, 18, 0);
    lv_obj_t* l = oneLine(row, m.placed[k], F_S, INK, W - 2 * PAD - 80);
    lv_obj_align(l, LV_ALIGN_LEFT_MID, 60, 0);
    y += 56;
  }
  if (seqDone) {
    lv_obj_t* d = panel(c, SURFACE, 30);
    lv_obj_set_pos(d, PAD, y + 10);
    lv_obj_set_size(d, W - 2 * PAD, CH - y - 10 - 76);
    lv_obj_t* ok = label(d, std::string(LV_SYMBOL_OK) + "  Rätt ordning!", F_L, GOOD);
    lv_obj_set_pos(ok, 30, 22);
    textIn(d, m.why, 30, 80, W - 2 * PAD - 60, CH - y - 10 - 76 - 100, INK, {F_MB, F_M, F_S});
    lv_obj_t* h = label(c, "Tryck för att fortsätta", F_M, MUTED);
    lv_obj_align(h, LV_ALIGN_BOTTOM_MID, 0, -24);
    return;
  }
  int cols = 2, bw = (W - 2 * PAD - GAP) / 2;
  int rows = (m.steps.size() + 1) / 2;
  int bh = std::min(104, (CH - y - 10 - GAP - (rows - 1) * GAP) / std::max(1, rows));
  for (size_t k = 0; k < m.steps.size() && k < 10; k++) {
    button(c, PAD + (k % cols) * (bw + GAP), y + 10 + (k / cols) * (bh + GAP), bw, bh, m.steps[k],
           (int)k == m.wrongStep ? Kind::Bad : Kind::Secondary, Act::GameStep, k, nullptr, 22);
  }
}

// ---------------------------------------------------------------- Läxor
void buildHwList(const std::vector<HwTile>& tiles) {
  lv_obj_t* c = fresh();
  lv_obj_t* t = label(c, "Läxor och prov", F_L, INK);
  lv_obj_set_pos(t, PAD + 8, 18);
  lv_obj_t* grid = box(c);
  lv_obj_add_flag(grid, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(grid, LV_DIR_VER);
  lv_obj_set_pos(grid, PAD, 80);
  lv_obj_set_size(grid, W - 2 * PAD, CH - 80);
  lv_obj_set_flex_flow(grid, LV_FLEX_FLOW_ROW_WRAP);
  lv_obj_set_style_pad_column(grid, GAP, 0);
  lv_obj_set_style_pad_row(grid, GAP, 0);
  lv_obj_set_style_pad_bottom(grid, GAP, 0);
  int tw = (W - 2 * PAD - 2 * GAP) / 3, th = 250;
  for (size_t i = 0; i < tiles.size(); i++) {
    auto& h = tiles[i];
    lv_obj_t* b = box(grid);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_size(b, tw, th);
    lv_obj_set_style_radius(b, 28, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(b, C(SURFACE), 0);
    lv_obj_set_style_bg_color(b, C(SURFACE2), LV_STATE_PRESSED);
    lv_obj_set_style_transform_width(b, -5, LV_STATE_PRESSED);
    lv_obj_set_style_transform_height(b, -5, LV_STATE_PRESSED);
    lv_obj_add_style(b, &stTrans, 0);
    lv_obj_add_event_cb(b, onClick, LV_EVENT_CLICKED, pack(Act::HwOpen, i));
    lv_obj_t* band = panel(b, h.color, 0);
    lv_obj_set_size(band, tw, 12);
    lv_obj_set_pos(band, 0, 0);
    lv_obj_set_style_radius(b, 28, 0);
    lv_obj_set_style_clip_corner(b, true, 0);
    lv_obj_t* k = chip(b, h.kind, h.test ? 0x4A3A1A : 0x1A4390, h.test ? WARN : 0x9CC0FF);
    lv_obj_set_pos(k, 22, 30);
    textIn(b, h.title, 24, 88, tw - 48, 100, INK, {F_L, F_MB, F_M});
    if (!h.due.empty()) {
      lv_obj_t* d = label(b, h.due, F_S, MUTED);
      lv_obj_align(d, LV_ALIGN_BOTTOM_LEFT, 24, -20);
    }
    lv_obj_t* go = icon(b, LV_SYMBOL_VOLUME_MAX, MUTED, F_S);
    lv_obj_align(go, LV_ALIGN_BOTTOM_RIGHT, -24, -20);
  }
}

void buildHwDetail(const HwDetail& m) {
  lv_obj_t* c = fresh();
  lv_obj_t* meta = label(c, m.meta, F_S, m.test ? WARN : ACCENT);
  lv_obj_set_pos(meta, PAD + 8, 14);
  textIn(c, m.title, PAD + 8, 46, W - 2 * PAD - 16, 52, INK, {F_L, F_MB});
  int y = 108;
  if (!m.about.empty()) {
    textIn(c, m.about, PAD + 8, y, W - 2 * PAD - 16, 64, MUTED, {F_S});
    y += 74;
  }
  const int BH = 112;
  int by = CH - GAP - BH;
  lv_obj_t* p = panel(c, SURFACE, 30);
  lv_obj_set_pos(p, PAD, y);
  lv_obj_set_size(p, W - 2 * PAD, by - GAP - y);
  int ph = by - GAP - y;
  if (!m.hasMaterial) {
    textIn(p, "Det finns inget inläst material att lyssna på. Fota sidorna i Pluggis-appen.", 40, 30, W - 2 * PAD - 80,
           ph - 60, MUTED, {F_MB, F_M}, LV_TEXT_ALIGN_CENTER, true);
  } else {
    lv_obj_t* head = label(p, m.head, F_MB, INK);
    lv_obj_set_pos(head, 32, 22);
    char pc[16];
    snprintf(pc, sizeof(pc), "%d %%", m.percent);
    lv_obj_t* pl = label(p, pc, F_S, MUTED);
    lv_obj_align(pl, LV_ALIGN_TOP_RIGHT, -32, 26);
    bar(p, 32, 70, W - 2 * PAD - 64, 12, m.percent, 100, YELLOW, BG);
    textIn(p, m.line, 32, 100, W - 2 * PAD - 64, ph - 124, m.listening ? INK : MUTED, {F_L, F_MB, F_M}, LV_TEXT_ALIGN_LEFT, true);
  }
  button(c, PAD, by, 230, BH, "Tillbaka", Kind::Ghost, Act::HwBack, 0, LV_SYMBOL_LEFT);
  if (m.hasMaterial) {
    int x = PAD + 230 + GAP, nw = 250, pw = W - 2 * PAD - 230 - 2 * nw - 3 * GAP;
    button(c, x, by, nw, BH, "Förra sida", Kind::Secondary, Act::HwPrev, 0, LV_SYMBOL_PREV);
    button(c, x + nw + GAP, by, pw, BH, m.playLabel, m.listening ? Kind::Warn : Kind::Primary, Act::HwPlay, 0,
           m.listening ? LV_SYMBOL_PAUSE : LV_SYMBOL_PLAY);
    button(c, x + nw + GAP + pw + GAP, by, nw, BH, "Nästa sida", Kind::Secondary, Act::HwNext, 0, LV_SYMBOL_NEXT);
  }
}

// ---------------------------------------------------------------- Plan
void buildPlan(const Plan& m) {
  lv_obj_t* c = fresh();
  if (m.hasPrev) button(c, PAD, 14, 90, 76, LV_SYMBOL_LEFT, Kind::Secondary, Act::PlanPrev, 0, nullptr, 999);
  if (m.hasNext) button(c, W - PAD - 90, 14, 90, 76, LV_SYMBOL_RIGHT, Kind::Secondary, Act::PlanNext, 0, nullptr, 999);
  lv_obj_t* d = label(c, m.day, F_L, INK);
  lv_obj_align(d, LV_ALIGN_TOP_MID, 0, 12);
  lv_obj_t* s = label(c, m.summary, F_S, m.doneMinutes >= m.totalMinutes ? GOOD : MUTED);
  lv_obj_align(s, LV_ALIGN_TOP_MID, 0, 60);
  bar(c, 300, 100, W - 600, 12, m.doneMinutes, m.totalMinutes, GOOD);

  lv_obj_t* list = box(c);
  lv_obj_add_flag(list, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(list, LV_DIR_VER);
  lv_obj_set_pos(list, PAD, 132);
  lv_obj_set_size(list, W - 2 * PAD, CH - 132);
  lv_obj_set_flex_flow(list, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(list, 10, 0);
  lv_obj_set_style_pad_bottom(list, GAP, 0);
  // Varje moment: bock till vänster, ämne och minuter på första raden, uppgiften (högst två rader) under
  const int RW = W - 2 * PAD, TX = 92, TW = RW - TX - 28;
  const int lh = lv_font_get_line_height(F_M) + 6;
  for (size_t k = 0; k < m.items.size(); k++) {
    auto& r = m.items[k];
    lv_point_t sz;
    lv_text_get_size(&sz, r.task.c_str(), F_M, 0, 6, TW, LV_TEXT_FLAG_NONE);
    int taskH = std::min<int>(sz.y, 2 * lh);
    int rowH = 18 + 30 + 6 + taskH + 18;
    lv_obj_t* b = box(list);
    lv_obj_add_flag(b, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_size(b, RW, rowH);
    lv_obj_set_style_radius(b, 22, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(b, C(r.done ? 0x0B3346 : SURFACE), 0);
    lv_obj_set_style_bg_color(b, C(SURFACE2), LV_STATE_PRESSED);
    lv_obj_add_style(b, &stTrans, 0);
    lv_obj_add_event_cb(b, onClick, LV_EVENT_CLICKED, pack(Act::PlanRow, k));
    lv_obj_t* strip = panel(b, r.color, 999);
    lv_obj_set_size(strip, 8, rowH - 28);
    lv_obj_align(strip, LV_ALIGN_LEFT_MID, 12, 0);
    lv_obj_t* cb = panel(b, r.done ? GOOD : BG, 12);
    lv_obj_set_size(cb, 48, 48);
    lv_obj_align(cb, LV_ALIGN_LEFT_MID, 30, 0);
    lv_obj_add_flag(cb, LV_OBJ_FLAG_CLICKABLE);   // bocken bockar av (raden kan göra annat, t.ex. läsa upp)
    lv_obj_set_ext_click_area(cb, 16);
    lv_obj_add_event_cb(cb, onClick, LV_EVENT_CLICKED, pack(Act::PlanCheck, k));
    if (!r.done) {
      lv_obj_set_style_border_width(cb, 3, 0);
      lv_obj_set_style_border_color(cb, C(MUTED), 0);
    } else {
      lv_obj_t* ok = label(cb, LV_SYMBOL_OK, F_MB, BG);
      lv_obj_center(ok);
    }
    char mn[16];
    snprintf(mn, sizeof(mn), "%d min", r.minutes);
    lv_obj_t* ml = label(b, mn, F_S, MUTED);
    lv_obj_align(ml, LV_ALIGN_TOP_RIGHT, -24, 18);
    if (r.action && !r.done) {
      lv_obj_t* lc = chip(b, r.action == 1 ? std::string(LV_SYMBOL_VOLUME_MAX) + "  Lyssna" : std::string(LV_SYMBOL_PLAY) + "  Starta",
                          r.action == 1 ? ACCENT : 0x2FB58C, INK);
      lv_obj_update_layout(ml);
      lv_obj_align(lc, LV_ALIGN_TOP_RIGHT, -24 - lv_obj_get_width(ml) - 16, 10);
    }
    if (!r.subject.empty()) {
      lv_obj_t* sl = oneLine(b, r.subject, F_S, lv_color_to_u32(lv_color_lighten(C(r.color), 110)) & 0xFFFFFF, TW - (r.action && !r.done ? 320 : 140));
      lv_obj_set_pos(sl, TX, 18);
    }
    lv_obj_t* tl = label(b, r.task, F_M, r.done ? MUTED : INK);
    lv_label_set_long_mode(tl, LV_LABEL_LONG_DOT);
    lv_obj_set_size(tl, TW, taskH);
    lv_obj_set_pos(tl, TX, 18 + 30 + 6);
    if (r.done) lv_obj_set_style_text_decor(tl, LV_TEXT_DECOR_STRIKETHROUGH, 0);
  }
}

// ---------------------------------------------------------------- Chat (coachen)
void buildCoach(const Coach& m) {
  lv_obj_t* c = fresh(m.cardMode && m.answered ? Act::Background : Act::None);
  const int BH = 124;
  int by = CH - GAP - BH;
  int bottom = by - GAP - (m.session ? 72 + 10 : 0);

  if (m.cardMode) {
    uint32_t edge = !m.answered ? ACCENT : m.correct ? GOOD : BAD;
    lv_obj_t* card = panel(c, SURFACE, 30);
    lv_obj_set_pos(card, PAD, GAP);
    lv_obj_set_size(card, W - 2 * PAD, bottom - GAP);
    lv_obj_set_style_border_width(card, 4, 0);
    lv_obj_set_style_border_color(card, C(edge), 0);
    shadow(card, 26, 8);
    std::string head = m.answered ? (m.correct ? std::string(LV_SYMBOL_OK) + "  Rätt!" : "Inte riktigt") : "Flashkort";
    lv_obj_t* hl = label(card, head, F_MB, m.answered ? edge : MUTED);
    lv_obj_set_pos(hl, 30, 22);
    if (m.answered && m.box > 0) {
      char bx[24];
      snprintf(bx, sizeof(bx), "Fack %d av 5", m.box);
      lv_obj_t* bl = label(card, bx, F_S, MUTED);
      lv_obj_align(bl, LV_ALIGN_TOP_RIGHT, -30, 26);
    }
    int ch = bottom - GAP;
    std::string body = m.answered ? m.front + "\n\n" + m.back : m.front;
    int hintH = !m.hint.empty() && !m.answered ? 56 : 0;
    textIn(card, body, 50, 74, W - 2 * PAD - 100, ch - 74 - 24 - hintH, m.answered && m.correct ? GOOD : INK,
           {F_XL, F_L, F_MB, F_M}, LV_TEXT_ALIGN_CENTER, true);
    if (hintH) textIn(card, "Ledtråd: " + m.hint, 50, ch - 24 - hintH, W - 2 * PAD - 100, hintH, MUTED, {F_M, F_S}, LV_TEXT_ALIGN_CENTER, true);
  } else {
    int y = GAP;
    if (!m.heard.empty()) {
      heardBubble(c, m.heard, y, W - 2 * PAD - 300);
      y += 82;
    }
    // Pluggis figur till vänster om svaret
    int avW = 72;
    if (hasAvatar) {
      lv_obj_t* av = lv_image_create(c);
      lv_image_set_src(av, &avatarImg);
      lv_obj_set_pos(av, PAD, y);
      avW = avatarImg.header.w;
    } else {
      lv_obj_t* av = panel(c, ACCENT, 999);
      lv_obj_set_size(av, 72, 72);
      lv_obj_set_pos(av, PAD, y);
      lv_obj_t* avl = label(av, "P", F_L, INK);
      lv_obj_center(avl);
    }
    lv_obj_t* bub = panel(c, SURFACE, 30);
    lv_obj_set_pos(bub, PAD + avW + 20, y);
    lv_obj_set_size(bub, W - 2 * PAD - avW - 20, bottom - y);
    lv_obj_set_style_radius(bub, 30, 0);
    if (m.thinking) {
      lv_obj_t* tl = label(bub, "Pluggis tänker", F_L, YELLOW);
      lv_obj_set_pos(tl, 36, 30);
      thinkingDots(bub, 330, 42);
    } else {
      textIn(bub, m.say, 36, 26, W - 2 * PAD - avW - 20 - 72, bottom - y - 52, INK, {F_L, F_MB, F_M, F_S});
    }
  }

  // timern (uppdateras på plats av coachTimer)
  timerBox = panel(c, SURFACE, 999);
  lv_obj_set_pos(timerBox, PAD, by - GAP - 72);
  lv_obj_set_size(timerBox, W - 2 * PAD, 72);
  timerBar = bar(timerBox, 0, 0, W - 2 * PAD, 72, 0, 1, GOOD, SURFACE);
  timerLbl = label(timerBox, "", F_MB, INK);
  lv_obj_align(timerLbl, LV_ALIGN_LEFT_MID, 28, 0);
  if (!m.session) lv_obj_add_flag(timerBox, LV_OBJ_FLAG_HIDDEN);

  int talkW = W / 2 - GAP, sideW = (W - 2 * PAD - talkW - 2 * GAP) / 2;
  button(c, PAD, by, sideW, BH, m.session ? "Hjälp" : "Börja", Kind::Secondary, Act::CoachStart, 0,
         m.session ? LV_SYMBOL_BELL : LV_SYMBOL_PLAY);
  talkButton(c, PAD + sideW + GAP, by, talkW, BH, "Håll in och prata", m.recording);
  button(c, PAD + sideW + GAP + talkW + GAP, by, sideW, BH, "Avsluta", Kind::Secondary, Act::CoachEnd, 0, LV_SYMBOL_STOP);
}

void coachTimer(bool visible, int left, int total, const std::string& task) {
  if (!timerBox) return;
  if (!visible) { lv_obj_add_flag(timerBox, LV_OBJ_FLAG_HIDDEN); return; }
  lv_obj_remove_flag(timerBox, LV_OBJ_FLAG_HIDDEN);
  lv_bar_set_range(timerBar, 0, std::max(1, total));
  lv_bar_set_value(timerBar, std::max(0, std::min(left, total)), LV_ANIM_ON);
  lv_obj_set_style_bg_color(timerBar, C(left < 120 ? WARN : GOOD), LV_PART_INDICATOR);
  lv_obj_set_style_bg_opa(timerBar, LV_OPA_70, LV_PART_INDICATOR);
  char mm[16];
  snprintf(mm, sizeof(mm), "%d:%02d", left / 60, left % 60);
  std::string t = std::string(SYM_CLOCK) + "  " + mm + (task.empty() ? "" : "   " + task);
  lv_label_set_text(timerLbl, t.c_str());
}

}  // namespace ui
