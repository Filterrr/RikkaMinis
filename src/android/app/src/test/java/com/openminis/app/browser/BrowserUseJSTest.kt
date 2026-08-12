package com.openminis.app.browser

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserUseJSTest {

    @Test
    fun testJsQuote_escapesBackslash() {
        val result = BrowserUseJS.jsQuote("a\\b")
        assertTrue(result.contains("\\\\"))
    }

    @Test
    fun testJsQuote_escapesSingleQuote() {
        val result = BrowserUseJS.jsQuote("a'b")
        assertTrue(result.contains("\\'"))
    }

    @Test
    fun testJsQuote_escapesDoubleQuote() {
        val result = BrowserUseJS.jsQuote("a\"b")
        assertTrue(result.contains("\\\""))
    }

    @Test
    fun testJsQuote_escapesNewline() {
        val result = BrowserUseJS.jsQuote("a\nb")
        assertTrue(result.contains("\\n"))
    }

    @Test
    fun testJsQuote_escapesCarriageReturn() {
        val result = BrowserUseJS.jsQuote("a\rb")
        assertTrue(result.contains("\\r"))
    }

    @Test
    fun testJsQuote_escapesTab() {
        val result = BrowserUseJS.jsQuote("a\tb")
        assertTrue(result.contains("\\t"))
    }

    @Test
    fun testJsQuote_preservesOtherChars() {
        val result = BrowserUseJS.jsQuote("hello")
        assertTrue(result == "hello")
    }

    @Test
    fun testClick_returnsValidJS() {
        val result = BrowserUseJS.click("#myButton")
        assertTrue(result.contains("document.querySelector"))
        assertTrue(result.contains("'#myButton'"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testClick_escapesSelector() {
        val result = BrowserUseJS.click("button[data-id='1']")
        assertTrue(result.contains("\\'"))
    }

    @Test
    fun testClickCoordinate_returnsValidJS() {
        val result = BrowserUseJS.clickCoordinate(100, 200)
        assertTrue(result.contains("document.elementFromPoint(100, 200)"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testType_returnsValidJS() {
        val result = BrowserUseJS.type("#input", "hello")
        assertTrue(result.contains("document.querySelector"))
        assertTrue(result.contains("'#input'"))
        assertTrue(result.contains("'hello'"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testType_escapesText() {
        val result = BrowserUseJS.type("#input", "it's")
        assertTrue(result.contains("\\'"))
    }

    @Test
    fun testGetText_withSelector_returnsValidJS() {
        val result = BrowserUseJS.getText("#content")
        assertTrue(result.contains("document.querySelector"))
        assertTrue(result.contains("'#content'"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testGetText_withNullSelector_returnsValidJS() {
        val result = BrowserUseJS.getText(null)
        assertTrue(result.contains("document.body.innerText"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testGetReadable_returnsValidJS() {
        val result = BrowserUseJS.getReadable()
        assertTrue(result.contains("candidateSelectors"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testScroll_down_returnsValidJS() {
        val result = BrowserUseJS.scroll(ScrollDirection.DOWN, 100, null)
        assertTrue(result.contains("window.scrollBy(0, 100)"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testScroll_up_returnsValidJS() {
        val result = BrowserUseJS.scroll(ScrollDirection.UP, 50, null)
        assertTrue(result.contains("window.scrollBy(0, -50)"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testScroll_withSelector_returnsValidJS() {
        val result = BrowserUseJS.scroll(ScrollDirection.DOWN, 200, ".container")
        assertTrue(result.contains("'.container'"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testHover_returnsValidJS() {
        val result = BrowserUseJS.hover("#link")
        assertTrue(result.contains("document.querySelector"))
        assertTrue(result.contains("'#link'"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testHover_escapesSelector() {
        val result = BrowserUseJS.hover("a[href='/page']")
        assertTrue(result.contains("\\'"))
    }

    @Test
    fun testFindElements_returnsValidJS() {
        val result = BrowserUseJS.findElements("div")
        assertTrue(result.contains("document.querySelectorAll"))
        assertTrue(result.contains("'div'"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testGetPageInfo_returnsValidJS() {
        val result = BrowserUseJS.getPageInfo()
        assertTrue(result.contains("window.location.href"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testGetBackbone_returnsValidJS() {
        val result = BrowserUseJS.getBackbone(5)
        assertTrue(result.contains("MAX_DEPTH = 5"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testFetch_returnsValidJS() {
        val result = BrowserUseJS.fetch("https://example.com")
        assertTrue(result.contains("fetch(\"https://example.com\")"))
        assertTrue(result.contains("JSON.stringify"))
    }

    @Test
    fun testFetch_escapesUrl() {
        val result = BrowserUseJS.fetch("https://example.com/path?q=hello&x=1")
        assertTrue(result.contains("\\\""))
    }
}