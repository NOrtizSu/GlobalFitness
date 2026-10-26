package com.enjoyyourtime.globalfitness.ui.training.motion

/**
 * The colours of an animation, as ARGB ints indexed by [Ink.ordinal], derived from the theme's
 * roles so the figure works in light, dark and dynamic colour: the body is a step from the
 * background towards the text colour, trained muscles take the primary colour, upholstery the
 * secondary one and metal the text colour. Kept free of Compose types so it can be tested.
 */
internal class MotionPalette(val background: Int, val floor: Int, private val inks: IntArray) {
    operator fun get(ink: Ink): Int = inks[ink.ordinal]

    companion object {
        fun of(
            background: Int,
            onSurface: Int,
            onSurfaceVariant: Int,
            outline: Int,
            primary: Int,
            secondary: Int,
            tertiary: Int,
        ): MotionPalette {
            val dark = luminance(background) < 0.4f
            // In a light theme deeper means darker; in a dark one it means closer to the background.
            fun body(k: Float) = mix(background, onSurfaceVariant, k)
            val bodyNear = body(if (dark) 0.6f else 0.42f)
            val bodyFar = body(if (dark) 0.42f else 0.56f)
            val inks = IntArray(Ink.entries.size)
            fun set(ink: Ink, color: Int) {
                inks[ink.ordinal] = color
            }
            set(Ink.BODY, bodyNear)
            set(Ink.BODY_FAR, bodyFar)
            set(Ink.MUSCLE, primary)
            set(Ink.MUSCLE_FAR, mix(primary, bodyFar, 0.38f))
            set(Ink.METAL, mix(background, onSurface, if (dark) 0.78f else 0.82f))
            set(Ink.PLATE, mix(background, onSurface, if (dark) 0.5f else 0.7f))
            set(Ink.PLATE_DARK, mix(background, onSurface, if (dark) 0.36f else 0.86f))
            set(Ink.PAD, mix(background, secondary, if (dark) 0.42f else 0.62f))
            set(Ink.PAD_DARK, mix(background, secondary, if (dark) 0.28f else 0.8f))
            set(Ink.FRAME, mix(background, outline, if (dark) 0.5f else 0.42f))
            set(Ink.FRAME_DARK, mix(background, outline, if (dark) 0.36f else 0.58f))
            set(Ink.STACK, mix(background, secondary, if (dark) 0.55f else 0.5f))
            set(Ink.STACK_DARK, mix(background, secondary, if (dark) 0.38f else 0.68f))
            set(Ink.CABLE, mix(background, onSurfaceVariant, 0.75f))
            set(Ink.BAND, tertiary)
            set(Ink.HALO, background)
            return MotionPalette(background, mix(background, outline, if (dark) 0.55f else 0.45f), inks)
        }

        /** [a] moved towards [b] by [t] (0..1), per channel, opaque. */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Relative luminance (WCAG) of an ARGB colour, 0..1. */
        fun luminance(color: Int): Float {
            fun lin(shift: Int): Float {
                val c = ((color shr shift) and 0xFF) / 255f
                return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return 0.2126f * lin(16) + 0.7152f * lin(8) + 0.0722f * lin(0)
        }
    }
}
