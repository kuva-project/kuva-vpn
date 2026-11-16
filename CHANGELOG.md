# Changelog

All notable changes to KUVAVpn are documented here.

## v1.2.3 — 2026-11-16

### Added
- **Ping values colored by quality** in the free vault, search results and
  every My Configs row (manual + subscriptions): green up to 200 ms, yellow
  200–450 ms, red above 450 ms, gray when untested, red when dead. The
  dashboard stat tiles stay white — colored numbers there would fake a
  meaning the data doesn't have.

### Fixed
- **Shadowsocks links with base64-encoded userinfo failed to import**
  (Shadowmere-style `ss://BASE64@host:port#name` shares were silently
  dropped): the form A/B heuristic misrouted them to whole-body base64
  decoding, which failed. Any link containing `@` before the fragment is
  now parsed as form A (`base64(method:password)@host:port`), covering raw
  and encoded userinfo alike; real form B links still work.
- **Quick Settings tile went stale**: the KUVA tile in the Android quick
  settings panel only repaints while the panel is open, so connecting or
  disconnecting from inside the app left it showing the old state until the
  panel was reopened. Every state change now requests a tile refresh.
- **RTL mirroring of mixed Persian strings** in the config screens:
  Persian-containing texts inside LTR-locked containers ("۴۴ کانفیگ",
  "ترافیک: … (نامحدود)") rendered mirrored and dropped the infinity glyph.
  The bidi helper now forces an explicit RTL base direction for any string
  containing Persian letters.
- **Subscription expiry line could overflow**: the expiry is now a single
  non-wrapping Text; the Persian "add config" button label was shortened to
  fit its third of the toolbar.
- **Color drift across screens**: the neon palette is now defined once as
  tokens in the theme (protocol badges, snackbar states, usage bars, banner
  dots); screens reference the tokens instead of scattering raw hex values.

## v1.2.2 — 2026-11-15

### Added
- **Double-tap to connect**: one tap on a config row selects it, a double
  tap selects AND connects — no more going back to the home screen to press
  the power button (My Configs + free vault). Double-tapping the connected
  row itself disconnects. If the engine is set to Kuva Swift, a double tap
  quietly switches to Xray first (configs only run on Xray), stops any live
  tunnel and connects to the tapped config — the screen stays where it is.
- **Compact header status**: when the big power button is scrolled more than
  80% out of view, a small colored dot + state text appears under the
  "KUVA VPN" title (green = connected, orange = connecting/scanning, red =
  error, gray = disconnected). It fades away again as soon as the button is
  back in view — nothing is shown when the button is visible.

### Fixed
- **App crashes on theme/language change** (`IllegalStateException: Must be
  called from main thread`, ~34 reported instances): applying the night mode
  from a background coroutine now posts to the main looper.
- **ConcurrentModificationException during group ping** in the free vault:
  the pending-results map is now a ConcurrentHashMap, safe for parallel pings.
- **Cancel a Swift connection attempt**: a second tap on the connect button
  while the Swift handshake/scan is running (up to ~2 minutes) now cancels
  the attempt instead of queueing another start; the connecting coroutine
  exits silently and never overwrites the Disconnected state with a stale
  error afterwards.
- **In-app crash reports**: uncaught exceptions are written to
  `files/crash/` (newest 5 kept) and the newest 3 ride along inside the
  bug-report zip under `crashes/` — future reports are self-sufficient,
  no adb needed.

## v1.2.1 — 2026-11-15

### Added
- **Colored status banners** at the bottom of the config screens: green for
  success (configs added, subscription added/updated), yellow for failures
  (subscription update failed), each with a fitting icon and auto-fit width.
- **Protocol ribbons** on config rows: a colored side badge per protocol
  (VLESS blue, VMess purple, …) so configs are recognizable at a glance.
- **Redesigned search** in the free vault and My Configs (neon-purple field,
  cleaner results layout).
- **Language quick-switch icons (EN/FA)** in Settings.
- A **spinner on the subscription refresh button** while the update is running
  (the button can't be double-tapped anymore).
- Adding a subscription by dialog, QR or clipboard now shows a green
  **"Sub added"** confirmation.

### Fixed
- **Psiphon on armeabi-v7a devices**: the bundled Psiphon core (`libpt.so`)
  for 32-bit ARM was replaced with a working build — Kuva Swift + Psiphon now
  connects on older phones. The x86_64 core was refreshed as well.
- **Undo banner now actually appears** after deleting a config or a
  subscription (in both My Configs and the free-vault screen) — a lifecycle
  gate evaluated once at composition kept the undo listener from ever starting.
- The undo/delete banner got its own neutral dark style (red ✕ mark, purple
  **Undo** action), 25% narrower than the colored ones, and auto-dismisses
  after **4 s** for a single config / **6 s** for a whole subscription —
  after that the deletion becomes permanent.
- Free-config discovery: more reliable source fetching and fingerprint
  deduplication (fewer duplicates, fewer failed imports).
- Notification and live-traffic-stats stability fixes.
- General dashboard / settings UI polish and theme tweaks.

## v1.2.0 — 2026-11-13

### Added
- **Psiphon reverse-mode routing for Kuva Swift** (WARP → Psiphon): a per-country
  psiphon layer rides inside the MASQUE tunnel, with a 28-country exit picker
  (dashboard quick card + sheet with live flags). Reverse mode dials the whole
  tunnel through psiphon, so every probe already sees the true psiphon exit.
- **Honest Swift progress bar**: fully event-driven percentage (identity ready,
  gateway scan candidates, best gateway, TLS established, connect-ip 200,
  data-plane validation, WARP on) — monotonic, no fake stalls at 90%; a
  per-stage localized label is shown under the bar (EN + FA).
- **Tunnel watchdog**: `h2 keepalive timeout` / `connection aborted` drops are
  detected from core logs (the core process survives and self-reconnects, so
  process-death callbacks never fired); the UI honestly shows Swift connecting
  again and recovers automatically within up to 180 s, falling back to the full
  reconnect path otherwise.

### Fixed
- Stat tiles (Time / Ping / Download / Upload) on the dashboard are now truly
  centered — the inner column takes the full card width and every line is
  centered (EN + FA).

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
