package com.termux.app;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Patches bootstrap binaries and scripts to use the correct package paths.
 *
 * This is needed when using official Termux bootstrap with a different package name.
 * The official bootstrap has /data/data/com.termux hardcoded, but our app uses
 * a different package name (com.nx.pai).
 */
public final class BootstrapPatcher {

    private static final String LOG_TAG = "BootstrapPatcher";

    // Original path in official Termux bootstrap
    private static final String OLD_PREFIX = "/data/data/com.termux";

    // New path for our rebranded app
    private static final String NEW_PREFIX = "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME;

    private static final byte[] OLD_PREFIX_BYTES = OLD_PREFIX.getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEW_PREFIX_BYTES = NEW_PREFIX.getBytes(StandardCharsets.UTF_8);

    private BootstrapPatcher() {}

    /**
     * Patch all files in the PREFIX directory to use the correct paths.
     * Call this after bootstrap extraction.
     */
    public static boolean patchBootstrap(Context context) {
        if (OLD_PREFIX.equals(NEW_PREFIX)) {
            Logger.logInfo(LOG_TAG, "Package paths match, no patching needed");
            return true;
        }

        Logger.logInfo(LOG_TAG, "Patching bootstrap: " + OLD_PREFIX + " -> " + NEW_PREFIX);

        File prefixDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!prefixDir.exists()) {
            Logger.logError(LOG_TAG, "PREFIX directory does not exist: " + prefixDir);
            return false;
        }

        int patchedFiles = 0;
        int failedFiles = 0;

        List<File> allFiles = listFilesRecursively(prefixDir);
        Logger.logInfo(LOG_TAG, "Found " + allFiles.size() + " files to check");

        for (File file : allFiles) {
            if (!file.isFile() || file.isDirectory()) continue;
            if (!file.canRead()) continue;

            try {
                if (isTextFile(file)) {
                    if (patchTextFile(file)) {
                        patchedFiles++;
                    }
                } else if (isElfFile(file)) {
                    if (patchElfFile(file)) {
                        patchedFiles++;
                    }
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to patch: " + file.getPath() + " - " + e.getMessage());
                failedFiles++;
            }
        }

        Logger.logInfo(LOG_TAG, "Patching complete: " + patchedFiles + " files patched, " + failedFiles + " failed");
        return failedFiles == 0;
    }

    /**
     * Check if file is a text file (script) by checking for shebang or known extensions.
     */
    private static boolean isTextFile(File file) {
        String name = file.getName();

        // Check extensions
        if (name.endsWith(".sh") || name.endsWith(".py") || name.endsWith(".pl") ||
            name.endsWith(".rb") || name.endsWith(".lua") || name.endsWith(".conf") ||
            name.endsWith(".cfg") || name.endsWith(".txt") || name.endsWith(".json") ||
            name.endsWith(".xml") || name.endsWith(".pc") || name.endsWith(".la") ||
            name.endsWith(".cmake") || name.endsWith(".m4")) {
            return true;
        }

        // Check for shebang
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[2];
            if (fis.read(header) == 2) {
                return header[0] == '#' && header[1] == '!';
            }
        } catch (IOException e) {
            // Ignore
        }

