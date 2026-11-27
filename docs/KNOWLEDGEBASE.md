# StopESP Knowledge Base

Everything about this fork of StopESP: what it does, how it works, every setting, commands, permissions, build steps, troubleshooting and known limits.

> Status: the 1.21.11 port, every feature below and every patch in section 8c were written without being able to compile against Paper in the authoring environment. Treat the first CI build and a test server run as the real verification.

---

## 1. Overview

StopESP is a server-side anti-cheat plugin against **x-ray, ESP, wallhacks and radar** mods. It never trusts the client with information the player shouldn't have:

- **Anti-X-ray:** protected blocks (chests, beds, anvils, furnaces, lecterns, ...) are replaced with fake stone/deepslate/netherrack/end stone on the player's client until the player has real line of sight. Both chunk packets and later vanilla block-update packets are filtered.
- **Anti-ESP:** entities (players, mobs, villagers, items, projectiles, boats, minecarts, item frames, armor stands) are hidden from a player until they are in line of sight. Players are checked out to `anti-esp.player-radius` (default 160), leaves block sight, and anti-pop-in settings stop players flickering when they really are visible.
- **Radar blur:** far-away visible players get delayed, slightly jittered movement.
- **Alerts:** a heuristic flags players breaking hidden blocks that aren't connected to their tunnel.

| Requirement | Value |
|---|---|
| Minecraft | 1.21.11 (Paper, Folia, or a Paper fork) |
| Java | 21 |
| Hard dependency | ProtocolLib (use a build that supports 1.21.11) |
| Soft dependencies | Floodgate, Geyser-Spigot (Bedrock detection) |
| Plugin API version | `1.21` |

---

## 2. How it works

### 2.1 Architecture

```
StopESP (main)
 ├─ FriendManager            mutual friends, saved to friends.yml
 ├─ AntiXrayModule
 │   ├─ AntiXrayManager      block index, visibility scan, worker pool
 │   ├─ ViolationManager     x-ray heuristic, alerts, Discord webhook
 │   ├─ AntiXrayListener     chunk load/unload, block place/break, quit
 │   ├─ AntiXrayPacketListener  chunk hook, BLOCK_CHANGE / MULTI_BLOCK_CHANGE filtering,
 │                         sound/particle/world-event filtering,
 │   │                         block-entity (TILE_ENTITY_DATA) filtering
 │   └─ chunk/ChunkPacketRewriter + ChunkSectionRewriter
 │                             per-player chunk packet rewrite (pure-Java section codec)
 └─ AntiEspModule
     ├─ AntiEspManager       entity visibility scan (gather → async rays → apply)
     ├─ AntiEspListener      join/quit/teleport cleanup
     ├─ AntiEspPacketListener   keeps hidden players in the tab list
     └─ AntiEspMovementJitter   delayed/jittered movement for far players
Utilities: Scheduler (Folia/Paper), BlockKey, ActivityTracker, ScanGate,
           PerformanceGovernor, BedrockSupport, TransparentBlocks
```

### 2.2 X-ray pipeline

0. **Chunk packet rewrite (before sending).** When a chunk packet is about to be sent, `ChunkPacketRewriter` clones it and, for each section that contains protected blocks, rewrites the block-state palette so those blocks are fake stone/deepslate/netherrack/end stone *in the packet itself*. The real blocks never reach the client, not even for a moment or to a packet sniffer. Blocks the player has already been shown are left real. It auto-detects the chunk-buffer layout (with or without the data-array length prefix), checks itself (stone must be state id 1, air 0), fails safe, and switches itself off after repeated failures that never succeeded. If the chunk isn't indexed yet or anything looks wrong, the old post-send hide (step 2) is used instead.
1. **Index.** When a chunk loads, a snapshot is taken on the main thread and a worker builds a `ChunkData`: a bitset of *protected* blocks, a bitset of *occluding* blocks (for ray casts), and the real `BlockData` of every protected block.
2. **Hide after send (fallback).** If the rewrite couldn't handle a chunk, protected blocks are replaced right after it is sent (`hideAllInChunk`), which can show real blocks for a moment.
3. **Scan.** Every `scan-interval` ticks (stretched by TPS/AFK backoff) each eligible player gets an async scan: protected blocks inside the reveal radius that aren't fully buried and pass frustum culling are ray-cast from the player's eye (and a predicted future position if moving). Visible blocks are revealed. Only the other half of a multi-block (same material, one step: bed halves, double chests) is revealed with them. It no longer chains through every touching protected block (old behaviour revealed whole clusters of beds/anvils/glass/buttons).
4. **Buffer.** A block is only re-hidden after `visibility-buffer` consecutive failed checks, preventing flicker.
5. **Send.** Changes go out via `sendMultiBlockChange`.
6. **Filter later updates.** `BLOCK_CHANGE` and `MULTI_BLOCK_CHANGE` packets from the server (furnace lit, bed occupied, lectern book, brewing bottles...) carry the real state. For a block still hidden from that player they are replaced with the fake block, on a cloned packet (packets can be shared between players). Updates to blocks that are not protected types, or already revealed, pass untouched.

