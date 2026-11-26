package com.spaceace.game.hazard

import com.spaceace.game.content.Borer
import com.spaceace.game.content.Gravel
import com.spaceace.game.content.PlayerSummons
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Faction
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.WeaponType
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newNpc
import com.spaceace.game.model.newPlayer
import com.spaceace.game.model.newProjectile
import com.spaceace.game.model.newSummon
import com.spaceace.game.physics.DamageSettings
import com.spaceace.game.state.GameState
import com.spaceace.game.weapon.FireMode
import com.spaceace.game.weapon.Trigger
import com.spaceace.game.weapon.Weapon
import com.spaceace.game.weapon.chargeFraction
import com.spaceace.game.weapon.update
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
private val UP = Vec2(0f, 1f)
private val DOWN = Vec2(0f, -1f)

class BeamGeometryTest {
    private val eps = 1e-3f

    @Test
    fun `ray meets a circle at its near edge, misses aside, starts at zero inside`() {
        val meteor = newMeteor(EntityId(1), MeteorSize.SMALL, Vec2(0f, 5f), Vec2.ZERO)
        val r = meteor.hitbox.boundingRadius
        assertEquals(5f - r, assertNotNull(rayHit(Vec2.ZERO, UP, 10f, meteor)), eps)
        assertNull(rayHit(Vec2(2f, 0f), UP, 10f, meteor))
        assertNull(rayHit(Vec2.ZERO, UP, 3f, meteor), "too short")
        assertNull(rayHit(Vec2.ZERO, DOWN, 10f, meteor), "behind the origin")
        assertEquals(0f, rayHit(Vec2(0f, 5f), UP, 10f, meteor))
    }

    @Test
    fun `ray meets a polygon turned with its hull`() {
        // Нос Borer смотрит вниз (угол π): снизу первой встречается вершина-нос, ниже центра на 2/3 высоты.
        val borer = newNpc(EntityId(1), Borer, Vec2(0f, 5f))
        val noseBelowCenter = Borer.body.hitbox.top
        assertEquals(5f - noseBelowCenter, assertNotNull(rayHit(Vec2(0f, 0f), UP, 10f, borer)), eps)
        val turned = borer.turned((PI / 2).toFloat()) // нос влево: снизу встречается сторона основания
        val hit = assertNotNull(rayHit(Vec2(0f, 0f), UP, 10f, turned))
        assertTrue(hit > 5f - noseBelowCenter + eps, "base side is closer to the center than the nose: $hit")
    }

    @Test
    fun `beam stops at the first body of the other side or a meteor`() {
        val spec = BeamSpec(power = 10f, length = 12f, width = 0.1f)
        val enemy = newNpc(EntityId(1), Gravel, Vec2(3f, 6f))
        val meteor = newMeteor(EntityId(2), MeteorSize.SMALL, Vec2(3f, 4f), Vec2.ZERO)
        val ally = newSummon(EntityId(3), PlayerSummons.CALL, EntityId(9), Faction.PLAYER, Vec2(3f, 3f))
        val bolt = newProjectile(EntityId(4), ProjectileKind.BOSS_BOLT, Vec2(3f, 2.5f), Vec2.ZERO)
        val playerShot = BeamShot(EntityId(9), Faction.PLAYER, Vec2(3f, 1f), UP, spec, live = true)

        assertEquals(meteor.id, castBeam(playerShot, listOf(enemy, meteor, ally, bolt)).target, "allies and shots let it through")
        assertEquals(enemy.id, castBeam(playerShot, listOf(enemy, ally)).target)
        val clear = castBeam(playerShot, listOf(ally))
        assertNull(clear.target)
        assertEquals(13f, clear.end.y, 1e-3f)

        val enemyShot = BeamShot(enemy.id, Faction.ENEMY, Vec2(3f, 8f), DOWN, spec, live = true)
        val other = newNpc(EntityId(5), Gravel, Vec2(3f, 7f))
        assertEquals(meteor.id, castBeam(enemyShot, listOf(other, meteor, ally)).target, "own side does not block")
        assertEquals(ally.id, castBeam(enemyShot, listOf(other, ally)).target, "the drone covers the player")
    }

    @Test
    fun `damage is power times time times armor, shield nullifies it`() {
        val settings = DamageSettings(coefficient = 1f, energyThreshold = 100f)
        val player = newPlayer(EntityId(1))
        assertEquals(10f * 0.5f * player.armor, hazardDamage(10f * 0.5f, player, settings), 1e-4f)
        assertEquals(0f, hazardDamage(10f, player.withShield(true), settings))
    }

