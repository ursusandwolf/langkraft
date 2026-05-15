# Код-ревью: Langkraft — Immersion German Learning Platform

**Ревьюер:** Staff Engineer  
**Дата:** 2026-05-15  
**Версия:** langkraft-dev (коммит от 2026-05-14)  
**Стек:** Kotlin Multiplatform, Compose Multiplatform, Ktor, SQLDelight, Koin, Exposed, Gemini API

---

## 🔍 1. Общий обзор проекта

### Доменная модель

Проект — мобильно-веб платформа для иммерсивного изучения немецкого языка через YouTube-контент. Доменные сущности понятны:

- **ImmersionContent** — медиа-контент (аудио + субтитры)
- **SubtitleLine** — временна́я метка + текст
- **VocabularyWord** — сохранённое слово с SRS-данными (SM-2)
- **IngestionJob** — асинхронный джоб скачивания с YouTube
- **LinguisticAssistant** — AI-интерфейс (перевод, анализ, коррекция)

### Основные сущности и ответственность

| Слой | Модуль | Ответственность |
|---|---|---|
| Domain | `shared/commonMain/domain` | Модели, интерфейсы репозиториев, SRS-алгоритм, UseCase |
| Data | `shared/commonMain/data` | SQLDelight-репозитории, SyncManager |
| UI | `shared/commonMain/ui` | ViewModels, Compose-экраны |
| Infra | `shared/{android,js,desktop}Main` | AudioPlayer, FileSystem — platform-specific |
| Backend | `backend` | Ktor-сервер, yt-dlp, Gemini AI, auth, sync |

### Архитектурный стиль

Clean Architecture с правильным направлением зависимостей: `UI → Domain ← Data`. Стиль — **procedural/data-centric** с элементами **MVI** во ViewModels (sealed events + immutable state). Domain не содержит бизнес-логики в сущностях — это анемичная модель, что для данного масштаба допустимо.

### Читабельность без контекста

Хорошая. Нейминг осмысленный, разбиение по пакетам очевидно. Единственное исключение — `AuthRequest.passwordHash`: поле называется как хэш, но на самом деле передаётся пароль в открытом виде (см. раздел «Баги»).

---

## 🏗 2. Архитектура и дизайн

### SOLID

**S — Single Responsibility:**  
✅ Хорошо соблюдается. `PlayerViewModel` разделён на `PlayerLinguisticDelegate` и `OfflineDownloadDelegate` — правильное решение. `SyncManager` отвечает только за оркестрацию синхронизации.  
⚠️ `SqlDelightContentRepository` реализует сразу три интерфейса: `LocalContentRepository`, `RemoteContentSource`, `AudioDownloader`. Это граница нарушения — репозиторий локального хранилища не должен знать о скачивании файлов и HTTP-запросах к бэкенду.

**O — Open/Closed:**  
✅ `SpacedRepetitionAlgorithm` — интерфейс, реализация `Sm2Algorithm` сменяема. `LinguisticAssistant` — интерфейс с Mock/Gemini/Caching реализациями. Декоратор `CachingLinguisticAssistant` — грамотный пример OCP.

**L — Liskov:**  
✅ Нарушений не обнаружено.

**I — Interface Segregation:**  
⚠️ `VocabularyRepository` содержит метод `sync()` — это инфраструктурная ответственность, не относящаяся к domain-интерфейсу репозитория. Тест `SyncManagerTest` вынужден реализовывать 8 методов в fake-классе, из которых 7 неактуальны для тестируемого сценария.

**D — Dependency Inversion:**  
✅ Соблюдается. ViewModels и UseCases зависят от интерфейсов. DI через Koin. Backend использует интерфейс `VocabularySyncRepository`.  
⚠️ `SqlDelightContentRepository` содержит hardcoded `http://localhost:8080/api/ingest` — прямое нарушение DI и принципа конфигурируемости.

### God Object / Anti-patterns

- Нет явного God Object.
- `SqlDelightContentRepository` приближается к нему — 3 интерфейса + HTTP + FileSystem + DB.
- `SyncManager` — хороший пример правильно вынесенной ответственности.

