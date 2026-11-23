# Skärmdumpar av elevchatten

Tar skärmdumpar i iPhone-storlek (390×844, Retina) av den riktiga chattkoden med påhittade testdata
– ingen server eller Claude behövs. Används för bilderna i `docs/screenshots/`.

```bash
cd tools/screenshots
npm install playwright          # en gång
npx playwright install chromium # om du inte har Chrome
node shots.js ../../src/main/resources/static /tmp/skarmdumpar
```

Skriptet använder `/usr/bin/google-chrome`; ändra `executablePath` i `shots.js` om Chrome ligger någon annanstans
(eller ta bort raden för Playwrights egen Chromium).
