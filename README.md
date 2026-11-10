# KUVAVpn

Premium Android VPN client powered by **Xray-core** (VLESS / VMess / Trojan /
Shadowsocks / SOCKS / HTTP; REALITY, TLS, WebSocket, gRPC, HTTP/2, HTTPUpgrade,
XHTTP transports). Kotlin + Jetpack Compose (Material 3), Clean Architecture + MVVM.

![brand](art/brand/app_icon_1024.png)

## Feature map

| Area | What's implemented | Where |
|---|---|---|
| Real VPN tunnel | `VpnService` TUN + hev-socks5-tunnel (TUN→SOCKS) + Xray core | `core/service/KuvaVpnService.kt`, `core/tunnel/Tun2Socks.kt` |
| Config parsing | vless/vmess/trojan/ss/socks/http share links + raw Xray JSON, crash-safe | `core/config/parser/ShareLinkParser.kt` |
| Config generation | Full Xray JSON (inbounds, DNS, routing, stats, REALITY/TLS) | `core/config/XrayConfigBuilder.kt` |
| Free config discovery | Pluggable public sources (GitHub API, raw lists, public Telegram previews), HTTPS-only, add-only import, fingerprint dedup | `core/discovery/ConfigDiscoveryEngine.kt`, `domain/usecase/FreeConfigUseCase.kt` |
| Real latency tests | TCP handshake + full proxy round-trip via `MeasureOutboundDelay` | `core/diagnostics/ConfigTester.kt` |
| Real speed tests | Size-limited HTTP up/down through the actual tunnel, user-capped | same |
| Live stats | Real byte counters from the native tunnel, 1 s polling, speeds + totals + duration | `core/stats/TrafficStatsCollector.kt` |
| Exit IP verification | HTTPS geoip lookup **through the tunnel** after connect | `core/diagnostics/ExitIpChecker.kt` |
| My Configs + subscriptions | Import (link/clipboard/QR/file/manual), subscription refresh with expiry metadata, hard isolation from free repo | `domain/usecase/SubscriptionUseCase.kt`, `ui/mycfg/` |
| Split tunneling | Per-app bypass/only modes via VpnService Builder, picker UI | `KuvaVpnService.buildTunInterface`, `ui/apps/AppPickerScreen.kt` |
| Settings | Theme, EN/FA + RTL, routing, DNS (5 providers + custom), kill switch, IPv6, auto-connect, smart selection | `ui/settings/`, `data/settings/SettingsRepository.kt` |
| Smart selection | Weighted: measured ping 45% + measured down 35% + up 10% + freshness 10% | `ProfileRepositoryImpl.bestByPerformance` |
| Iran bypass + geo DBs | geosite:ir/geosite:category-ir + geoip:ir go direct (banks/gov work), bundled Loyalsoldier databases, in-app update from GitHub with sha256 check | `core/engine/GeoUpdater.kt`, `core/config/XrayConfigBuilder.kt` |
| Security | Android Keystore AES-256-GCM field encryption for UUIDs/URLs, HTTPS everywhere, TLS validation, no third-party data transmission, crash-safe parsers | `data/crypto/FieldCrypto.kt` |
| Extras | Boot auto-connect, QS tile, persistent notification with live speed, connection history, favorites, EN/FA localization | `core/service/`, `ui/notifications/` |

## Repository layout

```
kuva-vpn/
├── app/                        # Android application module
│   └── src/main/java/com/kuva/vpn/
│       ├── core/               # engine, config, tunnel, stats, discovery, diagnostics, service, control
│       ├── data/               # Room DB, DataStore settings, crypto, repositories
│       ├── domain/             # models, repository interfaces, use cases
│       ├── ui/                 # Compose screens (dashboard, free, mine, settings, apps), theme, navigation
│       └── di/                 # Hilt modules
├── core-engine/
│   ├── libv2ray/               # AndroidLibXrayLite source (LGPL-3.0) — build AAR from here
│   └── assets/                 # geoip.dat / geosite.dat (shipped with the app)
├── art/brand/                  # brand assets derived from the uploaded logo
└── docs/                       # BUILDING.md, SECURITY.md, LICENSES.md
```

## Quick start

See **docs/BUILDING.md** (one-time toolchain setup + two build commands).

## Privacy

No accounts, no analytics, no telemetry — the developer operates no backend
and receives nothing. Full details: **docs/PRIVACY.md**.
The release APK is produced with `./gradlew assembleRelease` after you supply
your own signing key (never commit secrets).

## Hard guarantees (acceptance criteria)

1. **No fake functionality** — every ping/speed/stat value comes from a real
   measurement (TCP handshake, proxied HTTP, native tunnel counters). There is
   no simulated path in the code.
2. **Free and personal repos are isolated** — `scope` column + scope-guarded
   DAO queries; subscription refresh only touches rows of its own subscription;
   manual personal configs (subscriptionId == null) are never auto-deleted.
3. **Add-only discovery** — imports never overwrite or delete existing configs;
   duplicates skipped by content fingerprint; source attribution + timestamp kept.
4. **Delete-dead is verified** — only configs that fail re-verification are
   removed; recovered ones are re-marked working.
5. **Crash-safe parsing** — malformed share links / bodies return null, never throw.
6. **No plaintext secrets** — UUIDs, passwords, subscription URLs are
   AES-256-GCM encrypted with an Android Keystore key.
