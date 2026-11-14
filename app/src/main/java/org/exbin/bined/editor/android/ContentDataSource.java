/*
 * Copyright (C) ExBin Project, https://exbin.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Modified for integration into S-File-Encryptor.
 * 
 */
package org.exbin.bined.editor.android;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import org.exbin.auxiliary.binary_data.delta.DataSource;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NullMarked;

/**
 * Data source using android data content.
 */
@NullMarked
public class ContentDataSource implements DataSource {

    private final ContentResolver contentResolver;
    private final Uri fileUri;

    private AssetFileDescriptor descriptor;
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(1);
    private final DeltaDataPageWindow window;
    private FileInputStream inputStream;
    private FileOutputStream outputStream;
    private FileChannel fileChannel;
    private final List<CacheClearListener> listeners = new ArrayList<>();
    private long dataLength;
    private boolean closed = false;

    public ContentDataSource(ContentResolver contentResolver, Uri fileUri) throws IOException {
        this.contentResolver = contentResolver;
        this.fileUri = fileUri;
        descriptor = contentResolver.openAssetFileDescriptor(fileUri, "rw");
        inputStream = descriptor.createInputStream();
        outputStream = descriptor.createOutputStream();
        fileChannel = outputStream.getChannel();
        dataLength = descriptor.getLength();
        window = new DeltaDataPageWindow(this);
    }

    public ContentResolver getContentResolver() {
        return contentResolver;
    }

    public FileInputStream getInputStream() {
        return inputStream;
    }

    public Uri getFileUri() {
        return fileUri;
    }

    @Override
    public long getDataLength() throws IOException {
        return dataLength;
    }

    @Override
public void setDataLength(long dataLength) throws IOException {
    checkClosed();

    if (dataLength < 0) {
        throw new IllegalArgumentException(
                "Invalid data length: " + dataLength
        );
    }

    long currentLength = fileChannel.size();

    if (dataLength < currentLength) {
        fileChannel.truncate(dataLength);
    } else if (dataLength > currentLength) {
        ByteBuffer zero = ByteBuffer.allocate(1);
        zero.put((byte) 0);
        zero.flip();

        writeFully(zero, dataLength - 1);
    }

    this.dataLength = dataLength;
}

    @Override
    public byte getByte(long position) throws IOException {
        checkClosed();
        return window.getByte(position);
        /*
        try (InputStream stream = contentResolver.openInputStream(fileUri)) {
            stream.skip(position);
            int value = stream.read();
            if (value == -1) {
                throw new IOException("Read exception");
            }
            return (byte) value;
        } */
    }

    @Override
public void setByte(long position, byte value) throws IOException {
    checkClosed();

    if (position < 0 || position == Long.MAX_VALUE) {
        throw new IOException(
                "Invalid write position: " + position
        );
    }

    byteBuffer.clear();
    byteBuffer.put(value);
    byteBuffer.flip();

    writeFully(byteBuffer, position);

    dataLength = Math.max(dataLength, position + 1);
}

    @Override
    public int read(long position, byte[] buffer, int offset, int length) throws IOException {
        checkClosed();
        return window.read(position, buffer, offset, length);
    }

    @Override
public void write(
        long position,
        byte[] buffer,
        int offset,
        int length
) throws IOException {

    checkClosed();

    if (buffer == null) {
        throw new NullPointerException("buffer");
    }

    if (position < 0 || length < 0 ||
            offset < 0 || offset > buffer.length - length) {

        throw new IOException("Invalid write parameters");
    }

    if (position > Long.MAX_VALUE - length) {
        throw new IOException("Write position overflow");
    }

    if (length == 0) {
        return;
    }

    ByteBuffer writeBuffer = ByteBuffer.wrap(
            buffer,
            offset,
            length
    );

    writeFully(writeBuffer, position);

    dataLength = Math.max(
            dataLength,
            position + length
    );
}

    private void writeFully(
        ByteBuffer buffer,
        long position
) throws IOException {

    long currentPosition = position;

    while (buffer.hasRemaining()) {

        int written = fileChannel.write(
                buffer,
                currentPosition
        );

        if (written <= 0) {
            throw new IOException(
                    "Unable to complete write at position "
                            + currentPosition
            );
        }

        currentPosition += written;
    }
}

    @Override
    public void clearCache() {
        for (CacheClearListener listener : listeners) {
            listener.clearCache();
        };
    }

    @Override
    public void close() throws IOException {
        checkClosed();
        fileChannel.close();
        inputStream.close();
        outputStream.close();
        descriptor.close();
        closed = true;
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("File was already closed");
        }
    }

    public void addCacheClearListener(CacheClearListener listener) {
        listeners.add(listener);
    }

    public void removeCacheClearListener(CacheClearListener listener) {
        listeners.remove(listener);
    }

    public interface CacheClearListener {

        void clearCache();
    }
}
