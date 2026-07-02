package org.aura.aura.classification;

// Intent is orthogonal to category: "where is my refund?" (RETURNS_AND_REFUNDS +
// GET_INFORMATION) needs a different playbook than "refund me now" (RETURNS_AND_REFUNDS +
// REQUEST_ACTION). Capturing both axes is what makes routing decisions possible later.
public enum TicketIntent {
    // GET_INFORMATION doubles as the fallback intent: answering with information is the
    // least destructive default — an action taken on a misread ticket is far costlier
    // than an answer that merely misses the mark.
    GET_INFORMATION,
    REQUEST_ACTION,
    REPORT_PROBLEM,
    PROVIDE_FEEDBACK
}
