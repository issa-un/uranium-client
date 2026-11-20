package app;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * What the menu shows: every category, its modules and their settings, read live from the
 * modules so the menu always shows the current state. The menu only lays these out.
 */
final class Modules {
    /** A setting row under a module. */
    sealed interface Row permits Toggle, Choice, Bind, Action, Slider, Shown {}

    /** On or off, shown as a switch. */
    record Toggle(String label, BooleanSupplier on, Runnable flip) implements Row {}

    /** One of a few values; clicking opens a dropdown of them (found by stepping through). */
    record Choice(String label, Supplier<String> value, Runnable next) implements Row {}

    /** A key or mouse button; clicking waits for the next press. */
    record Bind(String label, Supplier<String> value, BooleanSupplier awaiting, Runnable await) implements Row {}

    /** A button. */
    record Action(Supplier<String> label, Runnable run) implements Row {}

    /** A number on a track: drag it (a range has a min and a max knob); right-click resets it. */
    record Slider(String label, Num num) implements Row {}

    /** A setting that only shows while it matters (like the sliders of one aim mode). */
    record Shown(BooleanSupplier when, Row row) implements Row {}

    /** The settings to show right now: hidden ones left out. */
    static List<Row> visible(List<Row> rows) {
        List<Row> out = new java.util.ArrayList<>();
        for (Row row : rows) {
            if (row instanceof Shown shown) {
                if (shown.when().getAsBoolean()) {
                    out.add(shown.row());
                }
            } else {
                out.add(row);
            }
        }
        return out;
    }

    record Module(String name, String about, BooleanSupplier on, Runnable flip, List<Row> settings) {}

    record Category(String name, String icon, int color, List<Module> modules) {}

    private Modules() {}

