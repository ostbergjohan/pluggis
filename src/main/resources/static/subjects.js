// Samma ämnessymbol och färg överallt (chatt, admin). Symbolerna motsvarar SubjectIcon.java.
const SUBJECT_ICONS = [['matte', '➗'], ['matem', '➗'], ['engelsk', '🇬🇧'], ['svensk', '📖'], ['spansk', '🗣️'], ['tysk', '🗣️'],
  ['fransk', '🗣️'], ['moderna språk', '🗣️'], ['no', '🧪'], ['biolog', '🧪'], ['kemi', '🧪'], ['fysik', '🧪'], ['so', '🌍'],
  ['histor', '🌍'], ['geograf', '🌍'], ['samhäll', '🌍'], ['religion', '🌍'], ['teknik', '⚙️'], ['idrott', '⚽'], ['musik', '🎵'],
  ['bild', '🎨'], ['slöjd', '🧵'], ['hemkunskap', '🍳'],
  // English names (students using Pluggis in English)
  ['math', '➗'], ['english', '🇬🇧'], ['swedish', '📖'], ['spanish', '🗣️'], ['german', '🗣️'], ['french', '🗣️'],
  ['languages', '🗣️'], ['science', '🧪'], ['chemi', '🧪'], ['physic', '🧪'], ['social studies', '🌍'], ['technolog', '⚙️'],
  ['physical education', '⚽'], ['pe', '⚽'], ['music', '🎵'], ['art', '🎨'], ['craft', '🧵'], ['home economics', '🍳']];
const SUBJECT_COLORS = ['#2f6f5e', '#2c63c9', '#b7862a', '#c8453b', '#7a4fb5', '#1f8a9e', '#c2569b', '#5c7d2e'];
function subjectIcon(subject, title) {
  const s = `${subject || ''} ${title || ''}`.toLowerCase();
  for (const [k, icon] of SUBJECT_ICONS) {
    const hit = k.length <= 3 ? new RegExp(`(^|[^\\p{L}])${k}([^\\p{L}]|$)`, 'u').test(s) : s.includes(k);
    if (hit) return icon;
  }
  return '📘';
}
/** Fast färg per ämne (eller per läxa om ämne saknas). */
function subjectColor(subject, title) {
  const key = (subject || title || '').trim().toLowerCase();
  let h = 0; for (const c of key) h = (h * 31 + c.codePointAt(0)) >>> 0;
  return SUBJECT_COLORS[h % SUBJECT_COLORS.length];
}
/** Rubrik för ett block: "NO · Prov fotosyntes" (ämnet bara om det inte redan står i titeln). */
function blockLabel(x) {
  if (!x) return window.I18N && I18N.lang === 'en' ? 'Other' : 'Övrigt';
  const subj = (x.subject || '').trim();
  const en = window.I18N && I18N.lang === 'en';
  const t = (x.kind === 'TEST' && !/prov|test|exam/i.test(x.title) ? (en ? 'Test: ' : 'Prov: ') : '') + x.title;
  return subj && !x.title.toLowerCase().includes(subj.toLowerCase()) ? `${subj} · ${t}` : t;
}
