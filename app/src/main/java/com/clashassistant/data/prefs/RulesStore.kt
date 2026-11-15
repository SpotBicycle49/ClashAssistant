package com.clashassistant.data.prefs

import android.content.Context
import com.clashassistant.engine.elixir.ElixirRules

/** Хранит настройки правил эликсира на устройстве. */
class RulesStore(context: Context) {
    private val prefs = context.getSharedPreferences("elixir_rules", Context.MODE_PRIVATE)

    fun load(): ElixirRules {
        val d = ElixirRules()
        return ElixirRules(
            startElixir = prefs.getFloat("start", d.startElixir),
            maxElixir = prefs.getFloat("max", d.maxElixir),
            secondsPerElixir = prefs.getFloat("secPerElixir", d.secondsPerElixir),
            doubleStartSec = prefs.getFloat("doubleStart", d.doubleStartSec),
            tripleStartSec = prefs.getFloat("tripleStart", d.tripleStartSec),
        )
    }

    fun save(r: ElixirRules) {
        prefs.edit()
            .putFloat("start", r.startElixir)
            .putFloat("max", r.maxElixir)
            .putFloat("secPerElixir", r.secondsPerElixir)
            .putFloat("doubleStart", r.doubleStartSec)
            .putFloat("tripleStart", r.tripleStartSec)
            .apply()
    }
}
