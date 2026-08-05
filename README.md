# Ward

An Android client for [Xray core](https://github.com/XTLS/Xray-core) and [v2fly core](https://github.com/v2fly/v2ray-core), with a reworked interface.

> [!IMPORTANT]
> **Ward is an independent fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG).**
> It is not affiliated with, endorsed by, or supported by the v2rayNG project.
> Please report issues with this fork here — **not** to the v2rayNG maintainers.

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.4.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

---

## What is different from v2rayNG

- Rebuilt interface: grouped settings screens, animated profile cards and power button, a floating glass navigation bar
- Live core log viewer with log files kept on disk
- Selectable ping types (proxy GET, proxy HEAD, TCP, ICMP) with parallel measurement
- Ping support for raw JSON profiles whose outbound uses `vnext`, `servers`, `server` or wireguard `peers`

Everything else — the cores, the protocols, the config handling — comes from v2rayNG.

---

## Download

Builds are published in this repository's [Releases](https://github.com/shashachkaaa/VanguardClient/releases).

Do not download Ward from the v2rayNG release page, and do not expect
v2rayNG releases to contain any of the changes listed above.

---

## Geoip and Geosite

- `geoip.dat` and `geosite.dat` live in `Android/data/com.ward.client/files/assets` (the path may differ on some devices)
- the download feature fetches the enhanced version from [this repo](https://github.com/Loyalsoldier/v2ray-rules-dat) (it needs a working proxy)
- the official [domain list](https://github.com/v2fly/domain-list-community) and [ip list](https://github.com/v2fly/geoip) can be imported manually
- a third-party dat file can be used from the same folder, e.g. [h2y](https://guide.v2fly.org/routing/sitedata.html#%E5%A4%96%E7%BD%AE%E7%9A%84%E5%9F%9F%E5%90%8D%E6%96%87%E4%BB%B6)

---

## Development guide

- The Android project under the `V2rayNG` folder compiles directly in Android Studio or with the Gradle wrapper. The v2ray core inside the bundled aar is (probably) outdated.
- The aar is built from the Golang project [AndroidLibXrayLite](https://github.com/shashachkaaa/AndroidLibXrayLite), a fork of [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite). For a quick start, read the guides for [Go Mobile](https://github.com/golang/go/wiki/Mobile) and [Makefiles for Go Developers](https://tutorialedge.net/golang/makefiles-for-go-developers/).
- The app runs on Android emulators. On WSA the VPN permission has to be granted with `appops set [package name] ACTIVATE_VPN allow`.

---

## Credits and license

Ward is built on [v2rayNG](https://github.com/2dust/v2rayNG) by [2dust](https://github.com/2dust) and contributors, and on
[Xray-core](https://github.com/XTLS/Xray-core) / [v2ray-core](https://github.com/v2fly/v2ray-core).

Like v2rayNG, this project is licensed under the [GNU General Public License v3.0](LICENSE).
The upstream copyright notices are kept intact and the complete source of this fork is
published in this repository, as GPL-3.0 requires.
