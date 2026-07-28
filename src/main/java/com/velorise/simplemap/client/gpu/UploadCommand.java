package com.velorise.simplemap.client.gpu;

import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.pipeline.RevisionStamp;

/** One shared upload-engine transaction. */
public record UploadCommand(TileKey key, RevisionStamp stamp,
        MapRequestLane lane, int byteCount, long contentRevision,
        UploadBufferLease payload, Runnable uploadAction,
        Runnable committed, Runnable rejected) {
    public UploadCommand {
        if (lane == null) lane = MapRequestLane.BACKGROUND;
        if (byteCount < 0) throw new IllegalArgumentException("byteCount");
        if (uploadAction == null) throw new IllegalArgumentException("uploadAction");
    }

    public boolean current() {
        return stamp == null || stamp.isCurrent();
    }
}
