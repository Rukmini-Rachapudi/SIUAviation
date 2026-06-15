package com.skidreport.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * R4: every form of an aircraft's folder name must resolve to one canonical
 * key, so the same physical aircraft is never treated as two identities (which
 * would cause a missed pair) and two log forms of one aircraft collapse so the
 * detector's same-tail guard excludes them (no self-pair).
 */
public class TailNumbersTest {

    @Test
    public void variantsResolveToOneKey() {
        assertEquals("82P", TailNumbers.detect("N682P"));
        assertEquals("82P", TailNumbers.detect("682P"));
        assertEquals("82P", TailNumbers.detect("N682P 2024 logs"));
    }

    @Test
    public void realFleetFolderNames() {
        assertEquals("2JP", TailNumbers.detect("N192JP"));
        assertEquals("13N", TailNumbers.detect("N213N"));
        assertEquals("61J", TailNumbers.detect("N261J"));
        assertEquals("06H", TailNumbers.detect("N306H"));
        assertEquals("41E", TailNumbers.detect("N541E"));
        assertEquals("49K", TailNumbers.detect("N849K"));
    }

    @Test
    public void nonAircraftFoldersResolveToNull() {
        assertNull(TailNumbers.detect("Documents"));
        assertNull(TailNumbers.detect("near_miss_business_logic_prompt.md"));
        assertNull(TailNumbers.detect(""));
        assertNull(TailNumbers.detect(null));
    }

    @Test
    public void sameAircraftDifferentFormsCollapse() {
        // identical canonical key -> the detector skips this as a same-tail pair
        assertEquals(TailNumbers.detect("N682P"), TailNumbers.detect("682P"));
    }
}
