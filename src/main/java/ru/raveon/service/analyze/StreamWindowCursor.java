package ru.raveon.service.analyze;

import ru.raveon.model.ModelId;
import java.util.Arrays;
import java.util.UUID;

/** One cursor per player/model/session; only acknowledged overlap may be omitted. */
public final class StreamWindowCursor {
    public record Offer(long sourceEnd, byte[] snapshot) {}
    public record Request(UUID key, UUID player, ModelId model, long from, long end, Offer offer, byte[] bytes) {}
    private final UUID player;
    private final ModelId model;
    private final int window, step, rowBytes;
    private UUID key = UUID.randomUUID();
    private long offeredEnd, acknowledgedSource, acknowledgedEnd, appliedEnd;
    private byte[] acknowledged;
    private Request pending;

    public StreamWindowCursor(UUID player, ModelId model, int window, int step) {
        if (window < model.sequence() || window > 600 || step < 1 || step > window)
            throw new IllegalArgumentException("Invalid capture shape");
        this.player=player; this.model=model; this.window=window; this.step=step;
        rowBytes=model.features()*Float.BYTES;
    }

    public synchronized Offer offer(byte[] fullWindow) {
        if (fullWindow.length != window*rowBytes) throw new IllegalArgumentException("Capture shape mismatch");
        offeredEnd = offeredEnd == 0 ? window : Math.addExact(offeredEnd,step);
        return new Offer(offeredEnd,fullWindow.clone());
    }

    public synchronized Request request(Offer offer) {
        if (offer.sourceEnd() <= acknowledgedSource) throw new IllegalArgumentException("Outdated offer");
        long delta=offer.sourceEnd()-acknowledgedSource;
        boolean continuous=acknowledged != null && delta > 0 && delta < window;
        int rows=continuous ? (int)delta : window;
        if (continuous) {
            int overlap=(window-rows)*rowBytes;
            continuous=Arrays.equals(acknowledged, rows*rowBytes, acknowledged.length, offer.snapshot(),0,overlap);
        }
        if (!continuous && acknowledged != null) {
            key=UUID.randomUUID(); acknowledged=null; acknowledgedEnd=appliedEnd=0;
        }
        rows=continuous ? rows : window;
        long from=continuous ? acknowledgedEnd+1 : 1;
        byte[] values=Arrays.copyOfRange(offer.snapshot(),(window-rows)*rowBytes,offer.snapshot().length);
        pending=new Request(key,player,model,from,from+rows-1,offer,StreamWire.encode(key,player,model,from,values));
        return pending;
    }

    public synchronized void acknowledge(Request request,long have) {
        if (pending != request || !request.key().equals(key) || have != request.end())
            throw new IllegalArgumentException("Unexpected stream acknowledgement");
        acknowledged=request.offer().snapshot().clone();
        acknowledgedSource=request.offer().sourceEnd(); acknowledgedEnd=have;
    }

    public synchronized boolean claim(Request request) {
        if (pending != request || !request.key().equals(key) || acknowledgedEnd != request.end()
                || request.end() <= appliedEnd) return false;
        appliedEnd=request.end();
        return true;
    }
}
