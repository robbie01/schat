package net.sohio.chat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

record Snowflake(long rep) implements Comparable<Snowflake> {
    public static Instant EPOCH = Instant.ofEpochMilli(1704085200000L); // 1/1/24 EST

    private Snowflake(long ts, short unsignedSequence) throws IllegalArgumentException {
        this(buildRep(ts, unsignedSequence));
    }

    public Snowflake(Instant timestamp, short unsignedSequence) throws IllegalArgumentException {
        this(instantToTs(timestamp), unsignedSequence);
    }

    private static long instantToTs(Instant timestamp) {
        return EPOCH.until(timestamp, ChronoUnit.MILLIS);
    }

    private static long buildRep(long ts, short unsignedSequence) {
        if (ts < 0 || ts >= (1L << 47))
            throw new IllegalArgumentException("timestamp is out of range");
        return (ts << 16) | Short.toUnsignedLong(unsignedSequence);
    }

    private long ts() {
        return rep >>> 16;
    }

    public Instant timestamp() {
        return EPOCH.plusMillis(ts());
    }

    public int sequence() {
        return (int)(rep & 0xFFFF);
    }

    public Snowflake increment(Instant timestamp) {
        long ts = ts();
        long sequence = sequence();
        long now = instantToTs(timestamp);

        if (ts >= now) {
            if (sequence == Short.MIN_VALUE) { // actually max
                return new Snowflake(ts+1, (short)0);
            } else {
                return new Snowflake(ts, (short)(sequence+1));
            }
        } else {
            return new Snowflake(now, (short)0);
        }
    }

    @Override
    public String toString() {
        return Long.toString(rep);
    }

    @Override
    public int compareTo(Snowflake other) {
        return Long.compare(rep, other.rep);
    }
}
