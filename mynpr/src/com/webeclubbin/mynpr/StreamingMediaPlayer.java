package com.webeclubbin.mynpr;
//package com.pocketjourney.media;
//Code taken from below URL. 
//http://blog.pocketjourney.com/2008/04/04/tutorial-custom-media-streaming-for-androids-mediaplayer/
//Good looking out!

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Vector;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.os.Process;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * MediaPlayer does not yet support "Shoutcast"-like streaming from external URLs so this class provides a pseudo-streaming function
 * by downloading the content incrementally & playing as soon as we get enough audio in our temporary storage.
 */ 
public class StreamingMediaPlayer extends Service {

	final static public String AUDIO_MPEG =  "audio/mpeg";
	final static public String BITERATE_HEADER =  "icy-br";
    private int INTIAL_KB_BUFFER ;
    private int BIT = 8 ;
    private int SECONDS = 30 ;

	private int totalKbRead = 0;
	
	private File downloadingMediaFile ; 
	private String DOWNFILE = "downloadingMediaFile";

	private Context context;
	private int counter = 0;
	private int playedcounter = 0;
	//TODO should convert to Stack object instead of Vector
	private Vector<MediaPlayer> mediaplayers = new Vector<MediaPlayer>(3);
	private boolean started = false;
	private boolean processHasStarted = false; 
	private boolean regularStream = false;
	private InputStream stream = null;
	private URL url = null;
    private URLConnection urlConn = null;
    
    private String station = null;
    private String audiourl = null;
    
    private Intent startingIntent = null;
    
    // listen for calls
	// http://www.androidsoftwaredeveloper.com/2009/04/20/how-to-detect-call-state/ 
	  final PhoneStateListener myPhoneListener = new PhoneStateListener() {
		  public void onCallStateChanged(int state, String incomingNumber) {
			  String TAG = "PhoneStateListener";
			  
			  switch (state) {
			  	case TelephonyManager.CALL_STATE_RINGING:
			  		Log.d(TAG, "Someone's calling. Let us stop the service");
			  		sendMessage(PlayListTab.STOP);
				  	break;
			  	case TelephonyManager.CALL_STATE_OFFHOOK:
				  	break;
				case TelephonyManager.CALL_STATE_IDLE:
				  	break;
				default:
				  	Log.d(TAG, "Unknown phone state = " + state);
			  }
		  }
	  };
    
    //private int currentStatus = -1;

    //This object will allow other processes to interact with our service
    private final IStreamingMediaPlayer.Stub ourBinder = new IStreamingMediaPlayer.Stub(){
        String TAG = "IStreamingMediaPlayer.Stub";
        // Returns Currently Station Name
        public String getStation(){
        	Log.d(TAG, "getStation" );
        	return station;
        }
        
        // Returns Currently Playing audio url
        public String getUrl(){
        	Log.d(TAG, "getUrl" );
        	return audiourl;
        }
        
        // Check to see if service is playing audio
    	public boolean playing(){
    		Log.d(TAG, "playing?" );
    		return isPlaying();
    	}
    	
    	//Start playing audio
    	public void startAudio(){
    		Log.d(TAG, "startAudio" );
    		raiseThreadPriority();
    		
    		Runnable r = new Runnable() {   
    			public void run() {   
    				onStart (startingIntent, 0);
    			}   
    		};   
    		new Thread(r).start(); 
    		
    	}
    	
    	//Stop playing audio
    	public void stopAudio() {
    		Log.d(TAG, "stopAudio" );
    		stop();
    	}
    	
    };
    
    @Override 
    public void onCreate() {
    	  super.onCreate();
    	  
    	  String TAG = "StreamingMediaPlayer - onCreate";
    	  Log.d(TAG, "START");

    	  
    	  Log.d(TAG, "Setup Phone listener");
    	  TelephonyManager tm = (TelephonyManager)getSystemService(TELEPHONY_SERVICE);
    	  tm.listen(myPhoneListener, PhoneStateListener.LISTEN_CALL_STATE);

    }
    
