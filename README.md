# Uranium

A combat and utility client for Minecraft 26.1.2 – 26.3 (Fabric). It loads into a game that's already running, so there's no mod folder to set up.

## How to use it

1. Start Minecraft with Fabric and wait for the main menu.
2. Double-click **Uranium.bat** (or **Start.bat** in the shared zip), pick Minecraft and click **Open GUI**.
3. In the game, press **Numpad 8**. Left-click a module to turn it on, right-click it for settings.

> Inject once per game launch. To load a new version, restart Minecraft first or Unload.

## What's inside

**Combat**: Spear Dash, Shield Break, Hit, Slam Saver, Slam Follow-up, Slam Steer, World Slash, Aim Helper, Mace Swap, Elytra Mace, Auto Totem, Auto Weapon, Reach Swap, Quick Throw, Pearl Catch, Chest Swap

**UHC**: Web Escape, Web Trap, Wind Clutch, MLG Water, W-Tap, Jump Reset, Auto Gapple

**Utility**: Auto Tool, Auto Sprint, Auto Walk, Auto Eat, Auto Fish, Fullbright, Zoom, Clear Weather, Auto Respawn, Hit Sound, Middle Click Friend

**HUD**: Speed HUD, Radar, Landing, Combat stats, Slam Meter, Punish Alerts, Wheels, Hide other clients

Every module has a one-line description in the menu. Settings save automatically.

## Build it yourself

Use the Java 25 that comes with the Minecraft Launcher, then run `gradle jar`. The jar lands in `build/libs`, and `gradle shareZip` makes a zip you can send to friends.
