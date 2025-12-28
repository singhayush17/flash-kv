package io.github.whyush.flashkv.metrics;

import java.util.concurrent.atomic.LongAdder;

public class Metrics {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private final LongAdder connections = new LongAdder();
    private final LongAdder commands = new LongAdder();

    public void recordHit() { hits.increment(); }
    public void recordMiss() { misses.increment(); }
    public void recordEviction() { evictions.increment(); }
    public void recordExpired() { expired.increment(); }
    public void recordExpired(int n) { expired.add(n); }
    public void recordConnection() { connections.increment(); }
    public void recordCommand() { commands.increment(); }

    public String snapshot() {
        return String.format(
                "hits=%d misses=%d evictions=%d expired=%d connections=%d commands=%d",
                hits.sum(), misses.sum(), evictions.sum(),
                expired.sum(), connections.sum(), commands.sum()
        );
    }
}

