// Kopiera till config.h och fyll i. config.h checkas aldrig in (står i .gitignore).
#pragma once

#define PLUGGIS_WIFI_SSID   "ditt-wifi"   // går också att välja på skärmen (WiFi-ikonen → Byt WiFi)
#define PLUGGIS_WIFI_PASS   "lösenord"

// Datorns IP-adress där Pluggis körs, utan http:// (hostname -I på servern). Använd IP, inte datorns namn:
// Tab5 kan oftast inte slå upp namn som "debian" på hemnätet.
#define PLUGGIS_HOST        "192.168.1.20"
#define PLUGGIS_PORT        8080

// Elevens kod (5 tecken, samma som i elevchatten – admin → Inställningar → Elever). Den räcker: servern vet
// vilken elev koden hör till, och skärmen når bara den elevens läxor, plan och poäng.
#define PLUGGIS_CODE        "ABCDE"

// Stäng av efter så många minuter utan aktivitet (0 = aldrig)
#define PLUGGIS_IDLE_MINUTES 5

// Ljudvolym 0–255
#define PLUGGIS_VOLUME      180
