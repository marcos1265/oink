package com.hachiwii.oink

import com.hachiwii.oink.control.*
import com.hachiwii.oink.ui.OverlayInputTracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OverlayPrefsTest {
    @Test
    fun defaultConfigHasFpsAndBasicProfilesWithinBounds() {
        val cfg = OverlayPrefs.defaultConfig()
        assertEquals("fps", cfg.activeProfileId)
        assertTrue(cfg.profiles.any { it.id == "fps" && it.buttons.isNotEmpty() })
        assertTrue(cfg.profiles.any { it.id == "basic" && it.buttons.isNotEmpty() })
        cfg.profiles.flatMap { it.buttons }.forEach { b ->
            assertTrue(b.x in 0.04f..0.96f)
            assertTrue(b.y in 0.04f..0.96f)
            assertTrue(b.sizeDp in 36f..96f)
        }
    }

    @Test
    fun encodeDecodeRoundTripAndCorruptJsonFallback() {
        val custom = OverlayPrefs.defaultConfig()
            .withOpacity(0.55f)
            .addProfile("Custom", basedOnActive = false)
            .upsertButton(OverlayButton("b1", "Fire", OverlayActionKind.MOUSE, ControlButton.LEFT.ordinal, 0.8f, 0.6f, 64f, OverlayShape.PILL, toggle = true))

        val decoded = OverlayPrefs.decode(OverlayPrefs.encode(custom))
        assertEquals(custom.activeProfileId, decoded.activeProfileId)
        assertEquals(0.55f, decoded.opacity, 0.001f)
        assertEquals(3, decoded.profiles.size)
        val btn = decoded.activeProfile.buttons.single()
        assertEquals("Fire", btn.label)
        assertEquals(OverlayActionKind.MOUSE, btn.kind)
        assertEquals(ControlButton.LEFT.ordinal, btn.code)
        assertEquals(OverlayShape.PILL, btn.shape)
        assertTrue(btn.toggle)

        // Corrupt JSON falls back cleanly
        val fallback = OverlayPrefs.decode("{not-json")
        assertEquals("fps", fallback.activeProfileId)
    }

    @Test
    fun profileAndButtonCrudMaintainsInvariants() {
        val start = OverlayPrefs.defaultConfig().withActiveProfile("basic")
        assertEquals("basic", start.activeProfile.id)

        val renamed = start.renameProfile("basic", "Desktop Keys")
        assertEquals("Desktop Keys", renamed.activeProfile.name)

        // Deleting down to last profile stops at 1 profile
        val oneLeft = renamed.deleteProfile("fps")
        assertEquals(1, oneLeft.profiles.size)
        val unchanged = oneLeft.deleteProfile("basic")
        assertEquals(1, unchanged.profiles.size)

        // Button position and size are clamped
        val added = unchanged.upsertButton(OverlayButton("clamp", "X", OverlayActionKind.KEY, Hid.A, 2.5f, -1f, 200f))
        val clamped = added.activeProfile.buttons.first { it.id == "clamp" }
        assertEquals(0.96f, clamped.x, 0.001f)
        assertEquals(0.04f, clamped.y, 0.001f)
        assertEquals(96f, clamped.sizeDp, 0.001f)

        val removed = added.removeButton("clamp")
        assertFalse(removed.activeProfile.buttons.any { it.id == "clamp" })
    }

    @Test
    fun overlayInputTrackerRefCountsSharedBindingsAndReleasesAll() {
        val events = ArrayList<Triple<OverlayActionKind, Int, Boolean>>()
        val tracker = OverlayInputTracker { kind, code, down -> events += Triple(kind, code, down) }

        val shiftHold = OverlayButton("s1", "Shift", OverlayActionKind.KEY, Hid.L_SHIFT, 0.2f, 0.2f, toggle = false)
        val shiftToggle = OverlayButton("s2", "Shift", OverlayActionKind.KEY, Hid.L_SHIFT, 0.3f, 0.3f, toggle = true)
        val fireMouse = OverlayButton("m1", "LMB", OverlayActionKind.MOUSE, ControlButton.LEFT.ordinal, 0.8f, 0.7f, toggle = false)

        // Two controls share L_SHIFT: down emitted once, up only when both release
        tracker.pressPointer(shiftHold, 1L)
        tracker.toggleButton(shiftToggle)
        assertEquals(listOf(Triple(OverlayActionKind.KEY, Hid.L_SHIFT, true)), events)
        assertTrue(tracker.isToggled("s2"))

        tracker.releasePointer("s1", 1L)
        assertEquals(1, events.size) // still latched by s2

        tracker.pressPointer(fireMouse, 2L)
        assertEquals(2, events.size)

        // Leaving/disabling releases all active holds & toggles cleanly
        tracker.releaseAll()
        assertFalse(tracker.isToggled("s2"))
        assertTrue(events.contains(Triple(OverlayActionKind.KEY, Hid.L_SHIFT, false)))
        assertTrue(events.contains(Triple(OverlayActionKind.MOUSE, ControlButton.LEFT.ordinal, false)))

        // Extra releaseAll is a no-op
        val before = events.size
        tracker.releaseAll()
        assertEquals(before, events.size)
    }
}
