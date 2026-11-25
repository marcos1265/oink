package com.hachiwii.oink.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hachiwii.oink.L10n
import com.hachiwii.oink.LocalAppLang
import com.hachiwii.oink.control.*
import kotlin.math.roundToInt

/**
 * Tracks active hold pointers and latched toggle buttons so multi-touch and duplicate bindings
 * keep key/mouse state balanced and release everything cleanly on exit.
 */
internal class OverlayInputTracker(
    private val send: (OverlayActionKind, Int, Boolean) -> Unit,
) {
    private val pointerHolds = LinkedHashMap<Pair<String, Long>, Pair<OverlayActionKind, Int>>()
    private val toggled = LinkedHashMap<String, Pair<OverlayActionKind, Int>>()
    var version by mutableIntStateOf(0)
        private set

    private fun countFor(target: Pair<OverlayActionKind, Int>): Int =
        pointerHolds.values.count { it == target } + toggled.values.count { it == target }

    fun pressPointer(button: OverlayButton, pointerId: Long) {
        val target = button.kind to button.code
        val key = button.id to pointerId
        if (pointerHolds.containsKey(key)) return
        val wasDown = countFor(target) > 0
        pointerHolds[key] = target
        if (!wasDown) send(target.first, target.second, true)
        version++
    }

    fun releasePointer(buttonId: String, pointerId: Long) {
        val target = pointerHolds.remove(buttonId to pointerId) ?: return
        if (countFor(target) == 0) send(target.first, target.second, false)
        version++
    }

    fun toggleButton(button: OverlayButton) {
        val target = button.kind to button.code
        val existing = toggled.remove(button.id)
        if (existing != null) {
            if (countFor(existing) == 0) send(existing.first, existing.second, false)
        } else {
            val wasDown = countFor(target) > 0
            toggled[button.id] = target
            if (!wasDown) send(target.first, target.second, true)
        }
        version++
    }

    fun isToggled(buttonId: String): Boolean = toggled.containsKey(buttonId)

    fun releaseAll() {
        val active = LinkedHashSet<Pair<OverlayActionKind, Int>>().apply {
            addAll(pointerHolds.values)
            addAll(toggled.values)
        }
        if (active.isEmpty()) return
        pointerHolds.clear()
        toggled.clear()
        active.forEach { (kind, code) -> send(kind, code, false) }
        version++
    }
}

