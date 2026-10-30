package dev.suwizx.dropstacker.gametest

import dev.suwizx.dropstacker.config.DropStackerConfig
import dev.suwizx.dropstacker.config.LabelMode
import dev.suwizx.dropstacker.stack.LabelVisibility
import dev.suwizx.dropstacker.stack.StackEngine
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.server.level.ServerEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.ProblemReporter
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.UpdateInterval
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Server gametests, run by `./gradlew runGameTest` (and so by `./gradlew build`).
 *
 * The save/load tests round-trip an entity through NBT in memory, which is the same path a world
 * save, chunk unload, `/data merge` or portal trip takes, without needing a server restart.
 */
class DropStackerGameTests {
    @GameTest
    fun oversizedPileSurvivesSaveLoad(helper: GameTestHelper) {
        val tag = save(helper, pile(helper, 110))
        helper.assertTrue(tag.getCompoundOrEmpty("Item").getIntOr("count", -1) == 99, "saved Item.count should be capped at 99, tag: $tag")
        helper.assertTrue(tag.getIntOr(COUNT_KEY, -1) == 110, "saved $COUNT_KEY should be 110, tag: $tag")

        val loaded = load(helper, tag)
        helper.assertTrue(!loaded.isRemoved, "loaded pile was discarded")
        helper.assertTrue(loaded.item.`is`(Items.DIAMOND), "loaded pile should be diamonds, got ${loaded.item}")
        helper.assertTrue(loaded.item.count == 110, "loaded pile should hold 110, got ${loaded.item.count}")
        helper.succeed()
    }

    @GameTest
    fun pileAtVanillaLimitHasNoExtraField(helper: GameTestHelper) {
        val tag = save(helper, pile(helper, 99))
        helper.assertTrue(!tag.contains(COUNT_KEY), "a 99 pile needs no $COUNT_KEY, tag: $tag")

        val loaded = load(helper, tag)
        helper.assertTrue(loaded.item.count == 99, "loaded pile should hold 99, got ${loaded.item.count}")
        helper.succeed()
    }

    @GameTest
    fun thousandPileSurvivesSaveLoad(helper: GameTestHelper) {
        val loaded = load(helper, save(helper, pile(helper, 1000)))
        helper.assertTrue(!loaded.isRemoved, "loaded pile was discarded")
        helper.assertTrue(loaded.item.count == 1000, "loaded pile should hold 1000, got ${loaded.item.count}")
        helper.succeed()
    }

    /** An admin's `/data merge ... {Item:{count:50}}` must win over a leftover extra field. */
    @GameTest
    fun editedCountBeatsStaleExtraField(helper: GameTestHelper) {
        val tag = save(helper, pile(helper, 99))
        tag.getCompoundOrEmpty("Item").putInt("count", 50)
        tag.putInt(COUNT_KEY, 110)

        val loaded = load(helper, tag)
        helper.assertTrue(loaded.item.count == 50, "edited count should win, got ${loaded.item.count}")
        helper.succeed()
    }

    @GameTest
    fun mergeOnSpawn(helper: GameTestHelper) {
        val pos = helper.absoluteVec(Vec3(2.5, 2.0, 2.5))
        for (count in intArrayOf(40, 40, 30)) {
            helper.level.addFreshEntity(ItemEntity(helper.level, pos.x, pos.y, pos.z, ItemStack(Items.DIAMOND, count)))
        }

        val alive = helper.getEntities(EntityTypes.ITEM).filter { it.isAlive }
        helper.assertTrue(alive.size == 1, "expected one merged pile, got ${alive.size}: ${alive.map { it.item }}")
        helper.assertTrue(alive[0].item.count == 110, "merged pile should hold 110, got ${alive[0].item.count}")
        alive.forEach { it.discard() }
        helper.succeed()
    }

