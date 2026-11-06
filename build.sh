#!/bin/bash
# Builds ValqUpgrader.jar. Usage: CP=<paper-api classpath> ./build.sh <plugin_dir> [out.jar]
set -e
SRC=${1:-plugin}
OUT=${2:-ValqUpgrader.jar}
: "${CP:?set CP to the paper-api classpath (e.g. all jars from the server libraries folder)}"
TMP=$(mktemp -d)
javac -nowarn --release 21 -encoding UTF-8 -cp "$CP" -d "$TMP" $(find "$SRC/src" -name "*.java")
cp "$SRC"/resources/* "$TMP"/
(cd "$TMP" && jar cf "$OLDPWD/$OUT" .)
rm -rf "$TMP"
ls -la "$OUT"
