
package com.lukestudio.fileencryptor2;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import android.provider.DocumentsContract;
import android.database.Cursor;

public final class SecureDeleteManager {

    private static final int BUFFER_SIZE = 64 * 1024;

    private SecureDeleteManager() {
    }

    public enum Status {
        SUCCESS,
        UNSUPPORTED,
        FAILED
    }

    public interface ProgressListener {
    void onProgress(long bytesWritten, long totalBytes);
}

    public static final class Result {

        public final Status status;
        public final long bytesOverwritten;
        public final String message;

        private Result(
                Status status,
                long bytesOverwritten,
                String message
        ) {
            this.status = status;
            this.bytesOverwritten = bytesOverwritten;
            this.message = message;
        }
    }

    private static Result result(
            Status status,
            long bytes,
            String message
    ) {
        return new Result(status, bytes, message);
    }

    public static Result overwriteFile(
        Context context,
        Uri uri
) {
    return overwriteFile(context, uri, null);
}

public static Result overwriteFile(
        Context context,
        Uri uri,
        ProgressListener listener
) {

        if (context == null || uri == null) {

            return result(
                    Status.FAILED,
                    0,
                    (context != null ? context.getString(R.string.secure_delete_invalid_input) : "Invalid file or context.")
            );
        }

        ContentResolver resolver =
                context.getContentResolver();

        byte[] buffer = new byte[BUFFER_SIZE];

        SecureRandom secureRandom = new SecureRandom();

        long totalWritten = 0;

        try {

            // Não utiliza modos que truncam o arquivo.
            ParcelFileDescriptor pfd =
                    resolver.openFileDescriptor(uri, "rw");

            if (pfd == null) {

                return result(
                        Status.UNSUPPORTED,
                        0,
                        context.getString(R.string.secure_delete_provider_no_write)
                );
            }

            try (ParcelFileDescriptor.AutoCloseOutputStream output =
             new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
     FileChannel channel = output.getChannel()) {

                if (!channel.isOpen()) {

    return result(
            Status.UNSUPPORTED,
            0,
            context.getString(R.string.secure_delete_open_channel_failed)
    );
}

                long fileSize = channel.size();

                if (listener != null) {
    try {
        listener.onProgress(0, fileSize);
    } catch (RuntimeException ignored) {
    }
}

                if (fileSize < 0) {

                    return result(
                            Status.UNSUPPORTED,
                            0,
                            context.getString(R.string.secure_delete_size_unknown)
                    );
                }

                long offset = 0;

                while (offset < fileSize) {

                    int chunkSize = (int) Math.min(
                            BUFFER_SIZE,
                            fileSize - offset
                    );

                    secureRandom.nextBytes(buffer);

                    ByteBuffer byteBuffer =
                            ByteBuffer.wrap(
                                    buffer,
                                    0,
                                    chunkSize
                            );

                    while (byteBuffer.hasRemaining()) {

                        int written = channel.write(
                                byteBuffer,
                                offset
                        );

                        if (written <= 0) {

                            throw new IOException(
                                    context.getString(R.string.secure_delete_overwrite_failed)
                            );
                        }

                        offset += written;
                        totalWritten += written;
                    }

                    if (listener != null) {
    try {
        listener.onProgress(totalWritten, fileSize);
    } catch (RuntimeException ignored) {
    }
}

                }

                // Solicita sincronização dos dados.
                channel.force(true);

                return result(
                        Status.SUCCESS,
                        totalWritten,
                        context.getString(R.string.secure_delete_overwrite_complete)
                );
            }

        } catch (UnsupportedOperationException e) {

            return result(
                    Status.UNSUPPORTED,
                    totalWritten,
                    context.getString(R.string.secure_delete_provider_unsupported)
            );

        } catch (SecurityException e) {

            return result(
                    Status.UNSUPPORTED,
                    totalWritten,
                    context.getString(R.string.secure_delete_write_permission)
            );

        } catch (Exception e) {

            return result(
                    Status.FAILED,
                    totalWritten,
                    context.getString(R.string.secure_delete_overwrite_error, e.getMessage())
            );

        } finally {

            java.util.Arrays.fill(
                    buffer,
                    (byte) 0
            );
        }
    }

    public static Result deleteFile(Context context, Uri uri) {

    if (context == null || uri == null) {
        return result(
                Status.FAILED,
                0,
                (context != null ? context.getString(R.string.secure_delete_invalid_input) : "Invalid file or context.")
        );
    }

    ContentResolver resolver = context.getContentResolver();

    try {

        if (!DocumentsContract.isDocumentUri(context, uri)) {
            return result(
                    Status.UNSUPPORTED,
                    0,
                    context.getString(R.string.secure_delete_unsupported_uri)
            );
        }

        boolean deleted;

        try {

            deleted = DocumentsContract.deleteDocument(
                    resolver,
                    uri
            );

        } catch (Exception deleteException) {

            /*
             * Alguns DocumentsProvider podem efetivamente remover
             * o documento e ainda assim lançar uma exceção durante
             * a operação.
             *
             * Portanto, verificamos o documento antes de declarar
             * a operação como falha.
             */

            try (Cursor cursor = resolver.query(
                    uri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID
                    },
                    null,
                    null,
                    null
            )) {

                if (cursor == null || !cursor.moveToFirst()) {

                    return result(
                            Status.SUCCESS,
                            0,
                            context.getString(R.string.secure_delete_confirmed)
                    );
                }

            } catch (SecurityException verificationException) {

                return result(
                        Status.FAILED,
                        0,
                        context.getString(R.string.secure_delete_verify_failed)
                );
            }

            return result(
                    Status.FAILED,
                    0,
                    context.getString(R.string.secure_delete_error, deleteException.getMessage())
            );
        }

        if (!deleted) {

            return result(
                    Status.FAILED,
                    0,
                    context.getString(R.string.secure_delete_not_confirmed)
            );
        }

        /*
         * O provider confirmou a exclusão.
         * Fazemos uma verificação adicional.
         */

        try (Cursor cursor = resolver.query(
                uri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID
                },
                null,
                null,
                null
        )) {

            if (cursor == null || !cursor.moveToFirst()) {

                return result(
                        Status.SUCCESS,
                        0,
                        context.getString(R.string.secure_delete_confirmed)
                );
            }

            return result(
                    Status.FAILED,
                    0,
                    context.getString(R.string.secure_delete_still_accessible)
            );

        } catch (SecurityException e) {

            /*
             * O provider pode revogar a permissão da URI depois
             * da exclusão. Porém, como deleteDocument() retornou
             * true, consideramos a exclusão confirmada.
             */

            return result(
                    Status.SUCCESS,
                    0,
                    context.getString(R.string.secure_delete_provider_confirmed)
            );
        }

    } catch (SecurityException e) {

        return result(
                Status.UNSUPPORTED,
                0,
                context.getString(R.string.secure_delete_permission)
        );

    } catch (Exception e) {

        return result(
                Status.FAILED,
                0,
                context.getString(R.string.secure_delete_error, e.getMessage())
        );
    }
}

}
