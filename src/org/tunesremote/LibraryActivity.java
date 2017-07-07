/*
    TunesRemote+ - http://code.google.com/p/tunesremote-plus/

    Copyright (C) 2008 Jeffrey Sharkey, http://jsharkey.org/
    Copyright (C) 2010 TunesRemote+, http://code.google.com/p/tunesremote-plus/

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

    The Initial Developer of the Original Code is Jeffrey Sharkey.
    Portions created by Jeffrey Sharkey are
    Copyright (C) 2008. Jeffrey Sharkey, http://jsharkey.org/
    All Rights Reserved.
 */

package org.tunesremote;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import org.tunesremote.daap.Response;
import org.tunesremote.daap.Session;
import org.tunesremote.daap.Speaker;
import org.tunesremote.daap.Status;
import org.tunesremote.util.ClickSpan;
import org.tunesremote.util.ThreadExecutor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LevelListDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RatingBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Show a list of all libraries found on local wifi network. Should have refresh
 * button easly accessibly, and also detect wifi issues.
 */
public class LibraryActivity extends Activity implements ServiceListener, ClickSpan.OnClickListener {

   public final static String TAG = LibraryActivity.class.toString();
   public final static String TOUCH_ABLE_TYPE = "_touch-able._tcp.local.";
   public final static String DACP_TYPE = "_dacp._tcp.local.";
   public final static String REMOTE_TYPE = "_touch-remote._tcp.local.";
   public final static String HOSTNAME = "tunesremote";

   protected static Session session;
   protected static Status status;

   private static JmDNS zeroConf = null;
   private static MulticastLock mLock = null;
   private BackendService backend;
   private static final int DIALOG_SPEAKERS = 1;
   public final static long CACHE_TIME = 10000;
   protected long cachedVolume = -1;
   protected long cachedTime = -1;
   protected LevelListDrawable shuffle, repeat, play;

   /**
    * Instance of the speaker list adapter used in the speakers dialog
    */
   protected SpeakersAdapter speakersAdapter;
   protected final static List<Speaker> SPEAKERS = new ArrayList<Speaker>();

