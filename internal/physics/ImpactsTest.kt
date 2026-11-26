package com.spaceace.game.physics

import com.spaceace.game.model.Entity
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Health
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Player
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newPlayer
import com.spaceace.game.model.newPowerUp
import com.spaceace.game.model.newProjectile
import com.spaceace.game.weapon.PierceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DamageTest {
    private val eps = 1e-5f
    private val settings = DamageSettings(coefficient = 2f, energyThreshold = 1f)

    @Test
    fun `reduced mass of equal masses is half`() {
        assertEquals(1.5f, reducedMass(3f, 3f), eps)
    }

    @Test
    fun `reduced mass approaches smaller mass when other is huge`() {
        assertEquals(1f, reducedMass(1f, 1e6f), 1e-4f)
    }

    @Test
    fun `reduced mass rejects non-positive masses`() {
        assertFailsWith<IllegalArgumentException> { reducedMass(0f, 1f) }
    }

    @Test
    fun `energy is mu v squared over two and symmetric`() {
        // μ = 2·3/5 = 1.2; |v|² = 3² + 4² = 25 → E = 15.
        val energy = impactEnergy(2f, 3f, Vec2(3f, 4f))
        assertEquals(15f, energy, eps)
        assertEquals(energy, impactEnergy(3f, 2f, Vec2(-3f, -4f)), eps)
    }

    @Test
    fun `no damage below threshold`() {
        assertEquals(0f, impactDamage(0.99f, 1f, settings))
    }

    @Test
    fun `damage is K times energy times armor at and above threshold`() {
        assertEquals(2f, impactDamage(1f, 1f, settings), eps)
        assertEquals(10f, impactDamage(10f, 0.5f, settings), eps)
    }

    @Test
    fun `projectile restitution overrides pair maximum`() {
        assertEquals(0f, mixRestitution(1f, 0f, projectileRestitution = 0f))
        assertEquals(1f, mixRestitution(1f, 0.3f, projectileRestitution = null))
    }
}

class ImpactResolverTest {
    private val eps = 1e-4f
    private val damage = DamageSettings(coefficient = 1f, energyThreshold = 1f)
    private val pierce = PierceSettings(momentumTransfer = 0.5f, speedRetention = 0.5f, minSpeed = 4f)
    private val resolver = ImpactResolver(damage, pierce)

    private val meteor = newMeteor(EntityId(1), MeteorSize.LARGE, Vec2.ZERO, Vec2.ZERO)
    private val otherMeteor = newMeteor(EntityId(2), MeteorSize.LARGE, Vec2.ZERO, Vec2.ZERO)
    private val bullet = newProjectile(EntityId(10), ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO)
    private val gauss = newProjectile(EntityId(11), ProjectileKind.GAUSS_SLUG, Vec2.ZERO, Vec2.ZERO)
    private val orb = newProjectile(EntityId(12), ProjectileKind.RICOCHET_ORB, Vec2.ZERO, Vec2.ZERO)
    private val player = newPlayer(EntityId(20))
    private val powerUp = newPowerUp(EntityId(30), PowerUpType.Shield, Vec2.ZERO)

    private fun lookupOf(vararg entities: Entity): (EntityId) -> Entity? {
        val byId = entities.associateBy { it.id }
        return { byId[it] }
    }

    private fun hit(a: Entity, b: Entity?, va: Vec2, vb: Vec2 = Vec2.ZERO) = ImpactEvent(a.id, b?.id, va, vb)

    @Test
    fun `bullet damages meteor by energy formula and disappears`() {
        val v = Vec2(0f, 15f)
        val outcome = resolver.resolve(listOf(hit(bullet, meteor, v)), lookupOf(bullet, meteor))
        val expected = impactDamage(impactEnergy(bullet.mass, meteor.mass, v), meteor.armor, damage)
        val hurt = assertIs<Meteor>(outcome.updated.single())
        assertEquals(meteor.health.current - expected, hurt.health.current, eps)
        assertTrue(bullet.id in outcome.removed)
    }

    @Test
    fun `weak impact deals no damage`() {
        val outcome = resolver.resolve(listOf(hit(meteor, otherMeteor, Vec2(0f, 0.1f))), lookupOf(meteor, otherMeteor))
        assertTrue(outcome.updated.isEmpty())
        assertTrue(outcome.removed.isEmpty())
    }

    @Test
    fun `both damageable bodies take damage`() {
        val outcome = resolver.resolve(listOf(hit(meteor, otherMeteor, Vec2(0f, 5f), Vec2(0f, -5f))), lookupOf(meteor, otherMeteor))
        assertEquals(2, outcome.updated.size)
        assertTrue(outcome.updated.all { (it as Meteor).health.current < meteor.health.current })
    }

    @Test
    fun `killed meteor is removed and reported as destroyed`() {
        val weak = meteor.withHealth(Health(0.5f, meteor.health.max))
        val outcome = resolver.resolve(listOf(hit(bullet, weak, Vec2(0f, 15f))), lookupOf(bullet, weak))
        assertTrue(weak.id in outcome.removed)
        assertEquals(weak.id, outcome.destroyed.single().id)
        assertFalse(outcome.playerDestroyed)
    }

