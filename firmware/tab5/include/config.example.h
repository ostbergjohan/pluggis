// Kopiera till config.h och fyll i. config.h checkas aldrig in (står i .gitignore).
#pragma once

#define PLUGGIS_WIFI_SSID   "ditt-wifi"
#define PLUGGIS_WIFI_PASS   "lösenord"

// Datorn/servern där Pluggis körs (samma som i elevchattens adress), utan http://
#define PLUGGIS_HOST        "192.168.1.20"
#define PLUGGIS_PORT        8080

// Elevens enhets-id (admin → Inställningar → Elever) och PLUGGIS_DEVICE_TOKEN från .env
#define PLUGGIS_DEVICE_ID   "fred"
#define PLUGGIS_TOKEN       "byt-mig"

// Ljudvolym 0–255
#define PLUGGIS_VOLUME      180
