package com.application.motium.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.min

@Composable
fun ColorPickerDialog(
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
    textColor: Color,
    surfaceColor: Color
) {
    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(1f) }
    var value by remember { mutableStateOf(1f) }

    // Initialize HSV from current color
    LaunchedEffect(currentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb(
                (currentColor.red * 255).toInt(),
                (currentColor.green * 255).toInt(),
                (currentColor.blue * 255).toInt()
            ),
            hsv
        )
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    val selectedColor = remember(hue, saturation, value) {
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
        Color(color)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    "Choisir la couleur de l'app",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    fontSize = 20.sp,
                    color = textColor
                )

                // Color preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(selectedColor)
                        .border(2.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                )

                // Hue bar (rainbow)
                HueBar(
                    hue = hue,
                    onHueChange = { hue = it }
                )

                // Saturation/Value picker
                SaturationValuePicker(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onSaturationValueChange = { s, v ->
                        saturation = s
                        value = v
                    }
                )

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Annuler", color = textColor)
                    }
                    Button(
                        onClick = {
                            onColorSelected(selectedColor)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = selectedColor
                        )
                    ) {
                        Text("Appliquer", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit
) {
    var barWidth by remember { mutableStateOf(0f) }
    val density = LocalDensity.current.density

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val offsetX = change.position.x.coerceIn(0f, size.width.toFloat())
                        val newHue = (offsetX / size.width) * 360f
                        onHueChange(newHue)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val offsetX = offset.x.coerceIn(0f, size.width.toFloat())
                        val newHue = (offsetX / size.width) * 360f
                        onHueChange(newHue)
                    }
                }
        ) {
            barWidth = size.width

            // Draw rainbow gradient
            val colors = (0..6).map { i ->
                val h = i * 60f
                val color = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))
                Color(color)
            }

            drawRect(
                brush = Brush.horizontalGradient(colors),
                size = size
            )
        }

        // Indicator
        if (barWidth > 0f) {
            val indicatorX = (hue / 360f) * barWidth
            Box(
                modifier = Modifier
                    .offset(x = (indicatorX / density).dp - 12.dp, y = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color.Gray, CircleShape)
            )
        }
    }
}

@Composable
private fun SaturationValuePicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit
) {
    var pickerWidth by remember { mutableStateOf(0f) }
    var pickerHeight by remember { mutableStateOf(0f) }
    val density = LocalDensity.current.density

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val offsetX = change.position.x.coerceIn(0f, size.width.toFloat())
                        val offsetY = change.position.y.coerceIn(0f, size.height.toFloat())

                        val newSaturation = offsetX / size.width
                        val newValue = 1f - (offsetY / size.height)
                        onSaturationValueChange(newSaturation, newValue)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val offsetX = offset.x.coerceIn(0f, size.width.toFloat())
                        val offsetY = offset.y.coerceIn(0f, size.height.toFloat())

                        val newSaturation = offsetX / size.width
                        val newValue = 1f - (offsetY / size.height)
                        onSaturationValueChange(newSaturation, newValue)
                    }
                }
        ) {
            pickerWidth = size.width
            pickerHeight = size.height

            // Get the pure hue color
            val pureHueColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))

            // Draw saturation gradient (left to right: white to pure hue)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, Color(pureHueColor))
                ),
                size = size
            )

            // Draw value gradient (top to bottom: transparent to black)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black)
                ),
                size = size
            )
        }

        // Indicator circle
        if (pickerWidth > 0f && pickerHeight > 0f) {
            val indicatorX = saturation * pickerWidth
            val indicatorY = (1f - value) * pickerHeight

            Box(
                modifier = Modifier
                    .offset(x = (indicatorX / density).dp - 12.dp, y = (indicatorY / density).dp - 12.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(3.dp, Color.White, CircleShape)
                    .border(2.dp, Color.Black, CircleShape)
            )
        }
    }
}