    @Test
    fun `killed player is reported but not removed`() {
        val dying = player.withHealth(Health(0.5f, player.health.max))
        val outcome = resolver.resolve(listOf(hit(dying, meteor, Vec2(0f, 5f), Vec2(0f, -5f))), lookupOf(dying, meteor))
        assertTrue(outcome.playerDestroyed)
        assertFalse(dying.id in outcome.removed)
        assertTrue(assertIs<Player>(outcome.updated.first { it.id == dying.id }).health.isDepleted)
    }

    @Test
    fun `projectile already removed in this step does not hit twice`() {
        val v = Vec2(0f, 15f)
        val events = listOf(hit(bullet, meteor, v), hit(bullet, otherMeteor, v))
        val outcome = resolver.resolve(events, lookupOf(bullet, meteor, otherMeteor))
        assertEquals(listOf(meteor.id), outcome.updated.map { it.id })
    }

    @Test
    fun `ricochet orb spends bounces on walls and dies after the last one`() {
        var current: Projectile = orb
        repeat(orb.bouncesLeft) {
            val outcome = resolver.resolve(listOf(hit(current, null, Vec2(5f, 5f))), lookupOf(current))
            assertTrue(outcome.removed.isEmpty())
            current = assertIs<Projectile>(outcome.updated.single())
        }
        assertEquals(0, current.bouncesLeft)
        val last = resolver.resolve(listOf(hit(current, null, Vec2(5f, 5f))), lookupOf(current))
        assertTrue(current.id in last.removed)
    }

    @Test
    fun `ricochet orb damages meteor and keeps flying`() {
        val outcome = resolver.resolve(listOf(hit(orb, meteor, Vec2(0f, 10f))), lookupOf(orb, meteor))
        assertFalse(orb.id in outcome.removed)
        assertEquals(orb.bouncesLeft - 1, outcome.updated.filterIsInstance<Projectile>().single().bouncesLeft)
        assertTrue(outcome.updated.any { it is Meteor })
    }

    @Test
    fun `gauss slug pierces, pushes the meteor and slows down`() {
        val v = Vec2(0f, 20f)
        // Живучий метеорит: на 20 м/с слаг убивает обычный крупный, и импульс мёртвому не нужен.
        val tough = meteor.withHealth(Health.full(1000f))
        val outcome = resolver.resolve(listOf(hit(gauss, tough, v)), lookupOf(gauss, tough))
        assertFalse(gauss.id in outcome.removed)
        assertEquals(Vec2(0f, 10f), outcome.velocityOverrides[gauss.id])
        val impulse = assertNotNull(outcome.impulses[tough.id])
        assertEquals(gauss.mass * 20f * pierce.momentumTransfer, impulse.y, eps)
    }

    @Test
    fun `gauss slug stops when too slow after piercing`() {
        val outcome = resolver.resolve(listOf(hit(gauss, meteor, Vec2(0f, 6f))), lookupOf(gauss, meteor))
        assertTrue(gauss.id in outcome.removed)
    }

    @Test
    fun `gauss slug uses slowed velocity for the next target in the same step`() {
        val events = listOf(hit(gauss, meteor, Vec2(0f, 20f)), hit(gauss, otherMeteor, Vec2(0f, 20f)))
        val outcome = resolver.resolve(events, lookupOf(gauss, meteor, otherMeteor))
        assertEquals(Vec2(0f, 5f), outcome.velocityOverrides[gauss.id])
        assertEquals(gauss.mass * 10f * pierce.momentumTransfer, outcome.impulses.getValue(otherMeteor.id).y, eps)
    }

    @Test
    fun `player touching power-up records pickup without damage`() {
        val outcome = resolver.resolve(listOf(hit(powerUp, player, Vec2(0f, -20f))), lookupOf(powerUp, player))
        assertEquals(listOf(Pickup(player.id, powerUp.id)), outcome.pickups)
        assertTrue(outcome.updated.isEmpty())
    }

    @Test
    fun `events with unknown entities are ignored`() {
        val outcome = resolver.resolve(listOf(hit(bullet, meteor, Vec2(0f, 15f))), lookupOf(bullet))
        assertTrue(outcome.updated.isEmpty() && outcome.removed.isEmpty())
    }

    @Test
    fun `shield absorbs weak enemy shots and lets strong ones fly through, without damage either way`() {
        val shielded = player.withShield(true)
        val bolt = newProjectile(EntityId(40), ProjectileKind.BOSS_BOLT, Vec2.ZERO, Vec2.ZERO)
        val absorbed = resolver.resolve(listOf(hit(bolt, shielded, Vec2(0f, -20f))), lookupOf(bolt, shielded))
        assertTrue(bolt.id in absorbed.removed)
        assertTrue(absorbed.updated.isEmpty(), "no damage")

        // У настроенных снарядов врагов сила сбивания 0 — пролёт насквозь проверяется на щите слабее любого снаряда.
        val weakShield = ImpactResolver(damage, pierce, shieldPower = ProjectileKind.ENEMY_GAUSS_SLUG.stats.breakPower - 1f)
        val slug = newProjectile(EntityId(41), ProjectileKind.ENEMY_GAUSS_SLUG, Vec2.ZERO, Vec2.ZERO)
        val through = weakShield.resolve(listOf(hit(slug, shielded, Vec2(0f, -22f))), lookupOf(slug, shielded))
        assertFalse(slug.id in through.removed)
        assertTrue(through.updated.isEmpty() && through.impulses.isEmpty() && through.velocityOverrides.isEmpty())
    }
}
