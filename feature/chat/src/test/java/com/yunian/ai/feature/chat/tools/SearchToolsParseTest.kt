package com.yunian.ai.feature.chat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 必应结果页解析回归用例。
 *
 * 真机事故（2026-09-28）：搜索工具「调用成功但永远 no results」。
 * 根因是**移动版把链接包在标题外面** —— <a href="真实URL"><h2>标题</h2></a>，
 * 旧实现要求 <h2><a href=...>（桌面版形态），线上恒 0 命中。
 * 本用例锁死：两种形态都能解析，且 /ck/a 跳转能还原成真实地址。
 */
class SearchToolsParseTest {

    /** 移动版（App 实际 UA）：链接在 h2 外面 + p.b_lineclamp 摘要 */
    private val mobileHtml = """
        <ol id="b_results">
        <li class="b_algo" data-id iid=SERP.5257><link rel="stylesheet" href="/rp/a.css"/>
        <div class="b_algoheader"><a href="https://www.toutiao.com/" h="ID=SERP,5093.2">
        <h2 class=""><strong>今日</strong>头条</h2></a></div>
        <div class="b_caption"><p class="b_lineclamp3">9月27日 武契奇辞去塞尔维亚总统职务</p></div></li>
        <li class="b_algo" data-id iid=SERP.5261>
        <div class="b_tpcn"><a class="tilk" href="https://cn.bing.com/x">站内图标</a></div>
        <div class="b_algoheader"><a href="/ck/a?u=a1aHR0cHM6Ly9uZXdzLmV4YW1wbGUuY29tL24x&ntb=1"><h2>示例新闻一</h2></a></div>
        <p class="b_lineclamp4">示例摘要一</p></li>
        </ol>
    """.trimIndent()

    /** 桌面版：<h2><a href=...>标题</a></h2> */
    private val desktopHtml = """
        <ol id="b_results">
        <li class="b_algo" data-id iid=SERP.1><h2 class=""><a href="https://www.example.com/a" h="ID=SERP,1">桌面标题</a></h2>
        <p class="b_lineclamp">桌面摘要</p>
    """.trimIndent()

    @Test
    fun mobileFormAnchorWrappingH2IsParsed() {
        val results = SearchTools.parseBingResults(mobileHtml, 5)
        assertEquals(2, results.size)
        assertEquals("今日头条", results[0].title.replace(" ", ""))
        assertEquals("https://www.toutiao.com/", results[0].url)
        assertTrue(results[0].snippet.contains("武契奇"))
        // /ck/a?...&u=a1<base64> 必须还原为真实地址
        assertEquals("https://news.example.com/n1", results[1].url)
        // 站内链接必须被过滤
        assertTrue(results.none { it.url.contains("bing.com") })
    }

    @Test
    fun desktopFormAnchorInsideH2IsParsed() {
        val results = SearchTools.parseBingResults(desktopHtml, 5)
        assertEquals(1, results.size)
        assertEquals("桌面标题", results[0].title)
        assertEquals("https://www.example.com/a", results[0].url)
        assertEquals("桌面摘要", results[0].snippet)
    }

    @Test
    fun bingUrlNormalizationHandlesRedirectAndRelativeLinks() {
        assertEquals("https://a.example/1", SearchTools.normalizeBingUrl("https://a.example/1"))
        assertEquals(
            "https://news.example.com/n1",
            SearchTools.normalizeBingUrl("/ck/a?u=a1aHR0cHM6Ly9uZXdzLmV4YW1wbGUuY29tL24x&ntb=1"),
        )
        assertNull(SearchTools.normalizeBingUrl("/rp/x.css"))
    }

    @Test
    fun bingParserHonoursMaxResults() {
        assertEquals(1, SearchTools.parseBingResults(mobileHtml, 1).size)
    }

    @Test
    fun ddgLiteParserExtractsResults() {
        val html = """
            <table>
              <tr><td><a rel="nofollow" href="https://a.example/1" class="result-link">标题一</a></td></tr>
              <tr><td class="result-snippet">摘要一</td></tr>
            </table>
        """.trimIndent()
        val results = SearchTools.parseDdgLiteResults(html, 5)
        assertEquals(1, results.size)
        assertEquals("标题一", results[0].title)
        assertEquals("https://a.example/1", results[0].url)
        assertEquals("摘要一", results[0].snippet)
    }

    @Test
    fun parsersTolerateEmptyInput() {
        assertTrue(SearchTools.parseBingResults("", 5).isEmpty())
        assertTrue(SearchTools.parseDdgLiteResults("", 5).isEmpty())
    }
}
