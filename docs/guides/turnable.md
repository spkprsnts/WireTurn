# Гайд по запуску и настройке сервера Turnable

В этом руководстве описан процесс установки сервера Turnable на Ubuntu.

> **Обратите внимание:** Это упрощенная версия руководства. Оригинальную и подробную документацию со всеми техническими деталями вы можете найти в [репозитории Turnable](https://github.com/TheAirBlow/Turnable/blob/main/README_RU.md).

---

## 1. Требования

*   **ОС:** Ubuntu Server 24.04 (рекомендовано).
*   **VPS:** Наличие установленных протоколов WireGuard или VLESS. Для удобного управления ими можно использовать панель 3x-ui.
*   **Важно:** Сервер Turnable должен быть установлен на том же VPS, где запущены ваши WireGuard или VLESS.
*   **Доступ:** SSH-доступ к серверу с правами `sudo`.

---

## 2. Установка сервера

Обновите список пакетов и установите `wget`:
```bash
sudo apt update && sudo apt install wget -y
```

### Подготовка системы
Создадим специального пользователя и папку для сервера:

```bash
sudo adduser --system --group --no-create-home --shell /usr/sbin/nologin turnable
sudo mkdir -p /opt/turnable
sudo chown turnable:turnable /opt/turnable
cd /opt/turnable
```

### Скачивание программы
Скачиваем версию 0.4.1 (совместима с клиентом):

```bash
sudo -u turnable wget https://github.com/TheAirBlow/Turnable/releases/download/0.4.1/turnable-linux-amd64
sudo -u turnable chmod +x turnable-linux-amd64
sudo -u turnable mv turnable-linux-amd64 turnable
```

---

## 3. Настройка конфигурации

### Генерация ключей
Turnable шифрует трафик. Сгенерируйте свои уникальные ключи:

```bash
./turnable config keygen
```
Вы увидите строки `priv_key` и `pub_key`. **Скопируйте их в блокнот на компьютере**, они сейчас понадобятся.

### Создание файла config.json
Скопируйте этот блок текста целиком, **замените значения (начинающиеся с ВСТАВЬТЕ_)** на свои и вставьте в терминал (нажмите Enter):

```bash
sudo -u turnable cat <<EOF > /opt/turnable/config.json
{
    "platform_id": "vk.com",
    "call_id": "ВСТАВЬТЕ_ID_ЗВОНКА_VK",
    "priv_key": "ВСТАВЬТЕ_ВАШ_PRIV_KEY",
    "pub_key": "ВСТАВЬТЕ_ВАШ_PUB_KEY",
    "relay": {
        "enabled": true,
        "proto": "srtp",
        "cloak": "none",
        "public_ip": "ВСТАВЬТЕ_IP_СЕРВЕРА",
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

> **Где взять Call ID?**
> Зайдите на [vk.com/calls](https://vk.com/calls) с компьютера. Создайте звонок. Ссылка будет вида `https://vk.com/call/join/ABC123xyz...`. Вам нужно только то, что после `/join/` (например: `ABC123xyz...`).

### Создание файла store.json
Здесь мы указываем настройки ваших VPN. Скопируйте и вставьте в терминал (не забудьте поменять порты на свои):

```bash
sudo -u turnable cat <<EOF > /opt/turnable/store.json
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
            "username": "Валерий Жмышенко",
            "type": "relay",
            "peers": 10
        }
    ]
}
EOF
```
*   **UUID:** Это уникальный идентификатор пользователя. Его **обязательно** нужно сгенерировать на сайте [uuidgenerator.net](https://www.uuidgenerator.net/version4) и вставить в конфиг. Не пишите туда случайные буквы!

---

## 4. Ссылка для приложения

Теперь создадим ссылку, которую нужно будет вставить в WireTurn:

```bash
./turnable config generate "ВАШ_UUID_ИЗ_ШАГА_ВЫШЕ" wireguard vless
```
Скопируйте полученную ссылку `turnable://...`.

---

## 5. Автозапуск (чтобы сервер работал всегда)

Создаем файл службы одной командой:

```bash
sudo cat <<EOF > /etc/systemd/system/turnable.service
[Unit]
Description=Turnable Server
After=network.target

[Service]
Type=simple
User=turnable
Group=turnable
WorkingDirectory=/opt/turnable
ExecStart=/opt/turnable/turnable server
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
```

Запускаем сервер:
```bash
sudo systemctl daemon-reload
sudo systemctl enable turnable
sudo systemctl start turnable
```

Проверить, что всё работает, можно командой:
```bash
sudo systemctl status turnable
```
Если в выводе написано `active (running)`, значит сервер успешно запущен. Для выхода из режима просмотра нажмите клавишу **q** (убедитесь, что включена английская раскладка).

---

## 6. Настройка в WireTurn

1.  **Профиль:** В приложении нажмите на блок профиля -> `+` -> `Создать профиль`. После сохранения **нажмите на созданный профиль** в списке, чтобы выбрать его.
2.  **Клиент:** Выберите ядро **Turnable**. Скопируйте вашу ссылку `turnable://` (из шага 4) и в поле «Импорт» нажмите **«Из буфера»**.
3.  **Маршрут:** После импорта внизу появится блок с настройками — выберите нужный маршрут (WireGuard или VLESS).
4.  **Xray:** Импортируйте основной конфиг вашего VPN (файл, ссылка или QR-код из панели управления).
5.  **Запуск:** На главном экране включите переключатель **Xray**. Если планируете использовать системный VPN, включите и его (режим VPN зависит от Xray). Нажмите центральную кнопку.

### Совет по использованию
Вы можете создать второй профиль для другого протокола (например, если первый был для VLESS, а второй нужен для WireGuard), просто **склонировав** текущий профиль. Для этого нажмите на блок профилей, нажмите на три точки у нужного профиля и выберите «Клонировать». Затем в новом профиле просто выберите другой «Маршрут» и импортируйте соответствующий конфиг во вкладке Xray.

**Готово!**