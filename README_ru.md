
<div align="center">
  <h1>Raveon</h1>
  <p>AI-античит для Minecraft-серверов.</p>

  <p>
    <img alt="Minecraft" src="https://img.shields.io/badge/Minecraft-1.16+-62B47A?style=flat">
    <img alt="Java" src="https://img.shields.io/badge/Java-17+-E76F00?style=flat&logo=openjdk&logoColor=white">
    <img alt="Paper" src="https://img.shields.io/badge/Paper-supported-222222?style=flat">
    <img alt="License" src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat">
  </p>

  <p>
    <a href="https://discord.gg/RaveonAI">
      <img alt="Discord" src="https://img.shields.io/badge/Discord-Raveon-5865F2?style=flat&logo=discord&logoColor=white">
    </a>
    <a href="https://t.me/raveonanticheat">
      <img alt="Telegram" src="https://img.shields.io/badge/Telegram-Raveon-26A5E4?style=flat&logo=telegram&logoColor=white">
    </a>
  </p>
</div>

## Что такое Raveon

Raveon - это Minecraft-плагин античита для серверов. Плагин собирает данные игрока во время боя, отправляет их во внешний API и на основе вероятности читерского поведения ведет буфер нарушений, алерты, историю и наказания.

Проект рассчитан на связку:

- Minecraft-сервер с установленными Raveon и PacketEvents
- второй античит для анализа movement-поведения, например GrimAC, Vulcan или Intave
- внешний inference API для AI-анализа aim-поведения

## Возможности

- буферизация вероятности перед флагом, чтобы снижать случайные срабатывания
- алерты и мониторинг вероятности игрока в реальном времени
- меню игроков и история нарушений
- поддержка MySQL и SQLite для хранения данных
- Redis для межсерверной синхронизации
- WorldGuard-регионы для отключения AI-проверки в выбранных местах

## Важный момент перед установкой

AI-проверка работает только при доступном inference API. По умолчанию в конфигурации указан:

```yaml
analyze:
  server: https://api.raveon.wtf/v1/inference
```

Сервис моделей не входит в JAR. Проверки выключены, пока плагин не подключён: получите код на странице сервера на сайте и введите `/raveon connect <код>`. Плагин сам получит API-ключ, сохранит его в `analyze.api-key` и включит анализ без рестарта.

## Требования

- Java 17+ для Raveon; Java 21 для Minecraft 1.21.11 и полной матрицы проверок
- Paper 1.16.5, 1.20.1, 1.21.11; Folia 1.21.11
- PacketEvents 2.12.1
- WorldGuard опционально, если нужны bypass-настройки для регионов
- Redis опционально, если нужна синхронизация между серверами

Folia 1.16.5 не существует. Paper 1.16.5 должен работать на Java 17+; стандартная сборка 794 предупреждает об этой версии Java. Совместимость сервера и остальных плагинов проверяется отдельно.

## Установка

1. Соберите `build/libs/Raveon-2.0.2.jar`.
2. Установите `packetevents` в папку `plugins/`.
3. Поместите `Raveon-2.0.2.jar` в папку `plugins/`.
4. Один раз запустите сервер, чтобы плагин создал конфиги.
5. Остановите сервер и настройте файлы конфигурации.
6. Подключите плагин командой `/raveon connect <код>`.
7. Запустите сервер снова.

Reload применяет проверенный набор конфигураций целиком; при ошибке действующие настройки сохраняются. Изменения разделов `database`, `redis`, `placeholder` и адреса inference API требуют перезапуска. Автоматических миграций старых версий плагина нет.

`database.retention_days: 0` отключает удаление истории по возрасту. Положительное значение задаёт срок хранения в днях. Ограничения очередей не ограничивают размер базы и CSV на диске.

История вероятностей хранится отдельно для Pro и Ultra. Переключатель модели в меню игроков и истории меняет отображаемую модель; по умолчанию выбирается включённая primary либо другая включённая модель. Существующая история Pro сохраняется в своих таблицах. Для Ultra создаются отдельные таблицы снимков и записей. Перелистывание рейтинга использует уже загруженный список; новые результаты загружаются кнопкой обновления.

Клиент поддерживает один слот below-name. Если его занимает другой плагин, Raveon уступает ему. Для второй строки нужен другой способ отображения.