    /** A thrown drop keeps its throw animation: it must not join a pile while it is still flying. */
    @GameTest
    fun thrownItemDoesNotMergeInFlight(helper: GameTestHelper) {
        val pile = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 100))
        val thrown = thrownItem(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 1))
        helper.level.addFreshEntity(thrown)

        helper.assertTrue(thrown.isAlive && thrown.item.count == 1, "a flying drop must not merge on spawn")
        helper.assertTrue(StackEngine.absorbNeighbours(pile) == 0 && thrown.isAlive, "a pile must not catch a flying drop")
        aliveItems(helper).forEach { it.discard() }
        helper.succeed()
    }

    /** Once landed, the thrown drop joins the pile, and the pile stays where it is. */
    @GameTest
    fun landedItemJoinsTheBiggerPile(helper: GameTestHelper) {
        val pile = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 100))
        val pilePos = pile.position()
        val thrown = thrownItem(helper, Vec3(4.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 1))
        helper.level.addFreshEntity(thrown)
        thrown.setOnGround(true)

        StackEngine.absorbNeighbours(thrown)
        helper.assertTrue(!thrown.isAlive, "the landed drop should have joined the pile")
        helper.assertTrue(pile.isAlive && pile.item.count == 101, "the pile should hold 101, got ${pile.item.count}")
        helper.assertTrue(pile.position() == pilePos, "the pile must not move to the small drop")
        pile.discard()
        helper.succeed()
    }

    /** A small item scanning first must not pull a big pile over to itself. */
    @GameTest
    fun smallItemDoesNotPullBigPile(helper: GameTestHelper) {
        // Spawned without merge-on-spawn, as if both had been loaded from disk, then the small one scans first.
        DropStackerConfig.mergeOnSpawn = false
        val pile: ItemEntity
        val small: ItemEntity
        try {
            pile = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 100))
            small = spawnPile(helper, Vec3(5.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 5))
        } finally {
            DropStackerConfig.mergeOnSpawn = true
        }
        val pilePos = pile.position()
        StackEngine.absorbNeighbours(small)

        helper.assertTrue(pile.position() == pilePos, "the big pile must not move")
        helper.assertTrue(pile.isAlive && pile.item.count == 105, "the big pile should take the small one in, got ${pile.item.count}")
        helper.assertTrue(!small.isAlive, "the small item should be gone")
        pile.discard()
        helper.succeed()
    }

    /**
     * Players pick a pile up one vanilla stack per touch. Logging mods such as Ledger record the
     * picked-up stack with the vanilla item codec, which rejects counts above 99.
     */
    @GameTest
    fun playerPicksUpOneStackPerTouch(helper: GameTestHelper) {
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        val pile = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.COBBLESTONE, 200))
        pile.setNoPickUpDelay()

        pile.playerTouch(player)
        helper.assertTrue(player.inventory.countItem(Items.COBBLESTONE) == 64, "one touch should take one stack, got ${player.inventory.countItem(Items.COBBLESTONE)}")
        helper.assertTrue(pile.isAlive && pile.item.count == 136, "136 should stay on the ground, got ${pile.item.count}")

        repeat(3) { pile.playerTouch(player) }
        helper.assertTrue(player.inventory.countItem(Items.COBBLESTONE) == 200, "all 200 should be picked up, got ${player.inventory.countItem(Items.COBBLESTONE)}")
        helper.assertTrue(aliveItems(helper).isEmpty(), "the pile should be gone")
        helper.succeed()
    }

    /** A player with a full inventory standing on a pile must not cause any change (and so no packet). */
    @GameTest
    fun fullInventoryLeavesPileUntouched(helper: GameTestHelper) {
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        for (slot in 0 until player.inventory.containerSize) player.inventory.setItem(slot, ItemStack(Items.DIRT, 64))
        val pile = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.COBBLESTONE, 200))
        pile.setNoPickUpDelay()
        val before = pile.item

        pile.playerTouch(player)
        helper.assertTrue(pile.item === before && before.count == 200, "pile should be the untouched stack, got ${pile.item}")
        helper.succeed()
    }

    /**
     * A new drop joins the pile already lying there instead of swallowing it, so the dropped entity
     * still holds exactly what was dropped (which is what Ledger logs as the drop).
     */
    @GameTest
    fun newDropJoinsExistingPile(helper: GameTestHelper) {
        val pile = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 100))
        val drop = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.DIAMOND, 64))

        helper.assertTrue(pile.isAlive && pile.item.count == 164, "existing pile should hold 164, got ${pile.item.count}")
        helper.assertTrue(drop.isRemoved, "the new drop should have joined the pile")
        helper.assertTrue(drop.item.count == 64, "the joined drop should still report what was dropped, got ${drop.item.count}")
        pile.discard()
        helper.succeed()
    }

    /**
     * A hopper used to put a whole oversized pile into one slot, where vanilla's `setItem` trims it
     * to 64 and the rest was deleted. Every item must end up either in the hopper or still on the
     * ground.
     */
    @GameTest
    fun hopperTakesOversizedPileWithoutLoss(helper: GameTestHelper) {
        val hopperPos = BlockPos(1, 1, 1)
        helper.setBlock(hopperPos, Blocks.HOPPER)
        val hopper = helper.getBlockEntity(hopperPos, HopperBlockEntity::class.java)
        val pile = spawnPile(helper, Vec3(1.5, 2.0, 1.5), ItemStack(Items.COBBLESTONE, 200))

        repeat(5) {
            HopperBlockEntity.addItem(hopper, pile)
            val inHopper = (0 until hopper.containerSize).sumOf { hopper.getItem(it).count }
            val onGround = aliveItems(helper).sumOf { it.item.count }
            helper.assertTrue(inHopper + onGround == 200, "items lost: $inHopper in hopper + $onGround on ground != 200")
            for (slot in 0 until hopper.containerSize) {
                helper.assertTrue(hopper.getItem(slot).count <= 64, "slot $slot is oversized: ${hopper.getItem(slot)}")
            }
        }
        helper.assertTrue(aliveItems(helper).isEmpty(), "the pile should be fully collected")
        helper.succeed()
    }

    /** Villagers, allays and piglins store pickups in a [SimpleContainer], which trimmed the same way. */
    @GameTest
    fun simpleContainerSplitsOversizedStack(helper: GameTestHelper) {
        val roomy = SimpleContainer(8)
        val noRest = roomy.addItem(ItemStack(Items.WHEAT_SEEDS, 200))
        helper.assertTrue(noRest.isEmpty, "everything should fit, got rest $noRest")
        helper.assertTrue(roomy.countItem(Items.WHEAT_SEEDS) == 200, "container should hold 200, got ${roomy.countItem(Items.WHEAT_SEEDS)}")

        val tight = SimpleContainer(2)
        val rest = tight.addItem(ItemStack(Items.WHEAT_SEEDS, 200))
        helper.assertTrue(tight.countItem(Items.WHEAT_SEEDS) == 128, "container should hold 128, got ${tight.countItem(Items.WHEAT_SEEDS)}")
        helper.assertTrue(rest.count == 72, "rest should be 72, got ${rest.count}")
        helper.succeed()
    }

    /** Dolphins take the whole ground stack into their hand; a hand stack over 99 cannot be saved. */
    @GameTest
    fun dolphinTakesAtMostOneStack(helper: GameTestHelper) {
        val dolphin = helper.spawn(EntityTypes.DOLPHIN, Vec3(2.5, 2.0, 2.5))
        val pile = spawnPile(helper, Vec3(2.5, 2.0, 2.5), ItemStack(Items.COD, 200))

        PICK_UP_ITEM.invoke(dolphin, helper.level, pile)

        val held = dolphin.getItemBySlot(EquipmentSlot.MAINHAND)
        val onGround = aliveItems(helper).sumOf { it.item.count }
        helper.assertTrue(held.count <= held.maxStackSize, "dolphin holds an oversized stack: $held")
        helper.assertTrue(held.count + onGround == 200, "items lost: ${held.count} held + $onGround on ground != 200")
        dolphin.discard()
        helper.succeed()
    }

    /** The mod's own label is saved as the entity's custom name and must still be recognised after a reload. */
    @GameTest
    fun labelKeepsUpdatingAfterReload(helper: GameTestHelper) {
        val loaded = load(helper, save(helper, pile(helper, 200)))
        helper.assertTrue(StackEngine.isMergeable(loaded, loaded.item), "a reloaded pile must still be mergeable")

        loaded.item = loaded.item.copyWithCount(150)
        val label = loaded.customName?.string ?: ""
        helper.assertTrue(label.contains("150"), "label should show the new count, got '$label'")
        helper.succeed()
    }

    @GameTest
    fun foreignNameSurvivesReload(helper: GameTestHelper) {
        val named = pile(helper, 20)
        named.customName = Component.literal("Bob")
        val loaded = load(helper, save(helper, named))

        loaded.item = loaded.item.copyWithCount(10)
        helper.assertTrue(loaded.customName?.string == "Bob", "a real name must not be replaced, got '${loaded.customName?.string}'")
        helper.assertTrue(!StackEngine.isMergeable(loaded, loaded.item), "a named drop must not be merged")
        helper.succeed()
    }

    /** Vanilla only merges drops reserved for the same player (`target`); so must the mod. */
    @GameTest
    fun reservedDropsDoNotMergeWithOthers(helper: GameTestHelper) {
        val pos = helper.absoluteVec(Vec3(2.5, 2.0, 2.5))
        val reserved = ItemEntity(helper.level, pos.x, pos.y, pos.z, ItemStack(Items.DIAMOND, 10))
        reserved.setTarget(UUID.randomUUID())
        helper.level.addFreshEntity(reserved)
        helper.level.addFreshEntity(ItemEntity(helper.level, pos.x, pos.y, pos.z, ItemStack(Items.DIAMOND, 10)))

        val alive = aliveItems(helper)
        helper.assertTrue(alive.size == 2, "reserved and public drops must stay apart, got ${alive.map { it.item }}")
        alive.forEach { it.discard() }
        helper.succeed()
    }

    /** The defaults must keep rendering exactly the label the mod always had. */
    @GameTest
    fun defaultLabelLooksAsBefore(helper: GameTestHelper) {
        val label = pile(helper, 200).customName?.string
        helper.assertTrue(label == "[×200 | 5:00]", "unexpected default label '$label'")
        helper.succeed()
    }

    @GameTest
    fun labelFormatIsConfigurable(helper: GameTestHelper) = withConfig({
        labelFormat = "&f{count}"
    }) {
        val name = pile(helper, 200).customName
        helper.assertTrue(name?.string == "200", "label should be the bare count, got '${name?.string}'")
        val color = name?.toFlatList()?.firstOrNull { it.string.isNotEmpty() }?.style?.color
        helper.assertTrue(color == TextColor.fromLegacyFormat(ChatFormatting.WHITE), "count should be white, got $color")

        DropStackerConfig.labelFormat = "&#ff8800{stacks} stacks{timer}"
        DropStackerConfig.timerFormat = " &7({time})"
        val styled = pile(helper, 130).customName
        helper.assertTrue(styled?.string == "3 stacks (5:00)", "unexpected label '${styled?.string}'")
        helper.succeed()
    }

    /** `OVERSIZED_ONLY` labels a pile only once it is larger than one vanilla stack of its item. */
    @GameTest
    fun oversizedOnlyLabelsPilesAboveOneStack(helper: GameTestHelper) = withConfig({
        labelMode = LabelMode.OVERSIZED_ONLY
    }) {
        helper.assertTrue(pile(helper, 64).customName == null, "a full stack of 64 needs no label")
        helper.assertTrue(pile(helper, 65).customName != null, "65 diamonds should be labelled")
        helper.assertTrue(itemPile(helper, ItemStack(Items.TOTEM_OF_UNDYING, 1)).customName == null, "a single totem needs no label")
        helper.assertTrue(itemPile(helper, ItemStack(Items.TOTEM_OF_UNDYING, 2)).customName != null, "two totems should be labelled")
        helper.succeed()
    }

    /** With `labelThroughWalls` off, clients are told a labelled pile is "discrete", which hides its name tag behind blocks. */
    @GameTest
    fun labelCanBeHiddenBehindWalls(helper: GameTestHelper) = withConfig({
        labelThroughWalls = false
    }) {
        val labelled = pile(helper, 200)
        val sent = LabelVisibility.withDiscreteFlag(labelled, labelled.entityData.nonDefaultValues!!)
        val flags = sent.single { it.id() == 0 }.value() as Byte
        helper.assertTrue(flags.toInt() and LabelVisibility.DISCRETE_FLAG_MASK != 0, "labelled pile should be sent as discrete, flags $flags")

        DropStackerConfig.labelThroughWalls = true
        val unchanged = LabelVisibility.withDiscreteFlag(labelled, labelled.entityData.nonDefaultValues!!)
        helper.assertTrue(unchanged.none { it.id() == 0 }, "nothing should change while labels may show through walls")
        helper.succeed()
    }

    /** The flag must reach the packets a real [ServerEntity] sends, both on tracking start and on later updates. */
    @GameTest
    fun discreteFlagReachesSentPackets(helper: GameTestHelper) = withConfig({
        labelThroughWalls = false
    }) {
        val labelled = pile(helper, 200)
        val sent = mutableListOf<Packet<*>>()
        val synchronizer = object : ServerEntity.Synchronizer {
            override fun sendToTrackingPlayers(packet: Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>) {
                sent += packet
            }

            override fun sendToTrackingPlayersAndSelf(packet: Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>) {
                sent += packet
            }

            override fun sendToTrackingPlayersFiltered(
                packet: Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>,
                filter: java.util.function.Predicate<net.minecraft.server.level.ServerPlayer>,
            ) {
                sent += packet
            }
        }
        val tracker = ServerEntity(helper.level, labelled, UpdateInterval.periodic(1), false, synchronizer)

        tracker.sendPairingData(helper.makeMockServerPlayerInLevel()) { sent += it }
        helper.assertTrue(isDiscrete(sent), "the pairing packet should carry the discrete flag")

        sent.clear()
        labelled.item = labelled.item.copyWithCount(150)
        tracker.sendChanges()
        helper.assertTrue(sent.any { it is ClientboundSetEntityDataPacket }, "a count change should send an entity data packet")
        helper.assertTrue(isDiscrete(sent), "the update packet should carry the discrete flag")
        helper.succeed()
    }

    /** True if some entity data packet in [packets] sets the discrete bit in the shared flags. */
    private fun isDiscrete(packets: List<Packet<*>>): Boolean = packets
        .filterIsInstance<ClientboundSetEntityDataPacket>()
        .flatMap { it.packedItems() }
        .any { it.id() == 0 && ((it.value() as Byte).toInt() and LabelVisibility.DISCRETE_FLAG_MASK) != 0 }

    /** Malformed `&` codes in a user-written format must never throw or produce an unsavable colour. */
    @GameTest
    fun malformedLabelFormatsAreTolerated(helper: GameTestHelper) = withConfig({
        showDespawnTimer = false
    }) {
        val cases = mapOf(
            "&" to "&",
            "{count}&" to "200&",
            "&#12{count}" to "&#12200",
            "&#zzzzzz{count}" to "&#zzzzzz200",
            "&q{count}" to "&q200",
            "&&{count}" to "&200",
            "{unknown}{count}" to "{unknown}200",
        )
        for ((format, expected) in cases) {
            DropStackerConfig.labelFormat = format
            val label = pile(helper, 200).customName?.string
            helper.assertTrue(label == expected, "format '$format' should give '$expected', got '$label'")
        }

        // A sign in the hex digits is not a colour: `toIntOrNull(16)` would accept it as a negative number.
        DropStackerConfig.labelFormat = "&#-1abcd{count}"
        val signed = pile(helper, 200)
        val loaded = load(helper, save(helper, signed))
        helper.assertTrue(loaded.customName?.string == "&#-1abcd200", "a signed hex value must stay plain text, got '${loaded.customName?.string}'")
        helper.succeed()
    }

    private fun pile(helper: GameTestHelper, count: Int): ItemEntity {
        val pos = helper.absoluteVec(Vec3(1.5, 2.0, 1.5))
        return ItemEntity(helper.level, pos.x, pos.y, pos.z, ItemStack(Items.DIAMOND, count))
    }

    private fun spawnPile(helper: GameTestHelper, relative: Vec3, stack: ItemStack): ItemEntity {
        val pos = helper.absoluteVec(relative)
        val entity = ItemEntity(helper.level, pos.x, pos.y, pos.z, stack, 0.0, 0.0, 0.0)
        helper.level.addFreshEntity(entity)
        return entity
    }

    /** A drop as a player throws it: with a thrower and moving. */
    private fun thrownItem(helper: GameTestHelper, relative: Vec3, stack: ItemStack): ItemEntity {
        val pos = helper.absoluteVec(relative)
        val entity = ItemEntity(helper.level, pos.x, pos.y, pos.z, stack, 0.3, 0.2, 0.0)
        entity.setThrower(helper.makeMockPlayer(GameType.SURVIVAL))
        return entity
    }

    private fun aliveItems(helper: GameTestHelper): List<ItemEntity> =
        helper.getEntities(EntityTypes.ITEM).filter { it.isAlive && !it.item.isEmpty }

    private fun itemPile(helper: GameTestHelper, stack: ItemStack): ItemEntity {
        val pos = helper.absoluteVec(Vec3(1.5, 2.0, 1.5))
        return ItemEntity(helper.level, pos.x, pos.y, pos.z, stack)
    }

    /**
     * Runs [test] with temporarily changed config. Every test body runs synchronously within one
     * tick, so other tests never observe the change.
     */
    private fun withConfig(change: DropStackerConfig.() -> Unit, test: () -> Unit) {
        val before = listOf(
            DropStackerConfig.labelMode, DropStackerConfig.labelFormat, DropStackerConfig.timerFormat,
            DropStackerConfig.labelThroughWalls, DropStackerConfig.showDespawnTimer,
        )
        try {
            DropStackerConfig.change()
            DropStackerConfig.labelSettingsChanged()
            test()
        } finally {
            DropStackerConfig.labelMode = before[0] as LabelMode
            DropStackerConfig.labelFormat = before[1] as String
            DropStackerConfig.timerFormat = before[2] as String
            DropStackerConfig.labelThroughWalls = before[3] as Boolean
            DropStackerConfig.showDespawnTimer = before[4] as Boolean
            DropStackerConfig.labelSettingsChanged()
        }
    }

    /** Saves [entity] the way a chunk save does, failing the test on any serialization problem. */
    private fun save(helper: GameTestHelper, entity: ItemEntity): CompoundTag {
        val problems = ProblemReporter.Collector()
        val output = TagValueOutput.createWithContext(problems, helper.level.registryAccess())
        entity.saveWithoutId(output)
        helper.assertTrue(problems.isEmpty, "save reported problems: ${problems.treeReport}")
        return output.buildResult()
    }

    private fun load(helper: GameTestHelper, tag: CompoundTag): ItemEntity {
        val entity = ItemEntity(EntityTypes.ITEM, helper.level)
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), tag))
        return entity
    }

    private companion object {
        const val COUNT_KEY = "DropStackerCount"

        /** `Mob.pickUpItem` is protected; names are unobfuscated in 26.x, so plain reflection works. */
        val PICK_UP_ITEM = Mob::class.java
            .getDeclaredMethod("pickUpItem", ServerLevel::class.java, ItemEntity::class.java)
            .apply { isAccessible = true }
    }
}
