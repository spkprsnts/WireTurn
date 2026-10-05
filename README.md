<p align="center">
  <img src="docs/ic_launcher.webp" width="192" alt="WireTurn Logo" />
</p>

# WireTurn — Android WebRTC & WebDAV Tunnel

Android-клиент для [Turnable](https://github.com/TheAirBlow/Turnable), [olcRTC](https://github.com/openlibrecommunity/olcrtc), [WebDAV](https://github.com/spkprsnts/webdav-tunnel), [FreeTurn](https://github.com/samosvalishe/free-turn-proxy), [qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android), [OpenFlux](https://github.com/p1neappleXpress/OpenFlux) и [CSQTT](https://github.com/amurcanov/csqtt) — туннелирование трафика через WebRTC и WebDAV.

> **Disclaimer:** Проект предназначен исключительно для образовательных и исследовательских целей.

## Принцип работы

WireTurn упаковывает трафик в стандартные протоколы WebRTC (**DTLS**/**SRTP**) или передаёт его через облачные хранилища, в зависимости от выбранного ядра.

### Turnable
Туннелирование TCP/UDP через TURN-серверы или SFU-платформы: несколько параллельных пиров (Multi-Peer) для пропускной способности и стабильности, мультиплексирование потоков, опциональная имитация видеотрафика (SRTP/DTLS поверх VP8) для работы через SFU в режиме Relay, сквозное шифрование рукопожатия.

### olcRTC
Туннелирование через платформы видеоконференций с SOCKS5-прокси на клиенте. Транспорты: **DataChannel** (SCTP, низкая задержка), **VP8Channel** (стеганография в видеопотоке через KCP), **SEIChannel** (упаковка в SEI-метаданные H.264), **VideoChannel** (визуальная стеганография — QR-коды/графические тайлы).

### WebDAV
Туннелирование через любое WebDAV-совместимое облачное хранилище: передача данных поллингом, трафик неотличим от обычной работы с облачным диском по HTTPS.

### FreeTurn
Туннелирование через WebRTC поверх UDP с подпиской на список серверов (поддерживаются как обычные, так и Base64-закодированные подписки), гибкой настройкой обфускации/транспорта до TURN-relay и ручным решением капчи через встроенный браузер при необходимости.

### qWDTT
Ещё одно туннелирование через TURN-инфраструктуру звонков VK: WireGuard поверх DTLS-релея, локальный SOCKS5-прокси на клиенте. Ссылки и QR-коды в формате [qWDTT](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (включая устаревшую схему `wdtt://`) распознаются напрямую. Если на сервере включён raw-режим (`-listen-raw`) и в профиле указан его порт, VPN без Xray работает напрямую через TUN ядра.

### OpenFlux
Туннелирование через легитимные сторонние сервисы: **Yandex.Docs** (курсор совместного редактирования как канал передачи данных) или **MAX** (WebRTC DataChannel внутри голосового звонка) — серверная часть подключается к тому же документу/звонку вместо прямого адреса. Локальный SOCKS5-прокси на клиенте, без поддержки авторизации на нём (ограничение самого ядра). Через Cups.online и Mail.ru Документы доступен и режим без сервера: выходом служит PHP-нода на обычном веб-хостинге вместо VDS (только TCP).

### CSQTT
Сырые IP-пакеты через TURN-релеи звонков VK, замаскированные под медиатрафик звонка. Само ядро работает только с TUN: в VPN-режиме без Xray оно получает TUN устройства напрямую, в остальных случаях рядом запускается [socks2tun](https://github.com/spkprsnts/socks2tun) и даёт обычный локальный SOCKS5 с авторизацией. Ссылки `csqtt://` официального приложения и серверных панелей распознаются напрямую. Недоступно на `x86`.

## Возможности

- **Xray-core** — встроенный движок для VLESS/Trojan/Hysteria2 и WireGuard в режиме локального SOCKS5/HTTP-прокси. Работает поверх ядра или сам по себе: ссылка `vless://`, `trojan://` или `hysteria2://` импортируется как отдельный профиль Xray без ядра.
- **Dual-route** — автоматическое переключение на прямой адрес сервера при его доступности, минуя WebRTC-туннель, для снижения задержек.
- **SOCKS5 Chain** — для SOCKS5-нативных ядер (olcRTC, WebDAV, qWDTT, OpenFlux, CSQTT) подключается к VLESS/Trojan-серверу через локальный SOCKS5 ядра вместо прямого соединения (недоступно для Hysteria2).
- **VPN-режим и Split Tunneling** — полноценный TUN-режим с исключением (Bypass) или включением (Include) конкретных приложений. Ядра, которые умеют работать с TUN сами (CSQTT, qWDTT с raw-портом), без Xray получают TUN напрямую, минуя SOCKS5; такие профили помечены тегом «TUN».
- **Профили и подписки** — независимые конфигурации, массовый импорт, автообновление по расписанию с учётом квоты трафика; импорт по диплинкам `wireturn://` и `wt://`. Подробности — в [спецификации подписок и профилей](docs/subscriptions.md).
- **Быстрое управление** — смена профиля из уведомления, Quick Settings Tile и Intent API.
- **Умное ожидание сети** — восстановление туннеля при появлении интернета без лишних уведомлений об ошибках.
- **Material 3 Expressive** — динамические цвета и expressive motion анимации.

## Автоматизация (Intent API)

Управление туннелем из сторонних приложений (например, Tasker):
- **Запуск:** `com.wireturn.app.START_CORE`
- **Остановка:** `com.wireturn.app.STOP_CORE`

## Скриншоты

<p>
  <img src="docs/screenshots/screenshot_1.png" width="130" alt="Screenshot 1" />
  <img src="docs/screenshots/screenshot_2.png" width="130" alt="Screenshot 2" />
  <img src="docs/screenshots/screenshot_3.png" width="130" alt="Screenshot 3" />
  <img src="docs/screenshots/screenshot_4.png" width="130" alt="Screenshot 4" />
  <img src="docs/screenshots/screenshot_5.png" width="130" alt="Screenshot 5" />
  <img src="docs/screenshots/screenshot_6.png" width="130" alt="Screenshot 6" />
</p>

## Быстрый старт

### Требования
- Android 8.0+ (API 26), архитектуры `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (CSQTT — без `x86`).
- VPS для серверной части (Turnable, olcRTC, WebDAV, FreeTurn, qWDTT, OpenFlux или CSQTT).

### Настройка
- **[WT Panel](https://github.com/spkprsnts/wt-panel)** — панель для создания и управления серверами
- **[Настройка сервера Turnable](docs/guides/turnable.md)**
- **[Настройка сервера olcRTC](docs/guides/olcrtc.md)**
- **[Спецификация подписок и профилей](docs/subscriptions.md)**
- **[Многокадровый QR для длинных конфигов](docs/qr-transfer.md)**

## Стек технологий

**Kotlin** + **Jetpack Compose** (Material 3 Expressive). Нативные компоненты (C/Go/Rust) собираются из исходников через Git-субмодули:

- `libturnable.so` — [TheAirBlow/Turnable](https://github.com/TheAirBlow/Turnable)
- `libolcrtc.so` — [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc)
- `libwebdav.so` — [spkprsnts/webdav-tunnel](https://github.com/spkprsnts/webdav-tunnel)
- `libfreeturn.so` — [samosvalishe/free-turn-proxy](https://github.com/samosvalishe/free-turn-proxy)
- `libqwdtt.so` — [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (`go_client`)
- `libopenflux.so` — [p1neappleXpress/OpenFlux](https://github.com/p1neappleXpress/OpenFlux)
- `libcsqtt.so` — [amurcanov/csqtt](https://github.com/amurcanov/csqtt) (`rust-client`)
- `libsocks2tun.so` — SOCKS5 поверх TUN-only ядер, [spkprsnts/socks2tun](https://github.com/spkprsnts/socks2tun)
- `libxray.so` — [spkprsnts/vless-client](https://github.com/spkprsnts/vless-client)
- `libhevsocks5.so` — сетевой стек VPN-режима, [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)

## Для разработчиков

Сборка нативных библиотек (`.so`) автоматизирована через Gradle-задачи; рекомендуется **Linux** (Ubuntu/Debian) или **Windows + WSL2**.

Зависимости: `build-essential`, `pkg-config`, `cmake`, `golang` (1.23+), `openjdk-21-jdk`, `python3`, `git`, `curl`, а для CSQTT — Rust (через `rustup`) с Android-таргетами и `cargo-ndk`.

```bash
sudo apt update && sudo apt install -y build-essential pkg-config cmake git curl golang-go openjdk-21-jdk python3
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked

git clone --recursive https://github.com/spkprsnts/WireTurn.git
./gradlew buildCBinaries buildGoBinaries buildRustBinaries   # нативные компоненты
./gradlew assembleDebug                                      # APK
```

## Упоминания

- [TheAirBlow/Turnable](https://github.com/TheAirBlow/Turnable) — проект Turnable.
- [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc) — проект olcRTC.
- [spkprsnts/webdav-tunnel](https://github.com/spkprsnts/webdav-tunnel) — проект WebDAV Tunnel.
- [samosvalishe/free-turn-proxy](https://github.com/samosvalishe/free-turn-proxy) — проект FreeTurn.
- [samosvalishe/turn-proxy-android](https://github.com/samosvalishe/turn-proxy-android) — база UI и логики.
- [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) — проект qWDTT.
- [p1neappleXpress/OpenFlux](https://github.com/p1neappleXpress/OpenFlux) — проект OpenFlux.
- [amurcanov/csqtt](https://github.com/amurcanov/csqtt) — проект CSQTT (лицензия PolyForm Noncommercial 1.0.0, только некоммерческое использование).
- [spkprsnts/socks2tun](https://github.com/spkprsnts/socks2tun) — SOCKS5 для TUN-only ядер.
- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — кодовая база Xray.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — реализация сетевого стека для VPN-режима.

## Лицензия

[GPL-3.0](LICENSE)
