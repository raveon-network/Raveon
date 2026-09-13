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

## Что такое RaveonAI

RaveonAI - это Minecraft-плагин античита для серверов. Плагин собирает данные игрока во время боя, отправляет их во внешний API и на основе вероятности читерского поведения ведет буфер нарушений, алерты, историю и наказания.

Проект рассчитан на связку:

- Minecraft-сервер с установленными RaveonAI и PacketEvents
- второй античит для анализа movement-поведения, например GrimAC, Vulcan или Intave
- внешний inference API для AI-анализа aim-поведения

## Возможности

- буферизация вероятности перед флагом, чтобы снижать случайные срабатывания
- алерты и verbose-режим для отладки и наблюдения
- меню игроков и история нарушений
- мониторинг вероятности игрока в реальном времени
- поддержка MySQL и SQLite для хранения данных
- Redis для межсерверной синхронизации
- WorldGuard-регионы для отключения AI-проверки в выбранных местах

## Важный момент перед установкой

AI-проверка работает только при доступном inference API. По умолчанию в конфигурации указан:

```yaml
analyze:
  analyze_server: https://raveonai.wtf/v1/inference
```

Если вы не приобрели доступ к API, проверки будут недоступны.

## Требования

- Java 17+
- Paper 1.16+
- PacketEvents
- WorldGuard опционально, если нужны bypass-настройки для регионов
- Redis опционально, если нужна синхронизация между серверами

## Установка

1. Соберите или скачайте актуальный `RaveonAI` jar-файл.
2. Установите `packetevents` в папку `plugins/`.
3. Поместите `RaveonAI.jar` в папку `plugins/`.
4. Один раз запустите сервер, чтобы плагин создал конфиги.
5. Остановите сервер и настройте файлы конфигурации.
6. Включите AI-проверку в `plugins/Raveon/checks.yml`.
7. Запустите сервер снова.

## Основные команды

| Команда | Permission | Что делает |
| --- | --- | --- |
| `/raveon alerts` | `raveon.command.alert` | Включает или выключает алерты для игрока |
| `/raveon verbose` | `raveon.command.verbose` | Включает или выключает подробную информацию |
| `/raveon holo` | `raveon.command.holograms` | Включает или выключает AI-голограммы |
| `/raveon hologram` | `raveon.command.holograms` | Алиас команды голограмм |
| `/raveon menu` | `raveon.command.menu` | Открывает меню игроков |
| `/raveon history <player> [page]` | `raveon.command.history` | Показывает историю нарушений игрока |
| `/raveon monitor [player]` | `raveon.command.monitor` | Запускает монитор данных игрока |
| `/raveon monitor stop` | `raveon.command.monitor` | Останавливает текущий монитор |
| `/raveon reload` | `raveon.command.reload` | Перезагружает конфигурацию |

Базовое право для основной команды:

```text
raveon.command.use
```

## Сборка из исходников

```bash
git clone https://github.com/fruzzexx/RaveonAI.git
cd RaveonAI
./gradlew :RaveonAI:shadowJar
```

На Windows:

```bat
gradlew.bat :RaveonAI:shadowJar
```

Готовый jar-файл будет находиться в:

```text
RaveonAI/build/libs/
```

При создании issue рекомендуется приложить:

- версию Minecraft-сервера
- версию Java
- версию Paper
- версию PacketEvents
- версию RaveonAI
- конфиги `settings.yml` и `checks.yml` без паролей и приватных токенов
- логи запуска и stack trace, если есть ошибка
- шаги воспроизведения проблемы

## Лицензия

RaveonAI распространяется на условиях лицензии [GNU General Public License v3.0](LICENSE).
