package com.spaceace.game.physics

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Body
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.CircleShape
import com.badlogic.gdx.physics.box2d.Contact
import com.badlogic.gdx.physics.box2d.ContactImpulse
import com.badlogic.gdx.physics.box2d.ContactListener
import com.badlogic.gdx.physics.box2d.EdgeShape
import com.badlogic.gdx.physics.box2d.Fixture
import com.badlogic.gdx.physics.box2d.FixtureDef
import com.badlogic.gdx.physics.box2d.Manifold
import com.badlogic.gdx.physics.box2d.PolygonShape
import com.badlogic.gdx.physics.box2d.Shape
import com.badlogic.gdx.physics.box2d.World
import com.badlogic.gdx.utils.Disposable
import com.spaceace.game.Config
import com.spaceace.game.model.Entity
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Field
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.wrapAngle
import com.spaceace.game.state.EntityRegistry
import com.spaceace.game.state.GameState
import ktx.box2d.createWorld

// --- Система ---

/**
 * Фиксированный шаг физики и его прямые последствия: Box2D → события контактов →
 * [ImpactResolver] → обновление реестра и тел → удаление вылетевших и уничтоженных.
 * Дроп, раскол и подбор обрабатывает игровая логика по возвращённому [ImpactOutcome].
 */
class PhysicsSystem(private val state: GameState) : Disposable {
    private val field = Config.Physics.FIELD
    private val world = PhysicsWorld(state.entities, field)
    private val resolver = ImpactResolver(Config.Damage.SETTINGS, Config.Physics.PIERCE)

    /** Действует на тело до следующего шага; у только что созданной сущности тела ещё нет — тогда ничего. */
    fun applyImpulse(id: EntityId, impulse: Vec2) = world.applyImpulse(id, impulse)

    fun setVelocity(id: EntityId, velocity: Vec2) = world.setVelocity(id, velocity)

    /** Поворачивает тело к углу [angle] за один шаг [dt] (через угловую скорость, контакты не ломаются). */
    fun turnTo(id: EntityId, angle: Float, dt: Float) = world.turnTo(id, angle, dt)

    fun step(dt: Float): ImpactOutcome {
        val registry = state.entities
        world.dropOrphans()
        val events = world.step(dt)
        val outcome = resolver.resolve(events, registry::get)

        outcome.updated.forEach(registry::replace)
        for ((id, impulse) in outcome.impulses) world.applyImpulse(id, impulse)
        for ((id, velocity) in outcome.velocityOverrides) world.setVelocity(id, velocity)
        world.syncToRegistry(dt)

        val expired = registry.all
            .filter { leftField(it, field, Config.Physics.WALL_EXTENSION) || (it is Projectile && it.isExpired) }
            .map { it.id }
        (outcome.removed + expired).forEach { registry.remove(it) }
        world.dropOrphans()

        if (outcome.playerDestroyed) state.gameOver()
        return outcome
    }

    override fun dispose() = world.dispose()
}

// --- Мир Box2D ---

/**
 * Обёртка над Box2D World. Тела создаются по снимкам из реестра, userData тела — [EntityId].
 * Наружу отдаёт только чистые данные (Vec2, ImpactEvent).
 */
class PhysicsWorld(private val registry: EntityRegistry, private val field: Field) : Disposable {
    private val world: World = createWorld(gravity = Vector2.Zero)
    private val bodies = HashMap<EntityId, Body>()
    /** Группа столкновений, с которой создано тело: влетающий метеорит меняет её, и фильтр надо обновить. */
    private val groups = HashMap<EntityId, CollisionGroup>()
    private val collector = ContactCollector(registry)

    init {
        world.setContactListener(collector)
        createBounds()
    }

    /** Один фиксированный шаг; возвращает контакты, начавшиеся за этот шаг. */
    fun step(dt: Float): List<ImpactEvent> {
        createMissingBodies()
        world.step(dt, Config.Physics.VELOCITY_ITERATIONS, Config.Physics.POSITION_ITERATIONS)
        return collector.drain()
    }

