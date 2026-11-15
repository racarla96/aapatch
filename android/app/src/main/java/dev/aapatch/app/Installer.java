package dev.aapatch.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;

/** Installs one app made of one or more APKs through a PackageInstaller session. */
final class Installer {
    private Installer() {}

    static final String ACTION = "dev.aapatch.app.INSTALL_STATUS";

    /** Set by the visible activity so install results can be shown in the UI. */
    static volatile Consumer<String> statusSink;

    static void report(String message) {
        Consumer<String> s = statusSink;
        if (s != null) s.accept(message);
    }

    static void install(Context ctx, List<File> apks) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            byte[] buf = new byte[1 << 16];
            for (File f : apks) {
                try (InputStream in = new FileInputStream(f);
                     OutputStream out = session.openWrite(f.getName(), 0, f.length())) {
                    for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
                    session.fsync(out);
                }
            }
            // Explicit intent (required on Android 14+) and MUTABLE so the system can add the status extras.
            Intent intent = new Intent(ctx, InstallReceiver.class).setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, intent, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try { pi.abandonSession(id); } catch (RuntimeException ignored) { }
            throw e;
        }
    }
}
