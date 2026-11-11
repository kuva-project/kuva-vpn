# Changelog

All notable changes to KUVAVpn are documented here.

## v1.1.1 — 2026-11-12

### Fixed
- **Connected but no traffic (debug builds)**: the network watcher treated the
  VPN interface coming up as a "new network" and re-established the tunnel
  ~1.5 s into connecting; the second hev start was refused, leaving a dead
  tunnel under a Connected UI. VPN's own network is now ignored by the
  reconnect watcher, and reconnects are suppressed while a connect is in
  flight.
- **Split tunneling app picker UI desync**: selection state was mutated
  in place, so Compose never recomposed — taps really selected apps but
  checkboxes did not redraw. State is now reassigned on every change.
- **Duplicate "Searching for route…" label** on the dashboard in Swift
  scan mode; the gray copy above the progress bar was removed.
- Quick-settings tile state (decides from the connection repository, no
  longer desyncs with the dashboard).

## v1.1.0 — 2026-11-11

### Added
- **Kuva Swift — configless engine**: a bundled MASQUE core (official Aether
  v2.4.0 binaries, AGPL-3.0) connects with no profile at all — endpoint scan,
  identity persistence, auto-reconnect. HTTP/2 (TCP) transport with TLS
  ClientHello fragmentation for Iran's SNI filtering; IPv4-only.
- Engine selector on the dashboard (under the status text) and in
  Settings → Connection; Xray remains the default.
- Auto update check on every app start: gold banner on the dashboard
  ("New version available — see Settings → About") for 8 seconds and a red
  dot on the Settings tab until the app is updated.
- Collapsible settings cards: all 7 cards start collapsed showing only the
  title; tapping expands/collapses with an animated chevron. Lighter card
  title color.
- Real app exit: the EXIT button now ends the process, so the next launch
  boots on Xray as designed (engine choice still persists across
  background/foreground).
- LICENSE (AGPL-3.0) at repo root + bundled Aether LICENSES files; About
  attribution line.

## v1.0.3 — 2026-11-10

### Fixed
- **In-app update now completes**: the PackageInstaller confirm intent was
  never launched, so the update downloaded but never installed. The commit
  status receiver is now registered and launches the system install dialog
  (`STATUS_PENDING_USER_ACTION` → `startActivity`).
- Install-permission gate: if "Install unknown apps" is not granted, the app
  now shows "Install permission needed — tap to allow" instead of failing
  silently; tapping opens the system permission screen for KUVAVpn.

## v1.0.2 — 2026-11-09

### Added
- **Full Xray JSON import**: raw JSON configs (v2rayNG / v2Box full exports)
  can be pasted or imported; clipboard JSON with an `outbounds` array is
  detected automatically in My Configs → Import.
- Post-quantum vless `encryption` (e.g. `mlkem768x25519plus...`) is carried
  through to the generated core config.
- `spiderX` from REALITY settings is imported.

### Fixed
- Flat v2rayNG JSON shape (server in `settings` instead of `settings.vnext[]`)
  now parses.
- Root-level `remarks` (plural) used for config names.
- xhttp / httpupgrade transports now import host + path (ws, grpc, tcp, xhttp
  and httpupgrade all supported).

## v1.0.1 — 2026-11-08

### Fixed
- First patch release: parser name chain and misc robustness fixes.

## v1.0.0 — 2026-11-07

### Added
- Initial release: Xray-core (XTLS) VPN client — VLESS / VMess / Trojan /
  Shadowsocks / SOCKS / HTTP over REALITY, TLS, WebSocket, gRPC, HTTP/2,
  HTTPUpgrade, XHTTP.
- VpnService TUN tunnel (hev-socks5-tunnel), real ping / speed / traffic stats,
  exit-IP verification, Iran bypass (geosite:category-ir + geoip:ir direct),
  bundled Loyalsoldier geo databases with in-app update + SHA-256 check.
- Free config discovery (add-only, fingerprint dedup), My Configs with
  subscriptions, split tunneling, smart selection, EN/FA + RTL, Android
  Keystore AES-256-GCM secret encryption.
