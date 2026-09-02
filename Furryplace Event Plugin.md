# Furryplace Event Plugin

This document is the authoritative behavior specification for the one-time Furryplace building event plugin.

## Runtime and dependencies

- Server: Leaf (Paper-compatible) 26.2.
- Java: 25.
- Packet dependency: PacketEvents only. ProtocolLib must not be required.
- Permissions are checked through standard Bukkit permission checks and assigned through LuckPerms.
- Required existing worlds: `lobby`, `place`, and `place-template`.
- The worlds are already void worlds. VoidGen is not a plugin dependency.
- If `place` or `place-template` is unavailable, the plugin must refuse to enable event functionality and log a clear error without creating replacement worlds.
- If `lobby` is unavailable, login/event teleports must fail safely and log a clear error.
- All player-facing text must be Spanish, stored in editable YAML, and parsed as MiniMessage. Dynamic player input such as names and search text must be inserted as escaped/unparsed placeholders.

## Roles and permissions

Role precedence is always Admin, then Judge, then Player. A staff member who also inherits `furryplace.player` is still treated as staff and cannot compete.

- `furryplace.player`
  - Default LuckPerms permission for ordinary players.
  - May create one plot, build inside it while the event is Active, use its wands, browse plots, and cast one community vote.
- `furryplace.judge`
  - Cannot participate, own a plot, or cast a community vote.
  - May browse every plot in view-only mode and cast one judge selection during Review and Judging.
  - Cannot build, break, or interact in contestant plots.
- `furryplace.admin`
  - Cannot participate or cast a community vote.
  - Controls the event, timer, template, portal, spawn, review, judging, winner, and reset.
  - Has a protection bypass and may modify plots even after construction ends.

Everyone who is not a Judge or Admin is treated as a Player. `furryplace.player` will also be assigned as the default player node in LuckPerms.

## Event lifecycle

The persistent event states are:

1. `INACTIVE`
2. `ACTIVE`
3. `REVIEW_PENDING`
4. `REVIEWING`
5. `JUDGING`
6. `COMPLETE`

Long-running template generation, plot generation, and reset operations may use internal transient states, but must resume or recover safely after a restart.

### Inactive

- Players wait in `lobby` and cannot enter a contestant plot.
- Entering the controlled portal is canceled, applies backward horizontal velocity relative to the player's view direction, and sends the throttled inactive message.
- The admin may configure the spawn, portal, template, and duration.
- The event cannot start until the template has been initialized and a snapshot can be frozen successfully.

### Active construction

- The admin starts the event manually from the menu after a confirmation.
- Default duration: 20 minutes.
- Duration before starting: minimum 5 minutes, maximum 3 hours.
- During Active, an admin may extend or reduce the remaining time between 1 minute and 3 hours. Every adjustment sends its own server-wide Spanish announcement.
- Eligible players may create their first plot at any time before the global timer expires.
- Reconnecting contestants return to the lobby and must voluntarily use the portal again.
- Community voting is open.
- The timer uses a persisted absolute deadline and continues while the server is offline.
- If the server starts after the deadline, the event loads as `REVIEW_PENDING` rather than resuming construction.

Server-wide configurable announcements are sent at:

- Event start
- 10 minutes
- 5 minutes
- 1 minute
- 30 seconds
- 15 seconds
- 5, 4, 3, 2, and 1 seconds

Only thresholds actually crossed by normal countdown ticking are announced. A manual timer reduction does not spam skipped thresholds; it sends the timer-adjustment message. Extending above a previously announced threshold rearms that threshold.

### Review pending

When the timer reaches zero:

- Community voting locks.
- Incomplete plot generation is canceled and its partial blocks are cleared.
- Everyone in `place` is returned to the configured lobby spawn.
- Owner event state is saved and normal state is restored.
- Contestant plots become view-only for Players and Judges; Admin bypass remains available.
- The server waits for an Admin to begin cycling through plots.

The reviewing Admin selects any participant head as the first plot. Remaining plots follow persisted plot-allocation order, wrapping from the final entry back to the first.

### Reviewing

