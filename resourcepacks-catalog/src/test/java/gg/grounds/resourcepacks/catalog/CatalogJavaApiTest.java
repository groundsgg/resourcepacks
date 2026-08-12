package gg.grounds.resourcepacks.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogJavaApiTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void exposesTheCatalogToJavaWithoutMutableCollections() {
        assertEquals("grounds", GroundsGuiIds.NAMESPACE);
        assertEquals("0.0.0", CatalogBuildInfo.VERSION);
        assertTrue(GroundsAssetCatalog.INSTANCE.getCatalog().toString().contains("grounds:assets"));
        assertTrue(GroundsAssets.INSTANCE.getAll().isEmpty());

        Set rawAssets = GroundsAssets.INSTANCE.getAll();
        assertThrows(UnsupportedOperationException.class, () -> rawAssets.add("grounds:unexpected"));

        Map rawCatalogAssets = GroundsAssetCatalog.INSTANCE.getCatalog().getAssets();
        assertThrows(UnsupportedOperationException.class, () -> rawCatalogAssets.put("grounds:unexpected", new Object()));
    }
}
