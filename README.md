# StopESP

**Server-side anti-xray and anti-ESP for Paper, Folia, and Bukkit.** Hidden ores, chests, and players never leave your server, so a hacked client has nothing to draw on your screen.

Most anti-cheats watch how players behave and guess whether they are using X-ray or ESP. StopESP takes a different route: it just doesn't send the data. If a player has no real line of sight to a diamond vein or another player, their game never receives it. There is nothing for a cheat client to reveal, because the information was never there.

---

## What it does

**Anti-Xray.** Ores, chests, spawners, redstone, and anything else you list get replaced with ordinary stone (or netherrack, or end stone, picked automatically per dimension) until a player can actually see them. Walk up, mine into a wall, and the real blocks appear the moment they come into view. X-ray clients and cave-finder mods see a solid, boring world.

**Anti-ESP.** Players and mobs you have no line of sight to are hidden from your client entirely. Tracer and ESP cheats that draw glowing outlines through walls get nothing to outline. Optional extras: hide floating name tags of hidden players, hide already-invisible entities, and add specific mob types to always check.

**It also plugs the quiet leaks.** Chest-open sounds, block-break particles, and similar effects coming from spots you cannot see are cancelled, so nothing gives away a hidden room through the wall.

## Why server-side matters

A client-side or detection-based approach is always playing catch-up with the next cheat release. StopESP works at the level of what your server chooses to send. The block data and entity positions stay on the server until you legitimately earn the right to see them. That makes it effective against cheats it has never heard of.

The honest limit: this hides things you cannot see, not things you can. Once a player has real line of sight, they see the real block or entity, same as vanilla. StopESP stops the wallhack, not the person standing in front of the chest.

## Requirements

- **Minecraft 1.21.11 (Paper, Folia or a Paper fork)**
- **Java 21** (required by Minecraft 1.21)
- **Paper, Folia, or any Paper fork.** Folia's regional threading is detected and supported automatically.
- **[ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/)** installed on the server

## Installation

1. Drop `ProtocolLib` into your `plugins/` folder.
2. Drop `StopESP-1.0.0.jar` into `plugins/`.
3. Start the server once to generate `config.yml`.
4. Edit `enabled-worlds` to match your world names, then run `/stopesp reload`.

By default StopESP only works in worlds you list, so worlds you leave out cost nothing.

## Commands

| Command | What it does |
|---|---|
| `/stopesp reload` | Reload the config |
| `/stopesp on` | Turn hiding on |
| `/stopesp off` | Turn hiding off and reveal everything |
| `/stopesp debug <player>` | Show what StopESP is tracking for a player |

## Permissions

| Node | Default | Meaning |
|---|---|---|
| `stopesp.admin` | op | Use the commands above |
| `stopesp.bypass` | false | Blocks and entities are never hidden from this player |

## Configuration

Everything lives in `config.yml`. The parts you will actually touch:

- `enabled-worlds`: the worlds StopESP runs in.
- `protected-blocks`: the full list of blocks to hide. Ores, containers, spawners, and redstone are covered out of the box; add or remove freely.
- `reveal-radius`: how close a player gets before hidden blocks are checked (with optional per-world overrides).
- `anti-esp`: a section of its own. Toggle player hiding, mob hiding, name-tag hiding, invisible-entity hiding, and a list of extra entity types.

Performance knobs (`scan-interval`, `visibility-buffer`, `frustum-culling`, `worker-threads`) have sensible defaults. Visibility work runs off the main thread on a worker pool, and disabled worlds are skipped before any work happens.

## Building from source

Requires JDK 21.

```bash
./gradlew build      # jar lands in build/libs/
./gradlew runServer  # boots a fresh Paper 1.21.11 server with the plugin loaded
```

`runServer` pulls the latest Paper build and ProtocolLib for you, so you can test in one command.

## Support

Found a bug or want a feature? Join the [Discord](https://discord.gg/msEkYDWpXM).

## License

Released under the [MIT License](LICENSE).

## New in this fork

- **Y-level rules** (`y-rules`): skip hiding ores above a Y level and use a smaller reveal radius for ores to save CPU.
- **Violation log + alerts** (`violations`): flags players who break hidden blocks that aren't connected to the tunnel they were digging. Staff with `stopesp.alerts` get in-game alerts; optional Discord webhook; `/stopesp violations [player]`. It's a heuristic and only alerts, never punishes.
- **Block-entity update filtering** (`block-entities.hide-updates`): no block-entity data updates for blocks currently hidden from the player (add SPAWNER / sign types to `protected-blocks` to cover them).
- **More Anti-ESP targets**: dropped items, projectiles, item frames, armor stands (`anti-esp.hide-*`).
- **Performance**: TPS/MSPT-aware scan backoff, per-player entity budget and block-check budget (`performance.*`).
- **Config versioning**: `config-version` with automatic migration; new keys are added on update and a `config.yml.bak-vN` backup is saved.
- **Villagers** (`anti-esp.hide-villagers`): villagers and wandering traders are hidden until in line of sight (glass counts as visible, so trading halls behind glass still work).
- **Movement jitter** (`anti-esp.movement-jitter`): far-away visible players get batched, slightly jittered movement so radar mods can't track them precisely.
- **Async Anti-ESP**: entity line-of-sight now runs on the worker pool using the occlusion index (no world raycasts on the main thread), with a per-entity result cache for stationary entities.
- **Base-block preset** (`base-blocks-preset`): furnaces, brewing stands, enchanting tables, beacons, anchors, cauldrons, crafters, vaults, copper chests, workstations and more are hidden on top of `protected-blocks`.
- **Boats and minecarts** (`anti-esp.hide-boats`, `hide-minecarts`); the vehicle you ride is never hidden from you.
- **Team and friend visibility** (`visibility.*`): same-scoreboard-team players and *mutual* friends (`/stopesp friend add|remove|list`) are never hidden from each other.
- **Bedrock/Geyser** (`bedrock.*`): Floodgate-aware detection; no jitter for Bedrock players, optional lower scan rate.
- **Performance**: block data uses packed `long` keys instead of `Location` objects; AFK backoff; dirty-chunk tracking for far chunks; worker pool that grows and shrinks with load (`performance.*`).
- **Chunk packet rewrite** (`chunk-rewrite.enabled`): protected blocks are replaced inside the chunk packet per player before it is sent, so real blocks never reach the client. Self-checking with an automatic fallback; codec self-test in `tools/`.
- **Paintings and leash knots** (`anti-esp.hide-paintings`, `hide-leash-knots`).
