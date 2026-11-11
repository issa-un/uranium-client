"""
Spear + mace technique lab. NOT part of Lumen: nothing here is built into the jar.

Runs 56 technique ideas against Minecraft's combat math, a few hundred random fights each,
and writes results.md next to this file. Run:  python sim.py  [runs per idea, default 300]

What is modelled (per fight, random each run):
- the opponent: armor set, whether they hold a shield up, how fast they strafe and when
  they react, totems;
- you: ping (20-150 ms), the 3-tick smoothing your client shows other players with, and how
  much you can steer in the air;
- the game's math: player fall physics, the mace's smash damage (with Density and Breach),
  armor, toughness and Protection, the attack cooldown, the 10-tick hurt immunity, shields
  and axes, wind charge and pearl heights, spear reach and Lunge.

Numbers marked ASSUMED are not read from the game; change them at the top if you know better.
"""
import math
import random
import sys
from dataclasses import dataclass, field

# ---- game numbers ----
GRAVITY, AIR_DRAG = 0.08, 0.98            # player, per tick
JUMP_VY = 0.42
WIND_CHARGE_VY = 1.15                      # ASSUMED: self-launch from a wind charge at your feet (~7 blocks)
WIND_BURST_VY = {1: 0.9, 2: 1.05, 3: 1.2}  # ASSUMED: Wind Burst rebound after a slam lands
AIR_STEER = 0.026                          # sprint air control, blocks/tick^2
AIR_TOP_SPEED = 0.3                        # blocks/tick you can drift sideways in the air
JUKE_SECONDS = 0.35                        # ASSUMED: their last direction change you can't follow
CLICK_SPREAD_TICKS = 3.0                   # ASSUMED: how precisely a hand times a click
MACE_BASE = 6.0                            # mace attack damage
SMASH_MIN_FALL = 1.5
MACE_REACH, AXE_REACH, SWORD_REACH = 3.0, 3.0, 3.0
SPEAR_REACH = 4.5
SPEAR_JAB = 7.0                            # ASSUMED: netherite spear jab damage
SPEAR_KINETIC_PER_BPS = 0.55               # ASSUMED: charge damage per block/second of closing speed
SPEAR_MIN_CHARGE_BPS = 5.0                 # ASSUMED: slower than this, a charge does no kinetic damage
SPEAR_AIM_BASE = 0.12                      # ASSUMED: crosshair wobble at 4 blocks, standing still (blocks)
SPEAR_AIM_PER_BPS = 0.05                   # ASSUMED: extra wobble per block/second they strafe
SPEAR_AIM_SLACK = 0.1
ELYTRA_DIVE_BPT = (1.4, 2.4)               # ASSUMED: diving glide speed range, blocks/tick
ELYTRA_LEVEL_BPT = (1.3, 1.8)              # ASSUMED: flat glide speed, blocks/tick
LUNGE_BPS_PER_LEVEL = 0.458 * 20           # from Lumen's Speed HUD
SWORD_DAMAGE, AXE_DAMAGE = 8.0, 10.0       # netherite sword / axe
HURT_IMMUNITY_TICKS = 10
SHIELD_DISABLE_TICKS = 100
INTERP_MS = 150                            # how far behind your client shows other players
HITBOX_HALF = 0.3
PLAYER_HEIGHT = 1.8
EYE = 1.62

ARMOR_SETS = [  # (name, armor points, toughness, protection EPF, weight)
    ("netherite prot IV", 20, 12, 16, 0.45),
    ("diamond prot IV", 20, 8, 16, 0.25),
    ("netherite mixed prot", 20, 12, 12, 0.2),
    ("iron prot II", 15, 0, 8, 0.1),
]


def smash_bonus(fall, density=0):
    """Extra mace damage for the fall: 4/block for 3 blocks, 2/block to 8, then 1/block."""
    if fall <= SMASH_MIN_FALL:
        return 0.0
    if fall <= 3:
        bonus = 4 * fall
    elif fall <= 8:
        bonus = 12 + 2 * (fall - 3)
    else:
        bonus = 22 + (fall - 8)
    return bonus + 0.5 * density * fall


