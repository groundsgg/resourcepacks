package gg.grounds.resourcepacks.npc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
class NpcContentArtifactTest {
 @Test void neutralContentIsDataOnlyAndPreservesTheSchemaOneIndex() throws Exception {
  try(var jar=new JarFile(System.getProperty("npcContentJar"))) {
   var index=jar.getJarEntry("gg/grounds/resourcepacks/npc/index.json");
   assertNotNull(index);
   var text=new String(jar.getInputStream(index).readAllBytes(),StandardCharsets.UTF_8);
   var json=JsonParser.parseString(text).getAsJsonObject();
   assertEquals(1,json.get("schemaVersion").getAsInt());
   assertTrue(json.getAsJsonArray("models").isEmpty());
   assertFalse(jar.stream().anyMatch(e->e.getName().endsWith(".class")));
   assertNotNull(jar.getJarEntry("gg/grounds/resourcepacks/npc/pack-digests.json"));
  }
 }
}
