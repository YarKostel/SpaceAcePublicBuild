package com.spaceace.game.weapon

import com.spaceace.game.Config
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.WeaponType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerTest {
    private val dt = 1f / 60f
    private val eps = 1e-4f

    private fun run(mode: FireMode, held: List<Boolean>): List<Float?> {
        var trigger = Trigger()
        return held.map { h -> trigger.update(mode, h, dt).also { trigger = it.trigger }.power }
    }

    @Test
    fun `auto fires immediately and then at its interval`() {
        val shots = run(FireMode.Auto(interval = 4 * dt), List(13) { true })
        assertEquals(listOf(0, 4, 8, 12), shots.indices.filter { shots[it] != null })
    }

    @Test
    fun `auto stops when released`() {
        val shots = run(FireMode.Auto(interval = dt), listOf(true, false, false))
        assertEquals(1, shots.count { it != null })
    }

    @Test
    fun `semi-auto fires once per press`() {
        val shots = run(FireMode.SemiAuto(interval = dt), listOf(true, true, true, false, true))
        assertEquals(listOf(0, 4), shots.indices.filter { shots[it] != null })
    }

    @Test
    fun `charge fires on release with power proportional to charge`() {
        val mode = FireMode.Charge(minCharge = 2 * dt, maxCharge = 10 * dt, cooldown = 0f)
        val shots = run(mode, List(5) { true } + false)
        assertTrue(shots.dropLast(1).all { it == null })
        assertEquals(0.5f, assertNotNull(shots.last()), eps)
    }

    @Test
    fun `charge is capped at full power`() {
        val mode = FireMode.Charge(minCharge = dt, maxCharge = 3 * dt, cooldown = 0f)
        assertEquals(1f, assertNotNull(run(mode, List(20) { true } + false).last()), eps)
    }

    @Test
    fun `too short charge does not fire`() {
        val mode = FireMode.Charge(minCharge = 5 * dt, maxCharge = 10 * dt, cooldown = 0f)
        assertNull(run(mode, listOf(true, true, false)).last())
    }

    @Test
    fun `charge waits for cooldown before accumulating`() {
        val mode = FireMode.Charge(minCharge = dt, maxCharge = 10 * dt, cooldown = 3 * dt)
        val first = run(mode, listOf(true, true, false))
        assertNotNull(first.last())
        var trigger = Trigger(cooldown = 3 * dt)
        repeat(2) { trigger = trigger.update(mode, true, dt).trigger }
        assertEquals(0f, trigger.charge)
    }

    @Test
    fun `charge fraction for indicator`() {
        val mode = FireMode.Charge(minCharge = 0f, maxCharge = 2f, cooldown = 0f)
        assertEquals(0.25f, Trigger(charge = 0.5f).chargeFraction(mode), eps)
        assertEquals(0f, Trigger(charge = 0.5f).chargeFraction(FireMode.Auto(1f)))
    }
}

class WeaponTest {
    private val eps = 1e-4f
    private val muzzle = Vec2(3f, 2f)
    private val maxAngle = (PI / 3).toFloat()

    private fun angleFromVertical(v: Vec2) = atan2(v.x, v.y)

    @Test
    fun `every weapon type maps to a weapon`() {
        for (type in WeaponType.entries) assertEquals(type, Weapon.of(type).type)
    }

    @Test
    fun `machine gun shoots one bullet within spread`() {
        val random = Random(1)
        val spread = Math.toRadians(Config.Weapons.MachineGun.SPREAD_DEG.toDouble()).toFloat()
        repeat(100) {
            val shot = Weapon.MachineGun.shots(1f, Aim(muzzle, emptyList()), random).single()
            assertEquals(ProjectileKind.BULLET, shot.kind)
            assertEquals(Config.Weapons.MachineGun.SPEED, shot.velocity.length, eps)
            assertTrue(abs(angleFromVertical(shot.velocity)) <= spread + eps)
        }
    }

    @Test
    fun `gauss speed grows with charge`() {
        val weak = Weapon.GaussCannon.shots(0f, Aim(muzzle, emptyList()), Random(1)).single()
        val strong = Weapon.GaussCannon.shots(1f, Aim(muzzle, emptyList()), Random(1)).single()
        assertEquals(Config.Weapons.Gauss.MIN_SPEED, weak.velocity.y, eps)
        assertEquals(Config.Weapons.Gauss.MAX_SPEED, strong.velocity.y, eps)
        assertEquals(ProjectileKind.GAUSS_SLUG, strong.kind)
    }

    @Test
    fun `aim picks the target closest in height above the muzzle`() {
        val targets = listOf(Vec2(6f, 9f), Vec2(1f, 4f), Vec2(3f, 1f))
        val direction = aimDirection(muzzle, targets, maxAngle)
        val expected = Vec2(1f - 3f, 4f - 2f)
        assertEquals(angleFromVertical(expected), angleFromVertical(direction), eps)
        assertEquals(1f, direction.length, eps)
    }

    @Test
    fun `targets outside the aim cone are ignored instead of shooting into the wall`() {
        assertEquals(Vec2(0f, 1f), aimDirection(muzzle, listOf(Vec2(7f, 2.1f)), maxAngle))
        val reachable = Vec2(4f, 6f)
        val direction = aimDirection(muzzle, listOf(Vec2(7f, 2.1f), reachable), maxAngle)
        assertEquals(angleFromVertical(reachable - muzzle), angleFromVertical(direction), eps)
    }

    @Test
    fun `aim without targets above goes straight up`() {
        assertEquals(Vec2(0f, 1f), aimDirection(muzzle, listOf(Vec2(3f, 1f)), maxAngle))
    }

