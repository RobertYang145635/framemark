package com.framemark.app;

import android.app.Activity;
import android.content.Intent;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * S4: thin Capacitor bridge to ExportService.
 *
 * `start` is the only call that may create the service (the app is in the
 * foreground at that point). `update`/`stop` talk to the live instance directly:
 * calling startService() again would be a background service start on Android
 * 12+ and throw once the screen is off.
 *
 * The renderer policy is the other half of the slice: the foreground service
 * keeps the APP process alive, but the WebView renderer is a separate sandboxed
 * process that the OS may freeze once the activity is invisible (screen off),
 * which stops the JS export loop dead. RENDERER_PRIORITY_IMPORTANT tells the
 * system this renderer is doing user-visible work; it is restored on stop.
 */
@CapacitorPlugin(name = "ExportForeground")
public class ExportForegroundPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        Integer totalBoxed = call.getInt("total", 0);
        int total = totalBoxed == null ? 0 : totalBoxed;
        String current = call.getString("current", "");
        ExportService live = ExportService.getInstance();
        if (live != null) {
            live.updateProgress(0, total, current);
            setRendererImportant(true);
            resolveRunning(call, true);
            return;
        }
        try {
            Intent intent = new Intent(getContext(), ExportService.class);
            intent.setAction(ExportService.ACTION_START);
            intent.putExtra(ExportService.EXTRA_TOTAL, total);
            intent.putExtra(ExportService.EXTRA_DONE, 0);
            intent.putExtra(ExportService.EXTRA_CURRENT, current == null ? "" : current);
            ContextCompat.startForegroundService(getContext(), intent);
            setRendererImportant(true);
            resolveRunning(call, true);
        } catch (Exception error) {
            // Never let a service failure fail the export: the batch still runs,
            // it just may be throttled if the screen turns off.
            JSObject result = new JSObject();
            result.put("running", false);
            result.put("error", error.getMessage() == null ? "start failed" : error.getMessage());
            call.resolve(result);
        }
    }

    @PluginMethod
    public void update(PluginCall call) {
        Integer doneBoxed = call.getInt("done", 0);
        Integer totalBoxed = call.getInt("total", 0);
        int done = doneBoxed == null ? 0 : doneBoxed;
        int total = totalBoxed == null ? 0 : totalBoxed;
        String current = call.getString("current", "");
        ExportService live = ExportService.getInstance();
        if (live != null) {
            live.updateProgress(done, total, current);
        }
        resolveRunning(call, live != null);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        ExportService live = ExportService.getInstance();
        if (live != null) {
            live.stopExport();
        } else {
            try {
                getContext().stopService(new Intent(getContext(), ExportService.class));
            } catch (Exception ignored) {
                // already gone
            }
        }
        setRendererImportant(false);
        resolveRunning(call, false);
    }

    /**
     * Keep the WebView renderer out of the cached-process freezer while a batch
     * runs, and hand it back to the default (visible) policy when it stops.
     * Best effort: an OEM WebView without the API must not break the export.
     */
    private void setRendererImportant(final boolean important) {
        final Activity activity = getActivity();
        if (!(activity instanceof BridgeActivity)) return;
        final BridgeActivity bridgeActivity = (BridgeActivity) activity;
        activity.runOnUiThread(() -> {
            try {
                if (bridgeActivity.getBridge() == null) return;
                WebView webView = bridgeActivity.getBridge().getWebView();
                if (webView == null) return;
                webView.setRendererPriorityPolicy(
                        important ? WebView.RENDERER_PRIORITY_IMPORTANT : WebView.RENDERER_PRIORITY_VISIBLE,
                        !important);
            } catch (Throwable ignored) {
                // older WebView: keep the default policy
            }
        });
    }

    private void resolveRunning(PluginCall call, boolean running) {
        JSObject result = new JSObject();
        result.put("running", running);
        call.resolve(result);
    }
}
