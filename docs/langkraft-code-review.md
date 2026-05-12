# Code Review: LangKraft — Senior Kotlin/Java Analysis

> **Стек:** Kotlin Multiplatform · Ktor · SQLDelight · Exposed · Koin · Gemini AI  
> **Методология ревью:** SOLID · GoF Patterns · Clean Architecture · Бизнес-логика

---

## Общая оценка

Проект имеет **правильный архитектурный каркас**: чистое разделение на domain/data/ui, использование интерфейсов репозиториев, MVI-подобный паттерн в ViewModels, и хорошая идея с KMP под несколько платформ. Это крепкая база.

Главные болевые точки: **критическая уязвимость в аутентификации**, нарушение ISP в `ContentRepository`, перегруженный `PlayerViewModel`, и нереализованная клиентская синхронизация — всё это нужно исправить до production.

---

## 🔴 Критичные проблемы

---

### 1. Уязвимость безопасности: пароли без хеширования на сервере

**Файл:** `Routing.kt` — `authRoutes()`

```kotlin
// ❌ Клиент присылает "passwordHash" — но кто его хеширует?
val request = call.receive<RegisterRequest>()

// И сравнение — это просто строковое равенство
if (user == null || user.passwordHash != request.passwordHash) {
    call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
}
```

**Почему критично:** Клиент присылает поле с именем `passwordHash`, но сервер нигде не применяет `bcrypt`/`Argon2`. Это означает либо пароли хранятся в открытом виде, либо ответственность за хеширование перекладывается на клиента — что ещё хуже, так как разные клиенты могут хешировать по-разному. При утечке БД — все пароли компрометированы.

**Как исправить:**
```kotlin
// Добавить в build.gradle:
// implementation("at.favre.lib:bcrypt:0.10.2")

class AuthService(private val userRepository: BackendUserRepository) {

    suspend fun register(email: String, rawPassword: String, displayName: String): AuthResponse {
        val existing = userRepository.findByEmail(email)
        if (existing != null) throw ConflictException("User already exists")

        val passwordHash = BCrypt.withDefaults().hashToString(12, rawPassword.toCharArray())
        val userId = userRepository.createUser(email, passwordHash, displayName)
        return AuthResponse(generateToken(email), UserInfo(userId, email, displayName))
    }

    suspend fun login(email: String, rawPassword: String): AuthResponse {
        val user = userRepository.findByEmail(email)
            ?: throw UnauthorizedException("Invalid credentials")

        val result = BCrypt.verifyer().verify(rawPassword.toCharArray(), user.passwordHash)
        if (!result.verified) throw UnauthorizedException("Invalid credentials")

        return AuthResponse(generateToken(email), UserInfo(user.id, user.email, user.displayName))
    }
}
```

---

### 2. API-ключ Gemini торчит в URL

**Файл:** `GeminiLinguisticAssistant.kt`

```kotlin
// ❌ API-ключ в query parameter — виден в логах, прокси, history браузера
httpClient.post("$baseUrl?key=$apiKey") { ... }
```

**Как исправить:**
```kotlin
httpClient.post(baseUrl) {
    headers {
        append("x-goog-api-key", apiKey)  // или Authorization header
    }
    contentType(ContentType.Application.Json)
    setBody(...)
}
```

---

### 3. `SrsEngine` как `object` — нарушение DIP, невозможно тестировать

**Файл:** `SrsEngine.kt`, `SrsTrainingViewModel.kt`

```kotlin
// ❌ object = глобальный синглтон, нельзя замокировать
object SrsEngine {
    fun calculateNextReview(word: VocabularyWord, quality: Int): VocabularyWord { ... }
}

// В ViewModel — прямой вызов без DI
fun submitResult(quality: Int) {
    val updatedWord = SrsEngine.calculateNextReview(word, quality)  // ❌
    ...
}
```

