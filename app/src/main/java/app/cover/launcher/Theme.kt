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

/** Kanagawa Dragon, another Omarchy theme: warm ink for page 2 so it reads as "the other page" at a glance. */
object Kd {
    val bg = Color(0xFF181616)
    val bgDark = Color(0xFF0D0C0C)
    val fg = Color(0xFFC5C9C5)
    val dim = Color(0xFFA6A69C)
    val faint = Color(0xFF737C73)
    val border = Color(0xFF393836)
    val amber = Color(0xFFE6C384)
    val sage = Color(0xFF87A987)
    val red = Color(0xFFC4746E)
}

/** Jade on black for the Signal page, after Omarchy's Osaka Jade: the "terminal" page. */
object Jd {
    val bg = Color(0xFF111C18)
    val bgDark = Color(0xFF0A120F)
    val fg = Color(0xFFC1C497)
    val dim = Color(0xFF8C9A86)
    val faint = Color(0xFF5E6E62)
    val border = Color(0xFF23372B)
    val jade = Color(0xFF63B07A)
    val gold = Color(0xFFE5C76B)
    val red = Color(0xFFD9796B)
}

/** Near-black for page 4 (after Omarchy's Matte Black), so the album's own colours carry the page. */
object Mb {
    val bg = Color(0xFF121212)
    val fg = Color(0xFFBEBEBE)
    val dim = Color(0xFF8A8A8D)
    val faint = Color(0xFF55555A)
    val border = Color(0xFF2A2A2D)
}

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

val Body = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Tn.fg, lineHeight = 18.sp)
val Label = Body.copy(fontSize = 11.sp, color = Tn.dim, lineHeight = 14.sp)
