package com.hachiwii.oink.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class OverlayActionKind { KEY, MOUSE }

enum class OverlayShape { CIRCLE, PILL }

data class OverlayButton(
    val id: String,
    val label: String,
    val kind: OverlayActionKind,
    val code: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Float = 56f,
    val shape: OverlayShape = OverlayShape.CIRCLE,
    val toggle: Boolean = false,
)

data class OverlayProfile(
    val id: String,
    val name: String,
    val buttons: List<OverlayButton>,
)

data class OverlayConfig(
    val activeProfileId: String,
    val opacity: Float = 0.72f,
    val profiles: List<OverlayProfile>,
) {
    val activeProfile: OverlayProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    fun withOpacity(value: Float): OverlayConfig = copy(opacity = value.coerceIn(0.2f, 1.0f))

    fun withActiveProfile(profileId: String): OverlayConfig =
        if (profiles.any { it.id == profileId }) copy(activeProfileId = profileId) else this

    fun addProfile(name: String, basedOnActive: Boolean = false): OverlayConfig {
        val cleanName = name.trim().ifEmpty { "Profile ${profiles.size + 1}" }
        val baseButtons = if (basedOnActive) {
            activeProfile.buttons.map { it.copy(id = UUID.randomUUID().toString()) }
        } else emptyList()
        val created = OverlayProfile(id = UUID.randomUUID().toString(), name = cleanName, buttons = baseButtons)
        return copy(activeProfileId = created.id, profiles = profiles + created)
    }

    fun renameProfile(profileId: String, name: String): OverlayConfig {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return this
        return copy(profiles = profiles.map { if (it.id == profileId) it.copy(name = cleanName) else it })
    }

    fun deleteProfile(profileId: String): OverlayConfig {
        if (profiles.size <= 1) return this
        val next = profiles.filterNot { it.id == profileId }
        if (next.size == profiles.size) return this
        val nextActive = if (activeProfileId == profileId) next.first().id else activeProfileId
        return copy(activeProfileId = nextActive, profiles = next)
    }

    fun upsertButton(button: OverlayButton): OverlayConfig {
        val safe = button.copy(
            x = button.x.coerceIn(0.04f, 0.96f),
            y = button.y.coerceIn(0.04f, 0.96f),
            sizeDp = button.sizeDp.coerceIn(36f, 96f),
            label = button.label.ifBlank { OverlayBindings.shortLabel(button.kind, button.code) },
        )
        val current = activeProfile
        val updated = if (current.buttons.any { it.id == safe.id }) {
            current.buttons.map { if (it.id == safe.id) safe else it }
        } else {
            current.buttons + safe
        }
        return copy(profiles = profiles.map { if (it.id == current.id) it.copy(buttons = updated) else it })
    }

    fun removeButton(buttonId: String): OverlayConfig {
        val current = activeProfile
        val updated = current.buttons.filterNot { it.id == buttonId }
        return copy(profiles = profiles.map { if (it.id == current.id) it.copy(buttons = updated) else it })
    }
}

object OverlayBindings {
    data class ActionChoice(
        val id: String,
        val label: String,
        val shortLabel: String,
        val kind: OverlayActionKind,
        val code: Int,
        val category: String,
    )

    val categories: List<String> = listOf("鼠标按键", "常用按键", "方向键", "功能键", "字母键", "数字键")

    val choices: List<ActionChoice> = buildList {
        add(ActionChoice("mouse_left", "左键", "LMB", OverlayActionKind.MOUSE, ControlButton.LEFT.ordinal, "鼠标按键"))
        add(ActionChoice("mouse_right", "右键", "RMB", OverlayActionKind.MOUSE, ControlButton.RIGHT.ordinal, "鼠标按键"))
        add(ActionChoice("mouse_middle", "中键", "MMB", OverlayActionKind.MOUSE, ControlButton.MIDDLE.ordinal, "鼠标按键"))

        add(ActionChoice("key_esc", "Esc", "Esc", OverlayActionKind.KEY, Hid.ESCAPE, "常用按键"))
        add(ActionChoice("key_space", "空格", "Space", OverlayActionKind.KEY, Hid.SPACE, "常用按键"))
        add(ActionChoice("key_enter", "Enter", "Enter", OverlayActionKind.KEY, Hid.RETURN, "常用按键"))
        add(ActionChoice("key_tab", "Tab", "Tab", OverlayActionKind.KEY, Hid.TAB, "常用按键"))
        add(ActionChoice("key_backspace", "Backspace", "⌫", OverlayActionKind.KEY, Hid.BACKSPACE, "常用按键"))
        add(ActionChoice("key_shift", "Shift", "Shift", OverlayActionKind.KEY, Hid.L_SHIFT, "常用按键"))
        add(ActionChoice("key_ctrl", "Ctrl", "Ctrl", OverlayActionKind.KEY, Hid.L_CTRL, "常用按键"))
        add(ActionChoice("key_alt", "Alt", "Alt", OverlayActionKind.KEY, Hid.L_ALT, "常用按键"))
        add(ActionChoice("key_gui", "Win / Cmd", "Win", OverlayActionKind.KEY, Hid.L_GUI, "常用按键"))

        add(ActionChoice("key_up", "↑", "↑", OverlayActionKind.KEY, Hid.UP, "方向键"))
        add(ActionChoice("key_down", "↓", "↓", OverlayActionKind.KEY, Hid.DOWN, "方向键"))
        add(ActionChoice("key_left", "←", "←", OverlayActionKind.KEY, Hid.LEFT, "方向键"))
        add(ActionChoice("key_right", "→", "→", OverlayActionKind.KEY, Hid.RIGHT, "方向键"))

        for (n in 1..12) {
            add(ActionChoice("key_f$n", "F$n", "F$n", OverlayActionKind.KEY, Hid.f(n), "功能键"))
        }

        for (i in 0 until 26) {
            val ch = ('A' + i).toString()
            add(ActionChoice("key_${ch.lowercase()}", ch, ch, OverlayActionKind.KEY, Hid.key(0x04 + i), "字母键"))
        }

        for (n in 1..9) {
            add(ActionChoice("key_$n", "$n", "$n", OverlayActionKind.KEY, Hid.key(0x1E + n - 1), "数字键"))
        }
        add(ActionChoice("key_0", "0", "0", OverlayActionKind.KEY, Hid.key(0x27), "数字键"))
    }

