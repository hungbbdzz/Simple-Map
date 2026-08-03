package com.velorise.simplemap.client.persistence.v2;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Append-journal region container with CRC recovery and atomic compaction.
 * A truncated or corrupt tail is ignored; earlier committed records remain
 * readable. Source records have higher authority than disposable derived data.
 */
public final class RegionContainerV2 {
    public static final int MAGIC = 0x534D5232; // SMR2
    private static final int RECORD_MAGIC = 0x52454332; // REC2
    private static final int VERSION = 2;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    /** Single-writer append state; avoids rescanning a multi-MiB container before
     * every record while still detecting external replacement/truncation. */
    private static final Map<Path, AppendState> APPEND_STATES = new HashMap<>();

    public enum RecordType {
        SURFACE_SOURCE(1, true),
        CAVE_ARCHIVE(2, true),
        SURFACE_EXACT(16, false),
        SURFACE_LOD(17, false),
        CAVE_BAND(18, false),
        CAVE_LOD(19, false),
        MIGRATION_MARKER(31, true);

        private final int id;
        private final boolean source;
        RecordType(int id, boolean source) { this.id = id; this.source = source; }
        public int id() { return id; }
        public boolean source() { return source; }
        public static RecordType byId(int id) {
            for (RecordType type : values()) if (type.id == id) return type;
            return null;
        }
    }

    public record Header(long worldIdentity, int regionX, int regionZ,
            int dataVersion) { }
    public record RecordKey(RecordType type, int localKey) { }
    public record Record(RecordKey key, long sourceRevision,
            long styleRevision, byte[] payload) { }
    public record ReadResult(Header header, Map<RecordKey, Record> latest,
            long validBytes, boolean truncatedOrCorruptTail) { }

    private RegionContainerV2() { }

    public static synchronized boolean append(Path path, Header header,
            Record record) throws IOException {
        if (path == null || header == null || record == null
                || record.key() == null || record.key().type() == null
                || record.payload() == null
                || record.payload().length > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("invalid region container append");
        }
        Path identity = path.toAbsolutePath().normalize();
        Files.createDirectories(identity.getParent());
        long size = Files.exists(identity) ? Files.size(identity) : 0L;
        boolean create = size == 0L;
        boolean recovered = false;
        AppendState state = APPEND_STATES.get(identity);
        if (!create && (state == null || state.fileSize() != size
                || !sameHeader(state.header(), header))) {
            ReadResult existing = read(identity);
            if (existing.header() == null || !sameHeader(existing.header(), header)) {
                throw new IOException("SMR2 header identity mismatch");
            }
            if (existing.truncatedOrCorruptTail()) {
                try (FileChannel channel = FileChannel.open(identity,
                        StandardOpenOption.WRITE)) {
                    channel.truncate(existing.validBytes());
                    channel.force(true);
                }
                size = existing.validBytes();
                recovered = true;
            }
            state = new AppendState(existing.header(), size);
            APPEND_STATES.put(identity, state);
        }
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(identity,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
            if (create) writeHeader(output, header);
            CRC32 crc = new CRC32();
            crc.update(record.payload());
            output.writeInt(RECORD_MAGIC);
            output.writeByte(record.key().type().id());
            output.writeInt(record.key().localKey());
            output.writeLong(record.sourceRevision());
            output.writeLong(record.styleRevision());
            output.writeInt(record.payload().length);
            output.writeInt((int) crc.getValue());
            output.write(record.payload());
            output.flush();
        }
        APPEND_STATES.put(identity, new AppendState(header, Files.size(identity)));
        return recovered;
    }

    public static ReadResult read(Path path) throws IOException {
        if (path == null || !Files.exists(path) || Files.size(path) == 0L) {
            return new ReadResult(null, Map.of(), 0L, false);
        }
        LinkedHashMap<RecordKey, Record> latest = new LinkedHashMap<>();
        long validBytes = 0L;
        boolean damaged = false;
        Header header;
        try (CountingInputStream counting = new CountingInputStream(
                new BufferedInputStream(Files.newInputStream(path)));
             DataInputStream input = new DataInputStream(counting)) {
            header = readHeader(input);
            validBytes = counting.count();
            while (true) {
                long recordStart = counting.count();
                try {
                    int magic = input.readInt();
                    if (magic != RECORD_MAGIC) {
                        damaged = true;
                        break;
                    }
                    RecordType type = RecordType.byId(input.readUnsignedByte());
                    int localKey = input.readInt();
                    long sourceRevision = input.readLong();
                    long styleRevision = input.readLong();
                    int length = input.readInt();
                    int expectedCrc = input.readInt();
                    if (type == null || length < 0 || length > MAX_RECORD_BYTES) {
                        damaged = true;
                        break;
                    }
                    byte[] payload = input.readNBytes(length);
                    if (payload.length != length) {
                        damaged = true;
                        break;
                    }
                    CRC32 crc = new CRC32();
                    crc.update(payload);
                    if ((int) crc.getValue() != expectedCrc) {
                        damaged = true;
                        break;
                    }
                    RecordKey key = new RecordKey(type, localKey);
                    Record candidate = new Record(key, sourceRevision,
                            styleRevision, payload);
                    Record previous = latest.get(key);
                    if (previous == null
                            || previous.sourceRevision() < sourceRevision
                            || (previous.sourceRevision() == sourceRevision
                            && previous.styleRevision() <= styleRevision)) {
                        latest.put(key, candidate);
                    }
                    validBytes = counting.count();
                } catch (EOFException end) {
                    if (counting.count() != recordStart) damaged = true;
                    break;
                }
            }
        }
        return new ReadResult(header, Map.copyOf(latest), validBytes, damaged);
    }

    public static synchronized void compact(Path path) throws IOException {
        Path identity = path.toAbsolutePath().normalize();
        APPEND_STATES.remove(identity);
        ReadResult result = read(identity);
        if (result.header() == null) return;
        Path temporary = identity.resolveSibling(identity.getFileName() + ".compact.tmp");
        Files.deleteIfExists(temporary);
        boolean first = true;
        for (Record record : result.latest().values()) {
            if (first) {
                Files.createDirectories(temporary.toAbsolutePath().getParent());
                try (DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                    writeHeader(output, result.header());
                }
                first = false;
            }
            append(temporary, result.header(), record);
        }
        if (first) {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                writeHeader(output, result.header());
            }
        }
        try {
            Files.move(temporary, identity, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, identity, StandardCopyOption.REPLACE_EXISTING);
        }
        APPEND_STATES.remove(temporary.toAbsolutePath().normalize());
        APPEND_STATES.remove(identity);
    }


    private static boolean sameHeader(Header left, Header right) {
        return left.worldIdentity() == right.worldIdentity()
                && left.regionX() == right.regionX()
                && left.regionZ() == right.regionZ()
                && left.dataVersion() == right.dataVersion();
    }

    private static void writeHeader(DataOutputStream output, Header header)
            throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeLong(header.worldIdentity());
        output.writeInt(header.regionX());
        output.writeInt(header.regionZ());
        output.writeInt(header.dataVersion());
    }

    private static Header readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid SMR2 magic");
        int version = input.readInt();
        if (version != VERSION) throw new IOException("Unsupported SMR2 version " + version);
        return new Header(input.readLong(), input.readInt(), input.readInt(),
                input.readInt());
    }

    private record AppendState(Header header, long fileSize) { }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;
        private CountingInputStream(java.io.InputStream input) { super(input); }
        long count() { return count; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length)
                throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }
    }
}
