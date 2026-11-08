# API-defined models (plugin 2.0.10+)

Inference models are loaded from the license profile, not a Java enum. Up to 32
models may be present. Each has a stable ID, independent capture window/cadence,
buffer, action switches, display names and history. Any enabled model can be primary.
Profile updates can add, disable or remove models without restarting the plugin;
in-flight results from retired windows are discarded.

## Model identity and capture format

- `id`: lowercase `[a-z][a-z0-9_]{0,31}`; `all`, `auto`, `none`, `off`, `both` are reserved.
- `wire_id`: unique integer 1..255 in the profile. Pro owns 1; Ultra owns 2;
  custom models use 3..255. It identifies the requested model in RVS1/RVM2.
- `capture`: `pro` (8 rotation features) or `ultra` (the existing extended telemetry
  schema). It selects the collector and tensor feature layout, not model identity.
- `window`: 50..600 for Pro capture, 64..600 for Ultra capture.
- `step`: integer 1..window.

Several models may share one collector while retaining independent windows and
steps. A new trained model using either supported feature layout does not require
a new plugin build. A new feature layout requires implementing its collector and
transport schema; unknown formats are rejected, never silently treated as Ultra.

The existing Pro/Ultra profile format remains supported: their `capture` field
may be omitted. Custom models must declare it. Model references in buffers,
punishments and monitor commands use `id`, not `wire_id`.

## Website/API workflow

In the website admin Models editor, choose Add model, set ID, wire ID, capture
format, display names, plans, policy and visibility, then save. The equivalent API
is `PUT /api/admin/models` with the complete `{revision, models}` catalog. It uses
`wireId` (camel case); the plugin profile uses `wire_id` (snake case).

Existing IDs, wire IDs and formats cannot be rebound or removed from the website
catalog: hide a retired model instead. This preserves old server settings and
transport associations. New cards start hidden, with no plans assigned.

Assign server access in its admin model-access section, or call
`PUT /api/admin/servers/{serverId}/model-access/{modelId}` with `{revision, mode}`.
The owner can then enable the model and select it as primary. An individual grant
does not turn on inference automatically. Plugin model/role switches only narrow
the API's permission set.

The analysis backend must have an actual trained model registered with the same
ID, wire ID and capture layout. Adding a website card does not create weights or
extend an inference server that only implements Pro/Ultra. This change updates
the plugin and Rust website API; Python inference registration remains a separate
integration step.

## Local configuration

Any API model ID accepts the same per-model configuration, for example:

```yaml
analyze:
  models:
    aim_fast:
      enabled: true
      combat: 60
      actions:
        alert: true
        mitigate: false
        punish: true
      buffer:
        source: local
        inherit: ultra
        flag: 50
        reset: 25
        multiplier: 100
        decrease: 0.25
        threshold: 0.85
        legit-threshold: 0.1
        weight: 1
        primary-multiplier: 2
```

Configuration is optional: omitted blocks use the API policy, with normal local
defaults for local-only capture settings. Apply file edits with `/raveon reload`.
See [buffer source and inheritance rules](MODEL_BUFFERS.md).

`/raveon monitor prob <player> aim_fast` and monitor tab completion accept API IDs.
Hologram rotation and history menus enumerate active API models. Punishment groups
can list custom IDs in `models`. Dataset collection retains the two built-in
collector formats; API-defined inference models do not introduce new CSV layouts.

Pro/Ultra history tables remain intact. Custom model history gets separate tables
with a persistent model registry, so restarts, retention and explicit player
cleanup include the additional models. MySQL punishment model IDs are widened
from 16 to 32 characters without replacing the counter table.

## Validation

The contract checks exercise four-model profiles, independent capture cadence,
wire/result isolation, profile replacement, arbitrary buffer owners, local
configuration and punishment IDs. A fixture produced by the Rust website API is
parsed by Java. SQLite integration checks verify history isolation and registry
restoration with a real JDBC connection.
