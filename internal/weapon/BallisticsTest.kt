package com.spaceace.game.weapon

import com.spaceace.game.Config
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Faction
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.Split
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.WeaponType
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newPlayer
import com.spaceace.game.model.newProjectile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommonTest {
    private val eps = 1e-4f

    @Test
    fun `spread stays within its cone`() {
        val random = Random(5)
        val spread = degreesToRadians(10f)
        repeat(200) {
            val direction = spreadDirection(spread, random)
            assertEquals(1f, direction.length, eps)
            assertTrue(abs(atan2(direction.x, direction.y)) <= spread + eps)
        }
    }

    @Test
    fun `only meteors, enemies and bosses are weapon targets`() {
        assertTrue(isWeaponTarget(newMeteor(EntityId(1), MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO)))
        assertFalse(isWeaponTarget(newPlayer(EntityId(2))))
        assertFalse(isWeaponTarget(newProjectile(EntityId(3), ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO)))
    }

    @Test
    fun `projectile starts above the nose so it does not touch the ship`() {
        val player = newPlayer(EntityId(1))
        val muzzle = muzzleOf(player)
        assertEquals(player.position.x, muzzle.x, eps)
        assertEquals(player.position.y + player.hitbox.top + Config.Player.MUZZLE_GAP, muzzle.y, eps)
        val start = launchPosition(muzzle, ProjectileKind.GAUSS_SLUG)
        assertEquals(muzzle.y + ProjectileKind.GAUSS_SLUG.stats.radius, start.y, eps)
    }

    @Test
    fun `heavy rounds scale projectile mass`() {
        assertEquals(1f, shotMassScale(heavyRounds = false))
        assertEquals(Config.Weapons.HEAVY_MASS_SCALE, shotMassScale(heavyRounds = true))
    }
}

class GaussTest {
    private val settings = PierceSettings(momentumTransfer = 0.5f, speedRetention = 0.5f, minSpeed = 4f)

    @Test
    fun `charged value grows with power and is clamped`() {
        assertEquals(10f, charged(10f, 20f, 0f))
        assertEquals(15f, charged(10f, 20f, 0.5f))
        assertEquals(20f, charged(10f, 20f, 3f))
    }

    @Test
    fun `target gets part of the momentum and the slug slows down`() {
        val result = pierce(mass = 2f, velocity = Vec2(0f, 20f), settings)
        assertEquals(Vec2(0f, 20f), result.impulse)
        assertEquals(Vec2(0f, 10f), result.velocity)
    }

    @Test
    fun `slug stops when too slow after piercing`() {
        assertNull(pierce(mass = 1f, velocity = Vec2(0f, 6f), settings).velocity)
    }
}

class RicochetTest {
    private val eps = 1e-4f

    @Test
    fun `aiming without targets above goes straight up`() {
        assertEquals(STRAIGHT_UP, aimDirection(Vec2(3f, 5f), emptyList(), 1f))
        assertEquals(STRAIGHT_UP, aimDirection(Vec2(3f, 5f), listOf(Vec2(4f, 2f)), 1f), "targets below are ignored")
    }

    @Test
    fun `aims at the lowest target above within the cone`() {
        val muzzle = Vec2(0f, 0f)
        val direction = aimDirection(muzzle, listOf(Vec2(0f, 5f), Vec2(1f, 1f)), maxAngle = 1.5f)
        assertEquals(atan2(1f, 1f), atan2(direction.x, direction.y), eps)
        val beside = Vec2(10f, 1f)
        assertEquals(STRAIGHT_UP, aimDirection(muzzle, listOf(beside), maxAngle = 0.3f), "too far to the side")
        val further = aimDirection(muzzle, listOf(beside, Vec2(1f, 8f)), maxAngle = 0.3f)
        assertEquals(atan2(1f, 8f), atan2(further.x, further.y), eps)
    }
}

class IrMissilesTest {
    private val eps = 1e-4f
    private val near = newMeteor(EntityId(1), MeteorSize.SMALL, Vec2(1f, 1f), Vec2.ZERO)
    private val far = newMeteor(EntityId(2), MeteorSize.SMALL, Vec2(5f, 5f), Vec2.ZERO)
    private val missile = newProjectile(EntityId(10), ProjectileKind.MISSILE, Vec2.ZERO, Vec2(0f, 10f))

