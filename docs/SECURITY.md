# Security & Privacy Audit — KUVAVpn

## Implemented protections
- **Secrets at rest**: profile UUIDs/passwords, REALITY public keys, raw JSON
  and subscription URLs are encrypted with AES-256-GCM; key is non-exportable,
  generated inside Android Keystore. Room DB extraction alone reveals nothing.
- **Transport**: all discovery, subscription and geoip traffic is HTTPS with
  default certificate validation (no `allowInsecure` shortcuts in app code).
  Exit-IP lookup runs through the tunnel only after it is up.
- **DNS leak protection**: Xray DNS section resolves through the tunnel
  (`IPIfNonMatch` strategy); the TUN's DNS servers are tunnel-internal.
- **Input validation**: share-link parsing is total (returns null on malformed
  input); config JSON is parsed by Xray-core inside the native boundary — a
  malformed config fails `startLoop` and is reported as Error state, never a crash.
- **VPN lifecycle**: consent via official `VpnService.prepare`; `onRevoke`
  triggers full teardown; stale-socket ordering (stop service before closing
  the fd) verified against upstream implementation; service is START_STICKY and
  rebuilds after process death (Always-on VPN compatible).
- **Per-app routing**: enforced at the OS level by VpnService Builder
  (`addDisallowedApplication` / `addAllowedApplication`), not userspace filtering.
- **No tracking**: no analytics, no crash-report SDK, no config URLs leave the
  device except to the user's own subscription/source endpoints.
- **Logs**: core log level `warning`; no UUID/URL values are logged.

## Known limitations (documented per spec)
1. **Kill switch**: implemented via `VpnService.Builder.setBlocking(true)`
   (API 29+). The stronger system Always-on-VPN + "Block connections without
   VPN" must be enabled by the user in OS settings for device-wide enforcement.
2. **Telegram discovery** uses only the public t.me web preview of public
   channels. Private channels, login-protected endpoints and rate-limit
   circumvention are NOT supported by design.
3. **GitHub API rate limits**: unauthenticated requests are capped by GitHub;
   users may supply a personal token (stored encrypted) to raise limits.
4. **IPv6**: fully optional, off by default; when disabled, IPv6 routes are not
   claimed by the TUN (v6 leaks to the underlying network — see note in
   settings description).
5. **QR scanning** requires camera permission at runtime (zxing embedded).
6. Exit-IP geolocation depends on third-party HTTPS endpoints; when all fail
   the UI shows "Unavailable" rather than a guess.
