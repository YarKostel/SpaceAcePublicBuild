package com.spaceace.game.npc

import com.spaceace.game.content.Drone
import com.spaceace.game.content.Gravel
import com.spaceace.game.hazard.BeamSpec
import com.spaceace.game.hazard.ZoneSpec
import com.spaceace.game.model.Brain
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Field
import com.spaceace.game.model.Health
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Npc
import com.spaceace.game.model.NpcBody
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Role
import com.spaceace.game.model.SummonSpec
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.angleBetween
import com.spaceace.game.model.degrees
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newNpc
import com.spaceace.game.model.newPlayer
import com.spaceace.game.model.newSummon
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
private val FIELD = Field(7.2f, 12.8f)

private fun testKind(behavior: Behavior, role: Role = Role.MOB) = object : NpcKind {
    override val id = "test"
    override val title = "TEST"
    override val description = emptyList<String>()
    override val role = role
    override val body = NpcBody(Hitbox.Rect(1f, 0.6f), density = 1f, restitution = 0f, maxHealth = 100f, armor = 1f, physical = false, maxAccel = 5f)
    override val behavior = behavior
    override val loot = NpcLoot(0..0)
}

private fun npcAt(kind: NpcKind, x: Float = 3.6f, y: Float = 9f) = newNpc(EntityId(1), kind, Vec2(x, y))

private fun sight(playerAt: Vec2? = Vec2(3.6f, 2f)) =
    Sight(playerAt?.let { newPlayer(EntityId(2)).moved(it, Vec2.ZERO) }, FIELD)

private fun input(npc: Npc, sight: Sight = sight(), dt: Float = 0.1f) = NpcInput(npc, sight, dt, Random(1))

/** Прогоняет ИИ [steps] шагов и возвращает номера шагов с выстрелами. */
private fun shotSteps(start: Npc, steps: Int, dt: Float, sight: Sight = sight()): List<Int> {
    var npc = start
    val random = Random(3)
    return (0 until steps).filter { step ->
        val orders = think(npc, sight, dt, random)
        npc = npc.withBrain(orders.brain)
        orders.shots.isNotEmpty()
    }
}

class TransitionTest {
    private val npc = npcAt(testKind(Behavior(BehaviorPhase(Movement.Descend(1f)))))

    @Test
    fun `health, time and kills`() {
        assertFalse(isMet(Transition.HealthBelow(0.5f), input(npc)))
        assertTrue(isMet(Transition.HealthBelow(0.5f), input(npc.withHealth(Health(40f, 100f)))))
        assertTrue(isMet(Transition.After(3f), input(npc.withBrain(Brain(phaseTime = 3f)))))
        val killed = npc.withBrain(Brain().recordKill("a").recordKill("b").recordKill("a"))
        assertTrue(isMet(Transition.Kills(2, "a"), input(killed)))
        assertFalse(isMet(Transition.Kills(2, "b"), input(killed)))
        assertTrue(isMet(Transition.Kills(3), input(killed)), "any kind")
    }

    @Test
    fun `any of and custom`() {
        val never = Transition.Custom { false }
        assertTrue(isMet(Transition.AnyOf(listOf(never, Transition.After(0f))), input(npc)))
        assertFalse(isMet(Transition.AnyOf(listOf(never)), input(npc)))
    }
}

class PhaseTest {
    private val kind = testKind(
        Behavior(
            listOf(
                BehaviorPhase(Movement.Descend(1f), exit = Transition.After(1f)),
                BehaviorPhase(Movement.Descend(2f), exit = Transition.After(1f)),
            ),
        ),
    )

    @Test
    fun `phase advances when its exit is met and the last phase stays`() {
        var npc = npcAt(kind)
        val speeds = ArrayList<Float>()
        repeat(40) {
            val orders = think(npc, sight(), 0.1f, Random(1))
            npc = npc.withBrain(orders.brain)
            speeds += -orders.velocity.y
        }
        assertEquals(1, npc.brain.phase)
        assertEquals(1f, speeds.first())
        assertEquals(2f, speeds.last())
    }

    @Test
    fun `new phase forgets kills and attack progress`() {
        val npc = npcAt(kind).withBrain(Brain(phaseTime = 5f).recordKill("x"))
        val orders = think(npc, sight(), 0.1f, Random(1))
        assertEquals(1, orders.brain.phase)
        assertTrue(orders.brain.kills.isEmpty())
    }
}

class AttackCycleTest {
    private val dt = 0.01f
    private fun burst(count: Int, interval: Float, aim: Aim = Aim.AtTarget()) =
        Burst(ProjectileKind.ENEMY_BOLT, count = count, interval = interval, speed = 5f, aim = aim)

