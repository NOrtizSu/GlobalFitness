package com.enjoyyourtime.globalfitness.ui.training

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols glyphs of the training screens (the icons-extended artifact is not used). */
object TrainingIcons {
    /** Path data of every glyph, by name (also checked by a unit test). */
    internal val paths: Map<String, String> = mapOf(
        "FitnessCenter" to "M20.57,14.86L22,13.43 20.57,12 17,15.57 8.43,7 12,3.43 10.57,2 9.14,3.43 7.71,2 5.57,4.14 4.14,2.71 " +
            "2.71,4.14l1.43,1.43L2,7.71l1.43,1.43L2,10.57 3.43,12 7,8.43 15.57,17 12,20.57 13.43,22l1.43,-1.43L16.29,22l2.14,-2.14 " +
            "1.43,1.43 1.43,-1.43 -1.43,-1.43L22,16.29z",
        "MenuBook" to "M14,9.9v-1.7q0.825,-0.35 1.688,-0.525t1.812,-0.175q0.65,0 1.275,0.1t1.225,0.25v1.6" +
            "q-0.6,-0.225 -1.213,-0.338T17.5,9q-0.95,0 -1.825,0.238T14,9.9Zm0,5.5v-1.7q0.825,-0.35 1.688,-0.525" +
            "t1.812,-0.175q0.65,0 1.275,0.1t1.225,0.25v1.6q-0.6,-0.225 -1.213,-0.338T17.5,14.5q-0.95,0 -1.825,0.225" +
            "t-1.675,0.675Zm0,-2.75v-1.7q0.825,-0.35 1.688,-0.525t1.812,-0.175q0.65,0 1.275,0.1t1.225,0.25v1.6" +
            "q-0.6,-0.225 -1.213,-0.338T17.5,11.75q-0.95,0 -1.825,0.238T14,12.65Zm-1,4.4q1.1,-0.525 2.212,-0.788" +
            "T17.5,16q0.9,0 1.763,0.15t1.738,0.45v-9.9q-0.825,-0.35 -1.713,-0.525t-1.788,-0.175q-1.175,0 -2.325,0.3" +
            "t-2.175,0.9v9.85Zm-1,2.95q-1.2,-0.95 -2.6,-1.475t-2.9,-0.525q-1.05,0 -2.062,0.275T2.5,19.05" +
            "q-0.525,0.275 -1.012,-0.025T1,18.15v-12.05q0,-0.275 0.138,-0.525T1.55,5.2q1.175,-0.575 2.413,-0.888" +
            "T6.5,4q1.45,0 2.838,0.375T12,5.5q1.275,-0.75 2.663,-1.125T17.5,4q1.3,0 2.538,0.312T22.45,5.2" +
            "q0.275,0.125 0.413,0.375t0.138,0.525v12.05q0,0.575 -0.488,0.875t-1.012,0.025q-0.925,-0.5 -1.938,-0.775" +
            "T17.5,18q-1.5,0 -2.9,0.525t-2.6,1.475Z",
        "EditNote" to "M4,14v-2h7v2H4Zm0,-4v-2h11v2H4Zm0,-4v-2h11v2H4Zm9,14v-3.075l5.525,-5.5q0.225,-0.225 " +
            "0.5,-0.325t0.55,-0.1q0.3,0 0.575,0.113t0.5,0.338l0.925,0.925q0.2,0.225 0.312,0.5t0.113,0.55q0,0.275 " +
            "-0.1,0.562T21.575,14.5L16.075,20H13Zm6.575,-5.6l0.925,-0.975 -0.925,-0.925 -0.95,0.95 0.95,0.95Z",
        "History" to "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7" +
            "c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 " +
            "0.72,-1.21 -3.5,-2.08L13.5,8L12,8z",
        "Timer" to "M15,1L9,1v2h6L15,1zM11,14h2L13,8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42" +
            "C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61zM12,20c-3.87,0 -7,-3.13 " +
            "-7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        "StarBorder" to "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4" +
            "l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
        "PhotoCamera" to "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9z" +
            "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,15.2c1.77,0 3.2,-1.43 3.2,-3.2s-1.43,-3.2 -3.2,-3.2 " +
            "-3.2,1.43 -3.2,3.2 1.43,3.2 3.2,3.2z",
        "Image" to "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01" +
            "L14.5,12l4.5,6H5l3.5,-4.5z",
        "Trophy" to "M19,5h-2V3H7v2H5C3.9,5 3,5.9 3,7v1c0,2.55 1.92,4.63 4.39,4.94c0.63,1.5 1.98,2.63 3.61,2.96V19H7v2h10v-2h-4v-3.1" +
            "c1.63,-0.33 2.98,-1.46 3.61,-2.96C19.08,12.63 21,10.55 21,8V7C21,5.9 20.1,5 19,5zM5,8V7h2v3.82C5.84,10.4 5,9.3 5,8zM19,8" +
            "c0,1.3 -0.84,2.4 -2,2.82V7h2V8z",
        "Replay" to "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z",
        "SkipNext" to "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z",
        "Notes" to "M3,18h12v-2H3v2zM3,6v2h18V6H3zM3,13h18v-2H3v2z",
        "Tune" to "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z",
        "MusicNote" to "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
        "OpenInNew" to "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 " +
            "1.41,1.41L19,6.41V10h2V3h-7z",
    )

    /** [autoMirror]: directional glyphs are flipped in right-to-left layouts. */
    private fun icon(name: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).apply {
            addPath(pathData = addPathNodes(paths.getValue(name)), fill = SolidColor(Color.Black))
        }.build()

    /** Dumbbell: training itself (the Entreno tab's glyph). Equipment has its own glyphs in [ExerciseIcons]. */
    val FitnessCenter: ImageVector by lazy { icon("FitnessCenter") }

    /** Open book ("menu_book"): the exercise and machine catalog. */
    val MenuBook: ImageVector by lazy { icon("MenuBook") }

    /** Lines and a pencil ("edit_note"): logging workouts. */
    val EditNote: ImageVector by lazy { icon("EditNote", autoMirror = true) }

    val History: ImageVector by lazy { icon("History") }

    /** Rest timer. */
    val Timer: ImageVector by lazy { icon("Timer") }

    /** Favourite star, not set. */
    val StarBorder: ImageVector by lazy { icon("StarBorder") }

    val PhotoCamera: ImageVector by lazy { icon("PhotoCamera") }

    /** Gallery. */
    val Image: ImageVector by lazy { icon("Image") }

    /** Personal record. */
    val Trophy: ImageVector by lazy { icon("Trophy") }

    /** Repeat a past workout. */
    val Replay: ImageVector by lazy { icon("Replay") }

    /** Skip the rest. */
    val SkipNext: ImageVector by lazy { icon("SkipNext", autoMirror = true) }

    val Notes: ImageVector by lazy { icon("Notes", autoMirror = true) }

    /** Machine settings (seat, back rest...). */
    val Tune: ImageVector by lazy { icon("Tune") }

    /** The day's music (a generic note: Spotify's logo is never drawn). */
    val MusicNote: ImageVector by lazy { icon("MusicNote") }

    /** Opens outside the app (a playlist in Spotify). */
    val OpenInNew: ImageVector by lazy { icon("OpenInNew", autoMirror = true) }
}
