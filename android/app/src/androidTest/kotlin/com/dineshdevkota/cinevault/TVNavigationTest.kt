package com.dineshdevkota.cinevault

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Exercises actual remote input and focus on Android TV, without a touchscreen. */
@RunWith(AndroidJUnit4::class)
class TVNavigationTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val focusSnapshots = mutableListOf<String>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Test
    fun remoteCanReachEveryTabAndReturnHome() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            scenario = activity
            visible("Your movie collection starts here")
            device.pressDPadRight()
            visible("No movies yet")
            device.pressDPadRight()
            visible("Search your collection")
            device.pressDPadRight()
            visible("No NAS sources yet")
            device.pressDPadRight()
            visible("Metadata (themoviedb.org)")
            device.pressBack()
            visible("Your movie collection starts here")
        }
    }

    @Test
    fun selectedTabAndFocusSurviveActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            scenario = activity
            visible("Your movie collection starts here")
            repeat(3) { device.pressDPadRight() }
            visible("No NAS sources yet")
            activity.recreate()
            visible("No NAS sources yet")
            device.pressDPadRight()
            visible("Metadata (themoviedb.org)")
        }
    }

    private fun captureFocus(stage: String) {
        var window = ""
        scenario?.onActivity {
            window = " windowFocused=${it.window.decorView.hasWindowFocus()} lifecycle=${it.lifecycle.currentState}" +
                " viewFocus=${it.currentFocus?.javaClass?.simpleName} touchMode=${it.window.decorView.isInTouchMode}"
        }
        val xml = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString("UTF-8")
        val relevant = Regex("<node\\b[^>]*>").findAll(xml).map { it.value }.filter { node ->
            node.contains("focused=\"true\"") || node.contains("selected=\"true\"") ||
                listOf("Home", "Movies", "Search", "Sources", "Settings").any { node.contains("text=\"$it\"") }
        }.map { node ->
            val attributes = Regex("([\\w-]+)=\"([^\"]*)\"").findAll(node)
                .associate { it.groupValues[1] to it.groupValues[2] }
            listOf("text", "content-desc", "focused", "selected", "focusable", "bounds")
                .joinToString(",") { "$it=${attributes[it].orEmpty()}" }
        }.joinToString(" | ")
        focusSnapshots += "$stage package=${device.currentPackageName}$window [$relevant]"
    }

    private fun visible(text: String) {
        val found = device.wait(Until.hasObject(By.text(text)), 10_000)
        if (!found) captureFocus("assertion timeout")
        assertTrue("Expected TV screen text: $text\n${focusSnapshots.joinToString("\n").take(3_600)}", found)
        device.waitForIdle()
    }
}