**Как исправить — Strategy Pattern + DIP:**
```kotlin
// Интерфейс — можно заменить реализацию или замокировать в тестах
interface SpacedRepetitionAlgorithm {
    fun calculateNextReview(word: VocabularyWord, quality: Int): VocabularyWord
}

// Реализация SM-2
class Sm2Algorithm : SpacedRepetitionAlgorithm {
    override fun calculateNextReview(word: VocabularyWord, quality: Int): VocabularyWord { ... }
}

// ViewModel получает алгоритм через DI
class SrsTrainingViewModel(
    private val vocabularyRepository: VocabularyRepository,
    private val srsAlgorithm: SpacedRepetitionAlgorithm  // ← инжектируется
) : BaseViewModel()

// Koin
single<SpacedRepetitionAlgorithm> { Sm2Algorithm() }
```

Теперь можно написать `SrsTrainingViewModelTest` с `FakeAlgorithm` без реальных вычислений.

---

### 4. Нарушение ISP — `ContentRepository` объединяет несвязанные контракты

**Файл:** `Repositories.kt`

```kotlin
// ❌ Один интерфейс — три разные ответственности
interface ContentRepository {
    // Локальное хранилище
    fun getAllContent(): Flow<List<ImmersionContent>>
    suspend fun saveContent(content: ImmersionContent)

    // Сетевой вызов (HTTP к бекенду)
    suspend fun fetchFromYouTube(url: String): ImmersionContent

    // Файловая система (скачивание файлов)
    suspend fun downloadAudio(content: ImmersionContent): String

    // Статистика
    fun getImmersionStats(): Flow<ImmersionStats>
}
```

Реализация `SqlDelightContentRepository` вынуждена реализовывать сетевые операции в классе, который отвечает за работу с БД. Это делает его нетестируемым и нарушает SRP заодно.

**Как разделить:**
```kotlin
// Контракт 1: только локальное хранилище
interface LocalContentRepository {
    fun getAllContent(): Flow<List<ImmersionContent>>
    suspend fun getContentById(id: String): ImmersionContent?
    suspend fun saveContent(content: ImmersionContent)
    fun getImmersionStats(): Flow<ImmersionStats>
    suspend fun updateDownloadStatus(id: String, status: DownloadStatus, path: String?)
}

// Контракт 2: только удалённый источник
interface RemoteContentSource {
    suspend fun fetchFromYouTube(url: String): ImmersionContent
}

// Контракт 3: файловая операция
interface AudioDownloader {
    suspend fun downloadAudio(content: ImmersionContent): String
}
```

---

### 5. `PlayerViewModel` — God ViewModel (нарушение SRP)

**Файл:** `PlayerViewModel.kt` — **165 строк, 5 зависимостей**

Один ViewModel одновременно управляет:
- воспроизведением аудио и состоянием плеера
- скачиванием файлов для оффлайн-режима
- переводом слов и предложений через AI
- Deep Analysis (грамматический разбор)
- лемматизацией
- сохранением слов в словарь

**Как разделить по ответственностям:**
```kotlin
// Только аудио и навигация по контенту
class PlaybackViewModel(
    private val contentRepository: LocalContentRepository,
    private val audioPlayer: AudioPlayer
) : BaseViewModel()

// Только AI-функции для субтитров
class SubtitleAnalysisViewModel(
    private val linguisticAssistant: LinguisticAssistant
) : BaseViewModel()

// Только словарь в контексте плеера
class PlayerVocabularyViewModel(
    private val vocabularyRepository: VocabularyRepository
) : BaseViewModel()
```

Если разделение ViewModel кажется избыточным для KMP, можно использовать **Delegate-паттерн** — вынести AI-логику в отдельный `LinguisticAnalysisDelegate`, который `PlayerViewModel` просто делегирует.

---

### 6. `sync()` на клиенте — заглушка, которая врёт

**Файл:** `SqlDelightVocabularyRepository.kt`

```kotlin
// ❌ Интерфейс обещает синхронизацию — метод просто возвращает время
override suspend fun sync(lastSyncTimestamp: Long): Long {
    // For now just return current time as server timestamp mock
    return Clock.System.now().toEpochMilliseconds()
}
```

Это не заглушка — это ловушка. Dashboard и другие части кода могут вызвать `sync()` и считать, что данные синхронизированы, хотя это не так. Если функционал не реализован — либо убери метод из интерфейса, либо брось `NotImplementedError`.

---

### 7. N+1 проблема в синхронизации словаря

**Файл:** `BackendVocabularyRepository.kt`