    @Override
    public void onStart (Intent intent, int startId){
    	super.onStart(intent, startId);
    	
    	final String TAG = "StreamingMediaPlayer - onStart";
    	Log.d(TAG, "START");

    	Log.d(TAG, "Intent: " + intent.getStringExtra(PlayListTab.URL));
    	Log.d(TAG, "Station: " + intent.getStringExtra(PlayListTab.STATION));
    	Log.d(TAG, "RegularStream: " + intent.getBooleanExtra(PlayListTab.REGULARSTREAM, false));
    	
    	processHasStarted = true;
    	
    	audiourl =  intent.getStringExtra(PlayListTab.URL);
    	station =  intent.getStringExtra(PlayListTab.STATION);
    	
    	if (intent.getBooleanExtra(PlayListTab.REGULARSTREAM, false)){
    		regularStream = true;
    	}
    	
    	Log.d(TAG,"Run startStreaming function");
    
    	context = this;
     	downloadingMediaFile = new File(context.getCacheDir(),DOWNFILE + counter);
     	downloadingMediaFile.deleteOnExit();

     	Runnable r = new Runnable() {   
     		public void run() {   
     			try {
     					startStreaming( audiourl );
				} catch (IOException e) {
						Log.d(TAG, e.toString() );
				} 
    	    }   
    	};   
    	new Thread(r).start(); 
    	
    }
    
    @Override 
    public void onDestroy() {
    	  super.onDestroy();

    	  String TAG = "StreamingMediaPlayer - onDestroy";
    	  Log.d(TAG, "START");
    	  
    	  // stop the service here

    }
    
    @Override
    public IBinder onBind (Intent intent){
    	String TAG = "StreamingMediaPlayer - onBind";
    	Log.d(TAG, "START");
    	Log.d(TAG, "Intent: " + intent.getStringExtra(PlayListTab.URL));
    	Log.d(TAG, "Station: " + intent.getStringExtra(PlayListTab.STATION));
    	startingIntent = intent;

    	context = this;
    	
    	return ourBinder;
    }
   
	
    /**  
     * Progressivly download the media to a temporary location and update the MediaPlayer as new content becomes available.
     */  
    public void startStreaming(final String mediaUrl) throws IOException {
    	
    	final String TAG = "startStreaming";
        int bitrate = 56;
        
        sendMessage( PlayListTab.CHECKRIORITY );
        
		sendMessage( PlayListTab.RAISEPRIORITY );
        
        sendMessage( PlayListTab.START );
        
        

    	try {
    		url = new URL(mediaUrl);
    		urlConn = (HttpURLConnection)url.openConnection();
    		urlConn.setReadTimeout(1000 * 20);
    		urlConn.setConnectTimeout(1000 * 5);

    		String ctype = urlConn.getContentType () ;
    		if (ctype == null){
    			ctype = "";
    		} else {
    			ctype = ctype.toLowerCase() ;
    		}
    		
    		//See if we can handle this type 
    		Log.d(TAG, "Content Type: " + ctype );
    		if (ctype.contains(AUDIO_MPEG) || ctype.equals("")){
    			
    			String temp = urlConn.getHeaderField(BITERATE_HEADER);
    			Log.d(TAG, "Bitrate: " + temp );
    			if (temp != null){
    				bitrate = new Integer(temp).intValue();
    			}
    		} else {
    			Log.e(TAG, "Does not look like we can play this audio type: " + ctype);
    			Log.e(TAG, "Or we could not connect to audio");
    			sendMessage (PlayListTab.TROUBLEWITHAUDIO);
    			stop();
    			return;
    		}
    	} catch (IOException ioe) {
    		Log.e( TAG, "Could not connect to " +  mediaUrl );
    		sendMessage( PlayListTab.TROUBLEWITHAUDIO);
    		stop();
    		return;
    	} 
    	
    	

	    if (regularStream){
        	//Lets Start Streaming normally
	    	Runnable r = new Runnable() {   
    	        public void run() {   
    	            try {   
    	        		downloadAudio(mediaUrl);
    	            } catch (IOException e) {
    	            	Log.e(TAG, "Unable to initialize the MediaPlayer for Audio Url = " + mediaUrl, e);
    	            	sendMessage( PlayListTab.TROUBLEWITHAUDIO);
    	            	stop();
    	            	return;
    	            }   
    	        }   
    	    };   
    	    new Thread(r).start(); 
        } else {
        	//Lets Start Streaming by downloading parts of the stream and playing it in pieces
        	//Set up buffer size
        	//Assume XX kbps * XX seconds / 8 bits per byte
        	INTIAL_KB_BUFFER =  bitrate * SECONDS / BIT; 
        	 
    		Runnable r = new Runnable() {   
    	        public void run() {   
    	            try {   
    	        		downloadAudioIncrement(mediaUrl);
    	            } catch (IOException e) {
    	            	Log.e(TAG, "Unable to initialize the MediaPlayer for Audio Url = " + mediaUrl, e);
    	            	sendMessage( PlayListTab.TROUBLEWITHAUDIO);
    	            	stop();
    	            	return;
    	            }   
    	        }   
    	    };   
    	    new Thread(r).start(); 
        }
    }
    
