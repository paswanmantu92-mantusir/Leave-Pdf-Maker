package com.mantusir.leavepdf;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.LinearLayout;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback;


import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

public class MainActivity extends Activity {
    private AdView bannerAdView;
    private InterstitialAd interstitialAd;
    private long lastInterstitialShownAt = 0L;
    private AppOpenAd appOpenAd;
    private boolean isShowingAppOpenAd = false;



    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int CAMERA_REQUEST = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MobileAds.initialize(this, initializationStatus -> {
            loadInterstitialAd();
            loadAppOpenAd();
        });


        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        /*
         * CHOOSE FILE
         */
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {

                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }

                filePathCallback = callback;

                try {
                    Intent intent = params.createIntent();

                    startActivityForResult(
                            intent,
                            FILE_CHOOSER_REQUEST
                    );

                    return true;

                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        /*
         * NORMAL WEB DOWNLOADS
         */
        webView.setDownloadListener(new DownloadListener() {

            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength) {

                if (url != null &&
                        url.toLowerCase().endsWith(".apk")) {

                    downloadAndInstallApk(url, userAgent);

                    return;
                }

                Toast.makeText(
                        MainActivity.this,
                        "Download Folder में Save किया गया!",
                        Toast.LENGTH_SHORT
                ).show();

                try {

                    android.app.DownloadManager.Request request =
                            new android.app.DownloadManager.Request(
                                    Uri.parse(url)
                            );

                    String cookies =
                            CookieManager.getInstance()
                                    .getCookie(url);

                    if (cookies != null) {
                        request.addRequestHeader(
                                "Cookie",
                                cookies
                        );
                    }

                    request.addRequestHeader(
                            "User-Agent",
                            userAgent
                    );

                    request.setMimeType(mimeType);

                    String fileName =
                            android.webkit.URLUtil.guessFileName(
                                    url,
                                    contentDisposition,
                                    mimeType
                            );

                    request.setTitle(fileName);
                    request.setDescription("Downloading file");

                    request.setNotificationVisibility(
                            android.app.DownloadManager
                                    .Request
                                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );

                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                    );

                    android.app.DownloadManager manager =
                            (android.app.DownloadManager)
                                    getSystemService(
                                            DOWNLOAD_SERVICE
                                    );

                    if (manager != null) {
                        manager.enqueue(request);
                    }

                } catch (Exception e) {
                    e.printStackTrace();

                    Toast.makeText(
                            MainActivity.this,
                            "डाउनलोड में जाकर देखें",
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
        });

        /*
         * JAVASCRIPT BRIDGE FOR jsPDF BLOB
         */
        webView.addJavascriptInterface(
                new BlobDownloadInterface(),
                "AndroidDownload"
        );

        /*
         * NATIVE CAMERA BRIDGE
         */
        webView.addJavascriptInterface(
                new CameraInterface(),
                "AndroidCamera"
        );

        webView.addJavascriptInterface(
                new AdsInterface(),
                "AndroidAds"
        );

        /*
         * WEB PAGE
         */
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageFinished(
                    WebView view,
                    String url) {

                super.onPageFinished(view, url);

                installBlobDownloadHook(view);
                addSeeMoreCornerButton(view);
                installCameraButtonHook(view);
                installDownloadButtonAdHook(view);
                webView.postDelayed(
                        () -> showAppOpenAdIfAvailable(),
                        1000
                );
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url) {

                if (url != null &&
                        url.startsWith("blob:")) {

                    return true;
                }

                return false;
            }
        });

        LinearLayout adRoot = new LinearLayout(this);
        adRoot.setOrientation(LinearLayout.VERTICAL);

