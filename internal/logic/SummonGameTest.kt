package com.spaceace.game.logic

import com.badlogic.gdx.physics.box2d.Box2D
import com.spaceace.game.Config
import com.spaceace.game.content.Gravel
import com.spaceace.game.content.PlayerSummons
import com.spaceace.game.input.ControlState
import com.spaceace.game.input.InputSource
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Health
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.Npc
import com.spaceace.game.model.NpcBody
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Role
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.SummonSpec
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newNpc
import com.spaceace.game.model.newSummon
import com.spaceace.game.npc.AttackPlan
import com.spaceace.game.npc.AttackStep
import com.spaceace.game.npc.Behavior
import com.spaceace.game.npc.BehaviorPhase
import com.spaceace.game.npc.Movement
import com.spaceace.game.npc.NpcSystem
import com.spaceace.game.npc.Summon
import com.spaceace.game.npc.Transition
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.Slot
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
/** Призывы на настоящем Box2D: союзники игрока, лимиты, срок жизни, маяк, вражеские саммоны и переход фазы. */
class SummonGameTest {
    private val dt = Config.Physics.TIME_STEP

    @BeforeTest
    fun loadBox2D() = Box2D.init()

    private object Idle : InputSource {
        override fun poll() = ControlState()
    }

    private fun GameState.allies() = entities.ofType<Npc>().filter { it.isAlly }

    /** Только ИИ и физика, без спауна метеоритов и противников — тихое поле. */
    private fun quiet(body: (GameState, NpcSystem, PhysicsSystem) -> Unit) {
        val state = GameState(MetaState(selectedShip = ShipType.Glory))
        state.startRun()
        val physics = PhysicsSystem(state)
        try {
            body(state, NpcSystem(state, physics, Random(1)), physics)
        } finally {
            physics.dispose()
        }
    }

    private fun run(state: GameState, npcs: NpcSystem, physics: PhysicsSystem, seconds: Float) {
        repeat((seconds / dt).toInt()) {
            npcs.step(dt)
            physics.step(dt)
        }
    }

    @Test
    fun `drone escorts the ship and shoots the nearest enemy`() = quiet { state, npcs, physics ->
        assertEquals(1, npcs.summonForPlayer(PlayerSummons.CALL).size)
        state.entities.spawn { newNpc(it, Gravel, Vec2(1f, 9f)) }
        var shots = 0
        repeat((2f / dt).toInt()) {
            npcs.step(dt)
            physics.step(dt)
            shots = maxOf(shots, state.entities.ofType<Projectile>().count { it.kind == ProjectileKind.ALLY_BOLT })
        }
        val drone = state.allies().single()
        assertEquals(Faction.PLAYER, drone.faction)
        assertTrue((drone.position - state.player!!.position).length < 3f, "keeps close to the ship: ${drone.position}")
        assertTrue(shots > 0, "the drone fires")
    }

    @Test
    fun `per-summon limit holds and a call drone expires`() = quiet { state, npcs, physics ->
        repeat(3) { npcs.summonForPlayer(PlayerSummons.CALL) }
        assertEquals(PlayerSummons.CALL.maxAlive, state.allies().size)
        run(state, npcs, physics, PlayerSummons.CALL.lifetime!! + 0.2f)
        assertTrue(state.allies().isEmpty(), "lifetime is over")
    }

    @Test
    fun `total cap limits summons on the field`() = quiet { state, npcs, _ ->
        val many = SummonSpec(Gravel, count = 50, maxAlive = 50)
        npcs.summonForPlayer(many)
        assertEquals(Config.Summon.MAX_TOTAL, state.entities.ofType<Npc>().count { it.summon != null })
    }

    @Test
    fun `boss summons its guards, killing them moves it to the next phase`() = quiet { state, npcs, _ ->
        val guards = SummonSpec(Gravel, count = 2, maxAlive = 2)
        val summoner = object : NpcKind {
            override val id = "summoner"
            override val title = "SUMMONER"
            override val description = emptyList<String>()
            override val role = Role.BOSS
            override val body = NpcBody(Hitbox.Rect(1f, 1f), 1f, 0f, 100f, 1f, physical = false, maxAccel = 1f)
            override val behavior = Behavior(
                listOf(
                    BehaviorPhase(Movement.Descend(0f), AttackPlan.Cycle(listOf(AttackStep(Summon(guards), pause = 0.5f))), exit = Transition.Kills(2, Gravel.id)),
                    BehaviorPhase(Movement.Descend(0f)),
                ),
            )
            override val loot = NpcLoot(0..0)
        }
        val boss = state.entities.spawn { newNpc(it, summoner, Vec2(3.6f, 9f)) }
        repeat(120) { npcs.step(dt) } // несколько попыток призыва — лимит держит двоих
        val summoned = state.entities.ofType<Npc>().filter { it.summon?.owner == boss.id }
        assertEquals(2, summoned.size)
        assertTrue(summoned.all { it.faction == Faction.ENEMY && !it.isBoss })
        for (guard in summoned) {
            state.entities.remove(guard.id)
            npcs.recordKill(guard.kind)
        }
        npcs.step(dt)
        assertEquals(1, (state.entities[boss.id] as Npc).brain.phase)
    }