    fun applyImpulse(id: EntityId, impulse: Vec2) {
        val body = bodies[id] ?: return
        body.applyLinearImpulse(impulse.x, impulse.y, body.worldCenter.x, body.worldCenter.y, true)
    }

    fun setVelocity(id: EntityId, velocity: Vec2) {
        bodies[id]?.setLinearVelocity(velocity.x, velocity.y)
    }

    fun turnTo(id: EntityId, angle: Float, dt: Float) {
        val body = bodies[id] ?: return
        body.angularVelocity = wrapAngle(angle - body.angle) / dt
    }

    /** Переносит положения и скорости тел в снимки реестра; снаряды стареют на [dt]. */
    fun syncToRegistry(dt: Float) {
        for ((id, body) in bodies) {
            val entity = registry[id] ?: continue
            val moved = entity.moved(body.position.toVec2(), body.linearVelocity.toVec2())
            registry.replace(
                when (moved) {
                    is Projectile -> moved.aged(dt)
                    is Npc -> moved.turned(body.angle)
                    else -> moved
                },
            )
        }
    }

    /** Уничтожает тела сущностей, которых больше нет в реестре. */
    fun dropOrphans() {
        val iterator = bodies.entries.iterator()
        while (iterator.hasNext()) {
            val (id, body) = iterator.next()
            if (id !in registry) {
                world.destroyBody(body)
                iterator.remove()
                groups.remove(id)
            }
        }
    }

    override fun dispose() = world.dispose()

    private fun createMissingBodies() {
        for (entity in registry.all) {
            val body = bodies[entity.id]
            if (body == null) {
                bodies[entity.id] = createBody(entity)
                groups[entity.id] = collisionGroupOf(entity)
            } else {
                refreshFilter(entity, body)
            }
        }
    }

    private fun refreshFilter(entity: Entity, body: Body) {
        val group = collisionGroupOf(entity)
        if (groups[entity.id] == group) return
        val bits = filterFor(group)
        for (fixture in body.fixtureList) {
            fixture.filterData = fixture.filterData.apply {
                categoryBits = bits.category
                maskBits = bits.mask
            }
        }
        groups[entity.id] = group
    }

    private fun createBody(entity: Entity): Body {
        val material = materialOf(entity)
        val def = BodyDef().apply {
            type = when (material.bodyKind) {
                BodyKind.DYNAMIC -> BodyDef.BodyType.DynamicBody
                BodyKind.KINEMATIC -> BodyDef.BodyType.KinematicBody
            }
            position.set(entity.position.x, entity.position.y)
            angle = (entity as? Npc)?.angle ?: 0f
            linearVelocity.set(entity.velocity.x, entity.velocity.y)
            fixedRotation = material.fixedRotation
            bullet = material.bullet
            linearDamping = material.linearDamping
        }
        val body = world.createBody(def)
        body.userData = entity.id
        val bits = filterFor(collisionGroupOf(entity))
        val shape = shapeOf(entity.hitbox)
        try {
            body.createFixture(FixtureDef().apply {
                this.shape = shape
                density = material.density
                restitution = material.restitution
                friction = material.friction
                isSensor = material.sensor
                filter.categoryBits = bits.category
                filter.maskBits = bits.mask
            })
        } finally {
            shape.dispose()
        }
        return body
    }

    /** Box2D-форма по хитбоксу; вершины полигона уже против часовой стрелки и с центром в центре масс. */
    private fun shapeOf(hitbox: Hitbox): Shape {
        val vertices = hitbox.vertices ?: return CircleShape().apply { radius = hitbox.boundingRadius }
        val flat = FloatArray(vertices.size * 2)
        vertices.forEachIndexed { i, v ->
            flat[2 * i] = v.x
            flat[2 * i + 1] = v.y
        }
        return PolygonShape().apply { set(flat) }
    }

