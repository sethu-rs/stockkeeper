package com.stockkeeper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class StockKeeperApplicationTests {

    @Test
    void contextLoads() {
        // Verifies that the Spring context starts successfully,
        // including capacity-config.yml loading and DynamoDB client wiring.
    }
}