Block data is keyed by `BlockKey` (a packed `long`: x 26 bits, z 26 bits, y 12 bits), not `Location` objects. Because keys aren't world-specific, a player's tracking resets when they change world.

### 2.3 Anti-ESP pipeline (entities)

1. **Gather (entity thread):** nearby entities are collected with a predicate (only types that should be obfuscated), distances computed, a per-player budget applied (closest N always, rest rotate), and cheap position data captured in immutable snapshots. Entities that haven't moved (and whose viewer hasn't) reuse a cached result for up to 10 scans.
2. **Rays (worker thread):** line of sight uses the same occlusion index as x-ray, so no world access happens off-thread. If a world has no index yet, it falls back to the slower main-thread world ray trace.
3. **Apply (entity thread):** entities are hidden/shown with `hideEntity`/`showEntity`. New, unchecked entities are hidden by default (fail closed).

Line of sight for living entities uses: eye, middle and feet points, then the hitbox edges (corners at head, middle and feet, +/-0.3 blocks). The whole test is repeated from `peek-padding` blocks left and right of the viewer's eye, so players appear as you peek round a corner. A player who just lost sight stays visible for `hide-delay-ms` so there is no flicker. Leaves count as solid for sight (`leaves-block-sight`). Slabs, stairs, fences, doors, trapdoors, glass and similar still count as see-through.

Players are gathered out to `anti-esp.player-radius`, other entities out to `anti-esp.reveal-radius`. Anything outside the gathered radius is SHOWN unconditionally (this was the "far players show through walls / nametag cheat" hole), so keep `player-radius` at or above the server's player tracking range. The ray step limit scales with distance; before, it was a fixed 120 and a longer ray was treated as visible.

Never hidden from a viewer: their own projectiles, the vehicle they ride, scoreboard teammates, mutual friends.

### 2.4 Tab list fix

`hideEntity` on a player also makes the server remove them from the viewer's tab list. `AntiEspPacketListener` strips those `PLAYER_INFO_REMOVE` entries for players Anti-ESP is currently hiding, so everyone stays in the tab list. Real disconnects still work because the quit handler "forgets" the player first.

The old nameplate feature put hidden players into a `stopesp_hidden` scoreboard team on the viewer's scoreboard (the shared main scoreboard unless a plugin gives players their own). A player can only be in one team, so that pulled people out of their rank or tab-sorting team and broke the tab list (prefix gone, order wrong, name "missing"). That code is removed (hidden players are not sent at all, so there is no nameplate to hide) and the plugin deletes any leftover `stopesp_hidden` team on startup.

### 2.5 Movement jitter

For players who are visible but farther than `min-distance`, relative-move packets are batched (one every `update-interval` ticks) and sent with a random offset up to `jitter-blocks`. The offset is remembered and cancelled in the next batch, so error never accumulates. Teleport, position-sync and spawn packets reset the state. The server still sends exact positions occasionally, so this blurs radar rather than blocking it.

### 2.6 Violation heuristic

A break counts when the player breaks a hidden protected block (the client was never shown the real block) **and** it is farther than `tunnel-chain-distance` from their previous break within 15 seconds (i.e. not a continued tunnel). It only alerts; it never punishes. Mining straight through stone to a hidden block is normal, which is why the chain rule exists. Expect some false positives and tune the thresholds.

### 2.7 Performance features

