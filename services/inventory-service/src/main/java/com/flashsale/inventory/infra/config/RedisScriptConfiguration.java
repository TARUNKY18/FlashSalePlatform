package com.flashsale.inventory.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Loads the approved Inventory Lua scripts as singleton Spring beans.
 */
@Configuration(proxyBeanMethods = false)
public class RedisScriptConfiguration {

    @Bean
    public DefaultRedisScript<Long> stockDecrementScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/stock-decrement.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
