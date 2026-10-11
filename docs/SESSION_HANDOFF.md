# Продолжение сессии Langkraft

Дата сохранения: 2026-10-11. Рабочая ветка: main. Каталог: F:/Projects/Kotlin/langkraft.

## Последний запрос пользователя

Проверить gitignore, проанализировать проект, затем устранить найденные проблемы.
Во время реализации пользователь попросил сделать коммит, сохранить состояние для продолжения и завершить сессию.
Работа остановлена по этому запросу. Это WIP checkpoint, а не завершённая реализация.

## Выполнено

- Расширен .gitignore: Gradle/Kotlin, сборки всех модулей, IDE, локальные настройки/ключи, медиа. Gradle Wrapper JAR разрешён.
- Составлен docs/CODE_REVIEW.md. Это отчёт о состоянии ДО исправлений; часть замечаний уже затронута WIP-патчем.
- Доменные модели, интерфейсы репозиториев, AI-контракт и use case перенесены из shared в core. Backend зависит от core.
- Модели помечены Serializable, добавлены IngestRequest и ApiError.
- Обновлены версии: Kotlin 2.2.20, Compose 1.9.3, SQLDelight 2.1.0, Ktor 3.3.0, AGP 8.7.3, Gradle 8.14.3.
- Созданы Gradle Wrapper, androidApp и webApp. Для проверки JVM/Web без Android SDK используется -PskipAndroid=true.
- Убран expect/actual класс плеера: общий интерфейс AudioPlayer внедряется в ViewModel, конкретный класс создаётся платформой.
- Переписаны Android/JS адаптеры аудио, добавлены события ошибок, время seek/pause и освобождение ресурсов.
- ViewModel теперь вызывает плеер, собирает StateFlow, обрабатывает ошибки и отменяет устаревшие переводы.
- Панель слова включена в UI; слова переносятся через FlowRow, границы субтитров полуоткрытые.
- UUID перенесён из UI в ViewModel с kotlin.uuid; отмена use case импорта больше не поглощается.
- SQL REPLACE заменён на INSERT OR IGNORE + UPDATE, добавлены запросы и транзакционные реализации репозиториев.
- Добавлены JVM/Android/Web фабрики БД; для Web написан SQL.js worker с сохранением SQLite-снимков в IndexedDB.
- Добавлены Ktor-клиент импорта, Android-хранилище аудио и браузерный Cache Storage для офлайн-аудио.
- Добавлен экран библиотеки с вводом backend/YouTube URL и импортом.
- Добавлены Ktor main, /health, POST /api/ingest, GET /api/media/{uuid}.opus, CORS, ошибки и partial content.
- Вместо отсутствующей YtdlpJava используется MediaExtractor с ProcessBuilder для установленного yt-dlp: отдельный UUID-каталог, timeout, отмена, метаданные, немецкие SRT, cleanup при неудаче.
- SRT-парсер проверяет структуру, BOM/CRLF, диапазоны и точные миллисекунды; добавлены четыре регрессионных теста.

## Проверки и реальные результаты

- git check-ignore проверен; исходники, .env.example/.env.sample и Wrapper JAR не игнорируются.
- Генерация Wrapper отдельным bootstrap-проектом завершилась BUILD SUCCESSFUL.
- Запущены :core:jvmTest :shared:jvmTest :backend:test. Процесс остановлен пользователем через запрос завершить сессию, до результата компиляции и тестов.
- На момент остановки лог содержал начало :core:kmpPartiallyResolvedDependenciesChecker и checkKotlinGradlePluginConfigurationErrors SKIPPED.
- Ни успешная компиляция приложения, ни прохождение тестов, ни runtime-сценарий НЕ подтверждены.
- Весь новый код требует следующего прохода компиляции и исправления выявленных ошибок.
- На диске есть локальные изменения служебных Gradle-файлов и старого отчёта, существовавшие до начала работы. Они не должны попадать в коммит как исходники.

## Инструменты в этом окружении

