# Building KUVAVpn

## Prerequisites (one-time)
1. **JDK 17** (Temurin recommended)
2. **Android Studio** (latest stable) with SDK 35 + Build-Tools 35
3. **NDK r27** (only if rebuilding the tunnel .so from source)
4. **Go ≥ 1.24 + gomobile** (only for rebuilding the core AAR)
   ```
   go install golang.org/x/mobile/cmd/gomobile@latest
   gomobile init
   ```

## Step 1 — Build the Xray AAR (once, or when updating the core)

The project expects `core-engine/libv2ray.aar`. Build it from the bundled
AndroidLibXrayLite source:

```
cd core-engine/libv2ray
gomobile bind -v -androidapi 24 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' \
  -o ../libv2ray.aar .
```

Pinned upstream: Xray-core v26.10.29 (see go.mod). To update, edit
`core-engine/libv2ray/go.mod` to the new Xray-core tag and rebuild.

## Step 2 — Tunnel .so libraries

The app loads `libhev-socks5-tunnel.so` (MPL-2.0). Either:
- extract the .so files from a stock v2rayNG release APK (`lib/<abi>/`) into
  `core-engine/libs/<abi>/` — they are identical upstream artifacts, or
- build from https://github.com/hev-socks5-tunnel with the NDK:
  ```
  git clone --depth 1 https://github.com/hev-socks5-tunnel
  cd hev-socks5-tunnel && mkdir build && cd build
  cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
        -DANDROID_ABI=arm64-v8a .. && make
  ```
  Repeat per ABI and place each `libhev-socks5-tunnel.so` in `core-engine/libs/<abi>/`.

## Step 3 — Build the app

```
./gradlew assembleDebug        # debug APK
./gradlew test                 # unit tests
./gradlew assembleRelease      # release APK (signed, see below)
```

## Release signing (supply securely — never commit)

In `~/.gradle/gradle.properties` (outside the repo) or environment variables:
```
KUVA_KEYSTORE=/absolute/path/to/release.keystore
KUVA_KEYSTORE_PASSWORD=...
KUVA_KEY_ALIAS=...
KUVA_KEY_PASSWORD=...
```
Generate a key once with:
```
keytool -genkeypair -v -keystore release.keystore -alias kuva \
  -keyalg RSA -keysize 4096 -validity 10950
```

## Geo databases
`geoip.dat` / `geosite.dat` ship in `core-engine/assets/` and are copied to
internal storage on first run. Update them periodically from the upstream
Loyalsoldier/v2ray-rules-dat releases.