- All online players in every world are teleported to the selected plot in survival view-only mode with flight allowed.
- A joining player is first placed at the configured lobby spawn and then moved to the plot currently being reviewed.
- The Admin who starts review becomes its controller.
- Only the controller can use Previous, Next, and End Review.
- If the controller disconnects, review pauses. Another Admin must explicitly use `Tomar control`; the current plot and visited set remain persisted.
- There is no automatic per-plot timer. The controller advances manually.
- End Review remains disabled until every participating plot has been shown at least once.
- Previous and repeated visits remain allowed.
- A persistent Action Bar is shown while reviewing:

  `<yellow>Parcela de <player></yellow> <aqua>(<current>/<total>)</aqua>`

- The Action Bar updates on every transition and is cleared when cycling ends.
- Judges may add, change, or remove their judge selection during this phase.

When cycling ends, everyone returns to the configured lobby spawn and the state becomes `JUDGING`.

### Judging

- Judges may continue adding, changing, or removing their selection.
- Each contestant entry shows the judge-vote count and the names of every Judge who selected that contestant.
- Community totals remain advisory and do not mathematically change the judge result.
- The controlling Admin closes judging manually.
- The contestant with the most judge selections is the proposed winner.
- A tie, or no judge selections, requires the Admin to select the winner.
- Before confirmation, the Admin may override the judge-majority result.
- Winner confirmation is a separate deliberate action.

### Complete

- The winner is announced to every online player through configurable MiniMessage chat plus title/subtitle messages.
- Everyone online is teleported to a safe viewing position at the winner's plot.
- Players remain survival view-only with flight; Admin protection bypass remains available.
- The completed player menu highlights the winner and their community-vote total.
- `/furryplace browse` and `/furryplace view <player>` continue to allow view-only visits to every completed plot.
- A player joining after completion first reaches the configured lobby spawn and is then teleported to the winner's plot.
- If the winner was offline during confirmation, the same winner chat/title notification is shown once on their next login.

### Cancel and reset

There is one combined Admin action: `Cancelar y reiniciar evento`.

- It requires a strong confirmation.
- It returns online players to the configured lobby spawn and restores owner state.
- It clears generated contestant plots, plot entities, assignments, participants, generation queues, event inventories, votes, judge selections, review state, winner state, timer state, and the frozen event snapshot.
- It preserves the manually edited `place-template` world, selected portal, configured lobby spawn, menu configuration, and message configuration.
- It returns the lifecycle to `INACTIVE` only after clearing finishes.
- Clearing is queued/batched, persists its progress, and resumes safely after a restart.

## Lobby spawn, login, XP, and hardcore hearts

### Lobby spawn

- `/furryplace set-spawn` is Admin-only and player-only.
- It succeeds only while the Admin is standing in the world named exactly `lobby`; other worlds are rejected.
- It stores world, X, Y, Z, yaw, and pitch.
- If no custom spawn has been stored, use the current spawn location of `lobby`.
- Every joining player is initially teleported to this location. Stage-specific review or winner routing happens immediately afterward.

### Decorative XP

- Every online player must continuously display XP level `2026` with a completely full XP bar.
- The plugin reapplies this on join, respawn, world change, and any attempted XP/level change.
- XP is decorative and is not included in owner-state snapshots.

### Hardcore hearts

- PacketEvents must set the hardcore flag in the initial Join Game packet so survival players see visual hardcore hearts for the entire server session.
- Worlds remain genuinely non-hardcore. The spoof must not enable permadeath, bans, or true hardcore respawn rules.
- Creative owners naturally do not display the survival health HUD while building.

## Plot geometry and ownership

- Maximum contestants: 50.
- Each plot has an 80 x 80 block interior, equal to 5 x 5 chunks.
- Plot coordinates identify the northwest interior corner.
- Plot 1 interior: `X 0..79`, `Z 0..79`.
- Plot 2 interior: `X 1024..1103`, `Z 0..79`.
- Plot `n` starts at `X = (n - 1) * 1024`, `Z = 0`.
- Surface grass is at Y=80.
- The nominal center is `originX + 39.5`, `originZ + 39.5`.
- The complete interior from the world's minimum build height through its maximum build height belongs to the owner during Active.
- Ownership is stored by UUID, with the latest player name retained only for display and search.
- A player becomes a participant only after their plot finishes generating successfully.

### Protected outline

- A two-block-wide logical boundary surrounds the outside of the 80 x 80 interior.
- Boundary protection applies through the world's full height.
- Visible andesite forms a two-block-wide hollow boundary wall around the interior through the world's full height. This makes the physical blocks clearly show the plot limits.
- Contestants cannot modify the logical boundary or move blocks, fluids, or entities through it.

