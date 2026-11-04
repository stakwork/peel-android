package com.sphinx.peel.engine

import com.sphinx.peel.engine.MessageMarkup.Block
import com.sphinx.peel.engine.MessageMarkup.Emphasis
import com.sphinx.peel.engine.MessageMarkup.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from peel's Tests/PeelCoreTests/MessageMarkupTests.swift.
class MessageMarkupTest {
    private fun kindName(k: Kind) = when (k) {
        Kind.Code -> "code"
        is Kind.Link -> "link"
        is Kind.NamedLink -> "named"
        Kind.Mention -> "mention"
    }

    /// Each span as (kind, what shows, the source text it covers).
    private fun spans(text: String) =
        MessageMarkup.spans(text).map { Triple(kindName(it.kind), it.text, text.substring(it.start, it.end)) }

    private fun kinds(text: String) = spans(text).map { it.first }

    /// Each piece as it shows, with what marks it.
    private fun shown(text: String) = MessageMarkup.inlines(text).map { inline ->
        val marks = ArrayList<String>()
        if (Emphasis.BOLD in inline.emphasis) marks.add("b")
        if (Emphasis.ITALIC in inline.emphasis) marks.add("i")
        if (Emphasis.STRIKETHROUGH in inline.emphasis) marks.add("s")
        when (val k = inline.kind) {
            null -> {}
            Kind.Code -> marks.add("code")
            is Kind.Link -> marks.add("link ${k.url}")
            is Kind.NamedLink -> marks.add("named ${k.url}")
            Kind.Mention -> marks.add("mention")
        }
        if (marks.isEmpty()) inline.text else "${inline.text}[${marks.joinToString(" ")}]"
    }

    private fun blocks(text: String) = MessageMarkup.blocks(text)

    @Test fun inlineCodeDropsItsBackticks() {
        val found = spans("give the `repo-scoped concepts` from the generator")
        assertEquals(listOf(Triple("code", "repo-scoped concepts", "`repo-scoped concepts`")), found)
    }

    @Test fun severalSpansOnALine() {
        assertEquals(listOf("a", "b"), spans("`a` and `b`").map { it.second })
    }

    @Test fun unmatchedBackticksStayText() {
        for (t in listOf("a ` b", "``", "a `` b", "one `\ntwo`", "`` x ` y")) assertTrue(t, kinds(t).isEmpty())
    }

    @Test fun doubleAndTripleBackticksInALine() {
        assertEquals(listOf("code"), spans("use ``code`` here").map { it.second })
        assertEquals(listOf("code"), spans("use ```code``` here").map { it.second })
    }

    @Test fun aBacktickInsideALinkStaysPartOfIt() {
        assertEquals(listOf("link"), kinds("see https://example.com/a`b`c/d ok"))
    }

    @Test fun codeAfterABacktickInALinkIsStillFound() {
        val found = spans("see https://example.com/a`b and `c` too")
        assertEquals(listOf("link", "code"), found.map { it.first })
        assertEquals("c", found.last().second)
    }

    @Test fun aLinkInsideCodeIsCode() {
        assertEquals(listOf("code"), kinds("run `https://example.com/x` now"))
    }

    @Test fun aMentionInsideCodeIsCode() {
        assertEquals(listOf("code"), kinds("type `@tom` to ping"))
        assertEquals(listOf("mention", "code"), kinds("@tom look at `x`"))
    }

    @Test fun mentionsAreWordStartsWithoutTrailingMarks() {
        assertEquals(listOf("@tom_smith,"), spans("hi @tom_smith, ok").map { it.second })
        assertTrue(kinds("mail a@b.com").isEmpty())
        assertTrue(kinds("an @ alone").isEmpty())
    }

    @Test fun onlyHttpsLinks() {
        assertEquals(listOf("link"), kinds("https://example.com and http://example.com"))
    }

    @Test fun linkEdges() {
        assertEquals(listOf("https://a.co/x"), spans("see https://a.co/x.").map { it.third })
        assertEquals(listOf("https://a.co/x"), spans("(https://a.co/x)").map { it.third })
        assertEquals(listOf("https://a.co/w_(y)"), spans("https://a.co/w_(y) z").map { it.third })
        assertEquals(listOf("https://a.co:8080/p?q=1#f"), spans("go https://a.co:8080/p?q=1#f, now").map { it.third })
        assertEquals(listOf("link"), kinds("HTTPS://A.CO/x"))
        assertTrue(kinds("xhttps://a.co").isEmpty())
        assertTrue(kinds("https://user@a.co").isEmpty())
        assertTrue(kinds("https://").isEmpty())
        assertTrue(kinds("https://a.co:x").isEmpty())
        assertTrue(kinds("https://a..co").isEmpty())
    }