```kotlin
// ❌ 100 слов = 100 SELECT + до 100 INSERT — всё в одной транзакции, но всё равно медленно
clientChanges.forEach { word ->
    val updated = VocabularySync.update({ VocabularySync.id eq word.id }) { ... }
    if (updated == 0) {
        VocabularySync.insert { ... }
    }
}
```

**Как исправить — один UPSERT:**
```kotlin
// Exposed поддерживает upsert через raw SQL или через insertIgnore + update
fun sync(...): List<VocabularyWord> = transaction {
    // Вариант 1: batch upsert через raw SQL (SQLite)
    val upsertSql = """
        INSERT INTO vocabulary_sync (id, user_id, word, ..., last_updated)
        VALUES (?, ?, ?, ..., ?)
        ON CONFLICT(id) DO UPDATE SET
            word = excluded.word,
            last_updated = excluded.last_updated,
            ...
    """
    // Вариант 2: разделить на bulkInsert и bulkUpdate по ID
    val existingIds = VocabularySync
        .select { VocabularySync.id inList clientChanges.map { it.id } }
        .map { it[VocabularySync.id] }.toSet()

    val toInsert = clientChanges.filter { it.id !in existingIds }
    val toUpdate = clientChanges.filter { it.id in existingIds }

    VocabularySync.batchInsert(toInsert) { word -> /* маппинг */ }
    toUpdate.forEach { word -> VocabularySync.update(...) }
}
```

---

## 🟡 Стоит улучшить

---

### 8. Детерминированные ID для субтитров

**Файл:** `SrtParser.kt`

```kotlin
// ❌ Каждый парсинг = новые UUID — нельзя ссылаться на конкретный момент
id = UUID.randomUUID().toString()
```

Это ломает foreign key `subtitleLineId` в словаре: если контент перепарсить — все ссылки устаревают.

```kotlin
// ✅ Детерминированный ID из contentId + временной метки
id = "${contentId}_${startMs}_${endMs}"
// или UUID v5 (SHA-1 based):
id = UUID.nameUUIDFromBytes("$contentId:$startMs".toByteArray()).toString()
```

---

### 9. Hardcoded языки в модели (`textDe`, `textEn`)

**Файл:** `Models.kt`

```kotlin
// ❌ Имена полей — это немецкий и английский навсегда
data class SubtitleLine(
    val textDe: String,  // original
    val textEn: String?  // translation
)
```

Если завтра появится испанский контент или русский перевод — модель нужно переписывать.

```kotlin
// ✅ Независимо от языка
data class SubtitleLine(
    val originalText: String,   // язык контента
    val translationText: String? // перевод
)

// Язык хранить в ImmersionContent:
data class ImmersionContent(
    val contentLanguage: Language = Language.DE,
    val translationLanguage: Language = Language.EN,
    ...
)

enum class Language { DE, EN, ES, RU, FR, ... }
```

---

### 10. Magic numbers в SM-2 алгоритме

**Файл:** `SrsEngine.kt`

```kotlin
// ❌ Что такое 0.1, 0.08, 0.02? SM-2 формула без объяснений
val newEaseFactor = max(
    MIN_EASE_FACTOR,
    word.easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02))
)
```

```kotlin
// ✅ Именованные константы с ссылкой на первоисточник
/**
 * SuperMemo-2 algorithm constants.
 * See: https://www.supermemo.com/en/blog/application-of-a-computer-to-improve-the-results-of-teaching
 */
private const val SM2_MAX_QUALITY = 5
private const val SM2_EF_BASE_MODIFIER = 0.1
private const val SM2_EF_LINEAR_MODIFIER = 0.08
private const val SM2_EF_QUADRATIC_MODIFIER = 0.02

val newEaseFactor = max(
    MIN_EASE_FACTOR,
    word.easeFactor + (SM2_EF_BASE_MODIFIER
        - (SM2_MAX_QUALITY - quality) * (SM2_EF_LINEAR_MODIFIER
        + (SM2_MAX_QUALITY - quality) * SM2_EF_QUADRATIC_MODIFIER))
)
```

---

### 11. `isLoading` застревает в `true` при ненайденном контенте

**Файл:** `PlayerViewModel.kt`