### Safe arrival

- Teleports search outward from plot center for the nearest safe position with solid footing and two blocks of air.
- If the center area has been obstructed, the search continues within the plot.
- If no safe interior position exists, the player is placed in protected air above the outside boundary with flight enabled.
- Teleports must never place a player inside a solid block.

## Template and plot generation

### Template initialization

- The template interior uses the same coordinates as Plot 1 in `place-template`: `X 0..79`, `Z 0..79`.
- The outside logical/visible boundary is located at `X -2..81`, `Z -2..81` around that interior.
- The plugin never initializes the template automatically.
- Admin command: `/furryplace template generate`.
- The command uses a destructive confirmation and then overwrites the entire managed template region.
- Default interior:
  - Grass at Y=80.
  - Dirt from the world minimum height through Y=79.
- Default visible outline:
  - Two-block-wide andesite hollow boundary wall outside the interior through the world's full height.
- The start action is blocked until template generation has completed.

Admins may edit `place-template` manually after generation.

While an administrator is in `place-template`, the Admin menu shows a **Guardar plantilla** button. It captures the edited template snapshot and, after a successful save, returns the administrator to the configured lobby spawn. The button is hidden in other worlds and is available only while the event is inactive.

### Snapshot contents

At event start, freeze a versioned template snapshot containing:

- Block types and ordinary block data/state.
- Every non-air block in the managed interior and visible outline across the full world height.

Do not copy:

- Container inventories
- Sign text or other block-entity data
- Mobs, armor stands, paintings, item frames, or other entities
- Template biome data

### Refresh during Active

- Admins may explicitly refresh the frozen snapshot after editing `place-template`.
- Completed plots are never changed.
- Any queued or partially generated plot is canceled and cleared.
- After the new snapshot is captured, queued requests restart using the new snapshot.
- Future plot requests use the newest snapshot version.

### Generation queue

- Solid dirt-to-bottom plots must not be generated in one blocking server tick.
- Plot generation uses a global bounded per-tick work queue with physics disabled and visible Spanish progress in the Action Bar.
- The player waits at the configured lobby spawn until generation completes.
- The global event timer continues while players wait.
- If the event expires first, the partial plot is cleared and the request does not become a participant.
- At most 50 completed or reserved plot slots may exist.
- Generation progress and cleanup are restart-safe.

Completed plot blocks are stored normally in the `place` world. Plot ownership, settings, assignment order, and generation state are stored by the plugin.

## Player state and inventory isolation

Only a contestant actively inside their own plot during `ACTIVE` uses an isolated event state.

Before entering their own plot, save their normal:

- Inventory contents
- Armor
- Offhand
- Selected hotbar slot
- Cursor item
- Health
- Food level
- Saturation
- Exhaustion
- Active potion effects
- Game mode
- Allow-flight and flying flags

Then load that player's persistent event inventory/state and set them to Creative.

- XP is never saved/restored because it is globally forced to level 2026 with a full bar.
- Leaving the owner's plot, disconnecting, timeout, reset, or plugin shutdown saves event state and restores normal state.
- On Active reconnect, normal state is restored in the lobby; using the portal loads the saved event state again.
- Moving from the owner's plot to another plot immediately restores normal state and changes the player to survival view-only with flight.
- Players, Judges, and Admins who are not actively building their own contestant plot keep their normal inventory. Staff cannot own contestant plots.
- Ender Chest access is always blocked in `place`.
- State swaps use persisted transaction markers and atomic saves so a crash cannot duplicate or lose the normal inventory.

### Dropped items

- An owner may drop and pick up items only while inside their own plot during Active.
- Other players cannot drop or pick up item entities in `place`.
- An owner's dropped items remain associated with and inside that plot.
- Dropped item entities count toward the plot entity limit.

## Plot modes, movement, and void recovery

- During Active, an owner inside their own plot is Creative and may build/interact within the interior.
- Everyone else in `place` is survival view-only with flight enabled.
- View-only cancels block/entity interaction, placement, breaking, container use, item pickup, and item dropping, except for Admin protection bypass where explicitly allowed.
- During Reviewing and Complete, all ordinary players and Judges are survival view-only with flight.
- Admins retain their protection bypass after timeout and completion.
- When leaving `place`, restore any temporary game-mode and flight values.
- All damage to players is canceled in `place`, including PvP, fire, lava, explosion, suffocation, fall, and void damage.