@Composable
fun ControlOverlayLayer(holder: ControlHolder, modifier: Modifier = Modifier) {
    val holderState by rememberUpdatedState(holder)
    val tracker = remember {
        OverlayInputTracker { kind, code, down ->
            val h = holderState
            val s = h.session ?: return@OverlayInputTracker
            when (kind) {
                OverlayActionKind.MOUSE -> ControlButton.entries.getOrNull(code)?.let { btn ->
                    s.button(btn, down)
                    if (down) h.restartField?.invoke()
                }
                OverlayActionKind.KEY -> {
                    s.key(code, down)
                    if (down && !Hid.keepsCaret(code)) h.restartField?.invoke()
                }
            }
        }
    }

    LaunchedEffect(holder.overlayEnabled, holder.overlayEdit, holder.session, holder.active) {
        if (!holder.overlayEnabled || holder.overlayEdit || !holder.active || holder.session == null) {
            tracker.releaseAll()
        }
    }
    DisposableEffect(tracker) {
        onDispose { tracker.releaseAll() }
    }

    val config = holder.overlayConfig
    val profile = config.activeProfile
    var selectedId by remember { mutableStateOf<String?>(null) }
    var bindPickerFor by remember { mutableStateOf<String?>(null) }
    var showProfiles by remember { mutableStateOf(false) }

    if (holder.overlayEdit) {
        BackHandler {
            when {
                bindPickerFor != null -> bindPickerFor = null
                showProfiles -> showProfiles = false
                selectedId != null -> selectedId = null
                else -> holder.overlayEdit = false
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val boxH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        if (holder.overlayEdit) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.24f))
                    .pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown().consume(); selectedId = null }
                    }
            )
        }

        profile.buttons.forEach { btn ->
            key(btn.id) {
                if (holder.overlayEdit) {
                    EditOverlayButton(
                        button = btn,
                        selected = selectedId == btn.id,
                        opacity = config.opacity,
                        boxW = boxW,
                        boxH = boxH,
                        onSelect = { selectedId = btn.id },
                        onMoved = { nx, ny ->
                            holder.updateOverlay { it.upsertButton(btn.copy(x = nx, y = ny)) }
                        },
                    )
                } else {
                    PlayOverlayButton(
                        button = btn,
                        opacity = config.opacity,
                        tracker = tracker,
                        boxW = boxW,
                        boxH = boxH,
                    )
                }
            }
        }

        if (holder.overlayEdit) {
            OverlayEditToolbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 12.dp, end = 12.dp),
                profileName = profile.name,
                onAdd = {
                    val created = OverlayBindings.newButton(
                        x = (0.45f + (profile.buttons.size % 4) * 0.05f).coerceIn(0.2f, 0.8f),
                        y = (0.45f + ((profile.buttons.size / 4) % 3) * 0.06f).coerceIn(0.2f, 0.8f),
                    )
                    holder.updateOverlay { it.upsertButton(created) }
                    selectedId = created.id
                },
                onProfiles = { showProfiles = true },
                onDone = {
                    selectedId = null
                    holder.overlayEdit = false
                },
                onDisable = {
                    selectedId = null
                    holder.enableOverlay(false)
                },
            )

            val selectedButton = selectedId?.let { id -> profile.buttons.firstOrNull { it.id == id } }
            if (selectedButton != null) {
                OverlayButtonInspector(
                    button = selectedButton,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    onUpdate = { next -> holder.updateOverlay { it.upsertButton(next) } },
                    onOpenBind = { bindPickerFor = selectedButton.id },
                    onDelete = {
                        holder.updateOverlay { it.removeButton(selectedButton.id) }
                        selectedId = null
                    },
                )
            }
        }
    }

    val editingBtn = bindPickerFor?.let { id -> profile.buttons.firstOrNull { it.id == id } }
    if (editingBtn != null) {
        BindPickerDialog(
            current = editingBtn,
            onPick = { choice ->
                holder.updateOverlay {
                    it.upsertButton(
                        editingBtn.copy(
                            kind = choice.kind,
                            code = choice.code,
                            label = choice.shortLabel,
                        )
                    )
                }
                bindPickerFor = null
            },
            onDismiss = { bindPickerFor = null },
        )
    }

    if (showProfiles) {
        ProfileManagerDialog(
            config = config,
            onUpdate = { transform -> holder.updateOverlay(transform) },
            onDismiss = { showProfiles = false },
        )
    }
}

