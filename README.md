
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

- Java 17+ for Raveon; Java 21 for Minecraft 1.21.11.
- Paper 1.16.5, 1.20.1, or 1.21.11; Folia 1.21.11.
- PacketEvents 2.12.1.
- WorldGuard, optionally, for region bypass rules.
- Redis, optionally, for synchronization across servers.

Folia 1.16.5 does not exist. Paper 1.16.5 must run on Java 17+ to load Raveon; standard build 794 warns about this Java version. Check compatibility with your server build and other plugins separately.

## Installation

1. Build `build/libs/Raveon-2.0.6.jar`.
2. Install `packetevents` in the `plugins/` directory.
3. Copy `Raveon-2.0.6.jar` to `plugins/`.
4. Start the server once to generate the configuration files.
5. Stop the server and edit the configuration.
6. Connect the plugin with `/raveon connect <code>`.
7. Start the server again.

Reload validates and applies the entire configuration together. If validation fails, the current settings remain active. Changes to `database`, `redis`, `placeholder`, or the inference API address require a restart. There are no automatic migrations from older plugin versions.

`database.retention_days: 0` disables history expiration. A positive value sets the retention period in days. Queue limits do not limit the size of the database or CSV files on disk.

Probability history is stored separately for Pro and Ultra. The model selector in the player and history menus changes the displayed model; it defaults to the enabled primary model, or the other enabled model. Existing Pro history remains in its original tables. Ultra uses its own snapshot and entry tables. Paging the player ranking reuses the loaded list; use Refresh to load new results.

The client supports one below-name slot. If another plugin uses it, Raveon yields the slot. A second line requires another display method.

Floating holograms and below-name displays rotate through enabled, available models. Each selected model keeps showing its latest probability, average, and buffer and updates when a new result arrives. Configure the switch interval in ticks with `model_rotation.interval_ticks` (default 80) and the displayed names with `model_rotation.names` in `features/hologram.yml`. Templates use `{model}`. A model without results shows `--`. Missing rotation settings are added to existing configs; legacy per-model floating templates remain supported.

## Language and time format

English is the default (`language: en` in `settings.yml`); use `language: ru` for Russian. Edit translations in `lang/en/` or `lang/ru/`: `history.yml`, `players.yml`, `datacollect.yml`, `hologram.yml`, and `translations.yml`. Time uses the selected language, for example `8 min 10 sec` or `8 мин 10 сек`.

On the first upgrade, translations from the old `lang/en.yml` and `lang/ru.yml` are copied into the new files. Original files are preserved as backups and are no longer read once the new catalogs exist. Existing custom text is retained; the previous default history layout is upgraded to the new two-line card. Reload applies translation edits.

History cards show a colored probability and elapsed time since the last check. Back returns to the player list, keeping its page when history was opened from that list. Write punishment reasons directly in the commands in `features/punishments.yml`, for example `5:0 ban {player} Raveon detected unusual behavior`. Navigation labels are configured separately in each language's `history.yml` and `players.yml`.

Alert, monitor (action bar and chat), and hologram preferences are cached by player UUID for the current plugin session. Reconnecting restores both enabled and disabled choices, including the monitor target and model filter. Monitoring pauses while its target is offline and resumes when the target returns. The cache resets when the plugin/server restarts.

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

Dataset collection always uses Ultra. Dataset collection: `/raveon datacollect start <player> <cheat/legit> <dataset_name>` and `/raveon datacollect stop <player>`. These commands require `raveon.command.datacollect` and, respectively, `raveon.command.datacollect.start` or `raveon.command.datacollect.stop`.

CSV files are stored in `plugins/Raveon/datasets/`. If a write fails, unconfirmed frames remain in memory; run stop again after resolving the cause. A forced JVM termination does not guarantee that these frames will be saved.

## Building from source

```bash
./gradlew shadowJar
```

On Windows:

```bat
gradlew.bat shadowJar
```

The resulting JAR is written to:

```text
build/libs/Raveon-2.0.6.jar
```

Main classes are compiled with `--release 17`. GitHub Actions builds with JDK 21. The first Gradle run downloads public dependencies; with a populated local cache, add `--offline`.

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