    fun find(kind: OverlayActionKind, code: Int): ActionChoice? =
        choices.firstOrNull { it.kind == kind && it.code == code }

    fun shortLabel(kind: OverlayActionKind, code: Int): String =
        find(kind, code)?.shortLabel ?: if (kind == OverlayActionKind.MOUSE) "M$code" else "K$code"

    fun fullLabel(kind: OverlayActionKind, code: Int): String =
        find(kind, code)?.label ?: shortLabel(kind, code)

    fun newButton(x: Float = 0.5f, y: Float = 0.5f): OverlayButton = OverlayButton(
        id = UUID.randomUUID().toString(),
        label = "Space",
        kind = OverlayActionKind.KEY,
        code = Hid.SPACE,
        x = x.coerceIn(0.04f, 0.96f),
        y = y.coerceIn(0.04f, 0.96f),
        sizeDp = 56f,
        shape = OverlayShape.CIRCLE,
        toggle = false,
    )
}

object OverlayPrefs {
    private const val PREF_FILE = "control_overlay"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_ENABLED = "overlay_enabled"

    fun defaultConfig(): OverlayConfig = OverlayConfig(
        activeProfileId = "fps",
        opacity = 0.72f,
        profiles = listOf(
            OverlayProfile(
                id = "fps",
                name = "FPS",
                buttons = listOf(
                    OverlayButton("fps_w", "W", OverlayActionKind.KEY, Hid.key(0x1A), 0.18f, 0.44f, 52f),
                    OverlayButton("fps_a", "A", OverlayActionKind.KEY, Hid.key(0x04), 0.10f, 0.60f, 52f),
                    OverlayButton("fps_s", "S", OverlayActionKind.KEY, Hid.key(0x16), 0.18f, 0.76f, 52f),
                    OverlayButton("fps_d", "D", OverlayActionKind.KEY, Hid.key(0x07), 0.26f, 0.60f, 52f),
                    OverlayButton("fps_shift", "Shift", OverlayActionKind.KEY, Hid.L_SHIFT, 0.09f, 0.27f, 50f, OverlayShape.PILL),
                    OverlayButton("fps_ctrl", "Ctrl", OverlayActionKind.KEY, Hid.L_CTRL, 0.09f, 0.88f, 48f, OverlayShape.PILL),
                    OverlayButton("fps_e", "E", OverlayActionKind.KEY, Hid.key(0x08), 0.75f, 0.54f, 50f),
                    OverlayButton("fps_r", "R", OverlayActionKind.KEY, Hid.key(0x15), 0.89f, 0.54f, 50f),
                    OverlayButton("fps_space", "Space", OverlayActionKind.KEY, Hid.SPACE, 0.80f, 0.78f, 56f, OverlayShape.PILL),
                    OverlayButton("fps_lmb", "LMB", OverlayActionKind.MOUSE, ControlButton.LEFT.ordinal, 0.88f, 0.32f, 56f),
                    OverlayButton("fps_rmb", "RMB", OverlayActionKind.MOUSE, ControlButton.RIGHT.ordinal, 0.74f, 0.32f, 52f),
                    OverlayButton("fps_esc", "Esc", OverlayActionKind.KEY, Hid.ESCAPE, 0.50f, 0.18f, 44f, OverlayShape.PILL),
                ),
            ),
            OverlayProfile(
                id = "basic",
                name = "Basic",
                buttons = listOf(
                    OverlayButton("bas_up", "↑", OverlayActionKind.KEY, Hid.UP, 0.18f, 0.48f, 52f),
                    OverlayButton("bas_left", "←", OverlayActionKind.KEY, Hid.LEFT, 0.10f, 0.64f, 52f),
                    OverlayButton("bas_down", "↓", OverlayActionKind.KEY, Hid.DOWN, 0.18f, 0.80f, 52f),
                    OverlayButton("bas_right", "→", OverlayActionKind.KEY, Hid.RIGHT, 0.26f, 0.64f, 52f),
                    OverlayButton("bas_enter", "Enter", OverlayActionKind.KEY, Hid.RETURN, 0.86f, 0.52f, 52f, OverlayShape.PILL),
                    OverlayButton("bas_space", "Space", OverlayActionKind.KEY, Hid.SPACE, 0.76f, 0.74f, 56f, OverlayShape.PILL),
                    OverlayButton("bas_lmb", "LMB", OverlayActionKind.MOUSE, ControlButton.LEFT.ordinal, 0.88f, 0.30f, 52f),
                    OverlayButton("bas_rmb", "RMB", OverlayActionKind.MOUSE, ControlButton.RIGHT.ordinal, 0.74f, 0.30f, 52f),
                    OverlayButton("bas_esc", "Esc", OverlayActionKind.KEY, Hid.ESCAPE, 0.50f, 0.18f, 44f, OverlayShape.PILL),
                ),
            ),
        ),
    )

