package com.phloots.busjam

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the Robolectric + Compose UI test setup. UI agent may replace/extend. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun launches() {
        rule.waitUntilAtLeastOneExists(hasContentDescription("Critter Café"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Critter Café").assertIsDisplayed()
    }
}
