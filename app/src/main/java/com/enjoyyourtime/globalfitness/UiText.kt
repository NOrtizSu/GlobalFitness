package com.enjoyyourtime.globalfitness

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * User-facing text produced outside the UI (view models, managers, error mappers). It is resolved
 * against the current locale only when shown, so a language change never leaves stale text behind.
 * Arguments may themselves be [UiText]s.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Text that needs no translation (a name, a server message). */
    data class Raw(val text: String) : UiText

    data class Joined(val parts: List<UiText>, val separator: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *resolveArgs(args, context))
        is Plural -> context.resources.getQuantityString(id, count, *resolveArgs(args, context))
        is Joined -> parts.joinToString(separator) { it.resolve(context) }
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

        fun raw(text: String): UiText = Raw(text)

        private fun resolveArgs(args: List<Any>, context: Context): Array<Any> =
            args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
    }
}