def after_armor(damage, armor, toughness, epf, breach=0, ignores_armor=False):
    """Vanilla armor + toughness, then Protection (capped at 80%)."""
    if not ignores_armor:
        armor_eff = armor * max(0.0, 1 - 0.15 * breach)
        f = 2 + toughness / 4
        reduced = max(armor_eff * 0.2, armor_eff - damage / f)
        damage *= 1 - min(20, reduced) / 25
    return damage * (1 - min(20, epf) / 25)


def cooldown_scale(ticks_since_attack, cooldown_ticks):
    s = min(1.0, (ticks_since_attack + 0.5) / cooldown_ticks)
    return s


def fall_from(vy0, start_height=0.0):
    """Ticks of a launch: list of (height above launch point, vy) until back to 0 or below."""
    y, vy, out = start_height, vy0, []
    for _ in range(400):
        y += vy
        vy = (vy - GRAVITY) * AIR_DRAG
        out.append((y, vy))
        if y <= 0 and vy < 0:
            break
    return out


def apex(vy0):
    return max(h for h, _ in fall_from(vy0))


def fall_speed_after(drop):
    """Fall speed (blocks/tick) after dropping this far from rest."""
    y, vy = 0.0, 0.0
    while -y < drop:
        y += vy
        vy = (vy - GRAVITY) * AIR_DRAG
    return -vy


def ticks_to_fall(drop):
    y, vy, t = 0.0, 0.0, 0
    while -y < drop:
        y += vy
        vy = (vy - GRAVITY) * AIR_DRAG
        t += 1
    return t


# ---- one fight ----
@dataclass
class Fight:
    rng: random.Random
    armor: tuple = None
    shield: bool = False
    strafe_bps: float = 0.0
    react_ms: float = 0.0
    ping_ms: float = 0.0
    totems: int = 1
    hp: float = 20.0
    pops: int = 0
    dealt: float = 0.0
    hits: int = 0
    tries: int = 0
    last_hit_tick: int = -999
    last_hit_damage: float = 0.0
    shield_disabled_until: int = -1
    tick: int = 0
    notes: list = field(default_factory=list)

    @classmethod
    def random(cls, rng, shield_chance=0.3):
        r, acc, armor = rng.random(), 0.0, ARMOR_SETS[0]
        for a in ARMOR_SETS:
            acc += a[4]
            if r <= acc:
                armor = a
                break
        return cls(rng=rng, armor=armor, shield=rng.random() < shield_chance,
                   strafe_bps=rng.uniform(0, 5.6), react_ms=rng.uniform(150, 450),
                   ping_ms=rng.uniform(20, 150), totems=rng.choice([0, 1, 1, 2]))

    def lag_error(self):
        """How far off your client shows them when your hit arrives (blocks)."""
        return self.strafe_bps * (self.ping_ms / 2 + INTERP_MS) / 1000 * self.rng.uniform(0.3, 1.0)

    def deal(self, raw, breach=0, ignores_armor=False, blockable=True, disables_shield=False, counts=True):
        """A hit that connected: shields, immunity, armor, totems. counts=False for the axe
        half of a stun slam, which goes out with the mace as one try."""
        if counts:
            self.hits += 1
        if blockable and self.shield and self.tick >= self.shield_disabled_until:
            if disables_shield:
                self.shield_disabled_until = self.tick + SHIELD_DISABLE_TICKS
            return 0.0
        _, armor, tough, epf, _ = self.armor
        dmg = after_armor(raw, armor, tough, epf, breach, ignores_armor)
        if self.tick - self.last_hit_tick < HURT_IMMUNITY_TICKS:
            extra = dmg - self.last_hit_damage
            if extra <= 0:
                return 0.0
            self.last_hit_damage = dmg
            dmg = extra
        else:
            self.last_hit_tick = self.tick
            self.last_hit_damage = dmg
        self.hp -= dmg
        self.dealt += dmg
        if self.hp <= 0:
            if self.totems > 0:
                self.totems -= 1
                self.pops += 1
                self.hp = 1.0
            else:
                self.hp = 0
        return dmg