    @Test
    fun `burst fires count shots at its interval then pauses`() {
        val plan = AttackPlan.Cycle(listOf(AttackStep(burst(3, 0.1f), pause = 1f)))
        val steps = shotSteps(npcAt(testKind(Behavior(BehaviorPhase(Movement.Descend(0f), plan)))), 160, dt)
        assertEquals(6, steps.size, "$steps")
        assertTrue(steps.take(3).zipWithNext { a, b -> b - a }.all { it in 9..11 }, "$steps")
        assertTrue(steps[3] - steps[2] in 99..101, "pause between bursts: $steps")
    }

    @Test
    fun `steps follow in order with their repeats`() {
        val plan = AttackPlan.Cycle(
            listOf(
                AttackStep(Burst(ProjectileKind.ENEMY_BOLT, 1, 0f, 5f, Aim.AtTarget()), repeats = 2..2, pause = 0.1f),
                AttackStep(Burst(ProjectileKind.ENEMY_FLAK_SHELL, 1, 0f, 5f, Aim.AtTarget()), pause = 0.1f),
            ),
        )
        var npc = npcAt(testKind(Behavior(BehaviorPhase(Movement.Descend(0f), plan))))
        val kinds = ArrayList<ProjectileKind>()
        repeat(100) {
            val orders = think(npc, sight(), dt, Random(1))
            npc = npc.withBrain(orders.brain)
            kinds += orders.shots.map { it.kind }
        }
        val pattern = listOf(ProjectileKind.ENEMY_BOLT, ProjectileKind.ENEMY_BOLT, ProjectileKind.ENEMY_FLAK_SHELL)
        assertEquals(pattern + pattern + pattern, kinds.take(9))
    }

    @Test
    fun `random pick never takes a step of zero weight`() {
        val plan = AttackPlan.Cycle(
            listOf(
                AttackStep(burst(1, 0f), pause = 0.05f, weight = 1f),
                AttackStep(Burst(ProjectileKind.ENEMY_FLAK_SHELL, 1, 0f, 5f, Aim.AtTarget()), pause = 0.05f, weight = 0f),
            ),
            pick = Pick.RANDOM,
        )
        var npc = npcAt(testKind(Behavior(BehaviorPhase(Movement.Descend(0f), plan))))
        val random = Random(7)
        repeat(300) {
            val orders = think(npc, sight(), dt, random)
            npc = npc.withBrain(orders.brain)
            assertTrue(orders.shots.none { it.kind == ProjectileKind.ENEMY_FLAK_SHELL })
        }
    }

    @Test
    fun `aimed burst holds fire while the player is outside the firing arc`() {
        val plan = AttackPlan.Cycle(listOf(AttackStep(burst(1, 0f, Aim.AtTarget(halfAngle = degrees(40f))), pause = 0.2f)))
        val npc = npcAt(testKind(Behavior(BehaviorPhase(Movement.Descend(0f), plan))), x = 3.6f, y = 9f)
        assertTrue(shotSteps(npc, 100, dt, sight(Vec2(0.5f, 8f))).isEmpty(), "player far to the side")
        assertTrue(shotSteps(npc, 100, dt, sight(Vec2(4f, 3f))).isNotEmpty(), "player below, inside the arc")
    }

    @Test
    fun `no shots before the npc is on the field`() {
        val plan = AttackPlan.Cycle(listOf(AttackStep(burst(1, 0f), pause = 0.1f)))
        val above = npcAt(testKind(Behavior(BehaviorPhase(Movement.Descend(0f), plan))), y = FIELD.height + 1f)
        assertTrue(shotSteps(above, 50, dt).isEmpty())
    }

    @Test
    fun `fan spreads shots symmetrically around the aim`() {
        val fan = Burst(ProjectileKind.ENEMY_BOLT, 1, 0f, 5f, Aim.Fixed(Vec2(0f, -1f)), fan = 3, fanStep = degrees(20f))
        val shots = checkNotNull(volley(fan, input(npcAt(testKind(Behavior(BehaviorPhase(Movement.Descend(0f))))))))
        assertEquals(3, shots.size)
        val down = Vec2(0f, -1f)
        assertEquals(0f, angleBetween(shots[1].velocity, down), 1e-4f)
        assertEquals(degrees(20f), angleBetween(shots[0].velocity, down), 1e-4f)
        assertEquals(-shots[0].velocity.x, shots[2].velocity.x, 1e-4f)
    }
}

class MovementTest {
    private val patrol = Movement.Patrol(hoverY = 10f, speed = 2f, entrySpeed = 3f, edgeMargin = 0.2f, hoverGain = 3f)
    private val kind = testKind(Behavior(BehaviorPhase(patrol)))

