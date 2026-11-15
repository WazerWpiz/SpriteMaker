package com.fengeon.resprite_desktop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fengeon.resprite_desktop.model.Palette
import com.fengeon.resprite_desktop.ui.rsColors

@Composable
fun PalettePanel(
    palette: Palette,
    primary: Int,
    secondary: Int,
    onPrimaryChange: (Int) -> Unit,
    onSecondaryChange: (Int) -> Unit,
    onPaletteChange: (Palette) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // current primary / secondary swatches
            Box(modifier = Modifier.size(28.dp).background(Color(primary), RoundedCornerShape(4.dp))
                .border(1.dp, rsColors.swatchBorderActive, RoundedCornerShape(4.dp)))
            Box(modifier = Modifier.size(28.dp).background(Color(secondary), RoundedCornerShape(4.dp))
                .border(1.dp, rsColors.swatchBorder, RoundedCornerShape(4.dp)))
            Box(modifier = Modifier.clickable { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text("Palette: ${palette.name} ▾", color = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Palette.BUILT_INS.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { onPaletteChange(p); menuOpen = false }
                    )
                }
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(palette.colors) { c ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(c), RoundedCornerShape(3.dp))
                        .border(
                            if (c == primary) 2.dp else 1.dp,
                            if (c == primary) rsColors.swatchBorderActive else rsColors.swatchBorder,
                            RoundedCornerShape(3.dp)
                        )
                        .clickable { onPrimaryChange(c) }
                )
            }
        }
    }
}
