package com.yunian.ai.uicommon.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.theme.AppTheme
import kotlin.math.roundToInt

@Composable
fun ProfessionalColorPickerDialog(
    currentColor: Color,
    initialName: String = "",

    sessionKey: Any = Unit,
    onColorPicked: (Color, String) -> Unit,
    onDismiss: () -> Unit
) {

    val seed = remember(sessionKey, currentColor) { currentColor }

    var red by remember(sessionKey) {
        mutableIntStateOf((seed.red.coerceIn(0f, 1f) * 255f).roundToInt())
    }
    var green by remember(sessionKey) {
        mutableIntStateOf((seed.green.coerceIn(0f, 1f) * 255f).roundToInt())
    }
    var blue by remember(sessionKey) {
        mutableIntStateOf((seed.blue.coerceIn(0f, 1f) * 255f).roundToInt())
    }
    var customName by remember(sessionKey) { mutableStateOf(initialName) }

    val seedHsv = remember(sessionKey, seed) { seed.toHsv() }
    var hue by remember(sessionKey) { mutableFloatStateOf(seedHsv[0]) }
    var saturation by remember(sessionKey) { mutableFloatStateOf(seedHsv[1]) }
    var value by remember(sessionKey) { mutableFloatStateOf(seedHsv[2]) }

    LaunchedEffect(sessionKey, seed, initialName) {
        red = (seed.red.coerceIn(0f, 1f) * 255f).roundToInt()
        green = (seed.green.coerceIn(0f, 1f) * 255f).roundToInt()
        blue = (seed.blue.coerceIn(0f, 1f) * 255f).roundToInt()
        customName = initialName
        val h = seed.toHsv()
        hue = h[0]
        saturation = h[1]
        value = h[2]
    }

    val selectedColor = remember(red, green, blue) {
        Color(
            red = red.coerceIn(0, 255) / 255f,
            green = green.coerceIn(0, 255) / 255f,
            blue = blue.coerceIn(0, 255) / 255f,
            alpha = 1f
        )
    }
    val hex = remember(selectedColor) {
        val argb = selectedColor.toArgb()
        String.format("#%06X", argb and 0xFFFFFF)
    }
    val isDarkSurface = AppTheme.colors.surface.luminance() < 0.5f

    fun applyRgb(r: Int, g: Int, b: Int) {
        red = r.coerceIn(0, 255)
        green = g.coerceIn(0, 255)
        blue = b.coerceIn(0, 255)
        val hsv = Color(
            red = red / 255f,
            green = green / 255f,
            blue = blue / 255f,
            alpha = 1f
        ).toHsv()

        if (hsv[1] > 0.001f) {
            hue = hsv[0]
        }
        saturation = hsv[1]
        value = hsv[2]
    }

    fun applyHsv(h: Float, s: Float, v: Float) {
        hue = h.coerceIn(0f, 360f)
        saturation = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        val c = Color.hsv(hue, saturation, value)
        red = (c.red * 255f).roundToInt().coerceIn(0, 255)
        green = (c.green * 255f).roundToInt().coerceIn(0, 255)
        blue = (c.blue * 255f).roundToInt().coerceIn(0, 255)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "取色盘",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SaturationValuePanel(
                        hue = hue,
                        saturation = saturation,
                        value = value,
                        onChange = { s, v -> applyHsv(hue, s, v) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    VerticalHueBar(
                        hue = hue,
                        onHueChange = { applyHsv(it, saturation, value) },
                        modifier = Modifier
                            .width(28.dp)
                            .fillMaxHeight()
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isDarkSurface) Color.White.copy(alpha = 0.08f)
                            else Color.White.copy(alpha = 0.55f)
                        )
                        .border(
                            1.dp,
                            if (isDarkSurface) Color.White.copy(alpha = 0.12f)
                            else Color.Black.copy(alpha = 0.06f),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassRgbChannel(
                        label = "R",
                        channelColor = Color(0xFFE53935),
                        intValue = red,
                        onChange = { r -> applyRgb(r, green, blue) }
                    )
                    GlassRgbChannel(
                        label = "G",
                        channelColor = Color(0xFF43A047),
                        intValue = green,
                        onChange = { g -> applyRgb(red, g, blue) }
                    )
                    GlassRgbChannel(
                        label = "B",
                        channelColor = Color(0xFF1E88E5),
                        intValue = blue,
                        onChange = { b -> applyRgb(red, green, b) }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .drawGlass(
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(12.dp),
                            surfaceColor = AppTheme.colors.surfaceVariant
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(selectedColor)
                            .border(
                                1.dp,
                                AppTheme.colors.outline.copy(alpha = 0.45f),
                                RoundedCornerShape(8.dp)
                            )
                    )
                    Text(
                        text = hex,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = AppTheme.colors.onSurface,
                        modifier = Modifier.width(78.dp)
                    )
                    BasicTextField(
                        value = customName,
                        onValueChange = { customName = it.take(20) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = AppTheme.colors.onSurface,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(AppTheme.colors.success),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .drawGlass(
                                        backdrop = LocalPageBackdrop.current,
                                        shape = RoundedCornerShape(8.dp),
                                        surfaceColor = AppTheme.colors.surface
                                    )
                                    .border(
                                        1.dp,
                                        AppTheme.colors.outline.copy(alpha = 0.35f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (customName.isBlank()) {
                                    Text(
                                        "自定义名称",
                                        color = AppTheme.colors.onSurfaceVariant,
                                        fontSize = 13.sp
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onColorPicked(
                        selectedColor,
                        customName.trim().ifBlank { "自定义纯色" }
                    )
                }
            ) {
                Text("确定", color = AppTheme.colors.success, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppTheme.colors.onSurfaceVariant)
            }
        },
        containerColor = AppTheme.colors.surface
    )
}

@Composable
private fun GlassRgbChannel(
    label: String,
    channelColor: Color,
    intValue: Int,
    onChange: (Int) -> Unit
) {
    val clamped = intValue.coerceIn(0, 255)
    var text by remember(clamped) { mutableStateOf(clamped.toString()) }

    LaunchedEffect(clamped) {
        if (text.toIntOrNull() != clamped) {
            text = clamped.toString()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            modifier = Modifier.width(16.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.onSurface
        )
        SolidThumbSlider(
            value = clamped / 255f,
            onValueChange = { v ->
                val n = (v.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
                text = n.toString()
                onChange(n)
            },
            trackColor = channelColor,
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
        )
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(3)
                text = digits
                val n = digits.toIntOrNull()
                if (n != null) {
                    onChange(n.coerceIn(0, 255))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                color = AppTheme.colors.onSurface,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                fontWeight = FontWeight.Medium
            ),
            cursorBrush = SolidColor(channelColor),
            modifier = Modifier
                .width(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surface)
                .border(1.dp, AppTheme.colors.outline.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SolidThumbSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor.copy(alpha = 0.18f))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onValueChange((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0) {
                        onValueChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(value.coerceIn(0.001f, 1f))
                .background(
                    Brush.horizontalGradient(
                        listOf(trackColor.copy(alpha = 0.35f), trackColor)
                    )
                )
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = (value.coerceIn(0f, 1f) * size.width).coerceIn(10f, size.width - 10f)
            val cy = size.height / 2f
            drawCircle(color = Color.White, radius = 9f, center = Offset(cx, cy))
            drawCircle(color = trackColor, radius = 7f, center = Offset(cx, cy))
            drawCircle(
                color = Color.Black.copy(alpha = 0.18f),
                radius = 9f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
        }
    }
}

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val pureHue = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(hue) {
                detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0) {
                        onChange(
                            (offset.x / size.width).coerceIn(0f, 1f),
                            (1f - offset.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0 && size.height > 0) {
                        onChange(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, pureHue)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = saturation * size.width
            val cy = (1f - value) * size.height
            drawCircle(
                color = Color.White,
                radius = 10f,
                center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = 12f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
        }
    }
}

@Composable
private fun VerticalHueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hues = listOf(
        Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(hues))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.height > 0) {
                        onHueChange((offset.y / size.height * 360f).coerceIn(0f, 360f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (size.height > 0) {
                        onHueChange((change.position.y / size.height * 360f).coerceIn(0f, 360f))
                    }
                }
            }
    ) {
        val fraction = (hue / 360f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceAtLeast(0.001f))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
            )
        }
    }
}

private fun Color.toHsv(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}
