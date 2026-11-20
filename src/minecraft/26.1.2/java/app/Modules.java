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
                        new Module("Play Style", "How aim and timing feel: Calm when off, Cracked (fast, uneven, heats up in fights) when on",
                                PlayStyle::isCracked, PlayStyle::toggle, List.of(
                                new Choice("Style", PlayStyle::label, PlayStyle::toggle),
                                new Action(() -> HandProfile.isMeasured() ? "Mouse test again" : "Take the mouse test",
                                        () -> Mc.setScreen(minecraft, new MouseTestScreen())),
                                new Action(() -> HandProfile.isMeasured() ? "Back to an average hand" : "Using an average hand",
                                        () -> {
                                            HandProfile.reset();
                                            SpearSettings.save(minecraft);
                                        }))),
                        new Module("Spear Dash", "Swaps to your spear, lunges and swaps back on one key",
                                SpearDash::isEnabled, SpearDash::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(SpearDash.keyCode(), SpearDash.mouseButton()),
                                        SpearDash::isAwaitingBinding, SpearDash::awaitBinding),
                                new Toggle("Only from wind charge", SpearDash::isOnlyFromWindCharge,
                                        SpearDash::toggleOnlyFromWindCharge),
                                new Choice("Dashes", SpearDash::dashCountLabel, SpearDash::cycleDashCount),
                                new Choice("Flat lunge", SpearDash::flatModeLabel, SpearDash::cycleFlatMode),
                                new Toggle("Ready sound", SpearDash::isReadySound, SpearDash::toggleReadySound),
                                new Slider("Swap delay", SpearDash.SWAP_DELAY),
                                new Slider("Jab delay", SpearDash.JAB_DELAY),
                                new Slider("Return", SpearDash.RETURN),
                                new Slider("Chain gap", SpearDash.CHAIN_GAP))),
                        new Module("Auto Stun", "Axe through a raised shield, then a mace hit while it's down",
                                AutoStun::isEnabled, AutoStun::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(AutoStun.keyCode(), AutoStun.mouseButton()),
                                        AutoStun::isAwaitingBinding, AutoStun::awaitBinding),
                                new Toggle("On attack click", AutoStun::isOnAttackClick, AutoStun::toggleOnAttackClick),
                                new Choice("Trigger", AutoStun::triggerLabel, AutoStun::toggleAnyAttack),
                                new Choice("Axe hits", () -> AutoStun.axeHitsLabel(minecraft), AutoStun::cycleAxeHits),
                                new Toggle("Mace follow-up", AutoStun::isMaceFollowUp, AutoStun::toggleMaceFollowUp),
                                new Slider("Chance", AutoStun.CHANCE),
                                new Slider("Swap delay", AutoStun.SWAP_DELAY),
                                new Slider("Hit delay", AutoStun.HIT_DELAY),
                                new Slider("Between hits", AutoStun.BETWEEN_HITS),
                                new Slider("Axe to mace", AutoStun.AXE_TO_MACE),
                                new Slider("Swap back", AutoStun.SWAP_BACK))),
                        new Module("Trigger Bot", "Hits what you look at whenever the hit will count",
                                TriggerBot::isEnabled, TriggerBot::toggleEnabled, List.of(
                                new Toggle("Auto", TriggerBot::isAuto, TriggerBot::toggleAuto),
                                new Slider("Chance", TriggerBot.CHANCE),
                                new Toggle("Random delay", TriggerBot::isRandomDelay, TriggerBot::toggleRandomDelay),
                                new Slider("Delay", TriggerBot.DELAY))),
                        new Module("Knockback Displacement", "Your sprint hit pushes them into a hazard, or keeps them in your path",
                                KnockbackDisplacement::isEnabled, KnockbackDisplacement::toggleEnabled, List.of(
                                new Choice("Push", KnockbackDisplacement::modeLabel, KnockbackDisplacement::cycleMode),
                                new Slider("Max turn", KnockbackDisplacement.MAX_TURN))),
                        new Module("Hit Select", "Holds a slam clicked too early and sends it the moment it counts",
                                HitSelect::isEnabled, HitSelect::toggleEnabled, List.of()),
                        new Module("Slam Combo", "A sword hit right after your slam, the moment it can count",
                                SlamCombo::isEnabled, SlamCombo::toggleEnabled, List.of()),
                        new Module("Target Strafe", "Steers your fall onto the player below for a slam",
                                TargetStrafe::isEnabled, TargetStrafe::toggleEnabled, List.of()),
                        new Module("Kill Aura", "Turns onto the player in front of you and hits when the hit counts",
                                KillAura::isEnabled, KillAura::toggleEnabled, List.of(
                                new Slider("Range", KillAura.RANGE),
                                new Slider("Rotation speed", KillAura.SPEED),
                                new Slider("FOV", KillAura.FOV),
                                new Slider("Reaction", KillAura.REACTION),
                                new Slider("Chance", KillAura.CHANCE),
                                new Toggle("Mobs too", KillAura::isMobs, KillAura::toggleMobs))),
                        new Module("Aim Assist", "Locks onto the torso of anyone near, even behind you, until you yank away",
                                AimAssist::isEnabled, AimAssist::toggleEnabled, List.of(
                                new Toggle("Drift Lock", AimAssist::isDriftLock, AimAssist::toggleDriftLock))),
                        new Module("Mace Swap", "Pulls out your mace for slams worth 3+ hearts",
                                MaceSwap::isEnabled, MaceSwap::toggleEnabled, List.of()),
                        new Module("Elytra Mace", "Takes your elytra off as you dive at a player, so the hit slams",
                                ElytraMace::isEnabled, ElytraMace::toggleEnabled, List.of(
                                new Toggle("Glide again", ElytraMace::isGlideAgain, ElytraMace::toggleGlideAgain))),
                        new Module("Auto Totem", "Keeps a totem in your offhand",
                                AutoTotem::isEnabled, AutoTotem::toggleEnabled, List.of(
                                new Choice("Mode", AutoTotem::modeLabel, AutoTotem::cycleMode),
                                new Slider("Reaction", AutoTotem.REACTION),
                                new Slider("Step delay", AutoTotem.STEP_DELAY))),
                        new Module("Auto Weapon", "Switches to your best weapon when you hit with a tool",
                                AutoWeapon::isEnabled, AutoWeapon::toggleEnabled, List.of(
                                new Choice("Trigger", AutoWeapon::triggerLabel, AutoWeapon::toggleAnyAttack),
                                new Slider("Chance", AutoWeapon.CHANCE),
                                new Slider("Swap delay", AutoWeapon.SWAP_DELAY),
                                new Slider("Hit delay", AutoWeapon.HIT_DELAY))),
                        new Module("Reach Swap", "Hits players further away with your spear's 4.5-block reach",
                                ReachSwap::isEnabled, ReachSwap::toggleEnabled, List.of(
                                new Choice("Mode", ReachSwap::modeLabel, ReachSwap::cycleMode),
                                new Slider("Switch back", ReachSwap.SWITCH_BACK))),
                        new Module("Quick Throw", "Throws a pearl or wind charge on one key",
                                QuickThrow::isEnabled, QuickThrow::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(QuickThrow.keyCode(), QuickThrow.mouseButton()),
                                        QuickThrow::isAwaitingBinding, QuickThrow::awaitBinding),
                                new Choice("Item", QuickThrow::itemLabel, QuickThrow::cycleItem),
                                new Slider("Swap delay", QuickThrow.SWAP_DELAY),
                                new Slider("Throw delay", QuickThrow.THROW_DELAY),
                                new Slider("Swap back", QuickThrow.SWAP_BACK))),
                        new Module("Pearl Catch", "Sends a wind charge to meet your pearl in the air",
                                PearlCatch::isEnabled, PearlCatch::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(PearlCatch.keyCode(), PearlCatch.mouseButton()),
                                        PearlCatch::isAwaitingBinding, PearlCatch::awaitBinding),
                                new Toggle("On right-click pearl", PearlCatch::isOnRightClick,
                                        PearlCatch::toggleOnRightClick),
                                new Choice("Timing", PearlCatch::timingLabel, PearlCatch::cycleTiming))),
                        new Module("Elytra Swap", "Swaps between elytra and chestplate on one key",
                                ElytraSwap::isEnabled, ElytraSwap::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(ElytraSwap.keyCode(), ElytraSwap.mouseButton()),
                                        ElytraSwap::isAwaitingBinding, ElytraSwap::awaitBinding),
                                new Slider("Swap delay", ElytraSwap.SWAP_DELAY),
                                new Slider("Use delay", ElytraSwap.USE_DELAY),
                                new Slider("Swap back", ElytraSwap.SWAP_BACK))))),
                new Category("Cart", "K", 0xFFB2FF59, List.of(
                        new Module("Crossbow Cart", "Places a rail, TNT cart and fire, then shoots it with your crossbow",
                                CrossbowCart::isEnabled, CrossbowCart::toggleEnabled, List.of(
                                new Toggle("Shoot", CrossbowCart::isShoot, CrossbowCart::toggleShoot),
                                new Toggle("Replenish", CrossbowCart::isReplenish, CrossbowCart::toggleReplenish),
                                new Toggle("Macro mode", Cart::isMacro, Cart::toggleMacro),
                                new Choice("Self guard", SelfBlast::modeLabel, SelfBlast::cycleMode),
                                new Slider("Guard under", CrossbowCart.GUARD_UNDER),
                                new Toggle("Auto reload", CrossbowCart::isAutoReload, CrossbowCart::toggleAutoReload),
                                new Toggle("Hard switch", CrossbowCart::isHardSwitch, CrossbowCart::toggleHardSwitch),
                                new Toggle("Overload", CrossbowCart::isOverload, CrossbowCart::toggleOverload),
                                new Choice("Look", CrossbowCart::lookLabel, CrossbowCart::cycleLook),
                                new Slider("Delay", CrossbowCart.DELAY),
                                new Slider("Chance", CrossbowCart.CHANCE))),
                        new Module("Anti Cart", "Puts blocks between you and an enemy TNT cart that gets close",
                                AntiCart::isEnabled, AntiCart::toggleEnabled, List.of(
                                new Slider("Range", AntiCart.RANGE),
                                new Slider("Blocks", AntiCart.BLOCKS),
                                new Toggle("Jump block", AntiCart::isJumpBlock, AntiCart::toggleJumpBlock),
                                new Toggle("Alert", AntiCart::isAlert, AntiCart::toggleAlert),
                                new Slider("Alert range", AntiCart.ALERT_RANGE))),
                        new Module("Bow Cart", "Places a rail and TNT cart where your arrow will land",
                                BowCart::isEnabled, BowCart::toggleEnabled, List.of(
                                new Toggle("Crossbow too", BowCart::isCrossbowToo, BowCart::toggleCrossbowToo),
                                new Toggle("Lowest draw", BowCart::isLowestDraw, BowCart::toggleLowestDraw),
                                new Choice("Look", BowCart::lookLabel, BowCart::cycleLook),
                                new Toggle("Replenish", BowCart::isReplenish, BowCart::toggleReplenish),
                                new Toggle("Macro mode", Cart::isMacro, Cart::toggleMacro),
                                new Toggle("Auto reload", BowCart::isAutoReload, BowCart::toggleAutoReload),
                                new Slider("Delay", BowCart.DELAY),
                                new Slider("Chance", BowCart.CHANCE))))),
                new Category("UHC", "U", 0xFFC6FF00, List.of(
                        new Module("Web Escape", "Gets you out of cobwebs and fire with water",
                                WebEscape::isEnabled, WebEscape::toggleEnabled, List.of(
                                new Toggle("On fire too", WebEscape::isOnFire, WebEscape::toggleOnFire),
                                new Toggle("Pick up water", WebEscape::isPickUp, WebEscape::togglePickUp))),
                        new Module("Auto Web", "Webs the player you're looking at, on one key",
                                AutoWeb::isEnabled, AutoWeb::toggleEnabled, List.of(
                                new Bind("Key", () -> bindLabel(AutoWeb.keyCode(), AutoWeb.mouseButton()),
                                        AutoWeb::isAwaitingBinding, AutoWeb::awaitBinding),
                                new Toggle("Drain with lava", AutoWeb::isDrain, AutoWeb::toggleDrain))),
                        new Module("Wind Clutch", "Throws a wind charge under you before a fall that would nearly kill you",
                                WindClutch::isEnabled, WindClutch::toggleEnabled, List.of()),
                        new Module("MLG Water", "Places water under you before a hard landing",
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
                        new Module("Jump Reset", "Jumps as knockback hits you, for less knockback",
                                JumpReset::isEnabled, JumpReset::toggleEnabled, List.of(
                                new Toggle("Only sprinting", JumpReset::isOnlyWhileSprinting,
                                        JumpReset::toggleOnlyWhileSprinting))),
                        new Module("Auto Gapple", "Eats a golden apple when your health is low",
                                AutoGapple::isEnabled, AutoGapple::toggleEnabled, List.of(
                                new Choice("Health", AutoGapple::healthLabel, AutoGapple::cycleHealth))))),
                new Category("Utility", "T", 0xFF00E676, List.of(
                        new Module("Auto Tool", "Picks the fastest tool while you mine",
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
                        new Module("Middle Click Friend", "Middle-click a player to add them as a friend",
                                Utilities::isMiddleClickFriend, Utilities::toggleMiddleClickFriend, List.of()),
                        // Only shows when you search "debug".
                        new Module("Debug", "Shows how human the modules look and what could get you flagged",
                                Debug::isEnabled, Debug::toggleEnabled, List.of(
                                new Action(() -> "Open the debug screen", () -> Mc.setScreen(minecraft, new DebugScreen())),
                                new Action(AimRecorder::label, AimRecorder::toggle))))),
                new Category("HUD", "H", 0xFF64DD17, List.of(
                        new Module("Speedometer", hud ? "A speed ring above your hotbar" : "Needs Fabric API's HUD module",
                                VelocityHud::isSpeedHudOn, VelocityHud::toggleSpeedHud, List.of(
                                new Choice("Position", VelocityHud::positionLabel, VelocityHud::togglePosition),
                                new Toggle("Warnings", VelocityHud::isWarningsOn, VelocityHud::toggleWarnings),
                                toggleKey(VelocityHud.TOGGLE_SPEED))),
                        new Module("Radar", hud ? "Shows nearby players around your crosshair" : "Needs Fabric API's HUD module",
                                VelocityHud::isRadarOn, VelocityHud::toggleRadar, List.of(
                                new Choice("Range", () -> VelocityHud.radarRange() + " m", VelocityHud::cycleRadarRange),
                                new Choice("Team", () -> VelocityHud.isIgnoreTeam() ? "Ignore" : "Show",
                                        VelocityHud::toggleIgnoreTeam),
                                new Action(() -> friendLabel(minecraft), () -> toggleFriend(minecraft)),
                                toggleKey(VelocityHud.TOGGLE_RADAR))),
                        new Module("Slam Meter", "Shows how hard your mace slam will hit, and KILL or POP",
                                SlamMeter::isEnabled, SlamMeter::toggleEnabled, List.of()),
                        new Module("Combat Alerts", "Shows when a player eats, pops a totem or loses their shield",
                                CombatAlerts::isEnabled, CombatAlerts::toggleEnabled, List.of(
                                new Toggle("Sound", CombatAlerts::isSound, CombatAlerts::toggleSound))),
                        new Module("Minimal HUD", hud ? "A quiet HUD that fades away outside of fights"
                                : "Needs Fabric API's HUD module",
                                MinimalHud::isEnabled, MinimalHud::toggleEnabled, List.of()),
                        new Module("Armor HUD", hud ? "Your armor by the hotbar, with red !!! over anything that needs you"
                                : "Needs Fabric API's HUD module",
                                ArmorHud::isEnabled, ArmorHud::toggleEnabled, List.of(
                                new Toggle("Alerts", ArmorHud::isAlerts, ArmorHud::toggleAlerts))),
                        new Module("Pop Chams", hud ? "A ghost where a player pops a totem, with their name and count"
                                : "Needs Fabric API's rendering module",
                                PopChams::isEnabled, PopChams::toggleEnabled, List.of()),
                        new Module("Damage Particles", hud ? "Old-school red -1s fly off players you hit"
                                : "Needs Fabric API's rendering module",
                                DamageParticles::isEnabled, DamageParticles::toggleEnabled, List.of()),
                        new Module("Hit Wheels", hud ? "A wheel over players' heads that turns each time they get hit"
                                : "Needs Fabric API's rendering module",
                                HitWheels::isEnabled, HitWheels::toggleEnabled, List.of(
                                new Choice("Spin", HitWheels::spinLabel, HitWheels::cycleSpin),
                                new Choice("Who", HitWheels::whoLabel, HitWheels::cycleWho),
                                new Choice("Helmet", HitWheels::helmetLabel, HitWheels::cycleHelmet))),
                        new Module("Vanilla HUD", "Shows only the game's own HUD: hides Uranium and every other mod",
                                HudFilter::isHideAll, HudFilter::toggleHideAll, List.of(
                                toggleKey(VelocityHud.TOGGLE_HIDE))),
                        new Module("Hide Other Clients", "Hides the module lists other clients draw",
                                HudFilter::isEnabled, HudFilter::toggleEnabled, hideRows()),
                        new Module("Fall Damage", hud ? "Shows the fall damage you'll take before you land" : "Needs Fabric API's HUD module",
                                VelocityHud::isLandingOn, VelocityHud::toggleLanding, List.of(
                                toggleKey(VelocityHud.TOGGLE_LANDING))),
                        new Module("Combat Stats", hud ? "Your ping, fall, shield timing and shield breaks"
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
