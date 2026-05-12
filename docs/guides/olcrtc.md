# Установка сервера olcRTC

Данное руководство поможет установить сервер olcRTC на VPS с Ubuntu Server 24.04.

> Для продвинутых пользователей: [настройки запуска](https://github.com/openlibrecommunity/olcrtc/blob/master/docs/settings.md) · [автоматический скрипт установки](https://github.com/openlibrecommunity/olcrtc/blob/master/docs/fast.md) · [документация](https://github.com/openlibrecommunity/olcrtc/blob/master/readme.md)

> Все команды выполняются в терминале после подключения к серверу по SSH.

---

## 1. Подготовка системы

```bash
sudo apt update && sudo apt upgrade -y && sudo apt install git wget -y
```

### Установка Go

```bash
wget https://go.dev/dl/go1.26.2.linux-amd64.tar.gz
sudo rm -rf /usr/local/go
sudo tar -C /usr/local -xzf go1.26.2.linux-amd64.tar.gz
echo 'export PATH=$PATH:/usr/local/go/bin' >> ~/.bashrc
source ~/.bashrc
rm go1.26.2.linux-amd64.tar.gz
```

Проверьте установку — должна появиться строка с версией:
```bash
go version
```

### Установка Mage

```bash
go install github.com/magefile/mage@latest
echo 'export GOPATH=$HOME/go' >> ~/.bashrc
echo 'export PATH="$HOME/go/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

---

## 2. Установка olcRTC

```bash
sudo adduser --system --group --no-create-home --shell /usr/sbin/nologin olcrtc
cd /opt
sudo git clone https://github.com/openlibrecommunity/olcrtc --recurse-submodules
cd /opt/olcrtc
sudo GOFLAGS="-buildvcs=false" /usr/local/go/bin/go run github.com/magefile/mage@latest buildCLI
sudo chown -R olcrtc:olcrtc /opt/olcrtc
sudo chmod +x /opt/olcrtc/build/olcrtc-linux-amd64
sudo mv /opt/olcrtc/build/olcrtc-linux-amd64 /opt/olcrtc/build/olcrtc
```

> Сборка может занять несколько минут.

---

## 3. Получение параметров конфигурации

Сохраните оба значения — они понадобятся в следующих шагах.

**Ключ авторизации:**
```bash
openssl rand -hex 32
```

**ID комнаты WB Stream:**
```bash
cd /opt/olcrtc
sudo -u olcrtc ./build/olcrtc -mode gen -carrier wbstream -dns 1.1.1.1:53 -amount 1 -data data
```

В выводе будет UUID вида `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` — это ваш ID комнаты.

---

## 4. Настройка автозапуска

Замените плейсхолдеры на свои значения и выполните команду.

**client-id** — придумайте любой логин без пробелов (например, `wireturn`). Сохраните его.

```bash
sudo tee /etc/systemd/system/olcrtc.service > /dev/null <<EOF
[Unit]
Description=olcRTC Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=olcrtc
Group=olcrtc
WorkingDirectory=/opt/olcrtc
ExecStart=/opt/olcrtc/build/olcrtc \
  -mode srv \
  -carrier wbstream \
  -transport datachannel \
  -id "ВАШ_UUID_КОМНАТЫ" \
  -client-id "ВАШ_ID_КЛИЕНТА" \
  -key "ВАШ_КЛЮЧ_АВТОРИЗАЦИИ" \
  -link direct \
  -dns 1.1.1.1:53 \
  -data data
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now olcrtc
```

Проверьте статус — должно быть `active (running)`:
```bash
sudo systemctl status olcrtc
```

Для выхода нажмите `q`. Если статус `failed` — проверьте правильность введённых значений и смотрите логи: `sudo journalctl -u olcrtc -n 50`

---

## 5. Настройка в WireTurn

1. **Профиль:** Блок профиля → `+` → «Создать профиль». Нажмите на профиль, чтобы выбрать его.

2. **Клиент:** Ядро **olcRTC**, платформа **WB Stream**, транспорт **DataChannel**. Введите ID комнаты, ID клиента и ключ авторизации из шага 3 и 4.

3. **Xray (опционально):** Нужен только для режима **Dual-route**. Для импорта VLESS-конфига используйте одну из кнопок в верхней панели: кнопку QR-кода или иконку импорта (лист с плюсом, стоит правее) — она откроет выбор «Из буфера» или «Из файла». Это позволит автоматически переключаться между VLESS и туннелем olcRTC при блокировках.

4. **Запуск:** Включите **Xray**, при необходимости включите **VPN** (весь трафик устройства через туннель). Нажмите центральную кнопку.

---

**Готово!**
