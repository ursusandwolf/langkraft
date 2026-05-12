# Langkraft Code Review v2 — Senior Kotlin/Java Analysis

> **Стек:** Kotlin Multiplatform · Ktor · SQLDelight · Coroutines / StateFlow

---

## Общая оценка (Post-Refactoring)

Предыдущие критичные замечания (bcrypt, скрытие API-ключа, N+1 проблема, DIP/ISP для Repository и SRS) были **успешно устранены**. База проекта стала значительно безопаснее и чище.

Однако в текущей реализации остаются проблемы, связанные с реактивным программированием (потоками данных), многопоточностью на бекенде и мобильным UX. 

---

## 🔴 Критичные проблемы (Баги и блокировщики)

### 1. Состояние гонки (Race Condition) в `SrsTrainingViewModel` — **RESOLVED**

**Статус:** Решено. 
Анализ показал, что текущая архитектура `SrsTrainingViewModel` и `SqlDelightVocabularyRepository` уже использует реактивный поток (`Flow`) в качестве единого источника правды. Вызов `saveWord` автоматически инициирует перевыборку данных и обновление стейта через `collect`, что исключает необходимость в ручном управлении очередью.

### 2. Синхронизация блокирует оффлайн (`NotImplementedError`)

**Файл:** `SqlDelightVocabularyRepository.kt`

**Проблема:** Метод `sync()` правильно очищен от "заглушки, которая врёт", но сейчас он выбрасывает `NotImplementedError`. Это означает, что любое обращение к синхронизации на мобильном клиенте приведет к крэшу приложения. 

**Решение:**
Нужна реализация **Offline-First Sync Queue**:
- Создать таблицу `pending_sync_changes` (ID слова, тип операции, timestamp).
- Вынести `sync()` в отдельный фоновый `SyncWorker` (или `SyncManager`), который запускается при появлении интернета.

### 3. Блокирующий HTTP-запрос в `YouTubeIngestionService`

**Файл:** `YouTubeIngestionService.kt`, `Routing.kt`

**Проблема:** Эндпоинт `/api/ingest` принимает запрос и ждет полного скачивания видео, конвертации в Opus и парсинга субтитров:
```kotlin
val content = ingestionService.ingest(request.url) // Ждет 5-10 минут!
call.respond(content)
```
Ktor отвалится по таймауту, либо воркеры будут заблокированы.

**Решение:**
Перейти на асинхронную обработку. Клиент должен отправить URL и получить `jobId`. Затем клиент опрашивает статус (Polling) или получает ответ по WebSocket:
- `ContentProcessingStatus.DOWNLOADING_AUDIO`
- `ContentProcessingStatus.PARSING_SUBTITLES`

---

## 🟡 Архитектурные улучшения и UX

### 4. Шкала SRS 0-5 против 4 кнопок (Anki)

**Файл:** `SrsEngine.kt` / `Models.kt`

Хоть алгоритм и был перенесен за интерфейс `SpacedRepetitionAlgorithm`, он всё еще оперирует абстрактным `quality: Int` (0-5). Для мобильных приложений этот UX устарел. Рекомендуется перейти на 4-кнопочную систему (как в Anki):
`AGAIN (Сброс)`, `HARD (Тяжело)`, `GOOD (Нормально)`, `EASY (Легко)`.

Также модель `VocabularyWord` стоит расширить полями для трекинга ошибок:
- `val lapseCount: Int = 0` (счетчик полных забываний)
- `val tags: List<String>`

### 5. Дальнейшее разделение `PlayerViewModel`

**Файл:** `PlayerViewModel.kt`

Делегирование лингвистической логики в `PlayerLinguisticDelegate` сильно разгрузило класс. Тем не менее, ViewModel всё еще занимается скачиванием файлов для оффлайна (`handleToggleOffline`). 

Для полного соблюдения SRP рекомендуется вынести эту логику в `OfflineDownloadDelegate`, оставив в `PlayerViewModel` **только** управление аудиоплеером (play/pause/seek) и навигацию по субтитрам.

### 6. Waveform так и не генерируется

**Файл:** `Models.kt`

Модель `ImmersionContent` содержит `val waveform: List<Float>`, но на бекенде при Ingestion массива нет, и `WaveformVisualizer` всегда будет пустым. Необходимо добавить шаг извлечения амплитуд аудио при парсинге на бекенде (например, через ffmpeg).
