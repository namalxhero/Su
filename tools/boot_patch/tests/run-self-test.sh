#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="${TMPDIR:-/tmp}/ghostly-patch-core-test.jar"
kotlinc \
  "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/BootImageInspector.kt" \
  "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/BootImageLayout.kt" \
  "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/NewcCpio.kt" \
  "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/RamdiskCodec.kt" \
  "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/BootImageWriter.kt" \
  "$ROOT/tools/boot_patch/tests/PatchCoreSelfTest.kt" \
  -include-runtime -d "$OUT"
java -jar "$OUT"

# BootImageWriter.kt's own round-trip tests (patch + rebuild + re-parse +
# content checks) run as separate programs - each file above defines its
# own top-level main(), so they can't share one jar/invocation with
# PatchCoreSelfTest.kt's main() without a name clash.
for t in WriterSelfTest WriterSelfTestV1 WriterSelfTestVendorBoot InspectorV4Regression; do
  OUT_T="${TMPDIR:-/tmp}/ghostly-${t}.jar"
  kotlinc \
    "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/BootImageInspector.kt" \
    "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/BootImageLayout.kt" \
    "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/NewcCpio.kt" \
    "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/RamdiskCodec.kt" \
    "$ROOT/manager/app/src/main/java/com/namalxhero/su/bootpatch/BootImageWriter.kt" \
    "$ROOT/tools/boot_patch/tests/${t}.kt" \
    -include-runtime -d "$OUT_T"
  java -jar "$OUT_T"
done
