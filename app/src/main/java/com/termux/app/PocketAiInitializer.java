package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Initializes PocketAI backend on first launch.
 *
 * This class:
 * - Detects first run using SharedPreferences
 * - Creates ~/.pocketai_setup.sh that runs automatically on first terminal open
 * - Adds entry to .bashrc to trigger setup (self-removing after first run)
 * - Creates ~/.termux/boot/start.sh for auto-start on device boot (with Termux:Boot)
 * - Play-policy safe: requires user to open app once
 */
public final class PocketAiInitializer {

    private static final String LOG_TAG = "PocketAiInitializer";

    // SharedPreferences keys
    private static final String PREFS_NAME = "pocketai_prefs";
    private static final String KEY_INITIALIZED = "pocketai_initialized";
    private static final int CURRENT_VERSION = 1;

    // File paths (using TermuxConstants)
    private static final String BOOT_DIR_PATH = TermuxConstants.TERMUX_BOOT_SCRIPTS_DIR_PATH;
    private static final String START_SCRIPT_PATH = BOOT_DIR_PATH + "/start.sh";
    private static final String HOME_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH;
    private static final String SETUP_SCRIPT_PATH = HOME_DIR_PATH + "/.pocketai_setup.sh";
    private static final String BASHRC_PATH = HOME_DIR_PATH + "/.bashrc";

    private PocketAiInitializer() {
        // Static utility class
    }

