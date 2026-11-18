import com.google.gson.*;
import gg.grounds.npc.core.io.content.NpcContentLoader;
import gg.grounds.npc.importer.NpcBlockbenchImporter;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Grounds packaging only. Blockbench parsing and final contract checks belong to npc-core/importer. */
public final class GroundsNpcImporter {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "import" -> {
                var imported = NpcBlockbenchImporter.importModel(Path.of(args[1]), args[2]);
                var output = Path.of(args[3]);
                Files.createDirectories(output);
                Files.write(output.resolve("model.json"), imported.getModelJson());
                for (var entry : imported.getResources().entrySet()) {
                    var file = output.resolve("pack").resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                }
            }
            case "package" -> packageContent(Path.of(args[1]), args[2]);
            case "verify" -> NpcContentLoader.load(Path.of(args[1])).verifyResourcePack(Path.of(args[2]));
            default -> throw new IllegalArgumentException("Unknown adapter operation");
        }
    }

    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var values = new ArrayList<String>();
            for (var name : new TreeSet<>(value.getAsJsonObject().keySet()))
                values.add(new JsonPrimitive(name) + ":" + canonical(value.getAsJsonObject().get(name)));
            return "{" + String.join(",", values) + "}";
        }
        if (value.isJsonArray()) {
            var values = new ArrayList<String>();
            for (var item : value.getAsJsonArray()) values.add(canonical(item));
            return "[" + String.join(",", values) + "]";
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
            return new JsonPrimitive(value.getAsBigDecimal()).toString();
        return value.toString();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void zip(Path path, Map<String, byte[]> files) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : new TreeMap<>(files).entrySet()) {
                var member = new ZipEntry(entry.getKey()); member.setTime(0L);
                out.putNextEntry(member); out.write(entry.getValue()); out.closeEntry();
            }
        }
    }

    private static void packageContent(Path output, String compiler) throws Exception {
        var legacy = output.resolve("catalog-resources/gg/grounds/resourcepacks/npc");
        var index = JsonParser.parseString(Files.readString(legacy.resolve("index.json"))).getAsJsonObject();
        var server = new TreeMap<String, byte[]>();
        var pack = new TreeMap<String, byte[]>();
        var models = new JsonArray();
        for (var raw : index.getAsJsonArray("models")) {
            var model = raw.getAsJsonObject();
            var resource = model.get("resource").getAsString().substring(1);
            if (!resource.startsWith("gg/grounds/resourcepacks/npc/") || resource.contains(".."))
                throw new IllegalArgumentException("Unsafe Grounds model resource");
            var bytes = Files.readAllBytes(output.resolve("catalog-resources").resolve(resource));
            server.put(resource, bytes);
            var item = new JsonObject();
            item.add("id", model.get("asset")); item.addProperty("resource", resource); item.addProperty("sha256", hash(bytes));
            models.add(item);
        }
        var hashes = new JsonObject();
        var events = new JsonObject();
        try (var paths = Files.walk(output.resolve("pack"))) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var name = output.resolve("pack").relativize(path).toString().replace('\\', '/');
                var bytes = Files.readAllBytes(path); pack.put(name, bytes);
                if (name.equals("assets/grounds/sounds.json")) {
                    var sounds = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (var entry : sounds.entrySet()) events.add("grounds:" + entry.getKey(), entry.getValue());
                } else hashes.addProperty(name, hash(bytes));
            }
        }
        var contract = new JsonObject();
        contract.addProperty("bundleSchema", 1); contract.addProperty("compilerVersion", compiler);
        contract.addProperty("target", "26.3"); contract.add("models", models);
        contract.add("clientFiles", hashes); contract.add("soundEvents", events);
        contract.addProperty("contentId", hash(canonical(contract).getBytes(StandardCharsets.UTF_8)));
        server.put("bundle.json", canonical(contract).getBytes(StandardCharsets.UTF_8));
        pack.put("pack.mcmeta", "{\"pack\":{\"description\":\"Grounds NPC content\",\"min_format\":[97,1],\"max_format\":[97,1]}}".getBytes(StandardCharsets.UTF_8));
        var content = NpcContentLoader.load(server);
        content.verifyResourcePack(pack);
        zip(output.resolve("server-content.zip"), server);
        zip(output.resolve("resourcepack.zip"), pack);
    }
}
