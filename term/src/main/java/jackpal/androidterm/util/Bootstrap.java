package jackpal.androidterm.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;
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

    private static final String VERSION = "1";
    private static final String MARKER = ".bootstrap-version";
    private static final String FAILED_MARKER = ".bootstrap-failed";

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

    private static String assetName() {
        String abi = Build.SUPPORTED_ABIS[0];
        String arch = abi;
        if (abi.equals("arm64-v8a")) {
            arch = "aarch64";
        } else if (abi.equals("armeabi-v7a")) {
            arch = "arm";
        }
        return "bootstrap-" + arch + ".zip";
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

    public static void install(Context context) throws IOException {
        File files_dir = context.getFilesDir();
        String files_root = files_dir.getCanonicalPath();
        AssetManager assets = context.getAssets();
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(assets.open(assetName()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(files_dir, entry.getName());
                if (!target.getCanonicalPath().startsWith(files_root)) {
                    throw new IOException("Bad zip entry: " + entry.getName());
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
                int mode = (int) (entry.getExternalAttributes() >> 16) & 0777;
                Os.chmod(target.getAbsolutePath(), mode != 0 ? mode : 0644);
            }
        }
        createSymlinks();
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

    private static void createSymlinks() throws IOException {
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
                File link = new File(getPrefixDir(), line.substring(arrow + 1));
                link.getParentFile().mkdirs();
                if (!link.exists()) {
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
