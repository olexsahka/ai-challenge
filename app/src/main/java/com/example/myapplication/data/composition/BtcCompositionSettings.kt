package com.example.myapplication.data.composition

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "mcp_composition_prefs"
private const val KEY_BTC_COMPOSITION_ENABLED = "btc_composition_enabled"

open class BtcCompositionSettings(context: Context?) {
    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    open var enabled: Boolean
        get() = prefs?.getBoolean(KEY_BTC_COMPOSITION_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_BTC_COMPOSITION_ENABLED, value)?.apply() }
}