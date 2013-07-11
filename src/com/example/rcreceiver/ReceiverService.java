/**
 * License: Apache License 2.0 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * 2013, Bearstech, Marcus Bauer
 */
package com.example.rcreceiver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.UUID;


import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;


public class ReceiverService extends Service {


	private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	private final String TAG = "Receiver-SERVICE";
	
	int NOTIFICATION_ID = 1;
	static final int ONGOING_NOTIFICATION = 2;
	static final boolean FOREGROUND = true;
	static final boolean BACKGROUND = false;

	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	static final int MSG_SET_INT_AND_BROADCAST = 3;
	static final int MSG_BROADCAST_INT_VALUE = 4;
	static final int MSG_BT_CONNECT = 5;
	static final int MSG_BT_SEND = 6;
	static final int MSG_BT_RECV = 7;
	static final int MSG_SOCK_START = 8;
	static final int MSG_SOCK_REMOTE = 9;
	

	// Constants that indicate the current connection state
	public static final int BT_STATE_NONE = 0;       // doing nothing
	public static final int BT_STATE_LISTEN = 1;     // listening for incoming connections - unused
	public static final int BT_STATE_CONNECTING = 2; // initiating an outgoing connection
	public static final int BT_STATE_CONNECTED = 3;  // connected to a remote device

	static final int SERVER_PORT = 1122;
	
	// notification manager
	NotificationManager mNotificationManager;
	
	// incoming messenger
	final Messenger inMessenger = new Messenger(new MsgHandler());
	
	// array of registered client messengers
	ArrayList<Messenger> serviceClients = new ArrayList<Messenger>();
	
	// some value set by client
	int someValue = 0;
	

	private BluetoothAdapter mBluetoothAdapter;
	private BtConnectThread mBtConnectThread;
	private BtCommunicationThread mBtCommunicationThread;
	private int mBtState;

	ServerSocket mServerSocket = null;
	Socket mSocket = null;


