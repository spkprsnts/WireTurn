# Спецификация генерации профилей WireTurn

WireTurn поддерживает импорт профилей через JSON-файлы (одиночные или в ZIP-архиве). Для упрощения интеграции с внешними панелями и сервисами, приложение поддерживает «ленивую» инициализацию через URL-ссылки.

## Базовая структура JSON

При импорте достаточно указать имя и одну из ссылок на конфигурацию ядра. Остальные поля (ID, настройки по умолчанию) приложение заполнит автоматически.

```json
{
  "name": "Название профиля",
  "turnableUrl": "turnable://...",
  "xrayEnabled": true,
  "xrayProtocol": "VLESS",
  "vlessConfig": {
    "vlessLink": "vless://..."
  }
}
```

---

## Конфигурация ядра (Kernel)

В профиле может быть активна только одна конфигурация ядра. Вы можете использовать одно из следующих полей:

### 1. turnableUrl
*   **Формат:** `turnable://[user_uuid]:[call_id]@[platform_id]/[route1_id]/[route2_id]?pub_key=[key]&selected_route_id=[route_id]&socket[1]=[tcp/udp]&transport[1]=[kcp/...]#[route1_name],[route2_name]`
*   **Пример:** `turnable://uuid:call@vk.com/vless/wg?pub_key=abc&selected_route_id=vless&socket[1]=tcp&transport[1]=kcp&socket[2]=udp#VLESS,Wireguard`

### 2. olcrtcUrl
*   **Формат:** `olcrtc://[provider]?[transport]<[params]>@[id]#[key]$[mimo]`
*   **Пример:** `olcrtc://wbstream?vp8channel<vp8-fps=60&vp8-batch=64>@room123#abc$user_1`

### 3. webdavUrl
*   **Формат:** `webdavs://[user]:[pass]@[host]:[port]?timeout=[60s]&poll-min=[200ms]&poll-max=[500ms]#[profile_name]`
*   **Пример:** `webdavs://admin:password@webdav.example.com?timeout=30s&poll-min=100ms&poll-max=300ms#MyDisk`

---

### Поля для управления Xray:

| Поле | Тип | Описание |
| :--- | :--- | :--- |
| `xrayEnabled` | Boolean | `true` — включить Xray, `false` — только ядро. |
| `xrayProtocol` | String | Протокол: `"VLESS"` или `"WIREGUARD"`. |

### VLESS (vlessConfig)
Используется для протокола VLESS (Reality, gRPC и др.).

| Поле | Тип | Описание |
| :--- | :--- | :--- |
| `vlessLink` | String | Стандартная ссылка `vless://...`. При использовании с **Turnable** адрес и порт в ссылке игнорируются (заменяются на локальные). |
| `isDualRoute` | Boolean | `true` — включить раздельное туннелирование (Dual Route). |
| `directAddress` | String | Адрес для прямого подключения в режиме Dual Route (`host:port`). |
| `hcInterval` | String | Интервал проверки доступности (Health Check) в секундах. По умолчанию `"30"`. |
| `mux` | String | Количество потоков Mux. `"0"` — выключено. |

### WireGuard (wgConfig)
*Важно: Используется **исключительно** совместно с ядром **Turnable**.*

| Поле | Тип | Описание |
| :--- | :--- | :--- |
| `privateKey` | String | Приватный ключ интерфейса. |
| `publicKey` | String | Публичный ключ пира. |
| `address` | String | IP-адрес интерфейса (например, `10.0.0.2/32`). |
| `mtu` | String | MTU (например, `"1280"`). |
| `endpoint` | String | Адрес эндпоинта. **Всегда** заменяется на локальный адрес ядра Turnable. |
| `persistentKeepalive`| String | Интервал поддержки соединения в секундах. |

---

## Примеры готовых профилей

### Минимальный Turnable (только точка входа)
```json
{
  "name": "Turnable Entry Point",
  "turnableUrl": "turnable://v1/vk.com/direct/proxy?selected_route_id=proxy#Direct,Proxy",
  "xrayEnabled": false
}
```

### Turnable + VLESS (Dual Route)
```json
{
  "name": "Turnable Dual Route",
  "turnableUrl": "turnable://v1/vk.com/direct/proxy?selected_route_id=proxy#Direct,Proxy",
  "xrayEnabled": true,
  "xrayProtocol": "VLESS",
  "vlessConfig": {
    "vlessLink": "vless://uuid@host:port?security=reality&...",
    "isDualRoute": true
  }
}
```

### olcRTC (SOCKS5 Only)
```json
{
  "name": "olcRTC SOCKS5",
  "olcrtcUrl": "olcrtc://wbstream?vp8channel<vp8-fps=60&vp8-batch=64>@room123#abc$user_1",
  "xrayEnabled": false
}
```

### olcRTC + VLESS (Dual Route)
```json
{
  "name": "olcRTC Dual Route",
  "olcrtcUrl": "olcrtc://wbstream?vp8channel<vp8-fps=60&vp8-batch=64>@room123#abc$user_1",
  "xrayEnabled": true,
  "xrayProtocol": "VLESS",
  "vlessConfig": {
    "vlessLink": "vless://uuid@host:port?security=reality&...",
    "isDualRoute": true
  }
}
```

---

## Рекомендации для разработчиков панелей

1.  **Имя файла:** Рекомендуется называть файлы с префиксом `wt_`, например `wt_my_config.json`.
2.  **ZIP-архивы:** Приложение может импортировать `.zip` файл, содержащий множество `.json` профилей. Это удобно для массовой раздачи конфигураций.
3.  **Авто-инициализация:** При первом импорте (если список профилей пуст), WireTurn автоматически выберет первый импортированный профиль как активный.
4.  **Валидация:** Если в JSON указаны и структурированные данные, и URL-ссылка (например, `turnableUrl`), приоритет всегда отдается **ссылке**.
