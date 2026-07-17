package com.velorise.simplemap.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Retained as a compatibility facade. Global directory scans and background GPU
 * calls were deliberately removed; visible regions are now requested lazily by
 * the renderers through {@link MapProcessor}.
 */
public final class RegionProcessor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final RegionProcessor INSTANCE = new RegionProcessor();
    private volatile boolean running;

    private RegionProcessor() {
    }

    public static RegionProcessor getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        LOGGER.debug("RegionProcessor compatibility facade enabled (lazy mode)");
    }

    public synchronized void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
