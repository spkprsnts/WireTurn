# WireTurn — Android TURN Proxy

Android-клиент для [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) — проброс WireGuard/Hysteria-трафика через TURN-серверы.

> **Disclaimer:** Проект предназначен исключительно для образовательных и исследовательских целей.

## Принцип работы

Пакеты шифруются DTLS 1.2 и отправляются на TURN-сервер по протоколу STUN ChannelData (TCP или UDP). TURN-сервер пересылает трафик по UDP на ваш VPS, где он расшифровывается и передаётся в WireGuard/Hysteria. Учётные данные для TURN генерируются автоматически из ссылки на звонок.

## Возможности

- **VPN Mode (Global)** — полноценный VPN-режим (TUN) для перенаправления трафика всего устройства через прокси (на базе `tun2socks`)
- **awgproxy** — встроенный клиент WireGuard (форк wireproxy), работающий в режиме локального SOCKS5/HTTP прокси
- **Метрики в реальном времени** — отображение пинга до цели и скорости передачи данных (RX/TX)
- **GUI и Raw режимы** — удобный интерфейс с полями или прямой ввод аргументов ядра для продвинутых пользователей
- **Автоматизация** — управление через Quick Settings Tile (шторка) или через Broadcast Intent (`START_PROXY` / `STOP_PROXY`)
- **Капча и Watchdog** — автоматическое обнаружение/решение капчи и восстановление соединения при обрывах
- **История** — сохранение последних конфигураций для быстрого переключения
- **Кастомное ядро** — возможность использовать собственный бинарник `libvkturn.so`

## Скриншоты

<p float="left">
  <img src="docs/screenshots/screenshot_1.jpg" width="250" />
  <img src="docs/screenshots/screenshot_2.jpg" width="250" />
  <img src="docs/screenshots/screenshot_3.jpg" width="250" />
</p>

## Требования

- Android 8.0+ (API 26)
- Архитектуры: arm64-v8a, armeabi-v7a, x86_64
- VPS с установленным WireGuard или Hysteria
- Ссылка на звонок / данные для DataChannel

## Быстрый старт

### 1. Серверная часть

Установите и запустите серверную часть на VPS:

**Обычный режим (через ссылку на звонок):**
```bash
# Скачать бинарник
wget https://github.com/cacggghp/vk-turn-proxy/releases/latest/download/server-linux-amd64

# Запустить
chmod +x server-linux-amd64
nohup ./server-linux-amd64 -listen 0.0.0.0:56000 -connect 127.0.0.1:<порт_wg> > server.log 2>&1 &
```

**Режим DataChannel (DC):**
Для запуска в этом режиме необходим сервер из ветки [alxmcp/vk-turn-proxy/tree/dc](https://github.com/alxmcp/vk-turn-proxy/tree/dc). Подробные параметры запуска описаны в [инструкции к DC-версии](https://github.com/alxmcp/vk-turn-proxy/tree/dc?tab=readme-ov-file#datachannel).

### 2. Android-клиент

1. Установите APK из [Releases](https://github.com/spkprsnts/WireTurn/releases/latest).
2. Заполните настройки:
   - **Адрес сервера** — IP:порт вашего VPS (например `1.2.3.4:56000`)
   - **Ссылка** — ссылка на звонок
   - **Локальный адрес** — по умолчанию `127.0.0.1:9000`
3. Нажмите кнопку запуска. При успехе в логах появится `Established DTLS connection!`
4. Для использования Wireproxy загрузите конфиг, убедитесь что Endpoint равен локальному адресу и включите его на главном экране

## Стек

- **Kotlin** + **Jetpack Compose** + **Material 3**
- **Coroutines / StateFlow** — реактивная архитектура
- **DataStore** — хранение настроек
- Нативный бинарник на **Go** — `libvkturn.so` (arm64-v8a)
- Нативный бинарник на **Go** — `libwireproxy.so` (arm64-v8a)
- Нативный бинарник на **Go** — `libtun2socks.so` (arm64-v8a)

## Благодарности

- [cacggghp/vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy) — [@cacggghp](https://github.com/cacggghp), оригинальный vk-turn-proxy
- [alxmcp/vk-turn-proxy](https://github.com/alxmcp/vk-turn-proxy) — [@alxmcp](https://github.com/alxmcp), форк ядра с поддержкой новых DC
- [samosvalishe/turn-proxy-android](https://github.com/samosvalishe/turn-proxy-android) — [@samosvalishe](https://github.com/samosvalishe), основа UI и логики клиента
- [mishamosher/awgproxy](https://github.com/mishamosher/awgproxy) — [@mishamosher](https://github.com/mishamosher), реализация awgproxy (форк wireproxy)
- [xjasonlyu/tun2socks](https://github.com/xjasonlyu/tun2socks) — [@xjasonlyu](https://github.com/xjasonlyu), реализация tun2socks

## Лицензия

[GPL-3.0](LICENSE)