    fun load(context: Context): OverlayConfig {
        val raw = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getString(KEY_CONFIG, null)
        return decode(raw)
    }

    fun save(context: Context, config: OverlayConfig) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIG, encode(config))
            .apply()
    }

    fun loadEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun saveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun encode(config: OverlayConfig): String {
        val root = JSONObject()
        root.put("activeProfileId", config.activeProfileId)
        root.put("opacity", config.opacity.toDouble())
        val profilesJson = JSONArray()
        config.profiles.forEach { profile ->
            val pObj = JSONObject()
            pObj.put("id", profile.id)
            pObj.put("name", profile.name)
            val buttonsJson = JSONArray()
            profile.buttons.forEach { btn ->
                val bObj = JSONObject()
                bObj.put("id", btn.id)
                bObj.put("label", btn.label)
                bObj.put("kind", btn.kind.name)
                bObj.put("code", btn.code)
                bObj.put("x", btn.x.toDouble())
                bObj.put("y", btn.y.toDouble())
                bObj.put("sizeDp", btn.sizeDp.toDouble())
                bObj.put("shape", btn.shape.name)
                bObj.put("toggle", btn.toggle)
                buttonsJson.put(bObj)
            }
            pObj.put("buttons", buttonsJson)
            profilesJson.put(pObj)
        }
        root.put("profiles", profilesJson)
        return root.toString()
    }

    fun decode(json: String?): OverlayConfig {
        if (json.isNullOrBlank()) return defaultConfig()
        return runCatching {
            val root = JSONObject(json)
            val activeId = root.optString("activeProfileId", "")
            val opacity = root.optDouble("opacity", 0.72).toFloat().coerceIn(0.2f, 1.0f)
            val profilesArr = root.optJSONArray("profiles") ?: return defaultConfig()
            val profiles = buildList {
                for (i in 0 until profilesArr.length()) {
                    val pObj = profilesArr.optJSONObject(i) ?: continue
                    val id = pObj.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val name = pObj.optString("name").ifBlank { "Profile ${i + 1}" }
                    val bArr = pObj.optJSONArray("buttons") ?: JSONArray()
                    val buttons = buildList {
                        for (j in 0 until bArr.length()) {
                            val bObj = bArr.optJSONObject(j) ?: continue
                            val kind = runCatching { OverlayActionKind.valueOf(bObj.optString("kind", "KEY")) }
                                .getOrDefault(OverlayActionKind.KEY)
                            val code = bObj.optInt("code", Hid.SPACE)
                            val shape = runCatching { OverlayShape.valueOf(bObj.optString("shape", "CIRCLE")) }
                                .getOrDefault(OverlayShape.CIRCLE)
                            add(
                                OverlayButton(
                                    id = bObj.optString("id").ifBlank { UUID.randomUUID().toString() },
                                    label = bObj.optString("label").ifBlank { OverlayBindings.shortLabel(kind, code) },
                                    kind = kind,
                                    code = code,
                                    x = bObj.optDouble("x", 0.5).toFloat().coerceIn(0.04f, 0.96f),
                                    y = bObj.optDouble("y", 0.5).toFloat().coerceIn(0.04f, 0.96f),
                                    sizeDp = bObj.optDouble("sizeDp", 56.0).toFloat().coerceIn(36f, 96f),
                                    shape = shape,
                                    toggle = bObj.optBoolean("toggle", false),
                                )
                            )
                        }
                    }
                    add(OverlayProfile(id = id, name = name, buttons = buttons))
                }
            }
            if (profiles.isEmpty()) defaultConfig()
            else OverlayConfig(
                activeProfileId = if (profiles.any { it.id == activeId }) activeId else profiles.first().id,
                opacity = opacity,
                profiles = profiles,
            )
        }.getOrElse { defaultConfig() }
    }
}