    /**
     * Initialize PocketAI if this is the first launch.
     *
     * MUST be called AFTER bootstrap is ready (from TermuxInstaller callback).
     *
     * @param context Application context
     * @return true if initialization was performed, false if already initialized
     */
    public static boolean initializeIfNeeded(Context context) {
        if (context == null) {
            Logger.logError(LOG_TAG, "Context is null");
            return false;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int storedVersion = prefs.getInt(KEY_INITIALIZED, 0);

        if (storedVersion >= CURRENT_VERSION) {
            Logger.logDebug(LOG_TAG, "PocketAI already initialized (version " + storedVersion + ")");
            return false;
        }

        Logger.logInfo(LOG_TAG, "First launch detected, initializing PocketAI...");

        // Verify PREFIX exists (bootstrap is ready)
        File prefixDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        if (!prefixDir.exists() || !prefixDir.isDirectory()) {
            Logger.logError(LOG_TAG, "PREFIX not ready: " + TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            return false;
        }

        // Create boot directory if needed
        File bootDir = new File(BOOT_DIR_PATH);
        if (!bootDir.exists()) {
            if (!bootDir.mkdirs()) {
                Logger.logError(LOG_TAG, "Failed to create boot directory: " + BOOT_DIR_PATH);
                return false;
            }
            Logger.logDebug(LOG_TAG, "Created boot directory: " + BOOT_DIR_PATH);
        }

        // Create start.sh script (for Termux:Boot auto-start)
        if (!createStartScript()) {
            Logger.logError(LOG_TAG, "Failed to create start script");
            return false;
        }

        // Create setup script in home directory
        if (!createSetupScript()) {
            Logger.logError(LOG_TAG, "Failed to create setup script");
            return false;
        }

        // Add auto-run to .bashrc so setup runs when terminal opens
        if (!addBashrcEntry()) {
            Logger.logError(LOG_TAG, "Failed to add bashrc entry");
            return false;
        }

        // Mark as initialized
        prefs.edit().putInt(KEY_INITIALIZED, CURRENT_VERSION).apply();
        Logger.logInfo(LOG_TAG, "PocketAI initialized successfully");

        return true;
    }

    /**
     * Creates the setup script in home directory.
     * This script runs on first terminal open.
     */
    private static boolean createSetupScript() {
        String scriptContent = getSetupScriptContent();
        File scriptFile = new File(SETUP_SCRIPT_PATH);

        try {
            try (FileOutputStream fos = new FileOutputStream(scriptFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(scriptContent);
            }

            if (!scriptFile.setExecutable(true, false)) {
                Logger.logWarn(LOG_TAG, "setExecutable() returned false for setup script");
            }

            Logger.logInfo(LOG_TAG, "Created setup script: " + SETUP_SCRIPT_PATH);
            return true;

        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write setup script", e);
            return false;
        }
    }

    /**
     * Adds entry to .bashrc to auto-run setup on first terminal open.
     */
    private static boolean addBashrcEntry() {
        String bashrcEntry = "\n# PocketAI auto-setup (runs once on first open)\n" +
                "if [ -f \"$HOME/.pocketai_setup.sh\" ]; then\n" +
                "    bash \"$HOME/.pocketai_setup.sh\"\n" +
                "fi\n";

        File bashrcFile = new File(BASHRC_PATH);

        try {
            // Append to .bashrc (create if doesn't exist)
            try (FileOutputStream fos = new FileOutputStream(bashrcFile, true);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(bashrcEntry);
            }

            Logger.logInfo(LOG_TAG, "Added auto-setup entry to .bashrc");
            return true;

        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update .bashrc", e);
            return false;
        }
    }

    /**
     * Returns content of setup script that runs on first terminal open.
     */
    private static String getSetupScriptContent() {
        return "#!/data/data/com.nx.pai/files/usr/bin/bash\n" +
               "# PocketAI First-Run Setup Script\n" +
               "# This script runs automatically on first terminal open\n" +
               "\n" +
               "echo \"\"\n" +
               "echo \"========================================\"\n" +
               "echo \"   PocketAI - First Time Setup\"\n" +
               "echo \"========================================\"\n" +
               "echo \"\"\n" +
               "\n" +
               "# Remove this script after running (one-time setup)\n" +
               "rm -f \"$HOME/.pocketai_setup.sh\"\n" +
               "\n" +
               "# Remove the bashrc entry\n" +
               "sed -i '/pocketai_setup/d' \"$HOME/.bashrc\" 2>/dev/null\n" +
               "sed -i '/PocketAI auto-setup/d' \"$HOME/.bashrc\" 2>/dev/null\n" +
               "\n" +
               "echo \"[1/4] Updating package lists...\"\n" +
               "pkg update -y || { echo \"ERROR: pkg update failed\"; exit 1; }\n" +
               "\n" +
               "echo \"\"\n" +
               "echo \"[2/4] Installing dependencies (git, curl, proot-distro, tmux)...\"\n" +
               "pkg install -y git curl proot-distro tmux || { echo \"ERROR: Package installation failed\"; exit 1; }\n" +
               "\n" +
               "echo \"\"\n" +
               "echo \"[3/4] Cloning PocketAI repository...\"\n" +
               "if [ -d \"$HOME/PocketAi\" ]; then\n" +
               "    echo \"PocketAI directory exists, pulling latest...\"\n" +
               "    cd \"$HOME/PocketAi\" && git pull\n" +
               "else\n" +
               "    git clone https://github.com/mithun50/PocketAi.git \"$HOME/PocketAi\" || { echo \"ERROR: git clone failed\"; exit 1; }\n" +
               "fi\n" +
               "\n" +
               "echo \"\"\n" +
               "echo \"[4/4] Running PocketAI setup...\"\n" +
               "cd \"$HOME/PocketAi\"\n" +
               "chmod +x setup.sh\n" +
               "./setup.sh || { echo \"ERROR: setup.sh failed\"; exit 1; }\n" +
               "\n" +
               "echo \"\"\n" +
               "echo \"========================================\"\n" +
               "echo \"   PocketAI Setup Complete!\"\n" +
               "echo \"========================================\"\n" +
               "echo \"\"\n" +
               "echo \"To start the server, run:\"\n" +
               "echo \"  cd ~/PocketAi && pai api web\"\n" +
               "echo \"\"\n";
    }

    /**
     * Creates the start.sh boot script.
     *
     * This script will:
     * 1. Install required packages (git, curl, proot-distro, tmux)
     * 2. Clone PocketAI repository
     * 3. Run setup and start the server
     */
    private static boolean createStartScript() {
        String scriptContent = getStartScriptContent();

        File scriptFile = new File(START_SCRIPT_PATH);

        try {
            // Write script content
            try (FileOutputStream fos = new FileOutputStream(scriptFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(scriptContent);
            }

            // Make executable (chmod +x)
            if (!scriptFile.setExecutable(true, false)) {
                Logger.logWarn(LOG_TAG, "setExecutable() returned false, trying chmod");
                // Fallback: will be made executable when shell runs
            }

            Logger.logInfo(LOG_TAG, "Created start script: " + START_SCRIPT_PATH);
            return true;

        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write start script", e);
            return false;
        }
    }

    /**
     * Returns the content of start.sh boot script.
     */
    private static String getStartScriptContent() {
        return "#!/data/data/com.nx.pai/files/usr/bin/bash\n" +
               "# PocketAI Boot Script\n" +
               "# Auto-generated by PocketAiInitializer - DO NOT EDIT\n" +
               "# This script runs on Termux boot (requires Termux:Boot app)\n" +
               "\n" +
               "set -e\n" +
               "\n" +
               "LOGFILE=\"$HOME/.pocketai_boot.log\"\n" +
               "LOCKFILE=\"$HOME/.pocketai_setup.lock\"\n" +
               "POCKETAI_DIR=\"$HOME/PocketAi\"\n" +
               "\n" +
               "log() {\n" +
               "    echo \"[$(date '+%Y-%m-%d %H:%M:%S')] $1\" >> \"$LOGFILE\"\n" +
               "}\n" +
               "\n" +
               "# Prevent concurrent runs\n" +
               "if [ -f \"$LOCKFILE\" ]; then\n" +
               "    log \"Setup already running or locked, exiting\"\n" +
               "    exit 0\n" +
               "fi\n" +
               "\n" +
               "# Acquire wake lock to prevent sleep during setup\n" +
               "termux-wake-lock 2>/dev/null || true\n" +
               "\n" +
               "cleanup() {\n" +
               "    rm -f \"$LOCKFILE\"\n" +
               "}\n" +
               "trap cleanup EXIT\n" +
               "\n" +
               "touch \"$LOCKFILE\"\n" +
               "log \"PocketAI boot script started\"\n" +
               "\n" +
               "# Check if already set up\n" +
               "if [ -f \"$HOME/.pocketai_env\" ]; then\n" +
               "    log \"PocketAI already configured, starting server...\"\n" +
               "    source \"$HOME/.pocketai_env\"\n" +
               "    \n" +
               "    # Start server in tmux session if tmux is available\n" +
               "    if command -v tmux &> /dev/null; then\n" +
               "        tmux has-session -t pocketai 2>/dev/null || \\\n" +
               "            tmux new-session -d -s pocketai \"cd $POCKETAI_DIR && pai api web\" 2>/dev/null\n" +
               "        log \"Server started in tmux session 'pocketai'\"\n" +
               "    else\n" +
               "        # Fallback: run directly (will stop when terminal closes)\n" +
               "        cd \"$POCKETAI_DIR\" && nohup pai api web > \"$HOME/.pocketai_server.log\" 2>&1 &\n" +
               "        log \"Server started in background\"\n" +
               "    fi\n" +
               "    exit 0\n" +
               "fi\n" +
               "\n" +
               "log \"First run - installing dependencies...\"\n" +
               "\n" +
               "# Update package lists\n" +
               "pkg update -y >> \"$LOGFILE\" 2>&1 || {\n" +
               "    log \"ERROR: pkg update failed\"\n" +
               "    exit 1\n" +
               "}\n" +
               "\n" +
               "# Install required packages\n" +
               "pkg install -y git curl proot-distro tmux >> \"$LOGFILE\" 2>&1 || {\n" +
               "    log \"ERROR: Package installation failed\"\n" +
               "    exit 1\n" +
               "}\n" +
               "\n" +
               "log \"Dependencies installed, cloning PocketAI...\"\n" +
               "\n" +
               "# Clone PocketAI\n" +
               "if [ -d \"$POCKETAI_DIR\" ]; then\n" +
               "    log \"PocketAI directory exists, pulling latest...\"\n" +
               "    cd \"$POCKETAI_DIR\" && git pull >> \"$LOGFILE\" 2>&1\n" +
               "else\n" +
               "    git clone https://github.com/mithun50/PocketAi.git \"$POCKETAI_DIR\" >> \"$LOGFILE\" 2>&1 || {\n" +
               "        log \"ERROR: git clone failed\"\n" +
               "        exit 1\n" +
               "    }\n" +
               "fi\n" +
               "\n" +
               "log \"Running PocketAI setup...\"\n" +
               "\n" +
               "# Run setup script\n" +
               "cd \"$POCKETAI_DIR\"\n" +
               "chmod +x setup.sh\n" +
               "./setup.sh >> \"$LOGFILE\" 2>&1 || {\n" +
               "    log \"ERROR: setup.sh failed\"\n" +
               "    exit 1\n" +
               "}\n" +
               "\n" +
               "# Source environment\n" +
               "if [ -f \"$HOME/.pocketai_env\" ]; then\n" +
               "    source \"$HOME/.pocketai_env\"\n" +
               "    log \"Environment loaded\"\n" +
               "else\n" +
               "    log \"WARNING: .pocketai_env not found after setup\"\n" +
               "fi\n" +
               "\n" +
               "log \"Starting PocketAI server...\"\n" +
               "\n" +
               "# Start server in tmux\n" +
               "if command -v tmux &> /dev/null; then\n" +
               "    tmux new-session -d -s pocketai \"source $HOME/.pocketai_env && cd $POCKETAI_DIR && pai api web\" 2>/dev/null\n" +
               "    log \"Server started in tmux session 'pocketai'\"\n" +
               "else\n" +
               "    cd \"$POCKETAI_DIR\" && nohup pai api web > \"$HOME/.pocketai_server.log\" 2>&1 &\n" +
               "    log \"Server started in background (no tmux)\"\n" +
               "fi\n" +
               "\n" +
               "log \"PocketAI boot script completed successfully\"\n";
    }

    /**
     * Check if PocketAI has been initialized.
     */
    public static boolean isInitialized(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_INITIALIZED, 0) >= CURRENT_VERSION;
    }

    /**
     * Reset initialization state (for testing/debugging).
     */
    public static void resetInitialization(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_INITIALIZED).apply();
        Logger.logInfo(LOG_TAG, "PocketAI initialization state reset");
    }
}
