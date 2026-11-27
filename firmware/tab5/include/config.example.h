// Kopiera till config.h och fyll i. config.h checkas aldrig in (står i .gitignore).
#pragma once

#define PLUGGIS_WIFI_SSID   "ditt-wifi"
#define PLUGGIS_WIFI_PASS   "lösenord"

// Datorns IP-adress där Pluggis körs, utan http:// (hostname -I på servern). Använd IP, inte datorns namn:
// Tab5 kan oftast inte slå upp namn som "debian" på hemnätet.
#define PLUGGIS_HOST        "192.168.1.20"
#define PLUGGIS_PORT        8080

// Elevens enhets-id och elevens kod (föräldravyn → Inställningar → Elever). Det finns ingen föräldertoken längre.
#define PLUGGIS_DEVICE_ID   "fred"
#define PLUGGIS_TOKEN       "ABCDE"   // elevens kod

// Stäng av efter så många minuter utan aktivitet (0 = aldrig)
#define PLUGGIS_IDLE_MINUTES 5

// Ljudvolym 0–255
#define PLUGGIS_VOLUME      180
