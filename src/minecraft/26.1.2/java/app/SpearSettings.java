package app;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Saves the SPEAR settings to .minecraft/config/spear-injector.properties after every
 * change and loads them when an injected version starts and whenever you change server, so
 * new versions and Unload keep your setup. The keys never change between versions.
 *
 * Per server: a server on the combat-off list keeps every module that acts for you (Spear
 * Dash, Shield Break, Mace Swap, Auto Totem, Auto Weapon, Reach Swap, Quick Throw, Web Escape, Web Trap,
 * MLG Water, W-Tap, Jump Reset, Auto Gapple)
 * from firing there; the Utility modules work everywhere. A server with its own settings keeps its module settings under
 * "server.<address>." keys, and anywhere else uses the plain keys (your defaults). The HUD
 * layout, toggle keys, menu position and open tab are shared by every server.
 */
final class SpearSettings {
    private static final String FILE_NAME = "spear-injector.properties";
    private static final String SERVER_PREFIX = "server.";
    // Server addresses; read and changed on the render thread only.
    private static final Set<String> COMBAT_OFF = new TreeSet<>();
    private static final Set<String> OWN_SETTINGS = new TreeSet<>();
    private static String loadedFor = "";

    private SpearSettings() {}

    static void load(Minecraft minecraft) {
        Properties file = read(minecraft);
        String server = currentServer(minecraft);
        loadedFor = server;
        COMBAT_OFF.clear();
        COMBAT_OFF.addAll(list(file, "servers.combatOff"));
        OWN_SETTINGS.clear();
        OWN_SETTINGS.addAll(list(file, "servers.ownSettings"));
        loadShared(file);
        loadModules(file, OWN_SETTINGS.contains(server) ? SERVER_PREFIX + server + "." : "");
    }

    /** Called from the input loop: switches to the right settings when you change server. */
    static void followServer(Minecraft minecraft) {
        if (!currentServer(minecraft).equals(loadedFor)) {
            load(minecraft);
        }
    }

