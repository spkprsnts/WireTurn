package com.wireturn.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

object MarkdownUtils {
    private val URL_REGEX = Regex("(https?://[\\w.#@/!$?&%=+:\\-_~*]+)")

    /**
     * Парсит базовый Markdown (жирный, курсив, списки, ссылки) в AnnotatedString
     */
    fun parseMarkdown(
        text: String,
        linkStyle: SpanStyle = SpanStyle(textDecoration = TextDecoration.Underline)
    ): AnnotatedString {
        return buildAnnotatedString {
            val lines = text.lines()
            lines.forEachIndexed { index, line ->
                var currentLine = line.trim()
                
                // Обработка заголовков (### Header)
                if (currentLine.startsWith("#")) {
                    val level = currentLine.takeWhile { it == '#' }.length
                    currentLine = currentLine.removePrefix("#".repeat(level)).trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        parseInlineStyles(currentLine, linkStyle)
                    }
                } else if (currentLine.startsWith("- ") || currentLine.startsWith("* ")) {
                    // Обработка списков
                    append("  • ")
                    parseInlineStyles(currentLine.substring(2), linkStyle)
                } else {
                    parseInlineStyles(currentLine, linkStyle)
                }
                
                if (index < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    private fun AnnotatedString.Builder.parseInlineStyles(text: String, linkStyle: SpanStyle) {
        var i = 0
        while (i < text.length) {
            when {
                // Жирный текст (**bold**)
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            parseInlineStyles(text.substring(i + 2, end), linkStyle)
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                // Курсив (*italic*)
                text.startsWith("*", i) -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            parseInlineStyles(text.substring(i + 1, end), linkStyle)
                        }
                        i = end + 1
                    } else {
                        append("*")
                        i += 1
                    }
                }
                // Ссылки ([text](url))
                text.startsWith("[", i) -> {
                    val textEnd = text.indexOf("]", i)
                    val urlStart = text.indexOf("(", textEnd)
                    val urlEnd = text.indexOf(")", urlStart)
                    if (textEnd != -1 && urlStart == textEnd + 1 && urlEnd != -1) {
                        val linkText = text.substring(i + 1, textEnd)
                        val url = text.substring(urlStart + 1, urlEnd)
                        
                        pushLink(LinkAnnotation.Url(url, TextLinkStyles(linkStyle)))
                        append(linkText)
                        pop()

                        i = urlEnd + 1
                    } else {
                        append("[")
                        i += 1
                    }
                }
                // Код (`code`)
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.1f))) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append("`")
                        i += 1
                    }
                }
                // Обычный текст с поиском голых ссылок
                else -> {
                    val remaining = text.substring(i)
                    val match = URL_REGEX.find(remaining)
                    if (match != null && match.range.first == 0) {
                        val url = match.value
                        val displayUrl = shortenGithubUrl(url)
                        
                        pushLink(LinkAnnotation.Url(url, TextLinkStyles(linkStyle)))
                        append(displayUrl)
                        pop()
                        i += url.length
                    } else {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }

    private fun shortenGithubUrl(url: String): String {
        val githubCommitRegex = Regex("https?://github\\.com/[\\w\\d\\-_]+/[\\w\\d\\-_]+/commit/([a-f0-9]{7,40})")
        val githubCompareRegex = Regex("https?://github\\.com/[\\w\\d\\-_]+/[\\w\\d\\-_]+/compare/([^/?#]+)")

        githubCommitRegex.find(url)?.let {
            val hash = it.groupValues[1]
            return hash.take(7)
        }
        
        githubCompareRegex.find(url)?.let {
            return it.groupValues[1]
        }

        return url
    }
}
