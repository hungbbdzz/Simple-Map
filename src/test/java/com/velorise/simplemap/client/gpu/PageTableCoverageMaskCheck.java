package com.velorise.simplemap.client.gpu;

/** PASS93 guard for dynamic exact-surface subtile visibility. */
public final class PageTableCoverageMaskCheck {
    private PageTableCoverageMaskCheck() { }

    public static void main(String[] args) {
        int original = PageTableEntry.FLAG_PROTECTED | PageTableEntry.FLAG_LINEAR;
        int mask = 0x8421;
        int flags = PageTableEntry.withCoverageMask(original, mask);
        PageTableEntry entry = new PageTableEntry(1, 2, 3L, 4L, 0, flags,
                1.0f, 1.0f, 64, 4096);
        require(entry.coverageMask() == mask, "coverage mask was truncated");
        require((entry.flags() & PageTableEntry.FLAG_PROTECTED) != 0
                        && (entry.flags() & PageTableEntry.FLAG_LINEAR) != 0,
                "coverage encoding destroyed page-table flags");
        require((entry.coverageMask() & (1 << 15)) != 0
                        && (entry.coverageMask() & (1 << 1)) == 0,
                "subtile membership is incorrect");
        System.out.println("PAGE_TABLE_COVERAGE_MASK_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
