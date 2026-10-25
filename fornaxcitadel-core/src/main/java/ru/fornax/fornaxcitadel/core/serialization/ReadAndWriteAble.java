package ru.fornax.fornaxcitadel.core.serialization;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.jetbrains.annotations.NotNull;
import ru.fornax.fornaxcitadel.core.storage.WXZCord;

import java.io.*;
import java.util.UUID;

public abstract class ReadAndWriteAble {

    public static void writeUUID(UUID id, DataOutput output) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    public static UUID readUUID(DataInput input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    public static void writeWXZCord(WXZCord cord, DataOutput output) throws IOException {
        writeUUID(cord.getWorld(), output);
        output.writeInt(cord.getX());
        output.writeInt(cord.getZ());
    }

    public static WXZCord readWXZCord(DataInput input) throws IOException {
        return new WXZCord(
                readUUID(input),
                input.readInt(),
                input.readInt()
        );
    }

    public static WXZCord readWXZCord(byte[] bytes) throws IOException {
        return readFully(bytes, ReadAndWriteAble::readWXZCord);
    }

    public static byte[] toByteArray(WXZCord cord) {
        return writeToByteArray(output -> writeWXZCord(cord, output));
    }

    // NO STATIC

    public final void safeWrite(DataOutput output) throws IOException {
        this.write(output);
    }

    protected abstract void write(@NotNull DataOutput output) throws IOException;

    public final void safeRead(DataInput input) throws IOException {
        this.read(input);
    }

    protected abstract void read(@NotNull DataInput input) throws IOException;

    public void safeWriteChecked(@NotNull DataOutput output) {
        try {
            safeWrite(output);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void safeReadChecked(@NotNull DataInput input) {
        try {
            safeRead(input);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void safeRead(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            safeRead(input);
            if (input.available() != 0) {
                throw new IOException("Unread trailing data: " + input.available() + " bytes");
            }
        }
    }

    public byte[] toByteArray() {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        try {
            safeWrite(output);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return output.toByteArray();
    }

    private static byte[] writeToByteArray(DataWriter writer) {
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        try {
            writer.write(output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return output.toByteArray();
    }

    private static <T> T readFully(byte[] bytes, DataReader<T> reader) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            T result = reader.read(input);
            if (input.available() != 0) {
                throw new IOException("Unread trailing data: " + input.available() + " bytes");
            }
            return result;
        }
    }

    @FunctionalInterface
    private interface DataWriter {
        void write(DataOutput output) throws IOException;
    }

    @FunctionalInterface
    private interface DataReader<T> {
        T read(DataInput input) throws IOException;
    }

}
