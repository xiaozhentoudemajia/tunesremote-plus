package org.tunesremote;

import android.app.Activity;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

public class WifiSettingActivity extends Activity {
    public final static String TAG = WifiSettingActivity.class.toString();
    Button setDongle;
    TextView wifi_status;
    WebView dongleSetting;
    WifiManager wifiManager;
    WifiInfo wifiInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.wifi_setting);

        dongleSetting = (WebView) findViewById(R.id.wifi_setting_page);
        setDongle = (Button) findViewById(R.id.wifi_setting_button);
        wifi_status = (TextView) findViewById(R.id.wifi_status);

        dongleSetting.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        WebSettings settings = dongleSetting.getSettings();
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);


        dongleSetting.loadUrl("http://www.zhihu.com");
        setDongle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "dongleSetting load Url");
                //dongleSetting.loadUrl("http://www.chiphell.com");
                dongleSetting.loadUrl("http://192.168.111.1/index.html");
            }
        });

        wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        wifiInfo = wifiManager.getConnectionInfo();
        wifi_status.setText(wifiInfo.getSSID());
    }
}
