package com.arulebin.warden

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val WordmarkStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Black,
    fontSize = 22.sp,
    letterSpacing = 6.sp,
    color = Brand.text,
)

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brand.surface)
            .border(1.dp, Brand.line, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = Brand.textDim,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 2.5.sp,
    )
}

@Composable
fun PanelHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(title)
        trailing?.invoke()
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(text, color = Brand.textDim, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
fun Mono(
    text: String,
    color: Color = Brand.text,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun WardenButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.shield,
            contentColor = Brand.ink,
            disabledContainerColor = Brand.surfaceHi,
            disabledContentColor = Brand.textDim,
        ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun AddField(placeholder: String, buttonLabel: String = "Add", onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text(placeholder, fontFamily = FontFamily.Monospace, color = Brand.textDim) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Brand.shield,
                unfocusedBorderColor = Brand.line,
                focusedContainerColor = Brand.surfaceHi,
                unfocusedContainerColor = Brand.surfaceHi,
                cursorColor = Brand.shield,
                focusedTextColor = Brand.text,
                unfocusedTextColor = Brand.text,
            ),
        )
        WardenButton(buttonLabel, enabled = value.isNotBlank()) {
            onSubmit(value); value = ""
        }
    }
}

@Composable
fun DomainRow(domain: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(domain, modifier = Modifier.weight(1f))
        Text(
            "✕",
            color = Brand.textDim,
            fontSize = 16.sp,
            modifier = Modifier.clip(CircleShape).clickable { onRemove() }.padding(8.dp),
        )
    }
}
