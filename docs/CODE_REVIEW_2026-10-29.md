# Кодревью Langkraft — 2026-10-29

## Вердикт

Подтверждённые ниже дефекты исправлены точечными изменениями и покрыты регрессионными
тестами. Следующий этап — локальная Ollama через существующий Strategy-контракт;
реализация провайдера в это ревью не входит. [План работ](ARCHITECTURE_PLAN_2026-10-29.md).

## Область проверки

Проверено рабочее дерево, включая уже существовавшие незакоммиченные AI Strategy,
AI-тесты и NativeTextInput. Эти исходные изменения сохранены.

Глубокое чтение: backend auth/sync/routes, AI composition/cache, ingestion/process/media,
SQLDelight repositories и схема, UserSession/SyncManager, Player/Writing/SRS view models.
Выборочное чтение: browser SQL driver, lifecycle/recovery, DI, Gradle и CI.
Архив `docs/archive` не рассматривался как актуальная реализация.

Карта исполнения:

```text
Compose Web/Desktop и TUI → shared use cases/repositories
  → SQLDelight + platform audio storage
  → Ktor HTTP client → backend routes
    → auth/sync → Exposed/SQLite
    → ingestion workers → yt-dlp/ffmpeg → managed media directory
    → LinguisticAssistant → cache → task prompts → provider Strategy
```

## Исправленные находки

### P1: JWT отправлялся стороннему аудио-серверу [чтение + регрессионный тест]

**Где:** `shared/src/commonMain/kotlin/com/langkraft/di/Koin.kt`,
`shared/src/commonMain/kotlin/com/langkraft/data/sync/BackendAuthorization.kt`.
Общий HTTP client добавлял bearer token ко всем запросам, а AudioDownloaderImpl
принимает абсолютные audio URL. При внешнем URL credentials могли попасть третьей стороне.

**Исправление:** отдельный client plugin добавляет текущий токен только при совпадении
схемы, host и port с backend. Проверка выполняется на стадии onRequest с собранным URL.
**Проверка:** `BackendAuthorizationTest` — смена/удаление токена, внешние host,
protocol/port и перенаправление на другой origin.

### P1: смешивание данных при смене аккаунта [чтение + регрессионный тест]

**Где:** `shared/src/commonMain/kotlin/com/langkraft/data/sync/UserSession.kt`.
Раньше вход заменял только токен, оставляя общий словарь, PendingSyncChange и
last_sync_timestamp. После A → logout → B записи A могли быть отправлены от B,
а курсор A — скрыть часть изменений B.

**Исправление:** постоянный владелец локального профиля (backend URL + email),
запрет смены владельца, сохранение владельца при logout. Старый JWT используется
только для восстановления локальной привязки по email; это не заменяет проверку JWT сервером.
Неизвестное владение непустым профилем блокирует привязку без удаления данных.
Смена токена в SQLite и in-memory state выполняется без разрыва отменой coroutine;
операции сессии сериализованы mutex. Совместимость профиля проверяется до HTTP-запроса,
чтобы запрещённая локальная привязка не создавала удалённого пользователя при регистрации,
и повторно проверяется перед сохранением ответа.

**Проверка:** `UserSessionTest` — запрет B, сохранность слова/курсора/токена, повторный вход A,
миграция старого токена и отказ при неизвестном владельце.

**Ограничение:** это защитная блокировка, а не переключатель профилей. В частности,
гостевой словарь без подтверждённого владельца теперь нельзя автоматически загрузить
в аккаунт. Нужен отдельный сценарий осознанного связывания/миграции из плана.
Исторически смешанные записи автоматически разделить невозможно.

### P2: коллизия ключа перевода [чтение + регрессионный тест]

**Где:** `backend/src/main/kotlin/com/langkraft/backend/ai/CachingLinguisticAssistant.kt:21`.
Ключ `"$word|$context"` одинаков для `(a|b, c)` и `(a, b|c)`.
Второй запрос получал ответ для другого входа.

**Исправление:** структурный ключ `Pair<String, String>`.
**Проверка:** `CachingLinguisticAssistantTest` подтверждает разные ответы и cache hit
при повторе той же пары.

### P2: устаревший грамматический разбор в Player [чтение + регрессионный тест]

**Где:** `shared/src/commonMain/kotlin/com/langkraft/ui/player/PlayerLinguisticDelegate.kt:39`.
Проверялось только поколение контента. Два запроса по разным строкам одного видео
могли завершиться в обратном порядке; закрытие диалога не отменяло результат.

**Исправление:** отдельные Job и generation для deep analysis, отмена при новом
запросе/закрытии/смене контента, проверка поколения перед публикацией результата.
**Проверка:** `PlayerRaceTest.latestDeepAnalysisWinsAndDismissalIgnoresLateResponse`.

### P2: некорректный ввод превращался в 500 [чтение + HTTP-тест]

**Где:** `backend/src/main/kotlin/com/langkraft/backend/Routing.kt:125`.
Общий обработчик Exception перехватывал ошибки десериализации и `require` как
внутреннюю ошибку сервера.

