# What Pluggis needs – keys, tokens and accounts

A single list of everything you need to fill in for Pluggis to work: what's **required** and what's **optional**,
where you get it, where it goes and what it costs.

Everything secret is in two places, and neither of them is checked into git:

| Where | What |
|---|---|
| `.env` next to `docker-compose.yml` | Keys and tokens as text (copy from `.env.example`) |
| `secrets/` next to `docker-compose.yml` | Secrets as files (the Google key) |

Changed something in `.env` or `secrets/`? Run `docker compose up -d` and it's read in.

---

## Overview

| What | Required? | Where | Cost |
|---|---|---|---|
| [Parent view password](#1-parent-view-password-pluggis_admin_password) `PLUGGIS_ADMIN_PASSWORD` | **Yes** (the first time) | `.env` | – |
| [Claude](#2-claude--choose-one-of-three-ways): API key `ANTHROPIC_API_KEY` and/or subscription token `CLAUDE_CODE_OAUTH_TOKEN` | **Yes** (at least one) | `.env` | Per token, or included in Pro/Max |
| [Student codes](#3-student-codes) | Created by Pluggis | Admin → System → Students | – |
| [Azure voices](#4-azure-voices-optional) `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION` | No | `.env` | Free (F0) |
| [Google Calendar](#5-google-calendar-optional): the service account's JSON key + calendar ID | No | `secrets/` + admin | Free |
| [School calendar](#6-the-schools-calendar-optional) (InfoMentor or any iCal/webcal link) | No | Admin → the student's tab → Calendar | – |

**Smallest possible `.env`:**

```bash
PLUGGIS_ADMIN_PASSWORD=<at least 8 characters>
ANTHROPIC_API_KEY=sk-ant-api03-...
```

---

## 1. Parent view password (`PLUGGIS_ADMIN_PASSWORD`)

The parent view (`/admin`) logs in with a password. The student chat and the Tab5 log in with **the student's code**
(see 3).

- **Set:** `PLUGGIS_ADMIN_PASSWORD=...` in `.env` (at least 8 characters) and run `docker compose up -d`. The password
  is stored hashed (PBKDF2) when the server starts; the log says *"Lösenord för föräldravyn satt från
  PLUGGIS_ADMIN_PASSWORD"* (parent view password set).
- **Then:** open `/admin` and log in. You stay logged in for 30 days per browser. The line in `.env` can be removed.
- **Change:** in the parent view under System → Connection, or with a new value in `.env` and a restart.
- **Forgotten:** write a new password in `.env` and restart.
- **If it's missing** the parent view can't be opened (it shows how to set the password), and the log warns:
  *"Inget lösenord för föräldravyn – sätt PLUGGIS_ADMIN_PASSWORD i .env"*. Nothing is open without a login.

## 2. Claude – choose one of three ways

Pluggis needs Claude for the coach, photos, plans and flashcards. The mode is chosen with `PLUGGIS_CLAUDE_BACKEND`
in `.env` and can then be changed while running under **admin → Cost**. The choice there is saved and takes
precedence over `.env`.

| Mode | `.env` | Suits when |
|---|---|---|
| `api` (default) | `ANTHROPIC_API_KEY` | Simplest and fastest. Pay per token. |
| `claude-code` | `CLAUDE_CODE_OAUTH_TOKEN` | Everything on your Pro/Max subscription. The voice gets about a second slower. |
| `hybrid` | **both** | The voice via the API key, the rest via the subscription. Falls back to the API key if the subscription doesn't work. |

### API key (`ANTHROPIC_API_KEY`)

1. Log in to [console.anthropic.com](https://console.anthropic.com).
2. Add payment under *Billing*. Preferably set a monthly limit under *Limits*.
3. Create a key under *API keys* (starts with `sk-ant-api03-`).
4. Put `ANTHROPIC_API_KEY=...` in `.env`.

What it costs is shown in **admin → Cost**. The exact invoice is in the Console.

### Subscription token (`CLAUDE_CODE_OAUTH_TOKEN`)

Requires Claude Pro or Max.

1. Install Claude Code on your computer: `npm install -g @anthropic-ai/claude-code`.
2. Run `claude setup-token`. The browser opens for login, and then a token is printed (starts with `sk-ant-oat01-`).
3. Put in `.env`:
   ```bash
   PLUGGIS_CLAUDE_BACKEND=claude-code   # or hybrid
   CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-...
   ```

Good to know:
- **Validity:** the token is valid for a year. Run `claude setup-token` again when it has expired.
- **Limits:** Pluggis shares the subscription's limits with your own use of Claude.
- **Status:** admin → Cost shows whether the token and the `claude` program are there, successful and failed calls
  and the latest error.
- **Personal use only:** the subscription is for personal use at home. Read Anthropic's terms, linked from the README.

Other Claude settings (optional):

| Variable | Default | What |
|---|---|---|
| `PLUGGIS_CLAUDE_API_PURPOSES` | `coach` | Which jobs go via the API key in hybrid mode |
| `PLUGGIS_COACH_MODEL` | `claude-haiku-5-5` | Model for the voice coach |
| `PLUGGIS_COACH_EFFORT` / `PLUGGIS_COACH_THINKING` | `low` / `disabled` | Speed for the coach |
| `PLUGGIS_VISION_MODEL`, `PLUGGIS_PLANNING_MODEL`, `PLUGGIS_FLASHCARDS_MODEL` | `claude-sonnet-5-5` | Photos, plans, flashcards |
| `PLUGGIS_EXAM_MODEL` | `claude-opus-5-5` | Mock tests (questions and marking) |
| `PLUGGIS_CLAUDE_TIMEOUT` | `120` | Seconds before a call via the subscription is given up |
| `PLUGGIS_USD_SEK` | `10.0` | Exchange rate for the cost tab |

## 3. Student codes

Created by Pluggis and don't need to be in `.env`.

- **Where:** each student has a fixed 5-character code under **admin → System → Students**. The code is the
  student's login in the phone chat (`/#k=CODE`, or `/chat.html#k=CODE`) and on the Tab5.
- **Access:** with the code the student only reaches their own chat, plan, homework, cards and points.
- **Change:** *New code* makes the old one invalid immediately.
- **Language:** Swedish by default; a student can be switched to English (see the README, *Language*).

## 4. Azure voices (optional)

Better Swedish read-aloud (Sofie, Hillevi, Mattias), and voices for Spanish, German, French and English.
Without Azure, Piper is used, which runs locally.

1. Go to [portal.azure.com](https://portal.azure.com) → *Create a resource* → **Speech**.
2. Choose a resource group (create a new one, e.g. *pluggis*), region **Sweden Central** and pricing tier
   **Free F0**. F0 can't be charged and gives 500,000 characters per month.
3. Copy *Key 1* and the region under *Keys and Endpoint* and put them in `.env`:
   ```bash
   AZURE_SPEECH_KEY=...
   AZURE_SPEECH_REGION=swedencentral
   ```
4. The log says *"Azure-röster påslagna (region swedencentral)"* (Azure voices on). Choose the voice under the
   student's tab → Voice.

Only the coach's reply text is sent to Azure, never the student's voice. If the free limit is reached Piper takes
over by itself. Usage is shown in admin → Cost.

## 5. Google Calendar (optional)

Puts the plan as sessions in the student's Google Calendar.

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and create a project.
2. Enable the **Google Calendar API** under *APIs & Services → Library*.
3. Create a service account under *IAM & Admin → Service Accounts*. It doesn't need any roles. Choose
   *Keys → Add key → JSON*.
4. Put the JSON file in **`secrets/`**. The file name doesn't matter. The log says *"Google Kalender påslagen
   (tjänstekonto …)"* (Google Calendar on).
5. Share the student's calendar with the service account:
   - Log in as the student, or as someone allowed to share the calendar.
   - Open the calendar's settings → *Share with specific people*.
   - Add the service account's address (`…@….iam.gserviceaccount.com`, also shown in admin) with the permission
     **Make changes to events**.
6. Copy the **Calendar ID** under *Integrate calendar* in the calendar's settings. For the student's main calendar
   it's the student's Gmail address.
7. Paste the ID under admin → the student's tab → Calendar and press *Connect and sync*.

If you want to see the student's calendar yourself, the student also needs to share it with your account. To remove
the key, delete the file from `secrets/` and the key under the service account's *Keys* in Google Cloud.

## 6. The school's calendar (optional)

Homework and tests are fetched from the school's calendar subscription – InfoMentor or any other service with an
iCal/webcal link (Google Classroom, Microsoft Teams/Outlook, Schoolsoft, Unikum, Vklass …).

- **Where:** admin → the student's tab → 📅 Calendar. Paste the `webcal://`, `webcals://` or `https://…ics` link and
  press *Save and sync*. An exported `.ics` file can be uploaded instead.
- **Sync:** every hour.
- **Personal link:** treat it like a password. It's only stored in Pluggis' database and shouldn't go into `.env`
  or git.

---

## Other settings (optional, have defaults)

| Variable | Default | What |
|---|---|---|
| `PLUGGIS_MAX_MINUTES_PER_DAY` | `40` | Max study per day for new students (changed per student in admin) |
| `PLUGGIS_STUDY_DAYS` | `MONDAY,…,THURSDAY,SUNDAY` | Study days for new students (changed per student in admin) |
| `PLUGGIS_REMINDERS_ENABLED` | `true` | Reminders in the chat |
| `PLUGGIS_REWARDS_ENABLED` | `true` | Whether the coach talks about pocket money (points on/off per student in admin) |
| `PLUGGIS_SEK_PER_POINT` / `PLUGGIS_WEEKLY_CAP_SEK` | `0.10` / `50` | Kronor per point and cap per week |
| `PLUGGIS_SPEECH_URL` | set by docker-compose | The speech service (Whisper and Piper) |
| `STT_MULTI_MODEL` (speech service) | `Systran/faster-whisper-small` | Whisper for English, Spanish, German and French |
| `SPRING_PROFILES_ACTIVE=postgres` + `DB_URL`, `DB_USER`, `DB_PASSWORD` | H2 file | Postgres instead of the built-in database |
| `PLUGGIS_BACKUP_KEEP` / `PLUGGIS_BACKUP_DIR` | `14` / `./data/backups` | How many nightly backups are kept, and where (H2 only) |
| `PLUGGIS_FIRMWARE_REPO` | `ostbergjohan/pluggis` | The GitHub repo whose release `tab5` has the Tab5 firmware |
| `PLUGGIS_GITHUB_TOKEN` | – | Read access to the repo, only needed if it's private (for Tab5 updates) |

## Check that everything is in place

After `docker compose up -d --build`:

```bash
docker compose logs pluggis | grep -E "Claude körs|Azure|Google Kalender|föräldravyn"
```

| You want to see | Means |
|---|---|
| `Claude körs via API-nyckel` / `via Claude Code` / `hybrid` | Claude is set up |
| `Azure-röster påslagna` | Azure works (otherwise Piper) |
| `Google Kalender påslagen (tjänstekonto …)` | The Google key was found |
| `Lösenord för föräldravyn satt` (or no password warning) | The password is set |

In the parent view, **Cost** shows the status of the API key, the Claude Code token and the `claude` program.
**Calendar** on the student's tab shows the next sessions with links.

## If something leaks

| What | Do this |
|---|---|
| The parent view password | New `PLUGGIS_ADMIN_PASSWORD` in `.env` → `docker compose up -d`, and *Log out everywhere* under System → Connection |
| `ANTHROPIC_API_KEY` | Delete the key in console.anthropic.com → create a new one → `.env` |
| `CLAUDE_CODE_OAUTH_TOKEN` | Run `claude setup-token` for a new token → `.env`. Also change the password of your Claude account if you suspect misuse |
| `AZURE_SPEECH_KEY` | *Regenerate key* in the Azure portal → `.env` |
| The Google key | Delete the key under the service account's *Keys* → create a new one → `secrets/` |
| Student code | *New code* under admin → System → Students |
| The calendar link | Create a new subscription link in the school's service → paste it in admin |
