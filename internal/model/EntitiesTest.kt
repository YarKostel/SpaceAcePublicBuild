package com.spaceace.game.model

import com.spaceace.game.Config
import com.spaceace.game.content.Shard
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EntityTest {
    private val eps = 1e-5f
    private val id = EntityId(1)

    @Test
    fun `mass matches Box2D circle mass`() {
        val meteor = newMeteor(id, MeteorSize.LARGE, Vec2.ZERO, Vec2.ZERO)
        val stats = Config.Meteor.LARGE
        val radius = (stats.hitbox as Hitbox.Circle).radius
        assertEquals((PI * radius * radius * stats.density).toFloat(), meteor.mass, eps)
        assertEquals(radius, meteor.radius)
    }

    @Test
    fun `moved keeps type, id and health`() {
        val meteor = newMeteor(id, MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO).withHealth(Health(3f, 10f))
        val moved: Entity = meteor.moved(Vec2(1f, 2f), Vec2(0f, -1f))
        val asMeteor = assertIs<Meteor>(moved)
        assertEquals(id, asMeteor.id)
        assertEquals(Vec2(1f, 2f), asMeteor.position)
        assertEquals(3f, asMeteor.health.current)
    }

    @Test
    fun `new entities start with full health from config`() {
        assertEquals(ShipType.Rebel.stats.maxHealth, newPlayer(id).health.current)
        assertEquals(ShipType.Glory.stats.maxHealth, newPlayer(id, ShipType.Glory).health.current)
        val boss = newNpc(id, Shard, Vec2.ZERO)
        assertEquals(Shard.body.maxHealth, boss.health.current)
        assertEquals(Shard.body.armor, boss.armor)
        assertEquals(Shard.body.hitbox.area * Shard.body.density, boss.mass)
    }

    @Test
    fun `projectile bounces down to zero and expires by age`() {
        val orb = newProjectile(id, ProjectileKind.RICOCHET_ORB, Vec2.ZERO, Vec2.ZERO)
        assertEquals(Config.Projectile.RICOCHET_ORB.maxBounces, orb.bouncesLeft)
        var bounced = orb
        repeat(orb.bouncesLeft) { bounced = bounced.bounced() }
        assertEquals(0, bounced.bouncesLeft)
        assertFailsWith<IllegalArgumentException> { bounced.bounced() }
        assertTrue(orb.aged(Config.Projectile.RICOCHET_ORB.lifetime).isExpired)
    }

    @Test
    fun `projectile faction comes from kind`() {
        assertEquals(Faction.PLAYER, newProjectile(id, ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO).faction)
        assertEquals(Faction.ENEMY, newProjectile(id, ProjectileKind.BOSS_BOLT, Vec2.ZERO, Vec2.ZERO).faction)
        assertEquals(Faction.ENEMY, newProjectile(id, ProjectileKind.ENEMY_BOLT, Vec2.ZERO, Vec2.ZERO).faction)
    }

    @Test
    fun `power-up falls down and weapon pickups do not stack`() {
        val powerUp = newPowerUp(id, PowerUpType.WeaponPickup(WeaponType.GAUSS), Vec2.ZERO)
        assertTrue(powerUp.velocity.y < 0f)
        assertEquals(false, powerUp.type.stackable)
    }
}

class HealthTest {
    @Test
    fun `damage reduces health and clamps at zero`() {
        val health = Health.full(10f)
        assertEquals(7f, health.damaged(3f).current)
        val dead = health.damaged(25f)
        assertEquals(0f, dead.current)
        assertTrue(dead.isDepleted)
    }

    @Test
    fun `heal clamps at max`() {
        val health = Health(4f, 10f)
        assertEquals(10f, health.healed(100f).current)
        assertFalse(health.isDepleted)
    }

    @Test
    fun `negative amounts are rejected`() {
        val health = Health.full(10f)
        assertFailsWith<IllegalArgumentException> { health.damaged(-1f) }
        assertFailsWith<IllegalArgumentException> { health.healed(-1f) }
    }

    @Test
    fun `invalid health cannot be created`() {
        assertFailsWith<IllegalArgumentException> { Health(11f, 10f) }
        assertFailsWith<IllegalArgumentException> { Health(-1f, 10f) }
        assertFailsWith<IllegalArgumentException> { Health(0f, 0f) }
    }
}
