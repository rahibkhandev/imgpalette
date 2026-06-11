package com.rahibkhandev.palettesnap;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;

import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private static final int FILE_CHOOSER_REQUEST = 1;
    private static final int STORAGE_PERMISSION_REQUEST = 2;
    private static final String SITE_URL = "https://palettesnap-six.vercel.app/";

    // Store pending base64 data while waiting for permission
    private String pendingBase64Url = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        // Enable all necessary settings
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        // Keep links inside app
        webView.setWebViewClient(new WebViewClient());

        // Fix upload button
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {

                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(
                        Intent.createChooser(intent, "Select Image"),
                        FILE_CHOOSER_REQUEST
                );
                return true;
            }
        });

        // Handle download button
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("data:image")) {
                // Check and request permission first
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    // Android 9 and below needs permission
                    if (ContextCompat.checkSelfPermission(this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                        // Save URL and request permission
                        pendingBase64Url = url;
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                STORAGE_PERMISSION_REQUEST);
                        return;
                    }
                }
                // Android 10+ doesn't need permission — save directly
                saveBase64Image(url);
            }
        });

        webView.loadUrl(SITE_URL);
    }

    // Called after user allows or denies permission
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted — save the pending image
                if (pendingBase64Url != null) {
                    saveBase64Image(pendingBase64Url);
                    pendingBase64Url = null;
                }
            } else {
                Toast.makeText(this,
                        "Storage permission needed to save palette.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // Save base64 PNG to Downloads + Gallery
    private void saveBase64Image(String base64Url) {
        new Thread(() -> {
            try {
                // Decode base64 to bitmap
                String base64Data = base64Url.substring(base64Url.indexOf(",") + 1);
                byte[] imageBytes = Base64.decode(base64Data, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                String fileName = "palettesnap-" + System.currentTimeMillis() + ".png";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ — use MediaStore (no permission needed)
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);

                    Uri uri = getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                    if (uri != null) {
                        try (FileOutputStream fos = (FileOutputStream)
                                getContentResolver().openOutputStream(uri)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        }
                        values.clear();
                        values.put(MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);

                        runOnUiThread(() -> Toast.makeText(this,
                                "✅ Palette saved to Downloads & Gallery!",
                                Toast.LENGTH_LONG).show());
                    }

                } else {
                    // Android 9 and below
                    java.io.File downloadsDir = Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    java.io.File file = new java.io.File(downloadsDir, fileName);
                    FileOutputStream fos = new FileOutputStream(file);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.flush();
                    fos.close();

                    // Scan file so it appears in Gallery
                    Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    scanIntent.setData(Uri.fromFile(file));
                    sendBroadcast(scanIntent);

                    runOnUiThread(() -> Toast.makeText(this,
                            "✅ Palette saved to Downloads !",
                            Toast.LENGTH_LONG).show());
                }

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "❌ Save failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // Handle file picker result
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileUploadCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
        }
    }

    // Back button navigates inside webview
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}