# Drop Stacker

A Fabric mod for Minecraft that automatically stacks dropped item entities on the ground into a single entity, reducing entity lag and keeping your world tidy.

## Features

- **Instant Merging:** New drops join a nearby pile the moment they spawn — in the same tick, not after a scan interval. A mob's drops collapse before you see them land. Thrown items (a player pressing Q, villagers and piglins handing items over) keep their throw and join once they land.
- **Whole-Pile Collapse:** Each merge pass drains every compatible neighbour at once, so a 100-item pile becomes one entity in a single pass instead of one item per pass.
- **Limitless Piles:** Stacks beyond vanilla's 64-item limit — up to your configured `maxStackSize`, with per-item overrides.
- **Visual Feedback:** Colorful stack count label displayed above items (configurable thresholds and format).
- **Configurable Labels:** Format the label with placeholders and `&` colour codes, show it only on piles larger than one vanilla stack, and optionally hide it behind blocks.
- **Despawn Timer:** Optional countdown on the label with urgency-based color coding (supports infinite items with `∞`).
- **Adaptive Scanning:** Idle piles progressively back off from scanning (up to `maxScanInterval`), so a world full of settled items costs almost nothing. Safe because new drops push into piles on spawn rather than waiting to be polled.
- **Item Rules:** Blacklist, whitelist, and per-item stack-size overrides — all accepting item tags (`#minecraft:logs`) as well as exact ids.
- **Runtime Control:** `/dropstacker` reloads config, reports stats, and tunes settings without a restart.
- **Exact Matches Only:** Only items with identical components merge — the same enchantments, durability, name, contents and so on. Two differently enchanted swords stay two entities.
- **Safe Pickups:** Players, hoppers, hopper minecarts and mobs collect a pile one vanilla stack at a time. Nothing is trimmed away by a slot limit, and logging mods such as Ledger can record every pickup and drop (their item codec only accepts counts up to 99).
- **Respects Named Items:** An item entity with its own custom name is never relabelled or silently absorbed into another pile.
- **Vanilla Parity:** Merging honours vanilla's infinite-lifetime, near-despawn, and infinite-pickup-delay guards, keeps the longest pickup delay so merging can't shorten it, only merges drops reserved for the same player, and merges into the larger pile so a pile never jumps to a single item.
- **Server-Side Only:** All logic runs on the server. Vanilla clients see counts and timers with no mod installed.

## Requirements

