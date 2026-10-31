
<div align="center">
  <h1>Raveon</h1>
  <p>An AI anticheat for Minecraft servers.</p>

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

## What is Raveon?

Raveon is an anticheat plugin for Minecraft servers. It collects player data during combat, sends it to an external inference API, and uses the returned cheating probabilities to update detection buffers, alerts, history, and punishments.

The intended setup includes:

- A Minecraft server with Raveon and PacketEvents installed.
- Another anticheat for movement checks, such as GrimAC, Vulcan, or Intave.
- An external inference API for AI aim analysis.

## Features

- Probability buffering before a flag to reduce isolated false positives.
- Alerts and live monitoring of player probabilities.
- Player menus and violation history.
- MySQL and SQLite storage.
- Redis for synchronization across servers.
- WorldGuard region rules to disable AI checks in selected areas.

## Before installation

AI checks require an available inference API. The default configuration points to:

```yaml
analyze:
  server: https://api.raveon.wtf/v1/inference
```

The model service is not included in the JAR. Checks stay off until the plugin is connected: get a code on your server's page on the website and run `/raveon connect <code>`. The plugin obtains an API key, stores it in `analyze.api-key` and enables analysis without a restart.

## Requirements

- Java 17+ for Raveon; Java 21 for Minecraft 1.21.11 and the full verification matrix.
- Paper 1.16.5, 1.20.1, or 1.21.11; Folia 1.21.11.
- PacketEvents 2.12.1.
- WorldGuard, optionally, for region bypass rules.
- Redis, optionally, for synchronization across servers.

Folia 1.16.5 does not exist. Paper 1.16.5 must run on Java 17+ to load Raveon; standard build 794 warns about this Java version. Check compatibility with your server build and other plugins separately.

## Installation

1. Build `build/libs/Raveon-2.0.2.jar`.
2. Install `packetevents` in the `plugins/` directory.
3. Copy `Raveon-2.0.2.jar` to `plugins/`.
4. Start the server once to generate the configuration files.
5. Stop the server and edit the configuration.
6. Connect the plugin with `/raveon connect <code>`.
7. Start the server again.

Reload validates and applies the entire configuration together. If validation fails, the current settings remain active. Changes to `database`, `redis`, `placeholder`, or the inference API address require a restart. There are no automatic migrations from older plugin versions.

`database.retention_days: 0` disables history expiration. A positive value sets the retention period in days. Queue limits do not limit the size of the database or CSV files on disk.

Probability history is stored separately for Pro and Ultra. The model selector in the player and history menus changes the displayed model; it defaults to the enabled primary model, or the other enabled model. Existing Pro history remains in its original tables. Ultra uses its own snapshot and entry tables. Paging the player ranking reuses the loaded list; use Refresh to load new results.

The client supports one below-name slot. If another plugin uses it, Raveon yields the slot. A second line requires another display method.

## Language and time format

Set `language: en` or `language: ru` in `settings.yml`. Time follows the selected language: `8 min 10 sec` in English or `8 мин 10 сек` in Russian, without abbreviation periods or truncation in the history time column.

Existing language files are preserved when the plugin loads. To use updated default translations on an existing installation, update `lang/en.yml` and `lang/ru.yml`, keeping any custom text you need, then reload the configuration.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/raveon connect <code>` | `raveon.command.connect` | Connects the plugin with a one-time code from the website |
| `/raveon alerts` | `raveon.command.alert` | Toggles alerts for the player |
| `/raveon holo` | `raveon.command.holograms` | Toggles AI holograms |
| `/raveon hologram` | `raveon.command.holograms` | Alias for the hologram command |
| `/raveon menu [player]` | `raveon.command.menu` | Opens the player menu, or the named player's history |
| `/raveon history <player> [page]` | `raveon.command.history` | Shows a player's violation history |
| `/raveon monitor prob <player> [model]` | `raveon.command.monitor` | Shows the selected player's probabilities in an action bar: `last% \| avg% \| buffer` |
| `/raveon monitor chat [player] [model]` | `raveon.command.monitor` | Shows probabilities in chat for one player, or all players when no name is given |
| `/raveon monitor stop` | `raveon.command.monitor` | Stops action bar and chat monitoring |
| `/raveon reload` | `raveon.command.reload` | Reloads the configuration |

The root command requires:

```text
raveon.command.use
```

For monitoring, `model` accepts `pro`, `ultra`, or `all`.

Dataset collection: `/raveon datacollect start <player> <cheat/legit> <dataset_name> [pro/ultra]` and `/raveon datacollect stop <player> [pro/ultra]`. These commands require `raveon.command.datacollect` and, respectively, `raveon.command.datacollect.start` or `raveon.command.datacollect.stop`.

CSV files are stored in `plugins/Raveon/datasets/`. If a write fails, unconfirmed frames remain in memory; run stop again after resolving the cause. A forced JVM termination does not guarantee that these frames will be saved.

## Building from source

```bash
./gradlew check shadowJar
```

On Windows:

```bat
gradlew.bat check shadowJar
```

The resulting JAR is written to:

```text
build/libs/Raveon-2.0.2.jar
```

Main classes are compiled with `--release 17`. Use JDK 21 for the build and the full verification matrix. The `check` task verifies Pro/Ultra windows, CSV, FlatBuffers, queues, the entity index, configuration, SQLite, local HTTP, datasets, GUI, monitoring, and packets. The wire format is also verified against classes from the packaged JAR. Private reference JARs are not required.

The first Gradle run downloads public dependencies. With a populated local cache, you can add `--offline`. Redis is verified separately with `verifyRedisPresence`: set the `RAVEON_REDIS_BINARY` environment variable to the path of `redis-server`. The check starts a temporary server on loopback only, without persistence, and stops it afterward. The task fails if the binary is missing. Real MySQL, Minecraft gameplay, separate Folia regions, and client connections require a separate test environment.

When reporting an issue, include:

- Minecraft server version.
- Java version.
- Paper or Folia version.
- PacketEvents version.
- Raveon version.
- `settings.yml` and `checks.yml`, with passwords and private tokens removed.
- Startup logs and a stack trace, if available.
- Steps to reproduce the problem.

## License

Raveon is distributed under the [GNU General Public License v3.0](LICENSE).

Ultra API/CSV feature order and backend requirements: [Ultra feature protocol](docs/ULTRA_FEATURES.md) and [machine-readable schema](docs/ultra-schema.json). Pro retains its original eight-feature format.
