package com.velorise.simplemap.client.cave;

/** Dependency-free invariants for conservative generic cave-dimension classification. */
public final class CaveDimensionEvidenceCheck {
    private CaveDimensionEvidenceCheck() {
    }

    public static void main(String[] args) {
        require(!CaveDimensionEvidence.classifyEnclosed(0, 0, 0, 0),
                "empty evidence became a cave dimension");
        require(!CaveDimensionEvidence.classifyEnclosed(6, 6, 6, 0),
                "a small cluster of roofs classified the whole dimension");
        require(!CaveDimensionEvidence.classifyEnclosed(11, 11, 11, 0),
                "too few distinct chunks classified an enclosed world");
        require(!CaveDimensionEvidence.classifyEnclosed(12, 12, 12, 0),
                "End-island roof samples classified the whole dimension");
        require(!CaveDimensionEvidence.classifyEnclosed(12, 10, 10, 2),
                "open samples were ignored by the strong-evidence path");
        require(!CaveDimensionEvidence.classifyEnclosed(20, 18, 16, 2),
                "broad local roof majority overrode stable dimension metadata");
        require(!CaveDimensionEvidence.classifyEnclosed(20, 17, 16, 3),
                "dark/open dimension was misclassified as enclosed");
        System.out.println("Simple Map cave-dimension evidence checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
