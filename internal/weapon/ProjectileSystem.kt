package com.spaceace.game.weapon

import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.newProjectile
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.state.GameState
import kotlin.random.Random

/**
 * Поведение снарядов в полёте — каждый шаг, до шага физики. Единственное место в weapon/, которое меняет
 * поле: правила решают чистые функции в Ballistics.kt, здесь их результат применяется к реестру и Box2D.
 * Новое поведение снаряда = чистая функция в разделе оружия Ballistics.kt + проход здесь.
 * Поведение при ударе (пробитие, отскоки) — в [com.spaceace.game.physics.ImpactResolver] (physics/Impacts.kt).
 */
class ProjectileSystem(
    private val state: GameState,
    private val physics: PhysicsSystem,
    private val random: Random,
) {
    fun step(dt: Float) {
        guideMissiles(dt)
        detonateFlak()
        breakClashing(dt)
    }

    /** ИК ракеты: самонаводящиеся снаряды держат цель; уничтоженную цель заменяют ближайшей. */
    private fun guideMissiles(dt: Float) {
        val targets = state.entities.all.filter(::isAimable)
        for (projectile in state.entities.ofType<Projectile>()) {
            val current = projectile.target?.let { state.entities[it] }?.takeIf(::isAimable)
            val missile = guided(projectile, current, targets, dt) ?: continue
            state.entities.replace(missile)
            physics.setVelocity(missile.id, missile.velocity)
        }
    }

    /**
     * Зенитка: снаряды с истёкшим запалом заменяются осколками в той же точке. Тело Box2D снаряда физика уберёт сама
     * (сущности больше нет в реестре), тела осколков создаст на ближайшем шаге.
     */
    private fun detonateFlak() {
        for (shell in state.entities.ofType<Projectile>().filter(::isFlakReady)) {
            state.entities.remove(shell.id)
            for (shot in shrapnelOf(shell, random)) {
                state.entities.spawn { newProjectile(it, shot.kind, shell.position, shot.velocity, shell.massScale) }
            }
        }
    }

    /** Встречи снарядов: снаряды игрока и врагов, встретившиеся за шаг, сбивают друг друга по силе сбивания. */
    private fun breakClashing(dt: Float) {
        val (mine, theirs) = state.entities.ofType<Projectile>().partition { it.faction == Faction.PLAYER }
        if (mine.isEmpty() || theirs.isEmpty()) return
        val broken = HashSet<EntityId>()
        for (own in mine) {
            for (enemy in theirs) {
                if (enemy.id in broken || !touchWithin(own, enemy, dt)) continue
                when (clash(own.kind.stats, enemy.kind.stats)) {
                    Clash.NONE -> Unit
                    Clash.FIRST_BREAKS -> broken += own.id
                    Clash.SECOND_BREAKS -> broken += enemy.id
                    Clash.BOTH_BREAK -> broken += listOf(own.id, enemy.id)
                }
                if (own.id in broken) break
            }
        }
        broken.forEach(state.entities::remove)
    }
}
