package de.drremote.trotecsl400.api;

import java.util.List;

public record Sl400Sample(
        long timestampMs,
        double db,
        int rawTenths,
        String aux06Hex,
        List<Integer> tags
) {}
