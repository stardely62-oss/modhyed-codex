#!/usr/bin/env bash
# Runs the scripted client scenario (CI, Linux with Xvfb): generate a world with the dedicated
# server, copy it into the client's saves, play the scenario, then demand a PASS and a fresh screenshot.
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER=build/harness-server
CLIENT=build/harness-client

rm -rf "$SERVER" "$CLIENT/saves/harness" "$CLIENT/screenshots" "$CLIENT/harness-result.txt"
mkdir -p "$SERVER" "$CLIENT/saves"

echo 'eula=true' > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<'EOF'
level-name=world
level-type=minecraft\:normal
level-seed=20261025
generate-structures=true
online-mode=false
spawn-protection=0
spawn-monsters=false
spawn-animals=false
EOF

timeout 300 ./gradlew runGenworld --stacktrace
test -f "$SERVER/world/level.dat" || { echo 'genworld did not create a world'; exit 1; }
cp -r "$SERVER/world" "$CLIENT/saves/harness"

# Test the optional map integration with the same NeoForge build as the installer.
mkdir -p "$CLIENT/mods"
curl -sSfL -o "$CLIENT/mods/journeymap-neoforge-1.21.1-6.0.9.jar" 'https://cdn.modrinth.com/data/lfHFW1mp/versions/CtFE7hwT/journeymap-neoforge-1.21.1-6.0.9.jar'
echo '8900ee4cdb7d1ffb6559decdeec84ad479d04b2e7b01a60be9f6e328809af2bfd7a80077b7da0e49bc96261fe8fe274ba33c62f6c9f65302c783ed823c4ff746  '"$CLIENT/mods/journeymap-neoforge-1.21.1-6.0.9.jar" | sha512sum -c -

# Skip first-launch screens and keep running without window focus
cat > "$CLIENT/options.txt" <<'EOF'
onboardAccessibility:false
tutorialStep:none
skipMultiplayerWarning:true
joinedFirstServer:true
pauseOnLostFocus:false
soundCategory_master:0.0
lang:ru_ru
renderDistance:8
guiScale:2
EOF

timeout 600 xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew runHarness --stacktrace || true

echo '--- harness result ---'
cat "$CLIENT/harness-result.txt" 2>/dev/null || echo '(no result file: the client crashed or never reached the world)'
for shot in obelisk_front obelisk_cage obelisk_eye_follows obelisk_night menu menu_selected arrived \
            obelisk_tier4 menu_tier4 menu_zoomed ruin ruin_restored; do
  test -s "$CLIENT/screenshots/$shot.png" || { echo "screenshot $shot.png was not written"; exit 1; }
done
grep -qx 'RESULT PASS' "$CLIENT/harness-result.txt"