```kotlin
private fun loadContent(id: String) {
    scope.launch {
        _state.update { it.copy(isLoading = true) }
        val content = contentRepository.getContentById(id) ?: return@launch  // ❌ isLoading = true навсегда
        _state.update { it.copy(isLoading = false, content = content) }
    }
}
```

```kotlin
// ✅
val content = contentRepository.getContentById(id)
if (content == null) {
    _state.update { it.copy(isLoading = false, error = "Content not found: $id") }
    return@launch
}
_state.update { it.copy(isLoading = false, content = content) }
```

---

### 12. `getWordCountsByStatus()` возвращает `Map<String, Long>` вместо `Map<WordStatus, Long>`

**Файл:** `Repositories.kt`, `DashboardViewModel.kt`

```kotlin
// ❌ String-ключи — ошибка в имени = тихий баг (null вместо исключения)
wordsMastered = counts["MASTERED"] ?: 0,  // если написать "Mastered" — 0 без предупреждения
```

```kotlin
// ✅ Используй enum как ключ
fun getWordCountsByStatus(): Flow<Map<WordStatus, Long>>

// В репозитории:
.map { list ->
    list.associate { WordStatus.valueOf(it.status) to it.count }
}

// В DashboardViewModel:
wordsMastered = counts[WordStatus.MASTERED] ?: 0,
```

---

### 13. `BackendVocabularyRepository` без интерфейса

**Файл:** `BackendVocabularyRepository.kt`

Класс используется напрямую через DI и в роутинге. Без интерфейса невозможно написать unit-тесты для `Routing.kt` не поднимая БД.

```kotlin
interface VocabularySyncRepository {
    fun sync(userId: String, clientChanges: List<VocabularyWord>, lastSyncTimestamp: Long): List<VocabularyWord>
}

class ExposedVocabularySyncRepository : VocabularySyncRepository { ... }
```

---

### 14. Дублирование маппинга в `BackendVocabularyRepository`

Insert и Update содержат идентичные 13 строк маппинга полей:

```kotlin
// ✅ Вынести в extension-функцию
private fun UpdateStatement.applyWord(word: VocabularyWord, userId: String) {
    this[VocabularySync.userId] = userId
    this[VocabularySync.word] = word.word
    this[VocabularySync.lemma] = word.lemma
    // ... остальные поля
}

// Использование
VocabularySync.update({ VocabularySync.id eq word.id }) { it.applyWord(word, userId) }
VocabularySync.insert { it.applyWord(word, userId) }
```

---

### 15. JWT-логика разбросана по роутингу

**Файл:** `Routing.kt`

```kotlin
// ❌ JWT-секрет читается прямо в route-функции
val jwtSecret = config.property("jwt.secret").getString()
// + generateToken() — приватная top-level функция

// ❌ Magic number: 3600000 * 24 — что это?
.withExpiresAt(Date(System.currentTimeMillis() + 3600000 * 24))
```

```kotlin
// ✅ Выделить в JwtService
class JwtService(config: ApplicationConfig) {
    private val secret = config.property("jwt.secret").getString()
    private val issuer = config.property("jwt.issuer").getString()
    private val audience = config.property("jwt.audience").getString()

    companion object {
        private val TOKEN_VALIDITY = Duration.ofHours(24)
    }

    fun generateToken(email: String): String = JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("email", email)
        .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_VALIDITY.toMillis()))
        .sign(Algorithm.HMAC256(secret))

    fun extractEmail(principal: JWTPrincipal): String? =
        principal.getClaim("email", String::class)
}
```

---

### 16. `YtdlpInfo.webpage_url` — нарушение Kotlin naming conventions

**Файл:** `YtdlpClient.kt`

```kotlin
// ❌ snake_case в Kotlin data class
data class YtdlpInfo(
    val webpage_url: String? = null
)

// ✅ @SerialName для маппинга JSON
@Serializable
data class YtdlpInfo(
    val id: String? = null,
    val title: String? = null,
    val duration: Long? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null
)
```

---

## 🟢 Мелочи

