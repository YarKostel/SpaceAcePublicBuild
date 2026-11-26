package com.spaceace.game.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import com.spaceace.game.Config
import com.spaceace.game.content.Borer
import com.spaceace.game.content.Drone
import com.spaceace.game.content.Gravel
import com.spaceace.game.content.Shard
import com.spaceace.game.model.Entity
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.Npc
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.Player
import com.spaceace.game.model.ShipType

// --- Описание картинки ---

/**
 * Картинка сущности: [file] — путь внутри assets, [size] — ширина картинки относительно ширины хитбокса,
 * [turn] — доворот к углу тела, градусы (картинка нарисована носом вверх), [pixelArt] — увеличивать без сглаживания.
 */
data class SpriteSpec(
    val file: String,
    val size: Float,
    val turn: Float = 0f,
    val pixelArt: Boolean = false,
) {
    init {
        require(size > 0f) { "size must be positive: $size" }
    }
}

fun spriteOf(ship: ShipType): SpriteSpec = when (ship) {
    ShipType.Rebel -> Config.Sprites.REBEL
    ShipType.Glory -> Config.Sprites.GLORY
    ShipType.Greedy -> Config.Sprites.GREEDY
}

/** Вид без картинки (черновик Pr1177) рисуется хитбоксом. */
fun spriteOf(kind: NpcKind): SpriteSpec? = when (kind) {
    Gravel -> Config.Sprites.GRAVEL
    Borer -> Config.Sprites.BORER
    Shard -> Config.Sprites.SHARD
    Drone -> Config.Sprites.DRONE
    else -> null
}

fun spriteOf(entity: Entity): SpriteSpec? = when (entity) {
    is Player -> spriteOf(entity.ship)
    is Meteor -> Config.Sprites.METEOR
    is Npc -> spriteOf(entity.kind)
    else -> null
}

/** Ширина картинки в метрах: хитбокс без поворота тела, умноженный на [SpriteSpec.size]. */
fun spriteWidth(spec: SpriteSpec, hitbox: Hitbox): Float = 2 * hitbox.halfWidth * spec.size

/** Постоянная скорость вращения метеорита, градусы в секунду: у каждого своя и в свою сторону. */
fun meteorSpin(id: Int): Float {
    val spread = ((id * 37) % 21 - 10) / 10f
    return Config.Sprites.METEOR_SPIN * spread
}

// --- Загрузка и отрисовка ---

/** Текстуры грузятся при первом обращении; нет файла — null, и сущность рисуется хитбоксом. */
class Sprites : Disposable {
    private val regions = HashMap<String, TextureRegion?>()
    private val locked = Color(Config.Sprites.LOCKED_TINT, Config.Sprites.LOCKED_TINT, Config.Sprites.LOCKED_TINT, 1f)
    private var time = 0f

    fun has(entity: Entity): Boolean = spriteOf(entity)?.let(::region) != null

    /** Сущности с картинками, метры → виртуальные пиксели ([ppm]). Метеориты крутятся только для вида. */
    fun drawEntities(batch: SpriteBatch, entities: Collection<Entity>, ppm: Float, delta: Float) {
        time += delta
        for (entity in entities) {
            val spec = spriteOf(entity) ?: continue
            val degrees = when (entity) {
                is Npc -> entity.angle * MathUtils.radiansToDegrees
                is Meteor -> entity.id.value * 47f + time * meteorSpin(entity.id.value)
                else -> 0f
            }
            draw(batch, spec, entity.hitbox, entity.position.x * ppm, entity.position.y * ppm, ppm, degrees)
        }
    }

    /** Корабль в ангаре и снаряжении; некупленный — затемнён. */
    fun drawShip(batch: SpriteBatch, ship: ShipType, cx: Float, cy: Float, scale: Float, owned: Boolean = true) {
        batch.color = if (owned) Color.WHITE else locked
        draw(batch, spriteOf(ship), ship.stats.hitbox, cx, cy, scale, 0f)
        batch.color = Color.WHITE
    }

    /** Карточка бестиария, картинка не больше [maxSize] px по большей стороне; false — картинки нет, вызывающий рисует хитбокс. */
    fun drawKind(batch: SpriteBatch, kind: NpcKind, cx: Float, cy: Float, scale: Float, maxSize: Float): Boolean {
        val spec = spriteOf(kind) ?: return false
        return draw(batch, spec, kind.body.hitbox, cx, cy, scale, kind.body.startAngle * MathUtils.radiansToDegrees, maxSize)
    }

    override fun dispose() {
        regions.values.forEach { it?.texture?.dispose() }
        regions.clear()
    }

    private fun draw(
        batch: SpriteBatch, spec: SpriteSpec, hitbox: Hitbox, cx: Float, cy: Float, scale: Float, degrees: Float,
        maxSize: Float = Float.MAX_VALUE,
    ): Boolean {
        val region = region(spec) ?: return false
        val natural = spriteWidth(spec, hitbox) * scale
        val aspect = region.regionHeight.toFloat() / region.regionWidth
        val width = natural * minOf(1f, maxSize / maxOf(natural, natural * aspect))
        val height = width * aspect
        batch.draw(region, cx - width / 2, cy - height / 2, width / 2, height / 2, width, height, 1f, 1f, degrees + spec.turn)
        return true
    }

    private fun region(spec: SpriteSpec): TextureRegion? = regions.getOrPut(spec.file) {
        val file = Gdx.files.internal(spec.file)
        if (!file.exists()) return@getOrPut null
        // Крупные картинки на экране сильно уменьшаются — без мип-уровней они рябят; пиксель-арт только увеличивается.
        val texture = Texture(file, !spec.pixelArt)
        if (spec.pixelArt) {
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        } else {
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        }
        TextureRegion(texture)
    }
}
