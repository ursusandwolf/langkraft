# Исторические реализации main

Снимки сохранены из `945ab3533a0b067041974680be5b6e216f220b17` при ручной интеграции 22 октября 2026. Пути относительно этого каталога повторяют исходные. Код здесь не входит в компиляцию и не является инструкцией запуска.

Синхронный `POST /api/ingest`, связанный с HTTP disconnect, заменён постоянными заданиями dev: принятый job продолжает работу после закрытия запроса. Сохраняются admission control, проверка URL, отмена при остановке сервиса и завершение дерева процессов. Старый `YtDlpRunner` заменён `ExecutableProcessRunner`.

| Исторические файлы | Действующая замена / проверка |
| --- | --- |
| ClientDisconnect, YtDlpRunner и их тесты, IngestionTest | Backend ProcessRunnerTest, IngestionAdmissionTest, IngestionJobTest, BackendHttpErrorsTest |
| ContentApi, ContentHttpClient, ContentApiTest | HttpRemoteContentSource / HttpAudioDownloader; AudioDownloaderTest, AuthAndTwoClientSyncTest |
| IngestContentUseCaseTest, IngestionRepositoryTest | IngestCancellationTest, MigrationAndRepositoryTest, браузерный web-app.test.js; загрузка offline запускается пользователем отдельно |
| RepositoryTest, SubtitleMigrationTest, PersistenceTest | MigrationAndRepositoryTest, SyncRaceTest; обе исходные схемы → v3, связи, SRS, порядок реплик, backup и повторное открытие |
| PlayerViewModelTest | PlayerRaceTest: поздние ответы, смена контента, повторное сохранение, ошибка и повтор, устаревшая ошибка аудио |
| WebStorageTest, migration.test.js | browser.test.js и web-app.test.js: настоящий SQLite worker/IndexedDB, rollback, FK, аудио, reload/offline; старые Kotlin fixtures требуют нового DI |
| Второй Web Main и NativeUrlInput | Единственный webApp/src/jsMain/kotlin/Main.kt и общий NativeUrlInput expect/actual |
| AndroidDatabaseFactory | Android target отключён; его адаптация и регресс остаются в backlog |
| youtube-live.test.js, youtube-cancel.test.js | Активный opt-in youtube-live.test.js проверяет job polling, waveform и HTTP Range MP3; отмена HTTP не отменяет принятый job |

Строгие проверки SRT перенесены в активный `StrictSrtParserTest` с `originalText/translationText`. Проверка отказа raw VTT адаптирована к поддерживаемому VTT-конвертеру. Поминутное обновление SRS и проверки сохранения/перевода перенесены в действующий код.

Это карта замены, а не утверждение о полном совпадении тестового покрытия. Исторические README/UML/todo оставлены для сопоставления решений; актуальные инструкции находятся в корневом README и docs/CHANGELOG.md.