## Язык и формат времени

Укажите `language: en` или `language: ru` в `settings.yml`. Время соответствует выбранному языку: `8 min 10 sec` на английском или `8 мин 10 сек` на русском, без точек в сокращениях и без обрезания колонки времени в истории.

При загрузке плагин сохраняет существующие файлы переводов. Чтобы применить новые стандартные переводы на установленном сервере, обновите `lang/en.yml` и `lang/ru.yml`, сохранив нужный вам собственный текст, затем перезагрузите конфигурацию.

## Основные команды

| Команда | Permission | Что делает |
| --- | --- | --- |
| `/raveon connect <код>` | `raveon.command.connect` | Подключает плагин по одноразовому коду с сайта |
| `/raveon alerts` | `raveon.command.alert` | Включает или выключает алерты для игрока |
| `/raveon holo` | `raveon.command.holograms` | Включает или выключает AI-голограммы |
| `/raveon hologram` | `raveon.command.holograms` | Алиас команды голограмм |
| `/raveon menu [player]` | `raveon.command.menu` | Открывает меню игроков; с ником — историю игрока |
| `/raveon history <player> [page]` | `raveon.command.history` | Показывает историю нарушений игрока |
| `/raveon monitor prob <player> [model]` | `raveon.command.monitor` | Монитор вероятностей в action bar выбранного игрока: `last% \| avg% \| buffer` |
| `/raveon monitor chat [player] [model]` | `raveon.command.monitor` | Выводит вероятности игрока в чат; без ника — всех игроков |
| `/raveon monitor stop` | `raveon.command.monitor` | Останавливает монитор и вывод в чат |
| `/raveon reload` | `raveon.command.reload` | Перезагружает конфигурацию |

Базовое право для основной команды:

```text
raveon.command.use
```

Для монитора model принимает `pro`, `ultra` или `all`.

Сбор датасетов: `/raveon datacollect start <player> <cheat/legit> <dataset_name> [pro/ultra]` и `/raveon datacollect stop <player> [pro/ultra]`. Требуются `raveon.command.datacollect` и соответственно `raveon.command.datacollect.start` или `raveon.command.datacollect.stop`.

CSV находятся в `plugins/Raveon/datasets/`. При ошибке записи неподтверждённые кадры сохраняются в памяти; повторите stop после устранения причины. Принудительное завершение JVM не гарантирует их сохранения.

## Сборка из исходников

```bash
./gradlew check shadowJar
```

На Windows:

```bat
gradlew.bat check shadowJar
```

Готовый jar-файл будет находиться в:

```text
build/libs/Raveon-2.0.2.jar
```

Основные классы компилируются с `--release 17`. Для сборки и всей матрицы проверок используйте JDK 21. `check` проверяет окна Pro/Ultra, CSV, FlatBuffers, очереди, индекс сущностей, конфигурации, SQLite, локальный HTTP, датасеты, GUI, мониторинг и пакеты. Wire проверяется также с классами из собранного JAR. Частные эталонные JAR не требуются.

Первый запуск Gradle загружает публичные зависимости. При заполненном локальном кеше можно добавить `--offline`. Redis проверяется отдельной задачей `verifyRedisPresence`: задайте переменную `RAVEON_REDIS_BINARY` с путём к redis-server. Проверка сама запускает временный сервер только на loopback, без сохранения данных, и останавливает его. При отсутствии бинарника задача завершается ошибкой. Реальный MySQL, игровые сценарии Minecraft, разные регионы Folia и клиентские соединения требуют отдельного стенда.

При создании issue рекомендуется приложить:

- версию Minecraft-сервера
- версию Java
- версию Paper
- версию PacketEvents
- версию Raveon
- конфиги `settings.yml` и `checks.yml` без паролей и приватных токенов
- логи запуска и stack trace, если есть ошибка
- шаги воспроизведения проблемы

## Лицензия

Raveon распространяется на условиях лицензии [GNU General Public License v3.0](LICENSE).


Порядок признаков Ultra в API/CSV и требования к backend: [протокол Ultra](docs/ULTRA_FEATURES.md) и [машиночитаемая схема](docs/ultra-schema.json). Pro сохраняет исходный формат из восьми признаков.