### Связность/связанность

- Cohesion: высокая внутри пакетов domain и srs.
- Coupling: умеренный. `PlayerViewModel` зависит от 6 зависимостей — на грани, но делегаты снижают нагрузку.

### Разделение слоёв

В целом соблюдается. Нарушение: `SqlDelightContentRepository` делает `httpClient.post("http://localhost:8080/api/ingest")` — это инфраструктурный код внутри data-слоя без конфигурации.

---

## ⚙️ 3. Алгоритмы и логика

### Эффективность

**`BackendVocabularyRepository.sync`** — запрос серверных изменений:
```kotlin
VocabularySync.select { ... }.filter { it[VocabularySync.id] !in clientIds }
```
Фильтрация происходит **в памяти** после загрузки всех строк из БД. При 10 000+ слов это выгрузка всего словаря в RAM вместо одного SQL-запроса. Это O(n) аллокаций на каждый sync.

**Правильно:**
```sql
WHERE userId = ? AND lastUpdated > ? AND id NOT IN (?, ?, ...)
```

**`SqlDelightVocabularyRepository.sync`** — формирование `changedWords`:
```kotlin
val changedWords = pendingChanges.map { change ->
    val wordEntity = db.appDatabaseQueries.selectWordById(change.wordId).executeAsOneOrNull()
    ...
}
```
Это **N+1 запрос** — по одному SELECT на каждое слово. При 50 pending changes = 50 отдельных запросов.

**`SrsTrainingViewModel`** — состояние очереди обновляется через Flow от SQLDelight, что корректно, но между `submitResult()` и следующей эмиссией пользователь технически может нажать кнопку оценки дважды. Нет debounce или блокировки.

### SM-2 алгоритм

```kotlin
val newInterval = when (word.intervalDays) {
    0 -> 1
    1 -> 6
    else -> (word.intervalDays * newEaseFactor).toInt()
}
```
`toInt()` выполняет **truncation** вместо округления. При `intervalDays = 5`, `easeFactor = 1.3` → `5 * 1.3 = 6.5` → `6` вместо `7`. Алгоритм будет систематически недооценивать интервал на ~0.5 дня и тормозить прогресс.

---

## 📦 4. Работа с коллекциями и структурами данных

### Хорошее

- SQLDelight Flows + корутины — правильный подход для реактивности.
- `ConcurrentHashMap` в `YouTubeIngestionService.jobs` — корректно для concurrent access.
- `Mutex` в `SyncManager` — правильное использование.

### Проблемы

**`CachingLinguisticAssistant`** — **unbounded кэш**:
```kotlin
private val wordCache = ConcurrentHashMap<String, TranslationResult>()
private val sentenceCache = ConcurrentHashMap<String, String>()
private val analysisCache = ConcurrentHashMap<String, DeepAnalysisResult>()
private val correctionCache = ConcurrentHashMap<String, CorrectionResult>()
```
Нет никакой политики вытеснения. Кэш будет расти неограниченно всё время работы сервера. Нужен LRU или TTL.

**Tags как CSV:**
```kotlin
word.tags.joinToString(",")
// И обратно:
tags.split(",")
```
Классический антипаттерн. Тег с запятой (`"grammar, advanced"`) сломает парсинг. Нужен JSON или отдельная таблица.

**`downloadAudio`** — двойная запись данных:
```kotlin
fileSystem.writeBytes(tempPath, body)   // Записали раз
fileSystem.writeBytes(destinationPath, body) // Записали второй раз
fileSystem.delete(tempPath)
```
`body: ByteArray` держится в памяти и пишется дважды. Нет реального rename/move — это имитация. Для больших аудиофайлов (opus, 10-100MB) это критично.

---

## 🧱 5. Качество кода

### Нейминг

⚠️ **Опасный нейминг в AuthModels:**
```kotlin
data class AuthRequest(val email: String, val passwordHash: String)
data class RegisterRequest(val email: String, val passwordHash: String, ...)
```
Поле называется `passwordHash`, но в `AuthService` оно хэшируется через BCrypt. Это либо двойное хэширование (хэш хэша), либо просто пароль с ложным именем. Оба варианта — проблема.

