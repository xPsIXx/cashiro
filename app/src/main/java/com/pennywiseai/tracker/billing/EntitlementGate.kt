package com.pennywiseai.tracker.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cashiro ships every feature unlocked. Billing/paywall is not used.
 */
@Singleton
class EntitlementGate @Inject constructor(
    @Suppress("unused") entitlementSource: EntitlementSource,
) {
    val isProEntitled: StateFlow<Boolean> = MutableStateFlow(true)
}
