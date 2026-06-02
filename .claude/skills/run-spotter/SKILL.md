---
name: run-spotter
description: Launch and test the Spotter app (FastAPI server + Android client) in a fresh Linux container. Use when asked to run, start, smoke-test, or emulate Spotter, or to verify a change against the real running server / a built APK. Covers SDK + Postgres bootstrap, server boot, end-to-end API smoke, unit tests, APK build, and the emulator path (which needs a KVM-capable host).
---

# Running Spotter

Spotter is a FastAPI server (`server/`) + native Android client (`android/`) backed by Postgres,
with an optional LM Studio LLM the server proxies. This skill is the verified cold-start recipe:
it was c'd from a fresh container and every command below was run and confirmed.

## What's runnable where

| Target | Works in this container? | How you "see it work" |
|--------|--------------------------|------------------------|
| FastAPI server | ✅ yes | `curl` the live endpoints |
| Server pytest suite | ✅ yes | 55 tests pass against Postgres |
| Android unit tests | ✅ yes | 48 tests pass (JVM, no device) |
| Android debug APK | ✅ yes | `app-debug.apk` is produced |
| **Android emulator (interactive UI)** | ❌ **no — needs KVM** | run on a host with `/dev/kvm` |

> **Emulator reality check:** this container has no `/dev/kvm` and no CPU virtualization
> (`grep -cE 'vmx|svm' /proc/cpuinfo` → 0). `emulator -accel-check` returns code 8. Google's
> emulator can't run any system image here (x86_64 needs KVM; arm64 won't translate on an x86_64
> host without accel). To click through the UI, build the APK here and run the emulator on a
> KVM-capable machine (see the last section).

---

## 0. One-time bootstrap (fresh container)

### Android SDK (`compileSdk 35`, `build-tools 35.0.0`)
```bash
curl -s -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p /opt/android-sdk/cmdline-tools
( cd /opt/android-sdk/cmdline-tools && unzip -q /tmp/cmdtools.zip && mv cmdline-tools latest )
yes 2>/dev/null | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=/opt/android-sdk" > android/local.properties   # gitignored; required by Gradle
```
Export for every shell that runs Gradle: `export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk`

### Postgres
```bash
pg_ctlcluster 16 main start          # or: service postgresql start
su postgres -c "psql -c \"CREATE USER spotter WITH PASSWORD 'spotter' CREATEDB;\""
su postgres -c "psql -c \"CREATE DATABASE spotter_dev  OWNER spotter;\""   # app/dev DB
su postgres -c "psql -c \"CREATE DATABASE spotter_test OWNER spotter;\""   # pytest DB
```

### Python venv (gitignored — recreate on a fresh clone)
```bash
cd server && python3 -m venv .venv && .venv/bin/pip install -e ".[dev]" && cd ..
```

---

## 1. Run the server

The server reads config from env vars (`pydantic-settings`). LM Studio is optional — without it,
`/ai/chat` returns `503`, everything else works.
```bash
export DATABASE_URL="postgresql+asyncpg://spotter:spotter@localhost:5432/spotter_dev"
export SECRET_KEY="local-dev-secret"
export LM_STUDIO_BASE_URL="http://localhost:1234/v1"
export LM_STUDIO_MODEL="local-model"
cd server
.venv/bin/alembic upgrade head        # creates tables + seeds 36 exercises (migration 0002)
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 &
# wait for "Application startup complete" in the log, then:
curl -s http://127.0.0.1:8000/health   # -> {"status":"ok"}
```

### End-to-end API smoke (drive the real endpoints)
```bash
B=http://127.0.0.1:8000
TOK=$(curl -s -X POST $B/auth/register -H "Content-Type: application/json" \
  -d '{"name":"Tester","email":"t_'$RANDOM'@spotter.com","password":"Testpass123!"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
A="Authorization: Bearer $TOK"

curl -s $B/users/me -H "$A"                                   # current user
curl -s "$B/exercises" -H "$A" | python3 -c "import sys,json;print(len(json.load(sys.stdin)),'exercises')"
# IMPORTANT: url-encode searches with spaces — curl -G --data-urlencode "search=Bench Press"
BENCH=$(curl -s -G "$B/exercises" --data-urlencode "search=Bench Press" -H "$A" \
  | python3 -c "import sys,json;print(next(e['id'] for e in json.load(sys.stdin) if e['name']=='Bench Press'))")
PID=$(curl -s -X POST $B/plans -H "$A" -H "Content-Type: application/json" \
  -d '{"name":"Push Day","source":"manual","exercises":[{"exercise_id":"'$BENCH'","target_sets":3,"target_reps":8,"target_weight":135.0,"order":0}]}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s $B/plans/$PID -H "$A"            # plan detail — exercises include exercise_name
curl -s -X PUT $B/plans/$PID/exercises -H "$A" -H "Content-Type: application/json" \
  -d '{"exercises":[{"exercise_id":"'$BENCH'","target_sets":5,"target_reps":5,"order":0}]}'   # edit
curl -s -X POST $B/sessions -H "$A" -H "Content-Type: application/json" \
  -d '{"plan_id":"'$PID'","date":"2026-06-01"}'    # start session (auto-creates set logs)
curl -s $B/sessions -H "$A"              # session history summary (plan_name, sets, per-exercise)
```
Stop the server: `pkill -f "uvicorn app.main:app"`.

---

## 2. Tests

```bash
# Server (needs spotter_test DB):
cd server && DATABASE_URL="postgresql+asyncpg://spotter:spotter@localhost:5432/spotter_test" \
  SECRET_KEY=ci LM_STUDIO_BASE_URL=http://localhost:1234/v1 .venv/bin/pytest tests/ -q

# Server lint (CI only lints app/):
cd server && .venv/bin/ruff check app

# Android unit tests (JVM, no device):
cd android && ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest --no-daemon
```
> Gradle's first run is slow (downloads + first compile) and can exceed a 10-min tool timeout —
> run it backgrounded and poll the log for `BUILD SUCCESSFUL|BUILD FAILED`. Subsequent runs are ~20s.

---

## 3. Build the APK (client-side check that works here)

```bash
cd android && ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleDebug --no-daemon
ls -lh app/build/outputs/apk/debug/app-debug.apk     # ~18 MB
```

---

## 4. Emulate the UI (KVM-capable host only — NOT this container)

The Android client targets `http://10.0.2.2:8000/` (emulator alias for the host's `localhost`,
set in `android/.../di/AppModule.kt`), so run the server on the host and it's reachable from the AVD.

```bash
ANDROID_HOME=/opt/android-sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "emulator" "system-images;android-35;google_apis;x86_64"
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n spotter -k "system-images;android-35;google_apis;x86_64" -d pixel_6
$ANDROID_HOME/emulator/emulator -avd spotter -no-snapshot -no-boot-anim &   # add -no-window for headless
$ANDROID_HOME/platform-tools/adb wait-for-device
# poll until booted:
until [ "$($ANDROID_HOME/platform-tools/adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
$ANDROID_HOME/platform-tools/adb install android/app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n com.spotter/.MainActivity
$ANDROID_HOME/platform-tools/adb exec-out screencap -p > /tmp/spotter.png   # look at the screenshot
```
`emulator -accel-check` should print `KVM (version …) is installed and usable` on such a host.
```

> **Headless UI testing without an emulator:** if you want to assert on real Compose screens in CI
> (no device), add Robolectric + `androidx.compose.ui:ui-test-junit4` and write
> `createComposeRule()` tests. Not set up in the repo yet — propose it if UI-level coverage is wanted.
