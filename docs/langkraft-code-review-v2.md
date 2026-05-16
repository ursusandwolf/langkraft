# Код-ревью v2: Langkraft — повторный анализ + диагностика десктопа

**Ревьюер:** Staff Engineer  
**Дата:** 2026-05-16  
**Сравнение:** langkraft-dev (v1) → langkraft-dev v2  
**Фокус:** что исправлено, что осталось, почему не запускается на десктопе

---

## ✅ Что было исправлено (сравнение с v1)

Автор проделал хорошую работу и закрыл большинство блокирующих issues.

| # | Проблема из v1 | Статус |
|---|---|---|
| `ImmersionContent` без `@Serializable` | ✅ Исправлено — `@Serializable` добавлен |
| `passwordHash` вместо `password` | ✅ Исправлено — переименовано в `password` |
| `updateTime()` не подключён к AudioPlayer | ✅ Исправлено — `audioPlayer.currentTimeMs.collect` в `init` |
| Race condition в `SyncManager` | ✅ Исправлено — `mutex.withLock` теперь охватывает весь sync |
| `SqlDelightContentRepository` — 3 интерфейса | ✅ Исправлено — выделены `AudioDownloaderImpl` и `BackendRemoteSource` |
| Hardcoded `localhost:8080` | ✅ Исправлено — `AppConfig.backendUrl` через Koin |
| N+1 в `sync` | ✅ Исправлено — добавлен `selectWordsByIds`, batch SELECT |
| Теги через CSV | ✅ Исправлено — `Json.encodeToString(word.tags)` |
| Unbounded кэш в `CachingLinguisticAssistant` | ✅ Исправлено — LRU через `LinkedHashMap` с `removeEldestEntry` |
| `toInt()` в SM-2 | ✅ Исправлено — `roundToInt()` |
| Нет тестов для `Sm2Algorithm` | ✅ Добавлено — `Sm2AlgorithmTest` с 5 сценариями |
| Нет тестов для `SrsTrainingViewModel` | ✅ Добавлено — `SrsTrainingViewModelTest` |
| TTL для jobs в `YouTubeIngestionService` | ✅ Исправлено — cleanup каждый час |
| `Dispatchers.Unconfined` в `BaseViewModel` | ✅ Исправлено — `Dispatchers.Main` с инъекцией через конструктор |

Это значительный прогресс. Кодовая база стала существенно чище и надёжнее.

---

## 🖥 Почему десктоп не запускается — полная диагностика

Это главный вопрос ревью. Ниже — все причины по убыванию приоритета.

---

### [CRASH #1 — БЛОКИРУЮЩИЙ] `AppDatabase` не зарегистрирован в Koin

**Где:** `Koin.kt` → `commonModule`

```kotlin
// Koin.kt
val commonModule = module {
    single { SqlDelightContentRepository(get()) }       // get() → AppDatabase — НЕТ!
    single<AudioDownloader> { AudioDownloaderImpl(get(), get(), get()) } // третий get() → AppDatabase — НЕТ!
    single<VocabularyRepository> { SqlDelightVocabularyRepository(get(), get(), ...) } // первый get() → AppDatabase — НЕТ!
}
```

`AppDatabase` нигде не объявлен как `single { ... }` в `commonModule`. При запуске десктопа Koin выбросит:

```
org.koin.core.error.NoBeanDefFoundException:
  No definition found for class:'com.langkraft.db.AppDatabase'
```

**Почему это специфично для десктопа:** SQLDelight требует platform-specific драйвер для создания `AppDatabase`. Для десктопа это `JdbcSqliteDriver`, для Android — `AndroidSqliteDriver`, для JS — `WebWorkerDriver`. Создание `AppDatabase` **не может** быть в `commonModule` — оно должно быть в платформенном модуле Koin, который добавляется при инициализации.

**Исправление:**

