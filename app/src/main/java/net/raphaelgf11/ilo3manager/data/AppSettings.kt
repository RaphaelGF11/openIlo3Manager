package net.raphaelgf11.ilo3manager.data

data class AppSettings(
    val autoRefreshSeconds: Int = 30,
    /**
     * Whether the interval above is shortened when the dashboard runs over IPMI.
     *
     * An IPMI reading costs a UDP round trip on an already-open session; the SSH path spawns a
     * command and parses its output, an order of magnitude slower. One interval cannot suit both.
     */
    val fasterRefreshOverIpmi: Boolean = true,
    /** Divides [autoRefreshSeconds] when the above applies. */
    val ipmiRefreshDivider: Int = 3,
) {
    /** Interval to actually wait between two refreshes, given the transport in use. */
    fun refreshSecondsFor(usesIpmi: Boolean): Int {
        if (!usesIpmi || !fasterRefreshOverIpmi) return autoRefreshSeconds
        return (autoRefreshSeconds / ipmiRefreshDivider).coerceAtLeast(MIN_REFRESH_SECONDS)
    }

    private companion object {
        /** Below this the iLO gains nothing and the phone's radio never settles. */
        const val MIN_REFRESH_SECONDS = 5
    }
}