    @Test fun plainTextHasNoSpans() {
        assertTrue(kinds("nothing to see here").isEmpty())
    }

    @Test fun emphasis() {
        assertEquals(listOf("b[b]", " ", "i[i]", " ", "j[i]", " ", "c[b]", " ", "s[s]"), shown("**b** *i* _j_ __c__ ~~s~~"))
    }

    @Test fun nestedEmphasis() {
        assertEquals(listOf("bold [b]", "both[b i]", " bold[b]"), shown("**bold *both* bold**"))
        assertEquals(listOf("both[b i]"), shown("***both***"))
        assertEquals(listOf("gone[b s]"), shown("~~**gone**~~"))
    }

    @Test fun marksThatDoNotPairShowAsTyped() {
        for (t in listOf("**open", "close**", "a * b * c", "snake_case_name", "~one~", "****", "** a **")) {
            assertEquals(t, listOf(t), shown(t))
        }
    }

    @Test fun marksInsideCodeAreCode() {
        assertEquals(listOf("**not bold**[code]"), shown("`**not bold**`"))
        assertEquals(listOf("x[b code]"), shown("**`x`**"))
        assertEquals(listOf("*a ", "b*[code]", " c"), shown("*a `b*` c"))
    }

    @Test fun emphasisAroundALinkOrMentionIsNotTheirs() {
        assertEquals(listOf("https://a.co/x[b link https://a.co/x]"), shown("**https://a.co/x**"))
        assertEquals(listOf("@tom[i mention]"), shown("*@tom*"))
    }

    @Test fun emphasisWithPunctuationAndEmoji() {
        assertEquals(listOf("(", "x[b]", ")"), shown("(**x**)"))
        assertEquals(listOf("😀 ", "x[i]"), shown("😀 *x*"))
    }

    @Test fun starItemsAndItalics() {
        assertEquals(listOf(Block.Item("item"), Block.Paragraph("*italic*")), blocks("* item\n*italic*"))
        assertEquals(listOf("italic[i]"), shown("*italic*"))
    }

    @Test fun namedLink() {
        assertEquals(listOf("see ", "the docs[named https://a.co/x]", " now"), shown("see [the docs](https://a.co/x) now"))
        assertEquals(listOf("[a](https://a.co)"), spans("[a](https://a.co)").map { it.third })
    }

    @Test fun namedLinksOnlyOpenHttps() {
        for (t in listOf(
            "[x](http://a.co)", "[x](javascript:alert)", "[x](file:///etc/passwd)", "[x](https://user@a.co)",
            "[x](sphinx.chat://?action=tribe)", "[x](a.co)",
        )) {
            assertTrue(t, MessageMarkup.inlines(t).all { it.kind == null })
            assertEquals(t, listOf(t), shown(t))
        }
    }

    @Test fun headingsQuotesAndNumbers() {
        val text = "# Title\n## Sub\n#tag\n####### seven\n> one\n>two\n\n> three\n1. first\n12) second\n3.no"
        assertEquals(
            listOf(
                Block.Heading(1, "Title"), Block.Heading(2, "Sub"), Block.Paragraph("#tag\n####### seven"),
                Block.Quote("one\ntwo"), Block.Quote("three"), Block.Numbered(1, "first"),
                Block.Numbered(12, "second"), Block.Paragraph("3.no"),
            ),
            blocks(text),
        )
    }

    @Test fun paragraphsAndItems() {
        assertEquals(
            listOf(Block.Paragraph("hi"), Block.Item("one"), Block.Item("two"), Block.Paragraph("bye")),
            blocks("hi\n- one\n• two\nbye"),
        )
    }

    @Test fun fencedBlockKeepsItsLinesAndDropsTheLanguage() {
        assertEquals(
            listOf(Block.Paragraph("before"), Block.Code("let a = 1\n    indented"), Block.Paragraph("after")),
            blocks("before\n```swift\nlet a = 1\n    indented\n```\nafter"),
        )
    }

    @Test fun languageNameAfterASpaceIsDropped() {
        assertEquals(listOf(Block.Code("print(1)")), blocks("``` python\nprint(1)\n```"))
    }

