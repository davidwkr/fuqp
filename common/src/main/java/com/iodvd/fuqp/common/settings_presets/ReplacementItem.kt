package com.iodvd.fuqp.common.settings_presets

import com.iodvd.fuqp.common.Constants
import kotlinx.serialization.Serializable

@Serializable
data class ReplacementItem(
    val name: String,
    val value: String?,
    val database: String = Constants.SETTINGS_GLOBAL,
) {
    override fun toString() = "ReplacementItem { " +
            "'name': '$name', " +
            "'value': '$value', " +
            // "'database': '$database'" +
            " }"
}