        adRoot.addView(
                webView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        bannerAdView = new AdView(this);
        bannerAdView.setAdSize(AdSize.BANNER);
        bannerAdView.setAdUnitId(
                "ca-app-pub-5927800302920093/4289580788"
        );

        adRoot.addView(
                bannerAdView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        setContentView(adRoot);
        bannerAdView.loadAd(new AdRequest.Builder().build());

        webView.loadUrl(
                "https://projects.mantusir.in/p/make-pdf.html?m=1"
        );
    }


    private void installDownloadButtonAdHook(WebView view) {
        String js =
                "(function() {" +
                "if(window.__leavePdfDownloadAdHookInstalled)return;" +
                "window.__leavePdfDownloadAdHookInstalled=true;" +
                "function hook(){" +
                "var bs=document.querySelectorAll('button,input[type=button],input[type=submit]');" +
                "for(var i=0;i<bs.length;i++){" +
                "var b=bs[i];" +
                "var t=((b.innerText||b.value||b.textContent||'')+'').trim();" +
                "if(t.indexOf('डाउनलोड करें!')!==-1&&!b.__leavePdfDownloadAdHooked){" +
                "b.__leavePdfDownloadAdHooked=true;" +
                "b.addEventListener('click',function(e){" +
                "if(window.__leavePdfContinueDownload){" +
                "window.__leavePdfContinueDownload=false;return;}" +
                "e.preventDefault();e.stopImmediatePropagation();" +
                "AndroidAds.showDownloadInterstitial();" +
                "},true);" +
                "}}}" +
                "window.__leavePdfContinueDownload=false;" +
                "hook();" +
                "new MutationObserver(hook).observe(document.documentElement,{childList:true,subtree:true});" +
                "})();";

        view.evaluateJavascript(js, null);
    }

    private void continueDownloadAfterAd() {
        if (webView == null) return;

        webView.evaluateJavascript(
                "(function(){" +
                "window.__leavePdfContinueDownload=true;" +
                "var bs=document.querySelectorAll('button,input[type=button],input[type=submit]');" +
                "for(var i=0;i<bs.length;i++){" +
                "var b=bs[i];" +
                "var t=((b.innerText||b.value||b.textContent||'')+'').trim();" +
                "if(t.indexOf('डाउनलोड करें!')!==-1){b.click();break;}" +
                "}" +
                "})();",
                null
        );
    }

    private class AdsInterface {
        @JavascriptInterface
        public void showDownloadInterstitial() {
            runOnUiThread(() ->
                    showInterstitialIfReady(() -> continueDownloadAfterAd())
            );
        }
    }

    private void installCameraButtonHook(WebView view) {

        String js =
                "(function() {" +
                "if (window.__leavePdfCameraHookInstalled) return;" +
                "window.__leavePdfCameraHookInstalled = true;" +
                "function hook() {" +
                "  var btn = document.getElementById('capturePhotoBtn');" +
                "  if (!btn) {" +
                "    var all = document.querySelectorAll('button,input[type=button],input[type=submit]');" +
                "    for (var i = 0; i < all.length; i++) {" +
                "      var t = ((all[i].innerText || all[i].value || all[i].textContent || '') + '').trim().toLowerCase();" +
                "      if (t.indexOf('capture photo') !== -1 || t.indexOf('📷 capture photo') !== -1) {" +
                "        btn = all[i];" +
                "        break;" +
                "      }" +
                "    }" +
                "  }" +
                "  if (btn && !btn.__leavePdfCameraHooked) {" +
                "    btn.__leavePdfCameraHooked = true;" +
                "    btn.addEventListener('click', function(e) {" +
                "      e.preventDefault();" +
                "      e.stopImmediatePropagation();" +
                "      AndroidCamera.openCamera();" +
                "    }, true);" +
                "  }" +
                "}" +
                "hook();" +
                "new MutationObserver(hook).observe(document.documentElement, {childList:true, subtree:true});" +
                "})();";

        view.evaluateJavascript(js, null);
    }

    private void addSeeMoreCornerButton(WebView view) {
        String js =
            "(function() {" +
            "if (document.getElementById('leavePdfSeeMoreButton')) return;" +
            "var btn = document.createElement('button');" +
            "btn.id = 'leavePdfSeeMoreButton';" +
            "btn.innerText = 'See More...';" +
            "btn.style.position = 'fixed';" +
            "btn.style.right = '12px';" +
            "btn.style.top = '5px';" +
            "btn.style.zIndex = '999999';" +
            "btn.style.padding = '9px 14px';" +
            "btn.style.border = '0';" +
            "btn.style.borderRadius = '20px';" +
            "btn.style.fontSize = '14px';" +
            "btn.style.fontWeight = 'bold';" +
            "btn.style.background = '#ffffff';" +
            "btn.style.color = '#222222';" +
            "btn.style.boxShadow = '0 2px 8px rgba(0,0,0,0.25)';" +
            "btn.onclick = function() {" +
            "window.location.href = 'https://projects.mantusir.in';" +
            "};" +
            "document.body.appendChild(btn);" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    /*
     * APK DOWNLOAD + AUTOMATIC INSTALLER
     */
    private void downloadAndInstallApk(
            String apkUrl,
            String userAgent) {

        Toast.makeText(
                MainActivity.this,
                "Download Folder में Save किया गया!",
                Toast.LENGTH_SHORT
        ).show();

        new Thread(() -> {

            File apkFile = null;

            try {

                URL url = new URL(apkUrl);

                HttpURLConnection connection =
                        (HttpURLConnection) url.openConnection();

                connection.setRequestProperty(
                        "User-Agent",
                        userAgent
                );

                connection.setInstanceFollowRedirects(true);
                connection.connect();

                if (connection.getResponseCode() < 200 ||
                        connection.getResponseCode() >= 400) {

                    throw new Exception(
                            "Download failed: HTTP " +
                                    connection.getResponseCode()
                    );
                }

                apkFile = new File(
                        getExternalFilesDir(
                                Environment.DIRECTORY_DOWNLOADS
                        ),
                        "Leave-Pdf-Maker.apk"
                );

                InputStream input =
                        connection.getInputStream();

                FileOutputStream output =
                        new FileOutputStream(apkFile);

                byte[] buffer = new byte[8192];
                int length;

                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }

                output.flush();
                output.close();
                input.close();

                connection.disconnect();

                File finalApkFile = apkFile;

                runOnUiThread(() ->
                        openApkInstaller(finalApkFile)
                );

            } catch (Exception e) {

                e.printStackTrace();

                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "डाउनलोड में जाकर देखें",
                                Toast.LENGTH_LONG
                        ).show()
                );
            }

        }).start();
    }

    /*
     * OPEN ANDROID APK INSTALLER
     */
    private void openApkInstaller(File apkFile) {

        try {

            Uri apkUri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent intent =
                    new Intent(Intent.ACTION_VIEW);

            intent.setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
            );

            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
            );

            startActivity(intent);

        } catch (Exception e) {

            e.printStackTrace();

            Toast.makeText(
                    MainActivity.this,
                    "डाउनलोड में जाकर देखें",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    /*
     * Intercept URL.createObjectURL(blob)
     */
    private void loadInterstitialAd() {
        InterstitialAd.load(
                this,
                "ca-app-pub-5927800302920093/6796235339",
                new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        interstitialAd = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(
                            com.google.android.gms.ads.LoadAdError error) {
                        interstitialAd = null;
                    }
                }
        );
    }

    private boolean showInterstitialIfReady(Runnable afterAd) {
        long now = System.currentTimeMillis();

        if (interstitialAd == null) {
            if (afterAd != null) afterAd.run();
            loadInterstitialAd();
            return false;
        }

        if (now - lastInterstitialShownAt < 60000L) {
            if (afterAd != null) afterAd.run();
            return false;
        }

        lastInterstitialShownAt = now;

        interstitialAd.setFullScreenContentCallback(
                new com.google.android.gms.ads.FullScreenContentCallback() {
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        interstitialAd = null;
                        loadInterstitialAd();
                        if (afterAd != null) afterAd.run();
                    }

                    @Override
                    public void onAdFailedToShowFullScreenContent(
                            com.google.android.gms.ads.AdError error) {
                        interstitialAd = null;
                        loadInterstitialAd();
                        if (afterAd != null) afterAd.run();
                    }
                }
        );

        interstitialAd.show(this);
        return true;
    }

    private void loadAppOpenAd() {
        AppOpenAd.load(
                this,
                "ca-app-pub-5927800302920093/2038732675",
                new AdRequest.Builder().build(),
                new AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(AppOpenAd ad) {
                        appOpenAd = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(
                            com.google.android.gms.ads.LoadAdError adError
                    ) {
                        appOpenAd = null;
                    }
                }
        );
    }

    private void showAppOpenAdIfAvailable() {
        if (isShowingAppOpenAd) return;

        if (appOpenAd == null) {
            loadAppOpenAd();
            return;
        }

        isShowingAppOpenAd = true;

        appOpenAd.setFullScreenContentCallback(
                new com.google.android.gms.ads.FullScreenContentCallback() {
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        appOpenAd = null;
                        isShowingAppOpenAd = false;
                        loadAppOpenAd();
                    }

                    @Override
                    public void onAdFailedToShowFullScreenContent(
                            com.google.android.gms.ads.AdError error) {
                        appOpenAd = null;
                        isShowingAppOpenAd = false;
                        loadAppOpenAd();
                    }
                }
        );

        appOpenAd.show(this);
    }

    private void installBlobDownloadHook(WebView view) {

        String js =
                "(function() {" +

                "if (window.__androidBlobHookInstalled) return;" +
                "window.__androidBlobHookInstalled = true;" +

                "const originalCreateObjectURL = " +
                "URL.createObjectURL.bind(URL);" +

                "URL.createObjectURL = function(blob) {" +

                "    const url = originalCreateObjectURL(blob);" +

                "    try {" +

                "        if (blob && " +
                "(blob.type === 'application/pdf' || " +
                "blob.type === 'application/octet-stream')) {" +

                "            const reader = new FileReader();" +

                "            reader.onloadend = function() {" +

                "                try {" +

                "                    const result = reader.result;" +

                "                    const comma = result.indexOf(',');" +

                "                    const base64 = " +
                "result.substring(comma + 1);" +

                "                    AndroidDownload.saveBlob(" +
                "base64," +
                "'document.pdf'," +
                "'application/pdf'" +
                ");" +

                "                } catch (e) {" +
                "                    console.error(e);" +
                "                }" +

                "            };" +

                "            reader.readAsDataURL(blob);" +

                "        }" +

                "    } catch (e) {" +
                "        console.error(e);" +
                "    }" +

                "    return url;" +
                "};" +

                "})();";

        view.evaluateJavascript(js, null);
    }

    /*
     * NATIVE CAMERA
     * Captured photo is returned to the existing web photoInput,
     * so the existing crop/edit flow remains unchanged.
     */
    private class CameraInterface {

        @JavascriptInterface
        public void openCamera() {

            runOnUiThread(() -> {

                try {

                    File cameraFile = new File(
                            getExternalFilesDir(
                                    Environment.DIRECTORY_DOWNLOADS
                            ),
                            "leavepdf-camera.jpg"
                    );

                    cameraImageUri = FileProvider.getUriForFile(
                            MainActivity.this,
                            getPackageName() + ".fileprovider",
                            cameraFile
                    );

                    Intent intent =
                            new Intent(
                                    MediaStore.ACTION_IMAGE_CAPTURE
                            );

                    intent.putExtra(
                            MediaStore.EXTRA_OUTPUT,
                            cameraImageUri
                    );

                    intent.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );

                    intent.addFlags(
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );

                    startActivityForResult(
                            intent,
                            CAMERA_REQUEST
                    );

                } catch (Exception e) {

                    e.printStackTrace();

                    Toast.makeText(
                            MainActivity.this,
                            "Camera open failed",
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        }
    }

    /*
     * Receives the PDF Blob from JavaScript.
     */
    private class BlobDownloadInterface {

        @JavascriptInterface
        public void saveBlob(
                String base64Data,
                String fileName,
                String mimeType) {

            runOnUiThread(() -> {

                try {

                    byte[] pdfData;

                    if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.O) {

                        pdfData =
                                Base64.getDecoder()
                                        .decode(base64Data);

                    } else {

                        pdfData =
                                android.util.Base64.decode(
                                        base64Data,
                                        android.util.Base64.DEFAULT
                                );
                    }

                    ContentResolver resolver =
                            getContentResolver();

                    ContentValues values =
                            new ContentValues();

                    String uniqueFileName =
                            fileName.replaceFirst(
                                    "(?i)(\\.pdf)$",
                                    "_" + System.currentTimeMillis() + "$1"
                            );

                    values.put(
                            MediaStore.Downloads.DISPLAY_NAME,
                            uniqueFileName
                    );

                    values.put(
                            MediaStore.Downloads.MIME_TYPE,
                            mimeType
                    );

                    if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.Q) {

                        values.put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS
                        );

                        values.put(
                                MediaStore.Downloads.IS_PENDING,
                                1
                        );
                    }

                    Uri collection =
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI;

                    Uri fileUri =
                            resolver.insert(
                                    collection,
                                    values
                            );

                    if (fileUri == null) {
                        throw new Exception(
                                "Could not create Download file"
                        );
                    }

                    OutputStream output =
                            resolver.openOutputStream(
                                    fileUri
                            );

                    if (output == null) {
                        throw new Exception(
                                "Could not open Download file"
                        );
                    }

                    output.write(pdfData);
                    output.flush();
                    output.close();

                    if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.Q) {

                        ContentValues complete =
                                new ContentValues();

                        complete.put(
                                MediaStore.Downloads.IS_PENDING,
                                0
                        );

                        resolver.update(
                                fileUri,
                                complete,
                                null,
                                null
                        );
                    }

                    Toast.makeText(
                            MainActivity.this,
                            "PDF saved to Downloads",
                            Toast.LENGTH_LONG
                    ).show();

                } catch (Exception e) {

                    e.printStackTrace();

                    Toast.makeText(
                            MainActivity.this,
                            "PDF save failed: " +
                                    e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        }
    }

    /*
     * FILE CHOOSER RESULT
     */
    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == CAMERA_REQUEST) {

            if (resultCode == RESULT_OK && cameraImageUri != null) {
                sendCameraPhotoToWebView(cameraImageUri);
            }

            cameraImageUri = null;
            return;
        }

        if (requestCode == FILE_CHOOSER_REQUEST) {

            Uri[] results = null;

            if (resultCode == RESULT_OK && data != null) {

                if (data.getClipData() != null) {

                    int count =
                            data.getClipData()
                                    .getItemCount();

                    results = new Uri[count];

                    for (int i = 0; i < count; i++) {

                        results[i] =
                                data.getClipData()
                                        .getItemAt(i)
                                        .getUri();
                    }

                } else if (data.getData() != null) {

                    results = new Uri[]{
                            data.getData()
                    };
                }
            }

            if (filePathCallback != null) {

                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    private void sendCameraPhotoToWebView(Uri uri) {

        new Thread(() -> {

            try {

                InputStream input =
                        getContentResolver().openInputStream(uri);

                if (input == null) {
                    throw new Exception(
                            "Could not read camera image"
                    );
                }

                ByteArrayOutputStream output =
                        new ByteArrayOutputStream();

                byte[] buffer = new byte[8192];
                int length;

                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }

                input.close();

                String base64 =
                        android.util.Base64.encodeToString(
                                output.toByteArray(),
                                android.util.Base64.NO_WRAP
                        );

                runOnUiThread(() -> {

                    String js =
                            "(function(dataUrl){" +
                            "var input=document.getElementById('photoInput');" +
                            "if(!input){return;}" +
                            "fetch(dataUrl)" +
                            ".then(function(r){return r.blob();})" +
                            ".then(function(blob){" +
                            "var file=new File([blob]," +
                            "'camera-photo.jpg'," +
                            "{type:'image/jpeg'});" +
                            "var dt=new DataTransfer();" +
                            "dt.items.add(file);" +
                            "input.files=dt.files;" +
                            "input.dispatchEvent(" +
                            "new Event('change',{bubbles:true})" +
                            ");" +
                            "});" +
                            "})('data:image/jpeg;base64," +
                            base64 +
                            "');";

                    webView.evaluateJavascript(
                            js,
                            null
                    );
                });

            } catch (Exception e) {

                e.printStackTrace();

                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Camera photo could not be loaded",
                                Toast.LENGTH_LONG
                        ).show()
                );
            }

        }).start();
    }

    /*
     * BACK BUTTON
     */
    @Override
    public void onBackPressed() {

        if (webView != null &&
                webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }
}