def slam_lands(f: Fight, drop, reach=MACE_REACH, steer_ticks=None, horizontal_gap=0.0):
    """Whether a slam from this drop connects: their strafe during the fall minus your air
    steering, plus lag, must stay inside the reach window the fall speed leaves you."""
    t = ticks_to_fall(drop)
    seen_ms = max(0.0, t * 50 - f.react_ms)       # they run once they notice you
    moved = f.strafe_bps * seen_ms / 1000 * f.rng.uniform(0.4, 1.0) + horizontal_gap
    st = steer_ticks if steer_ticks is not None else t
    steer = min(0.5 * AIR_STEER * st ** 2, AIR_TOP_SPEED * st)
    # A change of direction in the last moments of your fall you can no longer answer.
    juke = f.strafe_bps * f.rng.uniform(0, JUKE_SECONDS)
    miss_by = max(0.0, moved - steer) + juke + f.lag_error() + abs(f.rng.gauss(0, 0.3))  # your own aim
    v = fall_speed_after(drop)
    gap = max(0.0, miss_by - HITBOX_HALF)
    if gap >= reach:
        return False, v
    # Ticks your eye spends within reach of their box on the way down, and the chance your
    # click lands in them (a hand clicks within about CLICK_SPREAD_TICKS of the moment).
    # The window opens when they come into reach below you and closes when you land (a
    # slam has to hit before your feet touch the ground, or the fall is gone).
    window = (math.sqrt(reach ** 2 - gap ** 2) + PLAYER_HEIGHT - EYE) / max(v, 0.05)
    return f.rng.random() < min(1.0, window / CLICK_SPREAD_TICKS), v


def try_slam(f, drop, density=0, breach=0, axe_first=False, steer_ticks=None, gap=0.0, cd=1.0):
    f.tries += 1
    ok, _ = slam_lands(f, drop, steer_ticks=steer_ticks, horizontal_gap=gap)
    if not ok:
        return 0.0
    if axe_first:
        # Stun slam: axe and mace in the same tick; the axe takes the shield down first.
        f.deal(AXE_DAMAGE, blockable=True, disables_shield=True, counts=False)
    raw = MACE_BASE * (0.2 + 0.8 * cd * cd) + smash_bonus(drop, density) * cd
    return f.deal(raw, breach=breach)


# ---- the ideas ----
def wc_height(n_charges=1, from_jump=True):
    """Total drop available after chaining wind charges (each at the top of the last)."""
    h = apex(JUMP_VY) if from_jump else 0
    for i in range(n_charges):
        h += apex(WIND_CHARGE_VY) * (0.85 ** i)  # ASSUMED: later charges a bit weaker (timing)
    return h


IDEAS = []


def idea(name, group, how):
    def wrap(fn):
        IDEAS.append((name, group, how, fn))
        return fn
    return wrap


# Mace: where the height comes from
@idea("Jump slam", "Mace height", "Jump and hit on the way down: barely a smash.")
def _(f):
    try_slam(f, apex(JUMP_VY))


@idea("Wind charge slam", "Mace height", "Wind charge at your feet, mace on the way down.")
def _(f):
    try_slam(f, wc_height(1))


@idea("Double wind charge slam", "Mace height", "Second wind charge at the top, then slam.")
def _(f):
    try_slam(f, wc_height(2))


@idea("Triple wind charge slam", "Mace height", "Three chained charges for a huge drop.")
def _(f):
    try_slam(f, wc_height(3))


@idea("Pearl up 10 slam", "Mace height", "Pearl onto something 10 up, drop on them.")
def _(f):
    try_slam(f, 10, steer_ticks=ticks_to_fall(10) * 0.7)


@idea("Pearl up 20 slam", "Mace height", "Pearl 20 up: big drop, long time to read.")
def _(f):
    try_slam(f, 20, steer_ticks=ticks_to_fall(20) * 0.7)


@idea("Pillar 5 drop", "Mace height", "Drop from a 5-block pillar next to them.")
def _(f):
    try_slam(f, 5, gap=0.5)


@idea("Elytra cancel 30", "Mace height", "Glide in high, stop gliding over them, fall 30.")
def _(f):
    try_slam(f, 30, steer_ticks=10)