    @Test
    fun `patrol descends to hover height, then walks edge to edge`() {
        assertEquals(Vec2(0f, -3f), move(patrol, input(npcAt(kind, y = 13f))).velocity)
        val entered = move(patrol, input(npcAt(kind, y = 10f)))
        assertTrue(entered.state.entered)
        assertEquals(2f, entered.velocity.x)
        val atRight = npcAt(kind, x = FIELD.width - 0.5f, y = 10f).withBrain(Brain(move = entered.state))
        assertEquals(-2f, move(patrol, input(atRight)).velocity.x)
    }

    private val stalk = Movement.Stalk(distance = 4f, speedX = 2f, descendSpeed = 1.5f, leaveSpeed = 3f)
    private val stalker = testKind(Behavior(BehaviorPhase(stalk)))

    @Test
    fun `stalker never climbs, aligns with the player and hovers at its distance`() {
        val far = move(stalk, input(npcAt(stalker, x = 1f, y = 12f), sight(Vec2(5f, 2f))))
        assertEquals(-1.5f, far.velocity.y)
        assertEquals(2f, far.velocity.x)
        val atDistance = move(stalk, input(npcAt(stalker, x = 5f, y = 6f), sight(Vec2(5f, 2f))))
        assertEquals(Vec2.ZERO, atDistance.velocity)
        val tooLow = move(stalk, input(npcAt(stalker, x = 5f, y = 5f), sight(Vec2(5f, 2f))))
        assertEquals(0f, tooLow.velocity.y, "below its distance it waits instead of climbing")
    }

    @Test
    fun `stalker leaves downwards once the player is above`() {
        assertEquals(Vec2(0f, -3f), move(stalk, input(npcAt(stalker, y = 4f), sight(Vec2(3.6f, 6f)))).velocity)
    }

    @Test
    fun `stalker keeps its hover point inside the field`() {
        val high = move(stalk, input(npcAt(stalker, x = 3.6f, y = FIELD.height - 0.5f), sight(Vec2(3.6f, 10f))))
        assertEquals(0f, high.velocity.y)
    }
}

class HazardAttackTest {
    private val field = Field(7.2f, 12.8f)
    private val dt = 0.01f

    private fun kindWith(plan: AttackPlan) = object : NpcKind {
        override val id = "hazard-test"
        override val title = "HAZARD TEST"
        override val description = emptyList<String>()
        override val role = Role.MOB
        override val body = NpcBody(Hitbox.Circle(0.3f), 1f, 0f, 50f, 1f, physical = false, maxAccel = 1f)
        override val behavior = Behavior(BehaviorPhase(Movement.Descend(0f), plan))
        override val loot = NpcLoot(0..0)
    }

    private fun sight(at: Vec2) = Sight(newPlayer(EntityId(9)).moved(at, Vec2.ZERO), field)

    @Test
    fun `laser warns first, then burns for its duration along the direction fixed at the shot`() {
        val laser = Laser(BeamSpec(power = 20f, length = 12f, width = 0.1f, warmup = 0.5f), duration = 1f)
        var npc: Npc = newNpc(EntityId(1), kindWith(AttackPlan.Cycle(listOf(AttackStep(laser, pause = 5f)))), Vec2(3f, 9f))
        val warn = ArrayList<Int>()
        val live = ArrayList<Int>()
        val directions = HashSet<Vec2>()
        repeat(250) { step ->
            // Цель уходит вбок после выстрела — луч не следит за ней.
            val target = if (step < 5) Vec2(3f, 2f) else Vec2(6f, 2f)
            val orders = think(npc, sight(target), dt, Random(1))
            npc = npc.withBrain(orders.brain)
            for (beam in orders.beams) {
                if (beam.live) live += step else warn += step
                directions += beam.direction
                assertEquals(Faction.ENEMY, beam.faction)
            }
        }
        assertTrue(warn.size in 48..51, "warning ~0.5 s: ${warn.size}")
        assertTrue(live.size in 98..101, "burning ~1 s: ${live.size}")
        assertTrue(warn.max() < live.min())
        assertEquals(1, directions.size, "direction fixed at the shot")
        assertEquals(Vec2(0f, -1f), directions.single())
    }

    @Test
    fun `hazard zone appears under the target or under the npc`() {
        val spec = ZoneSpec(radius = 1f, power = 5f, lifetime = 2f, affects = setOf(Faction.PLAYER))
        val atTarget = newNpc(EntityId(1), kindWith(AttackPlan.Cycle(listOf(AttackStep(Hazard(spec), pause = 1f)))), Vec2(3f, 9f))
        val zone = think(atTarget, sight(Vec2(5f, 2f)), dt, Random(1)).zones.single()
        assertEquals(Vec2(5f, 2f), zone.center)
        assertEquals(Faction.ENEMY, zone.faction)

        val atSelf = newNpc(EntityId(2), kindWith(AttackPlan.Cycle(listOf(AttackStep(Hazard(spec, ZoneAt.SELF), pause = 1f)))), Vec2(3f, 9f))
        assertEquals(Vec2(3f, 9f), think(atSelf, sight(Vec2(5f, 2f)), dt, Random(1)).zones.single().center)
    }
}

