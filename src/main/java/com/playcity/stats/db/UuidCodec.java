package com.playcity.stats.db;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class UuidCodec {
    private UuidCodec() {}

    public static byte[] toBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}

