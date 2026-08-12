package com.openminis.app.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class BrowserActionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // BrowserAction enum tests
    @Test
    fun `browserAction has correct values`() {
        assertEquals("navigate", BrowserAction.NAVIGATE.value)
        assertEquals("screenshot", BrowserAction.SCREENSHOT.value)
        assertEquals("click", BrowserAction.CLICK.value)
        assertEquals("type", BrowserAction.TYPE.value)
        assertEquals("get_text", BrowserAction.GET_TEXT.value)
        assertEquals("scroll", BrowserAction.SCROLL.value)
        assertEquals("get_page_info", BrowserAction.GET_PAGE_INFO.value)
        assertEquals("execute_js", BrowserAction.EXECUTE_JS.value)
        assertEquals("find_elements", BrowserAction.FIND_ELEMENTS.value)
        assertEquals("hover", BrowserAction.HOVER.value)
        assertEquals("get_readable", BrowserAction.GET_READABLE.value)
        assertEquals("set_user_agent", BrowserAction.SET_USER_AGENT.value)
        assertEquals("set_viewport", BrowserAction.SET_VIEWPORT.value)
        assertEquals("get_backbone", BrowserAction.GET_BACKBONE.value)
        assertEquals("fetch", BrowserAction.FETCH.value)
        assertEquals("new_tab", BrowserAction.NEW_TAB.value)
        assertEquals("close_tab", BrowserAction.CLOSE_TAB.value)
        assertEquals("list_tabs", BrowserAction.LIST_TABS.value)
        assertEquals("get_cookies", BrowserAction.GET_COOKIES.value)
        assertEquals("set_cookies", BrowserAction.SET_COOKIES.value)
        assertEquals("scroll_and_collect", BrowserAction.SCROLL_AND_COLLECT.value)
        assertEquals("wait_for_dom_stable", BrowserAction.WAIT_FOR_DOM_STABLE.value)
    }

    @Test
    fun `browserAction fromString returns correct action`() {
        assertEquals(BrowserAction.NAVIGATE, BrowserAction.fromString("navigate"))
        assertEquals(BrowserAction.SCREENSHOT, BrowserAction.fromString("screenshot"))
        assertEquals(BrowserAction.CLICK, BrowserAction.fromString("click"))
        assertEquals(BrowserAction.TYPE, BrowserAction.fromString("type"))
        assertEquals(BrowserAction.GET_TEXT, BrowserAction.fromString("get_text"))
        assertEquals(BrowserAction.SCROLL, BrowserAction.fromString("scroll"))
        assertEquals(BrowserAction.GET_PAGE_INFO, BrowserAction.fromString("get_page_info"))
        assertEquals(BrowserAction.EXECUTE_JS, BrowserAction.fromString("execute_js"))
        assertEquals(BrowserAction.FIND_ELEMENTS, BrowserAction.fromString("find_elements"))
        assertEquals(BrowserAction.HOVER, BrowserAction.fromString("hover"))
        assertEquals(BrowserAction.GET_READABLE, BrowserAction.fromString("get_readable"))
        assertEquals(BrowserAction.SET_USER_AGENT, BrowserAction.fromString("set_user_agent"))
        assertEquals(BrowserAction.SET_VIEWPORT, BrowserAction.fromString("set_viewport"))
        assertEquals(BrowserAction.GET_BACKBONE, BrowserAction.fromString("get_backbone"))
        assertEquals(BrowserAction.FETCH, BrowserAction.fromString("fetch"))
        assertEquals(BrowserAction.NEW_TAB, BrowserAction.fromString("new_tab"))
        assertEquals(BrowserAction.CLOSE_TAB, BrowserAction.fromString("close_tab"))
        assertEquals(BrowserAction.LIST_TABS, BrowserAction.fromString("list_tabs"))
        assertEquals(BrowserAction.GET_COOKIES, BrowserAction.fromString("get_cookies"))
        assertEquals(BrowserAction.SET_COOKIES, BrowserAction.fromString("set_cookies"))
        assertEquals(BrowserAction.SCROLL_AND_COLLECT, BrowserAction.fromString("scroll_and_collect"))
        assertEquals(BrowserAction.WAIT_FOR_DOM_STABLE, BrowserAction.fromString("wait_for_dom_stable"))
    }

    @Test
    fun `browserAction fromString returns null for invalid input`() {
        assertNull(BrowserAction.fromString("invalid_action"))
        assertNull(BrowserAction.fromString(""))
        assertNull(BrowserAction.fromString(" "))
    }

    @Test
    fun `browserAction opensNewPage is true only for NAVIGATE`() {
        assertTrue(BrowserAction.NAVIGATE.opensNewPage)
        assertFalse(BrowserAction.SCREENSHOT.opensNewPage)
        assertFalse(BrowserAction.CLICK.opensNewPage)
        assertFalse(BrowserAction.TYPE.opensNewPage)
        assertFalse(BrowserAction.GET_TEXT.opensNewPage)
        assertFalse(BrowserAction.SCROLL.opensNewPage)
        assertFalse(BrowserAction.GET_PAGE_INFO.opensNewPage)
        assertFalse(BrowserAction.EXECUTE_JS.opensNewPage)
        assertFalse(BrowserAction.FIND_ELEMENTS.opensNewPage)
        assertFalse(BrowserAction.HOVER.opensNewPage)
        assertFalse(BrowserAction.GET_READABLE.opensNewPage)
        assertFalse(BrowserAction.SET_USER_AGENT.opensNewPage)
        assertFalse(BrowserAction.SET_VIEWPORT.opensNewPage)
        assertFalse(BrowserAction.GET_BACKBONE.opensNewPage)
        assertFalse(BrowserAction.FETCH.opensNewPage)
        assertFalse(BrowserAction.NEW_TAB.opensNewPage)
        assertFalse(BrowserAction.CLOSE_TAB.opensNewPage)
        assertFalse(BrowserAction.LIST_TABS.opensNewPage)
        assertFalse(BrowserAction.GET_COOKIES.opensNewPage)
        assertFalse(BrowserAction.SET_COOKIES.opensNewPage)
        assertFalse(BrowserAction.SCROLL_AND_COLLECT.opensNewPage)
        assertFalse(BrowserAction.WAIT_FOR_DOM_STABLE.opensNewPage)
    }

    @Test
    fun `browserAction visualChangeActions contains correct actions`() {
        assertEquals(
            setOf(BrowserAction.NAVIGATE, BrowserAction.CLICK, BrowserAction.SCROLL, BrowserAction.HOVER, BrowserAction.TYPE),
            BrowserAction.visualChangeActions
        )
    }

    @Test
    fun `browserAction opensNewPageActions contains correct actions`() {
        assertEquals(setOf(BrowserAction.NAVIGATE), BrowserAction.opensNewPageActions)
    }

    @Test
    fun `browserAction allValues contains all action values`() {
        assertEquals(
            listOf(
                "navigate", "screenshot", "click", "type", "get_text", "scroll",
                "get_page_info", "execute_js", "find_elements", "hover", "get_readable",
                "set_user_agent", "set_viewport", "get_backbone", "fetch", "new_tab",
                "close_tab", "list_tabs", "get_cookies", "set_cookies", "scroll_and_collect",
                "wait_for_dom_stable"
            ),
            BrowserAction.allValues
        )
    }

    @ParameterizedTest
    @EnumSource(BrowserAction::class)
    fun `browserAction all actions have non-empty values`(action: BrowserAction) {
        assertTrue(action.value.isNotBlank())
    }

    // ScrollDirection enum tests
    @Test
    fun `scrollDirection has correct values`() {
        assertEquals("up", ScrollDirection.UP.value)
        assertEquals("down", ScrollDirection.DOWN.value)
    }

    @Test
    fun `scrollDirection fromString returns correct direction`() {
        assertEquals(ScrollDirection.UP, ScrollDirection.fromString("up"))
        assertEquals(ScrollDirection.DOWN, ScrollDirection.fromString("down"))
    }

    @Test
    fun `scrollDirection fromString returns null for invalid input`() {
        assertNull(ScrollDirection.fromString("invalid"))
        assertNull(ScrollDirection.fromString(""))
        assertNull(ScrollDirection.fromString("left"))
        assertNull(ScrollDirection.fromString("right"))
    }

    @Test
    fun `scrollDirection has exactly two values`() {
        assertEquals(2, ScrollDirection.entries.size)
    }

    // UserAgentProfile enum tests
    @Test
    fun `userAgentProfile has correct values`() {
        assertEquals("mobile_chrome", UserAgentProfile.MOBILE_CHROME.value)
        assertEquals("desktop_chrome", UserAgentProfile.DESKTOP_CHROME.value)
        assertEquals("custom", UserAgentProfile.CUSTOM.value)
    }

    @Test
    fun `userAgentProfile fromString returns correct profile`() {
        assertEquals(UserAgentProfile.MOBILE_CHROME, UserAgentProfile.fromString("mobile_chrome"))
        assertEquals(UserAgentProfile.DESKTOP_CHROME, UserAgentProfile.fromString("desktop_chrome"))
        assertEquals(UserAgentProfile.CUSTOM, UserAgentProfile.fromString("custom"))
    }

    @Test
    fun `userAgentProfile fromString returns null for invalid input`() {
        assertNull(UserAgentProfile.fromString("invalid"))
        assertNull(UserAgentProfile.fromString(""))
        assertNull(UserAgentProfile.fromString("desktop"))
        assertNull(UserAgentProfile.fromString("mobile"))
    }

    @Test
    fun `userAgentProfile userAgentString is correct`() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
            UserAgentProfile.MOBILE_CHROME.userAgentString
        )
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            UserAgentProfile.DESKTOP_CHROME.userAgentString
        )
        assertNull(UserAgentProfile.CUSTOM.userAgentString)
    }

    @Test
    fun `userAgentProfile displayName is correct`() {
        assertEquals("Mobile Chrome", UserAgentProfile.MOBILE_CHROME.displayName)
        assertEquals("Desktop Chrome", UserAgentProfile.DESKTOP_CHROME.displayName)
        assertEquals("Custom", UserAgentProfile.CUSTOM.displayName)
    }

    @Test
    fun `userAgentProfile viewportSize is correct`() {
        assertEquals(1280 to 800, UserAgentProfile.DESKTOP_CHROME.viewportSize)
        assertEquals(412 to 915, UserAgentProfile.MOBILE_CHROME.viewportSize)
        assertEquals(412 to 915, UserAgentProfile.CUSTOM.viewportSize)
    }

    @Test
    fun `userAgentProfile has exactly three values`() {
        assertEquals(3, UserAgentProfile.entries.size)
    }

    // Compose UI tests (though these enums don't have Composable functions,
    // we'll create test composables to verify basic rendering and interaction)
    @Test
    fun `browserAction can be rendered in composable`() {
        composeTestRule.setContent {
            BrowserAction.NAVIGATE.value.let { actionName ->
                androidx.compose.material.Text(text = "Action: $actionName")
            }
        }
        composeTestRule.onNodeWithText("Action: navigate").assertIsDisplayed()
    }

    @Test
    fun `browserAction click event works in composable`() {
        var clickedAction: String? = null
        composeTestRule.setContent {
            androidx.compose.material.Button(onClick = { clickedAction = BrowserAction.CLICK.value }) {
                androidx.compose.material.Text(text = "Click Action")
            }
        }
        composeTestRule.onNodeWithText("Click Action").performClick()
        assertEquals("click", clickedAction)
    }

    @Test
    fun `scrollDirection can be rendered in composable`() {
        composeTestRule.setContent {
            ScrollDirection.DOWN.value.let { direction ->
                androidx.compose.material.Text(text = "Direction: $direction")
            }
        }
        composeTestRule.onNodeWithText("Direction: down").assertIsDisplayed()
    }

    @Test
    fun `scrollDirection click event works in composable`() {
        var selectedDirection: String? = null
        composeTestRule.setContent {
            androidx.compose.material.Button(onClick = { selectedDirection = ScrollDirection.UP.value }) {
                androidx.compose.material.Text(text = "Scroll Up")
            }
        }
        composeTestRule.onNodeWithText("Scroll Up").performClick()
        assertEquals("up", selectedDirection)
    }

    @Test
    fun `userAgentProfile can be rendered in composable`() {
        composeTestRule.setContent {
            UserAgentProfile.DESKTOP_CHROME.displayName.let { profileName ->
                androidx.compose.material.Text(text = "Profile: $profileName")
            }
        }
        composeTestRule.onNodeWithText("Profile: Desktop Chrome").assertIsDisplayed()
    }

    @Test
    fun `userAgentProfile click event works in composable`() {
        var selectedProfile: String? = null
        composeTestRule.setContent {
            androidx.compose.material.Button(onClick = { selectedProfile = UserAgentProfile.MOBILE_CHROME.value }) {
                androidx.compose.material.Text(text = "Select Mobile")
            }
        }
        composeTestRule.onNodeWithText("Select Mobile").performClick()
        assertEquals("mobile_chrome", selectedProfile)
    }

    @Test
    fun `browserAction default parameter test`() {
        val defaultAction = BrowserAction.NAVIGATE
        assertNotNull(defaultAction)
        assertEquals("navigate", defaultAction.value)
        assertTrue(defaultAction.opensNewPage)
    }

    @Test
    fun `scrollDirection default parameter test`() {
        val defaultDirection = ScrollDirection.UP
        assertNotNull(defaultDirection)
        assertEquals("up", defaultDirection.value)
    }

    @Test
    fun `userAgentProfile default parameter test`() {
        val defaultProfile = UserAgentProfile.CUSTOM
        assertNotNull(defaultProfile)
        assertEquals("custom", defaultProfile.value)
        assertNull(defaultProfile.userAgentString)
        assertEquals(412 to 915, defaultProfile.viewportSize)
    }
}