- `VocabularyWord.nextReviewMs` ↔ DB-колонка `nextReviewAt` — несогласованное именование (ms vs At)
- `commonModule` в `Koin.kt` хардкодит `MockLinguisticAssistant()` — в продакшне нужен real HTTP client → backend
- `DashboardViewModel.loadStats()` загружает полный список `getWordsToReview()` только ради `.size` — это загружает все данные ради одного числа; лучше добавить `getReviewCount(): Flow<Int>` в репозиторий
- `BaseViewModel` — `onCleared()` нигде не вызывается автоматически, нет документации когда это делать на каждой платформе
- `MockLinguisticAssistant.translateWord` возвращает `word.lowercase()` как lemma — минимально, но вводит в заблуждение в тестах

---

## GoF-паттерны: анализ и рекомендации

### ✅ Хорошо применены

| Паттерн | Где | Оценка |
|---|---|---|
| **Strategy** | `LinguisticAssistant` + `GeminiLinguisticAssistant` / `MockLinguisticAssistant` | Отлично — можно менять реализацию AI без изменения кода |
| **Repository** | `ContentRepository`, `VocabularyRepository` интерфейсы | Правильное разделение домена и инфраструктуры |
| **Observer** | `StateFlow` / `Flow` во всех ViewModels | Реактивное UI, правильный подход для KMP |
| **Facade** | `IngestContentUseCase` — скрывает 3 шага за одним вызовом | Чисто |

### ⚠️ Применены, но есть проблемы

| Паттерн | Где | Проблема |
|---|---|---|
| **Singleton** | `SrsEngine object` | Нельзя замокировать, нарушает DIP |
| **Singleton** | `SrtParser object` | Приемлемо для stateless, но логгер внутри object неоптимален |

### 💡 Рекомендуется добавить

**Decorator** — кеширование AI-ответов:
```kotlin
// Один и тот же запрос "перевести слово X в контексте Y" 
// не должен идти к Gemini 10 раз
class CachingLinguisticAssistant(
    private val delegate: LinguisticAssistant,
    private val cache: MutableMap<String, TranslationResult> = ConcurrentHashMap()
) : LinguisticAssistant {

    override suspend fun translateWord(word: String, context: String): TranslationResult {
        val key = "$word|${context.take(50)}"
        return cache.getOrPut(key) { delegate.translateWord(word, context) }
    }
    // translateSentence, analyzeSentence — аналогично
}

// В BackendModule:
single<LinguisticAssistant> {
    CachingLinguisticAssistant(GeminiLinguisticAssistant(apiKey, get()))
}
```

**Chain of Responsibility** — обработка ошибок в роутинге:
```kotlin
// Вместо catch<Exception> { ... } для всего
// Цепочка: IngestionException → AiException → AuthException → Generic
```

**Template Method** — парсинг субтитров:
```kotlin
// SRT и VTT отличаются только форматом блоков — логика парсинга общая
abstract class SubtitleParser {
    fun parse(contentId: String, content: String): List<SubtitleLine> {
        return splitIntoBlocks(content)
            .mapNotNull { parseBlock(contentId, it) }
    }
    protected abstract fun splitIntoBlocks(content: String): List<String>
    protected abstract fun parseBlock(contentId: String, block: String): SubtitleLine?
}

class SrtParser : SubtitleParser() { ... }
class VttParser : SubtitleParser() { ... }
```

**Factory Method** — создание `LinguisticAssistant`:
```kotlin
// Вместо if/else в backendModule
interface LinguisticAssistantFactory {
    fun create(config: ApplicationConfig): LinguisticAssistant
}

class GeminiAssistantFactory(private val httpClient: HttpClient) : LinguisticAssistantFactory {
    override fun create(config: ApplicationConfig): LinguisticAssistant {
        val apiKey = config.propertyOrNull("gemini.api_key")?.getString()
        return if (apiKey.isNullOrBlank()) MockLinguisticAssistant()
               else GeminiLinguisticAssistant(apiKey, httpClient)
    }
}
```

---

## Бизнес-логика: рекомендации по развитию

### 1. Улучшение SRS-движка: 4-кнопочная оценка вместо шкалы 0-5

Текущая шкала 0–5 не подходит для мобильного UX. Anki и большинство современных SRS-приложений используют 4 кнопки:

```kotlin
enum class ReviewQuality(val sm2Value: Int) {
    AGAIN(0),   // Полностью забыл — сброс
    HARD(3),    // Вспомнил с трудом
    GOOD(4),    // Нормально
    EASY(5)     // Сразу вспомнил — ускорить интервал
}

// В SrsEngine добавить трекинг "провалов" (lapses)
data class SrsResult(
    val updatedWord: VocabularyWord,
    val wasLapse: Boolean  // для статистики и адаптивного обучения
)
```