    private fun createBounds() {
        val bounds = world.createBody(BodyDef())
        val bottom = -Config.Physics.WALL_EXTENSION
        val top = field.height + Config.Physics.WALL_EXTENSION
        addEdge(bounds, Vec2(0f, bottom), Vec2(0f, top), CollisionGroup.WALL)
        addEdge(bounds, Vec2(field.width, bottom), Vec2(field.width, top), CollisionGroup.WALL)
        addEdge(bounds, Vec2(0f, field.height), Vec2(field.width, field.height), CollisionGroup.CEILING)
        addEdge(bounds, Vec2(0f, Config.Player.FLOOR_Y), Vec2(field.width, Config.Player.FLOOR_Y), CollisionGroup.FLOOR)
    }

    private fun addEdge(body: Body, from: Vec2, to: Vec2, group: CollisionGroup) {
        val bits = filterFor(group)
        val shape = EdgeShape().apply { set(from.x, from.y, to.x, to.y) }
        try {
            body.createFixture(FixtureDef().apply {
                this.shape = shape
                restitution = Config.Physics.WALL_RESTITUTION
                friction = Config.Physics.FRICTION
                filter.categoryBits = bits.category
                filter.maskBits = bits.mask
            })
        } finally {
            shape.dispose()
        }
    }
}

// --- Сбор контактов ---

/**
 * Box2D запрещает менять мир внутри колбэков, поэтому контакты только записываются,
 * а обрабатываются после world.step. beginContact вызывается до разрешения удара —
 * скорости в событии «доударные».
 */
internal class ContactCollector(private val registry: EntityRegistry) : ContactListener {
    private val events = ArrayList<ImpactEvent>()

    fun drain(): List<ImpactEvent> = events.toList().also { events.clear() }

    override fun beginContact(contact: Contact) {
        val bodyA = contact.fixtureA.body
        val bodyB = contact.fixtureB.body
        val (first, second) = when {
            bodyA.userData is EntityId -> bodyA to bodyB
            bodyB.userData is EntityId -> bodyB to bodyA
            else -> return
        }
        val manifold = contact.worldManifold
        events += ImpactEvent(
            first = first.userData as EntityId,
            second = second.userData as? EntityId,
            firstVelocity = first.linearVelocity.toVec2(),
            secondVelocity = second.linearVelocity.toVec2(),
            point = if (manifold.numberOfContactPoints > 0) manifold.points[0].toVec2() else null,
        )
    }

    override fun preSolve(contact: Contact, oldManifold: Manifold) {
        val a = contact.fixtureA
        val b = contact.fixtureB
        val projectile = projectileOf(a) ?: projectileOf(b)
        // Отключённый контакт не обменивается импульсом, но beginContact уже записал удар — урон будет.
        val player = playerOf(a) ?: playerOf(b)
        if (projectile != null && player != null && (!projectile.kind.stats.pushesPlayer || passesShield(projectile, player))) {
            contact.isEnabled = false
            return
        }
        contact.setRestitution(mixRestitution(a.restitution, b.restitution, projectile?.kind?.stats?.restitution))
    }

    override fun endContact(contact: Contact) = Unit

    override fun postSolve(contact: Contact, impulse: ContactImpulse) = Unit

    private fun projectileOf(fixture: Fixture): Projectile? =
        (fixture.body.userData as? EntityId)?.let { registry[it] as? Projectile }

    private fun playerOf(fixture: Fixture): Player? = (fixture.body.userData as? EntityId)?.let { registry[it] as? Player }

    /** Снаряд врага сильнее щита пролетает сквозь прикрытый щитом корабль (урона нет — см. ImpactResolver). */
    private fun passesShield(projectile: Projectile, player: Player): Boolean =
        player.shielded && projectile.faction == Faction.ENEMY && projectile.kind.stats.breakPower > Config.PowerUp.SHIELD_BREAK_POWER
}

internal fun Vector2.toVec2() = Vec2(x, y)