✅ Нейминг везде остальном читаемый и консистентный.

### Читабельность

- Sealed class `PlayerEvent` с 13 подтипами — отличная читаемость MVI.
- `PlayerContract.kt` — хорошая практика держать State/Event в одном файле.
- `SrsEngine.kt` — константы именованы (`MIN_EASE_FACTOR`, `MASTERED_DAYS_THRESHOLD`) — хорошо.

### Дублирование

- Метод `startTimer()/stopTimer()` дублируется в `androidMain/AudioPlayerImpl` и `jsMain/AudioPlayerImpl`. Можно вынести в абстрактный базовый класс или общий utility.
- Блокировки в `toDomain()` функциях: каждый репозиторий имеет свои extension-функции маппинга — дублирование при добавлении полей.

### Магические числа/строки

```kotlin
const val MIN_SYNC_INTERVAL_MS = 60_000L // OK - именованная константа
delay(500) // YouTubeIngestionService - магия
List(100) { Random.nextFloat() } // генерация waveform - 100 - магия
List(50) { 0.2f + (it % 5) * 0.1f } // View - двойная магия
val TOKEN_VALIDITY_MS = 3600000 * 24L // OK - именована
```

### Размер методов

Все методы компактные. `processIngestion` в `YouTubeIngestionService` (~30 строк) — на верхней границе, но читаем.

---

## 🧪 6. Тестируемость

### Что хорошо

- `SyncManagerTest` — 7 тест-кейсов с чёткими сценариями (throttling, concurrency, error handling, LWW). Это **образцовый** уровень тестирования sync-логики для pet/стартап-проекта.
- `BackendVocabularyRepositoryTest` — интеграционный тест с in-memory SQLite, проверяет LWW конфликты и batch-операции. Хороший уровень.
- `SrtParserTest` — корректно проверяет SRT и VTT форматы включая edge-case с часами.

### Проблемы

- `DashboardViewModelTest` содержит один тест на синхронизацию ошибок. Нет тестов для `loadStats()`, `combine()` поведения.
- `SrsTrainingViewModel` — **нет тестов**. Алгоритм SRS и прогрессия очереди критичны для продукта.
- `PlayerViewModel` — **нет тестов**. Функция `updateTime()` с looping logic не покрыта.
- `Sm2Algorithm` — **нет тестов**. Математический алгоритм без unit-тестов — серьёзный пропуск.
- Жёсткие зависимости: `BaseViewModel` использует `Dispatchers.Unconfined` — это решает проблему тестируемости, но не является правильным паттерном для production (см. баги).
- `GeminiLinguisticAssistant` — нетестируем без mock HTTP-клиента.

---

## 🚀 7. Масштабируемость и расширяемость

### Легко расширяется

- Добавить новый SRS-алгоритм → реализовать `SpacedRepetitionAlgorithm` → подключить в DI.
- Добавить новую платформу (iOS) → реализовать `expect/actual` для `AudioPlayerImpl`, `FileSystemImpl`.
- Добавить новый AI-провайдер → реализовать `LinguisticAssistant` → обернуть в `CachingLinguisticAssistant`.

### Где код сломается при росте

- **N+1 в sync** — при 500+ словах sync начнёт подвисать (500 SELECT запросов).
- **Unbounded кэш** — сервер упадёт по OOM после нескольких дней под нагрузкой.
- **`jobs = ConcurrentHashMap`** в `YouTubeIngestionService` — нет TTL, нет очистки. Джобы накапливаются вечно. 10 000 ингестий = утечка памяти.
- **Waveform не сохраняется в БД** — при каждом открытии контента `waveform = emptyList()`, View показывает fallback `List(50) { 0.2f + ... }`. Это не задокументировано как TODO в коде.
- **Backend hardcoded URL** (`http://localhost:8080`) — невозможно деплоить без правки кода.
- **Навигация** — оба desktop и web main.kt рендерят только `DashboardView`. Добавление навигации потребует переработки точек входа.

