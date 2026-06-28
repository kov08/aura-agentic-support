package org.aura.aura.resolver;

import java.util.List;

// The seam. Resolver depends on THIS, never on a Map or a concrete class.
// Phase 3: PgVectorKnowledgeBase implements this same interface → drop-in. Resolver unchanged.
public interface KnowledgeBase {
    List<KbEntry> retrieve(String query);
}
