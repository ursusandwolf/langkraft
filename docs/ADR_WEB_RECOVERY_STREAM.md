# ADR: потоковый Web recovery archive v2

Статус: принято, 2026-11-05.

## Проблема

Архив v1 — один JSON с base64 SQLite и всех audio chunks. `JSON.stringify` и лимит 64 МиБ на весь Blob отсекают библиотеки, у которых сырые байты ещё допустимы, но base64+JSON превышают потолок. Полная материализация архива в строке также раздувает heap.

## Решение

Формат `langkraft-web-recovery-v2`: NDJSON (application/x-ndjson).

1. Первая строка — метаданные: `format`, `audio` (без bytes), `report`.
2. Далее строки `{"t":"sqlite","data":"<base64>"}` — фрагменты VACUUM SQLite (сырой размер фрагмента ≤ 256 KiB).
3. Строки `{"t":"chunk","id","index","data"}` — audio chunks.
4. Завершение: `{"t":"end"}`.

Импорт читает `Blob.stream()` построчно, декодирует фрагменты без сборки одного гигантского JSON. Export собирает `Blob` из массива частей (без `JSON.stringify` всего архива).

v1 JSON остаётся поддерживаемым на импорте. Новый export пишет v2.

## Лимиты

| | v1 | v2 |
| --- | --- | --- |
| Archive Blob / file | ≤ 64 MiB | файл может быть больше; декодированные бинарные данные ≤ 512 MiB |
| Snapshot clone (IDB→память) | ≤ 64 MiB на binary set (REL-15) | без изменений в этом ADR |

Библиотеки с >64 MiB сырого audio при **clone** по-прежнему упираются в REL-15; отдельный потоковый clone IDB→IDB — следующий шаг backlog.

## Последствия

- Имя файла: `langkraft-recovery.jsonl`.
- Тесты: round-trip v2 + сохранение импорта v1.
- Не меняет lease/lifecycle протоколы профилей.
