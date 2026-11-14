# Home Panel

A small Android app for your Home Assistant server. It can connect three ways:

| Connection | Example address | When it's used |
|---|---|---|
| **Local** | `http://homeassistant.local:8123` or `http://192.168.1.20:8123` | On your home Wi‑Fi/Ethernet |
| **Tailscale** | `http://homeassistant.your-tailnet.ts.net:8123` or `http://100.x.y.z:8123` | When the Tailscale VPN is on |
| **Remote** | `https://xxxx.ui.nabu.casa` or `https://ha.example.com` | Anywhere with internet |

Runs on **Android 7.0 (API 24) through Android 17 (API 37)**; it targets API 37.

## Features

- **Automatic mode** (default): checks every address you've set at the same time and picks
  the best one that answers: Local when you're on Wi‑Fi, then Tailscale when it's on, then Remote.
  It re-checks when your network changes (leaving home, Tailscale turned on or off) and switches
  without losing the page you were on.
- **Fixed modes**: force Local, Tailscale or Remote from the ⋮ menu → *Connect via*.
- **Tailscale detection**: sees whether the Tailscale VPN is up (by its `100.64.0.0/10` /
  `fd7a:115c:a1e0::/48` addresses) and shows a banner with an *Open Tailscale* button when you need it.
- **Android 17 local network permission**: asks for `ACCESS_LOCAL_NETWORK` when a local address
  is set, which Android 17 requires before an app can reach devices on your LAN.
- Plain `http://` is allowed (common on the LAN and over Tailscale, which already encrypts traffic).
  An optional switch accepts self-signed certificates, for the local and Tailscale addresses only.
- *Test connections* in Settings shows which addresses are reachable right now.
- File uploads (e.g. backups, images), edge-to-edge layout, predictive back, external links open in your browser.

## Tailscale setup

1. Install the [Tailscale app](https://play.google.com/store/apps/details?id=com.tailscale.ipn) on the phone and sign in to the same tailnet as your Home Assistant host
   (e.g. via the Tailscale add-on for Home Assistant).
2. In Home Panel → Settings, put your Home Assistant address on the tailnet in **Tailscale URL**:
   its MagicDNS name (`http://homeassistant.<tailnet>.ts.net:8123`) or its `100.x.y.z` IP.
3. If you use the Tailscale add-on's *proxy* / HTTPS feature, use `https://homeassistant.<tailnet>.ts.net` instead.

Tailscale stays in its own app: Home Panel uses the VPN when it's on and doesn't embed a second VPN.

## Building

Needs JDK 17+ and the Android SDK with `platforms;android-37.0`.

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

GitHub Actions builds the APK on every push (see the **home-panel-apk** artifact on each run).
Pushing a version tag publishes it as a GitHub Release:

```sh
git tag v1.0.1 && git push origin v1.0.1
```

Bump `versionCode` and `versionName` in `app/build.gradle.kts` first, or phones won't offer it as an update.

The release build is signed with the debug key so it installs directly. Set up your own signing
key before distributing it.