@idea("Cliff drop 12", "Mace height", "Fight under a ledge, drop 12 onto them.")
def _(f):
    try_slam(f, 12, gap=1.0)


# Mace: enchantments and swaps
@idea("Density V wind slam", "Mace enchant", "Wind charge slam with Density V.")
def _(f):
    try_slam(f, wc_height(1), density=5)


@idea("Breach IV wind slam", "Mace enchant", "Wind charge slam with Breach IV.")
def _(f):
    try_slam(f, wc_height(1), breach=4)


@idea("Density V pearl 20", "Mace enchant", "Big pearl drop with Density V.")
def _(f):
    try_slam(f, 20, density=5, steer_ticks=ticks_to_fall(20) * 0.7)


@idea("Breach IV pearl 20", "Mace enchant", "Big pearl drop with Breach IV.")
def _(f):
    try_slam(f, 20, breach=4, steer_ticks=ticks_to_fall(20) * 0.7)


@idea("Pick Density or Breach by drop", "Mace enchant", "Density for drops over 8, Breach under (Mace Swap style).")
def _(f):
    h = wc_height(f.rng.choice([1, 2]))
    if h > 8:
        try_slam(f, h, density=5)
    else:
        try_slam(f, h, breach=4)


@idea("Breach vs netherite only", "Mace enchant", "Breach only into netherite, Density otherwise.")
def _(f):
    h = wc_height(1)
    try_slam(f, h, breach=4 if "netherite" in f.armor[0] else 0, density=0 if "netherite" in f.armor[0] else 5)


# Mace: chains
@idea("Wind Burst III chain x3", "Mace chain", "Each slam bounces you back up; three in a row.")
def _(f):
    h = wc_height(1)
    for i in range(3):
        if try_slam(f, h) <= 0 and f.hits == 0:
            break
        f.tick += ticks_to_fall(h) + 2 * int(apex(WIND_BURST_VY[3]) ** 0.5 * 4)
        h = apex(WIND_BURST_VY[3])


@idea("Wind Burst I chain x3", "Mace chain", "Same chain, weaker bounce.")
def _(f):
    h = wc_height(1)
    for i in range(3):
        if try_slam(f, h) <= 0 and f.hits == 0:
            break
        f.tick += ticks_to_fall(h) + 12
        h = apex(WIND_BURST_VY[1])


@idea("Wind charge every cooldown", "Mace chain", "Re-launch with a wind charge after each slam (0.5 s charge cooldown).")
def _(f):
    for i in range(3):
        try_slam(f, wc_height(1))
        f.tick += 30


@idea("Slam then sword", "Mace chain", "Slam, then a sword hit once the immunity is over.")
def _(f):
    try_slam(f, wc_height(1))
    f.tick += 11
    f.tries += 1
    if f.rng.random() < 0.75:
        f.deal(SWORD_DAMAGE)


@idea("Sword then slam in immunity", "Mace chain", "Sword hit, then a slam inside the 10-tick immunity (only the extra counts).")
def _(f):
    f.tries += 1
    if f.rng.random() < 0.8:
        f.deal(SWORD_DAMAGE)
    f.tick += 6
    try_slam(f, wc_height(1))


@idea("Two slams spaced 10 ticks", "Mace chain", "Wait out the immunity between slams.")
def _(f):
    try_slam(f, wc_height(1))
    f.tick += HURT_IMMUNITY_TICKS
    try_slam(f, apex(WIND_BURST_VY[2]))


# Mace: shields
@idea("Plain slam into shield", "Shield", "Slam a shielding player with no axe.")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    try_slam(f, wc_height(1))


@idea("Stun slam (axe + mace same tick)", "Shield", "Axe takes the shield down, mace lands the same tick.")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    try_slam(f, wc_height(1), axe_first=True)


@idea("Axe first, slam next fall", "Shield", "Axe the shield on the ground, then wind charge slam.")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    f.tries += 1
    if f.rng.random() < 0.85:
        f.deal(AXE_DAMAGE, disables_shield=True)
    f.tick += ticks_to_fall(wc_height(1)) + 5
    try_slam(f, wc_height(1))


