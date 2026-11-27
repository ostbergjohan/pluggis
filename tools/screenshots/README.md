# Screenshots of the student chat

Takes iPhone-sized screenshots (390×844, Retina) of the real chat code with made-up test data
– no server or Claude needed. Used for the images in `docs/screenshots/`.

```bash
cd tools/screenshots
npm install playwright          # once
npx playwright install chromium # if you don't have Chrome
node shots.js ../../src/main/resources/static /tmp/screenshots en       # iPhone, English student, dark mode (the README images)
node shots.js ../../src/main/resources/static /tmp/screenshots          # the same in Swedish
node admin-mode.js ../../src/main/resources/static /tmp/admin-claude.png en   # the parent view's Claude panel
node shots-desktop.js ../../src/main/resources/static /tmp/screenshots  # computer
```

The script uses `/usr/bin/google-chrome`; change `executablePath` in `shots.js` if Chrome is somewhere else
(or remove the line to use Playwright's own Chromium).