    @Test
    fun `fan is symmetric around the vertical`() {
        val step = degreesToRadians(12f)
        val angles = (0 until 3).map { fanDirection(it, 3, step).let { d -> atan2(d.x, d.y) } }
        assertEquals(-step, angles[0], eps)
        assertEquals(0f, angles[1], eps)
        assertEquals(step, angles[2], eps)
        assertEquals(STRAIGHT_UP, fanDirection(0, 1, step), "single shot goes straight")
    }

    @Test
    fun `keeps the current target while it lives`() {
        assertEquals(far, homingTarget(current = far, candidates = listOf(near, far), from = Vec2.ZERO))
    }

    @Test
    fun `picks the nearest when the target is gone`() {
        assertEquals(near, homingTarget(current = null, candidates = listOf(far, near), from = Vec2.ZERO))
        assertNull(homingTarget(current = null, candidates = emptyList(), from = Vec2.ZERO))
    }

    @Test
    fun `guided missile locks on and turns towards the target`() {
        val result = assertNotNull(guided(missile, current = null, candidates = listOf(near), dt = 1f / 60f))
        assertEquals(near.id, result.target)
        assertTrue(result.velocity.x > 0f, "turned right towards ${near.position}: ${result.velocity}")
        assertEquals(missile.velocity.length, result.velocity.length, eps)
    }

    @Test
    fun `targets behind or beside the missile are ignored`() {
        val behind = newMeteor(EntityId(3), MeteorSize.SMALL, Vec2(0.5f, -2f), Vec2.ZERO)
        val beside = newMeteor(EntityId(4), MeteorSize.SMALL, Vec2(3f, 0f), Vec2.ZERO)
        assertNull(guided(missile, current = null, candidates = listOf(behind, beside), dt = 1f / 60f))
        val result = assertNotNull(guided(missile, current = null, candidates = listOf(behind, beside, far), dt = 1f / 60f))
        assertEquals(far.id, result.target, "the only target ahead")
    }

    @Test
    fun `current target is dropped once it falls out of the seek cone`() {
        val passed = newMeteor(EntityId(5), MeteorSize.SMALL, Vec2(0f, -1f), Vec2.ZERO)
        val result = assertNotNull(guided(missile, current = passed, candidates = listOf(passed, near), dt = 1f / 60f))
        assertEquals(near.id, result.target)
    }

    @Test
    fun `non-homing projectiles and empty skies are left alone`() {
        val bullet = newProjectile(EntityId(11), ProjectileKind.BULLET, Vec2.ZERO, Vec2(0f, 10f))
        assertNull(guided(bullet, current = null, candidates = listOf(near), dt = 1f))
        assertNull(guided(missile, current = null, candidates = emptyList(), dt = 1f))
    }
}

class FlakTest {
    private val cfg = Config.Weapons.Flak
    private val shell = newProjectile(EntityId(1), ProjectileKind.FLAK_SHELL, Vec2(3f, 5f), Vec2(0f, cfg.SPEED))

    @Test
    fun `flak fires one shell per click straight up`() {
        val weapon = Weapon.of(WeaponType.FLAK)
        assertIs<FireMode.SemiAuto>(weapon.mode)
        val shot = weapon.shots(1f, Aim(Vec2.ZERO, emptyList()), Random(1)).single()
        assertEquals(ProjectileKind.FLAK_SHELL, shot.kind)
        assertEquals(Vec2(0f, cfg.SPEED), shot.velocity)
    }

    @Test
    fun `shell explodes only after the fuse and lives long enough to do it`() {
        assertFalse(isFlakReady(shell))
        assertTrue(isFlakReady(shell.aged(cfg.FUSE)))
        assertTrue(cfg.FUSE < ProjectileKind.FLAK_SHELL.stats.lifetime)
    }

    @Test
    fun `other projectiles never explode`() {
        val bullet = newProjectile(EntityId(2), ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO).aged(10f)
        assertFalse(isFlakReady(bullet))
    }

    @Test
    fun `shrapnel spreads evenly around the shell and keeps its momentum`() {
        val shots = shrapnelOf(shell, Random(3))
        assertEquals(cfg.SHRAPNEL_COUNT, shots.size)
        assertTrue(shots.all { it.kind == ProjectileKind.FLAK_SHRAPNEL })
        val mean = shots.map { it.velocity }.reduce(Vec2::plus) * (1f / shots.size)
        assertEquals(shell.velocity.x, mean.x, 1e-3f)
        assertEquals(shell.velocity.y, mean.y, 1e-3f)
        assertTrue(shots.all { abs((it.velocity - shell.velocity).length - cfg.SHRAPNEL_SPEED) < 1e-3f })
    }

