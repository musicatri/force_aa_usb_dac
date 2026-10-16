#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
JDK="${JAVA_HOME:-$HOME/android-studio/jbr}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
mkdir -p build/classes build/dex dist
javac --release 8 -cp "$SDK/platforms/android-36/android.jar" -d build/classes src/dev/usbdac/*.java
mapfile -t classes < <(find build/classes -name '*.class' -type f)
"$SDK/build-tools/36.0.0/d8" --min-api 36 --lib "$SDK/platforms/android-36/android.jar" --output build/dex "${classes[@]}"
python3 - <<'PACK'
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
with ZipFile('module/daemon.jar','w',ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex','classes.dex')
with ZipFile('dist/usb-dac-force-1.3.0.zip','w',ZIP_DEFLATED) as z:
    for p in sorted(Path('module').iterdir()):
        if p.is_file(): z.write(p,p.name)
PACK
printf 'Built dist/usb-dac-force-1.3.0.zip\n'