**Исправление:** отдельные 400 для BadRequestException и IllegalArgumentException,
без передачи внутренних подробностей.
**Проверка:** `IngestionHttpErrorsTest` — повреждённый JSON и неверные параметры.
Дальнейшее улучшение: типизированные validation errors вместо общей IllegalArgumentException.

### P1: подробности внутренних исключений в HTTP [чтение + HTTP-тест]

**Где:** `backend/src/main/kotlin/com/langkraft/backend/Routing.kt:151`.
Текст любого исключения отправлялся клиенту и мог раскрыть путь БД или другие
внутренние данные.

**Исправление:** общий безопасный ответ 500; исключение пишется в серверный лог.
Отмена coroutine продолжает пробрасываться.
**Проверка:** искусственная внутренняя ошибка не раскрывает её сообщение клиенту.

### P2: отсутствие проверки auth-ввода [чтение + HTTP-тест]

**Где:** `backend/src/main/kotlin/com/langkraft/backend/AuthService.kt:41`.
Пустые поля могли попадать в БД; пароль длиннее ограничения BCrypt приводил к ошибке.

**Исправление:** проверка email, непустых пароля/display name, длины строк и
ограничения BCrypt в 72 UTF-8 байта до хэширования/проверки. Пароль не обрезается.
**Проверка:** `AuthAndTwoClientSyncTest` — пустые поля и 37 двухбайтовых символов
в пароле возвращают 400; корректные register/login/sync продолжают работать.

### P2: гонка регистрации одного email [чтение + тест конфликта insert]

**Где:** `backend/src/main/kotlin/com/langkraft/backend/db/BackendUserRepository.kt:12`.
Между findByEmail и INSERT другой запрос мог создать пользователя. UNIQUE conflict
превращался в 500 вместо 409.

**Исправление:** атомарный `insertIgnore` с проверкой insertedCount и возвратом
конфликта в AuthService. Предварительная проверка остаётся оптимизацией, а не гарантией.
**Проверка:** `BackendUserRepositoryTest` — повторный insert возвращает null и сохраняет
исходный ID/hash; HTTP-тест подтверждает 409 при повторной регистрации.

### P2: backend HTTP client не закрывался [чтение]

**Где:** `backend/src/main/kotlin/com/langkraft/backend/Application.kt`.
Singleton CIO client создавался в DI без явного завершения его ресурсов.
**Исправление:** сохранить ссылку при bootstrap и закрывать client на ApplicationStopped.
Ссылка захватывается до остановки Koin. Проверено повторным прогоном backend testApplication.

## Архитектурные замечания

| Приоритет | Наблюдение по исходникам | Следующий шаг |
| --- | --- | --- |
| P1 | Один локальный словарь/курсор; теперь защищён привязкой владельца | Профили и явная гостевая миграция |
| P1 | Shared HTTP timeout 30 секунд, backend AI timeout 60 секунд | Согласовать бюджеты до локального inference |
| P1 | AI-кэш ограничен количеством записей, но не размером/TTL; одинаковые запросы не объединены | Ограничить объём и число генераций, добавить single-flight |
| P2 | Backend зависит от всего shared с Compose и desktop dependencies | Выделить contracts |
| P2 | Backend schema меняется через createMissingTablesAndColumns в DI | Нумерованные проверяемые миграции |
| P2 | Ingestion и media публичные; auth не ограничивает частоту попыток | Определить режим развёртывания и его права/лимиты |
| P2 | AI capabilities отражает выбор провайдера, UI проверяет доступность при загрузке | Разделить configured/available и добавить повторную проверку |

Полезные существующие решения: порты LinguisticAssistant/ProcessRunner, server sync cursor,
условное удаление подтверждённых локальных изменений, leases и дисковые бюджеты media,
сохранение исходного профиля при recovery. Их следует сохранить при изменении границ модулей.

## Проверки

- `:shared:desktopTest`: 112 тестов, без ошибок.
- `:backend:test`: 76 тестов, без ошибок, один symlink-тест пропущен из-за отсутствия Windows privilege.
- `:shared:jsBrowserTest`: 44 теста, без ошибок.
- `:webApp:jsBrowserDevelopmentWebpack`: сборка успешна.
- `npm test` в tools/web-storage-tests: storage/recovery сценарии пройдены.
- `npm run test:app` и `npm run test:manual`: приложение, ручной перевод,
  ошибка/задержка AI и recovery в настоящем браузере.
- Python recovery unittest: 9 тестов, без ошибок.

Результаты относятся к рабочему дереву этого ревью, включая исходные незакоммиченные файлы.

## Что не проверялось

Реальные Gemini/Claude/OpenAI и Ollama, скачивание с YouTube, качество модели,
нагрузка/длительный soak, production deployment и аудит CVE зависимостей.
Android target выключен и не собирался; production Web bundle отдельно не проверялся.
Пропуск платформенного теста и предупреждения Gradle/Compose не считаются подтверждением
работы соответствующих непроверенных сценариев.
