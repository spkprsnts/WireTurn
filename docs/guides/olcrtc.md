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

## 2а. Обновление olcRTC

Если сервер уже установлен и нужно обновить его до свежей версии:

```bash
cd /opt/olcrtc
sudo git pull --recurse-submodules
sudo GOFLAGS="-buildvcs=false" /usr/local/go/bin/go run github.com/magefile/mage@latest buildCLI
sudo chown -R olcrtc:olcrtc /opt/olcrtc
sudo chmod +x /opt/olcrtc/build/olcrtc-linux-amd64
sudo mv /opt/olcrtc/build/olcrtc-linux-amd64 /opt/olcrtc/build/olcrtc
```

После пересборки перезапустите активные службы:

```bash
sudo systemctl restart olcrtc-wbstream
sudo systemctl restart olcrtc-telemost
```

### Миграция со старой установки

Если ранее служба была настроена по старой инструкции (называлась просто `olcrtc`), удалите её перед тем как создавать новые:

```bash
sudo systemctl disable --now olcrtc
sudo rm /etc/systemd/system/olcrtc.service
sudo systemctl daemon-reload
```

После этого создайте службы `olcrtc-wbstream` и/или `olcrtc-telemost` по разделу 4.

---

## 3. Получение параметров конфигурации

Сохраните все значения — они понадобятся в следующих шагах.

### 3.1 Ключ авторизации

```bash
openssl rand -hex 32
```

---

### 3.2 ID комнаты WB Stream

> **Внимание:** WB Stream ограничил создание гостевых комнат и подключение к ним — поддержка этой платформы в olcRTC может быть прекращена. Команда генерации ID комнаты ниже может не сработать. Если не сработает — пропустите разделы 3.2, 4.1 и 5.1 и настройте только Telemost.

```bash
cd /opt/olcrtc
sudo -u olcrtc ./build/olcrtc -mode gen -carrier wbstream -dns 1.1.1.1:53 -amount 1 -data data
```

В выводе будет UUID вида `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` — это ваш ID комнаты WB Stream.

---

### 3.3 ID комнаты Telemost

ID комнаты создаётся вручную через веб-интерфейс:

1. Перейдите на [telemost.yandex.ru](https://telemost.yandex.ru/) и войдите в аккаунт Яндекса.
2. Нажмите **«Новая видеовстреча»**.
3. В адресной строке появится ссылка вида `https://telemost.yandex.ru/j/495XXXXXXXXX`.
4. Цифры после `/j/` — это ваш **ID комнаты Telemost**.

---

## 4. Настройка автозапуска

Можно настроить **одну службу** (WB Stream **или** Telemost) или **обе сразу** — они работают независимо и не мешают друг другу. Если одна платформа недоступна, вторая продолжает работать.

**client-id** — придумайте любой логин без пробелов (например, `wireturn`). Для каждой службы можно использовать один и тот же client-id или разные — как удобнее.

---

### 4.1 Служба WB Stream

```bash
sudo tee /etc/systemd/system/olcrtc-wbstream.service > /dev/null <<EOF
[Unit]
Description=olcRTC Server (WB Stream)
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
  -id "ВАШ_UUID_КОМНАТЫ_WBSTREAM" \
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
sudo systemctl enable --now olcrtc-wbstream
```

Проверьте статус — должно быть `active (running)`:
```bash
sudo systemctl status olcrtc-wbstream
```

---

### 4.2 Служба Telemost

```bash
sudo tee /etc/systemd/system/olcrtc-telemost.service > /dev/null <<EOF
[Unit]
Description=olcRTC Server (Telemost)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=olcrtc
Group=olcrtc
WorkingDirectory=/opt/olcrtc
ExecStart=/opt/olcrtc/build/olcrtc \
  -mode srv \
  -carrier telemost \
  -transport vp8channel \
  -vp8-fps 60 \
  -vp8-batch 64 \
  -id "ВАШ_ID_КОМНАТЫ_TELEMOST" \
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
sudo systemctl enable --now olcrtc-telemost
```

Проверьте статус — должно быть `active (running)`:
```bash
sudo systemctl status olcrtc-telemost
```

Для выхода нажмите `q`. Если статус `failed` — проверьте правильность введённых значений и смотрите логи:
```bash
sudo journalctl -u olcrtc-wbstream -n 50
sudo journalctl -u olcrtc-telemost -n 50
```

---

### Управление службами

| Действие | WB Stream | Telemost |
|---|---|---|
| Запустить | `sudo systemctl start olcrtc-wbstream` | `sudo systemctl start olcrtc-telemost` |
| Остановить | `sudo systemctl stop olcrtc-wbstream` | `sudo systemctl stop olcrtc-telemost` |
| Перезапустить | `sudo systemctl restart olcrtc-wbstream` | `sudo systemctl restart olcrtc-telemost` |
| Статус | `sudo systemctl status olcrtc-wbstream` | `sudo systemctl status olcrtc-telemost` |

---

## 5. Настройка в WireTurn

Можно настроить один профиль (WB Stream **или** Telemost) или оба — для каждой платформы создаётся отдельный профиль.

1. **Создать профиль:** Нажмите на блок профиля → `+` → «Создать профиль». Откроется экран создания: введите название и в группе **«Ручная настройка»** выберите **olcRTC**.

2. **Настройка клиента:** Заполните параметры профиля (см. разделы 5.1–5.2) и нажмите **«Далее»**.

3. **Настройка Xray (опционально):** Откроется экран Xray — здесь доступен только **VLESS**. Xray используется для работы через VPN. Настройка VLESS нужна для режима **Dual-route** — автоматического переключения между VLESS и туннелем olcRTC при блокировках. Для импорта конфига используйте кнопки в верхней панели: QR-код или иконку импорта («Из буфера» / «Из файла»). Без настройки VLESS Xray тоже работает.

4. **Запуск:** Нажмите на профиль, чтобы выбрать его. Включите **Xray** при необходимости, включите **VPN** для пропуска всего трафика устройства через туннель. Нажмите центральную кнопку.

> **Редактирование:** Чтобы изменить настройки клиента — нажмите кнопку редактирования в блоке профиля. Чтобы изменить конфиг Xray — нажмите на блок Xray.

---

### 5.1 Профиль для WB Stream

Платформа **WB Stream**, транспорт **DataChannel**. Введите ID комнаты WB Stream, ID клиента и ключ авторизации из шагов 3 и 4.

---

### 5.2 Профиль для Telemost

Платформа **Telemost**, транспорт **VP8Channel**. Введите ID комнаты Telemost, ID клиента и ключ авторизации из шагов 3 и 4.

Дополнительно проверьте, что настройки соответствуют параметрам запуска службы:
- **VP8 FPS** — должно быть `60`
- **VP8 Batch** — должно быть `64`

---

**Готово!**
