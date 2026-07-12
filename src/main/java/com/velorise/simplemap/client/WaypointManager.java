package com.velorise.simplemap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WaypointManager {
    private static final WaypointManager INSTANCE = new WaypointManager();
    public static WaypointManager getInstance() {
        return INSTANCE;
    }

    public static class Waypoint {
        public final String name;
        public final double x;
        public final double z;
        public final int iconType; // -1: use custom item, 0: Red X, 1: Green House, 2: Blue Target, 3: Yellow Star
        public final String iconItem; // Item registry ID (e.g. "minecraft:diamond_pickaxe")
        public final float scale;     // Scale multiplier (0.1 to 2.0)
        public final String dimension;

        public Waypoint(String name, double x, double z, int iconType, String iconItem, float scale, String dimension) {
            this.name = name;
            this.x = x;
            this.z = z;
            this.iconType = iconType;
            this.iconItem = iconItem != null ? iconItem : "";
            this.scale = scale > 0.0f ? scale : 1.0f;
            this.dimension = dimension;
        }
    }

    private final List<Waypoint> waypoints = new ArrayList<>();
    private File currentWaypointsFile = null;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private WaypointManager() {}

    public void updateWorldDir(File worldDir) {
        if (worldDir == null) {
            waypoints.clear();
            currentWaypointsFile = null;
            return;
        }
        currentWaypointsFile = new File(worldDir, "waypoints.json");
        loadWaypoints();
    }

    private void loadWaypoints() {
        waypoints.clear();
        if (currentWaypointsFile == null || !currentWaypointsFile.exists()) return;

        try (FileReader reader = new FileReader(currentWaypointsFile)) {
            List<Waypoint> loaded = GSON.fromJson(reader, new TypeToken<List<Waypoint>>(){}.getType());
            if (loaded != null) {
                for (Waypoint w : loaded) {
                    // Sanitize old waypoints compatibility
                    String item = w.iconItem != null ? w.iconItem : "";
                    float sc = w.scale > 0.0f ? w.scale : 1.0f;
                    waypoints.add(new Waypoint(w.name, w.x, w.z, w.iconType, item, sc, w.dimension));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveWaypoints() {
        if (currentWaypointsFile == null) return;

        try (FileWriter writer = new FileWriter(currentWaypointsFile)) {
            GSON.toJson(waypoints, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<Waypoint> getWaypointsForDimension(String dimension) {
        List<Waypoint> list = new ArrayList<>();
        for (Waypoint w : waypoints) {
            if (w.dimension.equals(dimension)) {
                list.add(w);
            }
        }
        return list;
    }

    public void addWaypoint(Waypoint waypoint) {
        waypoints.add(waypoint);
        saveWaypoints();
    }

    public void removeWaypoint(Waypoint waypoint) {
        waypoints.remove(waypoint);
        saveWaypoints();
    }
}
