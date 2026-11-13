# Raveon

AI anticheat for Minecraft combat analysis.

Live monitoring, alerts, violation history and configurable punishments.
Supports SQLite, MySQL, Redis synchronization and WorldGuard integration.

## Requirements

- Java 17+; your Minecraft version may require a newer Java.
- Paper or Folia.
- PacketEvents 2.12.1.
- Access to the Raveon analysis service.

## Installation

1. Install PacketEvents.
2. Copy `Raveon-2.1.0.jar` into `plugins/`.
3. Start the server to generate configuration files.
4. Get a connection code from your server page on the website.
5. Run `/raveon connect <code>`.

Configuration is stored in `plugins/Raveon/`.
Messages are configured in `translation.yml`, `menu/*.yml` and `features/datacollect.yml`.

Use `/raveon reload` to apply configuration changes.
Database, Redis and server identity changes require a restart.

## Commands

The root command requires `raveon.command.use`.

| Command | Permission |
| --- | --- |
| `/raveon connect <code>` | `raveon.command.connect` |
| `/raveon alerts` | `raveon.command.alert` |
| `/raveon holo` | `raveon.command.holograms` |
| `/raveon menu [player]` | `raveon.command.menu` |
| `/raveon history <player> [page]` | `raveon.command.history` |
| `/raveon monitor prob <player> [model]` | `raveon.command.monitor` |
| `/raveon monitor chat [player] [model]` | `raveon.command.monitor` |
| `/raveon monitor stop` | `raveon.command.monitor` |
| `/raveon reload` | `raveon.command.reload` |

## Build

Linux / macOS:

```sh
./gradlew shadowJar
```

Windows:

```bat
gradlew.bat shadowJar
```

Output: `build/libs/Raveon-2.1.0.jar`.

## Links

- [Website](https://raveon.wtf)
- [Discord](https://discord.gg/RaveonAI)
- [Telegram](https://t.me/raveonanticheat)

## License

[GNU General Public License v3.0](LICENSE).
