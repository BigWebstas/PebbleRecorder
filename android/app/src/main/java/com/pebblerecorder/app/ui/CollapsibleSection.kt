package com.pebblerecorder.app.ui

import android.content.SharedPreferences
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.pebblerecorder.app.R

/**
 * Turns a section [header] into a tap-to-expand title for [body], with a chevron. Opening and
 * closing animates the layout under [transitionRoot]. Whether each section is open is remembered
 * in [prefs] under [key].
 */
fun makeCollapsible(
    header: TextView,
    body: View,
    transitionRoot: ViewGroup,
    prefs: SharedPreferences,
    key: String,
    defaultOpen: Boolean,
) {
    fun show(open: Boolean) {
        body.visibility = if (open) View.VISIBLE else View.GONE
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more, 0,
        )
    }
    show(prefs.getBoolean(key, defaultOpen))
    header.setOnClickListener {
        TransitionManager.beginDelayedTransition(transitionRoot, quickTransition()) // Material motion: the card eases open
        val open = body.visibility != View.VISIBLE
        prefs.edit().putBoolean(key, open).apply()
        show(open)
    }
}
