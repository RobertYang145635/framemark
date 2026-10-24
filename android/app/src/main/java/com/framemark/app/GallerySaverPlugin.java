package com.framemark.app;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;

@CapacitorPlugin(name = "GallerySaver")
public class GallerySaverPlugin extends Plugin {

    @PluginMethod
    public void saveImage(PluginCall call) {
        saveToMedia(call, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_PICTURES + "/FrameMark", "image/jpeg");
    }

    @PluginMethod
    public void saveDocument(PluginCall call) {
        String subdir = call.getString("subdir", "FrameMark");
        if (subdir == null || subdir.isEmpty()) subdir = "FrameMark";
        saveToMedia(call, MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_DOWNLOADS + "/" + subdir, "application/json");
    }

    @PluginMethod
    public void shareImage(PluginCall call) {
        String uriString = call.getString("uri");
        String mimeType = call.getString("mimeType", "image/jpeg");
        String title = call.getString("title", "分享照片");
        if (uriString == null || uriString.isEmpty()) {
            call.reject("缺少要分享的文件");
            return;
        }
        Uri uri = Uri.parse(uriString);
        if (!"content".equals(uri.getScheme())) {
            call.reject("仅支持分享应用相册文件");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(ClipData.newRawUri("", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, title);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(chooser);
            call.resolve();
        } catch (Exception error) {
            call.reject("分享失败：" + error.getMessage(), error);
        }
    }

    private void saveToMedia(PluginCall call, Uri collection, String relativePath, String defaultMime) {
        String base64 = call.getString("base64");
        String fileName = call.getString("fileName");
        String mimeType = call.getString("mimeType", defaultMime);
        if (base64 == null || base64.isEmpty()) {
            call.reject("文件数据为空");
            return;
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = "framemark.jpg";
        }
        fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "-");

        Uri item = null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            ContentResolver resolver = getContext().getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            item = resolver.insert(collection, values);
            if (item == null) {
                call.reject("无法创建文件条目");
                return;
            }
            try (OutputStream stream = resolver.openOutputStream(item, "w")) {
                if (stream == null) throw new IllegalStateException("无法写入文件");
                stream.write(bytes);
                stream.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, done, null, null);
            JSObject result = new JSObject();
            result.put("uri", item.toString());
            call.resolve(result);
        } catch (Exception error) {
            if (item != null) {
                try {
                    getContext().getContentResolver().delete(item, null, null);
                } catch (Exception ignored) {
                }
            }
            call.reject("保存文件失败：" + error.getMessage(), error);
        }
    }
}
