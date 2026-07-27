package com.velorise.simplemap.client.cave;

import java.util.Arrays;

/**
 * Cached run-level connectivity for one 16x16 cave tile.
 *
 * Candidate retention and all internal tile links are resolved once per tile
 * revision/view context. Page resolution then only needs to merge these local
 * components and evaluate links that cross tile boundaries.
 */
final class CaveTileGraphSummary {
    private static final int TILE_SIZE = CaveChunkTile.SIZE;
    private static final int COLUMN_COUNT = CaveChunkTile.COLUMN_COUNT;

    private final int[] firstNode;
    private final byte[] nodesPerColumn;
    private final byte[] nodeRun;
    private final int[] nodeBase;
    private final short[] nodeComponent;
    private final int[] nodeSupport;
    private final int componentCount;

    private CaveTileGraphSummary(int[] firstNode, byte[] nodesPerColumn,
            byte[] nodeRun, int[] nodeBase, short[] nodeComponent,
            int[] nodeSupport, int componentCount) {
        this.firstNode = firstNode;
        this.nodesPerColumn = nodesPerColumn;
        this.nodeRun = nodeRun;
        this.nodeBase = nodeBase;
        this.nodeComponent = nodeComponent;
        this.nodeSupport = nodeSupport;
        this.componentCount = componentCount;
    }

    static CaveTileGraphSummary build(CaveChunkTile.Snapshot snapshot,
            CaveView view, int maximumY, int minimumY, int preferredY,
            int runLimit) {
        int safeRunLimit = Math.max(1, Math.min(16, runLimit));
        int maximumNodes = COLUMN_COUNT * safeRunLimit;
        int[] firstNode = new int[COLUMN_COUNT];
        byte[] nodesPerColumn = new byte[COLUMN_COUNT];
        byte[] nodeRun = new byte[maximumNodes];
        int[] nodeBase = new int[maximumNodes];
        int[] parent = new int[maximumNodes];
        int[] componentSize = new int[maximumNodes];
        int[] nodeSupport = new int[maximumNodes];
        Arrays.fill(firstNode, -1);

        int[] bestRuns = new int[safeRunLimit];
        int[] bestScores = new int[safeRunLimit];
        int nodeTotal = 0;
        CaveColumnData[] columns = snapshot.columns();

        for (int columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
            CaveColumnData column = columns[columnIndex];
            if (column == null || column.count() == 0
                    || !snapshot.scanned().get(columnIndex)) continue;

            Arrays.fill(bestRuns, -1);
            Arrays.fill(bestScores, Integer.MIN_VALUE);
            int retained = 0;
            for (int run = 0; run < column.count(); run++) {
                int score = view == CaveView.FULL
                        ? column.fullBaseScore(run, preferredY)
                        : column.layeredBaseScore(run, maximumY, minimumY);
                if (score <= Integer.MIN_VALUE / 8) continue;
                if (retained == safeRunLimit
                        && score <= bestScores[safeRunLimit - 1]) continue;

                int insert = Math.min(retained, safeRunLimit - 1);
                while (insert > 0 && score > bestScores[insert - 1]) {
                    bestScores[insert] = bestScores[insert - 1];
                    bestRuns[insert] = bestRuns[insert - 1];
                    insert--;
                }
                bestScores[insert] = score;
                bestRuns[insert] = run;
                if (retained < safeRunLimit) retained++;
            }

            if (retained == 0) continue;
            firstNode[columnIndex] = nodeTotal;
            nodesPerColumn[columnIndex] = (byte) retained;
            for (int slot = 0; slot < retained; slot++) {
                nodeRun[nodeTotal] = (byte) bestRuns[slot];
                nodeBase[nodeTotal] = bestScores[slot];
                parent[nodeTotal] = nodeTotal;
                componentSize[nodeTotal] = 1;
                nodeSupport[nodeTotal] = 0;
                nodeTotal++;
            }
        }

        int[] previousOffsets = { -1, -TILE_SIZE - 1, -TILE_SIZE, -TILE_SIZE + 1 };
        int tolerance = view == CaveView.FULL ? 3 : 2;
        for (int z = 0; z < TILE_SIZE; z++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                int columnIndex = z * TILE_SIZE + x;
                if (firstNode[columnIndex] < 0) continue;
                for (int offset : previousOffsets) {
                    int neighbour = columnIndex + offset;
                    if (neighbour < 0 || neighbour >= COLUMN_COUNT
                            || firstNode[neighbour] < 0) continue;
                    int neighbourX = neighbour & 15;
                    if (Math.abs(neighbourX - x) > 1) continue;
                    connectColumns(columns, firstNode, nodesPerColumn, nodeRun,
                            parent, componentSize, nodeSupport,
                            columnIndex, neighbour, tolerance);
                }
            }
        }

