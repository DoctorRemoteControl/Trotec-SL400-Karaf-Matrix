package de.drremote.trotecsl400.core;

import de.drremote.trotecsl400.api.Sl400Sample;

import java.util.ArrayList;
import java.util.List;

public class Sl400Decoder {
    private enum State { SEEK_MARKER, READ_TAG, READ_PAYLOAD }

    private State state = State.SEEK_MARKER;
    private int currentTag = 0;
    private int expectedPayloadLen = 0;
    private final List<Byte> payload = new ArrayList<>(4);

    private Integer measurementRawTenths = null;
    private String aux06Hex = null;
    private final List<Integer> seenTags = new ArrayList<>();

    public void reset() {
        state = State.SEEK_MARKER;
        currentTag = 0;
        expectedPayloadLen = 0;
        payload.clear();
        resetSample();
    }

    public List<Sl400Sample> feed(byte[] bytes) {
        List<Sl400Sample> out = new ArrayList<>();
        for (byte b : bytes) {
            int ub = b & 0xFF;
            switch (state) {
                case SEEK_MARKER -> {
                    if (ub == 0xA5) state = State.READ_TAG;
                }
                case READ_TAG -> {
                    currentTag = ub;
                    int len = payloadLengthForTag(ub);
                    if (len < 0) {
                        state = State.SEEK_MARKER;
                    } else if (len == 0) {
                        handleTag(currentTag, new byte[0], out);
                        state = State.SEEK_MARKER;
                    } else {
                        payload.clear();
                        expectedPayloadLen = len;
                        state = State.READ_PAYLOAD;
                    }
                }
                case READ_PAYLOAD -> {
                    payload.add(b);
                    if (payload.size() >= expectedPayloadLen) {
                        byte[] data = new byte[payload.size()];
                        for (int i = 0; i < payload.size(); i++) data[i] = payload.get(i);
                        handleTag(currentTag, data, out);
                        payload.clear();
                        state = State.SEEK_MARKER;
                    }
                }
            }
        }
        return out;
    }

    private int payloadLengthForTag(int tag) {
        return switch (tag) {
            case 0x0D -> 2;
            case 0x06 -> 3;
            case 0x1B, 0x0B -> 1;
            case 0x00, 0x02, 0x0C, 0x0E, 0x19, 0x1A, 0x1F, 0x4B -> 0;
            default -> -1;
        };
    }

    private void handleTag(int tag, byte[] payload, List<Sl400Sample> out) {
        seenTags.add(tag);
        switch (tag) {
            case 0x0D -> {
                int b1 = payload[0] & 0xFF;
                int b2 = payload[1] & 0xFF;
                measurementRawTenths = decodeMeasurementTenths(b1, b2);
            }
            case 0x06 -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < payload.length; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(String.format("%02X", payload[i] & 0xFF));
                }
                aux06Hex = sb.toString();
            }
            case 0x00 -> {
                if (measurementRawTenths != null) {
                    out.add(new Sl400Sample(
                            System.currentTimeMillis(),
                            measurementRawTenths / 10.0,
                            measurementRawTenths,
                            aux06Hex,
                            List.copyOf(seenTags)
                    ));
                }
                resetSample();
            }
            default -> {
                // only track tags
            }
        }
    }

    private int decodeMeasurementTenths(int byte1, int byte2) {
        int hundreds = byte1 & 0x0F;
        int tens = (byte2 >> 4) & 0x0F;
        int ones = byte2 & 0x0F;
        return (hundreds * 100) + (tens * 10) + ones;
    }

    private void resetSample() {
        measurementRawTenths = null;
        aux06Hex = null;
        seenTags.clear();
    }
}