@Composable
private fun PlayOverlayButton(
    button: OverlayButton,
    opacity: Float,
    tracker: OverlayInputTracker,
    boxW: Float,
    boxH: Float,
) {
    val c = Oink.colors
    val view = LocalView.current
    val density = LocalDensity.current
    val widthDp = if (button.shape == OverlayShape.PILL) (button.sizeDp * 1.45f).dp else button.sizeDp.dp
    val heightDp = button.sizeDp.dp
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }
    val left = (button.x * boxW - wPx / 2f).roundToInt().coerceIn(0, (boxW - wPx).roundToInt().coerceAtLeast(0))
    val top = (button.y * boxH - hPx / 2f).roundToInt().coerceIn(0, (boxH - hPx).roundToInt().coerceAtLeast(0))

    var pressedCount by remember(button.id) { mutableIntStateOf(0) }
    val latched = (tracker.version >= 0) && button.toggle && tracker.isToggled(button.id)
    val lit = pressedCount > 0 || latched
    val shape = if (button.shape == OverlayShape.CIRCLE) CircleShape else RoundedCornerShape(50)
    val bg = if (lit) c.accentSoft2.copy(alpha = (opacity + 0.18f).coerceAtMost(1f))
        else c.elev.copy(alpha = (opacity * 0.85f).coerceIn(0.15f, 0.96f))
    val border = if (lit) c.accent else c.border.copy(alpha = (opacity + 0.1f).coerceAtMost(1f))
    val ink = if (lit) c.accentInk else c.ink.copy(alpha = (opacity + 0.2f).coerceAtMost(1f))

    val currentButton by rememberUpdatedState(button)
    val lang = LocalAppLang.current
    val desc = L10n.translate(OverlayBindings.fullLabel(button.kind, button.code), lang)

    Box(
        Modifier
            .offset { IntOffset(left, top) }
            .size(widthDp, heightDp)
            .clip(shape)
            .background(bg)
            .border(if (lit) 1.5.dp else 1.dp, border, shape)
            .pointerInput(button.id, button.toggle) {
                val activeIds = LinkedHashSet<Long>()
                try {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        first.consume()
                        val b = currentButton
                        if (activeIds.add(first.id.value)) {
                            pressedCount = activeIds.size
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (!b.toggle) tracker.pressPointer(b, first.id.value)
                        }
                        while (activeIds.isNotEmpty()) {
                            val event = awaitPointerEvent()
                            val cur = currentButton
                            for (change in event.changes) {
                                val id = change.id.value
                                when {
                                    change.pressed && id !in activeIds -> {
                                        change.consume()
                                        activeIds += id
                                        pressedCount = activeIds.size
                                        if (!cur.toggle) tracker.pressPointer(cur, id)
                                    }
                                    !change.pressed && id in activeIds -> {
                                        change.consume()
                                        activeIds -= id
                                        pressedCount = activeIds.size
                                        if (cur.toggle) tracker.toggleButton(cur)
                                        else tracker.releasePointer(cur.id, id)
                                    }
                                    else -> if (id in activeIds) change.consume()
                                }
                            }
                            if (event.type == PointerEventType.Exit) {
                                val cur = currentButton
                                activeIds.forEach { if (!cur.toggle) tracker.releasePointer(cur.id, it) }
                                activeIds.clear()
                                pressedCount = 0
                            }
                        }
                    }
                } finally {
                    val cur = currentButton
                    activeIds.forEach { tracker.releasePointer(cur.id, it) }
                    activeIds.clear()
                    pressedCount = 0
                }
            }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        val textSize = (button.sizeDp * 0.25f).coerceIn(11f, 16f)
        T(button.label, textSize, ink, weight = 600, maxLines = 1, ellipsis = true, raw = true)
    }
}

@Composable
private fun EditOverlayButton(
    button: OverlayButton,
    selected: Boolean,
    opacity: Float,
    boxW: Float,
    boxH: Float,
    onSelect: () -> Unit,
    onMoved: (Float, Float) -> Unit,
) {
    val c = Oink.colors
    val density = LocalDensity.current
    val widthDp = if (button.shape == OverlayShape.PILL) (button.sizeDp * 1.45f).dp else button.sizeDp.dp
    val heightDp = button.sizeDp.dp
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }

    var dragX by remember(button.id, button.x) { mutableFloatStateOf(button.x) }
    var dragY by remember(button.id, button.y) { mutableFloatStateOf(button.y) }
    val currentOnMove by rememberUpdatedState(onMoved)
    val currentOnSelect by rememberUpdatedState(onSelect)

    val left = (dragX * boxW - wPx / 2f).roundToInt().coerceIn(0, (boxW - wPx).roundToInt().coerceAtLeast(0))
    val top = (dragY * boxH - hPx / 2f).roundToInt().coerceIn(0, (boxH - hPx).roundToInt().coerceAtLeast(0))
    val shape = if (button.shape == OverlayShape.CIRCLE) CircleShape else RoundedCornerShape(50)

    Box(
        Modifier
            .offset { IntOffset(left, top) }
            .size(widthDp, heightDp)
            .clip(shape)
            .background(if (selected) c.accentSoft2 else c.elev.copy(alpha = (opacity + 0.14f).coerceAtMost(0.96f)))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.outline, shape)
            .pointerInput(button.id, boxW, boxH) {
                detectDragGestures(
                    onDragStart = { currentOnSelect() },
                    onDragEnd = { currentOnMove(dragX, dragY) },
                    onDragCancel = { currentOnMove(dragX, dragY) },
                ) { change, dragAmount ->
                    change.consume()
                    dragX = (dragX + dragAmount.x / boxW).coerceIn(0.04f, 0.96f)
                    dragY = (dragY + dragAmount.y / boxH).coerceIn(0.04f, 0.96f)
                }
            }
            .press(shape = shape, pressedColor = c.press) { onSelect() },
        contentAlignment = Alignment.Center,
    ) {
        val textSize = (button.sizeDp * 0.25f).coerceIn(11f, 16f)
        T(button.label, textSize, if (selected) c.accentInk else c.ink, weight = 600, maxLines = 1, ellipsis = true, raw = true)
    }
}

