# ChronoArena (Project 2)

A real-time multiplayer capture-zone game built with Java/Swing, TCP (state broadcast), and UDP (player input).

---

## Requirements

- Java 11 or later
- All source files in `src/`

---

## Build

From the project root:

```bash
javac -d out src/*.java
```

---

## Run

### 1. Start the server (one terminal)

```bash
java -cp out Server
```

The server reads configuration from `properties.properties` in the working directory.

### 2. Start each client (one terminal per player)

```bash
java -cp out ChronoArenaClientUI
```

A name dialog appears — enter your name and click **Join**. The game window opens once connected.

> To test on one machine, open two client terminals side by side. Both connect to `localhost` by default.  
> To play across machines, set `serverIP` in `properties.properties` to the server's IP address before starting clients.

---

## Controls

| Key | Action |
|-----|--------|
| `W` `A` `S` `D` or Arrow keys | Move |
| `F` or `Space` | Fire freeze ray (requires weapon pickup) |
| `Esc` or `Q` | Quit |

---

## Server console commands

Type these in the server terminal while it is running:

| Command | Effect |
|---------|--------|
| `LIST` | Show all connected players and their IDs |
| `RESET` | End the current round and start a new one |
| `KILL <id>` | Forcibly disconnect a player by ID |
| `STOP` | Shut down the server |

---

## Gameplay

- **Zones** — capture zones by standing inside them. A zone is captured after `zone_capture_sec` seconds of uncontested presence.
- **Contested** — if two or more players are in the same zone, capture is paused and the zone turns amber.
- **Grace period** — after a zone is captured, the owner keeps it for `zone_grace_sec` seconds even if they leave.
- **Score** — players earn `zone_points_per_sec` points every second they own a zone.
- **Energy pickups** — grant bonus points instantly.
- **Freeze-ray pickups** — grant the weapon. Press `F` or `Space` to fire at the nearest enemy, freezing them for `freeze_duration_sec` seconds.

Round ends when the timer reaches zero. The player with the highest score wins.

---

## Configuration

Edit `properties.properties` to adjust the game:

| Property | Default | Description |
|----------|---------|-------------|
| `serverIP` | `localhost` | Server address (clients only) |
| `TCP_port` | `1234` | TCP port |
| `UDP_port` | `1235` | UDP port |
| `map_width` / `map_height` | `900` / `650` | Map dimensions in pixels |
| `round_duration_sec` | `180` | Round length in seconds |
| `zone_count` | `4` | Number of capture zones (4–8) |
| `zone_capture_sec` | `3` | Seconds to capture an unclaimed zone |
| `zone_grace_sec` | `5` | Seconds owner keeps zone after leaving |
| `zone_points_per_sec` | `5` | Points per second per owned zone |
| `energy_item_points` | `25` | Points from an energy pickup |
| `freeze_duration_sec` | `4` | How long a frozen player is immobilised |
| `freeze_cooldown_sec` | `8` | Cooldown between freeze shots |