Создать `desktopMain/kotlin/di/DesktopModule.kt`:
```kotlin
// shared/src/desktopMain/kotlin/di/DesktopModule.kt
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.langkraft.db.AppDatabase
import org.koin.dsl.module
import java.io.File

val desktopModule = module {
    single<AppDatabase> {
        val dbDir = File(System.getProperty("user.home"), ".langkraft").also { it.mkdirs() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbDir.absolutePath}/langkraft.db")
        // Создаём схему только если БД новая
        val userVersion = driver.executeQuery(null, "PRAGMA user_version", { it.getLong(0)!! }, 0).value
        if (userVersion == 0L) {
            AppDatabase.Schema.create(driver)
        }
        AppDatabase(driver)
    }
}
```

И обновить `main.kt`:
```kotlin
fun main() = application {
    initKoin {
        modules(desktopModule) // ← добавить платформенный модуль
    }
    Window(...) { ... }
}
```

И `initKoin` в `Koin.kt` изменить, убрав дублирующий overload:
```kotlin
fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(commonModule)
    }
// Убрать: fun initKoin() = initKoin {}  ← это создаёт путаницу, хотя и компилируется
```

---

### [CRASH #2 — БЛОКИРУЮЩИЙ] Отсутствует Ktor client engine для JVM

**Где:** `shared/build.gradle.kts` → `desktopMain` dependencies

```kotlin
val desktopMain by getting {
    dependencies {
        implementation(compose.desktop.currentOs)
        implementation("app.cash.sqldelight:sqlite-driver:2.0.0")
        // ← здесь нет Ktor engine!
    }
}
```

`commonModule` создаёт `HttpClient {}` без явного движка:
```kotlin
single { 
    HttpClient {           // ← на JVM без движка: RuntimeException
        install(ContentNegotiation) { json(...) }
    }
}
```

На JVM `HttpClient {}` без движка бросает при первом использовании (или при инициализации, в зависимости от версии Ktor):

```
io.ktor.client.engine.HttpClientEngineContainer: 
  No HTTP client engine found. Add dependency with HTTP client engine.
```

Ktor на JVM не имеет встроенного движка — нужно явно добавить `CIO`, `OkHttp` или `Apache`.

**Исправление в `build.gradle.kts`:**
```kotlin
val desktopMain by getting {
    dependencies {
        implementation(compose.desktop.currentOs)
        implementation("app.cash.sqldelight:sqlite-driver:2.0.0")
        implementation("io.ktor:ktor-client-cio:2.3.5") // ← добавить
    }
}
```

И обновить создание `HttpClient` в `commonModule` — либо оставить без движка (он подхватится из classpath), либо явно передать движок через `expect/actual`:

```kotlin
// commonMain
expect fun createHttpClient(): HttpClient

// desktopMain
actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

// jsMain  
actual fun createHttpClient(): HttpClient = HttpClient(Js) { ... }
```

---

### [CRASH #3 — УСЛОВНЫЙ] `Dispatchers.Main` недоступен на старте

**Где:** `BaseViewModel`, `DashboardViewModel` — используют `Dispatchers.Main` по умолчанию

`DashboardViewModel` создаётся **внутри** `Window {}` composable:
```kotlin
// main.kt
Window(onCloseRequest = ::exitApplication) {
    LangkraftTheme {
        val dashboardViewModel = object : KoinComponent {
            val vm: DashboardViewModel by inject()
        }.vm
        DashboardView(dashboardViewModel)
    }
}
```

`DashboardViewModel` при создании сразу запускает корутины на `Dispatchers.Main`:
```kotlin
init {
    loadStats()          // scope.launch { ... } на Main
    scope.launch { syncManager.sync() }
}
```

Compose Desktop предоставляет собственный `Dispatchers.Main` через `kotlinx-coroutines-swing` (или skiko-интеграцию). Проблема: к моменту вызова `by inject()` внутри composable-функции Compose уже запущен и `Dispatchers.Main` доступен. **Это обычно работает.** Но если Koin пытается создать ViewModel eagerly (например, через `single` вместо `factory`), она создаётся до входа в Compose context.