        int[] rootToComponent = new int[nodeTotal];
        Arrays.fill(rootToComponent, -1);
        short[] nodeComponent = new short[nodeTotal];
        int componentCount = 0;
        for (int node = 0; node < nodeTotal; node++) {
            int root = findRoot(parent, node);
            int component = rootToComponent[root];
            if (component < 0) {
                component = componentCount++;
                rootToComponent[root] = component;
            }
            nodeComponent[node] = (short) component;
        }

        return new CaveTileGraphSummary(
                firstNode,
                nodesPerColumn,
                Arrays.copyOf(nodeRun, nodeTotal),
                Arrays.copyOf(nodeBase, nodeTotal),
                nodeComponent,
                Arrays.copyOf(nodeSupport, nodeTotal),
                componentCount);
    }

    int firstNode(int columnIndex) {
        return firstNode[columnIndex];
    }

    int nodesPerColumn(int columnIndex) {
        return Byte.toUnsignedInt(nodesPerColumn[columnIndex]);
    }

    int nodeRun(int nodeIndex) {
        return Byte.toUnsignedInt(nodeRun[nodeIndex]);
    }

    int nodeBase(int nodeIndex) {
        return nodeBase[nodeIndex];
    }

    int nodeComponent(int nodeIndex) {
        return Short.toUnsignedInt(nodeComponent[nodeIndex]);
    }

    int nodeSupport(int nodeIndex) {
        return nodeSupport[nodeIndex];
    }

    int componentCount() {
        return componentCount;
    }

    int nodeCount() {
        return nodeRun.length;
    }

    private static void connectColumns(CaveColumnData[] columns,
            int[] firstNode, byte[] nodesPerColumn, byte[] nodeRun,
            int[] parent, int[] componentSize, int[] nodeSupport,
            int firstColumn, int secondColumn, int tolerance) {
        CaveColumnData firstData = columns[firstColumn];
        CaveColumnData secondData = columns[secondColumn];
        int firstStart = firstNode[firstColumn];
        int secondStart = firstNode[secondColumn];
        int firstCount = Byte.toUnsignedInt(nodesPerColumn[firstColumn]);
        int secondCount = Byte.toUnsignedInt(nodesPerColumn[secondColumn]);
        for (int a = 0; a < firstCount; a++) {
            int firstNodeIndex = firstStart + a;
            int firstRun = Byte.toUnsignedInt(nodeRun[firstNodeIndex]);
            for (int b = 0; b < secondCount; b++) {
                int secondNodeIndex = secondStart + b;
                int secondRun = Byte.toUnsignedInt(nodeRun[secondNodeIndex]);
                if (!firstData.connectsTo(firstRun, secondData, secondRun, tolerance)) continue;
                nodeSupport[firstNodeIndex]++;
                nodeSupport[secondNodeIndex]++;
                union(parent, componentSize, firstNodeIndex, secondNodeIndex);
            }
        }
    }

    private static void union(int[] parent, int[] componentSize,
            int first, int second) {
        int firstRoot = findRoot(parent, first);
        int secondRoot = findRoot(parent, second);
        if (firstRoot == secondRoot) return;
        if (componentSize[firstRoot] < componentSize[secondRoot]) {
            int swap = firstRoot;
            firstRoot = secondRoot;
            secondRoot = swap;
        }
        parent[secondRoot] = firstRoot;
        componentSize[firstRoot] += componentSize[secondRoot];
    }

    private static int findRoot(int[] parent, int value) {
        int root = value;
        while (parent[root] != root) root = parent[root];
        while (parent[value] != value) {
            int next = parent[value];
            parent[value] = root;
            value = next;
        }
        return root;
    }
}
