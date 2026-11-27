#!/usr/bin/env bash
# Ritar Tab5-gränssnittet med testdata till PNG-filer i sim/out/ (kräver gcc, g++, git och python3 med Pillow).
set -euo pipefail
cd "$(dirname "$0")"
SIM=$(pwd)
TAB5=$(cd .. && pwd)
LVGL="$SIM/.lvgl"
LVGL_VERSION=$(grep -oE 'lvgl/lvgl@[~^]?[0-9.]+' "$TAB5/platformio.ini" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || echo 9.2.2)

if [ ! -d "$LVGL" ]; then
  git clone --depth 1 -b "v$LVGL_VERSION" https://github.com/lvgl/lvgl "$LVGL"
fi

mkdir -p build out
CFLAGS="-O1 -I$TAB5/include -I$LVGL -DLV_CONF_INCLUDE_SIMPLE -DSIM_OUT=\"$SIM/out\""

# LVGL och typsnitten byggs en gång (objektfiler i build/)
if [ ! -f build/liblvgl.a ]; then
  export CFLAGS LVGL
  find "$LVGL/src" -name '*.c' | xargs -P "$(nproc)" -I{} sh -c 'f={}; o="build/lvgl_$(echo "${f#$LVGL/src/}" | tr / _).o"; gcc $CFLAGS -c "$f" -o "$o"' 
  ar rcs build/liblvgl.a build/lvgl_*.o
fi
for f in "$TAB5"/src/fonts/*.c; do
  o="build/$(basename "$f" .c).o"
  [ "$o" -nt "$f" ] || gcc $CFLAGS -c "$f" -o "$o" &
done
wait

g++ -std=gnu++17 $CFLAGS -Wall -Wno-unused-function "$SIM/sim_main.cpp" "$TAB5/src/ui.cpp" build/font_*.o build/liblvgl.a -o build/sim -lm
# loggan och figuren ur include/brand.h (samma bytes som på Tab5)
python3 - "$TAB5/include/brand.h" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
for name, out in (("LOGO_PNG", "build/logo.png"), ("AVATAR_PNG", "build/avatar.png")):
    body = re.search(r"%s\[\] PROGMEM = \{(.*?)\};" % name, src, re.S).group(1)
    open(out, "wb").write(bytes(int(x, 16) for x in re.findall(r"0x[0-9a-fA-F]{2}", body)))
PY
rm -f out/*.ppm out/*.png
./build/sim > /dev/null
python3 - <<'EOF'
import glob, os
from PIL import Image
for p in sorted(glob.glob("out/*.ppm")):
    Image.open(p).save(p[:-4] + ".png")
    os.remove(p)
print("\n".join(sorted(glob.glob("out/*.png"))))
EOF
