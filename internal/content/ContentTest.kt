package com.spaceace.game.content

import com.spaceace.game.Config
import com.spaceace.game.logic.bossLoot
import com.spaceace.game.model.Brain
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Health
import com.spaceace.game.model.Npc
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.Player
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newNpc
import com.spaceace.game.model.newPlayer
import com.spaceace.game.model.newProjectile
import com.spaceace.game.model.noseDirection
import com.spaceace.game.npc.AttackState
import com.spaceace.game.npc.Environment
import com.spaceace.game.npc.NpcInput
import com.spaceace.game.npc.Sight
import com.spaceace.game.npc.think
import com.spaceace.game.physics.DamageSettings
import com.spaceace.game.physics.ImpactEvent
import com.spaceace.game.physics.ImpactResolver
import com.spaceace.game.weapon.PierceSettings
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val field = Config.Physics.FIELD
private fun sight(player: Vec2?) = Sight(player?.let { newPlayer(EntityId(9)).moved(it, Vec2.ZERO) }, field)

class GravelTest {
    private fun run(start: Npc, player: Vec2, seconds: Float): List<ProjectileKind> {
        var npc = start
        val random = Random(1)
        val shots = ArrayList<ProjectileKind>()
        repeat((seconds * 60).toInt()) {
            val orders = think(npc, sight(player), 1f / 60f, random)
            npc = npc.withBrain(orders.brain)
            shots += orders.shots.map { it.kind }
        }
        return shots
    }

    @Test
    fun `fires bursts of three only inside its 80 degree arc`() {
        val gravel = newNpc(EntityId(1), Gravel, Vec2(3.6f, 8f))
        // Первая очередь через startDelay 1 с, следующая — после паузы 2 с: за 3 с ровно одна очередь.
        assertEquals(List(3) { ProjectileKind.ENEMY_BOLT }, run(gravel, Vec2(4f, 3f), seconds = 3f))
        assertTrue(run(gravel, Vec2(0.3f, 6f), seconds = 3.5f).isEmpty(), "player outside the arc")
    }

    @Test
    fun `gravels come alone or in pairs, borers by one to three, in every pattern`() {
        val entries = Levels.of(1).enemies
        assertTrue(entries.filter { it.kind == Gravel }.all { it.group.first >= 1 && it.group.last <= 2 })
        assertTrue(entries.filter { it.kind == Borer }.all { it.group.first >= 1 && it.group.last <= 3 })
        val patterns = entries.map { it.pattern::class }.toSet()
        assertEquals(3, patterns.size, "formation, wave and ambush are all used: $patterns")
    }
}

class BorerTest {
    private fun steer(angle: Float, position: Vec2, player: Vec2?): Pair<Float, Vec2> {
        val borer = newNpc(EntityId(1), Borer, position).turned(angle)
        val result = Borer.steer(NpcInput(borer, sight(player), 1f / 60f, Random(1)))
        return (result.angle ?: borer.angle) to result.velocity
    }

    @Test
    fun `nose stays in the lower hemisphere`() {
        for (deg in listOf(0, 30, 89, 271, 300, 359)) {
            val angle = Borer.lower(Math.toRadians(deg.toDouble()).toFloat())
            assertTrue(noseDirection(angle).y <= 1e-4f, "$deg° -> nose ${noseDirection(angle)}")
        }
    }

    @Test
    fun `rams a player below, turning its nose towards them`() {
        var angle = PI.toFloat()
        var velocity = Vec2.ZERO
        repeat(120) { steer(angle, Vec2(2f, 9f), Vec2(5f, 3f)).let { angle = it.first; velocity = it.second } }
        assertTrue(velocity.x > 0f && velocity.y < 0f, "heads down-right to the player: $velocity")
        val toPlayer = (Vec2(5f, 3f) - Vec2(2f, 9f)).normalized()
        assertTrue((noseDirection(angle) dot toPlayer) > 0.99f)
    }

    @Test
    fun `climbs back when the player gets above it, but leaves from the lower quarter`() {
        val (_, climb) = steer(PI.toFloat(), Vec2(3f, 6f), Vec2(3f, 8f))
        assertTrue(climb.y > 0f, "mid-field: climbs to attack again")
        val (_, leave) = steer(PI.toFloat(), Vec2(3f, 2f), Vec2(3f, 6f))
        assertTrue(leave.y < 0f, "lower quarter: leaves downwards")
    }

    @Test
    fun `nose hits harder and the front takes half damage`() {
        val resolver = ImpactResolver(DamageSettings(coefficient = 1f, energyThreshold = 0f), PierceSettings(0.5f, 0.5f, 1f))
        val borer = newNpc(EntityId(1), Borer, Vec2(3f, 5f)) // нос вниз
        val player = newPlayer(EntityId(2)).moved(Vec2(3f, 4f), Vec2.ZERO)
        val lookup = { id: EntityId -> listOf(borer, player).firstOrNull { it.id == id } }
        fun damageToPlayer(point: Vec2): Float {
            val outcome = resolver.resolve(listOf(ImpactEvent(borer.id, player.id, Vec2(0f, -4f), Vec2.ZERO, point)), lookup)
            return player.health.current - (outcome.updated.first { it is Player } as Player).health.current
        }
        val nose = damageToPlayer(Vec2(3f, 4.6f))
        val flank = damageToPlayer(Vec2(3.3f, 5f))
        assertEquals(1.5f, nose / flank, 1e-3f)

        val bullet = newProjectile(EntityId(3), ProjectileKind.BULLET, Vec2.ZERO, Vec2.ZERO)
        val shotLookup = { id: EntityId -> listOf(borer, bullet).firstOrNull { it.id == id } }
        fun damageToBorer(point: Vec2, velocity: Vec2): Float {
            val outcome = resolver.resolve(listOf(ImpactEvent(bullet.id, borer.id, velocity, Vec2.ZERO, point)), shotLookup)
            return borer.health.current - (outcome.updated.first { it is Npc } as Npc).health.current
        }
        val front = damageToBorer(Vec2(3f, 4.6f), Vec2(0f, 15f))
        val back = damageToBorer(Vec2(3f, 5.3f), Vec2(0f, 15f))
        assertEquals(0.5f, front / back, 1e-3f)
    }
}