| Feature | What it does |
|---|---|
| TPS backoff | Stretches scan interval by up to `max-multiplier` when average tick time exceeds `mspt-threshold` (Paper only; ignored on Folia). |
| AFK backoff | Players idle for `idle-seconds` are scanned every `multiplier`-th interval. |
| Entity budget | At most `max-entities-per-player` entities are ray-checked per scan (closest first plus a rotating slice). |
| Block check budget | At most `max-block-checks-per-scan` block ray casts per scan, nearest first. |
| Dirty chunks | Far chunks (beyond `near-radius`) are skipped if unchanged, settled, and the player moved less than `move-distance`. Block changes force a full re-check. |
| LOS cache | Entities reuse results while neither they nor the viewer moved. |
| Adaptive workers | Pool starts at `min-worker-threads`, grows when work queues, shrinks when idle, caps at half max under high tick time. |
| Primitive keys | `long` keys instead of `Location` objects in all block maps. |

---

## 3. Commands

| Command | Permission | Description |
|---|---|---|
| `/stopesp reload` | `stopesp.admin` | Reload `config.yml` |
| `/stopesp on` / `off` | `stopesp.admin` | Start scanning / stop and reveal everything |
| `/stopesp debug <player>` | `stopesp.admin` | Tracking info for a player |
| `/stopesp violations [player]` | `stopesp.admin` | Violation counts |
| `/stopesp friend add <player>` | `stopesp.friend` | Add a friend (needs them to add you back) |
| `/stopesp friend remove <player>` | `stopesp.friend` | Remove a friend |
| `/stopesp friend list` | `stopesp.friend` | List friends and whether each is mutual |

## 4. Permissions

| Permission | Default | Meaning |
|---|---|---|
| `stopesp.admin` | op | All admin subcommands |
| `stopesp.bypass` | false | Nothing is hidden from this player (use for staff who need full view) |
| `stopesp.alerts` | op | Receive in-game violation alerts |
| `stopesp.friend` | true | Use `/stopesp friend` |

---

## 5. Configuration reference

Config is versioned (`config-version`, currently **6**). On startup missing keys are added with defaults, your values are kept, and a `config.yml.bak-vN` backup is written. Migration v3 also adds beds and anvils to `protected-blocks`.

### 5.1 Core

| Key | Default | Meaning |
|---|---|---|
| `show-thanks-message` | true | Join thanks message |
| `reveal-radius` | 20.0 | Blocks within which protected blocks can be revealed |
| `world-radius-overrides` | (empty) | Per-world reveal radius |
| `enabled-worlds` | (list) | Worlds where StopESP is active |
| `scan-interval` | 1 | Ticks between scans (before backoff) |
| `visibility-buffer` | 3 | Failed checks before a block is re-hidden |
| `frustum-culling` | true | Skip blocks clearly behind the player |
| `fake-block-mode` | CONTEXT | CONTEXT (copies the natural block next to the hidden one: grass, dirt, sand, deepslate, tuff...; falls back to AUTO), AUTO (stone/deepslate/netherrack/end stone) or a material name |
| `worker-threads` | -1 | **Max** worker threads (-1 = CPU cores, min 2, max 8) |
| `hide-ores` | false | Master switch for hiding `*_ORE` and ancient debris |
| `chunk-rewrite.enabled` | true | Rewrite the chunk packet per player before sending (falls back automatically) |
| `base-blocks-preset` | true | Also hide a built-in set of base blocks |
| `protected-blocks` | (list) | Blocks hidden until line of sight |

The preset adds (on top of `protected-blocks`, so a live config missing them is still covered): chest, trapped chest, barrel, ender chest, all anvils, all 16 beds, lectern, dispenser, dropper, hopper, beehive, bee nest, note block, jukebox, furnace, blast furnace, smoker, brewing stand, enchanting table, beacon, respawn anchor, cauldrons, composter, crafter, vault, trial spawner, crafting/smithing/loom/cartography/fletching tables, stonecutter, grindstone, lodestone, conduit, decorated pot, chiseled bookshelf, end portal frame, and copper chests (including waxed variants). Names missing on your version are skipped.

### 5.2 Anti-ESP (`anti-esp`)