    @Test
    fun `ricochet shoots towards the aim`() {
        val shot = Weapon.RicochetCannon.shots(1f, Aim(muzzle, listOf(Vec2(4f, 3f))), Random(1)).single()
        assertEquals(ProjectileKind.RICOCHET_ORB, shot.kind)
        assertTrue(shot.velocity.x > 0f && shot.velocity.y > 0f)
        assertEquals(Config.Weapons.Ricochet.SPEED, shot.velocity.length, eps)
    }
}

class NewWeaponsTest {
    private val dt = 1f / 60f
    private val eps = 1e-4f
    private val aim = Aim(Vec2(3f, 2f), emptyList())

    /** Номера шагов, на которых был выстрел, и сила каждого. */
    private fun fire(weapon: Weapon, held: List<Boolean>): List<Pair<Int, Float>> {
        var trigger = Trigger()
        return held.mapIndexedNotNull { step, h ->
            val result = trigger.update(weapon.mode, h, dt)
            trigger = result.trigger
            result.power?.let { step to it }
        }
    }

    @Test
    fun `machine gun fires slower than before and slower than gatling`() {
        val mg = (Weapon.MachineGun.mode as FireMode.Auto).interval
        val gatling = (Weapon.Gatling.mode as FireMode.Auto).interval
        assertTrue(mg >= 0.15f, "machine gun interval $mg")
        assertTrue(gatling < mg)
    }

    @Test
    fun `gatling round is heavier and slower with wider spread`() {
        val bullet = ProjectileKind.BULLET.stats
        val round = ProjectileKind.GATLING_ROUND.stats
        assertTrue(round.hitbox.area * round.density > bullet.hitbox.area * bullet.density)
        assertTrue(Config.Weapons.Gatling.SPEED < Config.Weapons.MachineGun.SPEED)
        assertTrue(Config.Weapons.Gatling.SPREAD_DEG > Config.Weapons.MachineGun.SPREAD_DEG)
        val shot = Weapon.Gatling.shots(1f, aim, Random(1)).single()
        assertEquals(ProjectileKind.GATLING_ROUND, shot.kind)
        assertEquals(Config.Weapons.Gatling.SPEED, shot.velocity.length, eps)
    }

    @Test
    fun `ricochet fires one orb per click without charging`() {
        assertIs<FireMode.SemiAuto>(Weapon.RicochetCannon.mode)
        val gap = (Config.Weapons.Ricochet.INTERVAL / dt).toInt() + 2
        val clicks = List(3) { listOf(true) + List(gap) { false } }.flatten()
        assertEquals(3, fire(Weapon.RicochetCannon, clicks).size)
        assertEquals(1, fire(Weapon.RicochetCannon, List(gap * 3) { true }).size, "holding is a single shot")
        assertEquals(1, Weapon.RicochetCannon.shots(1f, aim, Random(1)).size)
    }

    @Test
    fun `missiles charge, then fire three salvos of three, then a long cooldown`() {
        val cfg = Config.Weapons.IrMissiles
        val chargeSteps = (cfg.CHARGE / dt).toInt() + 2
        val after = (cfg.COOLDOWN / dt).toInt()
        val shots = fire(Weapon.IrMissiles, List(chargeSteps) { true } + List(after) { false })
        assertEquals(cfg.SALVOS, shots.size, "$shots")
        val gaps = shots.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.all { abs(it - cfg.SALVO_INTERVAL / dt) <= 1 }, "$gaps")
        assertEquals(cfg.MISSILES_PER_SALVO, Weapon.IrMissiles.shots(1f, aim, Random(1)).size)

        val tooEarly = fire(Weapon.IrMissiles, List(chargeSteps) { true } + List(30) { false } + List(chargeSteps) { true } + false)
        assertEquals(cfg.SALVOS, tooEarly.size, "second charge must wait for the cooldown")
    }

    @Test
    fun `short tap does not launch missiles`() {
        assertTrue(fire(Weapon.IrMissiles, listOf(true, true, false)).isEmpty())
    }

    @Test
    fun `missiles are fanned and fast`() {
        val shots = Weapon.IrMissiles.shots(1f, aim, Random(1))
        val angles = shots.map { atan2(it.velocity.x, it.velocity.y) }
        assertEquals(0f, angles[1], eps)
        assertTrue(angles[0] < 0f && angles[2] > 0f)
        assertTrue(shots.all { abs(it.velocity.length - Config.Weapons.IrMissiles.SPEED) < eps && it.kind == ProjectileKind.MISSILE })
        assertTrue(ProjectileKind.MISSILE.stats.homingTurnRate > 0f)
    }

    @Test
    fun `homing turns towards the target by at most the turn limit and keeps speed`() {
        val velocity = Vec2(0f, 10f)
        val maxTurn = (PI / 18).toFloat()
        val turned = steerHoming(velocity, toTarget = Vec2(5f, 0f), maxTurn = maxTurn)
        assertEquals(10f, turned.length, eps)
        assertEquals(maxTurn, atan2(turned.x, turned.y), eps)

        val aligned = steerHoming(velocity, toTarget = Vec2(0.1f, 5f), maxTurn = maxTurn)
        assertEquals(atan2(0.1f, 5f), atan2(aligned.x, aligned.y), eps)
    }

    @Test
    fun `homing takes the short way round`() {
        val turned = steerHoming(Vec2(-10f, -0.1f), toTarget = Vec2(-1f, 1f), maxTurn = 0.1f)
        assertTrue(turned.y > -0.1f, "should turn up through the left, got $turned")
    }
}