    /**  
     * Download the url stream to a temporary location and then call the setDataSource  
     * for that local file
     */  
    public void downloadAudioIncrement(String mediaUrl) throws IOException {
    	final String TAG = "downloadAudioIncrement";

    	//URLConnection cn = new URL(mediaUrl).openConnection(); 
    	//cn.setConnectTimeout(1000 * 30);
    	//cn.setReadTimeout(1000 * 15);
        //cn.connect();   
        stream = urlConn.getInputStream();
        if (stream == null) {
        	Log.e(TAG, "Unable to create InputStream for mediaUrl: " + mediaUrl);
        }
        
		Log.d(TAG, "File name: " + downloadingMediaFile);
		BufferedOutputStream bout = new BufferedOutputStream ( new FileOutputStream(downloadingMediaFile), 32 * 1024 );   
        byte buf[] = new byte[16 * 1024];
        int totalBytesRead = 0, incrementalBytesRead = 0, numread = 0;
        
        do {
        	if (bout == null) {
        		counter++;
        		Log.d(TAG, "FileOutputStream is null, Create new one: " + DOWNFILE + counter);
        		downloadingMediaFile = new File(context.getCacheDir(),DOWNFILE + counter);
        		downloadingMediaFile.deleteOnExit();
        		bout = new BufferedOutputStream ( new FileOutputStream(downloadingMediaFile) );	
        	}

        	try {
        		numread = stream.read(buf);
        	} catch (IOException e){
        		Log.e(TAG, e.toString());
        		if (stream != null){
        			Log.d(TAG, "Bad read. Let's try to reconnect to source and continue downloading");
        			urlConn = new URL(mediaUrl).openConnection(); 
        			urlConn.setConnectTimeout(1000 * 30);
        			urlConn.connect();   
        	        stream = urlConn.getInputStream();
        	        numread = stream.read(buf);
        		}
        	} catch (NullPointerException e) {
        		//Let's get out of here
        		break;
        	}
        	
            if (numread <= 0) {  
                break;   
            	
            } else {
            	//Log.v(TAG, "write to file");
            	bout.write(buf, 0, numread);

            	totalBytesRead += numread;
            	incrementalBytesRead += numread;
            	totalKbRead = totalBytesRead/1000;
            }
            
            if ( totalKbRead >= INTIAL_KB_BUFFER ) {
            	sendMessage( PlayListTab.CHECKRIORITY );
            	Log.v(TAG, "Reached Buffer amount we want: " + "totalKbRead: " + totalKbRead + " INTIAL_KB_BUFFER: " + INTIAL_KB_BUFFER);
            	bout.flush();
            	bout.close();
            	            	
            	bout = null;
            	
            	setupplayer(downloadingMediaFile);
            	totalBytesRead = 0;

            }
            
        } while (stream != null);   


    }  