    @Test
    fun `zones touch by overlap and affect only the configured sides`() {
        val zone = Zone(Vec2(3f, 3f), ZoneSpec(radius = 1f, power = 5f, lifetime = 3f, affects = setOf(Faction.PLAYER)), Faction.ENEMY)
        val player = newPlayer(EntityId(1)).moved(Vec2(3f, 4.2f), Vec2.ZERO)
        val far = newPlayer(EntityId(2)).moved(Vec2(3f, 8f), Vec2.ZERO)
        val enemy = newNpc(EntityId(3), Gravel, Vec2(3f, 3f))
        val meteor = newMeteor(EntityId(4), MeteorSize.SMALL, Vec2(3f, 3f), Vec2.ZERO)
        assertTrue(zoneTouches(zone, player) && zoneAffects(zone, player))
        assertFalse(zoneTouches(zone, far))
        assertFalse(zoneAffects(zone, enemy))
        assertFalse(zoneAffects(zone, meteor), "meteors have no side")
    }
}

class LaserTriggerTest {
    private val dt = 1f / 60f
    private val mode = FireMode.Beam(heatTime = 1f, coolTime = 2f)

    @Test
    fun `beam burns while held, overheats and stays off until fully cooled`() {
        var trigger = Trigger()
        var firing = 0
        repeat(90) {
            val result = trigger.update(mode, held = true, dt = dt)
            trigger = result.trigger
            if (result.power != null) firing++
        }
        assertTrue(firing in 59..61, "about one second of fire: $firing")
        assertTrue(trigger.overheated)
        assertEquals(trigger.heat, trigger.chargeFraction(mode))

        repeat(20) { trigger = trigger.update(mode, held = true, dt = dt).trigger }
        assertTrue(trigger.overheated, "still locked while cooling")
        repeat(100) { trigger = trigger.update(mode, held = false, dt = dt).trigger }
        assertFalse(trigger.overheated)
        assertEquals(0f, trigger.heat)
        assertTrue(trigger.update(mode, held = true, dt = dt).power != null)
    }

    @Test
    fun `laser is a beam weapon without projectiles`() {
        val laser = Weapon.of(WeaponType.LASER)
        assertIs<FireMode.Beam>(laser.mode)
        assertNotNull(laser.beam)
        assertTrue(laser.shots(1f, com.spaceace.game.weapon.Aim(Vec2.ZERO, emptyList()), kotlin.random.Random(1)).isEmpty())
    }
}

class HazardSystemTest {
    private val dt = 1f / 60f
    private val settings = DamageSettings(coefficient = 1f, energyThreshold = 0.5f)

    @Test
    fun `live enemy beam burns the player, a warning beam does not`() {
        val state = GameState()
        state.startRun()
        val player = state.player!!
        val system = HazardSystem(state, settings)
        val spec = BeamSpec(power = 30f, length = 12f, width = 0.1f)
        state.hazards.fire(BeamShot(EntityId(99), Faction.ENEMY, player.position + Vec2(0f, 4f), DOWN, spec, live = false))
        system.step(dt)
        assertEquals(player.health.current, state.player!!.health.current)
        state.hazards.fire(BeamShot(EntityId(99), Faction.ENEMY, player.position + Vec2(0f, 4f), DOWN, spec, live = true))
        system.step(dt)
        assertEquals(player.health.current - 30f * dt * player.armor, state.player!!.health.current, 1e-3f)
        assertEquals(player.id, state.hazards.beams.single().target)
    }

    @Test
    fun `player zone destroys enemies inside, expires on time and is cleared with the field`() {
        val state = GameState()
        state.startRun()
        val gravel = state.entities.spawn { newNpc(it, Gravel, Vec2(3f, 8f)) }
        val system = HazardSystem(state, settings)
        state.hazards.place(Zone(Vec2(3f, 8f), ZoneSpec(radius = 0.5f, power = 1000f, lifetime = 0.5f, affects = setOf(Faction.ENEMY)), Faction.PLAYER))
        val destroyed = system.step(dt)
        assertEquals(listOf(gravel.id), destroyed.map { it.id })
        assertFalse(gravel.id in state.entities)
        repeat(40) { system.step(dt) }
        assertTrue(state.hazards.zones.isEmpty(), "lifetime is over")
        assertTrue(state.player!!.health.current == state.player!!.health.max, "player zone does not hurt the player")
    }

    @Test
    fun `zone waits its warmup before hurting`() {
        val state = GameState()
        state.startRun()
        val player = state.player!!
        val system = HazardSystem(state, settings)
        state.hazards.place(Zone(player.position, ZoneSpec(radius = 0.5f, power = 10f, lifetime = 2f, affects = setOf(Faction.PLAYER), warmup = 0.5f), Faction.ENEMY))
        repeat(25) { system.step(dt) }
        assertEquals(player.health.current, state.player!!.health.current)
        repeat(10) { system.step(dt) }
        assertTrue(state.player!!.health.current < player.health.current)
        assertIs<Player>(state.player)
        assertTrue(state.entities.all.none { it is Npc })
    }
}
