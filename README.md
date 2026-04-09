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

To build a distributable client jar:

```bash
echo "Main-Class: ChronoArenaClientUI" > manifest.txt
jar cfm Client.jar manifest.txt -C out .
```

---

## Run

### 1. Start the server (one terminal)

```bash
java -cp out Server
```

A server window opens with a spectator view of the game and a control panel on the right.

### 2. Start each client (one terminal per player)

```bash
java -cp out ChronoArenaClientUI
```

Or, if using the jar:

```bash
java -jar Client.jar
```

A join dialog appears — enter the **server IP** and your **name**, then click **OK**. The game window opens once connected.

> To test on one machine, open two client terminals side by side. Both connect to `localhost` by default.  
> To play across machines on the same network, find the server's local IP with `ifconfig | grep "inet "` and enter it in the join dialog.  
> To play across different networks, forward TCP `1234` and UDP `1235` on the server's router and use the server's public IP.

---

## Controls

| Key | Action |
|-----|--------|
| `W` `A` `S` `D` or Arrow keys | Move |
| `F` or `Space` | Fire freeze ray (requires weapon pickup) |
| `Esc` or `Q` | Quit |

---

## Server control panel

The server window has a sidebar with the following controls:

| Control | Effect |
|---------|--------|
| **RESET ROUND** button | Ends the current round, resets all scores and zones, keeps all players connected |
| **STOP SERVER** button | Prompts for confirmation, then shuts down the server and disconnects all players |
| **KILL** button (per player) | Forcibly disconnects a specific player |

The player list in the sidebar updates every 500 ms and shows each player's ID, name, and current score.

---

## Gameplay

- **Zones** — capture zones by standing inside them. A zone is captured after `zone_capture_sec` seconds of uncontested presence.
- **Contested** — if two or more players are in the same zone, capture is paused and the zone turns amber.
- **Grace period** — after a zone is captured, the owner keeps it for `zone_grace_sec` seconds even if they leave.
- **Score** — players earn `zone_points_per_sec` points every second they own a zone.
- **Energy pickups** — grant bonus points instantly.
- **Freeze-ray pickups** — grant the weapon. Press `F` or `Space` to fire at the nearest enemy, freezing them for `freeze_duration_sec` seconds. All players see the freeze ray beam when it fires.

Round ends when the timer reaches zero. The player with the highest score wins.

---

## Configuration

Edit `properties.properties` to adjust the game. This file must be in the working directory when the server and clients are started.

| Property | Default | Description |
|----------|---------|-------------|
| `serverIP` | `localhost` | Server address (clients only — overridden by join dialog) |
| `TCP_port` | `1234` | TCP port |
| `UDP_port` | `1235` | UDP port |
| `map_width` / `map_height` | `900` / `650` | Map dimensions in pixels |
| `round_duration_sec` | `180` | Round length in seconds |
| `zone_count` | `4` | Number of capture zones |
| `zone_capture_sec` | `3` | Seconds to capture an unclaimed zone |
| `zone_grace_sec` | `5` | Seconds owner keeps zone after leaving |
| `zone_points_per_sec` | `5` | Points per second per owned zone |
| `energy_item_points` | `25` | Points from an energy pickup |
| `freeze_duration_sec` | `4` | How long a frozen player is immobilised |
| `freeze_cooldown_sec` | `8` | Cooldown between freeze shots |
