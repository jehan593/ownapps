package com.ownapps.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.ownapps.app.ui.theme.nord13
import com.ownapps.app.ui.theme.nord14
import com.ownapps.app.ui.theme.nord15
import com.ownapps.app.ui.theme.nord3

/**
 * Live Nord-palette coloring for the script editor. The mini-language is small enough to tint
 * with this forgiving scanner (mirrors the Lexer's rules) instead of a full parser, so text
 * that's mid-typing never breaks highlighting.
 */
private class ScriptSyntaxHighlight(
    private val keyword: SpanStyle,
    private val string: SpanStyle,
    private val number: SpanStyle,
    private val comment: SpanStyle
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        var i = 0
        val source = text.text
        val n = source.length
        while (i < n) {
            when (source[i]) {
                '#' -> {
                    val start = i
                    while (i < n && source[i] != '\n') i++
                    spans += AnnotatedString.Range(comment, start, i)
                }
                '"' -> {
                    val start = i
                    i++
                    while (i < n && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    if (i < n) i++
                    spans += AnnotatedString.Range(string, start, i)
                }
                else -> {
                    val c = source[i]
                    val start = i
                    when {
                        c.isDigit() -> {
                            while (i < n && (source[i].isDigit() ||
                                    (source[i] == '.' && i + 1 < n && source[i + 1].isDigit()))) i++
                            spans += AnnotatedString.Range(number, start, i)
                        }
                        c.isLetter() || c == '_' -> {
                            while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                            if (source.substring(start, i) in KEYWORDS) {
                                spans += AnnotatedString.Range(keyword, start, i)
                            }
                        }
                        else -> i++
                    }
                }
            }
        }
        return TransformedText(AnnotatedString(source, spans), OffsetMapping.Identity)
    }

    private companion object {
        val KEYWORDS = setOf(
            "if", "else", "while", "for", "in", "fn", "return", "break", "continue",
            "true", "false", "null", "and", "or", "not"
        )
    }
}

/** The Nord-styled highlighter shared by every editor dialog on screen. */
@Composable
fun rememberNordScriptHighlight(): VisualTransformation = remember {
    ScriptSyntaxHighlight(
        keyword = SpanStyle(color = nord13, fontWeight = FontWeight.Bold),
        string = SpanStyle(color = nord14),
        number = SpanStyle(color = nord15),
        comment = SpanStyle(color = nord3, fontStyle = FontStyle.Italic)
    )
}