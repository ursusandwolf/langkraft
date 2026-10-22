# Проверка интеграции — 22 октября 2026

REL-S2 и REL-S3 завершены: [PR #5](https://github.com/ursusandwolf/langkraft/pull/5) и [PR #6](https://github.com/ursusandwolf/langkraft/pull/6) включены в dev; merge REL-S3 — `63a6a94`, его дерево совпало с проверенным `be6b1a2`. [CI REL-S3](https://github.com/ursusandwolf/langkraft/actions/runs/39777909189) успешен: JVM, 31 Kotlin/Chromium-тест, storage/recovery и полный development/production UI. Выпуск объединяет оба спринта через обычный merge dev → main. Критерий завершения синхронизации: release CI успешен, деревья main/dev равны, dev fast-forward обновлён до release merge; слитые sprint-ветки удаляются после проверки ancestor. История не переписывается. Актуальное состояние веток проверяется через git fetch, а приведённые ниже хеши относятся к своим историческим этапам.

## Исторический срез после REL-S2

Актуальная проверка веток после REL-S2: обе исходные истории (`dev 3003225` и `main 945ab35`) входят в main. До нового спринта main/dev совпадали на b44ab98. REL-S2 слит в dev через [PR #5](https://github.com/ursusandwolf/langkraft/pull/5), merge 384a30f; [CI 39775953381](https://github.com/ursusandwolf/langkraft/actions/runs/39775953381) полностью успешен. Git merge-tree не выявил конфликтов, дерево merge совпало с протестированным 55941bd, девять коммитов сохранены. Main остаётся на b44ab98 до следующего выпуска; это нормальное отставание стабильной ветки. Для LK-04 выделена sprint/rel-s3-multitab; слитая REL-S2 ветка удалена локально и на origin. Ниже — результаты первоначальной интеграции, они не заменяют проверки новых спринтов.

Интеграция завершена: база dev `3003225`, история main `945ab35` сохранена. Ветка работы — `integration/dev-base-20261021`. Основные коммиты: `ecf787a` (baseline/ingest), `ef58d01` (data/Web/auth/sync), `43c72cb` (ручной merge), `0349898` (coroutines ABI), `dffc614` (Ubuntu launcher/ZIP), `d1163ef` (browser locale).

Слиты [PR #1: integration → dev](https://github.com/ursusandwolf/langkraft/pull/1), merge `8675897b1aa6c5b4f7d33399358882168ce1c9d3`, и [PR #2: dev → main](https://github.com/ursusandwolf/langkraft/pull/2), merge `9576ab4c439edc656dad0612f04ee44a735d39d0`. Перед PR #2 проверено точное совпадение дерева dev с протестированным `d1163ef`; дерево main после слияния также совпало. Force push и переписывание истории не использовались.

**Успешный CI итогового кода:** [push 39696795929](https://github.com/ursusandwolf/langkraft/actions/runs/39696795929) и [PR 39696800693](https://github.com/ursusandwolf/langkraft/actions/runs/39696800693), оба полностью зелёные до слияния PR #1.

Архиватор `python tools/review/Create-ReviewArchive.py` создаёт ZIP исходников текущего коммита без тестов и архивных вариантов. В manifest включены точный hash и SHA-256 каждого файла; целостность ZIP и исключения проверены. Генерируемый архив находится в `build/review/` и не коммитится.

Локально и в CI прошли **83 JVM-теста (42 shared + 41 backend)**, **26 общих Kotlin-тестов в Chromium**, три browser storage-сценария, Web UI development/production и smoke Desktop/TUI. Проверены миграции обеих схем, backup/повторное открытие, SRS/tags/source references, JWT и две локальные БД через настоящий Ktor sync, tombstones и гонка offline acknowledgement. PlayerRaceTest покрывает поздние загрузки/переводы, ошибки аудио и повтор сохранения.

Отдельно локально выполнен реальный YouTube импорт Easy German 1: 48 реплик, 156 секунд MP3, 200 waveform bins с ненулевым сигналом; Range bytes=0-255 вернул 206 и 256 байтов. Chromium подтвердил воспроизведение и offline после reload. Некорректные POSIX locale-значения намеренно воспроизводятся в development/production regression и нормализуются перед Compose; корректные предпочтения сохраняются.

Промежуточные CI выявили и помогли устранить несовместимость coroutines-core/test, запуск Chromium на Ubuntu и падение Compose при POSIX locale. Первый пустой Web-экран не был признан успехом: добавлены startup readiness, stack trace и screenshot при ошибке, найдена причина Intl.Locale и повторно пройден полный UI-сценарий.

Незавершённые внешние проверки: Gemini с реальным ключом, Android, физический звук Desktop/TUI (Windows Application Control блокирует ffplay; VLC отсутствует). Offline Web проверен внутри загруженного приложения; service worker для запуска приложения полностью без сети не добавлен. Квоты/retention, crash leftovers IndexedDB и координация вкладок остаются в backlog. Тестовые серверы остановлены; пользовательские данные не удалялись.