- Minecraft 26.3
- [Fabric Loader](https://fabricmc.net/) 0.19.5+
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- Java 25+

## Commands

`/dropstacker` requires permission level 2 (gamemasters).

| Command | Description |
|---|---|
| `/dropstacker reload` | Reload the config file from disk |
| `/dropstacker stats` | Merge passes, entities collapsed, and current settings |
| `/dropstacker resetstats` | Reset the counters |
| `/dropstacker toggle` | Enable/disable stacking at runtime |
| `/dropstacker set maxStackSize <n>` | Change the stack cap |
| `/dropstacker set scanInterval <n>` | Change the scan interval |
| `/dropstacker set radius <n>` | Change the horizontal scan radius |
| `/dropstacker set verticalRadius <n>` | Change the vertical scan radius |

## Configuration

The config file is created automatically at `.minecraft/config/drop-stacker.json` (or `config/drop-stacker.json` on a server). Fields update automatically on restart if you upgrade the mod.

```json
{
  "enabled": true,
  "mergeOnSpawn": true,
  "maxStackSize": 1000,
  "radius": 5.0,
  "verticalRadius": 2.0,
  "scanInterval": 5,
  "maxScanInterval": 100,
  "maxMergePerPass": 64,
  "ageInheritance": "YOUNGEST",
  "labelMode": "ALWAYS",
  "showDespawnTimer": true,
  "countLowThreshold": 64,
  "countHighThreshold": 500,
  "hideSingleItemLabel": false,
  "labelFormat": "&6[{countColor}×{count}{timer}&6]",
  "timerFormat": "&b | {timerColor}{time}",
  "labelThroughWalls": true,
  "despawnTicks": 6000,
  "blacklist": [],
  "whitelist": [],
  "overrides": {}
}
```

| Field | Description | Default |
|---|---|---|
| `enabled` | Master switch for all stacking | `true` |
| `mergeOnSpawn` | Merge new drops instantly as they spawn | `true` |
| `maxStackSize` | Maximum items per stacked entity | `1000` |
| `radius` | Horizontal scan radius (blocks) | `5.0` |
| `verticalRadius` | Vertical scan radius (blocks) | `2.0` |
| `scanInterval` | Ticks between merge scans for an active pile | `5` |
| `maxScanInterval` | Slowest scan rate an idle pile backs off to | `100` |
| `maxMergePerPass` | Max neighbours absorbed in one pass | `64` |
| `ageInheritance` | Despawn timer on merge: `YOUNGEST`, `KEEP_RECEIVER`, or `OLDEST` | `YOUNGEST` |
| `labelMode` | When to show the label: `ALWAYS`, `STACKED_ONLY`, `OVERSIZED_ONLY`, or `NEVER` | `ALWAYS` |
| `showDespawnTimer` | Show despawn countdown on the label | `true` |
| `countLowThreshold` | Count at which the label turns Yellow | `64` |
| `countHighThreshold` | Count at which the label turns Red | `500` |
| `hideSingleItemLabel`| Hide the label if the stack only has 1 item | `false` |
| `labelFormat` | Text of the label, see [Label format](#label-format) | `&6[{countColor}×{count}{timer}&6]` |
| `timerFormat` | Text inserted for `{timer}` while a despawn timer is shown | `&b \| {timerColor}{time}` |
| `labelThroughWalls` | Let players see labels through blocks, like other name tags | `true` |
| `despawnTicks` | World despawn rate (matches timer to server settings) | `6000` |
| `blacklist` | Items that are never stacked | `[]` |
| `whitelist` | If non-empty, *only* these items are stacked | `[]` |
| `overrides` | Per-item stack caps | `{}` |

### Item rules

`blacklist`, `whitelist`, and `overrides` all accept exact item ids and item tags (prefixed with `#`):

```json
{
  "blacklist": ["minecraft:elytra", "#minecraft:shulker_boxes"],
  "overrides": {
    "minecraft:cobblestone": 5000,
    "#minecraft:logs": 512
  }
}
```

### Label format

`labelFormat` and `timerFormat` take these placeholders:

| Placeholder | Value |
|---|---|
| `{count}` | Items in the pile |
| `{stacks}` | Vanilla stacks the pile fills (`130` diamonds → `3`) |
| `{countColor}` | Green, yellow or red by `countLowThreshold`/`countHighThreshold` |
| `{timer}` | `timerFormat`, or nothing when no timer is shown |
| `{time}` | Remaining time as `m:ss`, or `∞` |
| `{timerColor}` | Green, yellow or red by remaining time |

Colours and formats use `&` codes: `&0`–`&f`, `&l` bold, `&o` italic, `&n` underline, `&m` strikethrough, `&k` obfuscated, `&r` reset, `&#RRGGBB` for any colour, and `&&` for a literal `&`. As in vanilla, a colour code resets bold/italic/… set before it.

A format without `{timer}`, `{time}` or `{timerColor}` never changes while a pile just lies there, so labels are only resent when the count changes. For a plain white count on piles larger than one stack:

```json
{
  "labelMode": "OVERSIZED_ONLY",
  "labelFormat": "&f{count}",
  "labelThroughWalls": false
}
```

`OVERSIZED_ONLY` labels a pile once it holds more than one vanilla stack of its item: from 2 totems, from 65 planks.

With `labelThroughWalls` set to `false`, clients are told that labelled piles are "discrete" (like a sneaking player), so the vanilla client hides their name tag behind blocks. The flag is only added to the data sent to clients; the item itself is unchanged on the server.

**Clients with [Entity Culling](https://modrinth.com/mod/entityculling):** Entity Culling stops rendering entities that are completely hidden and draws their name tags itself. For entities that are not mobs it does not check the "discrete" flag, so the label of a fully enclosed pile shows through walls again. Add `minecraft:item` to `entityWhitelist` in the client's `config/entityculling.json` so item entities are never culled; vanilla then renders them and hides the label behind blocks as intended:

```json
"entityWhitelist": ["minecraft:item"]
```

Keep the entries already in that list when adding it. Modpacks can ship this as a default config.

### A note on `ageInheritance`

The default `YOUNGEST` means a pile inherits the longest remaining lifetime when it absorbs a drop — so a pile next to a farm keeps resetting its despawn timer and effectively never despawns. That is convenient, but on a busy server it lets item piles accumulate indefinitely. Set `KEEP_RECEIVER` for vanilla despawn behaviour.

## Building

```bash
./gradlew build
```

Output JAR is in `build/libs/`. Gradle provisions the required Java 25 toolchain automatically.

## License

MIT — see [LICENSE](LICENSE).

---

> [!CAUTION]
> I am not great at Kotlin and this project was built with AI assistance. There may be bad code, wrong patterns, or things that could be done much better. If you spot anything — **please open an issue or a pull request**. I genuinely appreciate it and will learn from it. Thank you.
