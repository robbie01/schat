package net.sohio.chat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class AtomicSnowflake {
    private AtomicReference<Snowflake> snowflake;

    public AtomicSnowflake() {
        snowflake = new AtomicReference<>();
    }

    public AtomicSnowflake(Snowflake start) {
        snowflake = new AtomicReference<>(start);
    }

    public Snowflake incrementAndGet(Instant timestamp) {
        return snowflake.updateAndGet(s -> {
            if (s == null) {
                return new Snowflake(timestamp, (short)0);
            } else {
                return s.increment(timestamp);
            }
        });
    }
}
