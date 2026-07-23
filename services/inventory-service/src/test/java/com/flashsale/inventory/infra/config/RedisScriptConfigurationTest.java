package com.flashsale.inventory.infra.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.script.RedisScript;

class RedisScriptConfigurationTest {

    private static final String APPROVED_SCRIPT_SHA1 =
            "2dab8322003da880c4aa8a4f55ca9aefaadc0215";

    @Test
    void loadsApprovedScriptWithLongResultAndStableSha() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RedisScriptConfiguration.class)) {
            RedisScript<?> first = context.getBean("stockDecrementScript", RedisScript.class);
            RedisScript<?> second = context.getBean("stockDecrementScript", RedisScript.class);

            String scriptText = first.getScriptAsString();
            String firstSha = first.getSha1();

            assertTrue(scriptText.contains("redis.call('DECRBY', KEYS[1], qty)"));
            assertTrue(scriptText.contains("return -2"));
            assertTrue(scriptText.contains("return -1"));
            assertEquals(Long.class, first.getResultType());
            assertEquals(APPROVED_SCRIPT_SHA1, firstSha);
            assertSame(first, second, "Spring must expose one script instance for SHA reuse");
            assertSame(firstSha, first.getSha1(), "DefaultRedisScript must cache its SHA");
        }
    }
}