	@Override
	public void onCreate() {
		mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		// bluetooth
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
	}
	
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		showNotification(FOREGROUND); // does also foreground start 
		Toast.makeText(this, "SERVICE: STARTED", Toast.LENGTH_SHORT).show();
		return START_STICKY;
	}
	

	@Override
	public IBinder onBind(Intent intent) {
		showNotification(BACKGROUND);
		Toast.makeText(this, "SERVICE: BOUND", Toast.LENGTH_SHORT).show();
		return inMessenger.getBinder();
	}

	
	@Override
	public void onDestroy() {
		mNotificationManager.cancel(NOTIFICATION_ID);
		Toast.makeText(this, "SERVICE: DESTROYED", Toast.LENGTH_SHORT).show();
		try {
			mSocket.close();
		} catch (Exception e) {
			Log.e(TAG, e.toString());
		}
	}

	private synchronized void setState(int state) {
		mBtState = state;

	}


	public synchronized int getState() {
		return mBtState;
	}

	@SuppressWarnings("deprecation")
	private void showNotification(boolean foreground) {

		// ticker text
		CharSequence tickerText = "Bluetooth service started";
		// notification
		Notification notification = new Notification(R.drawable.ic_launcher, tickerText, System.currentTimeMillis());
		// activity to launch
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, ReceiverActivity.class), 0);
		// start notification
		if (foreground) {
			notification.setLatestEventInfo(this, "Bluetooth Server", "Running as foreground Service", contentIntent);
			startForeground(ONGOING_NOTIFICATION, notification);
		}
		else {
			notification.setLatestEventInfo(this, "Bluetooth Server", "Running as bound Service", contentIntent);
			mNotificationManager.notify(NOTIFICATION_ID, notification);
		}
	}
	

	public synchronized void resetBT() {
		// cancel any running threads
		if (mBtConnectThread != null) {mBtConnectThread.cancel(); mBtConnectThread = null;}
		if (mBtCommunicationThread != null) {mBtCommunicationThread.cancel(); mBtCommunicationThread = null;}

		setState(BT_STATE_NONE);
	}


	public synchronized void startBTConnectThread(String address) {

		Log.d(TAG,"startBTConnectThread() called, starting BtConnectThread");
		
		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

		// cancel any running thread
		resetBT();

		// start connect thread
		mBtConnectThread = new BtConnectThread(device);
		mBtConnectThread.start();
		
		setState(BT_STATE_CONNECTING);
	}


	public synchronized void onBTConnected(BluetoothSocket socket, BluetoothDevice device) {

		// cancel any running thread
		resetBT();

		// start communication thread
		mBtCommunicationThread = new BtCommunicationThread(socket);
		mBtCommunicationThread.start();

		setState(BT_STATE_CONNECTED);
	}



	public void writeToBT(byte[] out) {

		BtCommunicationThread commThread;

		synchronized (this) {
			if (mBtState != BT_STATE_CONNECTED) return;
			commThread = mBtCommunicationThread;
		}
		// perform the write unsynchronized
		commThread.write(out);
	}

	
	
	
	private void btConnectionFailed() { //FIXME
	}


	private void btConnectionLost() {
	}
	

	void startSocketServer(){
		new Thread(new Runnable() {
			@Override
			public void run() {
			

				try {
					mServerSocket = new ServerSocket(SERVER_PORT);
				} catch (IOException e) {
					Log.e(TAG, "ERR **SERVER** SOCKET CREATION: " + e.getMessage() + " "+ e.toString());
				}
				

				try {
					mSocket = mServerSocket.accept();
					InetAddress ia = mSocket.getInetAddress(); // FIXME do something with this
					String remoteIP = ia.getHostAddress();
					
					for (int i=serviceClients.size()-1; i>=0; i--) {
						try {
							Message msg = Message.obtain(null, ReceiverService.MSG_SOCK_REMOTE);
							Bundle b = new Bundle();
					                b.putString("remoteip", remoteIP);
					                msg.setData(b);					                
							serviceClients.get(i).send(msg);
						} catch (RemoteException e) {
							// dead client, remove from list
							serviceClients.remove(i);
						}
					}
					
					InputStream inStream = mSocket.getInputStream();
					BufferedReader in = new BufferedReader(new InputStreamReader(inStream));
					String line;
					
					while ((line = in.readLine()) != null) {
						line = line + "\n";
						writeToBT(line.getBytes());
					}
				}
				catch (Exception e)
				{	
					Log.e(TAG, "ERR SOCKET CREATION: " + e.getMessage() + " "+ e.toString());
				}
			}
		}).start();

		Toast.makeText(this, "SERVER SOCKET CREATED", Toast.LENGTH_SHORT).show();
	}
	

	

	private class BtConnectThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;

		
		public BtConnectThread(BluetoothDevice device) {
			mmDevice = device;
			BluetoothSocket tmp = null;

			// Get a BluetoothSocket for a connection with the
			// given BluetoothDevice
			try {
				tmp = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);

			} catch (IOException e) {
				Log.e(TAG, "Socket create() failed", e);
			}
			mmSocket = tmp;
		}

		
		@Override
		public void run() {
			Log.i(TAG, "BEGIN mConnectThread run()");

			// cancel discovery because it slows down the connection
			mBluetoothAdapter.cancelDiscovery();

			// connect the bluetooth socket
			try {
				mmSocket.connect();
			} catch (IOException e) {
				Log.e(TAG,"ERROR socket.connect(): " + e);
				try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, "unable to close() socket during connection failure", e2);
				}
				btConnectionFailed();
				return;
			}

			// reset the this thread because we're done
			synchronized (ReceiverService.this) {
				mBtConnectThread = null;
			}

			// start the communication thread
			onBTConnected(mmSocket, mmDevice);
		}

		
		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "ConnectThread.cancel(): close() of connected socket failed", e);
			}
		}
	}

	private class BtCommunicationThread extends Thread {
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private final BufferedReader in;

		//
		// constructor - get streams
		//
		public BtCommunicationThread(BluetoothSocket socket) {
			Log.d(TAG, "created CommunicationThread");
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			try {
				tmpIn  = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
			
			in = new BufferedReader(new InputStreamReader(mmInStream));
		}

		
		public void run() {
			Log.i(TAG, "BEGIN mConnectedThread");
			String line;
			
			try {
				while ((line = in.readLine()) != null) {
					for (int i=serviceClients.size()-1; i>=0; i--) {
						try {
							Message msg = Message.obtain(null, ReceiverService.MSG_BT_RECV);
							Bundle b = new Bundle();
					                b.putString("message", line);
					                msg.setData(b);					                
							serviceClients.get(i).send(msg);
						} catch (RemoteException e) {
							// dead client, remove from list
							serviceClients.remove(i);
						}
					}
				}
			} catch (IOException e) {
				Log.e(TAG,"ERROR on socket read: "+e.toString());
				btConnectionLost();
			}
			
		}

		//
		// write to output stream
		//
		public void write(byte[] buffer) {
			Log.d(TAG,"write()");
			try {
				mmOutStream.write(buffer,0,buffer.length);
			} catch (IOException e) {
				Log.e(TAG, "Exception during write(): "+ e.toString());
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}
	
	

	@SuppressLint("HandlerLeak")
	class MsgHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			

			case MSG_REGISTER_CLIENT:
				serviceClients.add(msg.replyTo);
				break;
			case MSG_UNREGISTER_CLIENT:
				serviceClients.remove(msg.replyTo);
				break;
			

			case MSG_SET_INT_AND_BROADCAST:
				// get the int value from the message
				someValue = msg.arg1;
				// broadcast to all registered client messengers
				for (int i=serviceClients.size()-1; i>=0; i--) {
					try {
						serviceClients.get(i).send(Message.obtain(null, MSG_BROADCAST_INT_VALUE, someValue, 0));
					} catch (RemoteException e) {
						// dead client, remove from list
						serviceClients.remove(i);
					}
				}
				break;
				

			case MSG_BT_CONNECT:
				String address = msg.getData().getString("device");
				Log.d(TAG,"MSG_BT_CONNECT handling started");
				startBTConnectThread(address);
				break;
			case MSG_BT_SEND:
				String message = msg.getData().getString("message");
				Log.d(TAG,"MSG_BT_SEND handling started");
				if (mBtCommunicationThread != null)
					mBtCommunicationThread.write(message.getBytes());
				break;
				

			case MSG_SOCK_START:
				startSocketServer();
				break;
				
			default:
				super.handleMessage(msg);
			}
		}
	}
}
