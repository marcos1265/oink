<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/banner-dark.png">
    <img alt="Oink — Oink Is Not only a KVM. Your computers, working as one." src=".github/assets/banner-light.png" width="860">
  </picture>
</p>

# Oink (Modified Fork) — Oink Is Not only a KVM

> **Notice (Modified Version):** This repository is an **unofficial modified fork** based on [oinkvm/oink](https://github.com/oinkvm/oink) and is **not** the official Oink release or endorsed by its original authors. Original attribution and licensing under GPL-3.0 with Section 7 additional terms are preserved in [LICENSE](LICENSE) and [LICENSE-ADDITIONAL-TERMS.md](LICENSE-ADDITIONAL-TERMS.md).

**English** | [简体中文](README.zh-CN.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

Oink, short for "Oink Is Not only a KVM", is modern software that builds a peer-to-peer sharing network across multiple Windows, Mac, and mobile devices. Use the keyboard, mouse and trackpad of any device to control any nearby device, with the same native feel everywhere; play the audio of several devices through the speakers or headphones connected to one of them; share the clipboard and drag and drop between devices; discover devices on the local network automatically; all traffic is encrypted by default.

## Changes in This Fork

- **Pure-Local Operation**: Removed external telemetry, online update/feed fetching, and promotional links so all communication stays on the local network.
- **Windows & Desktop**:
  - **Portable Mode**: Place a `portable.txt` file next to the executable to store configuration, caches, and logs inside a local `./data` directory.
  - **Input & Pointer Improvements**: Added configurable corner dead zones and an optional hold-Ctrl modifier to prevent accidental cross-screen jumps near screen edges. Added an independent mouse mode to keep the local cursor unlocked and visible alongside external injected pointer input from remote desktop software. Fixed cursor-warping / camera-orbit jumps when dragging inside cursor-captured 3D applications.
  - **Service & Compatibility Updates**: Refined Windows background service startup handling/visibility and extended local clipboard & compatibility controls.
- **Android**:
  - **Multi-Language Support**: Full runtime localization in English, Simplified Chinese, Japanese, and Korean across the app, Settings, persistent notifications, Quick Settings tiles, and error toasts (with shorter, deduplicated, tap-to-dismiss toasts).
  - **Audio Output & Battery**: Removed exclusive audio-focus locking so Oink mixes smoothly with other media apps without getting stuck unavailable, switched default speaker output to Android's standard shared media pipeline (`DEEP_BUFFER` / 16-bit PCM) to match native system media loudness and reduce power draw, added a **Low-latency audio** toggle in Settings, and scoped high-performance Wi-Fi locks strictly to active audio streaming.
  - **Navigation, Lifecycle & Controls**: Added an explicit **Quit Oink** action in Settings and the persistent notification, preserved Transfer scroll state across smooth tab transitions, and added an optional customizable on-screen button overlay with profiles, hold/toggle actions, and keyboard/mouse bindings on the Control page.

## Install

Get builds for this fork from [Releases](https://github.com/marcos1265/oink/releases), or view the original upstream project at [oinkvm/oink](https://github.com/oinkvm/oink).

Supported operating systems and versions:

- **Mac:** Apple silicon (arm64), macOS 14.2+; Intel (x64), macOS 14.2+ (untested)
- **Windows:** x64, Windows 10 version 1809 (build 17763) or later, or Windows 11. Windows 10 versions before 22H2 are untested. Windows 10 LTSC (such as LTSC 2019 / LTSC 2021) does not include the WebView2 Runtime; install the Microsoft Edge WebView2 Runtime (Evergreen) first: https://developer.microsoft.com/microsoft-edge/webview2/
- **Android:** arm64-v8a / armeabi-v7a / x86_64 devices.

If SmartScreen says "Windows protected your PC", use **More info → Run anyway** only if offered. UAC may show an unknown publisher.

## Contributing

This project does not accept pull requests without context, because there is no one to review the code. Upstream bug reports and upstream contributions belong at [oinkvm/oink](https://github.com/oinkvm/oink/issues).

## FAQ

**Is Oink based on, or compatible with, Deskflow?**\
No. Oink is a project started completely from scratch. It uses its own peer-to-peer (not client–server) protocol and is not compatible with other software KVMs.

**Do I have to designate one device as the controller?**\
No. Oink uses a peer-to-peer design: the keyboard and mouse connected to any device can control the other devices.

**Can I share trackpad gestures?**\
Yes, but trackpads on Windows have not been tested.

**Does it support clipboard sharing and drag and drop across operating systems?**\
Yes. The clipboard is synced across devices automatically, and you can drag text, images and files directly from one device to another — even straight into apps such as Chrome.

**Do I need to buy a license for personal or commercial use?**\
No. Oink is free software released under GPL-3.0-only with additional terms, so you can use it for any personal or commercial purpose without asking for any further license. If you distribute Oink or a modified version of it, you must provide the complete source code under the same license, keep the original author attribution and license notices, and mark your changes; under the additional terms you also may not use the Oink name to promote derived products. Modifying Oink only for your own use, without distributing it, carries none of these obligations.

## License

This project is licensed under [GPL-3.0-only](LICENSE) with [additional terms](LICENSE-ADDITIONAL-TERMS.md).