@idea("Stun slam from pearl 20", "Shield", "Pearl high, stun slam on the way down.")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    try_slam(f, 20, axe_first=True, steer_ticks=ticks_to_fall(20) * 0.7)


@idea("Stun slam Breach IV", "Shield", "Stun slam with a Breach IV mace.")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    try_slam(f, wc_height(1), axe_first=True, breach=4)


@idea("Stun slam Density V double charge", "Shield", "Two charges, Density V, axe first.")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    try_slam(f, wc_height(2), axe_first=True, density=5)


# Spear
def spear_hit(f, damage, reach=SPEAR_REACH, gap=0.0):
    """A spear jab lands when your crosshair is on them (the server only checks range, with
    room to spare): a strafing target at range is a small thing to keep a crosshair on, and
    standing further out (gap) sometimes leaves them just past the tip."""
    f.tries += 1
    aim_error = abs(f.rng.gauss(0, SPEAR_AIM_BASE + SPEAR_AIM_PER_BPS * f.strafe_bps))
    if aim_error > HITBOX_HALF + SPEAR_AIM_SLACK:
        return 0.0
    if f.rng.random() < min(0.5, gap * 0.08):
        return 0.0  # just out of reach
    return f.deal(damage)


@idea("Spear jab poke", "Spear", "Stand at 4.5 and poke, out of their sword reach.")
def _(f):
    for _ in range(3):
        spear_hit(f, SPEAR_JAB, gap=f.rng.uniform(0.5, 1.5))
        f.tick += 20


@idea("Spear sprint charge", "Spear", "Sprint at them and let the speed do damage.")
def _(f):
    speed = 5.6 + f.strafe_bps * f.rng.uniform(-1, 1) * 0.5
    bonus = SPEAR_KINETIC_PER_BPS * speed if speed > SPEAR_MIN_CHARGE_BPS else 0
    spear_hit(f, SPEAR_JAB + bonus, gap=0.8)


@idea("Lunge I jab", "Spear", "Lunge I to close the gap, jab.")
def _(f):
    spear_hit(f, SPEAR_JAB + SPEAR_KINETIC_PER_BPS * (5.6 + LUNGE_BPS_PER_LEVEL * 1) * 0.5, gap=1.5)


@idea("Lunge III jab", "Spear", "Lunge III: much faster close, bigger kinetic hit.")
def _(f):
    spear_hit(f, SPEAR_JAB + SPEAR_KINETIC_PER_BPS * (5.6 + LUNGE_BPS_PER_LEVEL * 3) * 0.5, gap=2.5)


@idea("Horse charge", "Spear", "Ride a horse into them (14 blocks/s).")
def _(f):
    spear_hit(f, SPEAR_JAB + SPEAR_KINETIC_PER_BPS * 14, gap=1.0)


@idea("Wind charge forward + spear", "Spear", "Wind charge behind you for speed, then jab.")
def _(f):
    spear_hit(f, SPEAR_JAB + SPEAR_KINETIC_PER_BPS * 12, gap=1.5)


@idea("Spear poke into shield", "Spear", "Poke someone holding a shield up.")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    for _ in range(3):
        spear_hit(f, SPEAR_JAB, gap=1.0)
        f.tick += 20


@idea("Spear stun (axe + spear)", "Spear", "Axe the shield, spear jab the same tick (axe needs 3 blocks).")
def _(f):
    f.shield = f.shield or f.rng.random() < 0.7
    f.tries += 1
    if f.lag_error() + f.rng.uniform(0, 1.5) < AXE_REACH:
        f.deal(AXE_DAMAGE, disables_shield=True)
    spear_hit(f, SPEAR_JAB, gap=0.5)


@idea("Spear then sword", "Spear", "Poke at 4.5, step in, sword after the immunity.")
def _(f):
    spear_hit(f, SPEAR_JAB, gap=1.2)
    f.tick += 11
    f.tries += 1
    if f.rng.random() < 0.7:
        f.deal(SWORD_DAMAGE)


# Spear vs shields
def shielded(f):
    f.shield = f.shield or f.rng.random() < 0.7


