# WireTurn — Android WebRTC Tunnel

Android-клиент для [olcrtc](https://github.com/openlibrecommunity/olcrtc) и [Turnable](https://github.com/TheAirBlow/Turnable) — проброс трафика через инфраструктуру WebRTC.

> **Disclaimer:** Проект предназначен исключительно для образовательных и исследовательских целей.

## Принцип работы

WireTurn маскирует ваш интернет-трафик под легитимные WebRTC-сессии, делая его неотличимым от обычных видеозвонков или конференций.

1. **Маскировка трафика:** Пакеты (WireGuard или VLESS) упаковываются в протоколы **DTLS** или **SRTP**. Для сетевых фильтров и провайдеров это выглядит как стандартный зашифрованный медиапоток.
2. **Использование посредников:** Трафик передается через TURN-серверы крупных платформ (для **Turnable**) или платформы видеоконференций (для **olcrtc**).
3. **Механизмы туннелирования:**
    - **Turnable**: Имитирует видеозвонки и демонстрацию экрана. Трафик распределяется по нескольким параллельным потокам (Multi-Peer) для достижения максимальной скорости.
    - **olcrtc**: Поддерживает широкий спектр транспортов для обхода самых строгих ограничений:
        - **DataChannel**: Максимальная скорость и минимальные задержки (рекомендуется).
        - **VP8Channel / SEIChannel**: Маскировка данных внутри видеопотоков (VP8 или H.264), что делает трафик полностью идентичным видеозвонку.
        - **VideoChannel**: Передача данных через визуальные образы (QR-коды или тайлы) внутри видеотрансляции — крайний случай.

## Возможности

- **Xray Engine** — встроенный прокси-движок для работы с VLESS и WireGuard в режиме локального SOCKS5/HTTP прокси.
- **Dual-route (VLESS)** — интеллектуальная маршрутизация: автоматическое переключение на прямой адрес сервера (Direct Route) при его доступности, минуя WebRTC-туннель для снижения задержек.
- **VPN Mode & Split Tunneling** — полноценный VPN-режим (TUN) с поддержкой режимов исключения (Bypass) и включения (Include) конкретных приложений. Реализована группировка приложений и быстрый поиск.
- **Система профилей** — создание независимых конфигураций, поддержка массового импорта/экспорта (JSON, ZIP) и удобное управление.
- **Два режима работы**:
    - **Turnable** — классический проброс через TURN-серверы (ядро **Turnable**).
    - **olcrtc** — работа через платформы видеоконференций (ядро **olcrtc**).
- **Быстрое управление** — смена профиля прямо из уведомления, управление через Quick Settings Tile или Broadcast Intent API.
- **Метрики в реальном времени** — интерактивное отображение пинга и актуальной скорости передачи данных (RX/TX) на главном экране.
- **Умное ожидание сети** — при потере связи приложение не выдает ошибку, а переходит в состояние `Ожидание сети`, автоматически восстанавливая соединение при появлении намёка на интернет.
- **Автозапуск** — автоматическая активация туннеля при потере доступа к контрольному URL (например, при попадании в сеть с ограничениями).
- **Локализация** — поддержка независимой смены языка интерфейса (RU/EN) внутри приложения.
- **Кастомное ядро** — возможность загрузки собственного бинарника прокси-туннеля и гибкая настройка параметров запуска.
- **Авто-обновление** — встроенная система проверки обновлений с поддержкой **Beta (Unstable)** канала, просмотром ченджлога (Markdown) и обновлением по хэшу коммита.
- **Material You** — современный интерфейс с поддержкой динамических цветов, expressive motion анимаций, кастомным Splash Screen и тактильной отдачей.

## Автоматизация (Intent API)

Управление туннелем из сторонних приложений (например, Tasker):
- **Запуск:** `com.wireturn.app.START_PROXY`
- **Остановка:** `com.wireturn.app.STOP_PROXY`

## Скриншоты

<p>
  <img src="docs/screenshots/screenshot_1.png" width="155" alt="Screenshot 1" />
  <img src="docs/screenshots/screenshot_2.png" width="155" alt="Screenshot 2" />
  <img src="docs/screenshots/screenshot_3.png" width="155" alt="Screenshot 3" />
  <img src="docs/screenshots/screenshot_4.png" width="155" alt="Screenshot 4" />
</p>

## Требования

- Android 8.0+ (API 26)
- Архитектуры: `arm64-v8a`, `x86_64` (поддержка 32-битных систем прекращена)
- VPS с установленным WireGuard или VLESS-сервером

## Быстрый старт

Подробные инструкции по настройке серверной части и клиента WireTurn доступны в следующих руководствах:

- **[Настройка сервера Turnable](docs/guides/turnable.md)**
- **[Настройка сервера olcrtc](docs/guides/olcrtc.md)**

## Стек технологий

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Native Kernels (C/Go)** (автоматическая сборка из исходников через Git-субмодули):
    - `libturnable.so` — ядро Turnable ([TheAirBlow/Turnable](https://github.com/TheAirBlow/Turnable)).
    - `libolcrtc.so` — ядро olcrtc ([openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc)).
    - `libxray.so` — движок Xray ([spkprsnts/vless-client](https://github.com/spkprsnts/vless-client)).
    - `libhevsocks5.so` — сетевой стек для VPN-режима ([heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)).

## Для разработчиков

Проект поддерживает автоматизированную сборку нативных компонентов.
```bash
git clone --recursive https://github.com/spkprsnts/WireTurn.git
./gradlew buildCBinaries buildGoBinaries # Сборка всех .so через Docker/WSL
./gradlew assembleDebug
```

## Упоминания

- [TheAirBlow/Turnable](https://github.com/TheAirBlow/Turnable) — ядро Turnable.
- [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc) — ядро olcrtc.
- [samosvalishe/turn-proxy-android](https://github.com/samosvalishe/turn-proxy-android) — база UI и логики.
- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — база для ядра Xray.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — реализация сетевого стека для VPN-режима.

## Лицензия

[GPL-3.0](LICENSE)
