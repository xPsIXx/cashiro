package com.pennywiseai.tracker.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Performs a confirmation haptic feedback if the device supports it.
 * Falls back to a generic virtual-key feedback on older devices so we stay
 * compatible down to minSdk without lint InlinedApi warnings.
 */
fun View.performConfirmHapticFeedback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