    @Test fun windowsLineEndings() {
        assertEquals(listOf(Block.Code("let a = 1"), Block.Item("one")), blocks("```swift\r\nlet a = 1\r\n```\r\n- one"))
    }

    @Test fun fencedBlockOnOneLine() {
        assertEquals(listOf(Block.Code("ls -la")), blocks("```ls -la```"))
    }

    @Test fun fencedBlockWithCodeOnTheFenceLines() {
        assertEquals(listOf(Block.Code("let a = 1\nlet b = 2")), blocks("```let a = 1\nlet b = 2```"))
    }

    @Test fun unclosedFenceStaysText() {
        assertEquals(listOf(Block.Paragraph("```\nnot closed")), blocks("```\nnot closed"))
    }

    @Test fun emptyFenceStaysText() {
        assertEquals(listOf(Block.Paragraph("```\n```")), blocks("```\n```"))
    }

    @Test fun blockMarkersInsideCodeAreCode() {
        assertEquals(
            listOf(Block.Code("- not an item\n# nor\n> nor\n1. nor")),
            blocks("```\n- not an item\n# nor\n> nor\n1. nor\n```"),
        )
    }

    @Test fun blankLinesBesideABlockAreDropped() {
        assertEquals(
            listOf(Block.Paragraph("a"), Block.Code("x"), Block.Paragraph("b")),
            blocks("a\n\n```\nx\n```\n\nb"),
        )
    }

    @Test fun emptyTextHasNoBlocks() {
        assertTrue(blocks("").isEmpty())
        assertTrue(blocks("  \n\n ").isEmpty())
    }

    @Test fun plainText() {
        val cases = listOf(
            "plain text" to "plain text",
            "  spaced  " to "spaced",
            "- one item" to "one item",
            "use `swift build` here" to "use swift build here",
            "``a b`` and `c`" to "a b and c",
            "```\nlet a = 1\n    let b = 2\n```" to "let a = 1 let b = 2",
            "```swift\nx\n```" to "x",
            "```ls -la```" to "ls -la",
            "before\n\n```\ncode\n```\nafter" to "before code after",
            "- one `two`\n- three" to "one two three",
            "line one\r\nline two" to "line one line two",
            "an unmatched ` stays" to "an unmatched ` stays",
            "```\nnot closed" to "``` not closed",
            "https://a.example/`x`/b" to "https://a.example/`x`/b",
            "@ann see `x`" to "@ann see x",
            "  \n\n  " to "",
            "**bold** and _it_ ~~not~~" to "bold and it not",
            "# Title\n> quoted" to "Title quoted",
            "1. one\n2. two" to "1. one 2. two",
            "see [docs](https://a.co/x)" to "see docs",
            "snake_case" to "snake_case",
            "`**x**`" to "**x**",
        )
        for ((text, expected) in cases) assertEquals(text, expected, MessageMarkup.plainText(text))
    }

    @Test fun linkLabelAndHost() {
        assertEquals("https://a.co/x", MessageMarkup.linkLabel("https://a.co/x", "https://a.co/x"))
        assertEquals("a.co:8080", MessageMarkup.host("https://a.co:8080/p"))
        // A look-alike host shows in its punycode form.
        val link = MessageMarkup.spans("https://exämple.com/p").single()
        val url = (link.kind as Kind.Link).url
        assertEquals("https://xn--exmple-cua.com/p", url)
        assertEquals("https://xn--exmple-cua.com/p", MessageMarkup.linkLabel(link.text, url))
    }

    @Test fun firstLink() {
        assertEquals("https://a.co/x", MessageMarkup.firstLink("see https://a.co/x and https://b.co"))
        assertEquals("https://b.co", MessageMarkup.firstLink("[b](https://b.co) then https://c.co"))
        assertNull(MessageMarkup.firstLink("http://a.co `https://a.co`"))
    }

    @Test fun mentionsOfYou() {
        val me = Mentions.ownAliases("Tom Smith")
        assertEquals(setOf("Tom_Smith", "all"), me)
        assertTrue(Mentions.mentions("@Tom_Smith,", me))
        assertTrue(Mentions.mentions("@ALL", me))
        assertTrue(!Mentions.mentions("@Tom_Smith_2", me))
        assertTrue(!Mentions.mentions("mail@Tom_Smith", me))
    }
}
