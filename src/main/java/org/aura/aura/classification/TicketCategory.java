package org.aura.aura.classification;

// Closed taxonomy, not free text: structured outputs constrain the model to these exact
// names, so a category can never arrive misspelled or invented. Downstream routing
// (Day 7+) can switch on it without defensive string matching.
public enum TicketCategory {
    ORDER_STATUS,
    SHIPPING,
    RETURNS_AND_REFUNDS,
    BILLING,
    ACCOUNT,
    PRODUCT_QUESTION,
    // OTHER is the escape valve: forcing every ticket into a "real" category would
    // teach the model to guess. It is also the safe landing spot for the fallback path.
    OTHER
}
