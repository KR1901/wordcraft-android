package com.wordcraft.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime smoke test: verifies the launcher Activity can be created on a real Android runtime.
 * This does not download or initialize the optional on-device language model.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @Test
    fun launcherActivityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assert(!activity.isFinishing)
                assert(!activity.isDestroyed)
            }
        }
    }
}
