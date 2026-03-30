package de.drremote.trotecsl400.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.drremote.trotecsl400.api.IncidentRecord;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JsonExporter {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonExporter() {
    }

    public static Path writeIncidentsJson(List<IncidentRecord> incidents, String label) throws IOException {
        String safeLabel = sanitizeLabel(label);
        String prefix = "sl400_incidents_" + safeLabel + "_";
        Path file = Files.createTempFile(prefix, ".json");

        List<IncidentRecord> sorted = new ArrayList<>();
        if (incidents != null) {
            sorted.addAll(incidents);
            sorted.sort(Comparator.comparingLong(IncidentRecord::timestampMs));
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", "de.drremote.trotecsl400.incidents-export.v1");
        root.put("label", label == null || label.isBlank() ? "all" : label);
        root.put("generatedAtMs", System.currentTimeMillis());
        root.put("incidentCount", sorted.size());

        ArrayNode items = root.putArray("incidents");
        for (IncidentRecord incident : sorted) {
            items.addPOJO(incident);
        }

        try (Writer writer = Files.newBufferedWriter(file)) {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(writer, root);
        }

        return file;
    }

    private static String sanitizeLabel(String label) {
        String raw = (label == null || label.isBlank()) ? "all" : label;
        String safe = raw.replaceAll("[^a-zA-Z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (safe.length() < 3) {
            return "all";
        }
        return safe;
    }
}