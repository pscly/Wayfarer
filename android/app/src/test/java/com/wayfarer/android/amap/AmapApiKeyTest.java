package com.wayfarer.android.amap;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AmapApiKeyTest {
    @Test
    public void isPresent_returnsFalse_forBlankOrSentinelValues() {
        assertFalse(AmapApiKey.INSTANCE.isPresent(null));
        assertFalse(AmapApiKey.INSTANCE.isPresent(""));
        assertFalse(AmapApiKey.INSTANCE.isPresent("   "));
        // Any sentinel starting with MISSING_ should be treated as absent.
        assertFalse(AmapApiKey.INSTANCE.isPresent("MISSING_WAYFARER_AMAP_API_KEY"));
        assertFalse(AmapApiKey.INSTANCE.isPresent("MISSING_ANYTHING"));
        assertFalse(AmapApiKey.INSTANCE.isPresent("  MISSING_TRIMMED  "));
        assertFalse(AmapApiKey.INSTANCE.isPresent("YOUR_AMAP_API_KEY"));
        assertFalse(AmapApiKey.INSTANCE.isPresent("your_amap_api_key"));
    }

    @Test
    public void isPresent_returnsTrue_forNonBlankKey() {
        assertTrue(AmapApiKey.INSTANCE.isPresent("abc123"));
    }
}
