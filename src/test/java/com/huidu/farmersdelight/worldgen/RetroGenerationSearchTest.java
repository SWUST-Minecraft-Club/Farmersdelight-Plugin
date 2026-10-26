package com.huidu.farmersdelight.worldgen;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetroGenerationSearchTest {
    private static final String ONIONS = "farmersdelight:wild_onions";

    @Test void decodesCoordinatesIncludingNegativeChunksAndSections() {
        RetroGenerationSearch search = new RetroGenerationSearch(-20, -40);
        int index = (9 << 8) | (7 << 4) | 3;
        search.scanSection(-2, -3, -1, i -> i == index ? ONIONS : null);
        assertEquals(1, search.count());
        assertEquals(new RetroGenerationSearch.Position(ONIONS, -29, -7, -41), search.nearest().getFirst());
        assertEquals(82, search.distanceSquared(search.nearest().getFirst()));
    }

    @Test void readsOnlyLoadedSectionProvidedByCallerAndRejectsDistantChunksBeforeReading() {
        RetroGenerationSearch search = new RetroGenerationSearch(0, 0);
        AtomicInteger reads = new AtomicInteger();
        search.scanSection(17, 0, 4, i -> { reads.incrementAndGet(); return ONIONS; });
        assertEquals(0, reads.get());
        search.scanSection(16, -16, 4, i -> { reads.incrementAndGet(); return i == 4095 ? ONIONS : null; });
        assertEquals(4096, reads.get());
        assertEquals(1, search.count());
        assertTrue(search.includesChunk(-16, 16));
        assertFalse(search.includesChunk(0, -17));
    }

    @Test void retainsNearestTenWhileCountingAllMatches() {
        RetroGenerationSearch search = new RetroGenerationSearch(0, 0);
        // Scan farther plants first, so later chunks must replace bounded results.
        search.scanSection(2, 0, 4, i -> i < 16 ? ONIONS : null);
        search.scanSection(0, 0, 4, i -> i < 16 ? ONIONS : null);
        assertEquals(32, search.count());
        assertEquals(10, search.nearest().size());
        for (int x = 0; x < 10; x++) assertEquals(x, search.nearest().get(x).x());
    }

    @Test void ignoresCropsContainersForeignIdsAndEmptyStates() {
        RetroGenerationSearch search = new RetroGenerationSearch(0, 0);
        search.scanSection(0, 0, 4, i -> switch (i) {
            case 0 -> "farmersdelight:cabbages";
            case 1 -> "farmersdelight:cooking_pot";
            case 2 -> "other:wild_onions";
            case 3 -> "farmersdelight:wild_unknown";
            case 4 -> "farmersdelight:sandy_shrub";
            case 5 -> "farmersdelight:wild_rice";
            default -> null;
        });
        assertEquals(2, search.count());
        assertFalse(RetroGenerationSearch.isWildPlant(null));
        assertEquals("farmersdelight:sandy_shrub", search.nearest().getFirst().id());
    }

    @Test void searchOriginAndChunkBoundsHandleNegativeBlockCoordinates() {
        RetroGenerationSearch search = new RetroGenerationSearch(-1, -1);
        assertTrue(search.includesChunk(-17, 15));
        assertFalse(search.includesChunk(16, 0));
        assertFalse(search.includesChunk(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }
}