### Void recovery

- Track each player's last grounded position in `place` where the supporting block was solid.
- Trigger recovery only when the player is below the world's minimum build height, is moving downward, and is not actively flying.
- If the remembered supporting blocks still exist, calculate velocity that carries the player back toward that location.
- Do not apply recovery velocity to a player whose flying flag is active.
- If the remembered target was removed or cannot be reached safely, use the plot safe-arrival search as a fallback.
- Void damage remains canceled throughout recovery.

## Portal

- `/furryplace portal-wand` is Admin-only and player-only.
- It gives a tagged feather named `<white>Set portal</white>`.
- Clicking a Nether Portal block is accepted only in `lobby`; other worlds are rejected.
- The plugin detects and persists every connected portal block in that portal opening.
- Selecting another portal replaces the previous selection.
- The controlled portal never performs normal Nether travel.
- Portal contact is detected while the player is standing on or within the selected portal blocks, including Survival, before normal Nether travel can begin.

Portal routing is internal and does not execute command text:

- `INACTIVE`: cancel entry, push backward, show inactive message.
- `ACTIVE`: call the same internal service as `/furryplace join` for the entering player.
- `REVIEW_PENDING`: keep the player in the lobby and show the waiting-for-review message.
- `REVIEWING`: send the player to the plot currently being reviewed.
- `JUDGING`: keep the player with everyone else at the configured lobby spawn.
- `COMPLETE`: send the player to the confirmed winner's plot.

If the selected portal no longer contains valid portal blocks, it remains inactive and reports a clear Admin warning rather than allowing accidental Nether travel.

## Environmental wands

Every wand is tagged with Persistent Data Container data and cannot be identified only by material or visible name.

### Weather Wand

- Material: `BREEZE_ROD`
- Name: `<color:#e3ff59>Cambiar el clima</color>`
- Lore:
  - `<gray>Click derecho para cambiar el</gray>`
  - `<gray>clima de tu parcela!</gray>`
- Right-click opens weather choices: Despejado, Lluvia, and Tormenta.

### Time Wand

- Material: `FEATHER`
- Name: `<color:#e3ff59>Cambiar el tiempo</color>`
- Lore:
  - `<gray>Click derecho para cambiar el</gray>`
  - `<gray>tiempo de tu parcela!</gray>`
- Right-click opens time choices: Amanecer, Día, Mediodía, Atardecer, Noche, and Medianoche.

### Biome Wand

- Material: `STICK`
- Name: `<color:#e3ff59>Cambiar el bioma</color>`
- Lore:
  - `<gray>Click derecho para cambiar</gray>`
  - `<gray>la coloración de tu parcela!</gray>`
- Right-click opens a paginated, searchable list of every biome available in the server registry.
- Search accepts normalized Spanish display names and namespaced registry keys.

### Wand behavior

- Only the owner may use wands, only inside their own plot, and only during Active.
- Settings are stored per plot and apply visually to the entire 80 x 80 interior.
- Every player currently inside that plot sees the owner's saved setting.
- Changing a setting updates every current viewer.
- Leaving the plot restores normal client weather, time, and biome only for the player who left; it does not clear the plot setting for others.
- Entering or rejoining the plot reapplies its saved settings.
- Weather/time use player-specific server behavior; biome changes are client-only PacketEvents spoofing and never modify real world biome data.
- Each menu starts with no saved selection. There is no reset-to-default button; after selection, that category can only be replaced by another explicit selection until the event is reset.
- A player may possess only one copy of each wand.
- Restoring a wand removes the old tagged copy and reissues it.
- Wands are assigned from hotbar slot 0 upward. If a required slot contains a normal item, move it to a free inventory slot first. Never delete or overwrite the displaced item. If no free slot exists, report the error and do not change the inventory.

## Voting and winner selection

### Community vote

- Only participating Players may vote.
- Each Player has at most one community vote.
- Self-voting is blocked.
- Clicking another contestant switches the vote.
- Clicking the currently selected contestant removes the vote and returns the voter to abstaining.
- Community voting is available only during Active and locks at timeout.
- Community totals are publicly visible live in browse/vote menus.
- Only the total is public; community voter names are never displayed.
- The voter's selected player head glows.
- Community totals are advisory only.

### Judge selection

