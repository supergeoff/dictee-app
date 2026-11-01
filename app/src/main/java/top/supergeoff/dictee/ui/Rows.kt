package top.supergeoff.dictee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.supergeoff.dictee.R

/** Opacité d'une rangée qui ne répond plus : le dossier Drive inaccessible. */
private const val DISABLED_ALPHA = 0.5f

/** Rubrique d'un écran de réglages : son titre en capitales, puis sa carte. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        content()
    }
}

/** Carte de rangées, sur la surface et avec le contour des cartes de la liste ; [RowDivider] sépare les rangées. */
@Composable
internal fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Dictee.colors.surface)
            .border(1.dp, Dictee.colors.border, CardShape),
        content = content,
    )
}

/** Filet entre deux rangées d'une carte, plus discret que le contour, comme dans la liste. */
@Composable
internal fun RowDivider() {
    val border = Dictee.colors.border
    HorizontalDivider(color = border.copy(alpha = border.alpha * 0.6f))
}

/**
 * Rangée d'une carte : une [tile] facultative, le titre avec son sous-titre (en JetBrains Mono si [monoSubtitle]), puis [trailing].
 * Avec [onClick], toute la rangée répond au toucher ; [enabled] faux la grise et la rend muette.
 */
@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    monoSubtitle: Boolean = false,
    weight: FontWeight = FontWeight.Medium,
    tile: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = if (tile == null && subtitle == null) 14.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        tile?.invoke()
        RowTexts(title, subtitle, monoSubtitle, weight, Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, mono: Boolean, weight: FontWeight, modifier: Modifier) {
    val colors = Dictee.colors
    Column(modifier = modifier) {
        Text(text = title, style = sized(15, 20, weight), color = colors.text)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = if (mono) Dictee.type.mono.copy(fontSize = 12.sp, lineHeight = 18.sp) else sized(13, 18),
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Pictogramme au bout d'une rangée : le chevron qui ouvre un sous-écran, la coche du dossier choisi. */
@Composable
internal fun RowIcon(icon: Int, size: Dp = 18.dp, tint: Color = Dictee.colors.textSecondary) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(size), tint = tint)
}

/** Chevron des rangées qui ouvrent un sous-écran. */
@Composable
internal fun Chevron() {
    RowIcon(R.drawable.ic_chevron)
}
