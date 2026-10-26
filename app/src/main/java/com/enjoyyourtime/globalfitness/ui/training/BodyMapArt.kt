package com.enjoyyourtime.globalfitness.ui.training

import com.enjoyyourtime.globalfitness.training.model.Muscle

/**
 * One drawable part of a body figure as the upstream artwork names it ([slug]: "chest", "head"...),
 * with its SVG path data. Upstream splits most parts into left and right paths; they are merged here
 * because the app tracks muscle groups, not sides. The path data lives in BodyMapArtMale.kt and
 * BodyMapArtFemale.kt (react-native-body-highlighter, MIT; see NOTICE).
 */
internal class ArtPart(val slug: String, val paths: List<String>) {
    /** The muscle group this part shows; null for the head, hair, hands, knees, shins, ankles and feet. */
    val muscle: Muscle? = muscleForSlug(slug)

    /** Hair gets its own tone so the head reads as a head at small sizes. */
    val isHair: Boolean get() = slug == "hair"
}

internal fun part(slug: String, vararg paths: String) = ArtPart(slug, paths.asList())

/** Upstream slug → app muscle group. Slugs not listed are drawn in the neutral body colour and are not tappable. */
internal fun muscleForSlug(slug: String): Muscle? = when (slug) {
    "chest" -> Muscle.CHEST
    "deltoids" -> Muscle.SHOULDERS
    "biceps" -> Muscle.BICEPS
    "triceps" -> Muscle.TRICEPS
    "forearm" -> Muscle.FOREARMS
    "abs" -> Muscle.ABS
    "obliques" -> Muscle.OBLIQUES
    "trapezius" -> Muscle.TRAPS
    "upper-back" -> Muscle.LATS
    "lower-back" -> Muscle.LOWER_BACK
    "gluteal" -> Muscle.GLUTES
    "quadriceps" -> Muscle.QUADS
    "hamstring" -> Muscle.HAMSTRINGS
    "adductors" -> Muscle.ADDUCTORS
    "calves" -> Muscle.CALVES
    "neck" -> Muscle.NECK
    else -> null
}

/** The parts of one figure in drawing order (later parts are drawn on top). */
internal fun bodyArt(side: BodySide, female: Boolean): List<ArtPart> = when (side) {
    BodySide.FRONT -> if (female) FEMALE_FRONT_PARTS else MALE_FRONT_PARTS
    BodySide.BACK -> if (female) FEMALE_BACK_PARTS else MALE_BACK_PARTS
}

/** Muscles of [parts] in [Muscle] order. */
internal fun musclesOf(parts: List<ArtPart>): Set<Muscle> {
    val drawn = parts.mapNotNullTo(HashSet()) { it.muscle }
    return Muscle.entries.filterTo(LinkedHashSet()) { it in drawn }
}

private val frontMuscles by lazy { musclesOf(bodyArt(BodySide.FRONT, female = false)) }
private val backMuscles by lazy { musclesOf(bodyArt(BodySide.BACK, female = false)) }

/**
 * Muscles visible (and tappable) on this side of the body map, in [Muscle] order. Both figures show
 * the same muscles on each side, so this does not depend on the sex. Shoulders, triceps, forearms,
 * traps, adductors, calves and neck appear on both sides.
 */
fun BodySide.muscles(): Set<Muscle> = when (this) {
    BodySide.FRONT -> frontMuscles
    BodySide.BACK -> backMuscles
}
