package com.spaceace.game.physics

import com.spaceace.game.content.Borer
import com.spaceace.game.content.Gravel
import com.spaceace.game.content.Shard
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Field
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newNpc
import com.spaceace.game.model.newPlayer
import com.spaceace.game.model.newPowerUp
import com.spaceace.game.model.newProjectile
import com.spaceace.game.physics.CollisionGroup.ALLY
import com.spaceace.game.physics.CollisionGroup.BOSS
import com.spaceace.game.physics.CollisionGroup.CEILING
import com.spaceace.game.physics.CollisionGroup.ENEMY
import com.spaceace.game.physics.CollisionGroup.ENEMY_ENTERING
import com.spaceace.game.physics.CollisionGroup.ENEMY_HEAVY_PROJECTILE
import com.spaceace.game.physics.CollisionGroup.ENEMY_PROJECTILE
import com.spaceace.game.physics.CollisionGroup.FLOOR
import com.spaceace.game.physics.CollisionGroup.METEOR
import com.spaceace.game.physics.CollisionGroup.METEOR_ENTERING
import com.spaceace.game.physics.CollisionGroup.PLAYER
import com.spaceace.game.physics.CollisionGroup.PLAYER_PROJECTILE
import com.spaceace.game.physics.CollisionGroup.POWER_UP
import com.spaceace.game.physics.CollisionGroup.WALL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldBoundsTest {
    private val field = Field(7.2f, 12.8f)
    private val margin = 4f
    private val id = EntityId(1)

    private fun left(entity: com.spaceace.game.model.Entity) = leftField(entity, field, margin)

    @Test
    fun `meteor in the spawn zone stays, below or far above the field leaves`() {
        assertFalse(left(newMeteor(id, MeteorSize.SMALL, Vec2(3f, 14f), Vec2.ZERO)))
        assertTrue(left(newMeteor(id, MeteorSize.SMALL, Vec2(3f, 20f), Vec2.ZERO)))
        assertTrue(left(newMeteor(id, MeteorSize.SMALL, Vec2(3f, -1f), Vec2.ZERO)))
        assertFalse(left(newMeteor(id, MeteorSize.SMALL, Vec2(3f, -0.1f), Vec2.ZERO)))
    }

    @Test
    fun `projectile leaves through any edge`() {
        fun at(x: Float, y: Float) = newProjectile(id, ProjectileKind.BULLET, Vec2(x, y), Vec2.ZERO)
        assertFalse(left(at(3f, 5f)))
        assertTrue(left(at(3f, 13f)))
        assertTrue(left(at(3f, -1f)))
        assertTrue(left(at(-1f, 5f)))
        assertTrue(left(at(8f, 5f)))
    }

    @Test
    fun `player and boss never leave`() {
        assertFalse(left(newPlayer(id).moved(Vec2(3f, -5f), Vec2.ZERO)))
        assertFalse(left(newNpc(id, Shard, Vec2(3f, 50f))))
        assertFalse(left(newNpc(id, Shard, Vec2(3f, -50f))))
    }

    @Test
    fun `mobs leave below the field`() {
        assertFalse(left(newNpc(id, Gravel, Vec2(3f, 5f))))
        assertTrue(left(newNpc(id, Gravel, Vec2(3f, -2f))))
    }
}

class MaterialTest {
    private val id = EntityId(1)
    private val samples = listOf(
        newPlayer(id),
        newPlayer(id, ShipType.Glory),
        newNpc(id, Gravel, Vec2.ZERO),
        newNpc(id, Borer, Vec2.ZERO),
        newMeteor(id, MeteorSize.MEDIUM, Vec2.ZERO, Vec2.ZERO),
        newNpc(id, Shard, Vec2.ZERO),
        newProjectile(id, ProjectileKind.RICOCHET_ORB, Vec2.ZERO, Vec2.ZERO),
        newPowerUp(id, PowerUpType.HeavyRounds, Vec2.ZERO),
    )

