package jackpal.androidterm.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import jackpal.androidterm.TermDebug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The built-in Termux-style environment: a prefix tree under files/usr
 * (bash, clang, ssh, coreutils, ...) extracted on first launch from a
 * bootstrap zip in assets, built by bootstrap/build_bootstrap.py from the
 * Termux package repository.
 */
public class Bootstrap {
    public static final String PREF_BOOTSTRAP_SHELL = "bootstrap_shell";
    public static final String PREF_BUSYBOX_SHELL = "busybox_shell";
    public static final String PREF_BASH_SHELL = "bash_shell";

    private static final String VERSION = "1";
    private static final String MARKER = ".bootstrap-version";
    private static final String FAILED_MARKER = ".bootstrap-failed";
    private static final String ENV_VERSION = "2";
    private static final String ENV_MARKER = ".env-version";
    private static final String ENV_FAILED_MARKER = ".env-failed";

    private static Context sContext;

    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    public static File getPrefixDir() {
        return new File(sContext.getFilesDir(), "usr");
    }

    public static File getHomeDir() {
        return new File(sContext.getFilesDir(), "home");
    }

    public static String getPrefixPath() {
        return getPrefixDir().getAbsolutePath();
    }

    public static String getHomePath() {
        return getHomeDir().getAbsolutePath();
    }

    private static String archName() {
        String abi = Build.SUPPORTED_ABIS[0];
        if (abi.equals("arm64-v8a")) {
            return "aarch64";
        } else if (abi.equals("armeabi-v7a")) {
            return "arm";
        }
        return abi;
    }

    private static String assetName() {
        return "bootstrap-" + archName() + ".zip";
    }

    private static String envAssetName() {
        return "busybox-" + archName() + ".zip";
    }

    public static File getBusyBoxFile() {
        return new File(getPrefixDir(), "bin/busybox");
    }

    public static File getBashFile() {
        return new File(getPrefixDir(), "bin/bash");
    }

    /** Creates the environment layout: files/usr/{bin,lib,include,tmp} and files/home. */
    public static void ensureLayout() {
        for (String name : new String[] {"bin", "lib", "include", "tmp"}) {
            new File(getPrefixDir(), name).mkdirs();
        }
        getHomeDir().mkdirs();
    }

    public static boolean hasAsset(Context context) {
        try (InputStream in = context.getAssets().open(assetName())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isInstalled() {
        File marker = new File(getPrefixDir(), MARKER);
        File bash = new File(getPrefixDir(), "bin/bash");
        if (!marker.exists() || !bash.exists()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
            return VERSION.equals(reader.readLine());
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean needsInstall(Context context) {
        return hasAsset(context) && !isInstalled()
                && !new File(context.getFilesDir(), FAILED_MARKER).exists();
    }

    public static void markFailed(Context context) {
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), FAILED_MARKER))) {
            writer.write("failed");
        } catch (IOException e) {
            Log.e(TermDebug.LOG_TAG, "Could not write bootstrap failure marker", e);
        }
    }