        return false;
    }

    /**
     * Check if file is an ELF binary.
     */
    private static boolean isElfFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) == 4) {
                // ELF magic: 0x7F 'E' 'L' 'F'
                return magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
            }
        } catch (IOException e) {
            // Ignore
        }
        return false;
    }

    /**
     * Patch a text file by replacing old prefix with new prefix.
     */
    private static boolean patchTextFile(File file) throws IOException {
        // Read file content
        byte[] content;
        try (FileInputStream fis = new FileInputStream(file)) {
            content = new byte[(int) file.length()];
            fis.read(content);
        }

        String text = new String(content, StandardCharsets.UTF_8);

        // Check if contains old prefix
        if (!text.contains(OLD_PREFIX)) {
            return false;
        }

        // Replace
        String newText = text.replace(OLD_PREFIX, NEW_PREFIX);

        // Write back
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(newText.getBytes(StandardCharsets.UTF_8));
        }

        Logger.logDebug(LOG_TAG, "Patched text file: " + file.getPath());
        return true;
    }

    /**
     * Patch an ELF binary by replacing old prefix bytes with new prefix bytes.
     *
     * NOTE: This only works if OLD_PREFIX and NEW_PREFIX have the same length,
     * or if we pad the new prefix with null bytes.
     *
     * For different length paths, use patchelf tool instead.
     */
    private static boolean patchElfFile(File file) throws IOException {
        // Read entire file
        byte[] content;
        try (FileInputStream fis = new FileInputStream(file)) {
            content = new byte[(int) file.length()];
            fis.read(content);
        }

        // Check if contains old prefix
        int index = indexOf(content, OLD_PREFIX_BYTES);
        if (index == -1) {
            return false;
        }

        boolean patched = false;

        // For ELF files, we need to be careful about path lengths
        // If new prefix is longer, we can't safely patch without patchelf
        if (NEW_PREFIX_BYTES.length > OLD_PREFIX_BYTES.length) {
            // Try to find null-terminated strings and replace if there's room
            patched = patchElfStrings(content, file);
        } else {
            // New prefix is same length or shorter - pad with nulls
            byte[] paddedNew = new byte[OLD_PREFIX_BYTES.length];
            System.arraycopy(NEW_PREFIX_BYTES, 0, paddedNew, 0, NEW_PREFIX_BYTES.length);
            // Rest is already 0 (null padding)

            // Replace all occurrences
            int count = replaceAll(content, OLD_PREFIX_BYTES, paddedNew);
            if (count > 0) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(content);
                }
                patched = true;
                Logger.logDebug(LOG_TAG, "Patched ELF file: " + file.getPath() + " (" + count + " occurrences)");
            }
        }

        return patched;
    }

    /**
     * Patch null-terminated strings in ELF file.
     * Used when new prefix is longer than old prefix.
     */
    private static boolean patchElfStrings(byte[] content, File file) throws IOException {
        // Find all occurrences of old prefix followed by more path characters
        // Check if there's enough space (null bytes or extra path chars) to fit new prefix

        boolean anyPatched = false;
        int searchStart = 0;

        while (true) {
            int index = indexOf(content, OLD_PREFIX_BYTES, searchStart);
            if (index == -1) break;

            // Find end of this string (null terminator)
            int stringEnd = index;
            while (stringEnd < content.length && content[stringEnd] != 0) {
                stringEnd++;
            }

            int stringLength = stringEnd - index;
            String oldString = new String(content, index, stringLength, StandardCharsets.UTF_8);

            // Create new string with replaced prefix
            String newString = oldString.replace(OLD_PREFIX, NEW_PREFIX);

            // Check if new string fits in the same space
            if (newString.length() <= stringLength) {
                // It fits! Replace with null padding
                byte[] newBytes = newString.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(newBytes, 0, content, index, newBytes.length);
                // Null out remaining bytes
                for (int i = index + newBytes.length; i < stringEnd; i++) {
                    content[i] = 0;
                }
                anyPatched = true;
            } else {
                // Doesn't fit - skip this occurrence
                // This path won't work at runtime
                Logger.logWarn(LOG_TAG, "Cannot patch (no space): " + oldString + " in " + file.getName());
            }

            searchStart = stringEnd;
        }

        if (anyPatched) {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content);
            }
            Logger.logDebug(LOG_TAG, "Patched ELF strings in: " + file.getPath());
        }

        return anyPatched;
    }

    /**
     * Find index of pattern in data.
     */
    private static int indexOf(byte[] data, byte[] pattern) {
        return indexOf(data, pattern, 0);
    }

    private static int indexOf(byte[] data, byte[] pattern, int start) {
        outer:
        for (int i = start; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Replace all occurrences of pattern with replacement.
     * Returns count of replacements.
     */
    private static int replaceAll(byte[] data, byte[] pattern, byte[] replacement) {
        if (pattern.length != replacement.length) {
            throw new IllegalArgumentException("Pattern and replacement must be same length");
        }

        int count = 0;
        int index = 0;
        while ((index = indexOf(data, pattern, index)) != -1) {
            System.arraycopy(replacement, 0, data, index, replacement.length);
            index += replacement.length;
            count++;
        }
        return count;
    }

    /**
     * List all files in directory recursively.
     */
    private static List<File> listFilesRecursively(File dir) {
        List<File> result = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    result.addAll(listFilesRecursively(file));
                } else {
                    result.add(file);
                }
            }
        }
        return result;
    }
}