   public ServiceConnection connection = new ServiceConnection() {
      public void onServiceConnected(ComponentName className, final IBinder service) {
         ThreadExecutor.runTask(new Runnable() {

            public void run() {
               backend = ((BackendService.BackendBinder) service).getService();

               session = backend.getSession();
               if (session != null) {
                  // for some reason we are not correctly disposing of
                  // the session threads we create so we purge any
                  // existing ones before creating a new one
                  status = session.singletonStatus(statusUpdate);
                  status.updateHandler(statusUpdate);

                  // push update through to make sure we get updated
                  statusUpdate.sendEmptyMessage(Status.UPDATE_SPEAKERS);
                  statusUpdate.sendEmptyMessage(Status.UPDATE_TRACK);
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
         // update gui based on severity
         switch (msg.what) {
            case Status.UPDATE_TRACK:

            case Status.UPDATE_COVER:

            case Status.UPDATE_STATE:

            case Status.UPDATE_PROGRESS:
               break;

            // This one is triggered by a thread, so should not be used to
            // update progress, etc...
            case Status.UPDATE_RATING:
               break;
            case Status.UPDATE_SPEAKERS:
               ThreadExecutor.runTask(new Runnable() {
                  public void run() {
                     try {
                        if (status == null) {
                           return;
                        }
                        status.getSpeakers(SPEAKERS);
                     } catch (Exception e) {
                        Log.e(TAG, "Speaker Exception:" + e.getMessage());
                     }
                  }

               });
               break;
         }

         //checkShuffle();
         //checkRepeat();
      }
   };

   // this screen will run a network query of all libraries
   // upon selection it will try authenticating with that library, and launch
   // the pairing activity if failed
   protected void startProbe() throws Exception {

      if (zeroConf != null)
         LibraryActivity.this.stopProbe();

      runOnUiThread(new Runnable() {

         public void run() {
            adapter.known.clear();
            adapter.notifyDataSetChanged();
         }

      });
      // figure out our wifi address, otherwise bail
      WifiManager wifi = (WifiManager) LibraryActivity.this.getSystemService(Context.WIFI_SERVICE);

      WifiInfo wifiinfo = wifi.getConnectionInfo();
      int intaddr = wifiinfo.getIpAddress();

      if (intaddr != 0) { // Only worth doing if there's an actual wifi
         // connection

         byte[] byteaddr = new byte[] { (byte) (intaddr & 0xff), (byte) (intaddr >> 8 & 0xff),
                  (byte) (intaddr >> 16 & 0xff), (byte) (intaddr >> 24 & 0xff) };
         InetAddress addr = InetAddress.getByAddress(byteaddr);

         Log.d(TAG, String.format("found intaddr=%d, addr=%s", intaddr, addr.toString()));
         // start multicast lock
         mLock = wifi.createMulticastLock("TunesRemote lock");
         mLock.setReferenceCounted(true);
         mLock.acquire();

         zeroConf = JmDNS.create(addr, HOSTNAME);
         zeroConf.addServiceListener(TOUCH_ABLE_TYPE, LibraryActivity.this);
         zeroConf.addServiceListener(DACP_TYPE, LibraryActivity.this);

      } else
         checkWifiState();

   }

   protected void stopProbe() {
      zeroConf.removeServiceListener(TOUCH_ABLE_TYPE, this);
      zeroConf.removeServiceListener(DACP_TYPE, this);

      ThreadExecutor.runTask(new Runnable() {

         public void run() {
            try {
               zeroConf.close();
               zeroConf = null;
            } catch (IOException e) {
               Log.d(TAG, String.format("ZeroConf Error: %s", e.getMessage()));
            }
         }
      });

      mLock.release();
      mLock = null;
   }

   public static JmDNS getZeroConf() {
      return zeroConf;
   }

   public void serviceAdded(ServiceEvent event) {
      // someone is yelling about their touch-able service
      // go figure out what their ip address is
      Log.w(TAG, String.format("serviceAdded(event=\n%s\n)", event.toString()));
      final String name = event.getName();

      // trigger delayed gui event
      // needs to be delayed because jmdns hasnt parsed txt info yet
      resultsUpdated.sendMessageDelayed(Message.obtain(resultsUpdated, -1, name), DELAY);

   }

   public void serviceRemoved(ServiceEvent event) {
      Log.w(TAG, String.format("serviceRemoved(event=\n%s\n)", event.toString()));
   }

   public void serviceResolved(ServiceEvent event) {
      Log.w(TAG, String.format("serviceResolved(event=\n%s\n)", event.toString()));
   }

   public final static int DONE = 3;

   public Handler resultsUpdated = new Handler() {
      @Override
      public void handleMessage(Message msg) {
         if (msg.obj != null) {
            boolean result = adapter.notifyFound((String) msg.obj);

            // only update UI if a new one was added
            if (result) {
               adapter.notifyDataSetChanged();
            }
         }
      }
   };

   public final static int DELAY = 500;

   protected ListView list;
   protected LibraryAdapter adapter;

   @Override
   public void onResume() {
      super.onResume();
      try {
         checkWifiState();
      } catch (NullPointerException npe) {
         npe.printStackTrace();
      }
   }

   @Override
   public void onStart() {
      super.onStart();
      this.bindService(new Intent(this, BackendService.class), connection, Context.BIND_AUTO_CREATE);
      if (this.adapter != null) {
         this.adapter.known.clear();
      }
   }

   @Override
   public void onStop() {
      super.onStop();
      this.unbindService(connection);

   }

   @Override
   protected void onActivityResult(int requestCode, int resultCode, Intent data) {
      // someone thinks they are ready to pair with us
      if (resultCode == Activity.RESULT_CANCELED)
         return;

      final String address = data.getStringExtra(BackendService.EXTRA_ADDRESS);
      final String library = data.getStringExtra(BackendService.EXTRA_LIBRARY);
      final String code = data.getStringExtra(BackendService.EXTRA_CODE);
      Log.d(TAG, String.format("onActivityResult with address=%s, library=%s, code=%s and resultcode=%d", address,
               library, code, resultCode));

      ThreadExecutor.runTask(new Runnable() {

         public void run() {
            try {

               // check to see if we can actually authenticate against the
               // library
               backend.setLibrary(address, library, code);

               // if successful, then throw back to controlactivity
               // LibraryActivity.this.startActivity(new
               // Intent(LibraryActivity.this,
               // ControlActivity.class));
               LibraryActivity.this.setResult(Activity.RESULT_OK);
               LibraryActivity.this.finish();

            } catch (final ConnectException ce) {

               Log.e(TAG, String.format("ohhai we had problemz, probably still unpaired"), ce);
               LibraryActivity.this.runOnUiThread(new Runnable() {
                  public void run() {
                     Toast.makeText(
                              LibraryActivity.this,
                              "Connection error:"
                                       + (ce.getMessage().contains("ECONNREFUSED") ? " Connection refused" : ""),
                              Toast.LENGTH_LONG).show();
                  }
               });

            } catch (Exception e) {

               Log.e(TAG, String.format("ohhai we had problemz, probably still unpaired"), e);

               // we probably had a pairing issue, so start the pairing server
               // and
               // wait for trigger
               Intent intent = new Intent(LibraryActivity.this, PairingActivity.class);
               intent.putExtra(BackendService.EXTRA_ADDRESS, address);
               intent.putExtra(BackendService.EXTRA_LIBRARY, library);
               LibraryActivity.this.startActivityForResult(intent, 1);

            }
         }

      });

   }

   /**
    * Gets the current wifi state, and changes the text shown in the header as
    * required.
    */
   public void checkWifiState() {

      WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
      int intaddr = wifi.getConnectionInfo().getIpAddress();

      View header = adapter.footerView;
      if (!header.equals(adapter.footerView))
         Log.e(TAG, "Header is wrong");
      else {
         TextView title = (TextView) header.findViewById(android.R.id.text1);
         TextView explanation = (TextView) header.findViewById(android.R.id.text2);
         ProgressBar progress = (ProgressBar) header.findViewById(R.id.progress);

         if (wifi.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {

            // Wifi is disabled
            title.setText(R.string.wifi_disabled_title);
            explanation.setText(R.string.wifi_disabled);
            ClickSpan.clickify(explanation, "wifi in Settings", this);
            progress.setVisibility(View.GONE);

         } else if (intaddr == 0) {

            // Wifi is enabled, but no network connection
            title.setText(R.string.no_network_title);
            explanation.setText(R.string.no_network);
            ClickSpan.clickify(explanation, "wifi settings", this);
            progress.setVisibility(View.VISIBLE);
         } else {

            // Wifi is enabled and there's a network
            title.setText(R.string.item_network_title);
            explanation.setText(R.string.item_network_caption);
            progress.setVisibility(View.VISIBLE);

         }

      }

   }

   /**
    * Will set the state of the header view depending on Wifi state
    * @param state
    */
   public void setState(int state) {

      View header = list.getChildAt(0);
      if (!header.equals(adapter.footerView))
         Log.e(TAG, "Header is wrong");
      else {
         TextView title = (TextView) header.findViewById(android.R.id.text1);
         TextView explanation = (TextView) header.findViewById(android.R.id.text2);
         ProgressBar progress = (ProgressBar) header.findViewById(R.id.progress);

         switch (state) {

         // Wifi is disabled, hide spinner, show error, linkify wifi in settings
         case 0:
            title.setText(R.string.wifi_disabled_title);
            explanation.setText(R.string.wifi_disabled);
            ClickSpan.clickify(explanation, "wifi in Settings", this);
            progress.setVisibility(View.GONE);
            break;

         // Wifi is enabled but we're not connected to a network
         case 1:
            title.setText(R.string.no_network_title);
            explanation.setText(R.string.no_network);
            ClickSpan.clickify(explanation, "wifi settings", this);
            progress.setVisibility(View.VISIBLE);
            break;

         // Wifi is enabled and we're connected - show the standard text
         case 2:
            title.setText(R.string.item_network_title);
            explanation.setText(R.string.item_network_caption);
            progress.setVisibility(View.VISIBLE);
            break;

         }

      }

   }

   // there should be a single backend service that holds current session
   // information
   @Override
   public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      setContentView(R.layout.gen_list);

      this.adapter = new LibraryAdapter(this);

      this.list = (ListView) this.findViewById(android.R.id.list);
      this.list.addHeaderView(adapter.footerView, null, false);
      this.list.setAdapter(adapter);

      this.list.setOnItemClickListener(new OnItemClickListener() {
         public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            // read ip/port from caption if present
            // pass off to backend to try creating pairing session

            if (backend == null)
               return;

            String caption = ((TextView) view.findViewById(android.R.id.text2)).getText().toString();
            String[] split = caption.split("-");
            if (split.length < 2)
               return;

            String address = split[0].trim();

            // Use the library name
            String library = ((TextView) view.findViewById(android.R.id.text1)).getText().toString();

            // push off fake result to try login
            // this will start the pairing process if needed
            Intent shell = new Intent();
            shell.putExtra(BackendService.EXTRA_ADDRESS, address);
            shell.putExtra(BackendService.EXTRA_LIBRARY, library);
            onActivityResult(-1, Activity.RESULT_OK, shell);
            startActivity(new Intent(LibraryActivity.this, LibraryBrowseActivity.class));
         }
      });

      registerForContextMenu(list);

      ThreadExecutor.runTask(new Runnable() {

         public void run() {
            try {
               LibraryActivity.this.startProbe();
            } catch (Exception e) {
               Log.d(TAG, String.format("onCreate Error: %s", e.getMessage()));
            }
         }
      });

      // Speakers adapter needed for the speakers dialog
      speakersAdapter = new SpeakersAdapter(this);

   }

   public class SpeakersAdapter extends BaseAdapter {

      private final LayoutInflater inflater;

      public SpeakersAdapter(Context context) {
         inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
      }

      public int getCount() {
         if (SPEAKERS == null) {
            return 0;
         }
         return SPEAKERS.size();
      }

      public Object getItem(int position) {
         return SPEAKERS.get(position);
      }

      public long getItemId(int position) {
         return position;
      }

      /**
       * Toggles activation of a given speaker and refreshes the view
       * @param active Flag indicating, whether the speaker shall be activated
       * @param speaker the speaker to be activated or deactivated
       */
      public void setSpeakerActive(boolean active, final Speaker speaker) {
         if (speaker == null) {
            return;
         }
         if (status == null) {
            return;
         }
         speaker.setActive(active);

         ThreadExecutor.runTask(new Runnable() {
            public void run() {
               try {
                  status.setSpeakers(SPEAKERS);
               } catch (Exception e) {
                  Log.e(TAG, "Speaker Exception:" + e.getMessage());
               }
            }

         });

         notifyDataSetChanged();
      }

      public View getView(int position, View convertView, ViewGroup parent) {
         try {

            View row;
            if (null == convertView) {
               row = inflater.inflate(R.layout.item_speaker, null);
            } else {
               row = convertView;
            }

            /*************************************************************
             * Find the necessary sub views
             *************************************************************/
            TextView nameTextview = (TextView) row.findViewById(R.id.speakerNameTextView);
            TextView speakerTypeTextView = (TextView) row.findViewById(R.id.speakerTypeTextView);
            final CheckBox activeCheckBox = (CheckBox) row.findViewById(R.id.speakerActiveCheckBox);
            SeekBar volumeBar = (SeekBar) row.findViewById(R.id.speakerVolumeBar);

            /*************************************************************
             * Set view properties
             *************************************************************/
            final Speaker speaker = SPEAKERS.get(position);
            nameTextview.setText(speaker.getName());
            speakerTypeTextView.setText(speaker.isLocalSpeaker() ? R.string.speakers_dialog_computer_speaker
                    : R.string.speakers_dialog_airport_express);
            activeCheckBox.setChecked(speaker.isActive());
            activeCheckBox.setOnClickListener(new View.OnClickListener() {

               public void onClick(View v) {
                  setSpeakerActive(activeCheckBox.isChecked(), speaker);
               }
            });
            nameTextview.setOnClickListener(new View.OnClickListener() {

               public void onClick(View v) {
                  activeCheckBox.toggle();
                  setSpeakerActive(activeCheckBox.isChecked(), speaker);
               }
            });
            speakerTypeTextView.setOnClickListener(new View.OnClickListener() {

               public void onClick(View v) {
                  activeCheckBox.toggle();
                  setSpeakerActive(activeCheckBox.isChecked(), speaker);
               }
            });
            // If the speaker is active, enable the volume bar
            if (speaker.isActive()) {
               volumeBar.setEnabled(true);
               volumeBar.setProgress(speaker.getAbsoluteVolume());
               volumeBar.setOnSeekBarChangeListener(new VolumeSeekBarListener(speaker));
            } else {
               volumeBar.setEnabled(false);
               volumeBar.setProgress(0);
            }
            return row;
         } catch (RuntimeException e) {
            Log.e(TAG, "Error when rendering speaker item: ", e);
            throw e;
         }
      }
   }


   @Override
   protected Dialog onCreateDialog(int id) {
      if (id == DIALOG_SPEAKERS) {
         // Create the speakers dialog (once)
         return new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_lock_silent_mode_off)
                 .setTitle(R.string.control_menu_speakers).setAdapter(speakersAdapter, null)
                 .setPositiveButton("OK", null).create();
      }
      return null;
   }

   @Override
   public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
      super.onCreateContextMenu(menu, v, menuInfo);

      MenuInflater menuInflater = getMenuInflater();
      menuInflater.inflate(R.menu.group_menu, menu);
   }

   @Override
   public boolean onContextItemSelected(MenuItem item) {
      switch (item.getItemId())
      {
         case R.id.group_menu_rename_item:
            Toast.makeText(LibraryActivity.this, "Rename Function...", Toast.LENGTH_SHORT).show();
            break;
         case R.id.group_menu_speaker_item:
            Toast.makeText(LibraryActivity.this, "Speaker Function...", Toast.LENGTH_SHORT).show();
            showDialog(DIALOG_SPEAKERS);
            break;
      }

      return super.onContextItemSelected(item);
   }

   @Override
   public boolean onCreateOptionsMenu(Menu menu) {
      getMenuInflater().inflate(R.menu.act_library, menu);
      return true;
   }

   @Override
   public boolean onOptionsItemSelected(MenuItem item) {

      switch (item.getItemId()) {

      case R.id.menu_library_refresh:
         ThreadExecutor.runTask(new Runnable() {

            public void run() {
               try {
                  startProbe();
               } catch (Exception e) {
                  Log.d(TAG, String.format("onCreate Error: %s", e.getMessage()));
               }
            }

         });
         return true;

      case R.id.menu_library_manual:
         LayoutInflater inflater = (LayoutInflater) LibraryActivity.this
                  .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
         final View view = inflater.inflate(R.layout.dia_text, null);
         final TextView address = (TextView) view.findViewById(android.R.id.text1);
         final TextView code = (TextView) view.findViewById(android.R.id.text2);
         code.setText("0000000000000001");

         new AlertDialog.Builder(LibraryActivity.this).setView(view)
                  .setPositiveButton(R.string.library_manual_pos, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int which) {
                        // try connecting to this specific ip address
                        Intent shell = new Intent();
                        shell.putExtra(BackendService.EXTRA_ADDRESS, address.getText().toString());
                        shell.putExtra(BackendService.EXTRA_LIBRARY, "Manual");
                        shell.putExtra(BackendService.EXTRA_CODE, code.getText().toString());
                        onActivityResult(-1, Activity.RESULT_OK, shell);

                     }
                  }).setNegativeButton(R.string.library_manual_neg, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int which) {
                     }
                  }).create().show();
         return true;

      case R.id.menu_library_forget:
         new AlertDialog.Builder(LibraryActivity.this).setMessage(R.string.library_forget)
                  .setPositiveButton(R.string.library_forget_pos, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int which) {
                        backend.pairdb.deleteAll();

                     }
                  }).setNegativeButton(R.string.library_forget_neg, new DialogInterface.OnClickListener() {
                     public void onClick(DialogInterface dialog, int which) {
                     }
                  }).create().show();
         return true;

         case R.id.menu_add_device:
            startActivity(new Intent(this, WifiListActivity.class));
            return true;

      }
      return super.onOptionsItemSelected(item);

   }

   /**
    * OnSeekBarChangeListener that controls the volume for a certain speaker
    * @author Daniel Thommes
    */
   public class VolumeSeekBarListener implements SeekBar.OnSeekBarChangeListener {
      private final Speaker speaker;

      public VolumeSeekBarListener(Speaker speaker) {
         this.speaker = speaker;
      }

      public void onStopTrackingTouch(SeekBar seekBar) {
         final int newVolume = seekBar.getProgress();
         ThreadExecutor.runTask(new Runnable() {
            public void run() {
               try {
                  // Volume of the loudest speaker
                  int maxVolume = 0;
                  // Volume of the second loudest speaker
                  int secondMaxVolume = 0;
                  for (Speaker speaker : SPEAKERS) {
                     if (speaker.getAbsoluteVolume() > maxVolume) {
                        secondMaxVolume = maxVolume;
                        maxVolume = speaker.getAbsoluteVolume();
                     } else if (speaker.getAbsoluteVolume() > secondMaxVolume) {
                        secondMaxVolume = speaker.getAbsoluteVolume();
                     }
                  }
                  // fetch the master volume if necessary
                  checkCachedVolume();
                  int formerVolume = speaker.getAbsoluteVolume();
                  status.setSpeakerVolume(speaker.getId(), newVolume, formerVolume, maxVolume, secondMaxVolume,
                          cachedVolume);
                  speaker.setAbsoluteVolume(newVolume);
               } catch (Exception e) {
                  Log.e(TAG, "Speaker Exception:" + e.getMessage());
               }
            }

         });
      }

      public void onStartTrackingTouch(SeekBar seekBar) {
      }

      public void onProgressChanged(SeekBar seekBar, int newVolume, boolean fromUser) {
      }
   }

   /**
    * Updates the cachedVolume if necessary
    */
   protected void checkCachedVolume() {
      // try assuming a cached volume instead of requesting it each time
      if (System.currentTimeMillis() - cachedTime > CACHE_TIME) {
         if (status == null) {
            return;
         }
         this.cachedVolume = status.getVolume();
         this.cachedTime = System.currentTimeMillis();
      }
   }

   public class LibraryAdapter extends BaseAdapter {

      protected Context context;
      protected LayoutInflater inflater;
      public View footerView;
      protected final LinkedList<ServiceInfo> known = new LinkedList<ServiceInfo>();

      public LibraryAdapter(Context context) {
         this.context = context;
         this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
         this.footerView = inflater.inflate(R.layout.item_network, null, false);
      }

      public boolean notifyFound(String library) {
         boolean result = false;
         try {
            Log.d(TAG, String.format("DNS Name: %s", library));
            ServiceInfo serviceInfo = getZeroConf().getServiceInfo(TOUCH_ABLE_TYPE, library);

            // try and get the DACP type only if we cannot find any touchable
            if (serviceInfo == null) {
               serviceInfo = getZeroConf().getServiceInfo(DACP_TYPE, library);
            }

            if (serviceInfo == null) {
               return result; // nothing to add since serviceInfo is NULL
            }

            String libraryName = serviceInfo.getPropertyString("CtlN");
            if (libraryName == null) {
               libraryName = serviceInfo.getName();
            }

            // check if we already have this DatabaseId
            for (ServiceInfo service : known) {
               String knownName = service.getPropertyString("CtlN");
               if (knownName == null) {
                  knownName = service.getName();
               }
               if (libraryName.equalsIgnoreCase(knownName)) {
                  Log.w(TAG, "Already have DatabaseId loaded = " + libraryName);
                  return result;
               }
            }

            if (!known.contains(serviceInfo)) {
               known.add(serviceInfo);
               result = true;
            }
         } catch (Exception e) {
            Log.d(TAG, String.format("Problem getting ZeroConf information %s", e.getMessage()));
         }

         return result;
      }

      public Object getItem(int position) {
         return known.get(position);
      }

      @Override
      public boolean hasStableIds() {
         return true;
      }

      public int getCount() {
         return known.size();
      }

      public long getItemId(int position) {
         return position;
      }

      public View getView(int position, View convertView, ViewGroup parent) {

         if (convertView == null)
            convertView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);

         try {
            // fetch the dns txt record to get library info
            final ServiceInfo serviceInfo = (ServiceInfo) this.getItem(position);

            String title = serviceInfo.getPropertyString("CtlN");
            if (title == null) {
               title = serviceInfo.getName();
            }
            final String addr = serviceInfo.getHostAddresses()[0]; // grab first
            // one
            final String library = String.format("%s - %s", addr, serviceInfo.getPropertyString("DbId"));

            Log.d(TAG, String.format("ZeroConf Server: %s", serviceInfo.getServer()));
            Log.d(TAG, String.format("ZeroConf Port: %s", serviceInfo.getPort()));
            Log.d(TAG, String.format("ZeroConf Title: %s", title));
            Log.d(TAG, String.format("ZeroConf Library: %s", library));

            ((TextView) convertView.findViewById(android.R.id.text1)).setText(title);
            ((TextView) convertView.findViewById(android.R.id.text2)).setText(library);

         } catch (Exception e) {
            Log.d(TAG, String.format("Problem getting ZeroConf information %s", e.getMessage()));
            ((TextView) convertView.findViewById(android.R.id.text1)).setText("Unknown");
            ((TextView) convertView.findViewById(android.R.id.text2)).setText("Unknown");
         }

         return convertView;
      }

   }

   @Override
   public void onClick() {
      startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
   }

}
