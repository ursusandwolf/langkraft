# Решения ручного слияния main и dev — 2026-10-22

База: dev `3003225`. Сторона main: `945ab35`. После предварительного переноса исправлений слияние выявило 43 конфликтующих файла. Конфликты разобраны по подсистемам; массовое принятие одной стороны не выполнялось.

| Область | Итог |
| --- | --- |
| Build | Kotlin 2.0.21/Compose 1.7, async SQLDelight; из main добавлены installed Node, входные файлы локального storage package и Yarn --force. Lock обновлён под итоговый стек. Сохранён проверенный wrapper Gradle 8.14 |
| Backend | Постоянные async jobs dev, JWT/sync, строгий parser main, URL whitelist, bounded queue, управляемые процессы. MP3 и PartialContent 206 |
| Data | Общая v3 мигрирует обе схемы, сохраняет SRS/tags/sync и делает backup JVM БД. UPDATE/INSERT вместо REPLACE, стабильный ordinal и безопасные FK. Поминутное обновление due words из main |
| Audio | Потоковое файловое/IndexedDB хранилище, проверка ответа, удаление не привязанного к БД файла. Web fresh element, generation и request ID. Скорость Desktop/TUI сохранена |
| Player | Типизированный TranslationResult dev + стабильный ID слова, save guard, ошибка/повтор main. Отмена/поколения загрузки и перевода исключают поздние результаты. Offline download не переключает другой открытый контент |
| Web | Общий MainApp, единственная точка входа, native URL input и webApp.js. Тест ждёт готовности Compose и появления поля вместо фиксированного времени первого клика |
| Docs | README, канонический docs/CHANGELOG.md, UML-журнал и спринты отражают интеграцию. Исходные отчёты ревью сохранены |

Несовместимые исходные варианты и тестовые fixtures main сохранены в [архиве с картой замен](archive/main-reference/README.md). История main сохраняется вторым родителем merge-коммита; force push не требуется.

Граница проверки: Windows блокирует ffplay политикой Application Control, VLC отсутствует. Desktop/TUI проверяются smoke; реальный звук подтверждён в Chromium. Gemini с платным ключом и Android остаются отдельными проверками. Квоты media, координация вкладок и очистка crash leftovers остаются backlog, а не скрытыми завершёнными задачами.
