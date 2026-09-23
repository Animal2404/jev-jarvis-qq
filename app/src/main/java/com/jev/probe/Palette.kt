package com.jev.probe

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * The app's semantic colour palette, resolved for the current light/dark mode.
 *
 * Why this exists: every screen is built in Kotlin (no layout XML), so all 74
 * colour literals used to sit inline in the activities. That is what produced
 * "全白只剩字" — a view kept `Color.WHITE` background while the system switched
 * to dark and its text colour came from a dark-mode-aware source, leaving white
 * text on a white card.
 *
 * There is ONE source of truth here, and it has two value sets. Every view asks
 * this object for a role (`bg`, `card`, `ink`, …) instead of naming a colour, so
 * light and dark cannot drift apart, and nothing can be missed.
 *
 * A code-built screen cannot use `?attr/...` (that needs an XML theme lookup and
 * a `Context` with the theme applied), so the palette is selected from the
 * configuration's night flag directly. `Resources` re-reads that flag on every
 * call, so a live system switch is picked up on the next redraw.
 */
object Palette {

    /** True when the system is in night mode right now. */
    fun isNight(ctx: Context): Boolean = (ctx.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Screen background behind the cards. */
    fun bg(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#121212") else Color.parseColor("#F2F3F5")

    /** Card / panel surface. */
    fun card(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#1E1E1E") else Color.WHITE

    /** A surface slightly raised above [card] (e.g. reply cards, transcript box). */
    fun cardAlt(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#2A2A2A") else Color.parseColor("#F3F4F6")

    /** Primary text. */
    fun ink(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#E8EAED") else Color.parseColor("#111827")

    /** Secondary text. */
    fun sub(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#9AA0A6") else Color.parseColor("#6B7280")

    /** Tertiary / hint text. */
    fun hint(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#7F8489") else Color.parseColor("#9CA3AF")

    /** Accent (buttons, links, my own bubbles). */
    fun accent(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#7AA2FF") else Color.parseColor("#3A7AFE")

    /** Text ON an accent-filled surface. */
    fun onAccent(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#0B1220") else Color.WHITE

    /** Inactive pill / toggle track. */
    fun pillOff(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#33363B") else Color.parseColor("#EEF1F5")

    /** Hairline border on cards and outlined buttons. */
    fun stroke(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#3C4043") else Color.parseColor("#22000000")

    /** Divider inside the overlay panel. */
    fun divider(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#3C4043") else Color.parseColor("#1F000000")

    /** Selected reply card highlight. */
    fun selected(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#243447") else Color.parseColor("#EAF1FF")

    /** Edit-field background. */
    fun field(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#2A2A2A") else Color.parseColor("#F3F4F6")

    /** Transcript box in the overlay. */
    fun surface(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#232323") else Color.parseColor("#F9FAFB")

    // ---- status colours (readable on both surfaces) ----
    fun danger(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#F87171") else Color.parseColor("#DC2626")
    fun warn(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
    fun ok(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#4ADE80") else Color.parseColor("#16A34A")
    fun purple(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#C4B5FD") else Color.parseColor("#7C3AED")
    fun blue(ctx: Context): Int = if (isNight(ctx)) Color.parseColor("#7AA2FF") else Color.parseColor("#3A7AFE")

    /** The overlay panel background, with the user's opacity applied. */
    fun panel(ctx: Context, opacityPercent: Int): Int {
        val a = (opacityPercent / 100f * 255).toInt().coerceIn(150, 255)
        val base = if (isNight(ctx)) 30 else 255   // dark panel at night, white by day
        return Color.argb(a, base, base, base)
    }
}
