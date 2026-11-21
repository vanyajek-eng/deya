package com.powerhub.deya

/**
 * Pure alert state machine. Call on every successful telemetry poll.
 * A grid transition is emitted only after the candidate state remains unchanged for 30s.
 */
class AlertRules(
    private val gridDelayMs: Long = 30_000L,
    private val overloadKw: Float = 3.0f
) {
    private var stableGrid: Boolean? = null
    private var candidateGrid: Boolean? = null
    private var candidateSince: Long = 0L
    private var lowBatterySent = false
    private var overloadSent = false

    data class Events(val gridChangedTo: Boolean? = null, val lowBattery: Boolean = false, val overload: Boolean = false)

    fun evaluate(t: Telemetry, lowSocThreshold: Float, nowMs: Long = System.currentTimeMillis()): Events {
        var gridEvent: Boolean? = null
        if (stableGrid == null) {
            stableGrid = t.gridPresent
            candidateGrid = t.gridPresent
            candidateSince = nowMs
        } else if (t.gridPresent != stableGrid) {
            if (candidateGrid != t.gridPresent) {
                candidateGrid = t.gridPresent
                candidateSince = nowMs
            } else if (nowMs - candidateSince >= gridDelayMs) {
                stableGrid = t.gridPresent
                gridEvent = stableGrid
            }
        } else {
            candidateGrid = t.gridPresent
            candidateSince = nowMs
        }

        val low = t.soc <= lowSocThreshold && !lowBatterySent
        if (t.soc > lowSocThreshold + 2f) lowBatterySent = false
        if (low) lowBatterySent = true

        val high = !t.gridPresent && t.loadKw >= overloadKw && !overloadSent
        if (t.gridPresent || t.loadKw < overloadKw - 0.2f) overloadSent = false
        if (high) overloadSent = true

        return Events(gridEvent, low, high)
    }
}