@idea("Spear stun poke at 4.5", "Spear shield", "Axe + spear jab in the same tick from 4.5: the server lets the axe reach that far, so the shield drops and the jab lands (Lumen's Shield Break with a spear).")
def _(f):
    shielded(f)
    for _ in range(2):
        f.tries += 1
        aim_error = abs(f.rng.gauss(0, SPEAR_AIM_BASE + SPEAR_AIM_PER_BPS * f.strafe_bps))
        if aim_error <= HITBOX_HALF + SPEAR_AIM_SLACK:
            f.deal(AXE_DAMAGE, disables_shield=True, counts=False)
            f.deal(SPEAR_JAB)
        f.tick += 20


@idea("Lunge spear stun", "Spear shield", "Lunge III in, axe + spear the same tick: shield down plus the Lunge's kinetic hit.")
def _(f):
    shielded(f)
    f.tries += 1
    aim_error = abs(f.rng.gauss(0, SPEAR_AIM_BASE * 1.5 + SPEAR_AIM_PER_BPS * f.strafe_bps))
    if aim_error <= HITBOX_HALF + SPEAR_AIM_SLACK:
        f.deal(AXE_DAMAGE, disables_shield=True, counts=False)
        f.deal(SPEAR_JAB + SPEAR_KINETIC_PER_BPS * (5.6 + LUNGE_BPS_PER_LEVEL * 3) * 0.5)


@idea("Lunge behind, poke the back", "Spear shield", "Lunge III past them and poke their back before they turn their shield around.")
def _(f):
    shielded(f)
    pass_ms = 1000 * 3.0 / (5.6 + LUNGE_BPS_PER_LEVEL * 3)  # about 3 blocks to get past them
    turn_ms = f.react_ms + f.rng.uniform(50, 200)              # they spin to face you again
    if pass_ms + 100 < turn_ms:
        f.shield_disabled_until = f.tick + 1                   # their shield faces away: no block
    spear_hit(f, SPEAR_JAB, gap=0.5)


@idea("Poke when they swing", "Spear shield", "Wait at 4.5: they drop the shield to swing at you, and the jab lands in that gap.")
def _(f):
    shielded(f)
    for _ in range(3):
        if f.rng.random() < 0.45:                              # ASSUMED: they lower it to attack
            f.shield_disabled_until = f.tick + 1
        spear_hit(f, SPEAR_JAB, gap=0.8)
        f.tick += 20


# Spear + mace hybrids
@idea("Lunge then slam", "Hybrid", "Wind charge up, Lunge across the gap in the air, slam.")
def _(f):
    try_slam(f, wc_height(1), gap=max(0.0, f.rng.uniform(1, 4) - LUNGE_BPS_PER_LEVEL * 0.15))


@idea("Slam then spear poke", "Hybrid", "Slam, back off, spear them as they chase.")
def _(f):
    try_slam(f, wc_height(1))
    f.tick += 12
    spear_hit(f, SPEAR_JAB, gap=1.5)


@idea("Spear poke then slam", "Hybrid", "Poke to start, wind charge up while they close in, slam.")
def _(f):
    spear_hit(f, SPEAR_JAB, gap=1.2)
    f.tick += 12
    try_slam(f, wc_height(1))


@idea("Lunge escape, slam back", "Hybrid", "Lunge away when low, turn and slam when they follow.")
def _(f):
    f.tick += 20
    try_slam(f, wc_height(1), gap=f.rng.uniform(0, 2))


@idea("Spear knock up, mace follow", "Hybrid", "ASSUMED: spear hit knocks them up a little; slam as they fall.")
def _(f):
    spear_hit(f, SPEAR_JAB, gap=1.0)
    f.tick += 10
    try_slam(f, wc_height(1) + 0.8)