- Each Judge has at most one selection.
- Judges may select, switch, or remove their selection during Reviewing and Judging.
- Each contestant displays the judge-selection count plus the names of Judges who selected them.
- Judge selections lock when the controlling Admin closes judging.
- Judge majority proposes the winner; Admin resolves ties/no-vote cases and may override before confirmation.

## Protection rules

### General boundary protection

- Owners may modify only their own 80 x 80 interior during Active.
- Judges are always view-only in contestant plots.
- Players viewing another plot are always view-only.
- After timeout, Players and Judges cannot modify any contestant plot.
- Admins retain bypass access.
- Block placement/breaking, buckets, hanging entities, vehicles, containers, redstone movement, pistons, fluids, explosions, projectiles, entity interaction, and indirect dispensers must all respect plot ownership and the logical boundary.

### Prohibited content

Contestants cannot use or create:

- Command blocks, structure blocks, jigsaws, barriers, and similar operator-only items
- Bedrock, end portals, and similar unobtainable blocks
- Hostile spawn eggs and mob spawners
- TNT minecarts, end crystals, and explosive beds/respawn anchors
- Wither structures
- Iron or snow golem structures

When a prohibited creative item enters the owner's event inventory:

- Replace the stack in its original slot with one tagged explanation barrier.
- The barrier's Spanish MiniMessage name/lore comes from YAML.
- It cannot be placed, used, stored, or dropped.
- Remove that exact tagged barrier automatically after five seconds.
- Send the matching throttled warning.

### Fire, fluids, TNT, and explosions

- Lava, flint and steel, fire charges, and visual fire are allowed inside the owner's plot.
- Fire cannot spread, burn blocks, or damage entities.
- Lava may flow inside the plot but cannot cross the logical boundary and cannot damage players.
- TNT blocks may remain placed, but every ignition path is canceled.
- All explosion block/entity damage in `place` is canceled.
- Pistons and fluids work only when their complete effect remains inside the same owner's interior.

### Entities

- Hostile mobs are removed automatically and trigger the throttled warning.
- Permitted passive/decorative entities are allowed only inside the owner's plot.
- Maximum permitted non-player entities per plot: 50, including passive mobs, boats, minecarts, armor stands, paintings, item frames, and dropped item entities.
- Attempts above the limit are canceled with a throttled warning.
- A permitted entity crossing the boundary is returned to its last valid location inside its plot; remove it only if no valid return location exists.
- Wither and golem creation is blocked before the structure consumes its blocks where the API permits.

## Commands

### Player commands

- `/furryplace join`
  - Self-only; it never accepts a player argument.
  - During Active, joins or queues generation of the executor's own plot.
  - The controlled portal calls the same internal join service directly.
- `/furryplace menu`
  - Opens the stage- and role-specific main menu.
- `/furryplace browse`
  - Opens the paginated player-head browser when browsing is allowed.
- `/furryplace view <player_name>`
  - Teleports to that player's completed plot in view-only mode.
  - Targeting oneself behaves like `/furryplace join` during Active and like a view-only self-visit after completion.
- `/furryplace tool weather [player_name]`
- `/furryplace tool time [player_name]`
- `/furryplace tool biome [player_name]`
  - Without a name, an eligible owner restores their own wand while inside their plot.
  - Supplying a target requires Admin permission; the target must be an online contestant currently inside their own plot.
- `/furryplace winner`
  - Shortcut to the appropriate Judge/Admin review, judging, or winner menu.
  - Winner controls are also available through `/furryplace menu`.

### Admin commands

- `/furryplace set-spawn`
- `/furryplace portal-wand`
- `/furryplace template generate`
- `/furryplace template refresh`
- `/furryplace reset`
  - Opens or initiates the same confirmed `Cancelar y reiniciar evento` flow; it is not a second reset implementation.

Player-only commands return a clear Spanish error to non-player senders rather than silently doing nothing. Administrative console support is allowed only for operations that do not require a player location or inventory; targeted tool restoration requires an explicit online player.

## Menus and text input