В данном случае используется `factory { DashboardViewModel(...) }` — это lazy, ViewModel создаётся по требованию внутри Window. Поэтому это, скорее всего, не crashит, но **является архитектурным риском**: ViewModel не должна создаваться внутри composable-функции — это ломает remember/lifecycle.

**Правильный подход для десктопа:**
```kotlin
fun main() = application {
    initKoin { modules(desktopModule) }
    
    val dashboardViewModel: DashboardViewModel = remember { 
        // Кастомный KoinComponent вне composable
        getKoin().get()
    }
    
    Window(onCloseRequest = ::exitApplication) {
        LangkraftTheme {
            DashboardView(dashboardViewModel)
        }
    }
}
```

Или использовать `koin-compose` правильно: `val vm = koinInject<DashboardViewModel>()` внутри composable.

---

### [ПРОБЛЕМА] `initKoin()` вызывается внутри `application {}`

```kotlin
fun main() = application {
    initKoin()  // ← вызов Koin внутри Compose application lambda
    Window(...) { ... }
}
```

`application {}` — это Compose runtime lambda. Вызов `initKoin()` здесь означает, что Koin инициализируется при каждой рекомпозиции `application` блока. На практике `application {}` в Compose Desktop вызывается один раз, но это семантически неверно. Koin должен инициализироваться **до** `application {}`:

```kotlin
fun main() {
    initKoin { modules(desktopModule) }
    application {
        Window(...) { ... }
    }
}
```

---

## 🔍 Оставшиеся проблемы из v1

### [MEDIUM] DELETE-семантика через `lapseCount = -1` — хак

```kotlin
// SqlDelightVocabularyRepository.kt
VocabularyWord(
    id = change.wordId,
    lapseCount = -1,  // ← магический sentinel для "это удаление"
    ...
)

// При получении серверных изменений:
if (word.lapseCount == -1) {
    db.appDatabaseQueries.deleteWord(word.id)
}
```

`lapseCount = -1` — это захват бизнес-поля под системную нужду. Это нарушает семантику модели: `lapseCount` не может быть отрицательным по определению SM-2. Что произойдёт, если сервер получит слово с `lapseCount = -1` и не поймёт контекст? Или если в будущем кто-то добавит валидацию `require(lapseCount >= 0)`?

**Правильное решение:** Добавить `changeType: String` в `SyncRequest`:
```kotlin
@Serializable
data class SyncEntry(
    val word: VocabularyWord,
    val changeType: String  // "UPSERT" | "DELETE"
)

@Serializable
data class SyncRequest(
    val lastSyncTimestamp: Long,
    val clientChanges: List<SyncEntry>  // ← не List<VocabularyWord>
)
```

### [MEDIUM] `SyncManager.sync()` не переключается на IO диспатчер

```kotlin
// SyncManager.kt
mutex.withLock {
    _isSyncing.value = true
    // ...
    val newTimestamp = vocabularyRepository.sync(lastSyncTimestamp)  // HTTP-запрос на Main!
}
```

`DashboardViewModel` вызывает `syncManager.sync()` на `Dispatchers.Main`. Внутри `sync()` нет `withContext(Dispatchers.IO)`. `VocabularyRepository.sync()` делает сетевой запрос через Ktor. На Main диспатчере это заблокирует UI поток (или вызовёт исключение на строгих реализациях Main).

**Исправление:**
```kotlin
override suspend fun sync(force: Boolean) {
    ...
    mutex.withLock {
        _isSyncing.value = true
        withContext(Dispatchers.IO) {  // ← добавить
            val lastSyncTimestamp = ...
            val newTimestamp = vocabularyRepository.sync(lastSyncTimestamp)
            ...
        }
    }
}
```

### [LOW] Double write в `AudioDownloaderImpl` — не исправлено

```kotlin
fileSystem.writeBytes(tempPath, body)       // Запись 1
fileSystem.writeBytes(destinationPath, body) // Запись 2 — body уже в памяти
fileSystem.delete(tempPath)
```

