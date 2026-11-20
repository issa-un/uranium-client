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
 * Dash, Auto Stun, Mace Swap, Auto Totem, Auto Weapon, Reach Swap, Quick Throw, Web Escape, Auto Web,
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
        HandProfile.load(file);
        Debug.setEnabled(Boolean.parseBoolean(file.getProperty("debug.enabled", String.valueOf(Debug.isEnabled()))));
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
        HandProfile.save(file);
        file.setProperty("debug.enabled", String.valueOf(Debug.isEnabled()));
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
        SpearDash.setEnabled(p.bool("dash.enabled", false));
        SpearDash.setBinding(p.integer("dash.key", SpearDash.keyCode()),
                p.integer("dash.mouse", SpearDash.mouseButton()));
        SpearDash.setDashCount(p.integer("dash.count", SpearDash.dashCount()));
        // Older saves had one fixed return delay.
        int oldReturn = p.integer("dash.returnDelayMs", -1);
        if (oldReturn >= 0) {
            p.num("dash.return", SpearDash.RETURN, oldReturn, oldReturn);
        } else {
            p.num("dash.return", SpearDash.RETURN);
        }
        p.num("dash.swapDelay", SpearDash.SWAP_DELAY);
        p.num("dash.jabDelay", SpearDash.JAB_DELAY);
        p.num("dash.chainGap", SpearDash.CHAIN_GAP);
        SpearDash.setFlatMode(p.integer("dash.flatMode", SpearDash.flatMode()));
        SpearDash.setReadySound(p.bool("dash.readySound", SpearDash.isReadySound()));
        SpearDash.setFromSlot(p.integer("dash.fromSlot", SpearDash.fromSlot()));
        AutoStun.setEnabled(p.bool("shield.enabled", AutoStun.isEnabled()));
        AutoStun.setBinding(p.integer("shield.key", AutoStun.keyCode()),
                p.integer("shield.mouse", AutoStun.mouseButton()));
        AutoStun.setAxeHits(p.integer("shield.hits", AutoStun.axeHits()));
        AutoStun.setOnAttackClick(p.bool("shield.onClick", AutoStun.isOnAttackClick()));
        AutoStun.setAnyAttack(p.bool("shield.anyAttack", AutoStun.isAnyAttack()));
        AutoStun.setMaceFollowUp(p.bool("shield.mace", AutoStun.isMaceFollowUp()));
        p.num("shield.chance", AutoStun.CHANCE);
        p.num("shield.swapDelay", AutoStun.SWAP_DELAY);
        p.num("shield.hitDelay", AutoStun.HIT_DELAY);
        p.num("shield.betweenHits", AutoStun.BETWEEN_HITS);
        p.num("shield.axeToMace", AutoStun.AXE_TO_MACE);
        p.num("shield.swapBack", AutoStun.SWAP_BACK);
        MaceSwap.setEnabled(p.bool("maceSwap.enabled", MaceSwap.isEnabled()));
        ElytraMace.setEnabled(p.bool("elytraMace.enabled", ElytraMace.isEnabled()));
        ElytraMace.setGlideAgain(p.bool("elytraMace.glideAgain", ElytraMace.isGlideAgain()));
        WebEscape.setEnabled(p.bool("webEscape.enabled", WebEscape.isEnabled()));
        WebEscape.setPickUp(p.bool("webEscape.pickUp", WebEscape.isPickUp()));
        WebEscape.setOnFire(p.bool("webEscape.onFire", WebEscape.isOnFire()));
        AutoWeb.setEnabled(p.bool("webTrap.enabled", AutoWeb.isEnabled()));
        AutoWeb.setBinding(p.integer("webTrap.key", AutoWeb.keyCode()),
                p.integer("webTrap.mouse", AutoWeb.mouseButton()));
        AutoWeb.setDrain(p.bool("webTrap.drain", AutoWeb.isDrain()));
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
        Utilities.setZoomSmooth(p.bool("util.zoomSmooth", Utilities.isZoomSmooth()));
        Utilities.setZoomFactor(p.integer("util.zoomFactor", Utilities.zoomFactor()));
        Utilities.setZoomBinding(p.integer("util.zoomKey", Utilities.zoomKey()),
                p.integer("util.zoomMouse", Utilities.zoomMouse()));
        Utilities.setAutoRespawn(p.bool("util.autoRespawn", Utilities.isAutoRespawn()));
        Utilities.setDeathCoords(p.bool("util.deathCoords", Utilities.isDeathCoords()));
        Utilities.setAutoWalk(p.bool("util.autoWalk", Utilities.isAutoWalk()));
        // On/off keeps its old Clear Weather key, so it carries over.
        Weather.setEnabled(p.bool("util.clearWeather", Weather.isEnabled()));
        Weather.setMode(p.string("util.weatherMode", Weather.modeLabel()));
        Weather.setBars(p.bool("util.weatherBars", Weather.isBars()));
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
        TriggerBot.setEnabled(p.bool("hit.enabled", TriggerBot.isEnabled()));
        p.num("hit.chance", TriggerBot.CHANCE);
        TriggerBot.setAuto(p.bool("hit.auto", TriggerBot.isAuto()));
        TriggerBot.setOffHoldingMask(p.integer("hit.offHolding", TriggerBot.offHoldingMask()));
        TriggerBot.setSignsMask(p.integer("hit.signs", TriggerBot.signsMask()));
        TriggerBot.setRandomDelay(p.bool("hit.randomDelay", TriggerBot.isRandomDelay()));
        p.num("hit.delay", TriggerBot.DELAY);
        HitSelect.setEnabled(p.bool("slamSaver.enabled", HitSelect.isEnabled()));
        KnockbackDisplacement.setEnabled(p.bool("knockbackSteer.enabled", KnockbackDisplacement.isEnabled()));
        p.num("knockbackSteer.maxTurn", KnockbackDisplacement.MAX_TURN);
        KnockbackDisplacement.setMode(p.integer("knockbackSteer.mode", KnockbackDisplacement.mode()));
        SlamCombo.setEnabled(p.bool("slamFollowUp.enabled", SlamCombo.isEnabled()));
        TargetStrafe.setEnabled(p.bool("slamSteer.enabled", TargetStrafe.isEnabled()));
        SlamMeter.setEnabled(p.bool("slamMeter.enabled", SlamMeter.isEnabled()));
        CombatAlerts.setEnabled(p.bool("punishAlerts.enabled", CombatAlerts.isEnabled()));
        CombatAlerts.setSound(p.bool("punishAlerts.sound", CombatAlerts.isSound()));
        CrossbowCart.setEnabled(p.bool("cartHighTier.enabled", CrossbowCart.isEnabled()));
        BowCart.setEnabled(p.bool("cartLowTier.enabled", BowCart.isEnabled()));
        CrossbowCart.setShoot(p.bool("cartHighTier.shoot", CrossbowCart.isShoot()));
        p.num("cartHighTier.delay", CrossbowCart.DELAY);
        p.num("cartHighTier.chance", CrossbowCart.CHANCE);
        p.num("cartHighTier.guardUnder", CrossbowCart.GUARD_UNDER);
        p.num("cartLowTier.chance", BowCart.CHANCE);
        BowCart.setLowestDraw(p.bool("cartLowTier.lowestDraw", BowCart.isLowestDraw()));
        BowCart.setLook(p.integer("cartLowTier.look", BowCart.look()));
        ArmorHud.setEnabled(p.bool("armorSlots.enabled", ArmorHud.isEnabled()));
        AntiCart.setEnabled(p.bool("cartBlock.enabled", AntiCart.isEnabled()));
        p.num("cartBlock.range", AntiCart.RANGE);
        p.num("cartBlock.blocks", AntiCart.BLOCKS);
        AntiCart.setJumpBlock(p.bool("cartBlock.jumpBlock", AntiCart.isJumpBlock()));
        AntiCart.setAlert(p.bool("cartBlock.alert", AntiCart.isAlert()));
        p.num("cartBlock.alertRange", AntiCart.ALERT_RANGE);
        ArmorHud.setAlerts(p.bool("armorSlots.alerts", ArmorHud.isAlerts()));
        BowCart.setCrossbowToo(p.bool("cartLowTier.crossbowToo", BowCart.isCrossbowToo()));
        p.num("cartLowTier.delay", BowCart.DELAY);
        CrossbowCart.setReplenish(p.bool("cartHighTier.replenish", CrossbowCart.isReplenish()));
        BowCart.setReplenish(p.bool("cartLowTier.replenish", BowCart.isReplenish()));
        PopChams.setEnabled(p.bool("popChams.enabled", PopChams.isEnabled()));
        MinimalHud.setEnabled(p.bool("twoAm.enabled", MinimalHud.isEnabled()));
        PlayStyle.setCracked(p.bool("playStyle.cracked", PlayStyle.isCracked()));
        CrossbowCart.setAutoReload(p.bool("cartHighTier.autoReload", CrossbowCart.isAutoReload()));
        CrossbowCart.setHardSwitch(p.bool("cartHighTier.hardSwitch", CrossbowCart.isHardSwitch()));
        CrossbowCart.setOverload(p.bool("cartHighTier.overload", CrossbowCart.isOverload()));
        CrossbowCart.setSmoothLook(p.bool("cartHighTier.smoothLook", CrossbowCart.isSmoothLook()));
        Cart.setMacro(p.bool("cart.macro", Cart.isMacro()));
        SelfBlast.setMode(p.integer("cart.selfGuard", SelfBlast.mode()));
        BowCart.setAutoReload(p.bool("cartLowTier.autoReload", BowCart.isAutoReload()));
        DamageParticles.setEnabled(p.bool("damageParticles.enabled", DamageParticles.isEnabled()));
        KillAura.setEnabled(p.bool("worldSlash.enabled", KillAura.isEnabled()));
        p.num("worldSlash.range", KillAura.RANGE);
        p.num("worldSlash.speed", KillAura.SPEED);
        p.num("worldSlash.fov", KillAura.FOV);
        p.num("worldSlash.reaction", KillAura.REACTION);
        p.num("worldSlash.chance", KillAura.CHANCE);
        KillAura.setMobs(p.bool("worldSlash.mobs", KillAura.isMobs()));
        AimAssist.setEnabled(p.bool("aimHelper.enabled", AimAssist.isEnabled()));
        AimAssist.setDriftLock(p.bool("aimHelper.driftLock", AimAssist.isDriftLock()));
        p.num("aimHelper.range", AimAssist.RANGE);
        p.num("aimHelper.speed2", AimAssist.SPEED);
        AimAssist.setMode(p.integer("aimHelper.mode", AimAssist.mode()));
        AimAssist.setVertical(p.bool("aimHelper.vertical", AimAssist.isVertical()));
        p.num("aimHelper.prediction", AimAssist.PREDICTION);
        p.num("aimHelper.humanSpeed2", AimAssist.HUMAN_SPEED);
        p.num("aimHelper.reaction", AimAssist.REACTION);
        p.num("aimHelper.overshoot2", AimAssist.OVERSHOOT);
        p.num("aimHelper.fov", AimAssist.FOV);
        p.num("aimHelper.ignoreMouse", AimAssist.IGNORE_MOUSE);
        AimAssist.setAimAt(p.integer("aimHelper.aimAt", AimAssist.aimAt()));
        AimAssist.setLineOfSight(p.bool("aimHelper.lineOfSight", AimAssist.isLineOfSight()));
        AimAssist.setWhileBlocking(p.bool("aimHelper.whileBlocking", AimAssist.isWhileBlocking()));
        AimAssist.setIgnoreInvisible(p.bool("aimHelper.ignoreInvisible", AimAssist.isIgnoreInvisible()));
        AimAssist.setIgnoreCreative(p.bool("aimHelper.ignoreCreative", AimAssist.isIgnoreCreative()));
        AimAssist.setGround(p.bool("aimHelper.ground", AimAssist.isGround()));
        AimAssist.setKeyMode(p.integer("aimHelper.keyMode", AimAssist.keyMode()));
        AimAssist.setBinding(p.integer("aimHelper.key", AimAssist.keyCode()), p.integer("aimHelper.mouse", AimAssist.mouseButton()));
        HitWheels.setEnabled(p.bool("wheels.enabled", HitWheels.isEnabled()));
        HitWheels.setSpin(p.integer("wheels.spin", HitWheels.spin()));
        HitWheels.setWho(p.integer("wheels.who", HitWheels.who()));
        HitWheels.setHelmet(p.integer("wheels.helmet", HitWheels.helmet()));
        PearlCatch.setTiming(p.integer("pearlCatch.timing", PearlCatch.timing()));
        PearlCatch.setBinding(p.integer("pearlCatch.key", PearlCatch.keyCode()),
                p.integer("pearlCatch.mouse", PearlCatch.mouseButton()));
        ElytraSwap.setEnabled(p.bool("chestSwap.enabled", ElytraSwap.isEnabled()));
        ElytraSwap.setBinding(p.integer("chestSwap.key", ElytraSwap.keyCode()),
                p.integer("chestSwap.mouse", ElytraSwap.mouseButton()));
        p.num("chestSwap.swapDelay", ElytraSwap.SWAP_DELAY);
        p.num("chestSwap.useDelay", ElytraSwap.USE_DELAY);
        p.num("chestSwap.swapBack", ElytraSwap.SWAP_BACK);
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
        put(file, prefix, "dash.enabled", SpearDash.isEnabled());
        put(file, prefix, "dash.key", SpearDash.keyCode());
        put(file, prefix, "dash.mouse", SpearDash.mouseButton());
        put(file, prefix, "dash.count", SpearDash.dashCount());
        putNum(file, prefix, "dash.return", SpearDash.RETURN);
        putNum(file, prefix, "dash.swapDelay", SpearDash.SWAP_DELAY);
        putNum(file, prefix, "dash.jabDelay", SpearDash.JAB_DELAY);
        putNum(file, prefix, "dash.chainGap", SpearDash.CHAIN_GAP);
        put(file, prefix, "dash.flatMode", SpearDash.flatMode());
        put(file, prefix, "dash.readySound", SpearDash.isReadySound());
        put(file, prefix, "dash.fromSlot", SpearDash.fromSlot());
        put(file, prefix, "shield.enabled", AutoStun.isEnabled());
        put(file, prefix, "shield.key", AutoStun.keyCode());
        put(file, prefix, "shield.mouse", AutoStun.mouseButton());
        put(file, prefix, "shield.hits", AutoStun.axeHits());
        put(file, prefix, "shield.onClick", AutoStun.isOnAttackClick());
        put(file, prefix, "shield.anyAttack", AutoStun.isAnyAttack());
        put(file, prefix, "shield.mace", AutoStun.isMaceFollowUp());
        putNum(file, prefix, "shield.chance", AutoStun.CHANCE);
        putNum(file, prefix, "shield.swapDelay", AutoStun.SWAP_DELAY);
        putNum(file, prefix, "shield.hitDelay", AutoStun.HIT_DELAY);
        putNum(file, prefix, "shield.betweenHits", AutoStun.BETWEEN_HITS);
        putNum(file, prefix, "shield.axeToMace", AutoStun.AXE_TO_MACE);
        putNum(file, prefix, "shield.swapBack", AutoStun.SWAP_BACK);
        put(file, prefix, "maceSwap.enabled", MaceSwap.isEnabled());
        put(file, prefix, "elytraMace.enabled", ElytraMace.isEnabled());
        put(file, prefix, "elytraMace.glideAgain", ElytraMace.isGlideAgain());
        put(file, prefix, "webEscape.enabled", WebEscape.isEnabled());
        put(file, prefix, "webEscape.pickUp", WebEscape.isPickUp());
        put(file, prefix, "webEscape.onFire", WebEscape.isOnFire());
        put(file, prefix, "webTrap.enabled", AutoWeb.isEnabled());
        put(file, prefix, "webTrap.key", AutoWeb.keyCode());
        put(file, prefix, "webTrap.mouse", AutoWeb.mouseButton());
        put(file, prefix, "webTrap.drain", AutoWeb.isDrain());
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
        put(file, prefix, "util.zoomSmooth", Utilities.isZoomSmooth());
        put(file, prefix, "util.zoomFactor", Utilities.zoomFactor());
        put(file, prefix, "util.zoomKey", Utilities.zoomKey());
        put(file, prefix, "util.zoomMouse", Utilities.zoomMouse());
        put(file, prefix, "util.autoRespawn", Utilities.isAutoRespawn());
        put(file, prefix, "util.deathCoords", Utilities.isDeathCoords());
        put(file, prefix, "util.autoWalk", Utilities.isAutoWalk());
        put(file, prefix, "util.clearWeather", Weather.isEnabled());
        put(file, prefix, "util.weatherMode", Weather.modeLabel());
        put(file, prefix, "util.weatherBars", Weather.isBars());
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
        put(file, prefix, "hit.enabled", TriggerBot.isEnabled());
        putNum(file, prefix, "hit.chance", TriggerBot.CHANCE);
        put(file, prefix, "hit.auto", TriggerBot.isAuto());
        put(file, prefix, "hit.offHolding", TriggerBot.offHoldingMask());
        put(file, prefix, "hit.signs", TriggerBot.signsMask());
        put(file, prefix, "hit.randomDelay", TriggerBot.isRandomDelay());
        putNum(file, prefix, "hit.delay", TriggerBot.DELAY);
        put(file, prefix, "slamSaver.enabled", HitSelect.isEnabled());
        put(file, prefix, "knockbackSteer.enabled", KnockbackDisplacement.isEnabled());
        putNum(file, prefix, "knockbackSteer.maxTurn", KnockbackDisplacement.MAX_TURN);
        put(file, prefix, "knockbackSteer.mode", KnockbackDisplacement.mode());
        put(file, prefix, "slamFollowUp.enabled", SlamCombo.isEnabled());
        put(file, prefix, "slamSteer.enabled", TargetStrafe.isEnabled());
        put(file, prefix, "slamMeter.enabled", SlamMeter.isEnabled());
        put(file, prefix, "punishAlerts.enabled", CombatAlerts.isEnabled());
        put(file, prefix, "punishAlerts.sound", CombatAlerts.isSound());
        put(file, prefix, "cartHighTier.enabled", CrossbowCart.isEnabled());
        put(file, prefix, "cartLowTier.enabled", BowCart.isEnabled());
        put(file, prefix, "cartHighTier.shoot", CrossbowCart.isShoot());
        putNum(file, prefix, "cartHighTier.delay", CrossbowCart.DELAY);
        putNum(file, prefix, "cartHighTier.chance", CrossbowCart.CHANCE);
        putNum(file, prefix, "cartHighTier.guardUnder", CrossbowCart.GUARD_UNDER);
        putNum(file, prefix, "cartLowTier.chance", BowCart.CHANCE);
        put(file, prefix, "cartLowTier.lowestDraw", BowCart.isLowestDraw());
        put(file, prefix, "cartLowTier.look", BowCart.look());
        put(file, prefix, "armorSlots.enabled", ArmorHud.isEnabled());
        put(file, prefix, "cartBlock.enabled", AntiCart.isEnabled());
        putNum(file, prefix, "cartBlock.range", AntiCart.RANGE);
        putNum(file, prefix, "cartBlock.blocks", AntiCart.BLOCKS);
        put(file, prefix, "cartBlock.jumpBlock", AntiCart.isJumpBlock());
        put(file, prefix, "cartBlock.alert", AntiCart.isAlert());
        putNum(file, prefix, "cartBlock.alertRange", AntiCart.ALERT_RANGE);
        put(file, prefix, "armorSlots.alerts", ArmorHud.isAlerts());
        put(file, prefix, "cartLowTier.crossbowToo", BowCart.isCrossbowToo());
        putNum(file, prefix, "cartLowTier.delay", BowCart.DELAY);
        put(file, prefix, "cartHighTier.replenish", CrossbowCart.isReplenish());
        put(file, prefix, "cartLowTier.replenish", BowCart.isReplenish());
        put(file, prefix, "popChams.enabled", PopChams.isEnabled());
        put(file, prefix, "twoAm.enabled", MinimalHud.isEnabled());
        put(file, prefix, "playStyle.cracked", PlayStyle.isCracked());
        put(file, prefix, "cartHighTier.autoReload", CrossbowCart.isAutoReload());
        put(file, prefix, "cartHighTier.hardSwitch", CrossbowCart.isHardSwitch());
        put(file, prefix, "cartHighTier.overload", CrossbowCart.isOverload());
        put(file, prefix, "cartHighTier.smoothLook", CrossbowCart.isSmoothLook());
        put(file, prefix, "cart.macro", Cart.isMacro());
        put(file, prefix, "cart.selfGuard", SelfBlast.mode());
        put(file, prefix, "cartLowTier.autoReload", BowCart.isAutoReload());
        put(file, prefix, "damageParticles.enabled", DamageParticles.isEnabled());
        put(file, prefix, "worldSlash.enabled", KillAura.isEnabled());
        putNum(file, prefix, "worldSlash.range", KillAura.RANGE);
        putNum(file, prefix, "worldSlash.speed", KillAura.SPEED);
        putNum(file, prefix, "worldSlash.fov", KillAura.FOV);
        putNum(file, prefix, "worldSlash.reaction", KillAura.REACTION);
        putNum(file, prefix, "worldSlash.chance", KillAura.CHANCE);
        put(file, prefix, "worldSlash.mobs", KillAura.isMobs());
        put(file, prefix, "aimHelper.enabled", AimAssist.isEnabled());
        put(file, prefix, "aimHelper.driftLock", AimAssist.isDriftLock());
        putNum(file, prefix, "aimHelper.range", AimAssist.RANGE);
        putNum(file, prefix, "aimHelper.speed2", AimAssist.SPEED);
        put(file, prefix, "aimHelper.mode", AimAssist.mode());
        put(file, prefix, "aimHelper.vertical", AimAssist.isVertical());
        putNum(file, prefix, "aimHelper.prediction", AimAssist.PREDICTION);
        putNum(file, prefix, "aimHelper.humanSpeed2", AimAssist.HUMAN_SPEED);
        putNum(file, prefix, "aimHelper.reaction", AimAssist.REACTION);
        putNum(file, prefix, "aimHelper.overshoot2", AimAssist.OVERSHOOT);
        putNum(file, prefix, "aimHelper.fov", AimAssist.FOV);
        putNum(file, prefix, "aimHelper.ignoreMouse", AimAssist.IGNORE_MOUSE);
        put(file, prefix, "aimHelper.aimAt", AimAssist.aimAt());
        put(file, prefix, "aimHelper.lineOfSight", AimAssist.isLineOfSight());
        put(file, prefix, "aimHelper.whileBlocking", AimAssist.isWhileBlocking());
        put(file, prefix, "aimHelper.ignoreInvisible", AimAssist.isIgnoreInvisible());
        put(file, prefix, "aimHelper.ignoreCreative", AimAssist.isIgnoreCreative());
        put(file, prefix, "aimHelper.ground", AimAssist.isGround());
        put(file, prefix, "aimHelper.keyMode", AimAssist.keyMode());
        put(file, prefix, "aimHelper.key", AimAssist.keyCode());
        put(file, prefix, "aimHelper.mouse", AimAssist.mouseButton());
        put(file, prefix, "wheels.enabled", HitWheels.isEnabled());
        put(file, prefix, "wheels.spin", HitWheels.spin());
        put(file, prefix, "wheels.who", HitWheels.who());
        put(file, prefix, "wheels.helmet", HitWheels.helmet());
        put(file, prefix, "pearlCatch.timing", PearlCatch.timing());
        put(file, prefix, "pearlCatch.key", PearlCatch.keyCode());
        put(file, prefix, "pearlCatch.mouse", PearlCatch.mouseButton());
        put(file, prefix, "chestSwap.enabled", ElytraSwap.isEnabled());
        put(file, prefix, "chestSwap.key", ElytraSwap.keyCode());
        put(file, prefix, "chestSwap.mouse", ElytraSwap.mouseButton());
        putNum(file, prefix, "chestSwap.swapDelay", ElytraSwap.SWAP_DELAY);
        putNum(file, prefix, "chestSwap.useDelay", ElytraSwap.USE_DELAY);
        putNum(file, prefix, "chestSwap.swapBack", ElytraSwap.SWAP_BACK);
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
