# Установка сервера Turnable

Данное руководство поможет установить сервер Turnable на VPS с Ubuntu Server 24.04.

> Полная документация: [репозиторий Turnable](https://github.com/TheAirBlow/Turnable/blob/main/README_RU.md).
> Все команды выполняются в терминале после подключения к серверу по SSH.

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
sudo -u turnable wget https://github.com/TheAirBlow/Turnable/releases/download/0.4.1/turnable-linux-amd64
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

Замените плейсхолдеры на свои значения. Публичный IP сервера можно узнать командой `curl ifconfig.me`.

```bash
sudo -u turnable tee /opt/turnable/config.json > /dev/null <<EOF
{
    "platform_id": "vk.com",
    "call_id": "ВСТАВЬТЕ_ID_ЗВОНКА_VK",
    "priv_key": "ВСТАВЬТЕ_ВАШ_PRIV_KEY",
    "pub_key": "ВСТАВЬТЕ_ВАШ_PUB_KEY",
    "relay": {
        "enabled": true,
        "proto": "srtp",
        "cloak": "none",
        "public_ip": "ВСТАВЬТЕ_ПУБЛИЧНЫЙ_IP_СЕРВЕРА",
        "port": 56000
    },
    "p2p": {
        "enabled": false,
        "username": "...",
        "cloak": "none"
    },
    "provider": {
        "type": "json",
        "path": "store.json"
    }
}
EOF
```

---

## 7. Создание store.json

Перед выполнением команды сгенерируйте UUID на сайте [uuidgenerator.net](https://www.uuidgenerator.net/version4) и замените `ВСТАВЬТЕ_СГЕНЕРИРОВАННЫЙ_UUID`.

Порты WireGuard и VLESS нужно уточнить в панели **3x-ui** — они могут быть любыми. Откройте панель, найдите свои inbound-записи и посмотрите указанный там порт для каждого протокола. Подставьте эти значения в команду ниже.

```bash
sudo -u turnable tee /opt/turnable/store.json > /dev/null <<EOF
{
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
            "username": "Влад Урбан",
            "type": "relay",
            "peers": 10
        }
    ]
}
EOF
```

---

## 8. Генерация ссылки для приложения

Замените `ВАШ_UUID` на UUID из предыдущего шага:

```bash
sudo -u turnable /opt/turnable/turnable config generate "ВАШ_UUID" wireguard vless
```

Сохраните полученную ссылку `turnable://...` — она нужна при настройке WireTurn.

---

## 9. Настройка автозапуска

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

Для выхода нажмите `q`. Если статус `failed` — проверьте правильность данных в config.json и store.json, логи: `sudo journalctl -u turnable -n 50`

---

## 10. Настройка в WireTurn

1. **Профиль:** Блок профиля → `+` → «Создать профиль». Нажмите на профиль, чтобы выбрать его.

2. **Клиент:** Выберите ядро **Turnable**. Скопируйте ссылку `turnable://` из шага 8, затем нажмите иконку импорта в верхней панели (лист с плюсом) и выберите «Из буфера».

3. **Маршрут:** После импорта выберите нужный маршрут — **WireGuard** или **VLESS**.

4. **Xray:** Импортируйте основной VPN-конфиг (WireGuard или VLESS). Используйте кнопку QR-кода или иконку импорта (лист с плюсом, стоит правее) — «Из буфера» или «Из файла». Тип конфига определяется автоматически.

5. **Запуск:** Включите **Xray**, при необходимости включите **VPN** (весь трафик устройства через туннель). Нажмите центральную кнопку.

### Несколько профилей

Для использования нескольких протоколов клонируйте профиль: три точки у профиля → «Клонировать». В копии выберите другой маршрут и замените конфиг во вкладке Xray.

---

**Готово!**