- JDK: C:/Users/Admin/.jdks/corretto-21.0.12.1.
- Загруженный Gradle: build/tools/gradle-8.14.3/bin/gradle.bat.
- Кеш текущей работы: build/gradle-home (игнорируется Git).
- Лог проверки: build/validation.log (игнорируется Git).
- Bootstrap и скачанный архив: build/tools (игнорируются Git).
- Android SDK не найден в стандартном каталоге, переменные ANDROID_HOME / ANDROID_SDK_ROOT не заданы.
- Установку yt-dlp / FFmpeg и реальный импорт YouTube ещё не проверяли.
- Команды Git в sandbox требуют git -c safe.directory=F:/Projects/Kotlin/langkraft.
- Для скачивания зависимостей потребовался запуск вне сетевого sandbox через require_escalated.
- Индекс Git защищён файловыми разрешениями; запись коммита требует соответствующего разрешения инструмента.

## Первые шаги при возобновлении

1. Проверить git status и последний WIP-коммит. Прочитать этот файл и docs/CODE_REVIEW.md; не считать прошлый статический отчёт текущим результатом тестов.
2. Запустить JVM-тесты и компиляцию:
   PowerShell:
   $env:JAVA_HOME='C:/Users/Admin/.jdks/corretto-21.0.12.1'
   $env:GRADLE_USER_HOME='F:/Projects/Kotlin/langkraft/build/gradle-home'
   & './build/tools/gradle-8.14.3/bin/gradle.bat' -PskipAndroid=true :core:jvmTest :shared:jvmTest :backend:test --console=plain
   Также доступен ./gradlew.bat; он скачает дистрибутив в свой wrapper-кеш, если его там ещё нет.
3. Проверить API SQLDelight async: awaitAsList/awaitAsOneOrNull, transaction и synchronous адаптер Android. Они ещё не подтверждены компилятором.
4. Проверить JS API аудио (HTMLAudioElement.play promise), ComposeViewport/Dialog и совместимость browser WebWorkerDriver.
5. Собрать :webApp:jsBrowserDistribution, затем проверить библиотеку/проигрывание/перевод/сохранение и восстановление БД/аудио после перезагрузки.
6. Проверить жизненный цикл AppServices и PlayerViewModel: не использовать освобождённый плеер, избежать повторного закрытия БД/клиента при смене экранов/pagehide.
7. Обеспечить тесты, которые ещё не написаны: сохранение Content без потери субтитров/ссылок, сохранение SRS при изменении слова, rollback транзакции, гонки переводов, ошибка/отмена загрузки и импорта, Ktor API и медиа.
8. Проверить yt-dlp адаптер через fake MediaExtractor и реальный yt-dlp/FFmpeg; заголовок Content-Type .opus должен соответствовать контейнеру, GET range должен работать.
9. Проверить Android сборку с установленным SDK 35. Сейчас JDK/байткод настроены на 21; оценить переход bytecode на 17 для совместимости Android при сохранении JDK 21.
10. Добавить CI, обновить README, PROJECT_CONTEXT.md и todo.md по фактической реализации. Они пока содержат старые версии/описания.
11. Довести остальные замечания из CODE_REVIEW.md, обновить статус каждого и сообщить реальные результаты тестов.

## Важные незавершённые детали

- MockLinguisticAssistant остаётся заглушкой; Gemini и полноценный SRS не входили в список срочных исправлений.
- При неудаче скачивания use case уже сохраняет remote content; UX повтора ещё требует доработки (повторный импорт сейчас создаёт другой id).
- IndexedDB worker использует собственный протокол по образцу официального SQLDelight SQL.js worker. Требуются browser-тесты ошибок сохранения, восстановления снимка, очереди сообщений и транзакций.
- При восстановлении Web БД повторяется Schema.create; DDL изменён на IF NOT EXISTS, миграции ещё не введены.
- JVM-фабрика пока удобна для in-memory тестов; сценарий открытия существующей файловой БД требует проверки.
- Не запускать git add .: build/ и .gradle/ раньше были отслеживаемыми; сначала проверить уборку индекса и добавлять исходники адресно.
- Публикация, push и развёртывание не выполнялись и не запрашивались.

Для продолжения достаточно попросить: «Продолжи устранение проблем Langkraft по docs/SESSION_HANDOFF.md».