package com.phloots.busjam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.busjam.ui.theme.IconShape
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.theme.drawShapeIcon

/** [filled] of 3 stars. */
@Composable
fun StarRow(filled: Int, size: Dp, modifier: Modifier = Modifier, empty: Color = Color(0x33000000)) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(size * 0.1f)) {
        repeat(3) { i ->
            Canvas(Modifier.size(size)) {
                drawShapeIcon(
                    IconShape.STAR, Offset(this.size.width / 2, this.size.height / 2), this.size.minDimension,
                    if (i < filled) Palette.gold else empty,
                    outline = if (i < filled) Color(0xFFB07F00) else null,
                )
            }
        }
    }
}

@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Palette.ink,
    content: Color = Color.White,
    fontSize: Int = 16,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}
