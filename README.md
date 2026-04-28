# WireTurn — Android TURN Proxy

Android-клиент для [vk-turn-proxy](https://github.com/spkprsnts/vk-turn-proxy/tree/dc) и [Turnable](https://github.com/TheAirBlow/Turnable) — проброс трафика через TURN-серверы. Поддерживает инкапсуляцию WireGuard и VLESS.

> **Disclaimer:** Проект предназначен исключительно для образовательных и исследовательских целей.

## Принцип работы

Пакеты инкапсулируются в DTLS 1.2 и передаются на TURN-сервер по протоколу STUN ChannelData (TCP или UDP). TURN-сервер пересылает трафик по UDP на ваш VPS, где он расшифровывается и передаётся в выбранный прокси-протокол (WireGuard или VLESS). Учётные данные для TURN генерируются автоматически из ссылки на звонок (для vk-turn-proxy), берутся из `turnable://` ссылки или из параметров DataChannel.

## Возможности

- **VPN Mode & Split Tunneling** — полноценный VPN-режим (TUN) с возможностью исключения выбранных приложений из туннеля (на базе `tun2socks`).
- **Система профилей** — создание независимых конфигураций, поддержка импорта/экспорта в JSON и быстрое переключение между ними.
- **Xray Engine** — встроенный прокси-движок для работы с VLESS и WireGuard в режиме локального SOCKS5/HTTP прокси.
- **Два режима работы клиента**:
    - **TURN Mode** — классический проброс через TURN-серверы (выбор ядра: vk-turn-proxy или Turnable).
    - **DC Mode (DataChannel)** — работа через инфраструктуру **Salute Jazz** и **WB Stream** (форк-ядро vk-turn-proxy).
- **Быстрое управление** — смена профиля прямо из уведомления, управление через Quick Settings Tile или Broadcast Intent API.
- **Метрики в реальном времени** — отображение пинга и актуальной скорости передачи данных (RX/TX) на главном экране.
- **Умный Watchdog** — автоматическое переподключение при смене сети, потере пакетов или падении процесса ядра.
- **Privacy Mode** — режим конфиденциальности, скрывающий чувствительные данные (ссылки, адреса, UUID) в интерфейсе.
- **Кастомное ядро** — возможность загрузки собственного бинарника прокси-туннеля прямо из интерфейса.
- **Авто-обновление** — встроенная система проверки и установки обновлений приложения.
- **Material You** — современный интерфейс с поддержкой динамических цветов и тактильной отдачи.

## Автоматизация (Intent API)

Управление прокси из сторонних приложений (например, Tasker):
- **Запуск:** `com.wireturn.app.START_PROXY`
- **Остановка:** `com.wireturn.app.STOP_PROXY`

## Скриншоты

<p float="left">
  <img src="docs/screenshots/screenshot_1.jpg" width="250" />
  <img src="docs/screenshots/screenshot_2.jpg" width="250" />
  <img src="docs/screenshots/screenshot_3.jpg" width="250" />
</p>

## Требования

- Android 8.0+ (API 26)
- Архитектуры: `arm64-v8a`, `x86_64`
- VPS с установленным WireGuard или VLESS-сервером

## Быстрый старт

### 1. Серверная часть

**vk-turn-proxy:**
```bash
# Скачать бинарник
wget https://github.com/cacggghp/vk-turn-proxy/releases/latest/download/server-linux-amd64
chmod +x server-linux-amd64

# Запустить для WireGuard
nohup ./server-linux-amd64 -listen 0.0.0.0:56000 -connect 127.0.0.1:<порт_wg> > server.log 2>&1 &

# Запустить для VLESS
nohup ./server-linux-amd64 -vless -listen 0.0.0.0:56000 -connect 127.0.0.1:<порт_vless> > server.log 2>&1 &
```

**Режим DataChannel (DC):**
Требуется сервер с поддержкой Jazz/WB Stream.
- **Репозиторий:** [spkprsnts/vk-turn-proxy (branch: dc)](https://github.com/spkprsnts/vk-turn-proxy/tree/dc)

**Turnable:**
Используйте сервер и инструкции из репозитория.
- **Репозиторий:** [TheAirBlow/Turnable](https://github.com/TheAirBlow/Turnable/blob/main/README_RU.md)

### 2. Настройка клиента

1. **Установка:**
   Скачайте и установите APK-файл из раздела [Releases](https://github.com/spkprsnts/WireTurn/releases/latest).

2. **Работа с профилями:**
   Все настройки привязаны к профилям. Вы можете создавать неограниченное количество конфигураций, экспортировать их в JSON и быстро переключаться между ними.

3. **Конфигурация прокси-туннеля:**
   В выбранном профиле перейдите на вкладку **Клиент** и настройте параметры соединения:
   - **Выбор режима:**
     - **TURN**: классический проброс. Выберите ядро (**vk-turn-proxy** или **Turnable**) и укажите настройки подключения (адрес и ссылка на звонок, либо `turnable://`).
     - **DC (DataChannel)**: работа через WebRTC-инфраструктуру. Выберите сервис (**Salute Jazz** или **WB Stream**) и введите идентификатор комнаты (Код:Пароль или UUID).
   - **Локальный адрес:** укажите адрес и порт для прослушивания (например, `127.0.0.1:9000`).

4. **Настройка Xray (для WireGuard/VLESS):**
   Если вы используете Xray для инкапсуляции трафика:
   - Перейдите на вкладку **Xray** и импортируйте конфигурацию (через QR-код, файл или буфер обмена).
   - **Важно:** Для WireGuard убедитесь, что поле `Endpoint` в настройках Xray совпадает с **Локальным адресом прослушивания**. Для VLESS можно включить опцию «Использовать адрес прослушивания» в настройках прокси.

5. **Запуск и управление:**
   - На вкладке **Главная** нажмите центральную кнопку для запуска.
   - **VPN Mode**: системный VPN-режим. Работает **только при включенном Xray**, так как использует его как вышестоящий прокси.
   - **Split Tunneling**: в настройках можно выбрать приложения-исключения для VPN-режима.

> **Примечание:** Обратите внимание на соответствие протоколов. Если прокси-туннель настроен на работу с VLESS (флаг `-vless` на сервере), то и в Xray должен быть настроен протокол VLESS. Прямой совместимости между WireGuard и VLESS через один туннель нет.

## Стек технологий

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Native Go Kernels**:
    - `libvkturn.so` — ядро проброса TURN ([spkprsnts/vk-turn-proxy](https://github.com/spkprsnts/vk-turn-proxy/tree/dc)).
    - `libxray.so` — движок Xray (WireGuard/VLESS) ([spkprsnts/vless-client](https://github.com/spkprsnts/vless-client)).
    - `libturnable.so` — ядро Turnable.
    - `libtun2socks.so` — сетевой стек для VPN-режима.

## Упоминания

- [TheAirBlow/Turnable](https://github.com/TheAirBlow/Turnable) — ядро Turnable.
- [cacggghp/vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) — автор оригинального ядра.
- [alxmcp/vk-turn-proxy](https://github.com/alxmcp/vk-turn-proxy) — оригинальная реализация DataChannel.
- [samosvalishe/turn-proxy-android](https://github.com/samosvalishe/turn-proxy-android) — база UI и логики.
- [XTLS/Xray-core](https://github.com/XTLS/Xray-core) — база для ядра Xray.
- [xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks) — реализация tun2socks.

## Лицензия

[GPL-3.0](LICENSE)
