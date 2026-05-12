# Гайд по запуску и настройке сервера olcRTC

В этом руководстве описан процесс установки сервера olcRTC на примере платформы **WB Stream**. Инструкция ориентирована на Ubuntu 24.04.

> **Для продвинутых пользователей:** Если вы хотите использовать другие транспорты или платформы, изучите [настройки запуска](https://github.com/openlibrecommunity/olcrtc/blob/master/docs/settings.md) или попробуйте [автоматический скрипт](https://github.com/openlibrecommunity/olcrtc/blob/master/docs/fast.md).

---

## 1. Подготовка сервера (VPS)

### Требования
*   **ОС:** Ubuntu Server 24.04.
*   **Доступ:** SSH с правами `sudo`.

Обновите систему и установите Git и Wget:
```bash
sudo apt update && sudo apt install git wget -y
```

### Установка Go и Mage
Для сборки сервера нам понадобятся язык Go и инструмент Mage.

```bash
# Скачиваем и устанавливаем Go (версия 1.23.6)
wget https://go.dev/dl/go1.23.6.linux-amd64.tar.gz
sudo rm -rf /usr/local/go
sudo tar -C /usr/local -xzf go1.23.6.linux-amd64.tar.gz
echo 'export PATH=$PATH:/usr/local/go/bin' >> ~/.bashrc
source ~/.bashrc
rm go1.23.6.linux-amd64.tar.gz

# Устанавливаем Mage
go install github.com/magefile/mage@latest
echo 'export PATH="$HOME/go/bin:$PATH"' >> ~/.bashrc
echo 'export GOPATH=$HOME/go' >> ~/.bashrc
source ~/.bashrc
```

---

## 2. Установка и сборка olcRTC

Подготовим папку и пользователя для безопасности:

```bash
cd /opt
sudo adduser --system --group --no-create-home --shell /usr/sbin/nologin olcrtc

# Клонируем проект
sudo git clone https://github.com/openlibrecommunity/olcrtc --recurse-submodules
cd /opt/olcrtc

# Собираем сервер
sudo GOFLAGS="-buildvcs=false" /usr/local/go/bin/go run github.com/magefile/mage@latest buildCLI

# Настраиваем права и переименовываем бинарник
sudo chown -R olcrtc:olcrtc /opt/olcrtc
sudo chmod +x /opt/olcrtc/build/olcrtc-linux-amd64
sudo mv /opt/olcrtc/build/olcrtc-linux-amd64 /opt/olcrtc/build/olcrtc
```

---

## 3. Генерация данных для настройки

Для работы нам нужно создать ключ авторизации и комнату.

1.  **Генерируем ключ авторизации:**
    ```bash
    openssl rand -hex 32
    ```
    **Скопируйте и сохраните результат** (это будет ваш `Encryption key`).

2.  **Создаем комнату WB Stream:**
    ```bash
    cd /opt/olcrtc
    sudo -u olcrtc ./build/olcrtc -mode gen -carrier wbstream -dns 1.1.1.1:53 -amount 1 -data data
    ```
    В выводе вы увидите UUID комнаты. **Скопируйте и сохраните его** (это будет ваш `Room ID`).

---

## 4. Автозапуск сервера (Systemd)

Создаем файл службы, чтобы сервер работал постоянно. В блоке ниже **замените значения в скобках** на свои данные.

```bash
sudo cat <<EOF > /etc/systemd/system/olcrtc.service
[Unit]
Description=olcRTC Server
After=network.target

[Service]
Type=simple
User=olcrtc
Group=olcrtc
WorkingDirectory=/opt/olcrtc
ExecStart=/opt/olcrtc/build/olcrtc \\
  -mode srv \\
  -carrier wbstream \\
  -transport datachannel \\
  -id "ВАШ_UUID_КОМНАТЫ" \\
  -client-id "ПРИДУМАННЫЙ_АЙДИ_КЛИЕНТА" \\
  -key "ВАШ_КЛЮЧ_АВТОРИЗАЦИИ" \\
  -link direct \\
  -dns 1.1.1.1:53 \\
  -data data
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
```
*   **client-id:** Придумайте любой логин без пробелов (например, `wireturn`).

Запускаем:
```bash
sudo systemctl daemon-reload
sudo systemctl enable olcrtc
sudo systemctl start olcrtc
```

Проверить статус можно командой:
```bash
sudo systemctl status olcrtc
```
Если в выводе написано `active (running)`, значит сервер успешно запущен. Для выхода из режима просмотра нажмите клавишу **q** (убедитесь, что включена английская раскладка).

---

## 5. Настройка в WireTurn

1.  **Создайте профиль:** Нажмите на блок профиля -> `+` -> `Создать профиль`. После сохранения **выберите его**.
2.  **Вкладка «Клиент»:**
    *   **Ядро:** `olcRTC`.
    *   **Платформа (Carrier):** `WB Stream`.
    *   **Транспорт (Transport):** `DataChannel`.
3.  **Ввод данных:**
    *   В поле **ID комнаты** введите ваш `Room ID`.
    *   В поле **ID клиента** введите ваш `client-id` (который вы придумали для службы).
    *   В поле **Ключ авторизации** введите ваш `Encryption key`.

---

## 6. Запуск и принцип работы

1.  Включите переключатель **Xray** на главной вкладке.
2.  Включите **VPN** (этот режим работает только при включенном Xray).
3.  Нажмите кнопку запуска.

### Зачем нужен Xray?
Ядро **olcRTC** само по себе поднимает локальный **SOCKS5** прокси. Однако, чтобы пользоваться этим прокси было удобно, в WireTurn используется **Xray** как связующее звено (upstream). 

Это дает несколько преимуществ:
*   **Режим VPN:** Позволяет направлять трафик всего устройства через туннель olcRTC.
*   **Маршрутизация:** Вы можете настроить, какие приложения должны идти через туннель, а какие — напрямую.
*   **Dual-route:** Возможность автоматического переключения между прямым соединением и туннелем при проблемах со связью. Под «прямым соединением» понимается **VLESS**, который вы можете добавить во вкладке Xray. В этом режиме клиент будет использовать VLESS напрямую, когда это возможно, и автоматически переключится на туннель olcRTC, если обычное подключение будет заблокировано.

**Готово!**