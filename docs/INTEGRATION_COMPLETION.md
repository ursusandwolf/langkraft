# Проверка интеграции — 22 октября 2026

Рабочая ветка: `integration/dev-base-20261021`, база dev `3003225`, включается история main `945ab35`. Коммиты до ручного merge: `ecf787a`, `ef58d01`, `a2f3691`.

Локально подтверждены JVM-тесты, Web development UI/storage/offline и реальный YouTube импорт с Range 206. Дополнительный регресс player и production-сборка проверяются перед публикацией merge-коммита.

Первый удалённый CI [39693515339](https://github.com/ursusandwolf/langkraft/actions/runs/39693515339): JVM и Desktop/TUI smoke прошли, Web storage прошёл, UI упал до появления поля URL. Добавлены ожидание инициализации Compose и повтор навигационного клика до появления native input. Новый CI должен пройти перед слиянием в dev/main.

Финальные результаты, хеши и PR будут добавлены после проверок. Незавершённые внешние проверки: Gemini с реальным ключом, Android, физический звук Desktop/TUI (Windows Application Control блокирует ffplay; VLC отсутствует). Offline Web проверен внутри загруженного приложения; service worker для запуска приложения полностью без сети не добавлен.
