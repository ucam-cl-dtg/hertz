package uk.ac.cam.cl.dtg.android.audionetworking.hertz;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;

public class Hertz extends Activity {

	private Button actionButton;
	private EditText editText;
	private String filename;
	private ProgressBar saving;
	private Spinner spinner;
	
	private AlertDialog dialog;
	
	private ByteArrayOutputStream bytesOut;
	
	private boolean isListening;
	
   	public int sampleRate = 8000;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        actionButton = (Button) findViewById(R.id.actionButton);
        editText = (EditText) findViewById(R.id.editText);
        saving = (ProgressBar) findViewById(R.id.saving);
        spinner = (Spinner) findViewById(R.id.spinner);
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        dialog = builder.create();
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK",
        		new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
        
        saving.setVisibility(View.GONE);
        editText.setSingleLine(true);
        
        actionButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				
				String state = Environment.getExternalStorageState();
				Log.d("FS State", state);
				if (state.equals(Environment.MEDIA_SHARED)) {
					dialog.setTitle("Unmount USB storage");
					dialog.setMessage("Please unmount USB storage before " +
							"starting to record.");
					dialog.show();
					return;
				} else if (state.equals(Environment.MEDIA_REMOVED)) {
					dialog.setTitle("Insert SD Card");
					dialog.setMessage("Please insert an SD card. You need " +
							"something to record onto.");
					dialog.show();
					return;
				}
				
				filename = editText.getText().toString();
				if (filename.equals("") || filename == null) {
					dialog.setTitle("Enter a file name");
					dialog.setMessage("Please give your file a name. It's " +
							"the least it deserves.");
					dialog.show();
					return;
				}
				
				if (isListening) {
					isListening = false;
					Thread thread = new Thread() {
						public void run() {
							if (!filename.endsWith(".wav"))
								filename += ".wav";
							runOnUiThread(new Runnable() {
								public void run() {
									actionButton.setEnabled(false);
									actionButton.setText("Saving...");
									saving.setVisibility(View.VISIBLE);
								}
							});
							byte[] bytes = bytesOut.toByteArray();
							save(filename, bytes);
							runOnUiThread(new Runnable() {
								public void run() {
									actionButton.setEnabled(true);
									actionButton.setText("Start recording");
									saving.setVisibility(View.GONE);
								}
							});
						}
					};
					thread.start();
				} else {
					sampleRate = Integer.parseInt((String)spinner.getSelectedItem());
					isListening = true;
					actionButton.setText("Stop recording");
					Thread t = new Thread(new Capture());
					t.start();
				}
			}
        	
        });
    }
    
    public void onDestroy() {
    	super.onDestroy();
    	isListening = false;
    }
    
    private class Capture implements Runnable {
    	
	     private final int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_MONO;
	     private final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
	     // the actual output format is big-endian, signed
	     
    	public void run() {
    		// We're important...
	          android.os.Process
	                    .setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

	          // Allocate Recorder and Start Recording...
	          int bufferSize =
	        	  2*AudioRecord.getMinBufferSize(sampleRate,channelConfig, audioEncoding);
	          AudioRecord recordInstance =
	        	  new AudioRecord(MediaRecorder.AudioSource.MIC,
	        			  sampleRate, channelConfig, audioEncoding, bufferSize);
	          
	          byte[] tempBuffer = new byte[bufferSize];
	          bytesOut = new ByteArrayOutputStream();
	          recordInstance.startRecording();
	          
	          try {
	        	  while (isListening) { 
	        		  recordInstance.read(tempBuffer,0,bufferSize);
	        		  bytesOut.write(tempBuffer);
	        	  }
	          } catch (IOException e) {
	        	  e.printStackTrace();
	          } catch (OutOfMemoryError om) {
	        	  runOnUiThread(new Runnable() {
						public void run() {
							dialog.setTitle("Out of memory");
							dialog.setMessage("The system has been " +
									"too strong for too long - but what you " +
									"recorded up to now has been saved.");
							dialog.show();
							System.gc();
							actionButton.performClick();
						}
					});
	          }
	          
	          // we're done recording
	          Log.d("Capture","Stopping recording");
	          recordInstance.stop();
    	}
    }
    
	public void save(String name, byte[] bytes) {
		File fileName = new File(Environment.
				getExternalStorageDirectory() + "/" + name);
		if (fileName.exists())
			fileName.delete();

		try {
			fileName.createNewFile();
			FileOutputStream out = new FileOutputStream(fileName);

			byte[] header = createHeader(bytes);		
			out.write(header); out.write(bytes);
			out.flush(); out.close();
			System.gc();
			
		} catch (Exception e) {
			Log.e("SaveToWAV", "Error saving WAV file");
		}
		
		Intent scanWav = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
		scanWav.setData(Uri.fromFile(fileName));
		sendBroadcast(scanWav);
	}
	
	public byte[] createHeader(byte[] bytes) {
		
		int totalLength = bytes.length + 4 + 24 + 8;
		byte[] lengthData = intToBytes(totalLength);
		byte[] samplesLength = intToBytes(bytes.length);
		byte[] sampleRate = intToBytes(this.sampleRate);
		byte[] bytesPerSecond = intToBytes(this.sampleRate*2);

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		try {
			out.write(new byte[] {'R','I','F','F'});
			out.write(lengthData);
			out.write(new byte[] {'W','A','V','E'});

			out.write(new byte[] {'f','m','t',' '});
			out.write(new byte[] {0x10,0x00,0x00,0x00}); // 16 bit chunks
			out.write(new byte[] {0x01,0x00,0x01,0x00}); // mono
			out.write(sampleRate); // sampling rate 8000
			out.write(bytesPerSecond); // bytes per second 16000
			out.write(new byte[] {0x02,0x00,0x10,0x00}); // 2 bytes per sample

			out.write(new byte[] {'d','a','t','a'});
			out.write(samplesLength);	
		} catch (IOException e) {
			Log.e("Create WAV",e.getMessage());
		}

		return out.toByteArray();
	}
	
	public byte[] intToBytes(int in) {
		byte[] bytes = new byte[4];
		for (int i=0; i<4; i++) {
			bytes[i] = (byte) ((in >>> i*8) & 0xFF);
		}
		return bytes;
	}
}