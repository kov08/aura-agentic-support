package org.aura.aura.classification;

// Four levels, deliberately coarse: finer scales (1-10) invite arbitrary distinctions the
// model can't ground, which shows up as noisy, unstable rankings between near-identical tickets.
public enum TicketUrgency {
    LOW,
    // MEDIUM doubles as the fallback urgency: when classification fails we assume "normal
    // queue" rather than silently burying (LOW) or falsely alarming (CRITICAL).
    MEDIUM,
    HIGH,
    CRITICAL
}
