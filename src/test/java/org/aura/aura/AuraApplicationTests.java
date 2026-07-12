package org.aura.aura;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// "test" profile excludes ConversationRunner (a dev-time demo that fires a live Claude call on
// startup); this smoke test only needs the context to BUILD, which it does without an API key.
@ActiveProfiles("test")
@SpringBootTest
class AuraApplicationTests {

    @Test
    void contextLoads() {
    }

}
