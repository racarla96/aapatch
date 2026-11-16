package dev.aapatch.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

/** Receives the PackageInstaller result: asks the user to confirm, then reports success or failure. */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = confirmIntent(intent);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS: {
                String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                Installer.report("Instalada: " + pkg + "\nOrigen registrado por Android: " + installerOf(context, pkg)
                        + "\n(Si dice com.android.vending, Android Auto la verá como instalada desde Play Store.)");
                break;
            }
            default:
                Installer.report("No se pudo instalar (código " + status + "): " + message);
        }
    }

    /** Which app Android recorded as the installer of {@code pkg} (null if none). */
    @SuppressWarnings("deprecation")
    private static String installerOf(Context c, String pkg) {
        if (pkg == null) return "?";
        try {
            PackageManager pm = c.getPackageManager();
            String s = Build.VERSION.SDK_INT >= 30
                    ? pm.getInstallSourceInfo(pkg).getInstallingPackageName()
                    : pm.getInstallerPackageName(pkg);
            return s == null ? "(ninguno)" : s;
        } catch (Exception e) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmIntent(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        return i.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
