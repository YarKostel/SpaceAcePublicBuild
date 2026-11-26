package com.spaceace.game.state

import com.spaceace.game.Config
import com.spaceace.game.content.Shard
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.Player
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.WeaponType
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletTest {
    @Test
    fun `deposit and withdraw`() {
        val wallet = Wallet().deposit(50)
        assertEquals(50, wallet.balance)
        assertEquals(20, wallet.withdraw(30)?.balance)
        assertNull(wallet.withdraw(51))
    }

    @Test
    fun `negative amounts are rejected`() {
        assertFailsWith<IllegalArgumentException> { Wallet().deposit(-1) }
        assertFailsWith<IllegalArgumentException> { Wallet().withdraw(-1) }
        assertFailsWith<IllegalArgumentException> { Wallet(-5) }
    }

    @Test
    fun `overflow is detected`() {
        assertFailsWith<ArithmeticException> { Wallet(Int.MAX_VALUE).deposit(1) }
    }
}

class PhaseTest {
    @Test
    fun `progression cycles level, boss, shop`() {
        val phases = generateSequence(Phase.START) { it.next() }.take(4).toList()
        assertEquals(
            listOf(Phase.Level(1), Phase.BossFight(1), Phase.Shop(1), Phase.Level(2)),
            phases,
        )
    }
}

class GameStateTest {
    @Test
    fun `run starts from menu`() {
        val state = GameState()
        assertEquals(ScreenState.MENU, state.screen)
        assertTrue(state.startRun())
        assertEquals(ScreenState.PLAYING, state.screen)
        assertFalse(state.startRun())
    }

    @Test
    fun `field is shown during a run and hidden in menus`() {
        val state = GameState()
        assertFalse(state.showsField)
        state.startRun()
        assertTrue(state.showsField)
        state.pause()
        state.openSettings()
        assertTrue(state.showsField, "settings over pause keep the frozen field")
        state.closeSettings()
        state.openHangar()
        assertFalse(state.showsField, "hangar after quitting must not show the old run")
    }

    @Test
    fun `pause and resume only from valid screens`() {
        val state = GameState()
        assertFalse(state.pause())
        state.startRun()
        assertTrue(state.pause())
        assertFalse(state.pause())
        assertTrue(state.resume())
        assertEquals(ScreenState.PLAYING, state.screen)
    }

    @Test
    fun `new run after game over keeps money and resets the rest`() {
        val state = GameState()
        state.startRun()
        state.earn(120)
        state.entities.spawn { newMeteor(it, MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO) }
        state.equip(WeaponType.GAUSS)
        state.advancePhase()
        assertTrue(state.gameOver())

        assertTrue(state.startRun())
        assertEquals(120, state.meta.wallet.balance)
        assertEquals(Phase.START, state.run.phase)
        assertEquals(state.meta.selectedShip.stats.baseWeapon, state.run.weapon)
        assertIs<Player>(state.entities.all.single())
    }

    @Test
    fun `run starts with a full-health player`() {
        val state = GameState()
        state.startRun()
        assertEquals(state.meta.selectedShip.stats.maxHealth, state.player?.health?.current)
    }

    @Test
    fun `shop phase opens shop screen and next phase returns to playing`() {
        val state = GameState()
        state.startRun()
        state.advancePhase()
        assertEquals(ScreenState.PLAYING, state.screen)
        state.advancePhase()
        assertEquals(ScreenState.SHOP, state.screen)
        state.advancePhase()
        assertEquals(ScreenState.PLAYING, state.screen)
        assertEquals(Phase.Level(2), state.run.phase)
    }

    @Test
    fun `defeating the first boss pays out and opens the shop`() {
        val state = GameState()
        state.startRun()
        state.advancePhase()
        assertTrue(state.bossDefeated(50, PermanentItem.Trophy(Shard)))
        assertEquals(ScreenState.SHOP, state.screen)
        assertEquals(50, state.meta.wallet.balance)
        assertEquals(1, state.run.storage.items.size)
    }

    @Test
    fun `defeating the last boss is a victory`() {
        val state = GameState()
        state.startRun()
        repeat(3) { state.advancePhase() } // Level 1 → Boss 1 → Shop 1 → Level 2
        state.advancePhase() // Boss 2
        assertEquals(Phase.BossFight(Config.Progression.LAST_LEVEL), state.run.phase)
        assertTrue(state.bossDefeated(10, PermanentItem.Trophy(Shard)))
        assertEquals(ScreenState.VICTORY, state.screen)
        assertTrue(state.startRun())
    }

    @Test
    fun `boss reward only during a boss fight`() {
        val state = GameState()
        state.startRun()
        assertFalse(state.bossDefeated(50, PermanentItem.Trophy(Shard)))
        assertEquals(0, state.meta.wallet.balance)
    }

    @Test
    fun `next level starts with a clean field but the same player`() {
        val state = GameState()
        state.startRun()
        val player = assertNotNull(state.player)
        state.entities.spawn { newMeteor(it, MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO) }
        state.tick(5f)
        state.advancePhase()
        state.advancePhase()
        assertEquals(2, state.entities.size)
        state.advancePhase()
        assertEquals(listOf(player.id), state.entities.all.map { it.id })
        assertEquals(0f, state.run.phaseTime)
    }

    @Test
    fun `spend fails without enough money`() {
        val state = GameState(MetaState(Wallet(10)))
        assertFalse(state.spend(11))
        assertTrue(state.spend(10))
        assertEquals(0, state.meta.wallet.balance)
    }
}

class EntityRegistryTest {
    private fun meteor(id: EntityId) = newMeteor(id, MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO)

    @Test
    fun `spawn assigns unique ids`() {
        val registry = EntityRegistry()
        val a = registry.spawn(::meteor)
        val b = registry.spawn(::meteor)
        assertNotEquals(a.id, b.id)
        assertEquals(2, registry.size)
    }

    @Test
    fun `replace stores new snapshot`() {
        val registry = EntityRegistry()
        val meteor = registry.spawn(::meteor)
        registry.replace(meteor.moved(Vec2(1f, 1f), Vec2.ZERO))
        assertEquals(Vec2(1f, 1f), registry[meteor.id]?.position)
    }

    @Test
    fun `replace rejects unknown id and type change`() {
        val registry = EntityRegistry()
        assertFailsWith<IllegalArgumentException> { registry.replace(meteor(EntityId(42))) }
        val meteor = registry.spawn(::meteor)
        assertFailsWith<IllegalArgumentException> { registry.replace(newPlayer(meteor.id)) }
    }

    @Test
    fun `spawn rejects factory that ignores id`() {
        val registry = EntityRegistry()
        assertFailsWith<IllegalArgumentException> { registry.spawn { meteor(EntityId(999)) } }
    }

    @Test
    fun `ids are not reused after clear`() {
        val registry = EntityRegistry()
        val first = registry.spawn(::meteor)
        registry.clear()
        val second = registry.spawn(::meteor)
        assertNotEquals(first.id, second.id)
        assertNull(registry[first.id])
    }

    @Test
    fun `ofType filters by entity class`() {
        val registry = EntityRegistry()
        registry.spawn(::newPlayer)
        registry.spawn(::meteor)
        registry.spawn(::meteor)
        assertEquals(1, registry.ofType<Player>().size)
        assertEquals(2, registry.ofType<Meteor>().size)
    }

    @Test
    fun `remove returns removed entity`() {
        val registry = EntityRegistry()
        val meteor = registry.spawn(::meteor)
        assertEquals(meteor, registry.remove(meteor.id))
        assertNull(registry.remove(meteor.id))
    }
}