    //Play Audio stream normally
    public void downloadAudio(final String mediaUrl) throws IOException {
    	final String TAG = "downloadAudio";

		Runnable r = new Runnable() {   
	        public void run() {   
	        	MediaPlayer m = new MediaPlayer();
	        	MediaPlayer.OnBufferingUpdateListener onBuff = new MediaPlayer.OnBufferingUpdateListener(){
	        		public void onBufferingUpdate (MediaPlayer mp, int percent){
	        			Log.d(TAG," Precent Buffered: " + percent);
	        			if ( percent > 8 && percent < 11 ){
	        				sendMessage( PlayListTab.STOPSPIN );
	        			}
	        		}
	        	};
	        	MediaPlayer.OnCompletionListener listener = new MediaPlayer.OnCompletionListener () {
        			public void onCompletion(MediaPlayer mp){
        				String TAG = "MediaPlayer.OnCompletionListener - Normal Streaming";
        				Log.d(TAG, "Audio is done");
        				stop();
        				
        			}
        		};
        		
	            try {
	            	m.setOnBufferingUpdateListener(onBuff);
	            	m.setDataSource(mediaUrl);
	            	Log.d(TAG, "prepare audio");
	            	m.prepare();
	            	m.start();
	            	mediaplayers.add(m);
	            } catch (IllegalArgumentException e) {
	            	Log.e(TAG, "Unable to initialize the MediaPlayer for Audio Url = " + mediaUrl, e);
	            	sendMessage( PlayListTab.TROUBLEWITHAUDIO);
	            	stop();
	            } catch (IOException e) {
	            	Log.e(TAG, "Unable to initialize the MediaPlayer for Audio Url = " + mediaUrl, e);
	            	sendMessage( PlayListTab.TROUBLEWITHAUDIO);
	            	stop();
	            }
	        }   
	    };  
	    new Thread(r).start();
    	
    }  

    
    /**
     * Set Up player(s)
     */  
    private void  setupplayer(File partofaudio) {
    	final File f = partofaudio;
    	final String TAG = "setupplayer";
    	Log.d(TAG, "File " + f.getAbsolutePath());
	    Runnable r = new Runnable() {
	        public void run() {
	        	
	        	MediaPlayer mp = new MediaPlayer();
	        	try {
	        		
	        		MediaPlayer.OnCompletionListener listener = new MediaPlayer.OnCompletionListener () {
	        			public void onCompletion(MediaPlayer mp){
	        				String TAG = "MediaPlayer.OnCompletionListener - Partial download";
	        				Log.d(TAG, "Start");
	        				Log.d(TAG, "Current size of mediaplayer list: " + mediaplayers.size() );
	        				boolean waitingForPlayer = false;
	        				boolean leave = false;
	        				while (mediaplayers.size() <= 1 && leave == false){
    			        		Log.v(TAG, "waiting for another mediaplayer");
    			        		if (waitingForPlayer == false ){
    			        			try {
    			        				Log.v(TAG, "Sleep for a moment");
    			        				//Spin the spinner
    			        				
    			        				sendMessage( PlayListTab.SPIN ) ;
    			        				//Thread.currentThread().sleep(1000 * 15);
    			        				Thread.sleep(1000 * 15);
    			        				
    			        				
    			        				sendMessage( PlayListTab.STOPSPIN );
    			        				waitingForPlayer = true;
    			        			} catch (InterruptedException e) {
    			        				Log.e(TAG, e.toString());
    			        			}
    			        		} else {
    			        			Log.e(TAG, "Timeout occured waiting for another media player");
    			        			//Toast.makeText(context, "Trouble downloading audio. :-(" , Toast.LENGTH_LONG).show();
    			        			sendMessage( PlayListTab.TROUBLEWITHAUDIO);
    			        			stop();
    			        			
    			        			leave = true;
    			        		}
    			        	}
	        				if (leave == false){
	        					MediaPlayer mp2 = mediaplayers.get(1);
	        					mp2.start();
	        					Log.d(TAG, "Start another player");
    			        	
	        					mp.release();
	        					mediaplayers.remove(mp);
	        					removefile();
	        				}
	        				
	        			}
	        		};
	        		
	        		FileInputStream ins = new FileInputStream( f );
	            	mp.setDataSource(ins.getFD());
	        		mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
	        		
	        		Log.d(TAG, "Setup player completion listener");
	        		mp.setOnCompletionListener(listener);
	        		Log.d(TAG, "Prepare Media Player " + f);
	        		
	        		if ( ! started  ){
	        			mp.prepare();
	        		} else {
	        			//This will save us a few more seconds
	        			mp.prepareAsync();
	        		}
	        		
	        		mediaplayers.add(mp);
	        		if ( ! started  ){
		        		Log.d(TAG, "Start Media Player " + f);
		        		startMediaPlayer();
		        	}
	        	} catch  (IllegalStateException	e) {
	        		Log.e(TAG, e.toString());
	        		sendMessage( PlayListTab.TROUBLEWITHAUDIO);
	        		stop();
	        	} catch  (IOException	e) {
	        		Log.e(TAG, e.toString());
	        		sendMessage( PlayListTab.TROUBLEWITHAUDIO);
	        		stop();
	        	}
	        	
 	        }
	    };
	    Thread ourthread = new Thread(r);
	    ourthread.start();
	    
	    // Wait indefinitely for the thread to finish
	    if ( ! started  ){
	    	try {
	    		Log.d(TAG, "Start and wait for first audio clip to be prepared.");
	    		ourthread.join();
	    		// Finished
	    	} catch (InterruptedException e) {
	    		// Thread was interrupted
	    	}
	    }  

    }
   
