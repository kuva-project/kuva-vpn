# Changelog

All notable changes to KUVAVpn are documented here.

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
