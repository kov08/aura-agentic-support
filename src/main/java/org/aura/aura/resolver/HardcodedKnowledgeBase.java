package org.aura.aura.resolver;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class HardcodedKnowledgeBase implements KnowledgeBase {

    // Fake-backed ShopFast facts. ~5 entries is enough to demo + to break.
    private static final List<KbEntry> ENTRIES = List.of(
        new KbEntry("kb-returns", "Return window",
            "ShopFast accepts returns within 30 days of delivery for a full refund. Items must be unused."),
        new KbEntry("kb-shipping", "Shipping times",
            "Standard shipping is 5-7 business days. Express is 2 business days."),
        new KbEntry("kb-refund-time", "Refund processing",
            "Refunds are processed within 5-10 business days after the item is received at our warehouse."),
        new KbEntry("kb-cancel", "Order cancellation",
            "Orders can be cancelled within 1 hour of placement, before they enter fulfilment."),
        new KbEntry("kb-damaged", "Damaged items",
            "Report damaged items within 48 hours of delivery with a photo for a replacement or refund.")
    );

    // NAIVE ON PURPOSE: exact-ish keyword overlap. This WILL whiff on paraphrases —
    // and that whiff is the hand-rolled argument for semantic embeddings in Phase 3.
    @Override
    public List<KbEntry> retrieve(String query) {
        String q = query.toLowerCase();
        return ENTRIES.stream()
            .filter(e -> Arrays.stream(e.title().toLowerCase().split("\\W+"))
                               .anyMatch(token -> token.length() > 3 && q.contains(token)))
            .toList();
    }
}
