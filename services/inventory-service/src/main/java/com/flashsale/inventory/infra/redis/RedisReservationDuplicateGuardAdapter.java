package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.application.port.ReservationDuplicateGuardPort;
import com.flashsale.inventory.application.port.ReservationDuplicateGuardUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Duration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis SET NX EX 30 implementation of the reservation duplicate guard.
 *
 * <p>Key: {@code resv:lock:{userId}:{saleId}} — 30-second TTL, no hash tag.
 * If Redis is unavailable, propagates {@link ReservationDuplicateGuardUnavailableException};
 * the caller logs and falls through to the durable DB guard.
 */
@Component
public class RedisReservationDuplicateGuardAdapter implements ReservationDuplicateGuardPort {

    private final StringRedisTemplate redisTemplate;

    public RedisReservationDuplicateGuardAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(UserId userId, SaleId saleId) {
        String key = "resv:lock:" + userId.value() + ":" + saleId.value();
        try {
            Boolean result = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(30));
            if (result == null) {
                throw new ReservationDuplicateGuardUnavailableException(
                        "Reservation duplicate guard returned null result for key: " + key);
            }
            return result;
        } catch (ReservationDuplicateGuardUnavailableException ex) {
            throw ex;
        } catch (DataAccessResourceFailureException | QueryTimeoutException ex) {
            throw new ReservationDuplicateGuardUnavailableException(
                    "Reservation duplicate guard unavailable for key: " + key, ex);
        }
    }
}
