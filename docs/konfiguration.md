# Vad Pluggis behöver – nycklar, tokens och konton

En samlad lista över allt som ska fyllas i för att Pluggis ska fungera: vad som är **måste** och vad som är **valfritt**, var du får tag i det, var det läggs och vad det kostar.

Allt hemligt ligger på två ställen, och inget av dem checkas in i git:

| Var | Vad |
|---|---|
| `.env` bredvid `docker-compose.yml` | Nycklar och tokens som text (kopiera från `.env.example`) |
| `secrets/` bredvid `docker-compose.yml` | Hemligheter som filer (Google-nyckeln) |

Ändrat något i `.env` eller `secrets/`? Kör `docker compose up -d` så läses det in.

---

## Översikt

| Vad | Måste? | Var | Kostnad |
|---|---|---|---|
| [Föräldertoken](#1-föräldertoken-pluggis_device_token) `PLUGGIS_DEVICE_TOKEN` | **Ja** | `.env` | – |
| [Claude](#2-claude--välj-ett-av-tre-sätt): API-nyckel `ANTHROPIC_API_KEY` och/eller abonnemangstoken `CLAUDE_CODE_OAUTH_TOKEN` | **Ja** (minst en) | `.env` | Per token, eller ingår i Pro/Max |
| [Elevkoder](#3-elevkoder) | Skapas av Pluggis | Admin → Inställningar → Elever | – |
| [Azure-röster](#4-azure-röster-valfritt) `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION` | Nej | `.env` | Gratis (F0) |
| [Google Kalender](#5-google-kalender-valfritt): tjänstekontots JSON-nyckel + kalender-id | Nej | `secrets/` + admin | Gratis |
| [InfoMentor-kalendern](#6-infomentor-kalendern-valfritt) (webcals-länk) | Nej | Admin → Läxor & prov → Kalender | – |

**Minsta möjliga `.env`:**

```bash
PLUGGIS_DEVICE_TOKEN=<lång slumpad sträng>
ANTHROPIC_API_KEY=sk-ant-api03-...
```

---

## 1. Föräldertoken (`PLUGGIS_DEVICE_TOKEN`)

Lösenordet till allt utom elevchatten: föräldravyn, iOS-genvägen och curl.

- **Skapa:** `openssl rand -hex 24` (eller valfri lång slumpad sträng).
- **Lägg:** `PLUGGIS_DEVICE_TOKEN=...` i `.env`.
- **Används i:**
  - Föräldravyn: Inställningar → Token (sparas i webbläsaren, en gång per dator eller telefon).
  - iOS-genvägen och curl: headern `X-Pluggis-Token`.
- **Saknas den** är API:t öppet för alla på nätet. Loggen varnar då: *"pluggis.device-token är inte satt – API:t är öppet"*.
- **Byta:** ändra i `.env`, kör `docker compose up -d` och uppdatera token i föräldravyn och i genvägen.

## 2. Claude – välj ett av tre sätt

Pluggis behöver Claude för coachen, foton, planer och flashkort. Läget väljs med `PLUGGIS_CLAUDE_BACKEND` i `.env` och kan sedan bytas i drift under **admin → Kostnad**. Valet där sparas och gäller före `.env`.

| Läge | `.env` | Passar när |
|---|---|---|
| `api` (standard) | `ANTHROPIC_API_KEY` | Enklast och snabbast. Betala per token. |
| `claude-code` | `CLAUDE_CODE_OAUTH_TOKEN` | Allt på ditt Pro/Max-abonnemang. Rösten blir någon sekund långsammare. |
| `hybrid` | **båda** | Rösten via API-nyckeln, resten via abonnemanget. Faller tillbaka till API-nyckeln om abonnemanget inte går. |

### API-nyckel (`ANTHROPIC_API_KEY`)

1. Logga in på [console.anthropic.com](https://console.anthropic.com).
2. Lägg in betalning under *Billing*. Sätt gärna en månadsgräns under *Limits*.
3. Skapa en nyckel under *API keys* (börjar med `sk-ant-api03-`).
4. Lägg in `ANTHROPIC_API_KEY=...` i `.env`.

Vad det kostar syns i **admin → Kostnad**. Den exakta fakturan finns i Console.

### Abonnemangstoken (`CLAUDE_CODE_OAUTH_TOKEN`)

Kräver Claude Pro eller Max.

1. Installera Claude Code på din dator: `npm install -g @anthropic-ai/claude-code`.
2. Kör `claude setup-token`. Webbläsaren öppnas för inloggning, och sedan skrivs en token ut (börjar med `sk-ant-oat01-`).
3. Lägg i `.env`:
   ```bash
   PLUGGIS_CLAUDE_BACKEND=claude-code   # eller hybrid
   CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-...
   ```

Bra att veta:
- **Giltighet:** token gäller i ett år. Kör `claude setup-token` igen när den har gått ut.
- **Gränser:** Pluggis delar abonnemangets gränser med din egen användning av Claude.
- **Status:** admin → Kostnad visar om token och `claude`-programmet finns, lyckade och misslyckade anrop och senaste fel.
- **Bara eget bruk:** abonnemanget är till för eget bruk hemma. Läs Anthropics villkor, som README länkar till.

Övriga Claude-inställningar (valfria):

| Variabel | Standard | Vad |
|---|---|---|
| `PLUGGIS_CLAUDE_API_PURPOSES` | `coach` | Vilka jobb som går via API-nyckeln i hybridläget |
| `PLUGGIS_COACH_MODEL` | `claude-haiku-5-5` | Modell för röstcoachen |
| `PLUGGIS_COACH_EFFORT` / `PLUGGIS_COACH_THINKING` | `low` / `disabled` | Snabbhet för coachen |
| `PLUGGIS_VISION_MODEL`, `PLUGGIS_PLANNING_MODEL`, `PLUGGIS_FLASHCARDS_MODEL` | `claude-sonnet-5-5` | Foto, planer, flashkort |
| `PLUGGIS_CLAUDE_TIMEOUT` | `120` | Sekunder innan ett anrop via abonnemanget ges upp |
| `PLUGGIS_USD_SEK` | `10.0` | Växelkurs för kostnadsfliken |

## 3. Elevkoder

Skapas av Pluggis och behöver inte ligga i `.env`.

- **Var:** varje elev har en fast kod på 5 tecken under **admin → Inställningar → Elever**. Koden är elevens inloggning i mobilchatten (`/chat.html#k=KOD`).
- **Behörighet:** med koden når eleven bara sin egen chatt, plan, läxor, kort och poäng.
- **Byta:** *Ny kod* gör den gamla ogiltig direkt.

## 4. Azure-röster (valfritt)

Bättre svensk uppläsning (Sofie, Hillevi, Mattias). Utan Azure används Piper, som körs lokalt.

1. Gå till [portal.azure.com](https://portal.azure.com) → *Skapa en resurs* → **Speech**.
2. Välj en resursgrupp (skapa en ny, t.ex. *pluggis*), region **Sweden Central** och prisnivå **Free F0**. F0 kan inte debiteras och ger 500 000 tecken per månad.
3. Kopiera *Key 1* och regionen under *Keys and Endpoint* och lägg i `.env`:
   ```bash
   AZURE_SPEECH_KEY=...
   AZURE_SPEECH_REGION=swedencentral
   ```
4. Loggen visar *"Azure-röster påslagna (region swedencentral)"*. Välj röst under admin → Inställningar → Röst.

Bara coachens svarstext skickas till Azure, aldrig elevens röst. Är gratisgränsen nådd tar Piper över av sig själv. Förbrukningen syns i admin → Kostnad.

## 5. Google Kalender (valfritt)

Lägger in planen som pass i elevens Google Kalender.

1. Gå till [console.cloud.google.com](https://console.cloud.google.com) och skapa ett projekt.
2. Aktivera **Google Calendar API** under *APIs & Services → Library*.
3. Skapa ett tjänstekonto under *IAM & Admin → Service Accounts*. Det behöver inga roller. Välj *Keys → Add key → JSON*.
4. Lägg JSON-filen i **`secrets/`**. Filnamnet spelar ingen roll. Loggen visar *"Google Kalender påslagen (tjänstekonto …)"*.
5. Dela elevens kalender med tjänstekontot:
   - Logga in som eleven, eller som någon med rätt att dela kalendern.
   - Öppna kalenderns inställningar → *Dela med specifika personer*.
   - Lägg till tjänstekontots adress (`…@….iam.gserviceaccount.com`, visas också i admin) med rätten **Göra ändringar i händelser**.
6. Kopiera **Kalender-id** under *Integrera kalender* i kalenderns inställningar. För elevens huvudkalender är det elevens Gmail-adress.
7. Klistra in id:t under admin → Inställningar → Google Kalender och tryck *Koppla och synka*.

Vill du själv se elevens kalender behöver eleven också dela den med ditt konto. Om du vill ha bort nyckeln tar du bort filen ur `secrets/` och nyckeln under tjänstekontots *Keys* i Google Cloud.

## 6. InfoMentor-kalendern (valfritt)

Läxor och prov hämtas från skolans kalenderprenumeration.

- **Var:** admin → Läxor & prov → 📅 Kalender. Klistra in `webcals://…`-länken från InfoMentor och tryck *Spara och synka*.
- **Synk:** sker varje timme.
- **Personlig länk:** behandla den som ett lösenord. Den sparas bara i Pluggis databas och ska inte in i `.env` eller i git.

---

## Övriga inställningar (valfria, har standardvärden)

| Variabel | Standard | Vad |
|---|---|---|
| `PLUGGIS_MAX_MINUTES_PER_DAY` | `40` | Max plugg per dag för nya elever (ändras per elev i admin) |
| `PLUGGIS_STUDY_DAYS` | `MONDAY,…,THURSDAY,SUNDAY` | Pluggdagar för nya elever (ändras per elev i admin) |
| `PLUGGIS_REMINDERS_ENABLED` | `true` | Påminnelser i chatten |
| `PLUGGIS_REWARDS_ENABLED` | `true` | Om coachen pratar om veckopeng (poäng av/på per elev finns i admin) |
| `PLUGGIS_SEK_PER_POINT` / `PLUGGIS_WEEKLY_CAP_SEK` | `0.10` / `50` | Kronor per poäng och tak per vecka |
| `PLUGGIS_SPEECH_URL` | sätts av docker-compose | Ljudtjänsten (Whisper och Piper) |
| `SPRING_PROFILES_ACTIVE=postgres` + `DB_URL`, `DB_USER`, `DB_PASSWORD` | H2-fil | Postgres i stället för den inbyggda databasen |

## Kontrollera att allt är på plats

Efter `docker compose up -d --build`:

```bash
docker compose logs pluggis | grep -E "Claude körs|Azure|Google Kalender|device-token"
```

| Du vill se | Betyder |
|---|---|
| `Claude körs via API-nyckel` / `via Claude Code` / `hybrid` | Claude är inställt |
| `Azure-röster påslagna` | Azure fungerar (annars Piper) |
| `Google Kalender påslagen (tjänstekonto …)` | Google-nyckeln hittades |
| **Ingen** rad med `device-token är inte satt` | Föräldertoken är satt |

I föräldravyn visar **Kostnad** status för API-nyckel, Claude Code-token och `claude`-programmet. **Inställningar → Google Kalender** visar de närmaste passen med länkar.

## Om något läcker

| Vad | Gör så här |
|---|---|
| `PLUGGIS_DEVICE_TOKEN` | Ny sträng i `.env` → `docker compose up -d` → uppdatera föräldravyn och genvägen |
| `ANTHROPIC_API_KEY` | Ta bort nyckeln i console.anthropic.com → skapa ny → `.env` |
| `CLAUDE_CODE_OAUTH_TOKEN` | Kör `claude setup-token` för en ny token → `.env`. Byt även lösenord på ditt Claude-konto om du misstänker missbruk |
| `AZURE_SPEECH_KEY` | *Regenerate key* i Azure-portalen → `.env` |
| Google-nyckeln | Ta bort nyckeln under tjänstekontots *Keys* → skapa ny → `secrets/` |
| Elevkod | *Ny kod* under admin → Inställningar → Elever |
| InfoMentor-länken | Skapa en ny prenumerationslänk i InfoMentor → klistra in i admin |
