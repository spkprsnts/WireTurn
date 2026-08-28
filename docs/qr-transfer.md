# Многокадровый QR для длинных конфигов (формат `WTMQ1`)

Этот документ для разработчиков панелей, которые хотят сами рисовать/сканировать QR-коды с конфигами WireTurn (профиль, `wireturn://`-ссылка, ссылка ядра и т.д.), не полагаясь на встроенный экран приложения.

## Проблема

Некоторые ссылки на точку входа (в первую очередь `turnable://` с крупным `pub_key`, см. [схему ядра](subscriptions.md#21-turnable)) занимают 1.5–2+ КБ. Технически это влезает в один QR (byte-mode вмещает почти 3 КБ), но плотность модулей на таком коде становится настолько высокой, что обычная камера телефона его надёжно не сканирует — часть данных считывается с ошибками или скан обрывается.

WireTurn решает это без fountain-кодов и прочих тяжёлых схем: если строка длиннее безопасного порога, приложение показывает не один плотный QR, а зацикленную анимацию из нескольких простых кадров. Короткие конфиги (подавляющее большинство) по-прежнему показываются одним статичным QR — никакого изменения формата для них нет.

## Формат кадра

Каждый кадр — это обычная строка, которую можно закодировать в QR любой библиотекой. Если строка **не** начинается с префикса ниже — это обычный, самодостаточный QR (как раньше): просто используйте его целиком.

```
WTMQ1|<sessionId>|<index>|<total>|<payload>
```

| Поле | Описание |
| :--- | :--- |
| `WTMQ1` | Фиксированный префикс формата. |
| `sessionId` | Произвольный токен, общий для всех кадров одного набора. Нужен только чтобы отличить кадры текущей передачи от кадров предыдущей/чужой — **его конкретное значение не имеет значения для получателя**, сравнивается только на равенство. WireTurn использует 4 hex-символа, но это не требование протокола — генерируйте как удобно (например `Math.random()`). |
| `index` | Номер кадра, отсчёт с **1**. |
| `total` | Общее число кадров в наборе. |
| `payload` | Часть исходной строки. Конкатенация `payload` кадров `1..total` по порядку восстанавливает исходную строку **побайтово, без разделителей между кусками**. |

Разделитель полей — `|`. `payload` может сам содержать `|` (например, уже percent-encoded query-параметры) — это безопасно, если при разборе ограничивать `split` четырьмя разделителями и брать всё, что осталось, как `payload` целиком (см. пример ниже).

### Что использует приложение по умолчанию

Это не часть протокола (получатель не обязан этого повторять), но для справки — параметры, которые сейчас использует WireTurn при генерации:

- Порог переключения на анимацию: **700 символов**.
- Размер куска: **400 символов** payload на кадр.
- Интервал смены кадра: **450 мс**.
- Уровень коррекции ошибок QR: не задаётся явно (библиотека выбирает по умолчанию) — это никак не важно для чтения: уровень коррекции закодирован в самом QR, любой стандартный сканер прочитает его автоматически независимо от того, какой уровень выбрал генератор.

## Сборка на стороне читающего

1. Сканировать QR как обычно (любой видеопоток + любой QR-декодер).
2. Если результат не начинается с `WTMQ1|` — это готовое значение, дальше ничего делать не нужно.
3. Иначе — разобрать `sessionId`/`index`/`total`/`payload`, копить `payload` по `index` в карте (Map/dict).
   - Если пришёл кадр с другим `sessionId`, чем уже накопленный — это новый набор (старый брошен на середине или это вообще не относящийся набор): очистить накопленное и начать заново с этого `sessionId`.
   - Дубликаты (один и тот же `index` пришёл повторно, т.к. анимация зациклена) — просто перезаписывают то же значение, порядок и повторы не важны.
4. Как только собраны все индексы `1..total` — склеить `payload` по порядку индексов и это готовый результат.

Никакого тайм-аута/quorum не нужно: анимация на стороне генератора крутится, пока диалог открыт, так что рано или поздно попадут все кадры, даже если часть сканов не удалась.

## Пример на React

Ниже — минимальные компоненты-генератор и сканер. Генератор использует [`qrcode.react`](https://www.npmjs.com/package/qrcode.react), сканер — [`@zxing/browser`](https://www.npmjs.com/package/@zxing/browser) (можно заменить на `html5-qrcode` или любую другую библиотеку — формат кадра от этого не зависит).

```bash
npm install qrcode.react @zxing/browser
```

### Генератор

```jsx
// MultiFrameQr.jsx
import { useEffect, useMemo, useState } from "react";
import { QRCodeSVG } from "qrcode.react";

const CHUNK_PREFIX = "WTMQ1";
const SINGLE_FRAME_MAX_LENGTH = 700;
const CHUNK_PAYLOAD_SIZE = 400;
const FRAME_INTERVAL_MS = 450;

function randomSessionId() {
  return Math.floor(Math.random() * 0x10000)
    .toString(16)
    .padStart(4, "0");
}

function buildFrames(text) {
  if (text.length <= SINGLE_FRAME_MAX_LENGTH) return [text];

  const chunks = [];
  for (let i = 0; i < text.length; i += CHUNK_PAYLOAD_SIZE) {
    chunks.push(text.slice(i, i + CHUNK_PAYLOAD_SIZE));
  }

  const sessionId = randomSessionId();
  const total = chunks.length;
  return chunks.map(
    (chunk, i) => `${CHUNK_PREFIX}|${sessionId}|${i + 1}|${total}|${chunk}`
  );
}

export function MultiFrameQr({ text, size = 260 }) {
  const frames = useMemo(() => buildFrames(text), [text]);
  const [frameIndex, setFrameIndex] = useState(0);

  useEffect(() => {
    setFrameIndex(0);
    if (frames.length <= 1) return undefined;

    const id = setInterval(() => {
      setFrameIndex((i) => (i + 1) % frames.length);
    }, FRAME_INTERVAL_MS);
    return () => clearInterval(id);
  }, [frames]);

  return (
    <div style={{ textAlign: "center" }}>
      <QRCodeSVG value={frames[frameIndex]} size={size} level="L" />
      {frames.length > 1 && (
        <p>
          Кадр {frameIndex + 1} из {frames.length}
        </p>
      )}
    </div>
  );
}
```

### Сканер

```jsx
// MultiFrameQrScanner.jsx
import { useEffect, useRef, useState } from "react";
import { BrowserQRCodeReader } from "@zxing/browser";

const CHUNK_PREFIX = "WTMQ1";

function parseChunk(raw) {
  if (!raw.startsWith(`${CHUNK_PREFIX}|`)) return null;

  const parts = raw.split("|");
  if (parts.length < 5) return null;

  const [, sessionId, indexStr, totalStr, ...rest] = parts;
  const index = Number(indexStr);
  const total = Number(totalStr);
  if (!Number.isInteger(index) || !Number.isInteger(total)) return null;
  if (index < 1 || index > total) return null;

  // payload мог сам содержать "|" — склеиваем обратно всё, что осталось
  return { sessionId, index, total, payload: rest.join("|") };
}

export function MultiFrameQrScanner({ onResult }) {
  const videoRef = useRef(null);
  const [progress, setProgress] = useState(null); // { collected, total } | null

  useEffect(() => {
    const reader = new BrowserQRCodeReader();
    const parts = new Map();
    let activeSessionId = null;
    let activeTotal = 0;
    let done = false;

    const controlsPromise = reader.decodeFromVideoDevice(
      undefined, // undefined = камера по умолчанию; можно передать deviceId
      videoRef.current,
      (result, _error, controls) => {
        if (done || !result) return;
        const chunk = parseChunk(result.getText());

        if (!chunk) {
          done = true;
          controls.stop();
          onResult(result.getText());
          return;
        }

        if (activeSessionId !== chunk.sessionId) {
          activeSessionId = chunk.sessionId;
          activeTotal = chunk.total;
          parts.clear();
        }
        parts.set(chunk.index, chunk.payload);
        setProgress({ collected: parts.size, total: activeTotal });

        if (parts.size >= activeTotal) {
          done = true;
          controls.stop();
          let assembled = "";
          for (let i = 1; i <= activeTotal; i += 1) {
            assembled += parts.get(i) ?? "";
          }
          onResult(assembled);
        }
      }
    );

    return () => {
      done = true;
      controlsPromise.then((controls) => controls.stop()).catch(() => {});
    };
  }, [onResult]);

  return (
    <div>
      <video ref={videoRef} style={{ width: "100%", maxWidth: 320 }} muted />
      {progress && (
        <p>
          Получено {progress.collected} из {progress.total} частей
        </p>
      )}
    </div>
  );
}
```

> API `@zxing/browser` может немного отличаться между версиями пакета — сверяйтесь с его актуальной документацией, если сигнатура `decodeFromVideoDevice` не совпадёт.

## Ручной откат к одному QR

В диалоге экспорта WireTurn рядом с анимацией есть свич «Один QR-код»: он форсирует один статичный QR с полным текстом вместо чанкинга, даже если строка длинная. Он нужен для случаев, когда сканировать будет не WireTurn, а стороннее приложение, не понимающее формат `WTMQ1` — тогда важнее совместимость с обычным одиночным QR, чем надёжность скана. Если реализуете свою панель — стоит предусмотреть такой же явный откат, а не полагаться только на автоматический чанкинг.

## Совместимость

- Формат кадра одинаковый независимо от того, что именно передаётся: `wireturn://`-ссылка (§4 [спецификации подписок](subscriptions.md)), сырая ссылка ядра (`turnable://`, `olcrtc://`, ...) или что-либо ещё — с точки зрения этого формата это просто произвольная строка.
- Сканер WireTurn (и, соответственно, ваша реализация) должен без проблем читать обычные одиночные QR от других приложений — они не начинаются с `WTMQ1|`, значит обрабатываются как готовый результат сразу.
- Если вы генерируете конфиги только для собственной панели и знаете, что они всегда короткие — можно вообще не реализовывать эту часть и всегда рисовать один статичный QR: WireTurn-сканер прекрасно понимает и одиночные коды.
