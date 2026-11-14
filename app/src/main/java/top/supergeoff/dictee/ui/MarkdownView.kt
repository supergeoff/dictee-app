package top.supergeoff.dictee.ui

import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import io.noties.markwon.Markwon
import top.supergeoff.dictee.R

/** Markdown natif en lecture seule ; Markwon core ne charge pas les images distantes. */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val color = Dictee.colors.text.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                typeface = ResourcesCompat.getFont(context, R.font.figtree_variable)
                fontVariationSettings = "'wght' 400"
                textSize = 15f
                setTextIsSelectable(true)
            }
        },
        update = { view ->
            view.setTextColor(color)
            Markwon.create(view.context).setMarkdown(view, markdown)
        },
    )
}
