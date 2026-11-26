package com.spaceace.game.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Disposable
import com.spaceace.game.Config
import com.spaceace.game.hazard.HazardBoard
import com.spaceace.game.model.Entity
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.PowerUp
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.rotatedBy
import ktx.graphics.use
// --- Поле ---

/** Звёздный слой: [count] звёзд размера [size] px, едут вниз со скоростью [speed] px/с, яркость [brightness] от 0 до 1. */
data class StarLayer(val count: Int, val speed: Float, val size: Float, val brightness: Float) {
    init {
        require(count >= 0) { "count must be non-negative: $count" }
        require(speed >= 0f) { "speed must be non-negative: $speed" }
        require(brightness in 0f..1f) { "brightness must be in 0..1: $brightness" }
    }
}

/** Неподвижная раскладка звезды; где она сейчас — решает [starY]. */
data class Star(val x: Float, val y: Float, val layer: Int)

/** Случайная, но повторяемая (по [seed]) раскладка звёзд всех слоёв по полю [width]×[height]. */
fun starsOf(layers: List<StarLayer>, width: Float, height: Float, seed: Long): List<Star> {
    val random = kotlin.random.Random(seed)
    return layers.flatMapIndexed { index, layer ->
        List(layer.count) { Star(random.nextFloat() * width, random.nextFloat() * height, index) }
    }
}

/** Высота звезды через [time] секунд: едет вниз и, уйдя за нижний край, возвращается сверху. */
fun starY(star: Star, layer: StarLayer, time: Float, height: Float): Float {
    val y = (star.y - layer.speed * time) % height
    return if (y < 0f) y + height else y
}

/** Заливает виртуальную область 720×1280 (чтобы letterbox FitViewport был виден) и рисует звёздный фон с параллаксом. */
class FieldRenderer : Disposable {
    private val shapes = ShapeRenderer()
    private val fieldColor = Color(Config.Display.FIELD_R, Config.Display.FIELD_G, Config.Display.FIELD_B, 1f)
    private val width = Config.Display.VIRTUAL_WIDTH
    private val height = Config.Display.VIRTUAL_HEIGHT
    private val layers = Config.Stars.LAYERS
    private val stars = starsOf(layers, width, height, Config.Stars.SEED)
    private val starColors = layers.map { Color(it.brightness, it.brightness, it.brightness, 1f) }
    private var time = 0f

    /** [delta] — сколько прошло с прошлого кадра; 0 — фон стоит (пауза, меню). */
    fun render(camera: Camera, delta: Float) {
        time += delta
        shapes.use(ShapeRenderer.ShapeType.Filled, camera) {
            it.color = fieldColor
            it.rect(0f, 0f, width, height)
            for (star in stars) {
                val layer = layers[star.layer]
                it.color = starColors[star.layer]
                it.rect(star.x, starY(star, layer, time, height), layer.size, layer.size)
            }
        }
    }

    override fun dispose() = shapes.dispose()
}

// --- Сущности ---

/**
 * Корабли, противники и метеориты — картинками ([Sprites]), снаряды и усилители — примитивами поверх них.
 * Только читает снимки; метры → виртуальные пиксели.
 */
class EntityRenderer(private val sprites: Sprites) : Disposable {
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val ppm = Config.Physics.PIXELS_PER_METER
    private val player = color(Config.Render.PLAYER)
    private val meteor = color(Config.Render.METEOR)
    private val boss = color(Config.Render.BOSS)
    private val enemy = color(Config.Render.ENEMY)
    private val ally = color(Config.Render.ALLY)
    private val shield = color(Config.Render.SHIELD)
    // Цвета снарядов — через when: новый ProjectileKind без цвета не скомпилируется (словарь упал бы только в игре).
    private val projectiles: Map<ProjectileKind, Color> = ProjectileKind.entries.associateWith { kind ->
        color(
            when (kind) {
                ProjectileKind.BULLET -> Config.Render.BULLET
                ProjectileKind.GATLING_ROUND -> Config.Render.GATLING_ROUND
                ProjectileKind.GAUSS_SLUG -> Config.Render.GAUSS_SLUG
                ProjectileKind.MISSILE -> Config.Render.MISSILE
                ProjectileKind.RICOCHET_ORB -> Config.Render.RICOCHET_ORB
                ProjectileKind.FLAK_SHELL -> Config.Render.FLAK_SHELL
                ProjectileKind.FLAK_SHRAPNEL -> Config.Render.FLAK_SHRAPNEL
                ProjectileKind.BOSS_BOLT -> Config.Render.BOSS_BOLT
                ProjectileKind.ENEMY_BOLT -> Config.Render.ENEMY_BOLT
                ProjectileKind.ENEMY_FLAK_SHELL -> Config.Render.ENEMY_FLAK_SHELL
                ProjectileKind.ENEMY_FLAK_SHRAPNEL -> Config.Render.ENEMY_FLAK_SHRAPNEL
                ProjectileKind.ENEMY_GAUSS_SLUG -> Config.Render.ENEMY_GAUSS_SLUG
                ProjectileKind.ALLY_BOLT -> Config.Render.ALLY_BOLT
            },
        )
    }

    fun render(camera: Camera, entities: Collection<Entity>, delta: Float) {
        batch.use(camera) { sprites.drawEntities(it, entities, ppm, delta) }
        shapes.use(ShapeRenderer.ShapeType.Filled, camera) {
            for (entity in entities) {
                if (sprites.has(entity)) continue
                it.color = colorOf(entity)
                val angle = (entity as? Npc)?.angle ?: 0f
                drawHitbox(it, entity.hitbox, entity.position.x * ppm, entity.position.y * ppm, ppm, angle)
            }
        }
        val shielded = entities.firstOrNull { it is Player && it.shielded } ?: return
        shapes.use(ShapeRenderer.ShapeType.Line, camera) {
            it.color = shield
            val r = (shielded.radius + Config.Render.SHIELD_RING_GAP) * ppm
            it.circle(shielded.position.x * ppm, shielded.position.y * ppm, r, Config.Render.CIRCLE_SEGMENTS * 2)
        }
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
    }

