# Установка сервера Turnable

Данное руководство поможет установить сервер Turnable на VPS с Ubuntu Server 24.04.

> Полная документация: [репозиторий Turnable](https://github.com/TheAirBlow/Turnable/blob/main/README_RU.md).
> Все команды выполняются в терминале после подключения к серверу по SSH.

> **Уже установили Turnable версии 0.4.x?** Полная переустановка не требуется — смотрите раздел [«Миграция со старой версии»](#миграция-со-старой-версии-04x--051) в конце этого руководства.

---

## 1. Требования

- **ОС:** Ubuntu Server 24.04 LTS.
- **На VPS уже должен быть настроен** WireGuard и/или VLESS (рекомендуем панель **3x-ui**).
- **Важно:** Turnable устанавливается на **тот же VPS**, где работают WireGuard или VLESS.

---

## 2. Подготовка системы

```bash
sudo apt update && sudo apt install wget -y
```

Создаём пользователя и рабочую папку:

```bash
sudo adduser --system --group --no-create-home --shell /usr/sbin/nologin turnable
sudo mkdir -p /opt/turnable
sudo chown turnable:turnable /opt/turnable
```

---

## 3. Загрузка сервера

```bash
cd /opt/turnable
sudo -u turnable wget https://github.com/TheAirBlow/Turnable/releases/download/0.5.1/turnable-linux-amd64
sudo -u turnable chmod +x turnable-linux-amd64
sudo -u turnable mv turnable-linux-amd64 turnable
```

---

## 4. Генерация ключей шифрования

```bash
sudo -u turnable /opt/turnable/turnable config keygen
```

В выводе появятся `priv_key` и `pub_key`. **Сохраните оба значения** — они нужны в следующем шаге.

---

## 5. Получение Call ID ВКонтакте

> **Совет:** Можно попробовать найти уже существующую публичную комнату через Google по запросу `"https://vk.com/call/join/"`. Если найдёте рабочую ссылку — используйте её Call ID.

1. Перейдите на [vk.com/calls](https://vk.com/calls) и создайте звонок.
2. В адресной строке будет ссылка вида `https://vk.com/call/join/ABC123xyz...`
3. Скопируйте всё после `/join/` — это ваш **Call ID**.

---

## 6. Создание config.json

Начиная с версии 0.5.1 конфиг сервера и список пользователей/маршрутов объединены в один файл (раньше это были отдельные `config.json` и `store.json`). Замените плейсхолдеры на свои значения. Публичный IP сервера можно узнать командой `curl ifconfig.me`.

Перед выполнением команды сгенерируйте UUID пользователя на сайте [uuidgenerator.net](https://www.uuidgenerator.net/version4) и замените `ВСТАВЬТЕ_СГЕНЕРИРОВАННЫЙ_UUID`.

Порты WireGuard и VLESS нужно уточнить в панели **3x-ui** — они могут быть любыми. Откройте панель, найдите свои inbound-записи и посмотрите указанный там порт для каждого протокола. Подставьте эти значения в команду ниже.

```bash
sudo -u turnable tee /opt/turnable/config.json > /dev/null <<EOF
{
    "servers": {
        "main": {
            "type": "relay",
            "platform_id": "vk.com",
            "call_id": "ВСТАВЬТЕ_ID_ЗВОНКА_VK",
            "pub_key": "ВСТАВЬТЕ_ВАШ_PUB_KEY",
            "priv_key": "ВСТАВЬТЕ_ВАШ_PRIV_KEY",
            "proto": "srtp",
            "cloak": "none",
            "listen_addr": "0.0.0.0:56000",
            "public_ip": "ВСТАВЬТЕ_ПУБЛИЧНЫЙ_IP_СЕРВЕРА",
            "provider": "provider_main"
        }
    },
    "providers": {
        "provider_main": {
            "type": "raw",
            "routes": [
                {
                    "id": "wireguard",
                    "address": "127.0.0.1",
                    "port": 51820,
                    "socket": "udp",
                    "transport": "none",
                    "encryption": "handshake",
                    "name": "WireGuard"
                },
                {
                    "id": "vless",
                    "address": "127.0.0.1",
                    "port": 443,
                    "socket": "tcp",
                    "transport": "kcp",
                    "encryption": "handshake",
                    "name": "VLESS"
                }
            ],
            "users": [
                {
                    "uuid": "ВСТАВЬТЕ_СГЕНЕРИРОВАННЫЙ_UUID",
                    "allowed_routes": ["wireguard", "vless"],
                    "type": "relay",
                    "peers": 10
                }
            ]
        }
    }
}
EOF
```

> **Примечание:** В версии 0.5.1 у пользователя больше нет поля `username` (имя участника звонка ВК больше не передаётся через Turnable) и поля `forceturn` для типа `relay` (использовалось только для P2P-режима, который пока не реализован).

---

## 7. Генерация ссылки для приложения

Замените `ВАШ_UUID` на UUID из предыдущего шага. Первый аргумент — это ID сервера из конфига (`main`):

```bash
sudo -u turnable /opt/turnable/turnable config generate main "ВАШ_UUID" wireguard vless
```

Сохраните полученную ссылку `turnable://...` — она нужна при настройке WireTurn.

---

## 8. Настройка автозапуска

```bash
sudo tee /etc/systemd/system/turnable.service > /dev/null <<EOF
[Unit]
Description=Turnable Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=turnable
Group=turnable
WorkingDirectory=/opt/turnable
ExecStart=/opt/turnable/turnable server
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now turnable
```

Проверьте статус — должно быть `active (running)`:
```bash
sudo systemctl status turnable
```

Для выхода нажмите `q`. Если статус `failed` — проверьте правильность данных в config.json, логи: `sudo journalctl -u turnable -n 50`

---

## 9. Настройка в WireTurn

1. **Создать профиль:** Нажмите на блок профиля → `+` → «Создать профиль». Откроется экран создания: введите название и в группе **«Импорт»** вставьте ссылку `turnable://` из шага 7 — тип конфига определится автоматически.

2. **Маршрут:** На следующем экране в первом блоке выберите нужный маршрут — **WireGuard** или **VLESS**. Нажмите **«Далее»**.

3. **Настройка Xray:** Откроется экран Xray. Импортируйте основной VPN-конфиг, соответствующий выбранному маршруту (WireGuard → WG-конфиг, VLESS → VLESS-конфиг). Используйте кнопки в верхней панели: QR-код или иконку импорта («Из буфера» / «Из файла»).

4. **Запуск:** Нажмите на профиль, чтобы выбрать его. Включите **Xray**, при необходимости включите **VPN** для пропуска всего трафика устройства через туннель. Нажмите центральную кнопку.

> **Редактирование:** Чтобы изменить настройки клиента — нажмите кнопку редактирования в блоке профиля. Чтобы изменить конфиг Xray — нажмите на блок Xray.

### Несколько профилей

Для использования нескольких протоколов клонируйте профиль: три точки у профиля → «Клонировать». В копии выберите другой маршрут и замените конфиг, нажав на блок Xray.

---

**Готово!**

---

## Миграция со старой версии (0.4.x → 0.5.1)

Версия 0.5.1 несовместима со старыми клиентскими ссылками `turnable://` и старым форматом конфига — переезд нужно сделать вручную, но без полной переустановки сервера. Обновите и приложение WireTurn до версии, поддерживающей Turnable 0.5.1 — старая версия приложения не сможет разобрать новые ссылки, а новая не поймёт старые.

### 1. Остановите сервис и сделайте бэкап

```bash
sudo systemctl stop turnable
sudo cp /opt/turnable/config.json /opt/turnable/config.json.bak
sudo cp /opt/turnable/store.json /opt/turnable/store.json.bak
```

### 2. Обновите бинарник сервера

Повторите [шаг 3](#3-загрузка-сервера) — скачайте `turnable-linux-amd64` версии **0.5.1** и замените старый файл `/opt/turnable/turnable`.

### 3. Соберите новый config.json из старых файлов

Старые `config.json` и `store.json` объединяются в один файл. Соответствие полей:

| Было (0.4.x)                      | Стало (0.5.1)                                          |
|------------------------------------|---------------------------------------------------------|
| `config.json: platform_id`         | `servers.main.platform_id` (без изменений)              |
| `config.json: call_id`              | `servers.main.call_id` (без изменений)                  |
| `config.json: pub_key` / `priv_key` | `servers.main.pub_key` / `priv_key` (без изменений)      |
| `config.json: relay.proto`          | `servers.main.proto`                                     |
| `config.json: relay.cloak`          | `servers.main.cloak`                                      |
| `config.json: relay.public_ip`      | `servers.main.public_ip`                                  |
| `config.json: relay.port`           | `servers.main.listen_addr` (например, `"0.0.0.0:56000"`) |
| `config.json: p2p.*`                | удалено (P2P-режим пока не реализован)                   |
| `config.json: provider`             | `servers.main.provider` — теперь это **имя** провайдера, его настройки переехали в `providers` |
| `store.json: routes`                | `providers.provider_main.routes` (без изменений)         |
| `store.json: users[].username`      | удалено — это поле больше не поддерживается             |
| `store.json: users[].forceturn`     | удалено для типа `relay` (использовалось только для P2P) |
| `store.json: users[].uuid/allowed_routes/type/peers` | без изменений, переехали в `providers.provider_main.users` |

Откройте бэкапы и подготовьте новый файл по образцу из [шага 6](#6-создание-configjson):

1. Откройте `/opt/turnable/config.json.bak` — оттуда понадобятся `platform_id`, `call_id`, `pub_key`, `priv_key`, `relay.proto`, `relay.cloak`, `relay.public_ip`, `relay.port`.
2. Откройте `/opt/turnable/store.json.bak` — оттуда без изменений копируется массив `routes`, а из массива `users` копируется каждый объект **без полей `username` и `forceturn`** (остальные поля — `uuid`, `allowed_routes`, `type`, `peers` — оставляются как есть).
3. Соберите итоговый файл по этому шаблону (пример с одним маршрутом WireGuard и одним пользователем — допишите свои реальные маршруты и пользователей по той же схеме):

```bash
sudo -u turnable tee /opt/turnable/config.json > /dev/null <<EOF
{
    "servers": {
        "main": {
            "type": "relay",
            "platform_id": "ВАШ_СТАРЫЙ_platform_id",
            "call_id": "ВАШ_СТАРЫЙ_call_id",
            "pub_key": "ВАШ_СТАРЫЙ_pub_key",
            "priv_key": "ВАШ_СТАРЫЙ_priv_key",
            "proto": "ВАШ_СТАРЫЙ_relay.proto",
            "cloak": "ВАШ_СТАРЫЙ_relay.cloak",
            "listen_addr": "0.0.0.0:ВАШ_СТАРЫЙ_relay.port",
            "public_ip": "ВАШ_СТАРЫЙ_relay.public_ip",
            "provider": "provider_main"
        }
    },
    "providers": {
        "provider_main": {
            "type": "raw",
            "routes": [
                {
                    "id": "wireguard",
                    "address": "127.0.0.1",
                    "port": 51820,
                    "socket": "udp",
                    "transport": "none",
                    "encryption": "handshake",
                    "name": "WireGuard"
                }
            ],
            "users": [
                {
                    "uuid": "ВАШ_СТАРЫЙ_UUID",
                    "allowed_routes": ["wireguard"],
                    "type": "relay",
                    "peers": 10
                }
            ]
        }
    }
}
EOF
```

Затем удалите больше не нужный `store.json`:

```bash
sudo rm /opt/turnable/store.json
```

### 4. Запустите сервис и проверьте статус

```bash
sudo systemctl daemon-reload
sudo systemctl start turnable
sudo systemctl status turnable
```

Если конфиг прежний (тот же `pub_key`/`priv_key`/`call_id`), активные на старом сервере пользователи продолжат быть валидными — менять UUID не нужно.

### 5. Сгенерируйте новую ссылку

Старые ссылки `turnable://...`, выданные сервером 0.4.x, **не работают** с новым сервером и новым клиентом. Повторите [шаг 7](#7-генерация-ссылки-для-приложения) с тем же UUID пользователя:

```bash
sudo -u turnable /opt/turnable/turnable config generate main "ВАШ_СТАРЫЙ_UUID" wireguard vless
```

### 6. Обновите профиль в WireTurn

Откройте профиль с Turnable-ядром → кнопка редактирования → меню «Импорт» → вставьте новую ссылку. Все поля обновятся автоматически, конфиг Xray менять не нужно.