    @Test
    fun `entity mass matches Box2D mass from material density`() {
        for (entity in samples) {
            assertEquals(entity.hitbox.area * materialOf(entity).density, entity.mass, 1e-6f, "$entity")
        }
    }

    @Test
    fun `projectile restitution comes from its type`() {
        assertEquals(1f, materialOf(newProjectile(id, ProjectileKind.RICOCHET_ORB, Vec2.ZERO, Vec2.ZERO)).restitution)
        assertEquals(0f, materialOf(newProjectile(id, ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO)).restitution)
    }

    @Test
    fun `only piercing projectiles and power-ups are sensors`() {
        assertTrue(materialOf(newProjectile(id, ProjectileKind.GAUSS_SLUG, Vec2.ZERO, Vec2.ZERO)).sensor)
        assertTrue(materialOf(newPowerUp(id, PowerUpType.Shield, Vec2.ZERO)).sensor)
        assertFalse(materialOf(newProjectile(id, ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO)).sensor)
        assertFalse(materialOf(newMeteor(id, MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO)).sensor)
    }

    @Test
    fun `meteors are elastic by default`() {
        for (size in MeteorSize.entries) {
            assertEquals(1f, materialOf(newMeteor(id, size, Vec2.ZERO, Vec2.ZERO)).restitution)
        }
    }
}

class CollisionFilterTest {
    /** Box2D: контакт есть, только если категория каждого входит в маску другого. */
    private fun box2dCollides(a: CollisionGroup, b: CollisionGroup): Boolean {
        val fa = filterFor(a)
        val fb = filterFor(b)
        return (fa.mask.toInt() and fb.category.toInt()) != 0 && (fb.mask.toInt() and fa.category.toInt()) != 0
    }

    @Test
    fun `masks agree with the pair table for every pair`() {
        for (a in CollisionGroup.entries) for (b in CollisionGroup.entries) {
            assertEquals(collides(a, b), box2dCollides(a, b), "$a vs $b")
        }
    }

    @Test
    fun `categories are distinct bits`() {
        val bits = CollisionGroup.entries.map { it.bit.toInt() }
        assertEquals(bits.size, bits.toSet().size)
        assertTrue(bits.all { Integer.bitCount(it) == 1 })
    }

    @Test
    fun `rules from the design`() {
        assertTrue(box2dCollides(METEOR, METEOR))
        assertTrue(box2dCollides(PLAYER, FLOOR))
        for (other in CollisionGroup.entries - PLAYER) assertFalse(box2dCollides(FLOOR, other), "floor vs $other")
        assertFalse(box2dCollides(PLAYER_PROJECTILE, ENEMY_PROJECTILE))
        assertTrue(box2dCollides(ENEMY, PLAYER))
        assertTrue(box2dCollides(ENEMY, METEOR))
        assertTrue(box2dCollides(ENEMY, PLAYER_PROJECTILE))
        assertFalse(box2dCollides(ENEMY, ENEMY_PROJECTILE))
        assertFalse(box2dCollides(ENEMY, BOSS))
        assertTrue(box2dCollides(METEOR, BOSS))
        assertTrue(box2dCollides(POWER_UP, PLAYER))
        for (other in CollisionGroup.entries - PLAYER) assertFalse(box2dCollides(POWER_UP, other), "power-up vs $other")
        assertTrue(box2dCollides(PLAYER_PROJECTILE, CEILING))
        assertFalse(box2dCollides(METEOR, CEILING))
        assertTrue(box2dCollides(PLAYER, WALL))
        assertFalse(box2dCollides(PLAYER, PLAYER_PROJECTILE))
    }