- Every player always has a tagged Nether Star in hotbar slot 8 named `<yellow><b>Menu de FurryPlace</b></yellow>`. Left-click, right-click, and inventory interactions with it open the same stage- and role-specific menu as `/furryplace menu`; it is restored on every world and inventory transition.
- Menus are runtime-configurable from YAML.
- YAML controls inventory size, title, materials, names, lore, slots, glow, and optional sounds.
- Code owns a fixed validated set of action IDs; YAML cannot execute arbitrary commands or code.
- Invalid materials, sizes, slots, actions, or MiniMessage are logged clearly and fall back safely without crashing the event.
- Missing menus must be created in the same black-glass-pane, Spanish MiniMessage style as `menus/start-event.yml`.
- Dynamic player/biome lists use 54-slot paginated menus with navigation, search, close/back controls, and enough capacity for 50 contestants.
- Custom duration and search text use a simulated sign editor through PacketEvents.
- Closing or timing out a simulated sign returns safely to the originating menu without changing state.
- The existing start-menu placeholder must become a named validated placeholder, stray carriage returns must be removed, and Spanish spelling must be corrected during implementation.

Required menu groups include:

- Inactive event information
- Admin start/duration/configuration
- Active owner controls and wand access
- Browse/community voting
- Judge review selection
- Admin review start and controller navigation
- Post-review judging and winner confirmation
- Complete winner display
- Weather, time, and biome selection
- Destructive reset and template confirmations

## Messages and cooldowns

- All chat, titles, subtitles, Action Bars, item names/lore, menu text, warnings, and announcements use MiniMessage strings loaded from YAML.
- Item names and lore default to non-italic; a message must explicitly use `<i>...</i>` to enable italics.
- Warning cooldowns are tracked per player and per message key for five seconds.
- One warning category must not suppress a different warning.
- Countdown thresholds are separate message keys, allowing the 5-to-1 second countdown to display correctly.
- Persistent review/progress Action Bars and one-time critical administrative results are not lost behind an unrelated warning cooldown.

## Persistence and restart behavior

Persist at minimum:

- Event state and absolute deadline
- Current configured duration
- Template initialization and frozen snapshot version
- Plot index, bounds, owner UUID, last known name, and generation status
- Plot time, weather, and biome selections
- Normal/event owner-state transaction data
- Community votes
- Judge selections
- Review order, visited set, current index, and controlling Admin UUID
- Winner and delayed winner-notification state
- Lobby spawn
- Controlled portal blocks
- Long-running generation/reset progress

Use human-inspectable YAML for configuration and low-volume event metadata, with separate atomic per-player state files for inventory safety. Writes must use temporary files plus atomic replacement where supported. World block changes remain in the world save.

Restart rules:

- Active resumes from its absolute deadline or transitions to Review Pending if expired.
- Reviewing resumes at the same plot with the same visited set, paused until the previous controller returns or another Admin takes control.
- Judging resumes with all selections intact.
- Complete retains the winner and join routing.
- Partial plot generation is cleared and restarted from the saved queue/snapshot version.
- Template generation and destructive reset resume idempotently.
- On disable, every online owner state swap is saved transactionally and normal player state is restored where safely possible.

## Build and verification requirements

- Build a single Leaf/Paper plugin JAR for Java 25 and Leaf 26.2.
- Declare PacketEvents as a hard runtime dependency.
- Do not require ProtocolLib, VoidGen, a database server, WorldEdit, or direct LuckPerms API integration.
- Keep Paper/Leaf world and entity mutations on the server thread; only immutable serialization and disk I/O may run asynchronously.
- Batch large template, generation, and reset operations so the main thread remains responsive.

Automated tests must cover:

- State transitions, deadlines, restart recovery, and announcement thresholds
- Role precedence and command permission checks
- Plot index/bounds calculations through plot 50
- Community and judge vote switching/removal/majority/tie behavior
- Timer adjustments and threshold rearming
- Inventory/state swap transactions and crash recovery
- Menu configuration validation and safe MiniMessage placeholders
- Portal connected-block selection and stage routing
- Template snapshot version/queue refresh behavior
- Generation/reset queue persistence

Manual test-server acceptance must cover:

- Hardcore-heart spoof without real hardcore death behavior
- Level 2026/full-bar enforcement
- Sign input on the actual client version
- Visual biome, time, and weather for multiple simultaneous viewers
- Owner/viewer boundary transitions and inventory isolation
- Every blocked indirect interaction path, fire/fluid/piston boundary behavior, explosions, hostile mobs, and entity cap
- Void velocity recovery, flying exemption, and safe fallback
- Review cycling with reconnect/takeover, all 50 pages/plots, judging, winner announcement, and post-completion login routing
- Server restart during Active, generation, Reviewing, Judging, Complete, and reset
