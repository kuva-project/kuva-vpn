# Privacy Policy — KUVAVpn

_Last updated: 2026-11-09_

KUVAVpn is an open-source VPN client for Android, powered by the Xray core.
This policy describes exactly what network activity the app performs and what
data it stores. It applies to the app as published in this repository.

**The short version: KUVAVpn has no backend, no account system, no analytics
SDKs and no telemetry. The developer receives nothing. Every network request
the app makes is one you trigger, or one needed to run the features you use,
and it goes directly from your device to the service involved.**

## 1. Data the app does NOT collect

- No personal data (name, email, phone, identifiers)
- No usage analytics, crash-reporting or tracking SDKs (no Firebase, no
  Google Analytics, no ad libraries)
- No advertising identifiers
- No browsing history, DNS queries or traffic contents — these travel
  inside the encrypted tunnel to the VPN server **you** configured; the app
  does not log, store or transmit them anywhere else
- No contact list, photos, media or files

## 2. Data stored on your device (never sent to the developer)

| Data | Where | Protection |
|---|---|---|
| Config profiles (server address, port, UUID/password) | local Room database | UUIDs and passwords are AES-256-GCM encrypted with a hardware-backed Android Keystore key |
| Subscription URLs | local database | encrypted as above |
| Settings (timeout, MTU, routing mode, selected apps) | local DataStore | on-device only |
| Ping/speed test results | local database | on-device only |
| App list (for split tunneling) | read via PackageManager | used only to render the per-app routing list; never uploaded |

Uninstalling the app removes all of the above.

## 3. Network requests the app makes, and why

All of these are direct device-to-service requests. None of them pass through
a server operated by the developer, because the developer operates none.

| Purpose | Services contacted | When |
|---|---|---|
| VPN tunnel | the server(s) in **your** configs / subscriptions | when you connect |
| Subscription refresh | the subscription URL **you** entered | when you add/refresh a subscription |
| Free-config discovery | public GitHub raw repositories (freefq, Pawdroid, mahdibland, MhdiTaheri, Au1rxx, hamedcode), `radarhub.top`, public Telegram channel previews (`t.me/s/…`) | when you use the "new configs" tab or refresh |
| Config ping (latency) | TCP handshake to each config's server address | when you run a ping |
| Speed test | `speed.cloudflare.com` (`__down`/`__up`), routed **through the selected config** | when you run a speed test |
| Connectivity check | `www.gstatic.com/generate_204` | to verify a config actually routes traffic |
| Exit IP / country detection | `api.ip.sb`, `ipapi.co`, `ipwho.is`, flag images from `flagcdn.com` | after connecting, to show your apparent IP and country flag |
| GitHub API | `api.github.com` | listing public config-source repositories |

Free-config sources are third parties. Configs obtained from them point to
servers operated by whoever published them — treat them accordingly.

## 4. Android permissions and their purpose

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | core networking and reconnect-on-network-change |
| `FOREGROUND_SERVICE` (+ `SPECIAL_USE`) | keeping the VPN tunnel alive with a status notification |
| `POST_NOTIFICATIONS` | connection status notification (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | optional auto-reconnect after reboot |
| `QUERY_ALL_PACKAGES` | the per-app routing list (split tunneling); the list stays on the device |
| `VPN_SERVICE` binding | establishing the TUN interface |

The app requests no location, contacts, storage, camera or microphone
permission.

## 5. What your VPN operator sees

When you connect, traffic exits from the VPN server you configured. That
server's operator (your own panel, a subscription provider, or a free-config
publisher) can see the destination of your traffic in the same way any VPN
operator can. KUVAVpn adds no identification of its own beyond what the
config protocol requires (e.g. your UUID, which the server must have anyway).

## 6. Children

The app is not directed at children under 13 and collects no data from
anyone, so there is nothing to delete on their behalf.

## 7. Changes

Any change to this policy will be committed to this repository with a new
"last updated" date.

## 8. Contact

Open an issue at https://github.com/kuva-project/kuva-vpn/issues, or contact
the maintainer via GitHub: [@kuva-project](https://github.com/kuva-project).
listed on the repository page.
