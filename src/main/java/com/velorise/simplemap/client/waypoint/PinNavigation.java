package com.velorise.simplemap.client.waypoint;


import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapViewportCoordinator;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.velorise.simplemap.client.MapManager.MapConfig;
import net.minecraft.client.Minecraft;

/**
 * Stable world-space navigation-pin geometry.
 *
 * <p>The route always connects the player's current world position to the fixed
 * destination. Stability comes from defining the complete world-space segment
 * first and clipping only after its dot lattice has been chosen. Dots are phased
 * from the destination backwards, so walking straight toward the pin removes dots
 * behind the player instead of sliding every dot along the terrain. Viewport edges
 * never become navigation endpoints.</p>
 */
public final class PinNavigation {
    public static final double ROUTE_MARGIN_BLOCKS = 4.0;
    public static final double ROUTE_DOT_SPACING_BLOCKS = 8.0;
    public static final int MAX_VISIBLE_ROUTE_DOTS = 256;

    private PinNavigation() {
    }

    /** Activates a new destination. Legacy persisted route-origin fields are kept only
     * for save compatibility; rendering no longer treats them as a fixed path origin. */
    public static void activate(double targetX, double targetZ) {
        if (!validCoordinate(targetX) || !validCoordinate(targetZ)) return;
        MapConfig.pinWorldX = targetX;
        MapConfig.pinWorldZ = targetZ;
        MapConfig.pinActive = true;
        MapConfig.pinRouteStartValid = false;
        MapConfig.pinRouteStartWorldX = 0.0;
        MapConfig.pinRouteStartWorldZ = 0.0;
    }

    /** Moves an already tracked destination. */
    public static void updateDestination(double targetX, double targetZ) {
        if (!validCoordinate(targetX) || !validCoordinate(targetZ)) return;
        MapConfig.pinWorldX = targetX;
        MapConfig.pinWorldZ = targetZ;
    }

    public static void clear() {
        MapConfig.pinActive = false;
        MapConfig.pinRouteStartValid = false;
        MapConfig.pinRouteStartWorldX = 0.0;
        MapConfig.pinRouteStartWorldZ = 0.0;
    }

    /**
     * Compatibility hook for old pin save data. Route rendering is now live and does
     * not require a persisted origin, so no new route origin needs to be captured.
     */
    public static boolean captureRouteStartIfMissing() {
        return false;
    }