    static void save(Minecraft minecraft) {
        // Start from the file so other servers' settings are kept.
        Properties file = read(minecraft);
        String server = currentServer(minecraft);
        file.setProperty("servers.combatOff", String.join(",", COMBAT_OFF));
        file.setProperty("servers.ownSettings", String.join(",", OWN_SETTINGS));
        saveShared(file);
        saveModules(file, OWN_SETTINGS.contains(server) ? SERVER_PREFIX + server + "." : "");
        Path path = file(minecraft);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                file.store(writer, "SPEAR settings, shared by every injected version");
            }
        } catch (IOException ignored) {
            // Settings just are not kept this time.
        }
    }

    private static void loadShared(Properties file) {
        // New keys for the Uranium window, so it starts centred rather than where the old panel was.
        ClickGuiScreen.setPosition(
                integer(file, "gui.window.x", ClickGuiScreen.panelX()),
                integer(file, "gui.window.y", ClickGuiScreen.panelY()));
        ClickGuiScreen.setTab(integer(file, "gui.tab", ClickGuiScreen.tab()));
        for (int element = 0; element < VelocityHud.ELEMENTS; element++) {
            String key = "hud.offset." + VelocityHud.elementKey(element);
            VelocityHud.setOffset(element,
                    integer(file, key + ".x", VelocityHud.offsetX(element)),
                    integer(file, key + ".y", VelocityHud.offsetY(element)));
        }
        for (int module = 0; module < VelocityHud.TOGGLES; module++) {
            String key = "hud.toggle." + VelocityHud.toggleKeyName(module);
            VelocityHud.setToggleBinding(module,
                    integer(file, key + ".key", VelocityHud.toggleKey(module)),
                    integer(file, key + ".mouse", VelocityHud.toggleMouse(module)));
        }
    }

    private static void saveShared(Properties file) {
        file.setProperty("gui.window.x", String.valueOf(ClickGuiScreen.panelX()));
        file.setProperty("gui.window.y", String.valueOf(ClickGuiScreen.panelY()));
        file.setProperty("gui.tab", String.valueOf(ClickGuiScreen.tab()));
        for (int element = 0; element < VelocityHud.ELEMENTS; element++) {
            String key = "hud.offset." + VelocityHud.elementKey(element);
            file.setProperty(key + ".x", String.valueOf(VelocityHud.offsetX(element)));
            file.setProperty(key + ".y", String.valueOf(VelocityHud.offsetY(element)));
        }
        for (int module = 0; module < VelocityHud.TOGGLES; module++) {
            String key = "hud.toggle." + VelocityHud.toggleKeyName(module);
            file.setProperty(key + ".key", String.valueOf(VelocityHud.toggleKey(module)));
            file.setProperty(key + ".mouse", String.valueOf(VelocityHud.toggleMouse(module)));
        }
    }

    /** Module settings: under the prefix if there, else the plain key, else unchanged. */
    private static void loadModules(Properties file, String prefix) {
        Profile p = new Profile(file, prefix);
        MinecraftSpearDash.setEnabled(p.bool("dash.enabled", false));
        MinecraftSpearDash.setBinding(p.integer("dash.key", MinecraftSpearDash.keyCode()),
                p.integer("dash.mouse", MinecraftSpearDash.mouseButton()));
        MinecraftSpearDash.setDashCount(p.integer("dash.count", MinecraftSpearDash.dashCount()));
        // Older saves had one fixed return delay.
        int oldReturn = p.integer("dash.returnDelayMs", -1);
        if (oldReturn >= 0) {
            p.num("dash.return", MinecraftSpearDash.RETURN, oldReturn, oldReturn);
        } else {
            p.num("dash.return", MinecraftSpearDash.RETURN);
        }
        p.num("dash.swapDelay", MinecraftSpearDash.SWAP_DELAY);
        p.num("dash.jabDelay", MinecraftSpearDash.JAB_DELAY);
        p.num("dash.chainGap", MinecraftSpearDash.CHAIN_GAP);
        MinecraftSpearDash.setFlatMode(p.integer("dash.flatMode", MinecraftSpearDash.flatMode()));
        MinecraftSpearDash.setReadySound(p.bool("dash.readySound", MinecraftSpearDash.isReadySound()));
        MinecraftSpearDash.setOnlyFromWindCharge(p.bool("dash.onlyFromWindCharge",
                MinecraftSpearDash.isOnlyFromWindCharge()));
        ShieldBreak.setEnabled(p.bool("shield.enabled", ShieldBreak.isEnabled()));
        ShieldBreak.setBinding(p.integer("shield.key", ShieldBreak.keyCode()),
                p.integer("shield.mouse", ShieldBreak.mouseButton()));
        ShieldBreak.setAxeHits(p.integer("shield.hits", ShieldBreak.axeHits()));
        ShieldBreak.setOnAttackClick(p.bool("shield.onClick", ShieldBreak.isOnAttackClick()));
        ShieldBreak.setAnyAttack(p.bool("shield.anyAttack", ShieldBreak.isAnyAttack()));
        ShieldBreak.setMaceFollowUp(p.bool("shield.mace", ShieldBreak.isMaceFollowUp()));
        p.num("shield.chance", ShieldBreak.CHANCE);
        p.num("shield.swapDelay", ShieldBreak.SWAP_DELAY);
        p.num("shield.hitDelay", ShieldBreak.HIT_DELAY);
        p.num("shield.betweenHits", ShieldBreak.BETWEEN_HITS);
        p.num("shield.axeToMace", ShieldBreak.AXE_TO_MACE);
        p.num("shield.swapBack", ShieldBreak.SWAP_BACK);
        MaceSwap.setEnabled(p.bool("maceSwap.enabled", MaceSwap.isEnabled()));
        ElytraMace.setEnabled(p.bool("elytraMace.enabled", ElytraMace.isEnabled()));
        ElytraMace.setGlideAgain(p.bool("elytraMace.glideAgain", ElytraMace.isGlideAgain()));
        WebEscape.setEnabled(p.bool("webEscape.enabled", WebEscape.isEnabled()));
        WebEscape.setPickUp(p.bool("webEscape.pickUp", WebEscape.isPickUp()));
        WebEscape.setOnFire(p.bool("webEscape.onFire", WebEscape.isOnFire()));
        WebTrap.setEnabled(p.bool("webTrap.enabled", WebTrap.isEnabled()));
        WebTrap.setBinding(p.integer("webTrap.key", WebTrap.keyCode()),
                p.integer("webTrap.mouse", WebTrap.mouseButton()));
        WebTrap.setDrain(p.bool("webTrap.drain", WebTrap.isDrain()));
        MlgWater.setEnabled(p.bool("mlg.enabled", MlgWater.isEnabled()));
        WindClutch.setEnabled(p.bool("windClutch.enabled", WindClutch.isEnabled()));
        AutoTotem.setEnabled(p.bool("autoTotem.enabled", AutoTotem.isEnabled()));
        AutoTotem.setMode(p.integer("autoTotem.mode", AutoTotem.mode()));
        p.num("autoTotem.reaction", AutoTotem.REACTION);
        p.num("autoTotem.stepDelay", AutoTotem.STEP_DELAY);
        AutoWeapon.setEnabled(p.bool("autoWeapon.enabled", AutoWeapon.isEnabled()));
        AutoWeapon.setAnyAttack(p.bool("autoWeapon.anyAttack", AutoWeapon.isAnyAttack()));
        p.num("autoWeapon.chance", AutoWeapon.CHANCE);
        ReachSwap.setEnabled(p.bool("reachSwap.enabled", ReachSwap.isEnabled()));
        ReachSwap.setMode(p.integer("reachSwap.mode", ReachSwap.mode()));
        p.num("reachSwap.switchBack", ReachSwap.SWITCH_BACK);
        p.num("autoWeapon.swapDelay", AutoWeapon.SWAP_DELAY);
        p.num("autoWeapon.hitDelay", AutoWeapon.HIT_DELAY);
        AutoGapple.setEnabled(p.bool("autoGapple.enabled", AutoGapple.isEnabled()));
        AutoGapple.setHealthIndex(p.integer("autoGapple.health", AutoGapple.healthIndex()));
        AutoTool.setEnabled(p.bool("autoTool.enabled", AutoTool.isEnabled()));
        AutoTool.setSwapBack(p.bool("autoTool.swapBack", AutoTool.isSwapBack()));
        Utilities.setAutoSprint(p.bool("util.autoSprint", Utilities.isAutoSprint()));
        Utilities.setFullbright(p.bool("util.fullbright", Utilities.isFullbright()));
        Utilities.setZoom(p.bool("util.zoom", Utilities.isZoom()));
        Utilities.setZoomFactor(p.integer("util.zoomFactor", Utilities.zoomFactor()));
        Utilities.setZoomBinding(p.integer("util.zoomKey", Utilities.zoomKey()),
                p.integer("util.zoomMouse", Utilities.zoomMouse()));
        Utilities.setAutoRespawn(p.bool("util.autoRespawn", Utilities.isAutoRespawn()));
        Utilities.setDeathCoords(p.bool("util.deathCoords", Utilities.isDeathCoords()));
        Utilities.setAutoWalk(p.bool("util.autoWalk", Utilities.isAutoWalk()));
        Utilities.setClearWeather(p.bool("util.clearWeather", Utilities.isClearWeather()));
        Utilities.setHitSound(p.bool("util.hitSound", Utilities.isHitSound()));
        HudFilter.setHiddenSetting(p.string("util.hideOtherClients.sources", HudFilter.hiddenSetting()));
        HudFilter.setEnabled(p.bool("util.hideOtherClients", HudFilter.isEnabled()));
        Utilities.setMiddleClickFriend(p.bool("util.middleClickFriend", Utilities.isMiddleClickFriend()));
        QuickThrow.setEnabled(p.bool("quickThrow.enabled", QuickThrow.isEnabled()));
        QuickThrow.setItem(p.integer("quickThrow.item", QuickThrow.item()));
        QuickThrow.setBinding(p.integer("quickThrow.key", QuickThrow.keyCode()),
                p.integer("quickThrow.mouse", QuickThrow.mouseButton()));
        p.num("quickThrow.swapDelay", QuickThrow.SWAP_DELAY);
        p.num("quickThrow.throwDelay", QuickThrow.THROW_DELAY);
        p.num("quickThrow.swapBack", QuickThrow.SWAP_BACK);
        PearlCatch.setEnabled(p.bool("pearlCatch.enabled", PearlCatch.isEnabled()));
        PearlCatch.setOnRightClick(p.bool("pearlCatch.onRightClick", PearlCatch.isOnRightClick()));
        Hit.setEnabled(p.bool("hit.enabled", Hit.isEnabled()));
        p.num("hit.chance", Hit.CHANCE);
        SlamSaver.setEnabled(p.bool("slamSaver.enabled", SlamSaver.isEnabled()));
        SlamFollowUp.setEnabled(p.bool("slamFollowUp.enabled", SlamFollowUp.isEnabled()));
        SlamSteer.setEnabled(p.bool("slamSteer.enabled", SlamSteer.isEnabled()));
        SlamMeter.setEnabled(p.bool("slamMeter.enabled", SlamMeter.isEnabled()));
        PunishAlerts.setEnabled(p.bool("punishAlerts.enabled", PunishAlerts.isEnabled()));
        PunishAlerts.setSound(p.bool("punishAlerts.sound", PunishAlerts.isSound()));
        WorldSlash.setEnabled(p.bool("worldSlash.enabled", WorldSlash.isEnabled()));
        p.num("worldSlash.range", WorldSlash.RANGE);
        p.num("worldSlash.speed", WorldSlash.SPEED);
        p.num("worldSlash.fov", WorldSlash.FOV);
        p.num("worldSlash.reaction", WorldSlash.REACTION);
        p.num("worldSlash.chance", WorldSlash.CHANCE);
        WorldSlash.setMobs(p.bool("worldSlash.mobs", WorldSlash.isMobs()));
        AimHelper.setEnabled(p.bool("aimHelper.enabled", AimHelper.isEnabled()));
        p.num("aimHelper.range", AimHelper.RANGE);
        p.num("aimHelper.speed", AimHelper.SPEED);
        p.num("aimHelper.fov", AimHelper.FOV);
        p.num("aimHelper.ignoreMouse", AimHelper.IGNORE_MOUSE);
        AimHelper.setAimAt(p.integer("aimHelper.aimAt", AimHelper.aimAt()));
        AimHelper.setLineOfSight(p.bool("aimHelper.lineOfSight", AimHelper.isLineOfSight()));
        AimHelper.setWhileBlocking(p.bool("aimHelper.whileBlocking", AimHelper.isWhileBlocking()));
        AimHelper.setIgnoreInvisible(p.bool("aimHelper.ignoreInvisible", AimHelper.isIgnoreInvisible()));
        AimHelper.setIgnoreCreative(p.bool("aimHelper.ignoreCreative", AimHelper.isIgnoreCreative()));
        AimHelper.setGround(p.bool("aimHelper.ground", AimHelper.isGround()));
        AimHelper.setKeyMode(p.integer("aimHelper.keyMode", AimHelper.keyMode()));
        AimHelper.setBinding(p.integer("aimHelper.key", AimHelper.keyCode()), p.integer("aimHelper.mouse", AimHelper.mouseButton()));
        Wheels.setEnabled(p.bool("wheels.enabled", Wheels.isEnabled()));
        Wheels.setSpin(p.integer("wheels.spin", Wheels.spin()));
        Wheels.setWho(p.integer("wheels.who", Wheels.who()));
        Wheels.setHelmet(p.integer("wheels.helmet", Wheels.helmet()));
        PearlCatch.setTiming(p.integer("pearlCatch.timing", PearlCatch.timing()));
        PearlCatch.setBinding(p.integer("pearlCatch.key", PearlCatch.keyCode()),
                p.integer("pearlCatch.mouse", PearlCatch.mouseButton()));
        ChestSwap.setEnabled(p.bool("chestSwap.enabled", ChestSwap.isEnabled()));
        ChestSwap.setBinding(p.integer("chestSwap.key", ChestSwap.keyCode()),
                p.integer("chestSwap.mouse", ChestSwap.mouseButton()));
        p.num("chestSwap.swapDelay", ChestSwap.SWAP_DELAY);
        p.num("chestSwap.useDelay", ChestSwap.USE_DELAY);
        p.num("chestSwap.swapBack", ChestSwap.SWAP_BACK);
        AutoEat.setEnabled(p.bool("autoEat.enabled", AutoEat.isEnabled()));
        AutoEat.setHungerIndex(p.integer("autoEat.hunger", AutoEat.hungerIndex()));
        AutoFish.setEnabled(p.bool("autoFish.enabled", AutoFish.isEnabled()));
        MlgWater.setPickUp(p.bool("mlg.pickUp", MlgWater.isPickUp()));
        MlgWater.setMinDamageIndex(p.integer("mlg.minDamage", MlgWater.minDamageIndex()));
        WTap.setEnabled(p.bool("wtap.enabled", WTap.isEnabled()));
        WTap.setMode(p.integer("wtap.mode", WTap.mode()));
        // Older saves had the length in game ticks.
        int oldTicks = p.integer("wtap.ticks", -1);
        if (oldTicks >= 0) {
            p.num("wtap.length", WTap.LENGTH, oldTicks * 50, oldTicks * 50);
        } else {
            p.num("wtap.length", WTap.LENGTH);
        }
        p.num("wtap.reaction", WTap.REACTION);
        p.num("wtap.chance", WTap.CHANCE);
        WTap.setOnlySprintHits(p.bool("wtap.onlySprintHits", WTap.isOnlySprintHits()));
        WTap.setAnyAttack(p.bool("wtap.anyAttack", WTap.isAnyAttack()));
        JumpReset.setEnabled(p.bool("jumpReset.enabled", JumpReset.isEnabled()));
        JumpReset.setOnlyWhileSprinting(p.bool("jumpReset.onlySprinting", JumpReset.isOnlyWhileSprinting()));
        VelocityHud.setSpeedHudOn(p.bool("hud.speed", VelocityHud.isSpeedHudOn()));
        VelocityHud.setSpeedHudHigh(p.bool("hud.high", VelocityHud.isSpeedHudHigh()));
        VelocityHud.setWarningsOn(p.bool("hud.warnings", VelocityHud.isWarningsOn()));
        VelocityHud.setRadarOn(p.bool("radar.on", VelocityHud.isRadarOn()));
        VelocityHud.setRadarRange(p.integer("radar.range", VelocityHud.radarRange()));
        VelocityHud.setIgnoreTeam(p.bool("radar.ignoreTeam", VelocityHud.isIgnoreTeam()));
        VelocityHud.setFriends(Arrays.asList(p.string("radar.friends", "").split(",")));
        VelocityHud.setLandingOn(p.bool("landing.on", VelocityHud.isLandingOn()));
        CombatStats.setOn(p.bool("combatStats.on", CombatStats.isOn()));
    }

    private static void saveModules(Properties file, String prefix) {
        put(file, prefix, "dash.enabled", MinecraftSpearDash.isEnabled());
        put(file, prefix, "dash.key", MinecraftSpearDash.keyCode());
        put(file, prefix, "dash.mouse", MinecraftSpearDash.mouseButton());
        put(file, prefix, "dash.count", MinecraftSpearDash.dashCount());
        putNum(file, prefix, "dash.return", MinecraftSpearDash.RETURN);
        putNum(file, prefix, "dash.swapDelay", MinecraftSpearDash.SWAP_DELAY);
        putNum(file, prefix, "dash.jabDelay", MinecraftSpearDash.JAB_DELAY);
        putNum(file, prefix, "dash.chainGap", MinecraftSpearDash.CHAIN_GAP);
        put(file, prefix, "dash.flatMode", MinecraftSpearDash.flatMode());
        put(file, prefix, "dash.readySound", MinecraftSpearDash.isReadySound());
        put(file, prefix, "dash.onlyFromWindCharge", MinecraftSpearDash.isOnlyFromWindCharge());
        put(file, prefix, "shield.enabled", ShieldBreak.isEnabled());
        put(file, prefix, "shield.key", ShieldBreak.keyCode());
        put(file, prefix, "shield.mouse", ShieldBreak.mouseButton());
        put(file, prefix, "shield.hits", ShieldBreak.axeHits());
        put(file, prefix, "shield.onClick", ShieldBreak.isOnAttackClick());
        put(file, prefix, "shield.anyAttack", ShieldBreak.isAnyAttack());
        put(file, prefix, "shield.mace", ShieldBreak.isMaceFollowUp());
        putNum(file, prefix, "shield.chance", ShieldBreak.CHANCE);
        putNum(file, prefix, "shield.swapDelay", ShieldBreak.SWAP_DELAY);
        putNum(file, prefix, "shield.hitDelay", ShieldBreak.HIT_DELAY);
        putNum(file, prefix, "shield.betweenHits", ShieldBreak.BETWEEN_HITS);
        putNum(file, prefix, "shield.axeToMace", ShieldBreak.AXE_TO_MACE);
        putNum(file, prefix, "shield.swapBack", ShieldBreak.SWAP_BACK);
        put(file, prefix, "maceSwap.enabled", MaceSwap.isEnabled());
        put(file, prefix, "elytraMace.enabled", ElytraMace.isEnabled());
        put(file, prefix, "elytraMace.glideAgain", ElytraMace.isGlideAgain());
        put(file, prefix, "webEscape.enabled", WebEscape.isEnabled());
        put(file, prefix, "webEscape.pickUp", WebEscape.isPickUp());
        put(file, prefix, "webEscape.onFire", WebEscape.isOnFire());
        put(file, prefix, "webTrap.enabled", WebTrap.isEnabled());
        put(file, prefix, "webTrap.key", WebTrap.keyCode());
        put(file, prefix, "webTrap.mouse", WebTrap.mouseButton());
        put(file, prefix, "webTrap.drain", WebTrap.isDrain());
        put(file, prefix, "mlg.enabled", MlgWater.isEnabled());
        put(file, prefix, "windClutch.enabled", WindClutch.isEnabled());
        put(file, prefix, "autoTotem.enabled", AutoTotem.isEnabled());
        put(file, prefix, "autoTotem.mode", AutoTotem.mode());
        putNum(file, prefix, "autoTotem.reaction", AutoTotem.REACTION);
        putNum(file, prefix, "autoTotem.stepDelay", AutoTotem.STEP_DELAY);
        put(file, prefix, "autoWeapon.enabled", AutoWeapon.isEnabled());
        put(file, prefix, "autoWeapon.anyAttack", AutoWeapon.isAnyAttack());
        putNum(file, prefix, "autoWeapon.chance", AutoWeapon.CHANCE);
        put(file, prefix, "reachSwap.enabled", ReachSwap.isEnabled());
        put(file, prefix, "reachSwap.mode", ReachSwap.mode());
        putNum(file, prefix, "reachSwap.switchBack", ReachSwap.SWITCH_BACK);
        putNum(file, prefix, "autoWeapon.swapDelay", AutoWeapon.SWAP_DELAY);
        putNum(file, prefix, "autoWeapon.hitDelay", AutoWeapon.HIT_DELAY);
        put(file, prefix, "autoGapple.enabled", AutoGapple.isEnabled());
        put(file, prefix, "autoGapple.health", AutoGapple.healthIndex());
        put(file, prefix, "autoTool.enabled", AutoTool.isEnabled());
        put(file, prefix, "autoTool.swapBack", AutoTool.isSwapBack());
        put(file, prefix, "util.autoSprint", Utilities.isAutoSprint());
        put(file, prefix, "util.fullbright", Utilities.isFullbright());
        put(file, prefix, "util.zoom", Utilities.isZoom());
        put(file, prefix, "util.zoomFactor", Utilities.zoomFactor());
        put(file, prefix, "util.zoomKey", Utilities.zoomKey());
        put(file, prefix, "util.zoomMouse", Utilities.zoomMouse());
        put(file, prefix, "util.autoRespawn", Utilities.isAutoRespawn());
        put(file, prefix, "util.deathCoords", Utilities.isDeathCoords());
        put(file, prefix, "util.autoWalk", Utilities.isAutoWalk());
        put(file, prefix, "util.clearWeather", Utilities.isClearWeather());
        put(file, prefix, "util.hitSound", Utilities.isHitSound());
        put(file, prefix, "util.hideOtherClients", HudFilter.isEnabled());
        put(file, prefix, "util.hideOtherClients.sources", HudFilter.hiddenSetting());
        put(file, prefix, "util.middleClickFriend", Utilities.isMiddleClickFriend());
        put(file, prefix, "quickThrow.enabled", QuickThrow.isEnabled());
        put(file, prefix, "quickThrow.item", QuickThrow.item());
        put(file, prefix, "quickThrow.key", QuickThrow.keyCode());
        put(file, prefix, "quickThrow.mouse", QuickThrow.mouseButton());
        putNum(file, prefix, "quickThrow.swapDelay", QuickThrow.SWAP_DELAY);
        putNum(file, prefix, "quickThrow.throwDelay", QuickThrow.THROW_DELAY);
        putNum(file, prefix, "quickThrow.swapBack", QuickThrow.SWAP_BACK);
        put(file, prefix, "pearlCatch.enabled", PearlCatch.isEnabled());
        put(file, prefix, "pearlCatch.onRightClick", PearlCatch.isOnRightClick());
        put(file, prefix, "hit.enabled", Hit.isEnabled());
        putNum(file, prefix, "hit.chance", Hit.CHANCE);
        put(file, prefix, "slamSaver.enabled", SlamSaver.isEnabled());
        put(file, prefix, "slamFollowUp.enabled", SlamFollowUp.isEnabled());
        put(file, prefix, "slamSteer.enabled", SlamSteer.isEnabled());
        put(file, prefix, "slamMeter.enabled", SlamMeter.isEnabled());
        put(file, prefix, "punishAlerts.enabled", PunishAlerts.isEnabled());
        put(file, prefix, "punishAlerts.sound", PunishAlerts.isSound());
        put(file, prefix, "worldSlash.enabled", WorldSlash.isEnabled());
        putNum(file, prefix, "worldSlash.range", WorldSlash.RANGE);
        putNum(file, prefix, "worldSlash.speed", WorldSlash.SPEED);
        putNum(file, prefix, "worldSlash.fov", WorldSlash.FOV);
        putNum(file, prefix, "worldSlash.reaction", WorldSlash.REACTION);
        putNum(file, prefix, "worldSlash.chance", WorldSlash.CHANCE);
        put(file, prefix, "worldSlash.mobs", WorldSlash.isMobs());
        put(file, prefix, "aimHelper.enabled", AimHelper.isEnabled());
        putNum(file, prefix, "aimHelper.range", AimHelper.RANGE);
        putNum(file, prefix, "aimHelper.speed", AimHelper.SPEED);
        putNum(file, prefix, "aimHelper.fov", AimHelper.FOV);
        putNum(file, prefix, "aimHelper.ignoreMouse", AimHelper.IGNORE_MOUSE);
        put(file, prefix, "aimHelper.aimAt", AimHelper.aimAt());
        put(file, prefix, "aimHelper.lineOfSight", AimHelper.isLineOfSight());
        put(file, prefix, "aimHelper.whileBlocking", AimHelper.isWhileBlocking());
        put(file, prefix, "aimHelper.ignoreInvisible", AimHelper.isIgnoreInvisible());
        put(file, prefix, "aimHelper.ignoreCreative", AimHelper.isIgnoreCreative());
        put(file, prefix, "aimHelper.ground", AimHelper.isGround());
        put(file, prefix, "aimHelper.keyMode", AimHelper.keyMode());
        put(file, prefix, "aimHelper.key", AimHelper.keyCode());
        put(file, prefix, "aimHelper.mouse", AimHelper.mouseButton());
        put(file, prefix, "wheels.enabled", Wheels.isEnabled());
        put(file, prefix, "wheels.spin", Wheels.spin());
        put(file, prefix, "wheels.who", Wheels.who());
        put(file, prefix, "wheels.helmet", Wheels.helmet());
        put(file, prefix, "pearlCatch.timing", PearlCatch.timing());
        put(file, prefix, "pearlCatch.key", PearlCatch.keyCode());
        put(file, prefix, "pearlCatch.mouse", PearlCatch.mouseButton());
        put(file, prefix, "chestSwap.enabled", ChestSwap.isEnabled());
        put(file, prefix, "chestSwap.key", ChestSwap.keyCode());
        put(file, prefix, "chestSwap.mouse", ChestSwap.mouseButton());
        putNum(file, prefix, "chestSwap.swapDelay", ChestSwap.SWAP_DELAY);
        putNum(file, prefix, "chestSwap.useDelay", ChestSwap.USE_DELAY);
        putNum(file, prefix, "chestSwap.swapBack", ChestSwap.SWAP_BACK);
        put(file, prefix, "autoEat.enabled", AutoEat.isEnabled());
        put(file, prefix, "autoEat.hunger", AutoEat.hungerIndex());
        put(file, prefix, "autoFish.enabled", AutoFish.isEnabled());
        put(file, prefix, "mlg.pickUp", MlgWater.isPickUp());
        put(file, prefix, "mlg.minDamage", MlgWater.minDamageIndex());
        put(file, prefix, "wtap.enabled", WTap.isEnabled());
        put(file, prefix, "wtap.mode", WTap.mode());
        putNum(file, prefix, "wtap.length", WTap.LENGTH);
        putNum(file, prefix, "wtap.reaction", WTap.REACTION);
        putNum(file, prefix, "wtap.chance", WTap.CHANCE);
        put(file, prefix, "wtap.onlySprintHits", WTap.isOnlySprintHits());
        put(file, prefix, "wtap.anyAttack", WTap.isAnyAttack());
        put(file, prefix, "jumpReset.enabled", JumpReset.isEnabled());
        put(file, prefix, "jumpReset.onlySprinting", JumpReset.isOnlyWhileSprinting());
        put(file, prefix, "hud.speed", VelocityHud.isSpeedHudOn());
        put(file, prefix, "hud.high", VelocityHud.isSpeedHudHigh());
        put(file, prefix, "hud.warnings", VelocityHud.isWarningsOn());
        put(file, prefix, "radar.on", VelocityHud.isRadarOn());
        put(file, prefix, "radar.range", VelocityHud.radarRange());
        put(file, prefix, "radar.ignoreTeam", VelocityHud.isIgnoreTeam());
        put(file, prefix, "radar.friends", String.join(",", VelocityHud.friends()));
        put(file, prefix, "landing.on", VelocityHud.isLandingOn());
        put(file, prefix, "combatStats.on", CombatStats.isOn());
    }

    /**
     * The server you are on, as its address without the default port; "singleplayer" in
     * your own world and "none" in the menus.
     */
    static String currentServer(Minecraft minecraft) {
        if (minecraft.hasSingleplayerServer()) {
            return "singleplayer";
        }
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return "none";
        }
        String address = server.ip.trim().toLowerCase(Locale.ROOT);
        return address.endsWith(":25565") ? address.substring(0, address.length() - 6) : address;
    }

    /** False on servers where you turned combat modules off. */
    static boolean combatAllowed(Minecraft minecraft) {
        return !COMBAT_OFF.contains(currentServer(minecraft));
    }

    static void toggleCombatHere(Minecraft minecraft) {
        String server = currentServer(minecraft);
        if (!COMBAT_OFF.remove(server)) {
            COMBAT_OFF.add(server);
        }
    }

    static boolean hasOwnSettings(Minecraft minecraft) {
        return OWN_SETTINGS.contains(currentServer(minecraft));
    }

    /**
     * Turning own settings on copies what you have now into this server's section; turning
     * it off goes back to your defaults (the server's section is kept for next time).
     */
    static void toggleOwnSettingsHere(Minecraft minecraft) {
        String server = currentServer(minecraft);
        if (OWN_SETTINGS.remove(server)) {
            save(minecraft);
            load(minecraft);
        } else {
            OWN_SETTINGS.add(server);
            save(minecraft);
        }
    }

    private static Properties read(Minecraft minecraft) {
        Properties file = new Properties();
        Path path = file(minecraft);
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                file.load(reader);
            } catch (IOException | IllegalArgumentException exception) {
                return new Properties(); // unreadable: keep the defaults
            }
        }
        return file;
    }

    private static Path file(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    private static List<String> list(Properties file, String key) {
        return Arrays.stream(file.getProperty(key, "").split(","))
                .map(String::trim).filter(entry -> !entry.isEmpty()).toList();
    }

    private static void put(Properties file, String prefix, String key, Object value) {
        file.setProperty(prefix + key, String.valueOf(value));
    }

    /** A slider: a range as key.min and key.max, a single value under the key itself. */
    private static void putNum(Properties file, String prefix, String key, Num num) {
        if (num.range) {
            put(file, prefix, key + ".min", num.low());
            put(file, prefix, key + ".max", num.high());
        } else {
            put(file, prefix, key, num.low());
        }
    }

    private static int integer(Properties file, String key, int fallback) {
        try {
            return Integer.parseInt(file.getProperty(key, "").trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** Reads a module setting from a server's section, falling back to your defaults. */
    private record Profile(Properties file, String prefix) {
        String string(String key, String fallback) {
            String value = file.getProperty(prefix + key);
            if (value == null) {
                value = file.getProperty(key);
            }
            return value == null ? fallback : value.trim();
        }

        boolean bool(String key, boolean fallback) {
            String value = string(key, null);
            return value == null ? fallback : Boolean.parseBoolean(value);
        }

        int integer(String key, int fallback) {
            try {
                return Integer.parseInt(string(key, ""));
            } catch (NumberFormatException exception) {
                return fallback;
            }
        }

        /** A slider saved by putNum; what it has now if the file has nothing. */
        void num(String key, Num num) {
            num(key, num, num.low(), num.high());
        }

        void num(String key, Num num, int lowFallback, int highFallback) {
            if (num.range) {
                num.set(integer(key + ".min", lowFallback), integer(key + ".max", highFallback));
            } else {
                num.set(integer(key, lowFallback), 0);
            }
        }
    }
}