---

## 🐞 8. Потенциальные баги и риски

### [CRITICAL] Сериализация IngestionJob

```kotlin
@kotlinx.serialization.Serializable
data class IngestionJob(
    ...
    val content: ImmersionContent? = null, // ← ImmersionContent НЕ @Serializable!
    ...
)
```
`ImmersionContent` — обычный `data class` без `@Serializable`. Это **compile-time ошибка** или runtime crash при попытке сериализовать `IngestionJob` с непустым `content`. GET `/api/ingest/{jobId}` никогда не вернёт успешный результат с данными.

### [CRITICAL] Неверная семантика поля passwordHash

```kotlin
// Клиент отправляет:
AuthRequest(email = "user@example.com", passwordHash = "SuperSecret123")
// Сервер делает:
BCrypt.withDefaults().hashToString(12, request.passwordHash.toCharArray())
```
Если клиент отправляет **пароль** (судя по логике) с именем `passwordHash` — это вводит в заблуждение и создаёт риск того, что в будущем кто-то реально начнёт хэшировать на клиенте, и тогда `BCrypt(BCrypt(password))` станет реальной проблемой. Необходимо переименовать в `password`.

### [HIGH] Race condition в SyncManager

```kotlin
if (_isSyncing.value) return  // Check 1
if (_isSyncing.value) return  // Check 2 (внутри mutex)
mutex.withLock {
    _isSyncing.value = true   // Set
}
// mutex ОСВОБОЖДЁН — другая корутина уже может войти!
```
После `mutex.withLock` блок освобождается, но sync ещё не завершён. Второй вызов может пройти check 2 и запустить параллельный sync. Правильно: держать mutex на всё время выполнения sync, или использовать `Mutex.isLocked`.

### [HIGH] PlayerViewModel.isPlaying рассинхронизация

```kotlin
is PlayerEvent.PlayPause -> {
    if (_state.value.isPlaying) audioPlayer.pause() else audioPlayer.play()
    _state.update { it.copy(isPlaying = !it.isPlaying) } // Toggle без подтверждения
}
```
Состояние toggleется немедленно, не дожидаясь ответа от `AudioPlayer`. Если `audioPlayer.play()` выбросит исключение или завершится с ошибкой, `isPlaying = true` в state, хотя аудио не играет.

### [HIGH] `PlayerViewModel.updateTime` не подключён

```kotlin
fun updateTime(timeMs: Long) { ... } // public метод
```
Нет ни одного места, где этот метод вызывается. `AudioPlayer` эмитит `currentTimeMs` через `StateFlow`, но PlayerViewModel его не наблюдает. Функция looping (`isLooping`) в `updateTime` **никогда не работает**.

### [MEDIUM] DELETE в sync не обрабатывается

```kotlin
val changedWords = pendingChanges.map { change ->
    val wordEntity = db.appDatabaseQueries.selectWordById(change.wordId).executeAsOneOrNull()
    wordEntity?.toDomain() ?: VocabularyWord(
        id = change.wordId, word = "", lemma = "", translation = "", contextSentence = "",
        ...
    )
}
```
При DELETE слово уже удалено из локальной БД → `executeAsOneOrNull()` вернёт `null` → на сервер отправляется stub с пустыми строками. Сервер воспримет это как **upsert** с пустым словом. DELETE семантика теряется.

### [MEDIUM] `ImmersionContent.waveform` не персистируется

Waveform генерируется на бэкенде (сейчас Random), передаётся в `IngestionJob`, сохраняется в `ImmersionContent` — но в SQLDelight схеме нет поля `waveform`. При следующем открытии контента `waveform = emptyList()` и View показывает синтетический паттерн.

### [LOW] `SM2.toInt()` truncation

```kotlin
(word.intervalDays * newEaseFactor).toInt() // Надо .roundToInt()
```

### [LOW] `Jobs` map никогда не очищается

`YouTubeIngestionService.jobs: ConcurrentHashMap` — нет TTL или LRU. Завершённые джобы накапливаются вечно.

---

## 💡 9. Конкретные рекомендации