# Elytra
def elytra_mace(f, dive_from, glide_again=False):
    """Lumen's Elytra Mace: dive at them, the elytra comes off (ping + 2 ticks) before reach,
    you fall the rest and the hit counts as a slam of the whole dive. You steer with the
    elytra until the drop, so only their dodge after it counts against you."""
    f.tries += 1
    speed = f.rng.uniform(*ELYTRA_DIVE_BPT)
    lead_ticks = f.ping_ms / 50 + 2
    dodge = f.strafe_bps * (lead_ticks * 50 + f.rng.uniform(0, 150)) / 1000 * f.rng.uniform(0.3, 1.0)
    miss_by = dodge + f.lag_error() + abs(f.rng.gauss(0, 0.3))
    gap = max(0.0, miss_by - HITBOX_HALF)
    if gap >= MACE_REACH:
        return 0.0
    window = (math.sqrt(MACE_REACH ** 2 - gap ** 2) + PLAYER_HEIGHT - EYE) / speed
    if f.rng.random() >= min(1.0, window / CLICK_SPREAD_TICKS):
        return 0.0
    raw = MACE_BASE + smash_bonus(dive_from)
    return f.deal(raw)


@idea("Elytra Mace dive 15 (Lumen)", "Elytra", "Lumen's Elytra Mace: dive from 15 up, elytra drops just before reach, slam.")
def _(f):
    elytra_mace(f, 15)


@idea("Elytra Mace dive 30 (Lumen)", "Elytra", "Same from 30 up: a much bigger slam, faster fall.")
def _(f):
    elytra_mace(f, 30)


@idea("Elytra Mace + Density V (Lumen)", "Elytra", "Elytra Mace from 20 with a Density V mace.")
def _(f):
    f.tries += 0
    hp_before = f.dealt
    elytra_mace(f, 20)
    if f.dealt > hp_before:  # the same dive, Density's extra on top
        f.tick += HURT_IMMUNITY_TICKS - 1
        f.hp += f.dealt - hp_before
        f.dealt = hp_before
        f.last_hit_tick = -999
        f.deal(MACE_BASE + smash_bonus(20, density=5), counts=False)


@idea("Elytra Mace, glide again x2 (Lumen)", "Elytra", "Hit, glide again, climb, second dive.")
def _(f):
    elytra_mace(f, 18)
    f.tick += 80
    elytra_mace(f, 18)


@idea("Elytra spear poke", "Elytra", "Fly past them with a spear: the flight speed is the charge.")
def _(f):
    speed_bps = f.rng.uniform(*ELYTRA_LEVEL_BPT) * 20
    f.tries += 1
    # Passing at 30+ blocks/second: the 4.5-block tip is in reach for very few ticks.
    window = 2 * SPEAR_REACH / (speed_bps / 20)
    aim_error = abs(f.rng.gauss(0, SPEAR_AIM_BASE * 2 + SPEAR_AIM_PER_BPS * f.strafe_bps))
    if aim_error > HITBOX_HALF + SPEAR_AIM_SLACK or f.rng.random() >= min(1.0, window / CLICK_SPREAD_TICKS):
        return
    f.deal(SPEAR_JAB + SPEAR_KINETIC_PER_BPS * speed_bps)


@idea("Spear poke at 3 blocks", "Spear", "Poke close in, where aim is easier but their sword reaches you.")
def _(f):
    for _ in range(3):
        spear_hit(f, SPEAR_JAB, gap=0.0)
        f.tick += 20


# Timing and reading
@idea("Slam when they eat", "Timing", "Wait for a gapple: they stand still for 1.6 s.")
def _(f):
    f.strafe_bps *= 0.2
    f.shield = False
    try_slam(f, wc_height(1))


@idea("Slam when they pearl", "Timing", "Slam where their pearl lands.")
def _(f):
    f.strafe_bps = f.rng.uniform(0, 1.5)
    try_slam(f, wc_height(1), gap=f.rng.uniform(0, 3))


@idea("Slam while they block", "Timing", "Slam right as their shield is still rising (5 ticks).")
def _(f):
    f.shield = False
    try_slam(f, wc_height(1))


@idea("Slam after their pop", "Timing", "Slam right after a totem pop: they have 1 hp + absorption.")
def _(f):
    f.hp = 1 + 8
    try_slam(f, wc_height(1))


@idea("Corner them, then slam", "Timing", "Trap them against a wall first: no strafe space.")
def _(f):
    f.strafe_bps *= 0.3
    try_slam(f, wc_height(1))


@idea("Slam from behind", "Timing", "Come down behind them: slower to react.")
def _(f):
    f.react_ms += 250
    try_slam(f, wc_height(1))


