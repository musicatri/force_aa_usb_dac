#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JDK="${JAVA_HOME:-$HOME/android-studio/jbr}"
mkdir -p build/test-classes
"$JDK/bin/javac" --release 8 -d build/test-classes src/dev/usbdac/StrategyAccess.java src/dev/usbdac/RoutingDump.java tests/StrategyAccessTest.java tests/RoutingDumpTest.java src/dev/usbdac/OnceAfterConnection.java tests/OnceAfterConnectionTest.java
"$JDK/bin/java" -cp build/test-classes dev.usbdac.StrategyAccessTest
"$JDK/bin/java" -cp build/test-classes dev.usbdac.RoutingDumpTest tests/fixtures/oxygen/audio.txt tests/fixtures/oxygen/policy.txt tests/fixtures/lineage/audio.txt tests/fixtures/lineage/policy.txt
"$JDK/bin/java" -cp build/test-classes dev.usbdac.OnceAfterConnectionTest