- **[HIGH]** Добавить `@Serializable` к `ImmersionContent` или использовать отдельный DTO для API-ответа. Без этого endpoint `/api/ingest/{jobId}` не работает как задумано.
- **[HIGH]** Переименовать `passwordHash` → `password` в `AuthRequest`/`RegisterRequest`. Либо явно реализовать client-side hashing с документацией.
- **[HIGH]** Подключить `audioPlayer.currentTimeMs` StateFlow к `PlayerViewModel`: убрать метод `updateTime()`, вместо этого `scope.launch { audioPlayer.currentTimeMs.collect { updateTime(it) } }` в `loadContent`.
- **[HIGH]** Устранить N+1 в `SqlDelightVocabularyRepository.sync` — batch SELECT по списку ID.
- **[HIGH]** Вынести URL бэкенда (`http://localhost:8080`) в конфигурацию (Koin parameter или `BuildConfig`).
- **[HIGH]** Добавить тесты для `Sm2Algorithm` и `SrsTrainingViewModel`.
- **[MEDIUM]** Добавить LRU-вытеснение в `CachingLinguisticAssistant` (например, `LinkedHashMap` с `maxSize`).
- **[MEDIUM]** Исправить race condition в `SyncManager` — mutex должен охватывать всё тело sync, а не только установку флага.
- **[MEDIUM]** Исправить DELETE-семантику в sync: включить `changeType` в `SyncRequest`, чтобы сервер знал, что делать с записью.
- **[MEDIUM]** Заменить CSV-теги на JSON-массив или отдельную таблицу.
- **[MEDIUM]** Добавить TTL/cleanup для `YouTubeIngestionService.jobs`.
- **[LOW]** Заменить `toInt()` на `roundToInt()` в SM2 расчёте интервала.
- **[LOW]** Вынести `startTimer()/stopTimer()` в shared код между Android/JS AudioPlayer.
- **[LOW]** Заменить `Dispatchers.Unconfined` в `BaseViewModel` на `Dispatchers.Main` + передавать `TestDispatcher` в тестах через конструктор.

---

## 🔧 10. Рефакторинг (практика)

### Пример 1: Исправить N+1 в sync

**Проблема:**
```kotlin
// SqlDelightVocabularyRepository.sync
val changedWords = pendingChanges.map { change ->
    val wordEntity = db.appDatabaseQueries.selectWordById(change.wordId).executeAsOneOrNull()
    wordEntity?.toDomain() ?: VocabularyWord(id = change.wordId, word = "", ...) 
}
```

**Исправление:**
```kotlin
override suspend fun sync(lastSyncTimestamp: Long): Long {
    val pendingChanges = db.appDatabaseQueries.getAllPendingChanges().executeAsList()
    if (pendingChanges.isEmpty()) return lastSyncTimestamp

    val pendingIds = pendingChanges.map { it.wordId }.toSet()
    val upsertIds = pendingChanges.filter { it.changeType == "UPSERT" }.map { it.wordId }

    // Один batch SELECT вместо N отдельных
    val wordsById: Map<String, VocabularyWord> = db.appDatabaseQueries
        .selectWordsByIds(upsertIds) // Новый запрос: SELECT * WHERE id IN (?, ...)
        .executeAsList()
        .associate { it.id to it.toDomain() }

    val changedWords = pendingChanges.map { change ->
        when (change.changeType) {
            "UPSERT" -> wordsById[change.wordId] 
                ?: return@map null // слово уже удалено локально — пропускаем
            "DELETE" -> VocabularyWord.deleted(change.wordId) // маркер удаления
            else -> null
        }
    }.filterNotNull()

    // ... остальной sync
}
```

### Пример 2: Подключить AudioPlayer time к ViewModel

**Проблема:** `updateTime()` не вызывается, looping не работает.