    @Test
    fun `drone call from the inventory and the drone bay perk summon through the game logic`() {
        val state = GameState(MetaState(selectedShip = ShipType.Glory), Random(1))
        val physics = PhysicsSystem(state)
        try {
            val logic = GameLogic(state, physics, Idle, Random(1))
            state.startRun()
            assertTrue(state.pickUp(PowerUpType.DroneCall))
            val call = state.run.inventory.slots.indexOfFirst { it is Slot.Supply && it.type == PowerUpType.DroneCall }
            assertTrue(state.activateSlot(call))
            logic.step(dt)
            assertEquals(1, state.allies().size)

            assertTrue(state.pickUp(PowerUpType.PerkPickup(com.spaceace.game.model.Perk.DRONE_BAY)))
            val bay = state.run.inventory.slots.indexOfFirst { it is Slot.Gear }
            assertTrue(state.activateSlot(bay))
            assertTrue(!(state.run.inventory.slots[bay] as Slot.Gear).ready, "the bay goes on cooldown")
            logic.step(dt)
            assertEquals(1 + PlayerSummons.BAY.count, state.allies().size)
        } finally {
            physics.dispose()
        }
    }

    @Test
    fun `beacon keeps a drone around and brings it back after a while`() {
        val state = GameState(MetaState(selectedShip = ShipType.Glory), Random(1))
        val physics = PhysicsSystem(state)
        try {
            val logic = GameLogic(state, physics, Idle, Random(1))
            state.startRun()
            assertTrue(state.pickUp(PowerUpType.ItemPickup(PermanentItem.DroneBeacon)))
            logic.step(dt)
            val first = state.allies().single()
            state.entities.remove(first.id)
            val respawnSteps = (Config.Summon.BEACON_RESPAWN / dt).toInt()
            repeat(respawnSteps - 10) {
                state.player?.let { state.entities.replace(it.withHealth(Health.full(it.health.max))) }
                logic.step(dt)
            }
            assertTrue(state.allies().isEmpty(), "waits before respawning")
            repeat(20) { logic.step(dt) }
            assertEquals(1, state.allies().size)
        } finally {
            physics.dispose()
        }
    }

    @Test
    fun `only drones that keep between levels survive the level change`() {
        val state = GameState()
        state.startRun()
        val owner = state.player!!.id
        state.entities.spawn { newSummon(it, PlayerSummons.BEACON, owner, Faction.PLAYER, Vec2(3f, 3f)) }
        state.entities.spawn { newSummon(it, PlayerSummons.CALL, owner, Faction.PLAYER, Vec2(4f, 3f)) }
        repeat(3) { state.advancePhase() } // уровень 1 → босс → магазин → уровень 2
        assertEquals(listOf(PlayerSummons.BEACON), state.allies().map { it.summon?.spec })
    }

    @Test
    fun `player laser burns an enemy above and overheats`() {
        val state = GameState(MetaState(selectedShip = ShipType.Glory), Random(1))
        val physics = PhysicsSystem(state)
        val holdFire = object : InputSource {
            override fun poll() = ControlState(fire = true)
        }
        try {
            val logic = GameLogic(state, physics, holdFire, Random(1))
            state.startRun()
            state.equip(com.spaceace.game.model.WeaponType.LASER)
            val player = state.player!!
            val gravel = state.entities.spawn { newNpc(it, Gravel, Vec2(player.position.x, 9f)) }
            repeat(10) {
                state.player?.let { state.entities.replace(it.withHealth(Health.full(it.health.max))) }
                logic.step(dt)
            }
            val burnt = state.entities[gravel.id] as? Npc
            assertTrue(burnt == null || burnt.health.current < Gravel.body.maxHealth, "the beam hurts the gravel")
            assertTrue(state.hazards.beams.isNotEmpty())

            repeat((Config.Weapons.Laser.HEAT_TIME / dt).toInt() + 5) {
                state.player?.let { state.entities.replace(it.withHealth(Health.full(it.health.max))) }
                logic.step(dt)
            }
            assertTrue(state.run.trigger.overheated)
            assertTrue(state.hazards.beams.isEmpty(), "no beam while overheated")
        } finally {
            physics.dispose()
        }
    }

    @Test
    fun `a kind becomes met once it is on the field`() {
        val state = GameState(MetaState(selectedShip = ShipType.Glory), Random(1))
        val physics = PhysicsSystem(state)
        try {
            val logic = GameLogic(state, physics, Idle, Random(1))
            state.startRun()
            val above = state.entities.spawn { newNpc(it, Gravel, Vec2(3f, Config.Physics.WORLD_HEIGHT + 2f)) }
            logic.step(dt)
            assertTrue(Gravel.id !in state.meta.seen, "not yet on the field")
            state.entities.replace((state.entities[above.id] as Npc).moved(Vec2(3f, 10f), Vec2.ZERO))
            logic.step(dt)
            assertTrue(Gravel.id in state.meta.seen)
        } finally {
            physics.dispose()
        }
    }
}
