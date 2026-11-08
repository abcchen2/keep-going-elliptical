package com.keepgoing.elliptical.model

import android.content.Context
import androidx.annotation.StringRes

/**
 * A string resource plus arguments, resolved only when displayed, so text kept in
 * [MachineState] follows a language change. Arguments may themselves be [UiText].
 */
class UiText(@StringRes val id: Int, vararg val args: Any) {
    fun resolve(context: Context): String =
        context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
}