    @Test
    fun `enemy shrapnel flies at its own speed`() {
        val enemyShell = newProjectile(EntityId(3), ProjectileKind.ENEMY_FLAK_SHELL, Vec2(3f, 8f), Vec2(0f, -6f))
        val shots = shrapnelOf(enemyShell, Random(5))
        assertEquals(cfg.SHRAPNEL_COUNT, shots.size)
        assertTrue(shots.all { it.kind == ProjectileKind.ENEMY_FLAK_SHRAPNEL })
        assertTrue(shots.all { abs((it.velocity - enemyShell.velocity).length - cfg.ENEMY_SHRAPNEL_SPEED) < 1e-3f })
    }

    @Test
    fun `shrapnel speed comes from the split of the shell`() {
        assertEquals(cfg.SHRAPNEL_SPEED, ProjectileKind.FLAK_SHELL.stats.split?.speed)
        assertEquals(cfg.ENEMY_SHRAPNEL_SPEED, ProjectileKind.ENEMY_FLAK_SHELL.stats.split?.speed)
    }

    @Test
    fun `enemy shell differs from the player's only in shrapnel`() {
        val player = ProjectileKind.FLAK_SHELL.stats
        val enemy = ProjectileKind.ENEMY_FLAK_SHELL.stats
        assertEquals(player.copy(split = null), enemy.copy(split = null))
        assertEquals(player.split?.fuse, enemy.split?.fuse)
        assertEquals(player.split?.count, enemy.split?.count)
    }

    @Test
    fun `split rejects negative shrapnel speed`() {
        assertFailsWith<IllegalArgumentException> { Split(fuse = 0.1f, into = ProjectileKind.FLAK_SHRAPNEL, count = 4, speed = -1f) }
    }

    @Test
    fun `flak can be bought as a Rebel store`() {
        assertTrue(ShipType.Rebel.stats.stores.any { it.type == PowerUpType.WeaponPickup(WeaponType.FLAK) })
    }
}

class ClashTest {
    private val dt = 1f / 60f
    private val stats = ProjectileKind.BULLET.stats

    @Test
    fun `stronger survives, equal break both, zero against zero ignore each other`() {
        assertEquals(Clash.SECOND_BREAKS, clash(stats.copy(breakPower = 2f), stats.copy(breakPower = 1f)))
        assertEquals(Clash.FIRST_BREAKS, clash(stats.copy(breakPower = 1f), stats.copy(breakPower = 2f)))
        assertEquals(Clash.BOTH_BREAK, clash(stats.copy(breakPower = 1f), stats.copy(breakPower = 1f)))
        assertEquals(Clash.NONE, clash(stats.copy(breakPower = 0f), stats.copy(breakPower = 0f)))
        assertEquals(Clash.SECOND_BREAKS, clash(stats.copy(breakPower = 1f), stats.copy(breakPower = 0f)))
    }

    @Test
    fun `head-on bullets touch within a step even when they would jump over each other`() {
        val bullet = newProjectile(EntityId(1), ProjectileKind.BULLET, Vec2(3f, 5f), Vec2(0f, 16f))
        val bolt = newProjectile(EntityId(2), ProjectileKind.BOSS_BOLT, Vec2(3f, 5.3f), Vec2(0f, -8f))
        // За шаг они сближаются на 0.4 м, а касаются на 0.14 м: на концах шага касания нет, посередине — есть.
        assertTrue(touchWithin(bullet, bolt, dt))
        val aside = bolt.moved(Vec2(3.5f, 5.3f), bolt.velocity) as com.spaceace.game.model.Projectile
        assertFalse(touchWithin(bullet, aside, dt))
        val behind = bolt.moved(Vec2(3f, 4f), Vec2(0f, -8f)) as com.spaceace.game.model.Projectile
        assertFalse(touchWithin(bullet, behind, dt), "already passed each other")
    }

    @Test
    fun `configured projectiles of different sides fly through each other`() {
        // Сейчас у всех снарядов сила сбивания 0: снаряды игрока и врагов друг друга не сбивают.
        val (player, enemy) = ProjectileKind.entries.partition { it.faction == Faction.PLAYER }
        for (mine in player) for (theirs in enemy.filter { it.faction == Faction.ENEMY }) {
            assertEquals(Clash.NONE, clash(mine.stats, theirs.stats), "$mine vs $theirs")
        }
    }
}