| Key | Default |
|---|---|
| `enabled` | true |
| `reveal-radius` | 32.0 (mobs and other entities) |
| `player-radius` | 160.0 (players are line-of-sight checked out to here; never below reveal-radius) |
| `leaves-block-sight` | true |
| `peek-padding` | 0.4 (blocks left/right of the eye also tested; 0 = off) |
| `hide-delay-ms` | 400 (keep a player visible this long after losing sight; 0 = off) |
| `obfuscate-players` | true |
| `obfuscate-mobs` | true |
| `hide-nameplates` | false (no longer used, see 2.4) |
| `hide-invisible-entities` | false |
| `extra-entity-hide` | (list of entity types) |
| `hide-items` / `hide-projectiles` / `hide-item-frames` / `hide-armor-stands` | true |
| `hide-boats` / `hide-minecarts` | true |
| `hide-paintings` / `hide-leash-knots` | true |
| `hide-villagers` | true (glass counts as visible, so trading halls behind glass work) |
| `movement-jitter.enabled` | true |
| `movement-jitter.min-distance` | 48.0 |
| `movement-jitter.update-interval` | 4 ticks |
| `movement-jitter.jitter-blocks` | 0.5 |

### 5.3 Y-rules (`y-rules`, off by default)

Only affects ores, and only matters when `hide-ores: true`.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | false | Turn the rules on |
| `skip-ores-above-y` | 64 | Ores at/above this Y are never hidden or tracked |
| `ore-reveal-radius` | 12.0 | Reveal-check radius for ores (capped to `reveal-radius`) |

Already-indexed chunks keep old data until restart or `/stopesp off` + `on`.

### 5.4 Violations (`violations`)

| Key | Default |
|---|---|
| `enabled` | true |
| `alert-threshold` | 3 |
| `window-seconds` | 300 |
| `alert-cooldown-seconds` | 30 |
| `tunnel-chain-distance` | 3.0 |
| `log-to-console` | true |
| `discord-webhook` | "" (empty = off; must be a discord.com/discordapp.com webhook URL) |

### 5.5 Block entities (`block-entities`)

`hide-updates` (true): cancels block-entity data updates for blocks currently hidden from the player. Only covers blocks in `protected-blocks`; add `SPAWNER` or sign types if you want them covered.

### 5.6 Performance (`performance`)

| Key | Default |
|---|---|
| `tps-backoff.enabled` | true |
| `tps-backoff.mspt-threshold` | 35.0 |
| `tps-backoff.max-multiplier` | 4 |
| `max-entities-per-player` | 64 (0 = unlimited) |
| `max-block-checks-per-scan` | 4000 (0 = unlimited) |
| `min-worker-threads` | 1 |
| `afk-backoff.enabled` | true |
| `afk-backoff.idle-seconds` | 20 |
| `afk-backoff.multiplier` | 5 |
| `afk-backoff.skip-spectators` | false |
| `dirty-chunks.enabled` | true |
| `dirty-chunks.move-distance` | 2.0 |
| `dirty-chunks.near-radius` | 32.0 |

### 5.7 Visibility and Bedrock

| Key | Default | Meaning |
|---|---|---|
| `visibility.team-visible` | true | Same scoreboard team = never hidden from each other |
| `visibility.friends-enabled` | true | Mutual friends = never hidden from each other |
| `bedrock.disable-jitter` | true | No jitter to/for Bedrock players |
| `bedrock.scan-multiplier` | 1 | Scan Bedrock viewers N times less often |

---

## 6. Files

| Path | Purpose |
|---|---|
| `plugins/StopESP/config.yml` | Settings |
| `plugins/StopESP/config.yml.bak-vN` | Backup made by config migration |
| `plugins/StopESP/friends.yml` | Saved friend lists |

## 7. Building

- Local: JDK 21, then `./gradlew build`. The jar is in `build/libs/`. `./gradlew runServer` starts a test Paper 1.21.11 server (the ProtocolLib download there is pinned to 5.4.0; swap for a build that supports 1.21.11).
- CI: `.github/workflows/build.yml` builds on JDK 21 for pushes to `main`/`master`, pull requests and manual runs, uploads the jar as the `StopESP` artifact, and creates a GitHub release when you push a tag starting with `v`. `build.gradle.kts` also lists the ProtocolLib repository (`repo.dmulloy2.net`) so a clean runner can resolve ProtocolLib 5.4.0.

## 8. What changed in this fork

