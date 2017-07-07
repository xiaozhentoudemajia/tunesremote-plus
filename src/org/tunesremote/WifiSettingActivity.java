package org.tunesremote;

import android.app.Activity;
import android.content.Intent;
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
    TextView wifiStatus;
    WebView dongleSetting;
    WifiManager wifiManager;
    WifiInfo wifiInfo;
    String selectWifi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.wifi_setting);

        Bundle bundle = this.getIntent().getExtras();
        selectWifi = bundle.getString(this.getString(R.string.ssid_select));

        dongleSetting = (WebView) findViewById(R.id.wifi_setting_page);
        setDongle = (Button) findViewById(R.id.wifi_setting_button);
        wifiStatus = (TextView) findViewById(R.id.wifi_status);

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
        settings.setJavaScriptEnabled(true);


        dongleSetting.loadUrl("http://www.zhihu.com");
        setDongle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d(TAG, "dongleSetting load Url");
                dongleSetting.loadUrl("http://192.168.111.1/index.html");
            }
        });

        wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        wifiInfo = wifiManager.getConnectionInfo();
        wifiStatus.setText("当前:" + wifiInfo.getSSID() + " 选择:" + selectWifi);
    }
}
