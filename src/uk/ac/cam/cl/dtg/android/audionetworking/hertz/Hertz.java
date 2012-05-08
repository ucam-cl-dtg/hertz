package uk.ac.cam.cl.dtg.android.audionetworking.hertz;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;

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
import android.os.StatFs;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;

/**
 * An activity that allows the user to record full-quality audio at a variety of sample rates, and
 * save to a WAV file
 * 
 * @author Rhodri Karim
 * 
 */
public class Hertz extends Activity {

  private Button actionButton;
  private ImageButton newTimestamp;
  private EditText editText;
  private String filename;
  private ProgressBar saving;
  private Spinner spinner;

  private AlertDialog dialog;

  private File outFile;

  private boolean isListening;

  /**
   * The sample rate at which we'll record, and save, the WAV file.
   */
  public int sampleRate = 8000;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    // set up GUI references
    actionButton = (Button) findViewById(R.id.actionButton);
    newTimestamp = (ImageButton) findViewById(R.id.newTimestamp);
    editText = (EditText) findViewById(R.id.editText);
    saving = (ProgressBar) findViewById(R.id.saving);
    spinner = (Spinner) findViewById(R.id.spinner);

    // get a generic dialog ready for alerts
    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    dialog = builder.create();
    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();
      }
    });

    // add GUI functionality
    saving.setVisibility(View.GONE);
    editText.setSingleLine(true);

    newTimestamp.setOnClickListener(new OnClickListener() {

      @Override
      public void onClick(View v) {
        String timedFilename = "Rec_";
        Date date = new Date();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
        timedFilename += format.format(date);
        editText.setText(timedFilename);
      }
    });

    actionButton.setOnClickListener(new OnClickListener() {

      @Override
      public void onClick(View v) {

        // if we're already recording... start saving
        if (isListening) {
          isListening = false;
          Thread thread = new Thread() {
            @Override
            public void run() {
              runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  actionButton.setEnabled(false);
                  actionButton.setText("Saving...");
                  saving.setVisibility(View.VISIBLE);
                }
              });

              if (outFile != null) {
                appendHeader(outFile);

                Intent scanWav = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanWav.setData(Uri.fromFile(outFile));
                sendBroadcast(scanWav);

                outFile = null;
              }

              runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  actionButton.setEnabled(true);
                  editText.setEnabled(true);
                  newTimestamp.setEnabled(true);
                  actionButton.setText("Start recording");
                  saving.setVisibility(View.GONE);
                }
              });
            }
          };
          thread.start();

        } else {

          // check that there's somewhere to record to
          String state = Environment.getExternalStorageState();
          Log.d("FS State", state);
          if (state.equals(Environment.MEDIA_SHARED)) {
            dialog.setTitle("Unmount USB storage");
            dialog.setMessage("Please unmount USB storage before " + "starting to record.");
            dialog.show();
            return;
          } else if (state.equals(Environment.MEDIA_REMOVED)) {
            dialog.setTitle("Insert SD Card");
            dialog.setMessage("Please insert an SD card. You need " + "something to record onto.");
            dialog.show();
            return;
          }

          // check that the user's supplied a file name
          filename = editText.getText().toString();
          if (filename.equals("") || filename == null) {
            dialog.setTitle("Enter a file name");
            dialog.setMessage("Please give your file a name. It's " + "the least it deserves.");
            dialog.show();
            return;
          }
          if (!filename.endsWith(".wav"))
            filename += ".wav";

          // ask if file should be overwritten
          File userFile = new File(Environment.getExternalStorageDirectory() + "/" + filename);
          if (userFile.exists()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(Hertz.this);
            builder.setTitle("File already exists").setMessage(
                "Do you want to overwrite the existing " + "file with that name?").setCancelable(
                false).setPositiveButton("OK", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
                startRecording();
              }
            }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
              }
            });
            AlertDialog alert = builder.create();
            alert.show();
          } else { // otherwise, start recording
            startRecording();
          }

        }
      }

    });
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    isListening = false;
  }

  public void startRecording() {
    sampleRate = Integer.parseInt((String) spinner.getSelectedItem());
    isListening = true;
    editText.setEnabled(false);
    newTimestamp.setEnabled(false);
    actionButton.setText("Stop recording");
    Thread s = new Thread(new SpaceCheck());
    s.start();
    Thread t = new Thread(new Capture());
    t.start();
  }

  /**
   * Monitors the available SD card space while recording.
   * 
   * @author Rhodri Karim
   * 
   */
  private class SpaceCheck implements Runnable {
    @Override
    public void run() {
      String sdDirectory = Environment.getExternalStorageDirectory().toString();
      StatFs stats = new StatFs(sdDirectory);
      while (isListening) {
        stats.restat(sdDirectory);
        final long freeBytes = (long) stats.getAvailableBlocks() * (long) stats.getBlockSize();
        if (freeBytes < 5242880) { // less than 5MB remaining
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              dialog.setTitle("Low on disk space");
              dialog.setMessage("There isn't enough space " + "left on your SD card (" + freeBytes
                  + "b) , but what you've " + "recorded up to now has been saved.");
              dialog.show();
              actionButton.performClick();
            }
          });
          return;
        }

        try {
          Thread.sleep(3000);
        } catch (InterruptedException e) {
        }
      }
    }
  }

  /**
   * Capture raw audio data from the hardware and saves it to a buffer in the enclosing class.
   * 
   * @author Rhodri Karim
   * 
   */
  private class Capture implements Runnable {

    private final int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    private final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;

    // the actual output format is big-endian, signed

    @Override
    public void run() {
      // We're important...
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

      // Allocate Recorder and Start Recording...
      int bufferSize = 2 * AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding);
      AudioRecord recordInstance =
          new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioEncoding,
              bufferSize);
      if (recordInstance.getState() != AudioRecord.STATE_INITIALIZED) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            dialog.setTitle("Error recording audio");
            dialog
                .setMessage("Unable to access the audio recording hardware - is your mic working?");
            dialog.show();
            actionButton.performClick();
          }
        });
        return;
      }

      byte[] tempBuffer = new byte[bufferSize];

      String sdDirectory = Environment.getExternalStorageDirectory().toString();
      outFile = new File(sdDirectory + "/" + filename);
      if (outFile.exists())
        outFile.delete();

      FileOutputStream outStream = null;
      try {
        outFile.createNewFile();
        outStream = new FileOutputStream(outFile);
      } catch (Exception e) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            dialog.setTitle("Error creating file");
            dialog.setMessage("The WAV file you specified "
                + "couldn't be created. Try again with a " + "different filename.");
            dialog.show();
            outFile = null;
            actionButton.performClick();
          }
        });
        return;
      }

      recordInstance.startRecording();

      try {
        while (isListening) {
          recordInstance.read(tempBuffer, 0, bufferSize);
          outStream.write(tempBuffer);
        }
      } catch (final IOException e) {
        runOnUiThread(new Runnable() {

          @Override
          public void run() {
            dialog.setTitle("IO Exception");
            dialog.setMessage("An exception occured when writing to disk or reading from the microphone\n"
                    + e.getLocalizedMessage()
                    + "\nWhat you have recorded so far should be saved to disk.");
            dialog.show();
            actionButton.performClick();
          }

        });
      } catch (OutOfMemoryError om) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            dialog.setTitle("Out of memory");
            dialog.setMessage("The system has been " + "too strong for too long - but what you "
                + "recorded up to now has been saved.");
            dialog.show();
            System.gc();
            actionButton.performClick();
          }
        });
      }

      // we're done recording
      Log.d("Capture", "Stopping recording");
      recordInstance.stop();
      try {
        outStream.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Appends a WAV header to a file containing raw audio data. Uses different strategies depending
   * on amount of free disk space.
   * 
   * @param file The file containing 16-bit little-endian PCM data.
   */
  public void appendHeader(File file) {

    int bytesLength = (int) file.length();
    byte[] header = createHeader(bytesLength);
    int headerLength = header.length;

    String sdDirectory = Environment.getExternalStorageDirectory().toString();
    StatFs stats = new StatFs(sdDirectory);
    int freeBytes = stats.getAvailableBlocks() * stats.getBlockSize();

    if (freeBytes > 2 * bytesLength + headerLength + 5242880) { // be wasteful if we have loads of
                                                                // space
      Log.d("Hertz", "Using wasteful header append...");
      try {
        String oldName = file.getPath();
        File wavFile = new File(oldName + ".tmp");
        if (wavFile.exists())
          wavFile.delete();
        wavFile.createNewFile();
        FileOutputStream wavOut = new FileOutputStream(wavFile);

        wavOut.write(createHeader((int) file.length()));

        FileInputStream pcmIn = new FileInputStream(file);
        int bytesRead;
        byte[] buffer = new byte[1024];
        while ((bytesRead = pcmIn.read(buffer)) > 0)
          wavOut.write(buffer, 0, bytesRead);
        wavOut.flush();
        wavOut.close();
        pcmIn.close();
        file.delete();
        boolean renamed = wavFile.renameTo(file);
        if (!renamed)
          Log.e("Hertz", "could not rename file after wasteful append");
      } catch (FileNotFoundException e) {
        Log.e("Hertz", "Tried to wastefully append header to invalid file");
        return;
      } catch (IOException e) {
        Log.e("Hertz", "IO exception during wasteful header append");
        return;
      }
    } else
      try {
        Log.d("Hertz", "Using thrifty header append...");
        RandomAccessFile ramFile = new RandomAccessFile(file, "rw");
        ramFile.setLength(bytesLength + headerLength);

        byte[] buffer = new byte[headerLength];
        int p = bytesLength - headerLength;
        while (p >= 0) {
          ramFile.seek(p);
          ramFile.read(buffer);
          ramFile.seek(p + headerLength);
          ramFile.write(buffer);
          p -= headerLength;
        }

        int strayBytes = headerLength + p;
        ramFile.seek(0);
        ramFile.read(buffer, 0, strayBytes);
        ramFile.seek(headerLength);
        ramFile.write(buffer, 0, strayBytes);

        ramFile.seek(0);
        ramFile.write(header);
        ramFile.close();
      } catch (FileNotFoundException e) {
        Log.e("Hertz", "Tried to append header to invalid file");
        return;
      } catch (IOException e) {
        Log.e("Hertz", "IO Error during header append");
        return;
      }

  }

  /**
   * Saves the supplied byte array as a WAV file
   * 
   * @param name The desired filename
   * @param bytes The sound data in 16-bit little-endian PCM format
   */
  public void save(String name, byte[] bytes) {
    String sdDirectory = Environment.getExternalStorageDirectory().toString();

    File fileName = new File(sdDirectory + "/" + name);
    if (fileName.exists())
      fileName.delete();

    try {
      fileName.createNewFile();
      FileOutputStream out = new FileOutputStream(fileName);

      byte[] header = createHeader(bytes.length);
      out.write(header);
      out.write(bytes);
      out.flush();
      out.close();
      System.gc();

    } catch (Exception e) {
      Log.e("SaveToWAV", "Error saving WAV file");
    }

    Intent scanWav = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
    scanWav.setData(Uri.fromFile(fileName));
    sendBroadcast(scanWav);
  }

  /**
   * Creates a valid WAV header for the given bytes, using the class-wide sample rate
   * 
   * @param bytes The sound data to be appraised
   * @return The header, ready to be written to a file
   */
  public byte[] createHeader(int bytesLength) {

    int totalLength = bytesLength + 4 + 24 + 8;
    byte[] lengthData = intToBytes(totalLength);
    byte[] samplesLength = intToBytes(bytesLength);
    byte[] sampleRate = intToBytes(this.sampleRate);
    byte[] bytesPerSecond = intToBytes(this.sampleRate * 2);

    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try {
      out.write(new byte[] {'R', 'I', 'F', 'F'});
      out.write(lengthData);
      out.write(new byte[] {'W', 'A', 'V', 'E'});

      out.write(new byte[] {'f', 'm', 't', ' '});
      out.write(new byte[] {0x10, 0x00, 0x00, 0x00}); // 16 bit chunks
      out.write(new byte[] {0x01, 0x00, 0x01, 0x00}); // mono
      out.write(sampleRate); // sampling rate
      out.write(bytesPerSecond); // bytes per second
      out.write(new byte[] {0x02, 0x00, 0x10, 0x00}); // 2 bytes per sample

      out.write(new byte[] {'d', 'a', 't', 'a'});
      out.write(samplesLength);
    } catch (IOException e) {
      Log.e("Create WAV", e.getMessage());
    }

    return out.toByteArray();
  }

  /**
   * Turns an integer into its little-endian four-byte representation
   * 
   * @param in The integer to be converted
   * @return The bytes representing this integer
   */
  public byte[] intToBytes(int in) {
    byte[] bytes = new byte[4];
    for (int i = 0; i < 4; i++) {
      bytes[i] = (byte) ((in >>> i * 8) & 0xFF);
    }
    return bytes;
  }
}