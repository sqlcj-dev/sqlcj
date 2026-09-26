package dev.sqlcj.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/**
 * The corrective fact of a filesystem failure, such as {@code No such file or
 * directory}.
 *
 * <p>The fact never repeats the path, because every diagnostic that uses it
 * already names the file or directory it failed on.
 */
public final class FileSystemReason {

    private FileSystemReason() {
    }

    /** The corrective fact of one filesystem failure. */
    public static String of(IOException failure) {
        if (failure instanceof NoSuchFileException) {
            return "No such file or directory";
        }

        if (failure instanceof AccessDeniedException) {
            return "Permission denied";
        }

        if (failure instanceof FileAlreadyExistsException) {
            return "File exists";
        }

        if (failure instanceof FileSystemException fileSystemFailure && fileSystemFailure.getReason() != null) {
            return fileSystemFailure.getReason().trim();
        }

        String message = failure.getMessage();

        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }

        return message.lines()
            .findFirst()
            .orElse(message)
            .trim();
    }
}
