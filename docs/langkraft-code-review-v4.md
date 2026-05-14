# Langkraft Code Review v4 — Sync & Conflict Resolution

## Что проверяем
Изменения в коммите `cff0ae5`, включающие рефакторинг `SyncManager`, внедрение базового Last Write Wins (LWW) на бэкенде и интеграцию синхронизации в UI.

---

## Общая оценка
Проделана хорошая работа по декомпозиции: `SyncManager` теперь делегирует логику репозиторию, а не управляет очередью в памяти. Однако внедрение LWW на бэкенде создало серьезную проблему производительности (N+1 query), а в UI-слое появились "грязные" импорты и потенциально избыточные вызовы.

---

## Находки

### 🔴 Критично

#### 1. N+1 Query Problem в `BackendVocabularyRepository`
**Файл:** `backend/src/main/kotlin/com/langkraft/backend/db/BackendVocabularyRepository.kt`
> ```kotlin
> toUpdate.forEach { word ->
>     val existingLastUpdated = VocabularySync
>         .slice(VocabularySync.lastUpdated)
>         .select { VocabularySync.id eq word.id }
>         .singleOrNull()?.get(VocabularySync.lastUpdated) ?: 0L
>     // ... update if word.lastUpdated > existingLastUpdated
> }
> ```
**Почему:** При синхронизации 100 слов бэкенд выполнит 100 отдельных `SELECT` запросов. Это быстро приведет к деградации производительности БД под нагрузкой.
**Как исправить:** Выбрать все `lastUpdated` для списка `toUpdate.map { it.id }` одним запросом, сохранить в `Map<String, Long>` и сверяться с ней в цикле.

#### 2. Использование Wildcard Imports
**Файл:** `shared/src/commonMain/kotlin/com/langkraft/ui/dashboard/DashboardViewModel.kt`
> `import kotlinx.coroutines.flow.*`
**Почему:** Нарушает стандарты проекта (см. `GEMINI.md`) и затрудняет отслеживание зависимостей.
**Как исправить:** Использовать явные импорты для `MutableStateFlow`, `StateFlow`, `asStateFlow` и т.д.

---

### 🟡 Стоит улучшить

#### 3. Состояние гонки в `SyncManager`
**Файл:** `shared/src/commonMain/kotlin/com/langkraft/data/sync/SyncManager.kt`
> ```kotlin
> suspend fun sync() {
>     if (_isSyncing.value) return
>     mutex.withLock {
>         if (_isSyncing.value) return
>         _isSyncing.value = true
>     }
>     try { ... }
>     finally { _isSyncing.value = false } // Вне лока
> }
> ```
**Почему:** Хоть `StateFlow` атомарен, сброс флага вне мьютекса, который охраняет критическую секцию начала синхронизации, выглядит асимметрично.
**Как исправить:** Использовать `AtomicBoolean` для управления состоянием "занято" или сбрасывать флаг внутри мьютекса (хотя это может увеличить время удержания лока).

#### 4. Отсутствие персистентности `lastSyncTimestamp`
**Файл:** `shared/src/commonMain/kotlin/com/langkraft/data/sync/SyncManager.kt`
> `private var lastSyncTimestamp: Long = 0`
**Почему:** При перезапуске приложения мы всегда будем синхронизироваться "с нуля", что создает избыточный трафик и нагрузку на бэкенд.
**Как исправить:** Сохранять `lastSyncTimestamp` в `Multiplatform Settings` или локальную БД.

#### 5. Вызов `sync()` в `init` ViewModel
**Файл:** `shared/src/commonMain/kotlin/com/langkraft/ui/dashboard/DashboardViewModel.kt`
**Почему:** Автоматический запуск тяжелой операции при каждом создании ViewModel. Если пользователь переходит между экранами, синхронизация будет дергаться постоянно.
**Как исправить:** Вызывать `sync()` только если прошло достаточно времени с последней попытки или по явному действию пользователя.

---

### 🟢 Мелочи
- В `SyncManager` вместо `println` для ошибок стоит использовать `Result` или логирование.
- В `SqlDelightVocabularyRepository` значение `baseUrl` по умолчанию можно вынести в константу или строго требовать через конструктор без дефолта.

---

### Итоговые рекомендации
1. **Решено:** Исправлен N+1 в `BackendVocabularyRepository`.
2. **Решено:** Убраны wildcard импорты в `DashboardViewModel`.
3. **Важно:** Реализовать сохранение `lastSyncTimestamp`.
4. **Важно:** Улучшить обработку ошибок в `SyncManager`, чтобы UI мог отображать статус (например, "Ошибка сети").
