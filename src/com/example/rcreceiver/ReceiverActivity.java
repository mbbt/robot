/**
 * License: Apache License 2.0 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 2013, Bearstech, Marcus Bauer
 */

package com.example.rcreceiver;



import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class ReceiverActivity extends Activity {

	

	private static final String TAG = "ReceiverActivity";
	private static final boolean DEBUG = true;
	// Intent request codes
	private static final int REQUEST_CONNECT_DEVICE = 1;
	private static final int REQUEST_ENABLE_BT = 2;
        

	Messenger serviceOutMessenger = null;
	boolean serviceIsBound;
	TextView statusTextView;
	final Messenger serviceInMessenger = new Messenger(new ServiceMsgHandler());
	int testInt = 4711;


	private String mBluetoothDeviceName = null;
	private BluetoothAdapter mBluetoothAdapter = null;
	TextView textViewBtrecv;


	TextView textViewMyIP;
	TextView textViewRemoteIP;
	

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_receiver);
		statusTextView = (TextView) findViewById(R.id.textView8);
		textViewBtrecv = (TextView) findViewById(R.id.textView7);
		textViewMyIP = (TextView) findViewById(R.id.textView9);
		textViewRemoteIP = (TextView) findViewById(R.id.textView3);
		
		// get bluetooth adapter
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		// bluetooth not supported
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
			finish();
			return;
		}

	}
	
	@Override
	public void onStart() {
		super.onStart();

		// if necessary request bluetooth to be enabled
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
		} else {
		}
	}

	@Override
	public synchronized void onResume() {
		super.onResume();
		if(DEBUG) Log.e(TAG, "+ ON RESUME() +");


	}
	
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case REQUEST_CONNECT_DEVICE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				Log.d(TAG,"Menu connect request incoming, going to connectDevice()");
				String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
				Toast.makeText(this, "Should connect to "+address, Toast.LENGTH_SHORT).show();
				connectBtDevice(address);
			}
			break;
		case REQUEST_ENABLE_BT:
			if (resultCode == Activity.RESULT_OK) {
				//mChatService = new BluetoothChatService(this, mHandler);
			} else {
				// User did not enable Bluetooth or an error occurred
				Log.d(TAG, "BT not enabled");
				Toast.makeText(this, "Bluetooth not enabled.", Toast.LENGTH_SHORT).show();
				finish();
			}
		}
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.activity_receiver, menu);
		return true;
	}

	@Override
	protected void onPause() {
		super.onPause();
	}
	
	@Override
	public void onDestroy(){
		super.onDestroy();
		unbindService(mServiceConnection);
	}
	

	public void onBtnStart(View v){
		startService(new Intent(ReceiverActivity.this, ReceiverService.class));
	}
	
	public void onBtnStop(View v){
		stopService(new Intent(ReceiverActivity.this, ReceiverService.class));
	}
	
	public void onBtnBind(View v){
		doBindService();
	}
	
	public void onBtnUnbind(View v){
		doUnbindService();
	}
	
	public void onBtnTestMsg(View v){
		try {
			Message msg = Message.obtain(null, ReceiverService.MSG_SET_INT_AND_BROADCAST, testInt++, 0);
			serviceOutMessenger.send(msg);
		} catch (Exception e) {
			Toast.makeText(this, "SERVICE ERROR: "+e.getMessage()+" "+e.toString(), Toast.LENGTH_SHORT).show();
		}
	}
	
	public void onBtnBluetooth(View v){
		Intent serverIntent = new Intent(this, DeviceListActivity.class);
		startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
	}
	
	public void onBtnBtMsg(View v){
		sendBluetoothMessage(""  + testInt++ + "\n");
	}
	
	public void onSocketStart(View v){
		startSocket();
		textViewMyIP.setText("IP: " + getIpAddr());
	}

	public String getIpAddr() {
		WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		WifiInfo wifiInfo = wifiManager.getConnectionInfo();
		int ip = wifiInfo.getIpAddress();

		String ipString = String.format( "%d.%d.%d.%d",	
				(ip & 0xff), 
				(ip >> 8 & 0xff), 
				(ip >> 16 & 0xff), 
				(ip >> 24 & 0xff));
		return ipString;
	}
	

	void doBindService() {
		// bind
		bindService(new Intent(ReceiverActivity.this, ReceiverService.class), mServiceConnection, Context.BIND_AUTO_CREATE);
		serviceIsBound = true;
		statusTextView.setText("Binding.");
	}

	void doUnbindService() {
		if (serviceIsBound) {
			// unregister from service
			if (serviceOutMessenger != null) {
				try {
					Message msg = Message.obtain(null, ReceiverService.MSG_UNREGISTER_CLIENT);
					msg.replyTo = serviceInMessenger;
					serviceOutMessenger.send(msg);
				} catch (RemoteException e) {
					Log.e(TAG, e.toString());
				}
			}
			// unbind
			unbindService(mServiceConnection);
			serviceIsBound = false;
			statusTextView.setText("Unbinding.");
		}
	}
	
	//-----------------
	// bluetooth stuff
	//-----------------
	private void connectBtDevice(String address) {
		Log.d(TAG,"connectBtDevice(): "+address);
		
		try {
	                Message msg = Message.obtain(null, ReceiverService.MSG_BT_CONNECT);
			Bundle b = new Bundle();
	                b.putString("device", address);
	                msg.setData(b);
	                serviceOutMessenger.send(msg);
		} catch (Exception e) {
			Toast.makeText(this, "SERVICE ERROR: "+e.getMessage()+" "+e.toString(), Toast.LENGTH_SHORT).show();
		}
                

	}
	
	private void sendBluetoothMessage(String message) {
		
		// check that there's actually something to send
		if (message.length() == 0)
			return;
		
		try {
	                Message msg = Message.obtain(null, ReceiverService.MSG_BT_SEND);
			Bundle b = new Bundle();
	                b.putString("message", message);
	                msg.setData(b);
	                serviceOutMessenger.send(msg);
		} catch (Exception e) {
			Toast.makeText(this, "SEND BT ERROR: "+e.getMessage()+" "+e.toString(), Toast.LENGTH_SHORT).show();
		}


	}

	//--------------
	// socket stuff
	//--------------
	void startSocket() {
		try {
	                Message msg = Message.obtain(null, ReceiverService.MSG_SOCK_START);
	                serviceOutMessenger.send(msg);
		} catch (Exception e) {
			Toast.makeText(this, "SERVICE SOCK ERROR: "+e.getMessage()+" "+e.toString(), Toast.LENGTH_SHORT).show();
		}
	}
	
	//------------------
	// Status bar stuff
	//------------------
	private final void setStatus(int resId) {
		final ActionBar actionBar = getActionBar();
		actionBar.setSubtitle(resId);
	}

	private final void setStatus(CharSequence subTitle) {
		final ActionBar actionBar = getActionBar();
		actionBar.setSubtitle(subTitle);
	}

  
	private ServiceConnection mServiceConnection = new ServiceConnection() {
		
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {

			// keep the service messenger
			serviceOutMessenger = new Messenger(service);
			
			// send a message to the service to register our messenger
			try {
				Message msg = Message.obtain(null, ReceiverService.MSG_REGISTER_CLIENT);
				msg.replyTo = serviceInMessenger;
				serviceOutMessenger.send(msg);
			} catch (RemoteException e) {
			}

			Toast.makeText(ReceiverActivity.this, "ACTIVITY: connected to service", Toast.LENGTH_SHORT).show();
			statusTextView.setText("Bound to Service.");
		}

		@Override
		public void onServiceDisconnected(ComponentName className) {

			serviceOutMessenger = null;
			statusTextView.setText("Disconnected.");

			Toast.makeText(ReceiverActivity.this, "remote_service_disconnected", Toast.LENGTH_SHORT).show();
		}
	};
	
	

	@SuppressLint("HandlerLeak")
	class ServiceMsgHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case ReceiverService.MSG_BROADCAST_INT_VALUE:
				statusTextView.setText("Service reply " + msg.arg1);
				break;
			case ReceiverService.MSG_BT_RECV:
				textViewBtrecv.setText(msg.getData().getString("message"));
				break;
			case ReceiverService.MSG_SOCK_REMOTE:
				textViewRemoteIP.setText(msg.getData().getString("remoteip"));
				break;
			default:
				super.handleMessage(msg);
			}
		}
	}
	
}