    private static void extractZip(Context context, String asset) throws IOException, ErrnoException {
        File files_dir = context.getFilesDir();
        String files_root = files_dir.getCanonicalPath();
        AssetManager assets = context.getAssets();
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(assets.open(asset))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(files_dir, entry.getName());
                if (!target.getCanonicalPath().startsWith(files_root)) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }
                if (isSymlink(target)) {
                    target.delete();
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                target.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                Os.chmod(target.getAbsolutePath(), 0644);
            }
        }
    }

    public static boolean hasEnvAsset(Context context) {
        try (InputStream in = context.getAssets().open(envAssetName())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isEnvInstalled() {
        File marker = new File(getPrefixDir(), ENV_MARKER);
        if (!marker.exists() || !getBusyBoxFile().exists()) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
            return ENV_VERSION.equals(reader.readLine());
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean needsEnvInstall(Context context) {
        return hasEnvAsset(context) && !isEnvInstalled()
                && !new File(context.getFilesDir(), ENV_FAILED_MARKER).exists();
    }

    public static void markEnvFailed(Context context) {
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), ENV_FAILED_MARKER))) {
            writer.write("failed");
        } catch (IOException e) {
            Log.e(TermDebug.LOG_TAG, "Could not write env failure marker", e);
        }
    }

    /**
     * Installs the BusyBox environment asset (usr/bin/busybox, applet
     * symlinks, sysroot headers/libraries) into the files/ prefix, using
     * the same SYMLINKS.txt / EXECUTABLES.txt mechanism as the bootstrap.
     */
    public static void installEnv(Context context) throws IOException, ErrnoException {
        extractZip(context, envAssetName());
        applyExecutables();
        createSymlinks(getPrefixDir());
        ensureLayout();
        File tmp = new File(getPrefixDir(), "tmp");
        Os.chmod(tmp.getAbsolutePath(), 0700);
        writeHomeFiles();
        try (FileWriter writer = new FileWriter(new File(getPrefixDir(), ENV_MARKER))) {
            writer.write(ENV_VERSION);
        }
        Log.i(TermDebug.LOG_TAG, "BusyBox environment installed to " + getPrefixPath());
    }

    public static void install(Context context) throws IOException, ErrnoException {
        extractZip(context, assetName());
        applyExecutables();
        createSymlinks(context.getFilesDir());
        File tmp = new File(getPrefixDir(), "tmp");
        tmp.mkdirs();
        Os.chmod(tmp.getAbsolutePath(), 0700);
        for (String dir_name : new String[] {"bin", "libexec"}) {
            File dir = new File(getPrefixDir(), dir_name);
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file.isFile()) {
                    Os.chmod(file.getAbsolutePath(), 0755);
                }
            }
        }
        writeHomeFiles();
        try (FileWriter writer = new FileWriter(new File(getPrefixDir(), MARKER))) {
            writer.write(VERSION);
        }
        Log.i(TermDebug.LOG_TAG, "Bootstrap environment installed to " + getPrefixPath());
    }

    private static void applyExecutables() throws IOException, ErrnoException {
        File list = new File(getPrefixDir(), "EXECUTABLES.txt");
        if (!list.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(list))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 0) {
                    Os.chmod(new File(getPrefixDir(), line).getAbsolutePath(), 0755);
                }
            }
        }
    }

    private static boolean isSymlink(File file) {
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            return (stat.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
        } catch (ErrnoException e) {
            return false;
        }
    }

    /**
     * Creates the links listed in the prefix's SYMLINKS.txt. The link
     * paths in that list are relative to base_dir: the files directory
     * for the bootstrap zip (its entries are rooted at usr/), the prefix
     * itself for the built-in environment zip (its entries are rooted
     * at bin/, lib/, ...). Existing files and links are left alone, so
     * a real file installed by either environment always wins over a
     * symlink of the same name.
     */
    private static void createSymlinks(File base_dir) throws IOException, ErrnoException {
        File list = new File(getPrefixDir(), "SYMLINKS.txt");
        if (!list.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(list))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int arrow = line.indexOf('←');
                if (arrow < 0) {
                    continue;
                }
                String target = line.substring(0, arrow);
                File link = new File(base_dir, line.substring(arrow + 1));
                link.getParentFile().mkdirs();
                if (!link.exists() && !isSymlink(link)) {
                    Os.symlink(target, link.getAbsolutePath());
                }
            }
        }
    }

    private static void writeHomeFiles() throws IOException {
        File home = getHomeDir();
        home.mkdirs();
        File profile = new File(home, ".bash_profile");
        if (!profile.exists()) {
            try (FileWriter writer = new FileWriter(profile)) {
                writer.write("[ -f \"$HOME/.bashrc\" ] && . \"$HOME/.bashrc\"\n");
            }
        }
        File rc = new File(home, ".bashrc");
        if (!rc.exists()) {
            try (FileWriter writer = new FileWriter(rc)) {
                writer.write("cd \"$HOME\"\n");
            }
        }
    }
}
