package top.supergeoff.dictee.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.supergeoff.dictee.R

/** Rubrique d'un écran de réglages : son titre en capitales, puis sa carte. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        content()
    }
}

/** Carte de rangées (`card`) : surface, contour `border` et ombre douce en clair ; [RowDivider] sépare les rangées. */
@Composable
internal fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().card(), content = content)
}

/** Filet entre deux rangées d'une carte : le trait `border`, comme le contour de la carte. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(color = Dictee.colors.border)
}

/**
 * Rangée d'une carte : une [tile] facultative, le titre avec son sous-titre (en JetBrains Mono si [monoSubtitle]), puis [trailing].
 * Avec [onClick], toute la rangée répond au toucher ; [enabled] faux la passe à `opacity-inactive` et la rend muette.
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
            .alpha(if (enabled) 1f else Tokens.INACTIVE_ALPHA)
            .then(if (onClick != null) Modifier.pressable(radius = 0.dp, enabled = enabled, onClick = onClick) else Modifier)
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
        Text(text = title, style = Dictee.type.rowTitle.copy(fontWeight = weight), color = colors.text)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = if (mono) Dictee.type.mono else Dictee.type.caption,
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
