package dev.amenhancer.plugin.api;

import android.app.Application;

import java.io.File;
import java.io.InputStream;

/** 编译期桩：真实类型是 interface。 */
public interface PluginContext {
    int API_VERSION = 1;

    Application getApplication();

    File getCacheDirectory();

    File getDataDirectory();

    PluginHooks getHooks();

    ClassLoader getHostClassLoader();

    String getHostPackageName();

    long getHostVersionCode();

    String getHostVersionName();

    void log(String message, Throwable error);

    void onClose(Runnable cleanup);

    InputStream openAsset(String name);

    PluginRegistration claimResource(String key, boolean exclusive);
}