@Composable
private fun OverlayEditToolbar(
    modifier: Modifier,
    profileName: String,
    onAdd: () -> Unit,
    onProfiles: () -> Unit,
    onDone: () -> Unit,
    onDisable: () -> Unit,
) {
    val c = Oink.colors
    Row(
        modifier
            .boxShadow(c.shadow, 4.dp, 16.dp, 24.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.elev)
            .border(1.dp, c.border, RoundedCornerShape(24.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarChip("添加按键", accent = true, onClick = onAdd)
        ToolbarChip(profileName, raw = true, onClick = onProfiles)
        ToolbarChip("完成", accent = true, onClick = onDone)
        ToolbarChip("关闭按键映射", danger = true, onClick = onDisable)
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    accent: Boolean = false,
    danger: Boolean = false,
    raw: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Oink.colors
    val bg = when {
        danger -> c.badSoft
        accent -> c.accentSoft
        else -> c.chip
    }
    val ink = when {
        danger -> c.badInk
        accent -> c.accentInk
        else -> c.text2
    }
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(bg)
            .press(pressedColor = c.press) { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, 12.5f, ink, weight = 600, maxLines = 1, raw = raw)
    }
}

@Composable
private fun OverlayButtonInspector(
    button: OverlayButton,
    modifier: Modifier = Modifier,
    onUpdate: (OverlayButton) -> Unit,
    onOpenBind: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Oink.colors
    var labelText by remember(button.id, button.label) { mutableStateOf(button.label) }
    Column(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .boxShadow(c.shadow, 6.dp, 20.dp, 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.elev)
            .border(1.dp, c.border, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolbarChip("绑定按键", accent = true, onClick = onOpenBind)
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.field)
                    .border(1.dp, c.track, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = labelText,
                    onValueChange = {
                        val trimmed = it.take(12)
                        labelText = trimmed
                        onUpdate(button.copy(label = trimmed.ifEmpty { OverlayBindings.shortLabel(button.kind, button.code) }))
                    },
                    singleLine = true,
                    textStyle = textStyle(13f, c.ink),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ToolbarChip("删除", danger = true, onClick = onDelete)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("形状", 12.5f, c.muted)
            OptionPill("圆形", button.shape == OverlayShape.CIRCLE) { onUpdate(button.copy(shape = OverlayShape.CIRCLE)) }
            OptionPill("胶囊", button.shape == OverlayShape.PILL) { onUpdate(button.copy(shape = OverlayShape.PILL)) }
            Spacer(Modifier.weight(1f))
            T("${button.sizeDp.roundToInt()}dp", 12f, c.muted, tabular = true, raw = true)
            SmallStepButton("-") { onUpdate(button.copy(sizeDp = (button.sizeDp - 6f).coerceIn(36f, 96f))) }
            SmallStepButton("+") { onUpdate(button.copy(sizeDp = (button.sizeDp + 6f).coerceIn(36f, 96f))) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("触发方式", 12.5f, c.muted)
            OptionPill("按住触发", !button.toggle) { onUpdate(button.copy(toggle = false)) }
            OptionPill("切换开关", button.toggle) { onUpdate(button.copy(toggle = true)) }
        }
    }
}

@Composable
private fun OptionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Oink.colors
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) c.accentSoft else c.chip)
            .press(pressedColor = c.press, role = Role.RadioButton) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, 12f, if (selected) c.accentInk else c.text2, weight = if (selected) 600 else 400, maxLines = 1)
    }
}

