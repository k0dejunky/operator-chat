package com.amethyst2213.operatorchat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.schedule.ScheduleScreen
import com.amethyst2213.operatorchat.ui.schedule.ScheduleViewModel
import com.amethyst2213.operatorchat.ui.theme.StudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduleUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an upcoming show renders with its countdown and duration`() {
        compose.setContent {
            StudioTheme {
                ScheduleScreen(
                    state = ScheduleViewModel.Ui(
                        loading = false,
                        shows = listOf(
                            LiveToolsBridge.Show(
                                id = 4,
                                title = "Friday night live",
                                description = "New set, live chat.",
                                startsAt = "2026-11-20 20:00",
                                startsAtUtc = "2026-11-21 03:00:00",
                                duration = 90,
                                status = "scheduled",
                                seconds = 5 * 86400L,
                            ),
                        ),
                        siteZone = "America/Denver",
                    ),
                    onRefresh = {},
                    onSchedule = { _, _, _, _ -> },
                    onCancel = {},
                    onClearError = {},
                )
            }
        }
        compose.onNodeWithText("Friday night live").assertIsDisplayed()
        compose.onNodeWithText("2026-11-20 20:00").assertIsDisplayed()
        compose.onNodeWithText("90 min").assertIsDisplayed()
        compose.onNodeWithText("in 5d 0h").assertIsDisplayed()
    }

    @Test
    fun `an empty schedule explains what a show does`() {
        compose.setContent {
            StudioTheme {
                ScheduleScreen(
                    state = ScheduleViewModel.Ui(loading = false),
                    onRefresh = {},
                    onSchedule = { _, _, _, _ -> },
                    onCancel = {},
                    onClearError = {},
                )
            }
        }
        compose.onNodeWithText("No shows scheduled").assertIsDisplayed()
        compose.onNodeWithText("Schedule the next show").assertIsDisplayed()
    }
}
