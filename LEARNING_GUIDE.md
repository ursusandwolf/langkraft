# Навчальний гід по коду Langkraft

Мета: за 1–2 години зрозуміти, як читати й змінювати проєкт без ламання offline/sync.

## 1. Що робить продукт

Користувач імпортує YouTube → отримує аудіо+субтитри → слухає з плеером → зберігає слова → SRS (SM-2) → sync словника через JWT. Offline-копія аудіо окрема від серверного імпорту.

## 2. Маршрут навчання (порядок файлів)

| Крок | Файл | Що зрозуміти |
| --- | --- | --- |
| 1 | `AI_MAP.md` | полиці модулів |
| 2 | `domain/model/Models.kt` | ImmersionContent, SubtitleLine, VocabularyWord |
| 3 | `domain/repository/Repositories.kt` | порти без реалізації |
| 4 | `domain/usecase/IngestContentUseCase.kt` | оркестрація remote→local |
| 5 | `domain/srs/SrsEngine.kt` | Strategy SM-2 |
| 6 | `data/repository/BackendRemoteSource.kt` | polling job |
| 7 | `data/repository/SqlDelight*Repository.kt` | Flow + DatabaseAccess |
| 8 | `data/audio/*` + `AudioDownloaderImpl.kt` | найскладніша зона ownership |
| 9 | `ui/BaseViewModel.kt` + `player/*` | MVVM + делегати |
| 10 | `di/Koin.kt` + platform modules | збірка графа |
| 11 | `backend/Routing.kt` → `YouTubeIngestionService.kt` | серверний конвеєр |
| 12 | `backend/ai/*` | Decorator cache + Gemini Strategy |

## 3. Ключові ідеї

### Порт ≠ реалізація

`LocalContentRepository` — контракт. `SqlDelightContentRepository` — SQLDelight. UI залежить лише від порту → тести підміняють fakes.

### DatabaseAccess

Усі SQL через один mutex/`run`. Не викликати SQLDelight напряму з UI або з другого потоку без access — ризик гонок і «lazy поза транзакцією».

### Offline audio — три власники

1. Storage тримає файл/chunks до повернення location.  
2. Downloader тримає до SQL link.  
3. БД — після успішного link.  
Компенсація видаляє лише **новий** об’єкт без SQL-посилань.

### AI

Клієнт: `RemoteLinguisticAssistant` + JWT. Сервер: Gemini або Mock, обгорнутий `CachingLinguisticAssistant`. Ключ не їде на клієнт.

## 4. Вправи (без зміни прод-логіки)

1. Простеж `POST /api/ingest` → jobId → `GET /api/ingest/{id}` у клієнті.  
2. Знайди, де ставиться `DownloadStatus.DOWNLOADING` і де знімається.  
3. Намалюй послідовність sync: PendingSyncChange → SyncManager → BackendVocabularyRepository.  
4. Додай unit-тест на `Sm2Algorithm` для quality AGAIN/HARD/GOOD/EASY (якщо ще немає кейсу).

## 5. Антипатерни, яких уникати тут

- Новий «God» ViewModel замість делегата.
- Видалення IndexedDB/файлів без lease-перевірки.
- Локальний mock AI при помилці мережі (свідомо заборонено після REL-S4).
- Прямий `ProcessBuilder` поза `ProcessRunner`.

## 6. Куди далі

Поглиблення: `docs/DEVELOPER_GUIDE.md`, ADR у `docs/`, UML у `docs/UML_CLASSES.md`. Карта для ШІ: `AI_MAP.md`.
