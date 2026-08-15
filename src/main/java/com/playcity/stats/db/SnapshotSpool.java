package com.playcity.stats.db;

import com.playcity.stats.collect.Keys.BlockGroupDayKey;
import com.playcity.stats.collect.Keys.CommandHourKey;
import com.playcity.stats.collect.Keys.DeathDayKey;
import com.playcity.stats.collect.Keys.PlayerDayKey;
import com.playcity.stats.collect.Keys.PlayerHourKey;
import com.playcity.stats.collect.StatsAccumulator.DayDelta;
import com.playcity.stats.collect.StatsAccumulator.FlushSnapshot;
import com.playcity.stats.collect.StatsAccumulator.HourDelta;
import com.playcity.stats.collect.StatsAccumulator.PlayerIdentity;
import com.playcity.stats.collect.StatsAccumulator.SessionRow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

public final class SnapshotSpool {
    private static final int MAGIC = 0x53545350;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_STRING_BYTES = 1024 * 1024;

    private final Path directory;
    private final AtomicInteger pendingFiles = new AtomicInteger();

    public SnapshotSpool(Path rootDirectory, String storageIdentity) throws IOException {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (storageIdentity == null || storageIdentity.isBlank()) {
            throw new IllegalArgumentException("storageIdentity must not be blank");
        }
        this.directory = rootDirectory.resolve(identityHash(storageIdentity));
        Files.createDirectories(directory);
        recoverTemporaryFiles();
        pendingFiles.set(pendingPaths().size());
    }

    public synchronized void store(FlushSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.isEmpty()) {
            return;
        }

        Path target = pendingPath(snapshot.batchId());
        if (Files.exists(target)) {
            return;
        }

        byte[] encoded = encode(snapshot);
        Path temporary = directory.resolve(snapshot.batchId() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            if (moveNewFile(temporary, target)) {
                pendingFiles.incrementAndGet();
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized List<FlushSnapshot> loadAll() throws IOException {
        List<FlushSnapshot> snapshots = new ArrayList<>();
        for (Path path : pendingPaths()) {
            FlushSnapshot snapshot = decode(path);
            if (!path.getFileName().toString().equals(snapshot.batchId() + ".pending")) {
                throw new IOException("Spool filename does not match batch id: " + path.getFileName());
            }
            snapshots.add(snapshot);
        }
        return List.copyOf(snapshots);
    }

    public synchronized void complete(FlushSnapshot snapshot) throws IOException {
        if (Files.deleteIfExists(pendingPath(snapshot.batchId()))) {
            pendingFiles.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    public int pendingFileCount() {
        return pendingFiles.get();
    }

    public boolean contains(UUID batchId) {
        return batchId != null && Files.exists(pendingPath(batchId));
    }

    public String storageId() {
        return directory.getFileName().toString();
    }

    private void recoverTemporaryFiles() throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path temporary : paths
                .filter(path -> path.getFileName().toString().contains(".tmp-"))
                .sorted()
                .toList()) {
                FlushSnapshot snapshot = decode(temporary);
                Path target = pendingPath(snapshot.batchId());
                if (!moveNewFile(temporary, target)) {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    private List<Path> pendingPaths() throws IOException {
        try (var paths = Files.list(directory)) {
            return paths
                .filter(path -> path.getFileName().toString().endsWith(".pending"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
    }

    private Path pendingPath(UUID batchId) {
        return directory.resolve(batchId + ".pending");
    }

    private static boolean moveNewFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(source, target);
                return true;
            } catch (FileAlreadyExistsException ignored) {
                return false;
            }
        } catch (FileAlreadyExistsException ignored) {
            return false;
        }
    }

    private static byte[] encode(FlushSnapshot snapshot) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream payload = new DataOutputStream(payloadBytes)) {
            writeUuid(payload, snapshot.batchId());
            writeInstant(payload, snapshot.createdAt());
            writePlayers(payload, snapshot.players());
            writeHours(payload, snapshot.hourDeltas());
            writeDays(payload, snapshot.dayDeltas());
            writePlayerHourLongMap(payload, snapshot.activeMinuteBits());
            writeCommands(payload, snapshot.commandHourCounts());
            writeBlockGroups(payload, snapshot.blockGroupDayCounts());
            writeDeaths(payload, snapshot.deathDayCounts());
            writeSessions(payload, snapshot.sessions());
        }

        byte[] payload = payloadBytes.toByteArray();
        if (payload.length > MAX_FILE_BYTES) {
            throw new IOException("Snapshot spool payload exceeds " + MAX_FILE_BYTES + " bytes");
        }
        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream(payload.length + 20);
        try (DataOutputStream file = new DataOutputStream(fileBytes)) {
            file.writeInt(MAGIC);
            file.writeInt(FORMAT_VERSION);
            file.writeInt(payload.length);
            file.writeLong(crc.getValue());
            file.write(payload);
        }
        return fileBytes.toByteArray();
    }

    private static FlushSnapshot decode(Path path) throws IOException {
        long size = Files.size(path);
        if (size < 20 || size > MAX_FILE_BYTES + 20L) {
            throw new IOException("Invalid spool file size " + size + ": " + path.getFileName());
        }

        byte[] fileBytes = Files.readAllBytes(path);
        try (DataInputStream file = new DataInputStream(new ByteArrayInputStream(fileBytes))) {
            if (file.readInt() != MAGIC) {
                throw new IOException("Invalid spool file magic: " + path.getFileName());
            }
            int version = file.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported spool format " + version + ": " + path.getFileName());
            }
            int payloadLength = file.readInt();
            long expectedCrc = file.readLong();
            if (payloadLength < 0 || payloadLength != file.available()) {
                throw new IOException("Invalid spool payload length: " + path.getFileName());
            }
            byte[] payload = file.readNBytes(payloadLength);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expectedCrc) {
                throw new IOException("Spool checksum mismatch: " + path.getFileName());
            }
            return decodePayload(payload, path);
        } catch (EOFException | IllegalArgumentException e) {
            throw new IOException("Invalid spool file: " + path.getFileName(), e);
        }
    }

    private static FlushSnapshot decodePayload(byte[] payload, Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            FlushSnapshot snapshot = new FlushSnapshot(
                readUuid(input),
                readInstant(input),
                readPlayers(input),
                readHours(input),
                readDays(input),
                readPlayerHourLongMap(input),
                readCommands(input),
                readBlockGroups(input),
                readDeaths(input),
                readSessions(input)
            );
            if (input.available() != 0) {
                throw new IOException("Trailing data in spool file: " + path.getFileName());
            }
            return snapshot;
        } catch (EOFException | IllegalArgumentException e) {
            throw new IOException("Invalid spool payload: " + path.getFileName(), e);
        }
    }