    //Removed file from cache
    private void removefile (){
    	String TAG = "removefile";
    	File temp = new File(context.getCacheDir(),DOWNFILE + playedcounter);
    	Log.d(TAG, temp.getAbsolutePath());
    	temp.delete();
    	playedcounter++;
    }
    
    
    
    //Start first audio clip
    private void startMediaPlayer() {
    	String TAG = "startMediaPlayer";
    	
    	//Grab out first media player
    	started = true;
    	MediaPlayer mp = mediaplayers.get(0);
    	Log.d(TAG,"Start Player");
    	mp.start(); 
    	
    	sendMessage(PlayListTab.STOPSPIN);
    		
    }
    
    //Stop Audio
    public void stop(){
    	String TAG = "STOP";
    	Log.d(TAG,"Entry");
    	
    	if (regularStream == true){
    		sendMessage(PlayListTab.RESETPLAYSTATUS);
    	} 
    	regularStream = false;
    	
    	try {
    		
    		if (mediaplayers != null){
    			if (! mediaplayers.isEmpty() ){
        			final MediaPlayer mp = mediaplayers.get(0);
        			if (mp.isPlaying()){
        				Log.d(TAG,"Stop Player");
        				Runnable r = new Runnable() {   
        	    	        public void run() {   
        	    	        	mp.stop();  
        	    	        }   
        	    	    };   
        	    	    new Thread(r).start(); 
        				
        			}
        		}
    		}
    		
    		if (stream != null){
    			Log.d(TAG,"Close stream");
    			stream.close();
    		}
    		stream = null;
    		
    		//if (tellPlayList) {
    		//	sendMessage(PlayListTab.STOP);
    		//}
    		//sendMessage(PlayListTab.RESETPLAYSTATUS);
    		
    		//Take off listener
    		Log.d(TAG, "Remove Phone listener");
      	  	TelephonyManager tm = (TelephonyManager)getSystemService(TELEPHONY_SERVICE);
      	  	tm.listen(myPhoneListener, PhoneStateListener.LISTEN_NONE);
    		stopSelf();
    		

    	} catch (ArrayIndexOutOfBoundsException e) {
    		Log.e(TAG, "No items in Media player List");
    		sendMessage(PlayListTab.STOP);
    	} catch (IOException e) {
    		Log.e(TAG, "error closing open connection");
    		sendMessage(PlayListTab.STOP);
    	}
    }

    //Is the streamer playing audio?
    public boolean isPlaying() {
    	String TAG = "isPlaying";
    	Log.d(TAG, " = "  + processHasStarted);
    	/*boolean result = false;
    	try {
    		MediaPlayer mp = mediaplayers.get(0);
    		if (mp.isPlaying()){
    			result = true;
    		} else {
    			result = false;
    		}
    	} catch (ArrayIndexOutOfBoundsException e) {
    		Log.e(TAG, "No items in Media player List");
    	}
    	
    	return result; */
    	return processHasStarted ;
    }
    
    //Send Message to PlaylistTab
    private synchronized void sendMessage(int m){
    	String TAG = "sendMessage";
    	Intent i = new Intent(MyNPR.tPLAY);

    	i.putExtra(PlayListTab.MSG, m);
    	Log.d(TAG, "Broadcast Message intent");
    	context.sendBroadcast (i) ;
    }
    
    /*private void checkThreadPriority(){
    	String TAG = "checkThreadPriority";
    	Log.d(TAG, "Start" );
    	//Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); 
    	Log.v(TAG, "Process priority: " + Process.getThreadPriority(Process.myTid()));
    	//Process.THREAD_PRIORITY_FOREGROUND
    } */
    
    private void raiseThreadPriority(){
    	String TAG = "raiseThreadPriority";
    	Log.d(TAG, "Start" );
    	Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); 
    	//Log.v(TAG, "Process priority: " + Process.getThreadPriority(Process.myTid()));
    	//Process.THREAD_PRIORITY_FOREGROUND
    } 
    
}