class ShardTest {
    private val boss = newNpc(EntityId(1), Shard, Vec2(3.6f, Shard.HOVER_Y))

    private fun steps(start: Npc, player: Vec2, seconds: Float, dt: Float = 1f / 60f): Pair<Npc, List<ProjectileKind>> {
        var npc = start
        val random = Random(4)
        val shots = ArrayList<ProjectileKind>()
        repeat((seconds / dt).toInt()) {
            val orders = think(npc, sight(player), dt, random)
            npc = npc.withBrain(orders.brain)
            shots += orders.shots.map { it.kind }
        }
        return npc to shots
    }

    @Test
    fun `phase one alternates long bullet bursts and three flak shells`() {
        val (_, shots) = steps(boss, Vec2(3.6f, 2f), seconds = 40f)
        val runs = shots.fold(mutableListOf<Pair<ProjectileKind, Int>>()) { acc, kind ->
            if (acc.isNotEmpty() && acc.last().first == kind) acc[acc.lastIndex] = kind to acc.last().second + 1 else acc += kind to 1
            acc
        }
        val complete = runs.dropLast(1) // последняя серия могла оборваться на конце прогона
        val bolts = complete.filter { it.first == ProjectileKind.BOSS_BOLT }
        assertTrue(bolts.all { it.second % 10 == 0 && it.second / 10 in 2..5 }, "$runs")
        assertTrue(complete.filter { it.first == ProjectileKind.ENEMY_FLAK_SHELL }.all { it.second == 3 }, "$runs")
        assertTrue(complete.count { it.first == ProjectileKind.ENEMY_FLAK_SHELL } >= 2, "$runs")
    }

    @Test
    fun `below half health it switches to meteors and the aligned gauss shot`() {
        val hurt = boss.withHealth(Health(Shard.body.maxHealth * 0.49f, Shard.body.maxHealth))
        val (rage, _) = steps(hurt, Vec2(0.5f, 2f), seconds = 0.1f)
        assertEquals(1, rage.brain.phase)
        assertIs<Environment.SideMeteors>(Shard.behavior.phases[1].environment)
    }

    @Test
    fun `gauss fires after 2,5 s under the boss and the timer restarts`() {
        val phaseTwo = boss.withBrain(Brain(phase = 1, attack = AttackState()))
        fun input(player: Vec2, npc: Npc, dt: Float) = NpcInput(npc, sight(player), dt, Random(1))
        var npc = phaseTwo
        val under = Vec2(npc.position.x + 0.5f, 2f)
        val fired = ArrayList<Int>()
        repeat(330) { step ->
            val result = Shard.snipeWhenBelow(input(under, npc, 1f / 60f))
            if (result.shots.isNotEmpty()) {
                fired += step
                assertEquals(ProjectileKind.ENEMY_GAUSS_SLUG, result.shots.single().kind)
                assertTrue(result.shots.single().velocity.y < 0f)
            }
            npc = npc.withBrain(npc.brain.copy(attack = result.state))
        }
        assertEquals(2, fired.size, "$fired")
        assertTrue(fired[0] in 148..151 && fired[1] - fired[0] in 148..151, "$fired")

        val aside = Shard.snipeWhenBelow(input(Vec2(0.3f, 2f), npc, 3f))
        assertTrue(aside.shots.isEmpty(), "not under the boss — no shot and the timer resets")
    }

    @Test
    fun `boss drops its money and a trophy`() {
        val loot = bossLoot(boss, Random(1))
        assertEquals(60, loot.money)
        assertEquals(PermanentItem.Trophy(Shard), loot.item)
    }

    @Test
    fun `enemy gauss pierces meteors, flak splits into enemy shrapnel`() {
        val gauss = ProjectileKind.ENEMY_GAUSS_SLUG.stats
        assertTrue(gauss.piercing && gauss.hitsMeteors && !gauss.pushesPlayer)
        val split = assertNotNull(ProjectileKind.ENEMY_FLAK_SHELL.stats.split)
        assertEquals(ProjectileKind.ENEMY_FLAK_SHRAPNEL, split.into)
        assertEquals(Config.Weapons.Flak.SHRAPNEL_COUNT, split.count)
        assertEquals(Config.Weapons.Flak.FUSE, split.fuse)
    }
}

class LevelsTest {
    @Test
    fun `every level has a boss pool and the run fits the level list`() {
        assertTrue(Levels.ALL.size >= Config.Progression.LAST_LEVEL)
        for (level in 1..Config.Progression.LAST_LEVEL) assertEquals(Shard, Levels.bossFor(level, Random(level)))
        assertEquals(Levels.ALL.last(), Levels.of(99))
    }

    @Test
    fun `roster lists every kind once`() {
        assertEquals(Roster.ALL.size, Roster.ALL.map { it.id }.toSet().size)
    }
}
