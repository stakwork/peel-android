package com.sphinx.peel.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sphinx.peel.engine.MessageMarkup
import com.sphinx.peel.engine.MessageMarkup.Block
import com.sphinx.peel.engine.MessageMarkup.Emphasis
import com.sphinx.peel.engine.MessageMarkup.Kind
import com.sphinx.peel.engine.Mentions
import com.sphinx.peel.ui.theme.Theme

/// Message text as `MessageMarkup` reads it: links, mentions, inline code and
/// emphasis marked, list items with a hanging indent, headings larger, quotes
/// behind a bar, ``` blocks as code. Drawn like peel's `Apps/Shared/MessageText.swift`.
/// `myAliases` are what a mention of you looks like (`Mentions.ownAliases`);
/// empty in the agent chat. `maxLines` caps each paragraph (a list row's preview). Only https links open, through the app's link handler (`LocalLinkOpener`: a Hive app link opens its page) or ACTION_VIEW; a long
/// press anywhere else falls through to the row (the message menu).
@Composable
fun MessageText(
    text: String,
    modifier: Modifier = Modifier,
    myAliases: Set<String> = emptySet(),
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Theme.colors.text,
    maxLines: Int = Int.MAX_VALUE,
) {
    val blocks = remember(text) { MessageMarkup.blocks(text) }
    val palette = Theme.colors
    val context = LocalContext.current
    // The app's one link handler (a Hive app link opens its page), else the system's.
    val linkOpener = com.sphinx.peel.ui.apps.LocalLinkOpener.current
    val openLink = remember(context, linkOpener) {
        { url: String ->
            if (linkOpener != null) {
                linkOpener.open(url)
            } else {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                    // No browser to open it: nothing to do.
                }
            }
        }
    }
    val base = style.fontSize.takeIf { it != TextUnit.Unspecified } ?: 14.sp
    val body = style.copy(color = color, lineHeight = (base.value * 1.4f).sp)
    @Composable
    fun line(source: String, size: TextUnit = base, weight: FontWeight? = null, modifier: Modifier = Modifier) {
        val annotated = remember(source, myAliases, palette, base) {
            attributed(source, myAliases, palette, base, openLink)
        }
        Text(
            annotated,
            style = body.copy(fontSize = size, fontWeight = weight, lineHeight = (size.value * 1.4f).sp),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> line(block.text)
                is Block.Item -> Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = body, color = palette.secondaryText)
                    line(block.text, modifier = Modifier.weight(1f))
                }
                is Block.Numbered -> Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${block.number}.",
                        style = body.copy(fontFeatureSettings = "tnum"),
                        color = palette.secondaryText,
                    )
                    line(block.text, modifier = Modifier.weight(1f))
                }
                is Block.Heading -> line(
                    block.text,
                    size = (base.value + (if (block.level == 1) 6 else if (block.level == 2) 3 else 1)).sp,
                    weight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )
                is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(palette.secondaryText.copy(alpha = 0.4f)),
                    )
                    line(block.text, modifier = Modifier.weight(1f).padding(start = 9.dp))
                }
                is Block.Code -> Text(
                    block.text,
                    style = body.copy(fontFamily = FontFamily.Monospace, fontSize = (base.value - 1).sp, lineHeight = ((base.value - 1) * 1.4f).sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.codeFill)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/// One line of block text with its inline markup styled
/// (`MessageMarkup.inlines`): https links in the link colour, as
/// `linkLabel` words them, mentions as a soft chip (gold for you and @all),
/// inline code monospaced on a soft fill, a named link as its text with its
/// host beside it so where it goes is never hidden, emphasis bold, italic or
/// struck through. Anything else stays plain text.
internal fun attributed(
    text: String,
    myAliases: Set<String>,
    palette: com.sphinx.peel.ui.theme.PeelPalette,
    size: TextUnit,
    openLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    fun link(url: String) = LinkAnnotation.Url(
        url,
        TextLinkStyles(SpanStyle(color = palette.link)),
    ) { openLink(url) }
    for (inline in MessageMarkup.inlines(text)) {
        val emphasis = SpanStyle(
            fontWeight = if (Emphasis.BOLD in inline.emphasis) FontWeight.Bold else null,
            fontStyle = if (Emphasis.ITALIC in inline.emphasis) FontStyle.Italic else null,
            textDecoration = if (Emphasis.STRIKETHROUGH in inline.emphasis) TextDecoration.LineThrough else null,
        )
        withStyle(emphasis) {
            when (val kind = inline.kind) {
                null -> append(inline.text)
                Kind.Mention -> {
                    val you = Mentions.mentions(inline.text, myAliases)
                    withStyle(
                        SpanStyle(
                            fontWeight = if (you) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (you) palette.mentionYouText else palette.mentionText,
                            background = if (you) palette.mentionYouFill else palette.mentionFill,
                        ),
                    ) { append(inline.text) }
                }
                Kind.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = (size.value - 1).sp, background = palette.codeFill)) {
                    append(inline.text)
                }
                is Kind.Link -> withLink(link(kind.url)) { append(MessageMarkup.linkLabel(inline.text, kind.url)) }
                is Kind.NamedLink -> withLink(link(kind.url)) {
                    append(inline.text)
                    // Isolated left to right, so a direction override in the
                    // text before it cannot turn the host around.
                    withStyle(SpanStyle(color = palette.secondaryText)) {
                        append(" (\u2066${MessageMarkup.host(kind.url) ?: kind.url}\u2069)")
                    }
                }
            }
        }
    }
}
