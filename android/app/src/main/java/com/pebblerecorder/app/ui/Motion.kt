package com.pebblerecorder.app.ui

import android.transition.AutoTransition
import android.transition.Transition

// Android's default layout transition takes 300 ms, which feels sluggish for expanding a card.
private const val QUICK_MS = 180L

/** The layout transition used when sections open and close: Material-style easing, a touch quicker than the default. */
fun quickTransition(): Transition = AutoTransition().setDuration(QUICK_MS)
