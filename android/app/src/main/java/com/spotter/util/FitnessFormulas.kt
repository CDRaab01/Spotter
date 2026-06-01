package com.spotter.util

fun estimatedOneRM(weightLbs: Double, reps: Int): Double =
    if (reps <= 1) weightLbs else weightLbs * (1 + reps / 30.0)