    /** Uses the current player position when an explicit interpolated origin is not
     * available. Renderers should prefer {@link #currentRoute(double, double)}. */
    public static Route currentRoute() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return null;
        return currentRoute(minecraft.player.getX(), minecraft.player.getZ());
    }

    /**
     * Builds the complete navigation segment from the live player origin to the pin.
     * The viewport is intentionally not part of this calculation.
     */
    public static Route currentRoute(double startX, double startZ) {
        if (!MapConfig.pinActive
                || !validCoordinate(startX) || !validCoordinate(startZ)
                || !validCoordinate(MapConfig.pinWorldX)
                || !validCoordinate(MapConfig.pinWorldZ)) {
            return null;
        }
        /*
         * Navigation geometry is block-owned, not sub-pixel player-owned. Holding
         * the route origin at the centre of the player's current block means normal
         * movement inside one block cannot make the destination-phased guide lattice
         * wobble between neighbouring blocks. Crossing into a new block legitimately
         * advances the route origin and recomputes the path to the fixed target.
         */
        double routeStartX = blockCenter(startX);
        double routeStartZ = blockCenter(startZ);
        double dx = MapConfig.pinWorldX - routeStartX;
        double dz = MapConfig.pinWorldZ - routeStartZ;
        double length = Math.hypot(dx, dz);
        if (!(length > ROUTE_MARGIN_BLOCKS * 2.0)) return null;
        return new Route(routeStartX, routeStartZ,
                MapConfig.pinWorldX, MapConfig.pinWorldZ,
                dx, dz, length, dx / length, dz / length);
    }

    /**
     * Returns destination-phased dot indices whose full player-to-pin segment
     * intersects the supplied world rectangle. Clipping never redistributes dots.
     * {@code minWorldSpacing} may skip indices at far zoom, but the retained indices
     * remain aligned to the same destination-based lattice.
     */
    public static DotRange visibleDots(Route route,
            double minX, double maxX, double minZ, double maxZ,
            double minWorldSpacing, int maxDots) {
        if (route == null || maxX < minX || maxZ < minZ) return DotRange.EMPTY;
        double[] interval = {0.0, 1.0};
        if (!clipAxis(route.startX(), route.dx(), minX, maxX, interval)
                || !clipAxis(route.startZ(), route.dz(), minZ, maxZ, interval)) {
            return DotRange.EMPTY;
        }

        // t runs start -> target. Our dot phase runs target -> start, therefore the
        // visible distance-from-target interval is reversed relative to t.
        double minDistanceFromTarget = Math.max(ROUTE_MARGIN_BLOCKS,
                route.length() * (1.0 - interval[1]));
        double maxDistanceFromTarget = Math.min(
                route.length() - ROUTE_MARGIN_BLOCKS,
                route.length() * (1.0 - interval[0]));
        if (maxDistanceFromTarget < minDistanceFromTarget) return DotRange.EMPTY;

        int first = (int) Math.ceil((minDistanceFromTarget - ROUTE_MARGIN_BLOCKS)
                / ROUTE_DOT_SPACING_BLOCKS - 1.0e-9);
        int last = (int) Math.floor((maxDistanceFromTarget - ROUTE_MARGIN_BLOCKS)
                / ROUTE_DOT_SPACING_BLOCKS + 1.0e-9);
        first = Math.max(0, first);
        last = Math.min(route.lastDotIndex(), last);
        if (last < first) return DotRange.EMPTY;

        int stride = Math.max(1, (int) Math.ceil(
                Math.max(ROUTE_DOT_SPACING_BLOCKS, minWorldSpacing)
                        / ROUTE_DOT_SPACING_BLOCKS));
        first = alignUp(first, stride);
        if (first > last) return DotRange.EMPTY;

        int boundedMax = Math.max(1, maxDots);
        int count = 1 + (last - first) / stride;
        if (count > boundedMax) {
            int multiplier = (int) Math.ceil(count / (double) boundedMax);
            stride *= Math.max(1, multiplier);
            first = alignUp(first, stride);
            if (first > last) return DotRange.EMPTY;
        }
        return new DotRange(first, last, stride);
    }

    /** Dots are anchored from the fixed destination backwards toward the player. */
    public static double dotWorldX(Route route, int index) {
        double distance = ROUTE_MARGIN_BLOCKS
                + Math.max(0, index) * ROUTE_DOT_SPACING_BLOCKS;
        return blockCenter(route.targetX() - route.nx() * distance);
    }

    /** Dots are anchored from the fixed destination backwards toward the player. */
    public static double dotWorldZ(Route route, int index) {
        double distance = ROUTE_MARGIN_BLOCKS
                + Math.max(0, index) * ROUTE_DOT_SPACING_BLOCKS;
        return blockCenter(route.targetZ() - route.nz() * distance);
    }

    private static boolean clipAxis(double origin, double delta,
            double min, double max, double[] interval) {
        if (Math.abs(delta) < 1.0e-12) {
            return origin >= min && origin <= max;
        }
        double a = (min - origin) / delta;
        double b = (max - origin) / delta;
        if (a > b) {
            double swap = a;
            a = b;
            b = swap;
        }
        interval[0] = Math.max(interval[0], a);
        interval[1] = Math.min(interval[1], b);
        return interval[1] >= interval[0];
    }

    private static int alignUp(int value, int stride) {
        if (stride <= 1) return value;
        int remainder = Math.floorMod(value, stride);
        return remainder == 0 ? value : value + (stride - remainder);
    }

    private static boolean validCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000.0;
    }

    private static double blockCenter(double coordinate) {
        return Math.floor(coordinate) + 0.5;
    }

    public record Route(double startX, double startZ,
            double targetX, double targetZ,
            double dx, double dz, double length, double nx, double nz) {
        public int lastDotIndex() {
            double usable = length - ROUTE_MARGIN_BLOCKS * 2.0;
            return usable < 0.0 ? -1
                    : (int) Math.floor(usable / ROUTE_DOT_SPACING_BLOCKS);
        }
    }

    public record DotRange(int firstIndex, int lastIndex, int stride) {
        private static final DotRange EMPTY = new DotRange(1, 0, 1);

        public boolean isEmpty() {
            return lastIndex < firstIndex;
        }
    }


    public static class WaypointManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final WaypointManager INSTANCE = new WaypointManager();
    public static WaypointManager getInstance() {
        return INSTANCE;
    }

    public static class Waypoint {
        public final String name;
        public final double x;
        /** Optional vertical coordinate. Old waypoint files remain valid through hasY=false. */
        public final double y;
        public final double z;
        public final boolean hasY;
        public final int iconType; // -1: use custom item, 0..3: legacy presets
        public final String iconItem; // Item registry ID (e.g. "minecraft:diamond_pickaxe")
        public final float scale;     // Scale multiplier (0.1 to 2.0)
        public final String dimension;
        public final boolean deathPoint;

        /** Backward-compatible X/Z constructor used by old callers and old JSON. */
        public Waypoint(String name, double x, double z, int iconType, String iconItem,
                float scale, String dimension) {
            this(name, x, 0.0, z, false, iconType, iconItem, scale, dimension, false);
        }

        public Waypoint(String name, double x, double z, int iconType, String iconItem,
                float scale, String dimension, boolean deathPoint) {
            this(name, x, 0.0, z, false, iconType, iconItem, scale, dimension, deathPoint);
        }

        public Waypoint(String name, double x, double y, double z, int iconType,
                String iconItem, float scale, String dimension) {
            this(name, x, y, z, true, iconType, iconItem, scale, dimension, false);
        }

        public Waypoint(String name, double x, double y, double z, int iconType,
                String iconItem, float scale, String dimension, boolean deathPoint) {
            this(name, x, y, z, true, iconType, iconItem, scale, dimension, deathPoint);
        }

        private Waypoint(String name, double x, double y, double z, boolean hasY,
                int iconType, String iconItem, float scale, String dimension,
                boolean deathPoint) {
            this.name = sanitizeText(name, "Waypoint", 64);
            this.x = clampCoordinate(x);
            this.y = clampCoordinate(y);
            this.z = clampCoordinate(z);
            this.hasY = hasY && Double.isFinite(y);
            this.iconType = Math.max(-1, Math.min(3, iconType));
            this.iconItem = sanitizeText(iconItem, "", 128);
            this.scale = Float.isFinite(scale) ? Math.max(0.1f, Math.min(2.0f, scale)) : 1.0f;
            this.dimension = sanitizeText(dimension, "unknown", 256);
            this.deathPoint = deathPoint;
        }

        private static Waypoint loaded(Waypoint waypoint) {
            return new Waypoint(waypoint.name, waypoint.x, waypoint.y, waypoint.z,
                    waypoint.hasY, waypoint.iconType, waypoint.iconItem, waypoint.scale,
                    waypoint.dimension, waypoint.deathPoint);
        }
    }

    private final List<Waypoint> waypoints = new ArrayList<>();
    /** Immutable render snapshots rebuilt only when the waypoint set mutates. */
    private volatile List<Waypoint> allSnapshot = List.of();
    private volatile Map<String, List<Waypoint>> dimensionSnapshots = Map.of();
    /** Render-visible waypoint mutation generation for retained minimap frames. */
    private final AtomicLong visualRevision = new AtomicLong();
    private File currentWaypointsFile = null;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_WAYPOINTS = 4096;
    private static final double MAX_WORLD_COORDINATE = 30_000_000.0;

    private WaypointManager() {}

    public synchronized void updateWorldDir(File worldDir) {
        if (worldDir == null) {
            waypoints.clear();
            currentWaypointsFile = null;
            rebuildSnapshotsLocked();
            return;
        }
        currentWaypointsFile = new File(worldDir, "waypoints.json");
        loadWaypoints();
    }

    private synchronized void loadWaypoints() {
        waypoints.clear();
        if (currentWaypointsFile == null || !currentWaypointsFile.exists()) {
            rebuildSnapshotsLocked();
            return;
        }

        List<Waypoint> loaded = new ArrayList<>();
        try (var reader = Files.newBufferedReader(currentWaypointsFile.toPath(), StandardCharsets.UTF_8)) {
            Waypoint[] data = GSON.fromJson(reader, Waypoint[].class);
            if (data != null) {
                for (Waypoint waypoint : data) {
                    if (waypoint == null || !Double.isFinite(waypoint.x)
                            || !Double.isFinite(waypoint.z)) continue;
                    loaded.add(Waypoint.loaded(waypoint));
                    if (loaded.size() >= MAX_WAYPOINTS) break;
                }
            }
            waypoints.addAll(loaded);
        } catch (Exception exception) {
            LOGGER.error("Failed to load waypoints; preserving unreadable file", exception);
            waypoints.clear();
            quarantineCorruptFile();
        }
        rebuildSnapshotsLocked();
    }

    public synchronized void saveWaypoints() {
        if (currentWaypointsFile == null) return;
        Path target = currentWaypointsFile.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(waypoints, writer);
            }
            moveReplacing(temporary, target);
        } catch (IOException exception) {
            LOGGER.error("Failed to save waypoints atomically", exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    public List<Waypoint> getAllWaypoints() {
        return allSnapshot;
    }

    public long visualRevision() {
        return visualRevision.get();
    }

    public List<Waypoint> getWaypointsForDimension(String dimension) {
        String requested = MapManager.getInstance().resolveDimensionResourceId(dimension);
        return dimensionSnapshots.getOrDefault(requested, List.of());
    }

    public synchronized void addWaypoint(Waypoint waypoint) {
        if (waypoint == null || waypoints.size() >= MAX_WAYPOINTS) return;
        waypoints.add(waypoint);
        rebuildSnapshotsLocked();
        saveWaypoints();
    }

    /** Replaces one waypoint atomically while preserving its list position. */
    public synchronized boolean updateWaypoint(Waypoint original, Waypoint replacement) {
        if (original == null || replacement == null) return false;
        int index = waypoints.indexOf(original);
        if (index < 0) return false;
        waypoints.set(index, replacement);
        rebuildSnapshotsLocked();
        saveWaypoints();
        return true;
    }

    /** Resolves legacy preset icons to the same item icon used by new waypoints. */
    public static String resolveIconItemId(Waypoint waypoint) {
        if (waypoint == null) return "minecraft:compass";
        if (waypoint.iconType == 0) return "minecraft:red_dye";
        if (waypoint.iconType == 1) return "minecraft:red_bed";
        if (waypoint.iconType == 2) return "minecraft:target";
        if (waypoint.iconType == 3) return "minecraft:nether_star";
        return waypoint.iconItem == null || waypoint.iconItem.isBlank()
                ? "minecraft:compass" : waypoint.iconItem;
    }

    public synchronized void addDeathWaypoint(double x, double y, double z, String dimension, int maximum) {
        String targetDimension = dimension == null || dimension.isBlank() ? "unknown" : dimension;
        int limit = Math.max(0, Math.min(20, maximum));
        if (limit == 0) return;
        int deathCount = 0;
        for (Waypoint waypoint : waypoints) {
            if (waypoint.deathPoint && waypoint.dimension.equals(targetDimension)) deathCount++;
        }
        while (deathCount >= limit) {
            boolean removed = false;
            for (int index = 0; index < waypoints.size(); index++) {
                Waypoint waypoint = waypoints.get(index);
                if (waypoint.deathPoint && waypoint.dimension.equals(targetDimension)) {
                    waypoints.remove(index);
                    deathCount--;
                    removed = true;
                    break;
                }
            }
            if (!removed) break;
        }
        if (waypoints.size() >= MAX_WAYPOINTS) return;
        String name = String.format(Locale.ROOT, "Death (%d, %d, %d)",
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        waypoints.add(new Waypoint(name, x, y, z, -1, "minecraft:recovery_compass", 1.0f,
                targetDimension, true));
        rebuildSnapshotsLocked();
        saveWaypoints();
    }

    public synchronized void removeWaypoint(Waypoint waypoint) {
        if (waypoint == null) return;
        if (!waypoints.remove(waypoint)) return;
        rebuildSnapshotsLocked();
        saveWaypoints();
    }

    private void rebuildSnapshotsLocked() {
        List<Waypoint> all = List.copyOf(waypoints);
        Map<String, List<Waypoint>> grouped = new HashMap<>();
        MapManager manager = MapManager.getInstance();
        for (Waypoint waypoint : all) {
            String dimension = manager.resolveDimensionResourceId(waypoint.dimension);
            grouped.computeIfAbsent(dimension, ignored -> new ArrayList<>())
                    .add(waypoint);
        }
        Map<String, List<Waypoint>> immutable = new HashMap<>(grouped.size());
        for (Map.Entry<String, List<Waypoint>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        allSnapshot = all;
        dimensionSnapshots = Map.copyOf(immutable);
        visualRevision.incrementAndGet();
    }

    private static String sanitizeText(String value, String fallback, int maximumLength) {
        if (value == null) return fallback;
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), maximumLength));
        for (int offset = 0; offset < value.length() && cleaned.length() < maximumLength;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isISOControl(codePoint)) cleaned.appendCodePoint(codePoint);
        }
        String result = cleaned.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private static double clampCoordinate(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(-MAX_WORLD_COORDINATE, Math.min(MAX_WORLD_COORDINATE, value));
    }

    private void quarantineCorruptFile() {
        if (currentWaypointsFile == null || !currentWaypointsFile.isFile()) return;
        Path source = currentWaypointsFile.toPath();
        Path quarantine = source.resolveSibling(currentWaypointsFile.getName()
                + ".corrupt." + System.currentTimeMillis());
        try {
            moveReplacing(source, quarantine);
        } catch (IOException moveFailure) {
            LOGGER.warn("Could not quarantine unreadable waypoint file {}", source, moveFailure);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void shareLocation(Minecraft minecraft, String label,
            double x, double y, double z, boolean hasY, String dimension) {
        if (minecraft == null) return;
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        String coordinates = hasY
                ? String.format(Locale.ROOT, "[%d, %d, %d]", blockX,
                        (int) Math.floor(y), blockZ)
                : String.format(Locale.ROOT, "[%d, ?, %d]", blockX, blockZ);
        String place = label == null || label.isBlank()
                ? "Location" : "Waypoint \"" + label.trim() + "\"";
        String text = place + " at " + coordinates + " in "
                + MapManager.displayDimensionName(dimension);
        minecraft.keyboardHandler.setClipboard(text);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("Location copied; press Enter to share."), true);
        }
        MapViewportCoordinator.getInstance().closeFullscreen();
        minecraft.setScreen(new ChatScreen(text));
    }
}

}
