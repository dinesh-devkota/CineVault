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

/** Exercises actual remote input and focus on Android TV, without a touchscreen. */
@RunWith(AndroidJUnit4::class)
class TVNavigationTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun remoteCanReachEveryTabAndReturnHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
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
            visible("Your movie collection starts here")
            repeat(3) { device.pressDPadRight() }
            visible("No NAS sources yet")
            activity.recreate()
            visible("No NAS sources yet")
            device.pressDPadRight()
            visible("Metadata (themoviedb.org)")
        }
    }

    private fun visible(text: String) {
        assertTrue("Expected TV screen text: $text", device.wait(Until.hasObject(By.text(text)), 10_000))
        device.waitForIdle()
    }
}
