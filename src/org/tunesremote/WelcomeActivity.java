package org.tunesremote;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.ImageView;

import org.tunesremote.daap.Session;
import org.tunesremote.daap.Status;
import org.tunesremote.util.NotificationService;
import org.tunesremote.util.ThreadExecutor;


public class WelcomeActivity extends Activity {
    public final static String TAG = WelcomeActivity.class.toString();
    protected static ImageView welcome;
    protected static BackendService backend;
    protected static Session session;
    protected static Status status;
    protected boolean agreed = false;
    public final static String EULA = "eula";
    protected SharedPreferences prefs;

    public ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, final IBinder service) {

            ThreadExecutor.runTask(new Runnable() {

                public void run() {
                    try {
                        backend = ((BackendService.BackendBinder) service).getService();
                        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(WelcomeActivity.this);
                        backend.setPrefs(settings);

                        if (!agreed)
                            return;

                        Log.w(TAG, "onServiceConnected");

                        session = backend.getSession();
                        if (session == null) {
                            // we could not connect with library, so launch
                            // picker
                            Log.w(TAG, "we could not connect with library, so launch picker");
                            WelcomeActivity.this.startActivityForResult(
                                    new Intent(WelcomeActivity.this, LibraryActivity.class), 1);

                        } else {
                            // for some reason we are not correctly disposing of
                            // the session threads we create so we purge any
                            // existing ones before creating a new one
                            status = session.singletonStatus(statusUpdate);
                            status.updateHandler(statusUpdate);

                            // push update through to make sure we get updated
                            statusUpdate.sendEmptyMessage(Status.UPDATE_SPEAKERS);
                            statusUpdate.sendEmptyMessage(Status.UPDATE_TRACK);

                            if (settings.getBoolean("notification", false))
                                WelcomeActivity.this.startService(new Intent(WelcomeActivity.this, NotificationService.class));
                            startActivity(new Intent(WelcomeActivity.this, LibraryActivity.class));

                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "onServiceConnected:" + e);
                    }
                }
            });

        }

        public void onServiceDisconnected(ComponentName className) {
            // make sure we clean up our handler-specific status
            Log.w(TAG, "onServiceDisconnected");
            status.updateHandler(null);
            backend = null;
            status = null;
        }
    };

    protected Handler statusUpdate = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            Log.w(TAG, "handleMessage:"+msg.toString());
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.prefs = PreferenceManager.getDefaultSharedPreferences(this);
        this.agreed = prefs.getBoolean(EULA, false);

        if (!this.agreed) {
            // show eula wizard
            this.startActivityForResult(new Intent(this, WizardActivity.class), 1);
        }

        setContentView(R.layout.act_welcome);
        Log.d(TAG, "WelcomeActivity !!!");
        welcome = (ImageView) findViewById(R.id.welcome_page);
        welcome.setFocusable(true);
        welcome.setClickable(true);
    }

    @Override
    protected void onStart() {
        super.onStart();

        Intent service = new Intent(this, BackendService.class);

        // regardless of stayConnected, were always going to need a bound
        // backend for this activity
        this.bindService(service, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.w(TAG, "Stopping TunesRemote...");
        try {
            if (session != null) {
                session.purgeSingletonStatus();
            }

            this.unbindService(connection);
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "Destroying TunesRemote...");
        try {
            if (session != null) {
                session.purgeSingletonStatus();
                session.logout();
                session = null;
            }
            backend = null;
        } catch (Exception ex) {
            Log.e(TAG, ex.getMessage(), ex);
        }
        Log.w(TAG, "Destroyed TunesRemote!");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            // yay they agreed, so store that info
            SharedPreferences.Editor edit = prefs.edit();
            edit.putBoolean(EULA, true);
            edit.commit();
            this.agreed = true;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            startActivity(new Intent(WelcomeActivity.this, LibraryActivity.class));
        } else {
            // user didnt agree, so close
            this.finish();
        }
    }
}