class SummonRulesTest {
    private val field = Field(7.2f, 12.8f)
    private val spec = SummonSpec(Drone, count = 2, maxAlive = 3, lifetime = 5f, diesWithOwner = true)

    @Test
    fun `room respects count, per-owner limit and the total cap`() {
        assertEquals(2, summonRoom(spec, ownerAlive = 0, totalAlive = 0, maxTotal = 10))
        assertEquals(1, summonRoom(spec, ownerAlive = 2, totalAlive = 2, maxTotal = 10))
        assertEquals(0, summonRoom(spec, ownerAlive = 3, totalAlive = 3, maxTotal = 10))
        assertEquals(1, summonRoom(spec, ownerAlive = 0, totalAlive = 9, maxTotal = 10))
        assertEquals(0, summonRoom(spec, ownerAlive = 0, totalAlive = 12, maxTotal = 10))
    }

    @Test
    fun `summons appear on a ring around the summoner`() {
        val center = Vec2(3f, 4f)
        val positions = summonPositions(center, 3, 0.8f, Random(1))
        assertEquals(3, positions.size)
        assertTrue(positions.all { abs((it - center).length - 0.8f) < 1e-4f })
        assertEquals(3, positions.toSet().size)
    }

    @Test
    fun `summon vanishes when expired or when its owner is gone`() {
        val drone = newSummon(EntityId(1), spec, EntityId(9), Faction.PLAYER, Vec2.ZERO)
        assertFalse(shouldVanish(drone, ownerAlive = true))
        assertTrue(shouldVanish(drone.aged(5f), ownerAlive = true), "lifetime is over")
        assertTrue(shouldVanish(drone, ownerAlive = false), "dies with its owner")
        val loyal = newSummon(EntityId(2), spec.copy(diesWithOwner = false, lifetime = null), EntityId(9), Faction.PLAYER, Vec2.ZERO)
        assertFalse(shouldVanish(loyal.aged(1000f), ownerAlive = false))
        assertFalse(shouldVanish(newNpc(EntityId(3), Gravel, Vec2.ZERO), ownerAlive = false), "not a summon")
    }

    @Test
    fun `ally targets the nearest enemy, then meteors, never allies or things off the field`() {
        val from = Vec2(3f, 3f)
        val near = newNpc(EntityId(1), Gravel, Vec2(3f, 6f))
        val far = newNpc(EntityId(2), Gravel, Vec2(6f, 11f))
        val ally = newSummon(EntityId(3), spec, EntityId(9), Faction.PLAYER, Vec2(3f, 3.5f))
        val meteor = newMeteor(EntityId(4), MeteorSize.SMALL, Vec2(3f, 4f), Vec2.ZERO)
        val outside = newNpc(EntityId(5), Gravel, Vec2(3f, 14f))
        assertEquals(near, allyTarget(from, listOf(far, ally, meteor, near, outside), field))
        assertEquals(meteor, allyTarget(from, listOf(ally, meteor, outside), field))
        assertNull(allyTarget(from, listOf(ally, outside), field))
    }

    @Test
    fun `escort heads for its spot near the owner and stays put without one`() {
        val escort = Movement.Escort(radius = 1f, height = 1.2f, speed = 5f)
        val owner = newPlayer(EntityId(9)).moved(Vec2(3.6f, 2f), Vec2.ZERO)
        val far = newSummon(EntityId(1), spec, owner.id, Faction.PLAYER, Vec2(3.6f, 9f))
        val result = move(escort, NpcInput(far, Sight(null, field, owner), 1f / 60f, Random(1)))
        assertTrue(result.velocity.y < 0f && abs(result.velocity.length - 5f) < 1e-3f, "${result.velocity}")
        assertEquals(Vec2.ZERO, move(escort, NpcInput(far, Sight(null, field, null), 1f / 60f, Random(1))).velocity)
    }

    @Test
    fun `summon step orders a summon and then pauses`() {
        val plan = AttackPlan.Cycle(listOf(AttackStep(Summon(spec), pause = 2f)))
        val boss = newNpc(EntityId(1), Gravel, Vec2(3f, 9f))
        val first = attack(plan, NpcInput(boss, Sight(null, field), 0.01f, Random(1)))
        assertEquals(listOf(spec), first.summons)
        assertEquals(2f, first.state.cooldown)
        val later = attack(plan, NpcInput(boss.withBrain(boss.brain.copy(attack = first.state)), Sight(null, field), 0.01f, Random(1)))
        assertTrue(later.summons.isEmpty())
    }
}
