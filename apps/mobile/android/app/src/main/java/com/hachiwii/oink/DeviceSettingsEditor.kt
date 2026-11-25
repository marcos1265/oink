package com.hachiwii.oink

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.oink_mobile_ffi.*

@Composable
fun DeviceSettingsEditor(repository: OinkRepository, device: Device) {
    val lang = LocalAppLang.current
    fun s(zh: String) = L10n.translate(zh, lang)
    var settings by remember(device.id) { mutableStateOf<DeviceSettingsView?>(null) }
    OutlinedButton(enabled = device.online, onClick = {
        repository.command { core ->
            val value = core.deviceSettings(device.id)
            withContext(Dispatchers.Main) { settings = value }
        }
    }) { Text(s("读取电脑输入设置")) }
    settings?.let { value ->
        Text(s("修改后由电脑确认；可重新读取以查看最新值。"))
        listOf(Triple("模拟 Mac 触控板滚动", "macScrollFeel", value.macScrollFeel),
            Triple("原生多指手势", "nativeMultifinger", value.nativeMultifinger),
            Triple("触控板注入", "touchpadInjection", value.touchpadInjection)).forEach { (title, key, enabled) ->
            if (enabled == null) Text(s("$title：此设备未提供状态"))
            else {
                var checked by remember(value, key) { mutableStateOf(enabled) }
                Row {
                    Text(s(title), Modifier.weight(1f))
                    Switch(checked, onCheckedChange = {
                        checked = it
                        repository.command { core -> core.setInputOption(device.id, key, it) }
                    })
                }
            }
        }
        value.screens.forEach { screen ->
            Text(screen.name)
            var gain by remember(value, screen.screen) { mutableStateOf(screen.gain.toString()) }
            var remoteGain by remember(value, screen.screen) { mutableStateOf(screen.remoteGain.toString()) }
            OutlinedTextField(gain, onValueChange = { gain = it }, label = { Text(s("本机指针速度（0.1–5）")) })
            Button(enabled = gain.toDoubleOrNull()?.let { it in 0.1..5.0 } == true,
                onClick = { repository.command { it.setScreenGain(screen.screen, gain.toDouble(), false) } }) { Text(s("应用本机速度")) }
            OutlinedTextField(remoteGain, onValueChange = { remoteGain = it }, label = { Text(s("远程指针速度（0.1–5）")) })
            Button(enabled = remoteGain.toDoubleOrNull()?.let { it in 0.1..5.0 } == true,
                onClick = { repository.command { it.setScreenGain(screen.screen, remoteGain.toDouble(), true) } }) { Text(s("应用远程速度")) }
            TextButton(onClick = { repository.command { it.setScreenDisabled(device.id, !screen.disabled) } }) {
                Text(s(if (screen.disabled) "启用此设备屏幕" else "禁用此设备屏幕"))
            }
        }
        value.inputTransform?.let { original ->
            var transform by remember(value) { mutableStateOf(original) }
            TransformEditor(s("发送到 Mac"), transform.macos, device.platform, "macos") { transform = transform.copy(macos = it) }
            TransformEditor(s("发送到 Windows"), transform.windows, device.platform, "windows") { transform = transform.copy(windows = it) }
            transform.overrides.forEachIndexed { index, override ->
                Text(s("设备例外：${override.target.take(8)}"))
                override.reverseWheel?.let { reversed ->
                    Row {
                        Text(s("反向滚轮"), Modifier.weight(1f))
                        Switch(reversed, onCheckedChange = { next ->
                            transform = transform.copy(overrides = transform.overrides.mapIndexed { i, item ->
                                if (i == index) item.copy(reverseWheel = next) else item
                            })
                        })
                    }
                }
                TextButton(onClick = { transform = transform.copy(overrides = transform.overrides.filterIndexed { i, _ -> i != index }) }) { Text(s("移除此设备例外")) }
            }
            Button(onClick = { repository.command { it.setInputTransform(device.id, transform) } }) { Text(s("保存输入转换")) }
        } ?: Text(s("此设备未提供输入转换设置"))
    }
}

@Composable
private fun TransformEditor(title: String, value: TargetTransform, source: String, target: String, update: (TargetTransform) -> Unit) {
    val lang = LocalAppLang.current
    fun s(zh: String) = L10n.translate(zh, lang)
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row { Text(s("反向滚轮"), Modifier.weight(1f)); Switch(value.reverseWheel, onCheckedChange = { update(value.copy(reverseWheel = it)) }) }
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }) { Text(s("键盘：${value.keyboard.preset}")) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            KeyPreset.entries.forEach { preset -> DropdownMenuItem(text = { Text(preset.toString()) }, onClick = {
                menu = false
                val mapping = if (preset == KeyPreset.CUSTOM) value.keyboard.copy(preset = preset)
                    else presetMapping(value.keyboard, preset, source, target)
                update(value.copy(keyboard = mapping))
            }) }
        }
    }
    if (value.keyboard.preset == KeyPreset.CUSTOM) {
        var table by remember(value.keyboard.table) { mutableStateOf(value.keyboard.table.joinToString("\n") { "${it.from.toString(16)}=${it.to.toString(16)}" }) }
        var tap by remember(value.keyboard.capsTapHold) { mutableStateOf(value.keyboard.capsTapHold?.tap?.toString(16).orEmpty()) }
        var hold by remember(value.keyboard.capsTapHold) { mutableStateOf(value.keyboard.capsTapHold?.hold?.toString(16).orEmpty()) }
        OutlinedTextField(table, onValueChange = { table = it }, label = { Text(s("HID 键码映射（十六进制，每行 源=目标）")) })
        OutlinedTextField(tap, onValueChange = { tap = it }, label = { Text(s("Caps 短按目标（留空关闭）")) })
        OutlinedTextField(hold, onValueChange = { hold = it }, label = { Text(s("Caps 长按目标（十六进制）")) })
        val parsed = runCatching { table.lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('='); require(parts.size == 2)
            KeyMapping(parts[0].trim().toUInt(16), parts[1].trim().toUInt(16))
        }.toList() }.getOrNull()
        val caps = if (tap.isBlank() && hold.isBlank()) null else runCatching { CapsTapHold(tap.toUInt(16), hold.toUInt(16)) }.getOrNull()
        TextButton(enabled = parsed != null && ((tap.isBlank() && hold.isBlank()) || caps != null), onClick = {
            update(value.copy(keyboard = value.keyboard.copy(table = requireNotNull(parsed), capsTapHold = caps)))
        }) { Text(s("应用映射草稿")) }
    }
}

private fun presetMapping(previous: KeyboardMapping, preset: KeyPreset, source: String, target: String): KeyboardMapping {
    val acrossOs = (source == "macos" && target == "windows") || (source == "windows" && target == "macos")
    val mapping = if (!acrossOs) emptyList() else when (preset) {
        KeyPreset.MAC_FEEL -> listOf(0xe0u to 0xe3u, 0xe3u to 0xe0u, 0xe4u to 0xe7u, 0xe7u to 0xe4u)
        KeyPreset.WIN_FEEL -> listOf(0xe2u to 0xe3u, 0xe3u to 0xe2u, 0xe6u to 0xe7u, 0xe7u to 0xe6u)
        else -> emptyList()
    }
    return previous.copy(preset = preset, table = mapping.map { KeyMapping(it.first, it.second) }, capsTapHold = null)
}