### 2. Vocabulary Deck: теги и группировки

Сейчас слова хранятся плоским списком. Для удобного обучения нужны:

```kotlin
data class VocabularyWord(
    ...
    val tags: List<String> = emptyList(),    // "A2", "reflexive_verbs", "from_video_xyz"
    val lapseCount: Int = 0,                 // сколько раз забывал
    val reviewCount: Int = 0,               // всего повторений
    val firstSeenAt: Long = 0
)
```

### 3. Content Ingestion Pipeline: явные состояния обработки

Текущий `DownloadStatus` слишком груб. Пользователю непонятно "что сейчас происходит":

```kotlin
enum class ContentProcessingStatus {
    IDLE,
    FETCHING_METADATA,      // yt-dlp --dump-json
    DOWNLOADING_AUDIO,      // скачивание .opus
    PARSING_SUBTITLES,      // парсинг .srt
    GENERATING_WAVEFORM,    // генерация waveform (если добавить)
    READY,
    ERROR
}
```

### 4. Offline-first очередь синхронизации

Сейчас синхронизация — это разовый запрос без устойчивости к сбоям. Правильная модель:

```kotlin
// Локально сохранять "pending changes"
data class PendingSyncChange(
    val wordId: String,
    val changeType: ChangeType,  // UPSERT, DELETE
    val timestamp: Long
)

// SyncManager наблюдает за сетью и применяет изменения при появлении связи
class SyncManager(
    private val localRepository: VocabularyRepository,
    private val remoteApi: VocabularySyncApi,
    private val networkMonitor: NetworkMonitor
) {
    fun startSync() {
        networkMonitor.isOnline
            .filter { it }
            .onEach { performSync() }
            .launchIn(scope)
    }
}
```

### 5. AI Rate Limiting и Debouncing в плеере

Сейчас каждый клик по слову — немедленный запрос к Gemini. При быстром скролле субтитров это создаёт шторм запросов:

```kotlin
// В PlayerViewModel: debounce для lemmatization/translation
private val translationRequests = MutableSharedFlow<Pair<String, SubtitleLine>>()

init {
    translationRequests
        .debounce(300.milliseconds)  // ждём 300ms после последнего клика
        .onEach { (word, line) -> performTranslation(word, line) }
        .launchIn(scope)
}

fun onEvent(event: PlayerEvent) {
    when (event) {
        is PlayerEvent.WordClicked -> translationRequests.tryEmit(event.word to event.line)
        ...
    }
}
```

### 6. Waveform — данные есть, генерации нет

В модели `ImmersionContent` есть поле `waveform: List<Float>`, но нигде в коде оно не заполняется при ingestion. Нужно добавить шаг генерации в `YouTubeIngestionService` или вынести это в фоновый процесс. Без этого WaveformVisualizer всегда будет показывать пустой экран.

---

## Итоговые приоритеты

| Приоритет | Что делать |
|---|---|
| 🔴 **Немедленно** | Исправить хеширование паролей (`bcrypt`) |
| 🔴 **Немедленно** | Убрать API-ключ из URL (перенести в header) |
| 🔴 **Sprint 1** | Реализовать `sync()` на клиенте или убрать из интерфейса |
| 🔴 **Sprint 1** | Исправить N+1 в `BackendVocabularyRepository.sync()` |
| 🟡 **Sprint 2** | Разделить `ContentRepository` по ISP |
| 🟡 **Sprint 2** | Вынести `SrsEngine` за `object`, сделать инжектируемым |
| 🟡 **Sprint 2** | Добавить `JwtService`, убрать JWT-логику из роутинга |
| 🟡 **Sprint 3** | Детерминированные ID субтитров |
| 🟡 **Sprint 3** | Декоратор-кеш для `LinguisticAssistant` |
| 🟢 **Backlog** | Расширить `WordStatus` / добавить теги и lapseCount |
| 🟢 **Backlog** | Offline-sync очередь |
| 🟢 **Backlog** | Заполнение `waveform` при ingestion |