- Ported from Paper 26.1.2 / Java 25 to **1.21.11 / Java 21**.
- Y-level rules, violation log with staff alerts and Discord webhook, block-entity update filtering.
- Anti-ESP for items, projectiles, item frames, armor stands, villagers, boats and minecarts.
- Per-player chunk packet rewrite (closes the chunk-load leak), paintings and leash knots as Anti-ESP targets.
- Tab list fix, movement jitter, `hide-ores` switch, base-block preset, beds and anvils protected.
- Team and mutual-friend visibility, Bedrock/Geyser handling.
- Async entity visibility with cache; primitive-key block maps; AFK backoff; dirty-chunk tracking; adaptive worker pool; TPS backoff and budgets.
- Config versioning with automatic migration.

## 8a. Patch log (leak fixes after in-game freecam/ESP testing)

| Area | Problem | Change |
|---|---|---|
| Flood fill | One visible protected block revealed every touching protected block, chaining through beds, anvils, glass, buttons, plates | Only same-material neighbours of a directly visible block, one step (bed halves, double chests) |
| Default protected list | Glass, stained glass, buttons, pressure plates were protected and fed the chaining | Removed from default `config.yml` (remove them from a live config too) |
| Built-in preset | A live config could lack beds/anvils/lectern/etc. | Chests, anvils, beds, lectern, dispenser, dropper, hopper, beehive, bee nest, note block, jukebox added to the preset |
| Block updates | Vanilla `BLOCK_CHANGE`/`MULTI_BLOCK_CHANGE` leaked real states of hidden blocks (furnace lit, bed used...) | New packet filters swap in the fake block while hidden |
| Tab list | `stopesp_hidden` scoreboard team broke rank/tab entries | Feature removed, old team purged on startup, `hide-nameplates` default false |
| Far players | Players beyond `reveal-radius` were shown through walls; ray limit 120 returned "visible" | `player-radius` (160), distance-scaled ray limit |
| Leaves | Rays passed through foliage | `leaves-block-sight: true` |
| Pop-in | Players appeared late at corners or flickered | Hitbox-edge samples, `peek-padding`, `hide-delay-ms` |
| CI | Workflow named `main.yml`, ProtocolLib repo missing | `build.yml`, repo added |

## 8b. Self-test for the chunk codec

`tools/ChunkRewriterSelfTest.java` (not part of the Gradle build) round-trips random chunk buffers in both layouts (single-value, indirect and direct palettes) and feeds junk input to confirm it fails safe:

```
javac -d out src/main/java/com/github/tejaslamba2006/stopesp/chunk/ChunkSectionRewriter.java tools/ChunkRewriterSelfTest.java
java -cp out ChunkRewriterSelfTest
```

It checks internal consistency, not the live protocol.

## 8d. Nametag-ESP hardening

A nametag/ESP mod can only draw an entity the client has been told about, so the only real defence is not sending it. Anti-ESP already does that for players without line of sight. Two gaps remained, both closed without hiding anyone who is genuinely visible:

| Gap | Fix | Setting |
|---|---|---|
| One visible hitbox corner (a boot, an elbow) revealed the whole player, so the nametag appeared above their head behind the wall | A player is sent only when at least N of 15 body sample points are visible (3 centre-line points + 12 hitbox corners) | `anti-esp.min-visible-samples` (default 2; 1 = old behaviour) |
| On join, respawn, world change and long teleports the entity tracker sent everyone before the first async scan ran | Hide-first: players around the viewer (and the moving player, for everyone around) are hidden inside the event, then revealed by the normal line-of-sight scan | `anti-esp.fail-closed` (default true) |

The old scoreboard-team nameplate trick is not a defence: mods draw their own tags and ignore the team option. It stays removed.

Optional: `anti-esp.near-reveal-radius` (default 0 = off) always sends players closer than that many blocks, which restores the vanilla faint nametag through the floor at short range. It is a deliberate leak of that radius, so a nametag cheat sees those players too.

`anti-esp.third-person-reach` (default 4.0, 0 = off) adds F5 support: players are also checked from where the third-person camera would be, behind the eye and in front of it for the front view, pulled in by walls like the vanilla camera. Cached results are now also tied to where the viewer is looking.

Still true: a player who is partly on screen shows their nametag, and `peek-padding` / `hide-delay-ms` still leak briefly by design.

## 8e. Context-aware fake blocks