**Исправление в `PlayerViewModel`:**
```kotlin
private fun loadContent(id: String) {
    scope.launch {
        _state.update { it.copy(isLoading = true) }
        val content = contentRepository.getContentById(id) ?: run {
            _state.update { it.copy(isLoading = false, error = "Content not found") }
            return@launch
        }
        _state.update { it.copy(isLoading = false, content = content) }
        audioPlayer.load(content.getPlaybackUrl())

        // Подключаем поток времени от реального плеера
        audioPlayer.currentTimeMs.collect { timeMs ->
            handleTimeUpdate(timeMs) // было: updateTime(timeMs)
        }
    }
}

private fun handleTimeUpdate(timeMs: Long) {
    val currentState = _state.value
    if (currentState.isLooping) {
        val currentLine = currentState.content?.subtitles?.find {
            currentState.currentTimeMs in it.startMs..it.endMs
        }
        if (currentLine != null && timeMs > currentLine.endMs) {
            audioPlayer.seekTo(currentLine.startMs) // Реальный seek, а не только state
            _state.update { it.copy(currentTimeMs = currentLine.startMs) }
            return
        }
    }
    _state.update { it.copy(currentTimeMs = timeMs) }
}
```

### Пример 3: Разделить SqlDelightContentRepository

**Проблема:** один класс реализует LocalContentRepository + RemoteContentSource + AudioDownloader.

**Исправление — выделить AudioDownloaderImpl:**
```kotlin
// Было: SqlDelightContentRepository : LocalContentRepository, RemoteContentSource, AudioDownloader

// Стало:
class SqlDelightContentRepository(
    private val db: AppDatabase
) : LocalContentRepository {
    // Только DB-операции: getAllContent, getContentById, saveContent, getImmersionStats
}

class AudioDownloaderImpl(
    private val httpClient: HttpClient,
    private val fileSystem: FileSystem,
    private val contentRepository: LocalContentRepository
) : AudioDownloader {
    override suspend fun downloadAudio(content: ImmersionContent): String { ... }
}

class BackendRemoteSource(
    private val httpClient: HttpClient,
    private val baseUrl: String  // ← теперь конфигурируется!
) : RemoteContentSource {
    override suspend fun fetchFromYouTube(url: String): ImmersionContent { ... }
}

// В Koin:
val commonModule = module {
    single { SqlDelightContentRepository(get()) }
    single<LocalContentRepository> { get<SqlDelightContentRepository>() }
    single<RemoteContentSource> { BackendRemoteSource(get(), get<AppConfig>().backendUrl) }
    single<AudioDownloader> { AudioDownloaderImpl(get(), get(), get()) }
}
```

---

## 📊 11. Итоговая оценка

| Критерий | Оценка | Комментарий |
|---|---|---|
| **Архитектура** | **7/10** | Clean Architecture соблюдается, KMP + expect/actual грамотно. Минус за smeared репозиторий и ISP нарушения. |
| **Качество кода** | **6.5/10** | Хороший нейминг и читаемость. Серьёзный баг с сериализацией, N+1, unbounded кэш. |
| **Поддерживаемость** | **7/10** | Разумная декомпозиция, MVI паттерн. Тесты есть, но покрытие критичных компонентов (SRS, Player) отсутствует. |
| **Общая оценка** | **6.5/10** | |

---

### Резюме

**Стоит ли пускать в production?** — **Нет, не в текущем состоянии.**

Причины, блокирующие прод:

1. **`ImmersionContent` не сериализуема** — ключевой endpoint `/api/ingest/{jobId}` не вернёт данные с content при READY статусе. Это означает, что full flow импорта YouTube-видео **не работает end-to-end**.
2. **`updateTime()` не подключён** — looping-режим (заявленная фича) не работает.
3. **Hardcoded `localhost:8080`** — клиент физически не может подключиться к удалённому серверу.
4. **N+1 в sync** — приемлемо для MVP с 50 словами, критично при реальном использовании.

**Что сделано хорошо и заслуживает уважения:** продуманная доменная модель, грамотная декомпозиция PlayerViewModel через делегаты, качественные тесты SyncManager, правильная реализация SM-2, Decorator pattern для кэшированного AI, чистая SQLDelight схема с индексами. Для проекта на этапе MVP это сильная основа — нужна итерация фиксов, прежде чем выходить в прод.