@Composable
private fun SmallStepButton(symbol: String, onClick: () -> Unit) {
    val c = Oink.colors
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(c.chip)
            .press(pressedColor = c.press) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        T(symbol, 14f, c.ink, weight = 600, raw = true)
    }
}

@Composable
private fun BindPickerDialog(
    current: OverlayButton,
    onPick: (OverlayBindings.ActionChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Oink.colors
    OinkDialog("绑定按键", onDismiss, listOf(DialogAction("取消") { onDismiss() })) {
        T("选择要绑定的键位或鼠标按键", 13f, c.muted, Modifier.padding(bottom = 10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OverlayBindings.categories.forEach { category ->
                val items = OverlayBindings.choices.filter { it.category == category }
                T(category, 12f, c.accentInk, weight = 600)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.card),
                ) {
                    items.forEachIndexed { idx, choice ->
                        val selected = current.kind == choice.kind && current.code == choice.code
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .press(pressedColor = c.press, role = Role.RadioButton) { onPick(choice) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OinkRadio(selected)
                            T(choice.label, 14f, c.ink, Modifier.weight(1f))
                            T(choice.shortLabel, 12f, c.muted, raw = true)
                        }
                        if (idx < items.lastIndex) {
                            Box(Modifier.fillMaxWidth().padding(start = 12.dp).height(1.dp).background(c.line))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileManagerDialog(
    config: OverlayConfig,
    onUpdate: ((OverlayConfig) -> OverlayConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Oink.colors
    var newName by remember { mutableStateOf("") }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    OinkDialog("配置方案", onDismiss, listOf(DialogAction("完成") { onDismiss() })) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("透明度", 14f, c.ink, Modifier.weight(1f))
                T("${(config.opacity * 100).roundToInt()}%", 13f, c.accentInk, weight = 600, tabular = true, raw = true)
                SmallStepButton("-") { onUpdate { it.withOpacity(it.opacity - 0.1f) } }
                SmallStepButton("+") { onUpdate { it.withOpacity(it.opacity + 0.1f) } }
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)) {
                config.profiles.forEachIndexed { i, p ->
                    val selected = p.id == config.activeProfile.id
                    if (renamingId == p.id) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.field)
                                    .border(1.dp, c.track, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it.take(24) },
                                    singleLine = true,
                                    textStyle = textStyle(14f, c.ink),
                                    cursorBrush = SolidColor(c.accent),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            ToolbarChip("保存", accent = true) {
                                onUpdate { it.renameProfile(p.id, renameText) }
                                renamingId = null
                            }
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .press(pressedColor = c.press, role = Role.RadioButton) {
                                    onUpdate { it.withActiveProfile(p.id) }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OinkRadio(selected)
                            T(p.name, 14.5f, c.ink, Modifier.weight(1f), raw = true)
                            ToolbarChip("重命名") {
                                renamingId = p.id
                                renameText = p.name
                            }
                            if (config.profiles.size > 1) {
                                ToolbarChip("删除", danger = true) {
                                    onUpdate { it.deleteProfile(p.id) }
                                }
                            }
                        }
                    }
                    if (i < config.profiles.lastIndex) {
                        Box(Modifier.fillMaxWidth().padding(start = 12.dp).height(1.dp).background(c.line))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.field)
                        .border(1.dp, c.track, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (newName.isEmpty()) T("方案名称", 13.5f, c.faint, align = TextAlign.Start)
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it.take(24) },
                        singleLine = true,
                        textStyle = textStyle(14f, c.ink),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ToolbarChip("新建方案", accent = true) {
                    onUpdate { it.addProfile(newName, basedOnActive = true) }
                    newName = ""
                }
            }
        }
    }
}
