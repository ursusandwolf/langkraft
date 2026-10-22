# Документация Langkraft

| Задача | Документ |
| --- | --- |
| Понять продукт и текущее состояние | [Корневой README](../README.md) |
| Установить зависимости и запустить Web/Desktop/TUI/backend | [RUNNING](RUNNING.md) |
| Разобраться в модулях, DI, сценариях, паттернах и правилах разработки | [DEVELOPER_GUIDE](DEVELOPER_GUIDE.md) |
| Посмотреть текущие классы, интерфейсы и связи | [UML_CLASSES](UML_CLASSES.md) |
| Изменить данные, миграции или offline storage | [DATA_LAYER](DATA_LAYER.md) |
| Настроить срок хранения backend-аудио | [ADR_BACKEND_MEDIA_RETENTION](ADR_BACKEND_MEDIA_RETENTION.md) |
| Разобраться в Web bootstrap/worker | [WEB_APP](WEB_APP.md) |
| Понять восстановление незавершённых Web/Desktop/TUI-загрузок | [ADR_AUDIO_DOWNLOAD_RECOVERY](ADR_AUDIO_DOWNLOAD_RECOVERY.md) |
| Восстановить старый профиль без изменения исходника | [LEGACY_RECOVERY](LEGACY_RECOVERY.md) |
| Проверить контракт субтитров | [SUBTITLES](SUBTITLES.md) |
| Узнать изменения и тестовые результаты | [CHANGELOG](CHANGELOG.md), [UML-журнал](CHANGELOG_UML.md) |
| Выбрать следующую задачу | [todo](todo.md), [план кодревью](CODE_REVIEW_FIX_PLAN.md) |
| Передать исходники на независимое ревью | [AI_CODE_REVIEW](AI_CODE_REVIEW.md), `python tools/review/Create-ReviewArchive.py` |
| Проверить историю интеграции dev/main | [INTEGRATION_COMPLETION](INTEGRATION_COMPLETION.md), [решения конфликтов](INTEGRATION_RESOLUTION.md) |

`UML_CLASSES` — актуальная карта программы; `CHANGELOG_UML` — хронология изменений. Исходные отчёты CODE_REVIEW и содержимое `archive` сохранены для сравнения, их прежние инструкции не заменяют RUNNING и текущие исходники. Снимки тестовых результатов в отчётах относятся к указанным этапам/коммитам.
