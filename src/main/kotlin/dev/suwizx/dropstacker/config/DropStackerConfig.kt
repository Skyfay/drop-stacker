package dev.suwizx.dropstacker.config

import com.google.gson.GsonBuilder
import dev.suwizx.dropstacker.stack.StackLabel
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * How a receiving stack's despawn timer is affected when it absorbs a neighbour.
 *
 * [YOUNGEST] keeps a pile alive as long as fresh items keep dropping into it, which is convenient
 * around farms but means such a pile never despawns. [KEEP_RECEIVER] matches vanilla lifetimes.
 */
enum class AgeInheritance { YOUNGEST, KEEP_RECEIVER, OLDEST }

/**
 * When the count/timer name tag is attached to an item entity.
 *
 * [OVERSIZED_ONLY] labels only piles larger than one vanilla stack of their item (2+ totems, 65+
 * planks), i.e. exactly the piles a player could not tell apart from a normal drop.
 */
enum class LabelMode { ALWAYS, STACKED_ONLY, OVERSIZED_ONLY, NEVER }

object DropStackerConfig {
    private val logger = LoggerFactory.getLogger("drop-stacker-config")

    /** Vanilla `ItemEntity.LIFETIME`; used when [despawnTicks] is left unset. */
    const val VANILLA_LIFETIME: Int = 6000

    /** Renders the label as it looked before label formats existed. */
    const val DEFAULT_LABEL_FORMAT: String = "&6[{countColor}×{count}{timer}&6]"
    const val DEFAULT_TIMER_FORMAT: String = "&b | {timerColor}{time}"

    var enabled: Boolean = true
    var mergeOnSpawn: Boolean = true
    var maxStackSize: Int = 1000
    var radius: Double = 5.0
    var verticalRadius: Double = 2.0
    var scanInterval: Int = 5
    var maxScanInterval: Int = 100
    var maxMergePerPass: Int = 64
    var ageInheritance: AgeInheritance = AgeInheritance.YOUNGEST
    var labelMode: LabelMode = LabelMode.ALWAYS
    var showDespawnTimer: Boolean = true
    var countLowThreshold: Int = 64
    var countHighThreshold: Int = 500
    var hideSingleItemLabel: Boolean = false
    var labelFormat: String = DEFAULT_LABEL_FORMAT
    var timerFormat: String = DEFAULT_TIMER_FORMAT
    var labelThroughWalls: Boolean = true
    var despawnTicks: Int = VANILLA_LIFETIME
    var blacklist: List<String> = emptyList()
    var whitelist: List<String> = emptyList()
    var overrides: Map<String, Int> = emptyMap()

    private val configFile: File = FabricLoader.getInstance().configDir.resolve("drop-stacker.json").toFile()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load() {
        if (!configFile.exists()) {
            save()
            ItemRules.invalidate()
            return
        }

        try {
            val data = configFile.reader().use { reader ->
                gson.fromJson(reader, DropStackerConfigData::class.java)
            } ?: DropStackerConfigData()

            enabled = data.enabled ?: true
            mergeOnSpawn = data.mergeOnSpawn ?: true
            maxStackSize = (data.maxStackSize ?: 1000).coerceAtLeast(1)

            // scanRadiusX/Y/Z were split in 1.0.x; fold them into radius/verticalRadius so existing
            // configs keep working. The new keys win when both are present.
            radius = (data.radius ?: data.scanRadiusX ?: data.scanRadiusZ ?: 5.0).coerceAtLeast(0.0)
            verticalRadius = (data.verticalRadius ?: data.scanRadiusY ?: 2.0).coerceAtLeast(0.0)

            scanInterval = (data.scanInterval ?: 5).coerceAtLeast(1)
            maxScanInterval = (data.maxScanInterval ?: 100).coerceAtLeast(scanInterval)
            maxMergePerPass = (data.maxMergePerPass ?: 64).coerceAtLeast(1)
            ageInheritance = data.ageInheritance
                ?.let { runCatching { AgeInheritance.valueOf(it.uppercase()) }.getOrNull() }
                ?: AgeInheritance.YOUNGEST
            labelMode = data.labelMode
                ?.let { runCatching { LabelMode.valueOf(it.uppercase()) }.getOrNull() }
                ?: LabelMode.ALWAYS
            showDespawnTimer = data.showDespawnTimer ?: true
            countLowThreshold = data.countLowThreshold ?: 64
            countHighThreshold = data.countHighThreshold ?: 500
            hideSingleItemLabel = data.hideSingleItemLabel ?: false
            labelFormat = data.labelFormat ?: DEFAULT_LABEL_FORMAT
            timerFormat = data.timerFormat ?: DEFAULT_TIMER_FORMAT
            labelThroughWalls = data.labelThroughWalls ?: true
            despawnTicks = (data.despawnTicks ?: VANILLA_LIFETIME).coerceAtLeast(1)
            blacklist = data.blacklist ?: emptyList()
            whitelist = data.whitelist ?: emptyList()
            overrides = data.overrides ?: emptyMap()

            // Re-save so any new fields missing from an old config file get written
            save()
        } catch (e: Exception) {
            logger.error("Failed to load config, using defaults.", e)
        }

        ItemRules.invalidate()
        labelSettingsChanged()
    }

    /** Makes every label rebuild on its next refresh, e.g. after the format changed. */
    fun labelSettingsChanged() {
        StackLabel.settingsChanged()
    }

    fun save() {
        try {
            configFile.writeText(gson.toJson(snapshot()))
        } catch (e: Exception) {
            logger.error("Failed to save config.", e)
        }
    }

    private fun snapshot() = DropStackerConfigData(
        enabled = enabled,
        mergeOnSpawn = mergeOnSpawn,
        maxStackSize = maxStackSize,
        radius = radius,
        verticalRadius = verticalRadius,
        scanInterval = scanInterval,
        maxScanInterval = maxScanInterval,
        maxMergePerPass = maxMergePerPass,
        ageInheritance = ageInheritance.name,
        labelMode = labelMode.name,
        showDespawnTimer = showDespawnTimer,
        countLowThreshold = countLowThreshold,
        countHighThreshold = countHighThreshold,
        hideSingleItemLabel = hideSingleItemLabel,
        labelFormat = labelFormat,
        timerFormat = timerFormat,
        labelThroughWalls = labelThroughWalls,
        despawnTicks = despawnTicks,
        blacklist = blacklist,
        whitelist = whitelist,
        overrides = overrides,
    )

    private data class DropStackerConfigData(
        var enabled: Boolean? = null,
        var mergeOnSpawn: Boolean? = null,
        var maxStackSize: Int? = null,
        var radius: Double? = null,
        var verticalRadius: Double? = null,
        var scanInterval: Int? = null,
        var maxScanInterval: Int? = null,
        var maxMergePerPass: Int? = null,
        var ageInheritance: String? = null,
        var labelMode: String? = null,
        var showDespawnTimer: Boolean? = null,
        var countLowThreshold: Int? = null,
        var countHighThreshold: Int? = null,
        var hideSingleItemLabel: Boolean? = null,
        var labelFormat: String? = null,
        var timerFormat: String? = null,
        var labelThroughWalls: Boolean? = null,
        var despawnTicks: Int? = null,
        var blacklist: List<String>? = null,
        var whitelist: List<String>? = null,
        var overrides: Map<String, Int>? = null,

        // Legacy 1.0.x keys, read once and folded into radius/verticalRadius above.
        var scanRadiusX: Double? = null,
        var scanRadiusY: Double? = null,
        var scanRadiusZ: Double? = null,
    )
}