    private fun colorOf(entity: Entity): Color = when (entity) {
        is Player -> player
        is Meteor -> meteor
        is Npc -> when {
            entity.isAlly -> ally
            entity.isBoss -> boss
            else -> enemy
        }
        is PowerUp -> powerUpColor(entity.type)
        is Projectile -> projectiles.getValue(entity.kind)
    }

    private fun color(rgba8888: Long) = Color(rgba8888.toInt())
}

/**
 * Заливает хитбокс с центром в ([cx], [cy]) пикселях; [scale] — пикселей на метр, [angle] — поворот корпуса, рад.
 * Полигоны выпуклые, поэтому хватает веера треугольников из первой вершины.
 */
fun drawHitbox(shapes: ShapeRenderer, hitbox: Hitbox, cx: Float, cy: Float, scale: Float, angle: Float = 0f) {
    val vertices = hitbox.vertices?.let { list -> if (angle == 0f) list else list.map { it.rotatedBy(angle) } }
    if (vertices == null) {
        shapes.circle(cx, cy, hitbox.boundingRadius * scale, Config.Render.CIRCLE_SEGMENTS)
        return
    }
    val first = vertices[0]
    for (i in 1 until vertices.size - 1) {
        val b = vertices[i]
        val c = vertices[i + 1]
        shapes.triangle(
            cx + first.x * scale, cy + first.y * scale,
            cx + b.x * scale, cy + b.y * scale,
            cx + c.x * scale, cy + c.y * scale,
        )
    }
}

// --- Лучи и зоны ---

/**
 * Опасные зоны (под кораблями) и лучи (поверх). Предупреждение: у зоны — только контур, у луча — тонкая тусклая линия.
 * Только читает доску опасностей; метры → виртуальные пиксели.
 */
class HazardRenderer : Disposable {
    private val shapes = ShapeRenderer()
    private val ppm = Config.Physics.PIXELS_PER_METER
    private val playerBeam = color(Config.Render.BEAM_PLAYER)
    private val enemyBeam = color(Config.Render.BEAM_ENEMY)
    private val warning = color(Config.Render.BEAM_WARNING)
    private val playerZone = color(Config.Render.ZONE_PLAYER)
    private val enemyZone = color(Config.Render.ZONE_ENEMY)

    fun renderZones(camera: Camera, hazards: HazardBoard) {
        if (hazards.zones.isEmpty()) return
        blended {
            shapes.use(ShapeRenderer.ShapeType.Filled, camera) {
                for (zone in hazards.zones.filter { z -> z.live }) {
                    it.color = if (zone.faction == Faction.PLAYER) playerZone else enemyZone
                    it.circle(zone.center.x * ppm, zone.center.y * ppm, zone.spec.radius * ppm, Config.Render.CIRCLE_SEGMENTS * 2)
                }
            }
            shapes.use(ShapeRenderer.ShapeType.Line, camera) {
                it.color = warning
                for (zone in hazards.zones.filterNot { z -> z.live }) {
                    it.circle(zone.center.x * ppm, zone.center.y * ppm, zone.spec.radius * ppm, Config.Render.CIRCLE_SEGMENTS * 2)
                }
            }
        }
    }

    fun renderBeams(camera: Camera, hazards: HazardBoard) {
        if (hazards.beams.isEmpty()) return
        blended {
            shapes.use(ShapeRenderer.ShapeType.Filled, camera) {
                for (beam in hazards.beams) {
                    val shot = beam.shot
                    it.color = when {
                        !shot.live -> warning
                        shot.faction == Faction.PLAYER -> playerBeam
                        else -> enemyBeam
                    }
                    val width = if (shot.live) shot.spec.width else shot.spec.width * Config.Render.BEAM_WARNING_WIDTH
                    it.rectLine(shot.origin.x * ppm, shot.origin.y * ppm, beam.end.x * ppm, beam.end.y * ppm, width * ppm)
                }
            }
        }
    }

    override fun dispose() = shapes.dispose()

    private inline fun blended(draw: () -> Unit) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        draw()
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    private fun color(rgba8888: Long) = Color(rgba8888.toInt())
}

// --- Отладочный слой ---

data class DebugStats(val fps: Int, val physicsSteps: Int, val entities: Int)

/** Рамка поля и строка FPS/шагов/объектов под верхней полосой HUD. */
class DebugOverlay : Disposable {
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply { data.setScale(Config.Debug.FONT_SCALE) }
    private val frameColor = Color(Config.Debug.FRAME_R, Config.Debug.FRAME_G, Config.Debug.FRAME_B, 1f)

    // Строка пересобирается только при изменении значений, чтобы не аллоцировать каждый кадр.
    private var cached: DebugStats? = null
    private var line = ""

    fun render(camera: Camera, stats: DebugStats) {
        shapes.use(ShapeRenderer.ShapeType.Line, camera) {
            it.color = frameColor
            it.rect(1f, 1f, Config.Display.VIRTUAL_WIDTH - 2f, Config.Display.VIRTUAL_HEIGHT - 2f)
        }
        if (stats != cached) {
            cached = stats
            line = "FPS ${stats.fps}  steps ${stats.physicsSteps}  obj ${stats.entities}"
        }
        batch.use(camera) {
            font.draw(it, line, Config.Debug.TEXT_MARGIN, Config.Debug.TEXT_Y)
        }
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
    }
}
