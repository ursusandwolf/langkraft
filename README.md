# Langkraft 🇩🇪

**Langkraft** (from German *Lang* + *Kraft* - long-term power/strength) is an immersion-first German language learning platform. Unlike traditional apps that focus on grammar drills, Langkraft centers the learning experience around **comprehensible input** and **listening**.

## Текущая стадия проекта — 21 октября 2026

**Проект находится на стадии разработки и стабилизации перед интеграцией веток. Готовность к выпуску не подтверждена.** В коде dev уже есть Desktop/TUI, асинхронный импорт YouTube, словарь, SRS, авторизация, синхронизация и интеграция Gemini с mock-режимом. Наличие реализации пока не означает, что все пользовательские сценарии проверены.

### Почему main и dev отличаются

Ветки развивались независимо после общего коммита `1831c5a`. На момент аудита в dev — 51 уникальный коммит, в main — 6. В dev сосредоточена расширенная функциональность, а main содержит отдельную Web-реализацию и исправления надёжности. Поэтому main сейчас не отражает весь объём разработки.

Основой дальнейшей интеграции выбрана **dev (`3003225`)**. Исправления из **main (`945ab35`)** предстоит адаптировать к её архитектуре. Пробное слияние выявило **29 конфликтующих файлов**, включая модели, БД, импорт и плеер. Автоматическое принятие одной стороны может потерять функциональность или исправления.

Для проверки создана отдельная ветка `integration/dev-base-20261021`. Слияние и публикация изменений в main/dev в рамках аудита не выполнялись.

### Проверки и прогресс интеграционной ветки

Исходная dev не проходила JS-компиляцию и 7 backend-тестов. В рабочей копии интеграционной ветки эти ошибки исправлены (INT-03); завершён перенос защиты аудиоответов и импорта (INT-04). Изменения ещё не слиты в dev/main.

| Область | Результат | Что это означает |
| --- | --- | --- |
| JVM-код shared и backend | Компиляция прошла | Подтверждена сборка кода, но не полный пользовательский сценарий |
| Shared-тесты на JVM | **28/28 успешно после INT-04** | Проверены sync, SRS, ViewModel, аудиоответы и отмена |
| Backend-тесты | **26/26 успешно после INT-04** | Проверены репозитории, очередь, HTTP-ошибки, SRT/VTT, waveform, timeout и отмена дерева процессов |
| JS-компиляция shared и webApp | **Проходит после INT-03** | Dispatcher вынесен в платформенные реализации, время берётся из kotlinx.datetime.Clock |
| Web-запуск и offline | **Не подтверждены** | Отсутствуют регистрации БД/плеера в Web DI; запись файлов в JS пока является заглушкой |
| Desktop/TUI и внешние сервисы | Полный регресс не выполнен | Запуск, реальный импорт, воспроизведение, Gemini и синхронизация двух клиентов требуют отдельных проверок |

В INT-04 очередь ограничена, процессы получили таймаут и обработку отмены, добавлены проверки аудиоответов и субтитров. Для выпуска ещё нужно подключить браузерное хранилище, согласовать миграции БД, исключить потерю связей словаря при повторном импорте и проверить восстановление заданий после перезапуска. Перед внешним развёртыванием требуется заменить фиксированный JWT secret конфигурационным секретом.

Текущие ограничения импорта: только HTTPS-ссылки на отдельные YouTube-видео с известной длительностью до 4 часов; по умолчанию принимаются до 12 незавершённых заданий (4 воркера + 8 ожидающих). Перегрузка возвращает HTTP 503 с Retry-After. yt-dlp ограничен 600 секундами, waveform ffmpeg — 120 секундами. Новые медиа сохраняются отдельно для каждого jobId, локальное аудио — MP3. Проверки MIME отбрасывают явный неаудиоконтент, но не проверяют декодирование файла.

### Ближайшие этапы

1. **Выполнено в интеграционной рабочей копии:** восстановить JS-компиляцию и backend-тесты (27/27 JVM-тестов).
2. **Выполнено в интеграционной рабочей копии:** адаптировать проверки HTTP/MIME, корректную отмену операций и защиту импорта из main (INT-04; всего 54 JVM-теста).
3. Согласовать миграции БД и браузерное хранилище, сохранив SRS, синхронизацию и Desktop/TUI.
4. Выполнить регресс платформ и пользовательских сценариев; затем интегрировать изменения в dev и подготовить слияние dev → main.

Четыре спринта и их статус: [roadmap](docs/todo.md). Подробные находки, порядок переноса и критерии допуска: [план интеграции](docs/INTEGRATION_PLAN_2026-10-21.md). Изменения фиксируются в [changelog](docs/CHANGELOG.md) и [текстовом UML-журнале](docs/CHANGELOG_UML.md). Результаты проверок main нельзя считать результатами проверок dev.

## 🚀 The Philosophy
- **Immersion First:** Language is acquired through interesting content, not memorized through rules.
- **Listening Centric:** The primary goal is to bridge the gap between "hearing noise" and "understanding meaning."
- **Context is King:** Every word you learn is tied to the exact audio fragment and sentence where you first encountered it.
- **Zero Friction:** Import content directly from YouTube and start learning instantly.

## 🛠 Tech Stack
- **Multiplatform UI:** [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) 1.7.0; Desktop UI and Lanterna TUI. Web requires the fixes described above; Android is currently excluded from the Gradle build.
- **Core Logic:** Kotlin Multiplatform (KMP).
- **Database:** [SQLDelight](https://cashapp.github.io/sqldelight/) (SQLite) for offline-first persistence.
- **Audio Engine:** VLCJ (Desktop), FFmpeg (TUI), and HTML5 Audio (Web implementation).
- **Backend:** Ktor Server for content ingestion.
- **Media Extraction:** [yt-dlp](https://github.com/yt-dlp/yt-dlp) integrated via a custom Java/Kotlin wrapper.
- **AI Layer:** Google Gemini backend integration with a mock fallback; end-to-end verification is pending.

## 🏗 Architecture
Langkraft follows **Clean Architecture** principles to ensure the learning logic remains independent of platforms and frameworks:

- **`:shared`**:
    - `commonMain`: Domain models, Repositories, UseCases, and Compose UI.
    - `desktopMain`/`jsMain`/`androidMain`: Platform-specific implementations; Android target is currently disabled.
- **`:backend`**: Ktor service for asynchronous YouTube ingestion (MP3 audio and subtitles), authentication, AI and vocabulary synchronization.
- **`:webApp`**: Web entry point; currently blocked by the issues above. `androidApp` sources exist but the module is not included in settings.

## 🗺 Roadmap
The current roadmap is the integration and stabilization sequence above. Existing ingestion, AI and SRS implementations must be preserved and verified while fixes are ported from main.

## 🛠 Getting Started
1. Clone the repository.
2. Install JDK 21 and ensure `yt-dlp`, `ffmpeg` and `ffprobe` are available on PATH for media operations. Desktop VLCJ playback also requires VLC/native libraries.
3. Open the project in **IntelliJ IDEA** or **Android Studio**.
4. Run the baseline checks from the project directory (PowerShell):

```powershell
.\gradlew.bat :shared:desktopTest :backend:test
.\gradlew.bat :shared:compileKotlinJs :webApp:compileKotlinJs
```

Both commands pass in the integration working copy after INT-03; the original dev revision retains the failures described above. Gradle needs network access on the first run to download dependencies. These checks do not start the application; Android installation and a working Web launch are not currently supported by this verified baseline.

---
*Developed with ❤️ for language learners who want to finally understand German.*
