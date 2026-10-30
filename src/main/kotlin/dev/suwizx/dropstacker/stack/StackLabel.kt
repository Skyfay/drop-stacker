package dev.suwizx.dropstacker.stack

import dev.suwizx.dropstacker.config.DropStackerConfig
import dev.suwizx.dropstacker.config.LabelMode
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.entity.item.ItemEntity

/**
 * Builds the name tag shown on a stacked item entity from [DropStackerConfig.labelFormat].
 *
 * The mod is server-side only — a vanilla client must still see stack counts — so the label rides
 * on the entity's custom name rather than being drawn client-side. That makes every refresh a
 * synced-data write and a packet to every tracking player, so the hot path here is [labelKey]:
 * a cheap scalar that changes exactly when the *rendered text* would change. The mixin only
 * rebuilds the component tree when that key moves.
 *
 * Formats use `{placeholders}` and `&` colour codes (`&a`, `&l`, `&r`, `&#RRGGBB`, `&&` for a
 * literal `&`):
 * - `{count}` total items, `{stacks}` vanilla stacks they fill, `{countColor}` the threshold colour
 * - `{timer}` the rendered [DropStackerConfig.timerFormat], or nothing when no timer is shown
 * - `{time}` remaining `m:ss` (or `∞`), `{timerColor}` its urgency colour
 */
object StackLabel {
    /** [labelKey] value meaning "no label should be shown at all". */
    const val NO_LABEL: Long = Long.MIN_VALUE

    /** Vanilla's `ItemEntity.INFINITE_LIFETIME` sentinel. */
    const val INFINITE_LIFETIME: Int = -32768

    private val TIMER_PLACEHOLDERS = listOf("{timer}", "{time}", "{timerColor}")

    /** Legacy codes that set a colour (and so reset formatting); the rest are formats. */
    private const val COLOR_CODES = "0123456789abcdef"

    /** Bumped on every settings change, so labels built from an old format get rebuilt. */
    var generation: Int = 0
        private set

    /** Whether the format renders the timer at all; if not, it must not cause per-second rebuilds. */
    private var usesTimer: Boolean = true

    fun settingsChanged() {
        generation++
        usesTimer = DropStackerConfig.showDespawnTimer &&
            TIMER_PLACEHOLDERS.any { DropStackerConfig.labelFormat.contains(it) }
    }

    /** Whether labels change every second and so need the periodic refresh. */
    fun showsTimer(): Boolean = usesTimer && DropStackerConfig.labelMode != LabelMode.NEVER

    /**
     * A scalar identifying the text [build] would produce for this entity right now.
     *
     * Packs the count with the whole-second remainder of the despawn timer, which is the finest
     * granularity the rendered text can distinguish. Settings changes are tracked separately
     * through [generation].
     */
    fun labelKey(entity: ItemEntity, count: Int): Long {
        if (!shouldLabel(entity, count)) return NO_LABEL

        val seconds = when {
            !usesTimer -> -1L
            entity.age <= INFINITE_LIFETIME -> -2L
            else -> {
                val remaining = DropStackerConfig.despawnTicks - entity.age
                if (remaining > 0) (remaining / 20).toLong() else -1L
            }
        }
        return (count.toLong() shl 32) xor (seconds and 0xFFFFFFFFL)
    }

    private fun shouldLabel(entity: ItemEntity, count: Int): Boolean = when (DropStackerConfig.labelMode) {
        LabelMode.NEVER -> false
        LabelMode.STACKED_ONLY -> count > 1
        LabelMode.OVERSIZED_ONLY -> count > entity.item.maxStackSize
        LabelMode.ALWAYS -> !(count <= 0 || (count == 1 && DropStackerConfig.hideSingleItemLabel))
    }

    /** The label component, or `null` when this entity should carry no label. */
    fun build(entity: ItemEntity, count: Int): Component? {
        if (!shouldLabel(entity, count)) return null

        val countColor = when {
            count >= DropStackerConfig.countHighThreshold -> "&c"
            count >= DropStackerConfig.countLowThreshold -> "&e"
            else -> "&a"
        }
        val maxStackSize = entity.item.maxStackSize.coerceAtLeast(1)
        val stacks = (count + maxStackSize - 1) / maxStackSize

        var time = ""
        var timerColor = ""
        if (DropStackerConfig.showDespawnTimer) {
            if (entity.age <= INFINITE_LIFETIME) {
                time = "∞"
                timerColor = "&a"
            } else {
                val remainingTicks = DropStackerConfig.despawnTicks - entity.age
                if (remainingTicks > 0) {
                    val totalSeconds = remainingTicks / 20
                    time = "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
                    timerColor = when {
                        remainingTicks < 600 -> "&c"
                        remainingTicks < 2400 -> "&e"
                        else -> "&a"
                    }
                }
            }
        }

        val values = mapOf(
            "{count}" to count.toString(),
            "{stacks}" to stacks.toString(),
            "{countColor}" to countColor,
            "{time}" to time,
            "{timerColor}" to timerColor,
        )
        val timer = if (time.isEmpty()) "" else fill(DropStackerConfig.timerFormat, values)
        return parse(fill(DropStackerConfig.labelFormat, values + ("{timer}" to timer)))
    }

    private fun fill(format: String, values: Map<String, String>): String {
        var text = format
        for ((placeholder, value) in values) text = text.replace(placeholder, value)
        return text
    }

    /**
     * Turns `&` colour and format codes into a component. A colour code resets formatting, as in
     * vanilla's legacy codes; unknown codes are kept as plain text.
     */
    fun parse(text: String): Component {
        val root = Component.empty()
        val segment = StringBuilder()
        var style = Style.EMPTY

        fun flush() {
            if (segment.isNotEmpty()) {
                root.append(Component.literal(segment.toString()).withStyle(style))
                segment.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&' || i + 1 >= text.length) {
                segment.append(c)
                i++
                continue
            }

            val code = text[i + 1]
            if (code == '&') {
                segment.append('&')
                i += 2
                continue
            }
            if (code == '#' && i + 8 <= text.length) {
                val digits = text.substring(i + 2, i + 8)
                // `toIntOrNull(16)` alone also accepts a sign, which is not a colour.
                val rgb = if (digits.all { it.digitToIntOrNull(16) != null }) digits.toInt(16) else null
                if (rgb != null) {
                    flush()
                    style = Style.EMPTY.withColor(TextColor.fromRgb(rgb))
                    i += 8
                    continue
                }
            }

            val format = ChatFormatting.getByCode(code)
            if (format == null) {
                segment.append(c)
                i++
                continue
            }
            flush()
            style = when {
                format == ChatFormatting.RESET -> Style.EMPTY
                code.lowercaseChar() in COLOR_CODES -> Style.EMPTY.withColor(format)
                else -> style.applyFormat(format)
            }
            i += 2
        }
        flush()
        return root as MutableComponent
    }
}
