package com.elitetunerx

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("etx", Context.MODE_PRIVATE)
    fun label(ctx: Context): String = get(ctx).getString("next_label", null) ?: "Game • DXVK ? • Driver ?"
    fun setLabel(ctx: Context, v: String) = get(ctx).edit().putString("next_label", v).apply()
    fun targetPkg(ctx: Context): String? = get(ctx).getString("target_pkg", null)
    fun setTargetPkg(ctx: Context, v: String) = get(ctx).edit().putString("target_pkg", v).apply()
}