Проблема осталась: `ByteArray` для аудио (~20-80MB) хранится в памяти и пишется дважды. Комментарий в коде честно признаёт это (`// Note: Our FileSystem abstraction is simple`), но `FileSystem` так и не получил метод `rename()`.

**Минимальное исправление:** добавить `fun rename(from: String, to: String)` в `FileSystem` интерфейс с реализацией через `File.renameTo()` на JVM.

### [LOW] Избыточный overload `initKoin()`

```kotlin
fun initKoin(appDeclaration: KoinAppDeclaration = {}) = ...  // уже есть default param
fun initKoin() = initKoin {}                                  // ← дублирует первый
```

Второй overload не добавляет ценности и создаёт confusion. При вызове `initKoin()` из `main.kt` Kotlin выбирает no-arg версию. Это работает, но семантически неясно.

### [LOW] `println` вместо логгера в `VocabularyRepository`

```kotlin
} catch (e: Exception) {
    println("Sync failed: ${e.message}")  // ← println в production коде
    lastSyncTimestamp
}
```

В `shared/commonMain` нет доступа к `slf4j`, поэтому `println` — технически вынужденная мера. Но стоит добавить multiplatform-логгер (например, `kermit` от Touchlab) или как минимум обернуть в ожидаемую (`expect fun log(...)`) функцию.

---

## 🧪 Оценка новых тестов

### `Sm2AlgorithmTest` — хорошо ✅

5 чётких тестов покрывают: первый правильный ответ, второй правильный ответ, проверку `roundToInt`, сброс при провале, рост ease factor. Особенно важен `testIntervalRounding` — он верифицирует конкретно исправленный баг.

### `SrsTrainingViewModelTest` — хорошо, но есть нюанс ⚠️

```kotlin
private val testDispatcher = UnconfinedTestDispatcher()

val viewModel = SrsTrainingViewModel(repo, FakeSrsAlgorithm(), testDispatcher)
```

Использование `UnconfinedTestDispatcher` правильно для тестов. Однако `FakeVocabularyRepository` возвращает `MutableStateFlow` в `getWordsToReview()`, что делает тест немного хрупким — реальный `SqlDelightVocabularyRepository` возвращает cold Flow от SQLDelight, который ведёт себя иначе. Тест проверяет ViewModel, но не проверяет поведение при повторных эмиссиях (что происходит после `saveWord`).

---

## 📊 Обновлённая итоговая оценка

| Критерий | v1 | v2 | Изменение |
|---|---|---|---|
| **Архитектура** | 7/10 | 8.5/10 | +1.5 — разделение репозиториев, AppConfig |
| **Качество кода** | 6.5/10 | 8/10 | +1.5 — исправлены N+1, кэш, roundToInt, теги |
| **Поддерживаемость** | 7/10 | 8/10 | +1.0 — новые тесты, инъекция dispatcher |
| **Общая оценка** | 6.5/10 | **7.5/10** | +1.0 |

### Вывод

**Стоит ли пускать в production?** — Нет, пока не решены две блокирующие проблемы десктопа (#1 `AppDatabase` и #2 Ktor engine). После их исправления — **условно да**: архитектура стала существенно чище, критические security и serialization баги закрыты, тестовое покрытие расширено. Оставшиеся проблемы (`lapseCount = -1`, double write, `println`) допустимы для MVP-стадии при наличии соответствующих TODO.

---

## 🔧 Быстрый фикс для десктопа (чеклист)

```
[ ] 1. Создать shared/src/desktopMain/kotlin/di/DesktopModule.kt с AppDatabase + JdbcSqliteDriver
[ ] 2. Добавить io.ktor:ktor-client-cio:2.3.5 в desktopMain dependencies
[ ] 3. Обновить main.kt: initKoin { modules(desktopModule) } вынести ДО application {}
[ ] 4. Убрать избыточный fun initKoin() overload из Koin.kt
[ ] 5. (Опционально) Добавить withContext(Dispatchers.IO) в SyncManager.sync()
```

После этих пяти шагов десктоп должен запуститься.