    @Test
    fun `entering meteor ignores only the walls`() {
        assertFalse(box2dCollides(METEOR_ENTERING, WALL))
        for (other in CollisionGroup.entries - METEOR_ENTERING - WALL) {
            assertEquals(box2dCollides(METEOR, other), box2dCollides(METEOR_ENTERING, other), "entering vs $other")
        }
        assertTrue(box2dCollides(METEOR_ENTERING, METEOR))
        assertTrue(box2dCollides(METEOR_ENTERING, METEOR_ENTERING))
        val id = EntityId(1)
        assertEquals(METEOR_ENTERING, collisionGroupOf(newMeteor(id, MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO, entering = true)))
        assertEquals(METEOR, collisionGroupOf(newMeteor(id, MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO)))
    }

    @Test
    fun `entering enemy ignores only the walls`() {
        assertFalse(box2dCollides(ENEMY_ENTERING, WALL))
        for (other in CollisionGroup.entries - ENEMY_ENTERING - WALL) {
            assertEquals(box2dCollides(ENEMY, other), box2dCollides(ENEMY_ENTERING, other), "entering vs $other")
        }
        assertTrue(box2dCollides(ENEMY_ENTERING, ENEMY_ENTERING))
        val id = EntityId(1)
        val ambusher = com.spaceace.game.model.newNpc(id, com.spaceace.game.content.Gravel, Vec2.ZERO, entry = com.spaceace.game.model.Entry(Vec2(1f, 0f), 3f))
        assertEquals(ENEMY_ENTERING, collisionGroupOf(ambusher))
        assertEquals(ENEMY, collisionGroupOf(ambusher.arrived()))
    }

    @Test
    fun `allies shield the player but do not get in the way`() {
        assertTrue(box2dCollides(ALLY, ENEMY_PROJECTILE))
        assertTrue(box2dCollides(ALLY, ENEMY_HEAVY_PROJECTILE))
        assertTrue(box2dCollides(ALLY, ENEMY))
        assertTrue(box2dCollides(ALLY, ENEMY_ENTERING))
        assertTrue(box2dCollides(ALLY, METEOR))
        assertTrue(box2dCollides(ALLY, WALL))
        assertTrue(box2dCollides(ALLY, ALLY))
        assertFalse(box2dCollides(ALLY, PLAYER))
        assertFalse(box2dCollides(ALLY, PLAYER_PROJECTILE))
        assertFalse(box2dCollides(ALLY, POWER_UP))
        val drone = com.spaceace.game.model.newSummon(
            EntityId(1), com.spaceace.game.content.PlayerSummons.CALL, EntityId(2), com.spaceace.game.model.Faction.PLAYER, Vec2.ZERO,
        )
        assertEquals(ALLY, collisionGroupOf(drone))
    }

    @Test
    fun `heavy enemy projectiles also hit meteors`() {
        assertTrue(box2dCollides(ENEMY_HEAVY_PROJECTILE, PLAYER))
        assertTrue(box2dCollides(ENEMY_HEAVY_PROJECTILE, METEOR))
        assertFalse(box2dCollides(ENEMY_PROJECTILE, METEOR))
        assertFalse(box2dCollides(ENEMY_HEAVY_PROJECTILE, BOSS))
        val id = EntityId(1)
        assertEquals(ENEMY_HEAVY_PROJECTILE, collisionGroupOf(newProjectile(id, ProjectileKind.ENEMY_GAUSS_SLUG, Vec2.ZERO, Vec2.ZERO)))
    }

    @Test
    fun `projectile group depends on faction`() {
        val id = EntityId(1)
        assertEquals(PLAYER_PROJECTILE, collisionGroupOf(newProjectile(id, ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO)))
        assertEquals(ENEMY_PROJECTILE, collisionGroupOf(newProjectile(id, ProjectileKind.BOSS_BOLT, Vec2.ZERO, Vec2.ZERO)))
        assertEquals(ENEMY_PROJECTILE, collisionGroupOf(newProjectile(id, ProjectileKind.ENEMY_BOLT, Vec2.ZERO, Vec2.ZERO)))
    }
}