    private static void writePlayers(DataOutputStream out, Map<UUID, PlayerIdentity> values) throws IOException {
        out.writeInt(values.size());
        for (PlayerIdentity value : values.values()) {
            writeUuid(out, value.playerUuid());
            writeInstant(out, value.firstSeenAt());
            writeInstant(out, value.lastSeenAt());
            writeString(out, value.lastKnownName());
        }
    }

    private static Map<UUID, PlayerIdentity> readPlayers(DataInputStream input) throws IOException {
        Map<UUID, PlayerIdentity> values = new HashMap<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            UUID uuid = readUuid(input);
            values.put(uuid, new PlayerIdentity(uuid, readInstant(input), readInstant(input), readString(input)));
        }
        return Map.copyOf(values);
    }

    private static void writeHours(DataOutputStream out, Map<PlayerHourKey, HourDelta> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            writePlayerHourKey(out, entry.getKey());
            HourDelta value = entry.getValue();
            out.writeLong(value.playtimeSec());
            out.writeLong(value.afkSec());
            out.writeLong(value.chatMessages());
            out.writeLong(value.chatChars());
            out.writeLong(value.commandsTotal());
            out.writeLong(value.blocksPlaced());
            out.writeLong(value.blocksBroken());
            out.writeLong(value.distanceM());
            out.writeLong(value.teleportCount());
            out.writeLong(value.teleportDistanceM());
        }
    }

    private static Map<PlayerHourKey, HourDelta> readHours(DataInputStream input) throws IOException {
        Map<PlayerHourKey, HourDelta> values = new HashMap<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            values.put(readPlayerHourKey(input), new HourDelta(
                input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong()
            ));
        }
        return Map.copyOf(values);
    }

    private static void writeDays(DataOutputStream out, Map<PlayerDayKey, DayDelta> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            writePlayerDayKey(out, entry.getKey());
            DayDelta value = entry.getValue();
            out.writeLong(value.playtimeSec());
            out.writeLong(value.sessions());
            out.writeLong(value.deaths());
            out.writeLong(value.killsPvp());
            out.writeLong(value.killsMob());
            out.writeLong(value.teleportCount());
            out.writeLong(value.teleportDistanceM());
        }
    }

    private static Map<PlayerDayKey, DayDelta> readDays(DataInputStream input) throws IOException {
        Map<PlayerDayKey, DayDelta> values = new HashMap<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            values.put(readPlayerDayKey(input), new DayDelta(
                input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong(), input.readLong()
            ));
        }
        return Map.copyOf(values);
    }

    private static void writePlayerHourLongMap(DataOutputStream out, Map<PlayerHourKey, Long> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            writePlayerHourKey(out, entry.getKey());
            out.writeLong(entry.getValue());
        }
    }

    private static Map<PlayerHourKey, Long> readPlayerHourLongMap(DataInputStream input) throws IOException {
        Map<PlayerHourKey, Long> values = new HashMap<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            values.put(readPlayerHourKey(input), input.readLong());
        }
        return Map.copyOf(values);
    }

    private static void writeCommands(DataOutputStream out, Map<CommandHourKey, Long> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            CommandHourKey key = entry.getKey();
            writeUuid(out, key.playerUuid());
            out.writeLong(key.epochHour());
            writeString(out, key.commandKey());
            writeString(out, key.variantKey());
            out.writeLong(entry.getValue());
        }
    }

    private static Map<CommandHourKey, Long> readCommands(DataInputStream input) throws IOException {
        Map<CommandHourKey, Long> values = new HashMap<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            CommandHourKey key = new CommandHourKey(
                readUuid(input), input.readLong(), readRequiredString(input), readRequiredString(input)
            );
            values.put(key, input.readLong());
        }
        return Map.copyOf(values);
    }

    private static void writeBlockGroups(DataOutputStream out, Map<BlockGroupDayKey, Long> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            BlockGroupDayKey key = entry.getKey();
            writeUuid(out, key.playerUuid());
            out.writeLong(key.day().toEpochDay());
            writeString(out, key.groupKey());
            out.writeInt(key.action());
            out.writeLong(entry.getValue());
        }
    }

    private static Map<BlockGroupDayKey, Long> readBlockGroups(DataInputStream input) throws IOException {
        Map<BlockGroupDayKey, Long> values = new HashMap<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            BlockGroupDayKey key = new BlockGroupDayKey(
                readUuid(input), LocalDate.ofEpochDay(input.readLong()), readRequiredString(input), input.readInt()
            );
            values.put(key, input.readLong());
        }
        return Map.copyOf(values);
    }

    private static void writeDeaths(DataOutputStream out, Map<DeathDayKey, Long> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : values.entrySet()) {
            DeathDayKey key = entry.getKey();
            writeUuid(out, key.playerUuid());
            out.writeLong(key.day().toEpochDay());
            writeString(out, key.cause());
            out.writeLong(entry.getValue());
        }
    }

    private static Map<DeathDayKey, Long> readDeaths(DataInputStream input) throws IOException {
        Map<DeathDayKey, Long> values = new HashMap<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            DeathDayKey key = new DeathDayKey(
                readUuid(input), LocalDate.ofEpochDay(input.readLong()), readRequiredString(input)
            );
            values.put(key, input.readLong());
        }
        return Map.copyOf(values);
    }

    private static void writeSessions(DataOutputStream out, List<SessionRow> values) throws IOException {
        out.writeInt(values.size());
        for (SessionRow value : values) {
            writeUuid(out, value.playerUuid());
            writeInstant(out, value.joinAt());
            writeInstant(out, value.quitAt());
            out.writeInt(value.durationSec());
            out.writeInt(value.afkSec());
            writeString(out, value.joinWorld());
            writeString(out, value.quitWorld());
        }
    }

    private static List<SessionRow> readSessions(DataInputStream input) throws IOException {
        List<SessionRow> values = new ArrayList<>();
        for (int i = 0, count = readCount(input); i < count; i++) {
            values.add(new SessionRow(
                readUuid(input), readInstant(input), readInstant(input), input.readInt(), input.readInt(),
                readString(input), readString(input)
            ));
        }
        return List.copyOf(values);
    }

    private static void writePlayerHourKey(DataOutputStream out, PlayerHourKey key) throws IOException {
        writeUuid(out, key.playerUuid());
        out.writeLong(key.epochHour());
    }

    private static PlayerHourKey readPlayerHourKey(DataInputStream input) throws IOException {
        return new PlayerHourKey(readUuid(input), input.readLong());
    }

    private static void writePlayerDayKey(DataOutputStream out, PlayerDayKey key) throws IOException {
        writeUuid(out, key.playerUuid());
        out.writeLong(key.day().toEpochDay());
    }

    private static PlayerDayKey readPlayerDayKey(DataInputStream input) throws IOException {
        return new PlayerDayKey(readUuid(input), LocalDate.ofEpochDay(input.readLong()));
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeInstant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        return Instant.ofEpochSecond(input.readLong(), input.readInt());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Spool string exceeds " + MAX_STRING_BYTES + " bytes");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > MAX_STRING_BYTES || length > input.available()) {
            throw new IOException("Invalid spool string length: " + length);
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static String readRequiredString(DataInputStream input) throws IOException {
        String value = readString(input);
        if (value == null) {
            throw new IOException("Required spool string is null");
        }
        return value;
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("Invalid spool entry count: " + count);
        }
        return count;
    }

    private static String identityHash(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