@idea("Slam with low ping", "Timing", "Same slam with 20 ms ping.")
def _(f):
    f.ping_ms = 20
    try_slam(f, wc_height(1))


@idea("Slam with high ping", "Timing", "Same slam with 200 ms ping.")
def _(f):
    f.ping_ms = 200
    try_slam(f, wc_height(1))


@idea("Half cooldown slam", "Timing", "Slam before your mace cooldown is full (50%).")
def _(f):
    try_slam(f, wc_height(1), cd=0.5)


@idea("Spam slam", "Timing", "Slam as soon as you land, no waiting (cooldown ~30%).")
def _(f):
    try_slam(f, wc_height(1), cd=0.3)


# Movement
@idea("Strafe-in slam", "Movement", "Steer in the air the whole fall.")
def _(f):
    try_slam(f, wc_height(1), steer_ticks=ticks_to_fall(wc_height(1)) * 1.2)


@idea("No-steer slam", "Movement", "Straight up, straight down.")
def _(f):
    try_slam(f, wc_height(1), steer_ticks=0)


@idea("Diagonal wind launch", "Movement", "Wind charge at an angle to land on a runner.")
def _(f):
    try_slam(f, wc_height(1) * 0.8, steer_ticks=ticks_to_fall(wc_height(1)) * 1.5)


@idea("Pearl catch slam", "Movement", "Pearl + wind charge catch for height anywhere.")
def _(f):
    try_slam(f, 14, steer_ticks=ticks_to_fall(14) * 0.8, gap=f.rng.uniform(0, 1.5))


@idea("Ender pearl over, drop", "Movement", "Pearl over their head and fall straight on them.")
def _(f):
    try_slam(f, 8, steer_ticks=5, gap=f.rng.uniform(0, 2))


def run(runs):
    rows = []
    for index, (name, group, how, fn) in enumerate(IDEAS):
        rng = random.Random(1000 + index)
        damage, hits, tries, pops, kills = [], 0, 0, 0, 0
        for _ in range(runs):
            f = Fight.random(rng)
            fn(f)
            damage.append(f.dealt)
            hits += f.hits
            tries += f.tries
            pops += f.pops
            kills += 1 if f.hp <= 0 else 0
        damage.sort()
        rows.append(dict(name=name, group=group, how=how,
                         avg=sum(damage) / runs, p90=damage[int(runs * 0.9)],
                         land=hits / max(1, tries), pops=pops / runs, kills=kills / runs))
    return rows


def main():
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    rows = run(runs)
    assert len(rows) == 66, len(rows)
    rows.sort(key=lambda r: -(r["avg"] + 10 * r["pops"] + 20 * r["kills"]))
    out = ["# Spear + mace lab: %d ideas, %d random fights each\n" % (len(rows), runs),
           "Not in Lumen. Each fight rolls the opponent's armor, shield, strafing, reaction, "
           "totems and your ping, then runs the game's combat math. Damage is after armor and "
           "Protection, in hit points (2 = one heart). Ranked by damage + pops + kills.\n",
           "| # | Idea | Group | Lands | Avg dmg | Top 10% dmg | Pops/fight | Kills/fight |",
           "|---|---|---|---|---|---|---|---|"]
    for i, r in enumerate(rows, 1):
        out.append("| %d | %s | %s | %.0f%% | %.1f | %.1f | %.2f | %.2f |" % (
            i, r["name"], r["group"], 100 * r["land"], r["avg"], r["p90"], r["pops"], r["kills"]))
    out.append("\n## What each idea is\n")
    for r in rows:
        out.append("- **%s** (%s): %s" % (r["name"], r["group"], r["how"]))
    path = __file__.rsplit("sim.py", 1)[0] + "results.md"
    open(path, "w", encoding="utf-8").write("\n".join(out) + "\n")
    for i, r in enumerate(rows, 1):
        print("%2d %-36s %-13s land %3.0f%%  avg %5.1f  p90 %5.1f  pops %.2f  kills %.2f" % (
            i, r["name"], r["group"], 100 * r["land"], r["avg"], r["p90"], r["pops"], r["kills"]))


if __name__ == "__main__":
    main()
