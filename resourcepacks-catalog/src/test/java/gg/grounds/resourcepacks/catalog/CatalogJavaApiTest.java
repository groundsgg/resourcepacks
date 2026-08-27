package gg.grounds.resourcepacks.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.grounds.scene.format.AssetKind;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogJavaApiTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void exposesTheCatalogToJavaWithoutMutableCollections() {
        assertEquals("grounds", GroundsGuiIds.NAMESPACE);
        assertEquals(System.getProperty("catalog.version"), GroundsAssetCatalog.INSTANCE.getCatalog().getVersion());
        assertEquals(
                java.util.List.of("AssetKey(value=grounds:editor/marker)", "AssetKey(value=grounds:editor/guide)"),
                GroundsAssets.INSTANCE.getAll().stream().map(Object::toString).toList());
        assertEquals(AssetKind.PROP, java.util.List.copyOf(GroundsAssetCatalog.INSTANCE.getCatalog().getAssets().values()).get(0).getKind());
        assertEquals(0.5, java.util.List.copyOf(GroundsAssetCatalog.INSTANCE.getCatalog().getAssets().values()).get(0).getDefaultBounds().getCenter().getY());
        assertEquals(AssetKind.NPC_BODY, java.util.List.copyOf(GroundsAssetCatalog.INSTANCE.getCatalog().getAssets().values()).get(1).getKind());
        assertEquals(1.8, java.util.List.copyOf(GroundsAssetCatalog.INSTANCE.getCatalog().getAssets().values()).get(1).getDefaultBounds().getSize().getY());

        Set rawAssets = GroundsAssets.INSTANCE.getAll();
        assertThrows(UnsupportedOperationException.class, () -> rawAssets.add("grounds:unexpected"));

        Map rawCatalogAssets = GroundsAssetCatalog.INSTANCE.getCatalog().getAssets();
        assertThrows(UnsupportedOperationException.class, () -> rawCatalogAssets.put("grounds:unexpected", new Object()));
    }
}
