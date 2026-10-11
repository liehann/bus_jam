package com.phloots.busjam.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.phloots.busjam.data.PackInfo
import com.phloots.busjam.ui.packColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.jam.core.model.Dir
import com.phloots.busjam.ui.GameButton
import com.phloots.busjam.ui.game.drawContainer
import com.phloots.busjam.ui.theme.Palette

@Composable
fun HomeScreen(
    nextLevel: Int?,
    totalLevels: Int,
    totalStars: Int,
    onPlay: () -> Unit,
    onLevels: () -> Unit,
    /** Debug builds: the level packs to choose from (hidden when fewer than 2). */
    packs: List<PackInfo> = emptyList(),
    selectedPack: String? = null,
    onSelectPack: (String) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val cell = size.height / 4.2f
            val cx = size.width / 2
            drawContainer(Offset(cx - cell * 2.4f, size.height / 2), cell * 0.82f, cell * 1.84f, Dir.UP, 0, iconSize = cell * 0.46f)
            drawContainer(Offset(cx, size.height / 2), cell * 0.82f, cell * 3.84f, Dir.UP, 1, iconSize = cell * 0.46f)
            drawContainer(Offset(cx + cell * 2.4f, size.height / 2), cell * 0.82f, cell * 2.84f, Dir.DOWN, 2, iconSize = cell * 0.46f)
        }
        Spacer(Modifier.height(16.dp))
        Text("Bus Jam", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Palette.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            if (totalLevels > 0) "$totalStars / ${totalLevels * 3} stars" else "Loading levels...",
            color = Palette.ink.copy(alpha = 0.6f),
        )
        if (packs.size > 1) {
            Spacer(Modifier.height(20.dp))
            PackSelector(packs, selectedPack, onSelectPack)
            Spacer(Modifier.height(20.dp))
        } else {
            Spacer(Modifier.height(40.dp))
        }
        GameButton(
            text = if (nextLevel != null) "Play  -  Level $nextLevel" else "Play",
            onClick = onPlay,
            enabled = nextLevel != null,
            modifier = Modifier.fillMaxWidth().height(72.dp).testTag("play"),
            fontSize = 24,
        )
        Spacer(Modifier.height(16.dp))
        GameButton(
            text = "Levels",
            onClick = onLevels,
            enabled = totalLevels > 0,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("levels"),
            container = Palette.slotFill,
            content = Palette.ink,
            fontSize = 18,
        )
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun PackSelector(packs: List<PackInfo>, selected: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("pack-selector"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LEVEL PACK (debug)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.ink.copy(alpha = 0.6f))
        packs.forEachIndexed { i, p ->
            val on = p.file == selected
            val color = packColor(i)
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) color.copy(alpha = 0.12f) else Color.White, shape)
                    .border(if (on) 2.dp else 1.dp, if (on) color else Palette.slotLine, shape)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(p.file) })
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("pack-${p.file}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(14.dp).background(if (on) color else Color.Transparent, CircleShape)
                        .border(2.dp, color, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) color else Palette.ink)
                    val desc = p.description.ifBlank { p.file }
                    Text(desc, fontSize = 12.sp, color = Palette.ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
