package app.cover.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Tokyo Night, the palette Omarchy ships as a built-in theme. */
object Tn {
    val bg = Color(0xFF1A1B26)
    val bgDark = Color(0xFF16161E)
    val fg = Color(0xFFC0CAF5)
    val dim = Color(0xFF565F89)
    val border = Color(0xFF3B4261)
    val blue = Color(0xFF7AA2F7)
    val purple = Color(0xFFBB9AF7)
    val green = Color(0xFF9ECE6A)
    val orange = Color(0xFFFF9E64)
    val red = Color(0xFFF7768E)
    val cyan = Color(0xFF7DCFFF)
    val yellow = Color(0xFFE0AF68)
}

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

val Body = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Tn.fg, lineHeight = 18.sp)
val Label = Body.copy(fontSize = 11.sp, color = Tn.dim, lineHeight = 14.sp)