With `fake-block-mode: CONTEXT`, each protected block is indexed with a disguise chosen from the natural blocks touching it inside its chunk (most common wins, the block below wins ties; ores and see-through blocks are never used). Chunk packets, block updates and re-hides all use the same stored disguise. Existing configs keep their old value, so set it by hand; already-indexed chunks need a restart or `/stopesp off` then `on`.

## 9. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Plugin won't load | ProtocolLib missing or too old for 1.21.11; Java isn't 21 |
| Original `StopESP-1_0_0.jar` fails | It was built for Java 25; build this source instead |
| Only some players appear in tab list | Make sure `AntiEspPacketListener` is active (Anti-ESP enabled, ProtocolLib loaded) |
| Villagers vanish near a trading hall | Walls without transparent blocks block line of sight; use glass or set `hide-villagers: false` |
| Chest in a far chunk stays hidden | Set `dirty-chunks.enabled: false` and report it |
| Block flickers | Raise `visibility-buffer` |
| Console says "Chunk packet rewrite skipped/disabled" | The packet layout wasn't recognized; hiding falls back to the post-send path. Set `chunk-rewrite.enabled: false` to silence it, and report your server/ProtocolLib versions |
| Ores no longer hidden | `hide-ores: false` (default) |
| Citizens/Shopkeepers NPCs disappear | NPC villagers are hidden like normal ones; add an `ignore-npcs` option (not implemented yet) or set `hide-villagers: false` |
| Too many false violation alerts | Raise `alert-threshold` or `tunnel-chain-distance`, or lengthen `alert-cooldown-seconds` |
| Config changed after update | Migration added new keys; compare with `config.yml.bak-vN` |
| You still see beds, anvils, chests, furnaces in freecam | Check, in order: you have `stopesp.bypass` (ops with `*` do); your world is not in `enabled-worlds`; you are on the same scoreboard team or mutual friends with the other player; your live `config.yml` lacks the entries (the preset covers most); chunks loaded before a change need `/stopesp off` then `/stopesp on`. Remember freecam is checked from your real body, not the camera |
| Players still show through walls far away | Check `anti-esp.player-radius`; leaves/slabs/stairs/glass are see-through for sight except leaves when `leaves-block-sight` is on |
| Tab list loses prefixes or names | Fixed by removing the `stopesp_hidden` team; restart once so the old team is purged, and set `hide-nameplates: false` |
| Players pop in late or flicker | Raise `peek-padding` and `hide-delay-ms` (small leak in return) |
| Build fails in CI | Paste the error from the "Build plugin" step; the patches in 8a were never compiled |

## 10. Known limits

- Not compiled against Paper by the author of this port; verify with the CI build and a test server.
- The chunk packet rewrite relies on reading the packet through reflection (mojmap names on Paper 1.21) and on the chunk being indexed before it is sent. When either fails, the fallback can show real blocks briefly. The section codec itself is covered by `tools/ChunkRewriterSelfTest.java`, but it has not been verified against a real 1.21.11 client.
- Block-entity data inside chunk packets (sign text, spawner data, etc.) is not stripped; only block states are rewritten.
- Jitter is bypassed by occasional exact server position syncs.
- `getAverageTickTime`-based backoff needs Paper; on Folia it is ignored. Some scans read player data from the global scheduler, which Folia may restrict.
- Friend visibility needs both players to add each other; party-plugin integration isn't included.
- Bedrock handling hasn't been tested against a real Geyser setup.
- The violation log is a heuristic, not proof.
- No setting can stop ESP while a player behind a wall stays on screen: ESP shows whatever the client is told exists. Anti-pop-in settings only make hiding happen when truly blocked, at the cost of a small leak (`peek-padding` blocks, `hide-delay-ms` ms).
- Freecam is evaluated from the player's real position within `reveal-radius`/`player-radius`; anything revealed near the body can be viewed from the free camera.
- Slabs, stairs, fences, doors, trapdoors, glass, carpets and signs still count as see-through for line of sight.
- The block-change filters and the entity patches use ProtocolLib 5.4.0 method names verified by inspecting the jar only.

## 11. Ideas not yet built

Chest/piston animation filtering, stripping block-entity data from chunk packets, ore-ratio tracking, fake ore decoys, claim-plugin bypass (GriefPrevention/WorldGuard/Towny), NPC/shopkeeper ignore, `/stopesp stats`, `/stopesp reveal`, PlaceholderAPI, violation events for other plugins, unit tests in CI, Modrinth/Hangar publishing.
