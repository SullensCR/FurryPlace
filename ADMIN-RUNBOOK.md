# FurryplaceEvent administrator runbook

## Install

1. Stop the server and back up `lobby`, `place`, and `place-template`.
2. Install PacketEvents 2.13.0 in the server's `plugins` folder.
3. Copy `FurryplaceEvent-1.0.7.jar` into `plugins` and start Leaf 26.2.
4. Confirm the console prints `FurryplaceEvent 1.0.7 está listo.`

Every player receives the persistent FurryPlace menu Nether Star in hotbar slot 8. It remains available in every world and opens the same menu as `/furryplace menu`.

Admins can test Bedrock forms with `/furryplace test MODAL|SIMPLE|CUSTOM <player_name>` while the target is online and connected through Floodgate.

The plugin does not create missing worlds. If `place` or `place-template` is missing, event functionality is disabled safely.

## One-time setup

1. Stand at the desired position in `lobby` and run `/furryplace set-spawn`.
2. Run `/furryplace portal-wand`, then right-click a Nether Portal block in `lobby`.
3. Run `/furryplace template generate` and confirm the menu. This creates the default ground with a two-block-wide andesite outline level with the grass in `place-template`, captures it, and keeps players in the lobby while progress is shown.
4. If desired, edit the finished `place-template` manually. While standing in `place-template`, open `/furryplace menu` and click **Guardar plantilla** to capture the edits and return to lobby. Starting the event also freezes the current edited template; do not run `template generate` again, because that command intentionally restores the default template first.

Do not stop the server while setup is running unless necessary. If it does stop, the journal restarts the interrupted job safely on the next startup.

## Actualizaciones de configuración

All editable YAML files are versioned. When you install a newer plugin JAR, it backs up every older configuration it updates to `plugins/FurryplaceEvent/backups/<UTC timestamp>/`, writes the new defaults, and carries your existing values into the new files. New settings are added automatically; no manual reconfiguration is needed. Do not edit files inside `backups`; they are recovery copies.

## Run the event

1. Run `/furryplace menu` as an Admin.
2. Open event controls, set the duration, and confirm **Iniciar evento**.
3. Players enter the selected portal voluntarily. A plot is allocated only after its generation finishes.
4. During Active, an Admin can change remaining time from the same event-control menu.
5. To replace the frozen template during Active, use `/furryplace template refresh` and confirm. Completed plots remain untouched; incomplete plots restart from the new snapshot.

## Review, judging, and winner

1. At timeout, incomplete plots are cleared and everyone returns to `lobby`.
2. Open `/furryplace menu`, choose event controls, and select any participant as the first reviewed plot.
3. Use **Anterior**, **Siguiente**, and **Terminar revisión**. Every plot must be visited before review can end.
4. If the controlling Admin disconnects, another Admin opens the review controls and chooses **Tomar control**.
5. Judges vote from `/furryplace winner` or their main menu. Right-click changes/removes a vote; left-click visits the plot.
6. In Judging, an Admin opens `/furryplace winner`, selects the proposed or overridden winner, and confirms the separate winner dialog.

## Reset and recovery

- `/furryplace reset` opens the single destructive confirmation flow.
- Reset restores online inventories, clears all contestant plots and event inventories, and preserves the lobby spawn, selected portal, editable `place-template` world, and YAML configuration.
- Runtime metadata is under `plugins/FurryplaceEvent/data`. Player inventory recovery files are under `data/players`.
- Never manually delete a player recovery file while that player may still have an event inventory loaded.
- For disaster recovery, stop the server first and restore the three world folders plus the complete `plugins/FurryplaceEvent` folder from the same backup point.

## Build from source

From the project directory:

```bash
mvn -Dmaven.repo.local=.m2-local clean test package
```

The JAR is created at `target/FurryplaceEvent-1.0.7.jar`. Source tests cannot prove packet visuals; verify hardcore hearts, sign input, biome overlays, and biome restoration with real 26.2 clients before the event.
