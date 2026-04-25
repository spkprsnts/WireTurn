# WireTurn — Android TURN Proxy

Android-клиент для [vk-turn-proxy](https://github.com/spkprsnts/vk-turn-proxy/tree/dc) и [Turnable](https://github.com/TheAirBlow/Turnable) — проброс трафика через TURN-серверы. Поддерживает инкапсуляцию WireGuard и VLESS.

> **Disclaimer:** Проект предназначен исключительно для образовательных и исследовательских целей.

## Принцип работы

Пакеты инкапсулируются в DTLS 1.2 и передаются на TURN-сервер по протоколу STUN ChannelData (TCP или UDP). TURN-сервер пересылает трафик по UDP на ваш VPS, где он расшифровывается и передаётся в выбранный прокси-протокол (WireGuard или VLESS). Учётные данные для TURN генерируются автоматически из ссылки на звонок (для vk-turn-proxy), берутся из `turnable://` ссылки или из параметров DataChannel.

## Возможности

- **VPN Mode (Global)** — полноценный VPN-режим (TUN) для перенаправления трафика всего устройства через прокси (на базе `tun2socks`).
- **Xray Engine** — встроенный прокси-движок для работы в режиме локального SOCKS5/HTTP прокси.
- **Два режима работы клиента**:
    - **TURN Mode** — классический проброс через TURN-серверы (выбор ядра: vk-turn-proxy или Turnable).
    - **DC Mode (DataChannel)** — работа через инфраструктуру **Salute Jazz** и **WB Stream** (форк-ядро vk-turn-proxy).
- **Поддержка Turnable** — использование высокопроизводительного ядра с поддержкой ссылок формата `turnable://`.
- **Поддержка VLESS и WireGuard** — инкапсуляция трафика в современные прокси-протоколы.
- **Privacy Mode** — режим конфиденциальности, скрывающий чувствительные данные (ссылки, адреса, UUID) в интерфейсе.
- **Метрики в реальном времени** — отображение пинга до цели и скорости передачи данных (RX/TX) непосредственно на главном экране.
- **Умный Watchdog** — автоматическое переподключение при смене сети, потере пакетов или падении процесса ядра.
- **Автоматизация** — управление через Quick Settings Tile (плитка в шторке) или через Broadcast Intent API.
- **Кастомное ядро** — возможность загрузки собственного бинарника прокси-туннеля прямо из интерфейса.
- **Авто-обновление** — встроенная система проверки и установки обновлений приложения, работающая даже через активный прокси.
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
- Архитектуры: `arm64-v8a`, `armeabi-v7a`, `x86_64`
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

2. **Конфигурация прокси-туннеля:**
   Перейдите на вкладку **Клиент** и настройте параметры соединения:
   - **Выбор режима:**
     - **TURN**: классический проброс. Выберите ядро (**vk-turn-proxy** или **Turnable**) и укажите настройки подключения (адрес и ссылка на звонок, либо `turnable://`).
     - **DC (DataChannel)**: работа через WebRTC-инфраструктуру. Выберите сервис (**Salute Jazz** или **WB Stream**) и введите идентификатор комнаты (Код:Пароль или UUID).
   - **Локальный адрес:** укажите адрес и порт для прослушивания (например, `127.0.0.1:9000`). Этот адрес будет использоваться как точка входа для трафика.

3. **Настройка Xray (для WireGuard/VLESS):**
   Если вы используете Xray для инкапсуляции трафика:
   - Перейдите на вкладку **Xray** и импортируйте конфигурацию (через QR-код, файл или буфер обмена).
   - **Важно:** Для WireGuard убедитесь, что поле `Endpoint` в настройках Xray совпадает с **Локальным адресом прослушивания**. Для VLESS можно включить опцию «Использовать адрес прослушивания» в настройках прокси или вручную изменить адрес в ссылке.

4. **Запуск и управление:**
   - На вкладке **Главная** нажмите центральную кнопку для запуска прокси-туннеля.
   - **Xray**: переключатель управляет использованием Xray-движка. Xray запускается только при активном прокси-туннеле и может быть отключен независимо от него.
   - **VPN Mode**: системный VPN-режим. Работает **только при включенном Xray**, так как использует его как вышестоящий прокси. Если Xray не активен, VPN Mode не запустится.

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
