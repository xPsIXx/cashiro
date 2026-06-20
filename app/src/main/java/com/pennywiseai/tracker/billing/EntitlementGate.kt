package com.pennywiseai.tracker.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub EntitlementGate for Firefly fork (no Pro billing).
 * Always entitled so all account features (merge etc) are available.
 */
@Singleton
class EntitlementGate @Inject constructor() {
    val isProEntitled: StateFlow<Boolean> = MutableStateFlow(true)
}

