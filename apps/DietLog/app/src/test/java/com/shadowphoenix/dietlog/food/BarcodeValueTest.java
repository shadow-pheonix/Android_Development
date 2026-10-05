package com.shadowphoenix.dietlog.food;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BarcodeValueTest {
    @Test
    public void upcaAlsoChecksEquivalentEan13() {
        assertEquals(Arrays.asList("123456789012", "0123456789012"),
                BarcodeValue.lookupCandidates("123456789012"));
    }

    @Test
    public void leadingZeroEan13AlsoChecksUpca() {
        assertEquals(Arrays.asList("0123456789012", "123456789012"),
                BarcodeValue.lookupCandidates("0123456789012"));
    }

    @Test
    public void upceExpandsForCatalogsThatStoreUpca() {
        assertEquals("042000001005", BarcodeValue.expandUpce("04210005"));
        assertTrue(BarcodeValue.lookupCandidates("04210005", "UPC_E").contains("042000001005"));
        assertTrue(BarcodeValue.lookupCandidates("04210005", "UPC_E").contains("0042000001005"));
    }

    @Test
    public void ean8DoesNotGetExpandedAsUpce() {
        assertEquals(Arrays.asList("04210005"), BarcodeValue.lookupCandidates("04210005", "EAN_8"));
    }

    @Test
    public void rejectsNonNumericScannerPayloads() {
        assertTrue(BarcodeValue.lookupCandidates("ABC123").isEmpty());
    }
}
