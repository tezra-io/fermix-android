package io.tezra.fermix.onboarding

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** The one argument of an onboarding string. */
private const val ARGUMENT = "%1\$s"

/**
 * [template], one of strings.xml's with a single `%1$s`, with [argument] in its place set in [style]:
 * "Shown as **Pixel 9 Pro**" (design section 13.3, step 5). The template is read unformatted, so the
 * argument is styled where a translation puts it.
 */
fun boldArgument(
    template: String,
    argument: String,
    style: SpanStyle,
): AnnotatedString {
    val at = template.indexOf(ARGUMENT)
    require(at >= 0 && template.indexOf(ARGUMENT, at + 1) < 0) { "'$template' holds not exactly one $ARGUMENT" }
    return buildAnnotatedString {
        append(template.substring(0, at))
        withStyle(style) { append(argument) }
        append(template.substring(at + ARGUMENT.length))
    }
}