    static List<Category> categories(Minecraft minecraft) {
        boolean hud = VelocityHud.isAvailable();
        return List.of(
                new Category("Combat", "C", 0xFF39FF14, List.of(
                        new Module("Play Style", "On: Cracked, snappy flicks and quick uneven timing that speed up as a fight heats up. Off: Calm",
                                PlayStyle::isCracked, PlayStyle::toggle, List.of(
                                new Choice("Style", PlayStyle::label, PlayStyle::toggle),
                                new Action(() -> HandProfile.isMeasured() ? "Mouse test again" : "Take the mouse test",
                                        () -> Mc.setScreen(minecraft, new MouseTestScreen())),
                                new Action(() -> HandProfile.isMeasured() ? "Back to an average hand" : "Using an average hand",
                                        () -> {
                                            HandProfile.reset();
                                            SpearSettings.save(minecraft);
                                        }))),
                        new Module("Spear Dash", "Swap, lunge and swap back on a key",
                                MinecraftSpearDash::isEnabled, MinecraftSpearDash::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(MinecraftSpearDash.keyCode(), MinecraftSpearDash.mouseButton()),
                                        MinecraftSpearDash::isAwaitingBinding, MinecraftSpearDash::awaitBinding),
                                new Toggle("Only from wind charge", MinecraftSpearDash::isOnlyFromWindCharge,
                                        MinecraftSpearDash::toggleOnlyFromWindCharge),
                                new Choice("Dashes", MinecraftSpearDash::dashCountLabel, MinecraftSpearDash::cycleDashCount),
                                new Choice("Flat lunge", MinecraftSpearDash::flatModeLabel, MinecraftSpearDash::cycleFlatMode),
                                new Toggle("Ready sound", MinecraftSpearDash::isReadySound, MinecraftSpearDash::toggleReadySound),
                                new Slider("Swap delay", MinecraftSpearDash.SWAP_DELAY),
                                new Slider("Jab delay", MinecraftSpearDash.JAB_DELAY),
                                new Slider("Return", MinecraftSpearDash.RETURN),
                                new Slider("Chain gap", MinecraftSpearDash.CHAIN_GAP))),
                        new Module("Auto Stun", "Axe, then mace, through a raised shield",
                                ShieldBreak::isEnabled, ShieldBreak::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(ShieldBreak.keyCode(), ShieldBreak.mouseButton()),
                                        ShieldBreak::isAwaitingBinding, ShieldBreak::awaitBinding),
                                new Toggle("On attack click", ShieldBreak::isOnAttackClick, ShieldBreak::toggleOnAttackClick),
                                new Choice("Trigger", ShieldBreak::triggerLabel, ShieldBreak::toggleAnyAttack),
                                new Choice("Axe hits", () -> ShieldBreak.axeHitsLabel(minecraft), ShieldBreak::cycleAxeHits),
                                new Toggle("Mace follow-up", ShieldBreak::isMaceFollowUp, ShieldBreak::toggleMaceFollowUp),
                                new Slider("Chance", ShieldBreak.CHANCE),
                                new Slider("Swap delay", ShieldBreak.SWAP_DELAY),
                                new Slider("Hit delay", ShieldBreak.HIT_DELAY),
                                new Slider("Between hits", ShieldBreak.BETWEEN_HITS),
                                new Slider("Axe to mace", ShieldBreak.AXE_TO_MACE),
                                new Slider("Swap back", ShieldBreak.SWAP_BACK))),
                        new Module("Trigger Bot", "Hits what you look at whenever the hit will count",
                                Hit::isEnabled, Hit::toggleEnabled, List.of(
                                new Toggle("Auto", Hit::isAuto, Hit::toggleAuto),
                                new Slider("Chance", Hit.CHANCE),
                                new Toggle("Random delay", Hit::isRandomDelay, Hit::toggleRandomDelay),
                                new Slider("Delay", Hit.DELAY))),
                        new Module("Knockback Displacement", "Your sprint hit pushes them into a hazard, or keeps them in your path",
                                KnockbackSteer::isEnabled, KnockbackSteer::toggleEnabled, List.of(
                                new Choice("Push", KnockbackSteer::modeLabel, KnockbackSteer::cycleMode),
                                new Slider("Max turn", KnockbackSteer.MAX_TURN))),
                        new Module("Hit Select", "Holds a slam clicked into their hit immunity and sends it the tick it counts",
                                SlamSaver::isEnabled, SlamSaver::toggleEnabled, List.of()),
                        new Module("Slam Follow-up", "After your slam, a sword hit the moment their immunity ends",
                                SlamFollowUp::isEnabled, SlamFollowUp::toggleEnabled, List.of()),
                        new Module("Target Strafe", "Air-strafes you onto the player below while you fall into a slam",
                                SlamSteer::isEnabled, SlamSteer::toggleEnabled, List.of()),
                        new Module("Kill Aura", "Quiet kill aura: turns onto who's in front of you and hits when it counts",
                                WorldSlash::isEnabled, WorldSlash::toggleEnabled, List.of(
                                new Slider("Range", WorldSlash.RANGE),
                                new Slider("Rotation speed", WorldSlash.SPEED),
                                new Slider("FOV", WorldSlash.FOV),
                                new Slider("Reaction", WorldSlash.REACTION),
                                new Slider("Chance", WorldSlash.CHANCE),
                                new Toggle("Mobs too", WorldSlash::isMobs, WorldSlash::toggleMobs))),
                        new Module("Aim Assist", "Locks onto the torso of anyone near, even behind you, until you yank away",
                                AimHelper::isEnabled, AimHelper::toggleEnabled, List.of(
                                new Toggle("Drift Lock", AimHelper::isDriftLock, AimHelper::toggleDriftLock))),
                        new Module("Mace Swap", "Slams worth 3+ hearts go out with a mace",
                                MaceSwap::isEnabled, MaceSwap::toggleEnabled, List.of()),
                        new Module("Elytra Mace", "Diving at who you look at, your elytra comes off so the hit slams",
                                ElytraMace::isEnabled, ElytraMace::toggleEnabled, List.of(
                                new Toggle("Glide again", ElytraMace::isGlideAgain, ElytraMace::toggleGlideAgain))),
                        new Module("Auto Totem", "Keeps a totem in your offhand",
                                AutoTotem::isEnabled, AutoTotem::toggleEnabled, List.of(
                                new Choice("Mode", AutoTotem::modeLabel, AutoTotem::cycleMode),
                                new Slider("Reaction", AutoTotem.REACTION),
                                new Slider("Step delay", AutoTotem.STEP_DELAY))),
                        new Module("Auto Weapon", "Your best weapon when you hit holding a tool",
                                AutoWeapon::isEnabled, AutoWeapon::toggleEnabled, List.of(
                                new Choice("Trigger", AutoWeapon::triggerLabel, AutoWeapon::toggleAnyAttack),
                                new Slider("Chance", AutoWeapon.CHANCE),
                                new Slider("Swap delay", AutoWeapon.SWAP_DELAY),
                                new Slider("Hit delay", AutoWeapon.HIT_DELAY))),
                        new Module("Reach Swap", "The spear's 4.5-block reach for players",
                                ReachSwap::isEnabled, ReachSwap::toggleEnabled, List.of(
                                new Choice("Mode", ReachSwap::modeLabel, ReachSwap::cycleMode),
                                new Slider("Switch back", ReachSwap.SWITCH_BACK))),
                        new Module("Quick Throw", "A pearl or wind charge on a key",
                                QuickThrow::isEnabled, QuickThrow::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(QuickThrow.keyCode(), QuickThrow.mouseButton()),
                                        QuickThrow::isAwaitingBinding, QuickThrow::awaitBinding),
                                new Choice("Item", QuickThrow::itemLabel, QuickThrow::cycleItem),
                                new Slider("Swap delay", QuickThrow.SWAP_DELAY),
                                new Slider("Throw delay", QuickThrow.THROW_DELAY),
                                new Slider("Swap back", QuickThrow.SWAP_BACK))),
                        new Module("Pearl Catch", "A wind charge meets your pearl mid-air",
                                PearlCatch::isEnabled, PearlCatch::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(PearlCatch.keyCode(), PearlCatch.mouseButton()),
                                        PearlCatch::isAwaitingBinding, PearlCatch::awaitBinding),
                                new Toggle("On right-click pearl", PearlCatch::isOnRightClick,
                                        PearlCatch::toggleOnRightClick),
                                new Choice("Timing", PearlCatch::timingLabel, PearlCatch::cycleTiming))),
                        new Module("Elytra Swap", "Elytra or chestplate on a key",
                                ChestSwap::isEnabled, ChestSwap::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(ChestSwap.keyCode(), ChestSwap.mouseButton()),
                                        ChestSwap::isAwaitingBinding, ChestSwap::awaitBinding),
                                new Slider("Swap delay", ChestSwap.SWAP_DELAY),
                                new Slider("Use delay", ChestSwap.USE_DELAY),
                                new Slider("Swap back", ChestSwap.SWAP_BACK))))),
                new Category("Cart", "K", 0xFFB2FF59, List.of(
                        new Module("High Tier", "Rail, TNT cart, fire or lava in front of it, then your crossbow",
                                CartHighTier::isEnabled, CartHighTier::toggleEnabled, List.of(
                                new Toggle("Shoot", CartHighTier::isShoot, CartHighTier::toggleShoot),
                                new Toggle("Replenish", CartHighTier::isReplenish, CartHighTier::toggleReplenish),
                                new Toggle("Macro mode", Cart::isMacro, Cart::toggleMacro),
                                new Choice("Self guard", SelfBlast::modeLabel, SelfBlast::cycleMode),
                                new Slider("Guard under", CartHighTier.GUARD_UNDER),
                                new Toggle("Auto reload", CartHighTier::isAutoReload, CartHighTier::toggleAutoReload),
                                new Toggle("Hard switch", CartHighTier::isHardSwitch, CartHighTier::toggleHardSwitch),
                                new Toggle("Overload", CartHighTier::isOverload, CartHighTier::toggleOverload),
                                new Choice("Look", CartHighTier::lookLabel, CartHighTier::cycleLook),
                                new Slider("Delay", CartHighTier.DELAY),
                                new Slider("Chance", CartHighTier.CHANCE))),
                        new Module("Block", "Blocks between you and a TNT cart that gets too close",
                                CartBlock::isEnabled, CartBlock::toggleEnabled, List.of(
                                new Slider("Range", CartBlock.RANGE),
                                new Slider("Blocks", CartBlock.BLOCKS),
                                new Toggle("Jump block", CartBlock::isJumpBlock, CartBlock::toggleJumpBlock),
                                new Toggle("Alert", CartBlock::isAlert, CartBlock::toggleAlert),
                                new Slider("Alert range", CartBlock.ALERT_RANGE))),
                        new Module("Low Tier", "Fire your bow: a rail and TNT cart where the arrow comes down",
                                CartLowTier::isEnabled, CartLowTier::toggleEnabled, List.of(
                                new Toggle("Crossbow too", CartLowTier::isCrossbowToo, CartLowTier::toggleCrossbowToo),
                                new Toggle("Lowest draw", CartLowTier::isLowestDraw, CartLowTier::toggleLowestDraw),
                                new Choice("Look", CartLowTier::lookLabel, CartLowTier::cycleLook),
                                new Toggle("Replenish", CartLowTier::isReplenish, CartLowTier::toggleReplenish),
                                new Toggle("Macro mode", Cart::isMacro, Cart::toggleMacro),
                                new Toggle("Auto reload", CartLowTier::isAutoReload, CartLowTier::toggleAutoReload),
                                new Slider("Delay", CartLowTier.DELAY),
                                new Slider("Chance", CartLowTier.CHANCE))))),
                new Category("UHC", "U", 0xFFC6FF00, List.of(
                        new Module("Web Escape", "Water out of cobwebs and fire",
                                WebEscape::isEnabled, WebEscape::toggleEnabled, List.of(
                                new Toggle("On fire too", WebEscape::isOnFire, WebEscape::toggleOnFire),
                                new Toggle("Pick up water", WebEscape::isPickUp, WebEscape::togglePickUp))),
                        new Module("Auto Web", "Webs the player you look at, on a key",
                                WebTrap::isEnabled, WebTrap::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(WebTrap.keyCode(), WebTrap.mouseButton()),
                                        WebTrap::isAwaitingBinding, WebTrap::awaitBinding),
                                new Toggle("Drain with lava", WebTrap::isDrain, WebTrap::toggleDrain))),
                        new Module("Wind Clutch", "A wind charge under you before a fall that would nearly kill you",
                                WindClutch::isEnabled, WindClutch::toggleEnabled, List.of()),
                        new Module("MLG Water", "Water down before a hard landing",
                                MlgWater::isEnabled, MlgWater::toggleEnabled, List.of(
                                new Choice("Min damage", MlgWater::minDamageLabel, MlgWater::cycleMinDamage),
                                new Toggle("Pick up water", MlgWater::isPickUp, MlgWater::togglePickUp))),
                        new Module("W-Tap", "Sprint reset after every hit",
                                WTap::isEnabled, WTap::toggleEnabled, List.of(
                                new Choice("Trigger", WTap::triggerLabel, WTap::toggleAnyAttack),
                                new Choice("Mode", WTap::modeLabel, WTap::cycleMode),
                                new Toggle("Only sprint hits", WTap::isOnlySprintHits, WTap::toggleOnlySprintHits),
                                new Slider("Chance", WTap.CHANCE),
                                new Slider("Reaction", WTap.REACTION),
                                new Slider("Length", WTap.LENGTH))),
                        new Module("Jump Reset", "Jumps as knockback lands",
                                JumpReset::isEnabled, JumpReset::toggleEnabled, List.of(
                                new Toggle("Only sprinting", JumpReset::isOnlyWhileSprinting,
                                        JumpReset::toggleOnlyWhileSprinting))),
                        new Module("Auto Gapple", "A golden apple when health is low",
                                AutoGapple::isEnabled, AutoGapple::toggleEnabled, List.of(
                                new Choice("Health", AutoGapple::healthLabel, AutoGapple::cycleHealth))))),
                new Category("Utility", "T", 0xFF00E676, List.of(
                        new Module("Auto Tool", "The fastest tool while you mine",
                                AutoTool::isEnabled, AutoTool::toggleEnabled, List.of(
                                new Toggle("Swap back", AutoTool::isSwapBack, AutoTool::toggleSwapBack))),
                        new Module("Auto Sprint", "Sprints whenever you walk forward",
                                Utilities::isAutoSprint, Utilities::toggleAutoSprint, List.of()),
                        new Module("Auto Walk", "Walks forward for you",
                                Utilities::isAutoWalk, Utilities::toggleAutoWalk, List.of()),
                        new Module("Auto Eat", "Eats when you get hungry",
                                AutoEat::isEnabled, AutoEat::toggleEnabled, List.of(
                                new Choice("Hunger", AutoEat::hungerLabel, AutoEat::cycleHunger))),
                        new Module("Auto Fish", "Reels in on a bite and casts again",
                                AutoFish::isEnabled, AutoFish::toggleEnabled, List.of()),
                        new Module("Fullbright", "Lights up caves and nights",
                                Utilities::isFullbright, Utilities::toggleFullbright, List.of()),
                        new Module("Zoom", "Hold a key to zoom in",
                                Utilities::isZoom, Utilities::toggleZoom, List.of(
                                new Bind("Hold", () -> bindLabel(Utilities.zoomKey(), Utilities.zoomMouse()),
                                        Utilities::isAwaitingZoomBinding, Utilities::awaitZoomBinding),
                                new Choice("Zoom", () -> Utilities.zoomFactor() + "x", Utilities::cycleZoomFactor),
                                new Choice("Zoom in", Utilities::zoomStyleLabel, Utilities::cycleZoomStyle))),
                        new Module("Weather", "No rain on your screen, or a cinematic film look",
                                Weather::isEnabled, Weather::toggleEnabled, List.of(
                                new Choice("Mode", Weather::modeLabel, Weather::cycleMode),
                                new Toggle("Film bars", Weather::isBars, Weather::toggleBars))),
                        new Module("Auto Respawn", "Skips the death screen",
                                Utilities::isAutoRespawn, Utilities::toggleAutoRespawn, List.of(
                                new Toggle("Death coords", Utilities::isDeathCoords, Utilities::toggleDeathCoords))),
                        new Module("Hit Sound", "A ding when your hit lands",
                                Utilities::isHitSound, Utilities::toggleHitSound, List.of()),
                        new Module("Middle Click Friend", "Middle-click a player to friend them",
                                Utilities::isMiddleClickFriend, Utilities::toggleMiddleClickFriend, List.of()),
                        // Only shows when you search "debug".
                        new Module("Debug", "Shows how human the modules look and what could get you flagged",
                                Debug::isEnabled, Debug::toggleEnabled, List.of(
                                new Action(() -> "Open the debug screen", () -> Mc.setScreen(minecraft, new DebugScreen())),
                                new Action(AimRecorder::label, AimRecorder::toggle))))),
                new Category("HUD", "H", 0xFF64DD17, List.of(
                        new Module("Speed HUD", hud ? "Speed ring above the hotbar" : "Needs Fabric API's HUD module",
                                VelocityHud::isSpeedHudOn, VelocityHud::toggleSpeedHud, List.of(
                                new Choice("Position", VelocityHud::positionLabel, VelocityHud::togglePosition),
                                new Toggle("Warnings", VelocityHud::isWarningsOn, VelocityHud::toggleWarnings),
                                toggleKey(VelocityHud.TOGGLE_SPEED))),
                        new Module("Radar", hud ? "Nearby players around the crosshair" : "Needs Fabric API's HUD module",
                                VelocityHud::isRadarOn, VelocityHud::toggleRadar, List.of(
                                new Choice("Range", () -> VelocityHud.radarRange() + " m", VelocityHud::cycleRadarRange),
                                new Choice("Team", () -> VelocityHud.isIgnoreTeam() ? "Ignore" : "Show",
                                        VelocityHud::toggleIgnoreTeam),
                                new Action(() -> friendLabel(minecraft), () -> toggleFriend(minecraft)),
                                toggleKey(VelocityHud.TOGGLE_RADAR))),
                        new Module("Slam Meter", "While you fall with a mace: how hard the slam hits them, KILL or POP",
                                SlamMeter::isEnabled, SlamMeter::toggleEnabled, List.of()),
                        new Module("Punish Alerts", "Shows when they eat, pop a totem or lose their shield",
                                PunishAlerts::isEnabled, PunishAlerts::toggleEnabled, List.of(
                                new Toggle("Sound", PunishAlerts::isSound, PunishAlerts::toggleSound))),
                        new Module("2AM", hud ? "A quiet HUD: record ring, ticker, pop dots; fades out of fights"
                                : "Needs Fabric API's HUD module",
                                NightShift::isEnabled, NightShift::toggleEnabled, List.of()),
                        new Module("Armor Slots", hud ? "Your armor in slots by the hotbar; red !!! hop over anything that needs you"
                                : "Needs Fabric API's HUD module",
                                ArmorSlots::isEnabled, ArmorSlots::toggleEnabled, List.of(
                                new Toggle("Alerts", ArmorSlots::isAlerts, ArmorSlots::toggleAlerts))),
                        new Module("Pop Chams", hud ? "A ghost where someone pops a totem, with who and how many"
                                : "Needs Fabric API's rendering module",
                                PopChams::isEnabled, PopChams::toggleEnabled, List.of()),
                        new Module("Damage Particles", hud ? "Old-school red -1s showering off players you hurt"
                                : "Needs Fabric API's rendering module",
                                DamageParticles::isEnabled, DamageParticles::toggleEnabled, List.of()),
                        new Module("Wheels", hud ? "A wheel above players' heads that turns when they get hit"
                                : "Needs Fabric API's rendering module",
                                Wheels::isEnabled, Wheels::toggleEnabled, List.of(
                                new Choice("Spin", Wheels::spinLabel, Wheels::cycleSpin),
                                new Choice("Who", Wheels::whoLabel, Wheels::cycleWho),
                                new Choice("Helmet", Wheels::helmetLabel, Wheels::cycleHelmet))),
                        new Module("Hide UI", "Only the game's own HUD on screen: hides Uranium and every other mod",
                                HudFilter::isHideAll, HudFilter::toggleHideAll, List.of(
                                toggleKey(VelocityHud.TOGGLE_HIDE))),
                        new Module("Hide other clients", "Hides other clients' HUD lists",
                                HudFilter::isEnabled, HudFilter::toggleEnabled, hideRows()),
                        new Module("Landing", hud ? "Fall damage before you land" : "Needs Fabric API's HUD module",
                                VelocityHud::isLandingOn, VelocityHud::toggleLanding, List.of(
                                toggleKey(VelocityHud.TOGGLE_LANDING))),
                        new Module("Combat stats", hud ? "Stun slam data: ping, fall, shield ticks, breaks"
                                : "Needs Fabric API's HUD module",
                                CombatStats::isOn, CombatStats::toggle, List.of()))));
    }

    /** Hide other clients: a switch for each source seen drawing on the HUD (on = hidden). */
    private static List<Row> hideRows() {
        List<String> seen = HudFilter.seen();
        if (seen.isEmpty()) {
            return List.of(new Action(() -> HudFilter.isEnabled() ? "Nothing else has drawn yet"
                    : "Turn on, then sources show here", () -> {}));
        }
        java.util.ArrayList<Row> rows = new java.util.ArrayList<>();
        for (String source : seen) {
            String label = "Hide " + (source.length() > 30 ? source.substring(0, 29) + "\u2026" : source);
            rows.add(new Toggle(label, () -> HudFilter.isHidden(source), () -> HudFilter.toggleHidden(source)));
        }
        return rows;
    }

    /** A key that turns a HUD module on or off without opening the menu. */
    private static Bind toggleKey(int module) {
        return new Bind("Toggle key", () -> VelocityHud.awaitingToggle() == module ? "Press a key (Esc: none)"
                : bindLabel(VelocityHud.toggleKey(module), VelocityHud.toggleMouse(module)),
                () -> VelocityHud.awaitingToggle() == module, () -> VelocityHud.awaitToggleBinding(module));
    }

    /** The player you look at, else the nearest one; friends never set off the Radar. */
    private static String friendLabel(Minecraft minecraft) {
        Player candidate = VelocityHud.friendCandidate(minecraft);
        if (candidate == null) {
            return "Friends: " + VelocityHud.friendCount();
        }
        return (VelocityHud.isFriend(candidate) ? "Unfriend " : "Friend ") + candidate.getName().getString();
    }

    private static void toggleFriend(Minecraft minecraft) {
        Player candidate = VelocityHud.friendCandidate(minecraft);
        if (candidate != null) {
            VelocityHud.toggleFriend(candidate);
        }
    }

    /** A binding's name as the game shows it ("R", "Left Shift", "Mouse 4"), or "None". */
    static String bindLabel(int keyCode, int mouseButton) {
        if (mouseButton >= 0) {
            return MinecraftInputController.mouseLabel(mouseButton);
        }
        if (keyCode < 0) {
            return "None";
        }
        return Mc.keyboard().getOrCreate(keyCode).getDisplayName().getString();
    }
}
