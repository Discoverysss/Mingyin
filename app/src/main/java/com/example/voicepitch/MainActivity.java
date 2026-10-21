package com.example.voicepitch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.DialogInterface;
import android.media.MediaPlayer;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private PitchView pitchView;
    private volatile boolean recording;
    private volatile AudioRecord audioRecord;
    private Thread audioThread;
    private long sessionStarted;
    private final ArrayList<TrackPoint> activeTrack = new ArrayList<>();
    private long lastTrackWrite;
    private boolean gapWritten;
    private long noPitchSince = -1;
    private boolean captureAudio;
    private File rawAudioFile;
    private File audioFile;
    private FileOutputStream rawAudioStream;
    private MediaPlayer mediaPlayer;
    private boolean playbackPreparing;
    private boolean seekingPlaybackGraph;
    private Object systemBackCallback;
    private final Runnable playbackTicker=new Runnable(){@Override public void run(){if(mediaPlayer!=null&&mediaPlayer.isPlaying()){pitchView.setPlaybackState(true,mediaPlayer.getCurrentPosition(),mediaPlayer.getDuration());main.postDelayed(this,60);}}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pitchView = new PitchView(this);
        boolean dark = pitchView.darkPalette;
        applySystemBars(dark);
        setContentView(pitchView);
        if (android.os.Build.VERSION.SDK_INT >= 33) systemBackCallback=BackApi33.register(this,this::handleSystemBack);
    }

    private void handleSystemBack() {
        if (pitchView != null && pitchView.handleBack()) return;
        new AlertDialog.Builder(this)
            .setTitle("退出明音？")
            .setMessage(recording ? "退出后将结束当前录音。" : "确定要退出应用吗？")
            .setNegativeButton("继续使用", null)
            .setPositiveButton("退出", (dialog, which) -> finish())
            .show();
    }

    @Override public void onBackPressed() { handleSystemBack(); }

    private void applySystemBars(boolean dark) {
        int barColor = Color.parseColor(dark ? "#111A22" : "#F7F6FD");
        Window window = getWindow();
        window.setStatusBarColor(barColor);
        window.setNavigationBarColor(barColor);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            window.getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void togglePalette() {
        pitchView.darkPalette = !pitchView.darkPalette;
        getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("dark", pitchView.darkPalette).apply();
        pitchView.applyPalette();
        applySystemBars(pitchView.darkPalette);
        pitchView.invalidate();
    }

    private void toggleListening() {
        if (recording) { stopListening(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 41);
            return;
        }
        startListening(pitchView.mode == 2);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 41 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening(pitchView.mode == 2);
        else if (pitchView != null) pitchView.setMessage("需要麦克风权限才能检测音高");
    }

    private void startListening(boolean saveAudio) {
        if (recording) return;
        captureAudio=saveAudio;
        recording = true;
        sessionStarted = System.currentTimeMillis();
        activeTrack.clear(); lastTrackWrite = 0; gapWritten = false; noPitchSince = -1;
        if (captureAudio) {
            try {
                rawAudioFile=new File(getCacheDir(),"pitch-recording-"+sessionStarted+".pcm");
                audioFile=new File(getFilesDir(),"pitch-recording-"+sessionStarted+".wav");
                rawAudioStream=new FileOutputStream(rawAudioFile);
            } catch(IOException ex) { recording=false; pitchView.setMessage("无法创建录音文件"); return; }
        }
        pitchView.setListening(true);
        pitchView.beginSession();
        pitchView.setMessage("请自由哼唱");
        audioThread = new Thread(this::captureLoop, "pitch-audio-worker");
        audioThread.start();
    }

    private void stopListening() {
        recording = false;
        AudioRecord r = audioRecord;
        if (r != null) {
            try { r.stop(); } catch (IllegalStateException ignored) { }
        }
        pitchView.setListening(false);
        Thread worker=audioThread;
        if(worker!=null&&worker!=Thread.currentThread()){try{worker.join(1200);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}
        if (!captureAudio && !activeTrack.isEmpty()) main.post(this::saveSession);
    }

    private void captureLoop() {
        final int sampleRate = 44100;
        final long captureSession=sessionStarted;
        final int frameSize = 2048;
        AudioRecord recorder = null;
        try {
            int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(min, frameSize * 4);
            // MIC is the normal capture path for a close-range vocal monitor. UNPROCESSED
            // can bypass device input gain on some phones, making quiet humming too weak.
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            audioRecord = recorder;
            recorder.startRecording();
            short[] frame = new short[frameSize];
            final int hopSize = frameSize / 2;
            float[] samples = new float[frameSize];
            int filled = 0;
            float smoothedHz = -1f;
            int missedFrames = 0;
            float jumpCandidateHz=-1f;
            int jumpCandidateFrames=0;
            while (recording) {
                while (recording && filled < frameSize) {
                    int count = recorder.read(frame, filled, frameSize - filled, AudioRecord.READ_BLOCKING);
                    if (count > 0) {
                        if(captureAudio&&rawAudioStream!=null)writePcm(frame,filled,count);
                        filled += count;
                    }
                }
                if (!recording) break;
                double energy = 0;
                for (int i = 0; i < frameSize; i++) {
                    samples[i] = frame[i] / 32768f;
                    energy += samples[i] * samples[i];
                }
                float rms = (float)Math.sqrt(energy / frameSize);
                // Lower gate keeps soft humming frames available to the pitch estimator.
                PitchResult result = rms < 0.0015f ? null : Yin.detect(samples, sampleRate);
                if (result != null && result.confidence > 0.72f) {
                    missedFrames = 0;
                    if(smoothedHz<0){smoothedHz=result.hz;jumpCandidateHz=-1;jumpCandidateFrames=0;}
                    else {
                        float semitoneJump=(float)(12*Math.log(result.hz/smoothedHz)/Math.log(2));
                        if(Math.abs(semitoneJump)>2.5f){
                            float candidateDelta=jumpCandidateHz>0?(float)(12*Math.log(result.hz/jumpCandidateHz)/Math.log(2)):Float.MAX_VALUE;
                            if(Math.abs(candidateDelta)<0.8f)jumpCandidateFrames++;else{jumpCandidateHz=result.hz;jumpCandidateFrames=1;}
                            if(jumpCandidateFrames>=3){smoothedHz=jumpCandidateHz;jumpCandidateHz=-1;jumpCandidateFrames=0;}
                        }else{
                            jumpCandidateHz=-1;jumpCandidateFrames=0;
                            // Log-frequency smoothing damps small pitch jitter without favoring high notes.
                            smoothedHz=(float)(smoothedHz*Math.pow(2.0,semitoneJump*0.20/12.0));
                        }
                    }
                    final float hz = smoothedHz;
                    final float confidence = result.confidence;
                    long sampleTime = System.currentTimeMillis() - sessionStarted;
                    main.post(() -> { if (sessionStarted==captureSession) pitchView.setPitch(hz, confidence, sampleTime); });
                } else {
                    // Keep the tracker's previous state through brief weak/noisy frames.
                    jumpCandidateHz=-1;jumpCandidateFrames=0;
                    if (++missedFrames >= 8) smoothedHz = -1f;
                    long sampleTime = System.currentTimeMillis() - sessionStarted;
                    main.post(() -> { if (sessionStarted==captureSession) pitchView.setNoPitch(sampleTime); });
                }
                // Half-frame overlap updates the display about every 23 ms instead of 46 ms.
                System.arraycopy(frame, hopSize, frame, 0, frameSize - hopSize);
                filled = frameSize - hopSize;
            }
        } catch (SecurityException | IllegalStateException e) {
            main.post(() -> { recording = false; pitchView.setListening(false); pitchView.setMessage("无法读取麦克风，请重试"); });
        } finally {
            audioRecord = null;
            if (recorder != null) { try { recorder.release(); } catch (Exception ignored) { } }
            if(captureAudio)finishAudioFile();
        }
    }

    private void writePcm(short[] samples,int offset,int count) {
        try { byte[] bytes=new byte[count*2];for(int i=0;i<count;i++){short value=samples[offset+i];bytes[i*2]=(byte)(value&0xff);bytes[i*2+1]=(byte)((value>>8)&0xff);}rawAudioStream.write(bytes); }
        catch(IOException ignored) { }
    }

    private void finishAudioFile() {
        try {
            if(rawAudioStream!=null){rawAudioStream.flush();rawAudioStream.close();rawAudioStream=null;}
            if(rawAudioFile==null||audioFile==null||!rawAudioFile.exists()||rawAudioFile.length()<2)return;
            long dataLength=rawAudioFile.length();
            try(OutputStream out=new FileOutputStream(audioFile);FileInputStream in=new FileInputStream(rawAudioFile)){
                writeWavHeader(out,dataLength,44100,1,16);byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))>0)out.write(buffer,0,count);
            }
            rawAudioFile.delete();
            long duration=Math.max(0,System.currentTimeMillis()-sessionStarted);
            AudioSession saved=new AudioSession(sessionStarted,duration,"录音 "+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new java.util.Date(sessionStarted)),audioFile.getName());
            main.post(()->{saved.points.addAll(activeTrack);saveAudioSession(saved);});
        } catch(IOException ignored) { main.post(()->pitchView.setMessage("录音保存失败，请重试")); }
    }

    private void writeWavHeader(OutputStream out,long dataLength,int sampleRate,int channels,int bits) throws IOException {
        long byteRate=(long)sampleRate*channels*bits/8;out.write(new byte[]{'R','I','F','F'});writeLeInt(out,(int)(36+dataLength));out.write(new byte[]{'W','A','V','E','f','m','t',' '});writeLeInt(out,16);writeLeShort(out,1);writeLeShort(out,channels);writeLeInt(out,sampleRate);writeLeInt(out,(int)byteRate);writeLeShort(out,channels*bits/8);writeLeShort(out,bits);out.write(new byte[]{'d','a','t','a'});writeLeInt(out,(int)dataLength);
    }
    private void writeLeInt(OutputStream out,int v)throws IOException{out.write(v&255);out.write((v>>8)&255);out.write((v>>16)&255);out.write((v>>24)&255);}
    private void writeLeShort(OutputStream out,int v)throws IOException{out.write(v&255);out.write((v>>8)&255);}

    private void saveSession() {
        try {
            SharedPreferences prefs = getSharedPreferences("pitch_history", MODE_PRIVATE);
            JSONArray sessions = new JSONArray(prefs.getString("sessions", "[]"));
            JSONObject session = new JSONObject();
            session.put("started", sessionStarted); session.put("duration", System.currentTimeMillis() - sessionStarted);
            JSONArray points = new JSONArray();
            for (TrackPoint point : activeTrack) {
                JSONArray pair = new JSONArray(); pair.put(point.time); pair.put(point.midi); points.put(pair);
            }
            session.put("points", points); sessions.put(session);
            while (sessions.length() > 30) sessions = removeFirst(sessions);
            prefs.edit().putString("sessions", sessions.toString()).apply();
        } catch (Exception ignored) { }
    }

    private JSONArray removeFirst(JSONArray source) throws Exception {
        JSONArray copy = new JSONArray(); for (int i = 1; i < source.length(); i++) copy.put(source.get(i)); return copy;
    }

    private ArrayList<TrackSession> loadSessions() {
        ArrayList<TrackSession> result = new ArrayList<>();
        try {
            JSONArray sessions = new JSONArray(getSharedPreferences("pitch_history", MODE_PRIVATE).getString("sessions", "[]"));
            for (int i = sessions.length() - 1; i >= 0; i--) {
                JSONObject s = sessions.getJSONObject(i); TrackSession item = new TrackSession(s.getLong("started"), s.getLong("duration"));
                JSONArray points = s.getJSONArray("points");
                for (int j = 0; j < points.length(); j++) { JSONArray pair = points.getJSONArray(j); item.points.add(new TrackPoint(pair.getLong(0), (float) pair.getDouble(1))); }
                result.add(item);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private void saveAudioSession(AudioSession item) {
        try {
            JSONArray sessions=new JSONArray(getSharedPreferences("pitch_audio",MODE_PRIVATE).getString("sessions","[]"));
            JSONObject saved=new JSONObject();saved.put("id",item.id);saved.put("duration",item.duration);saved.put("name",item.name);saved.put("file",item.fileName);
            JSONArray points=new JSONArray();for(TrackPoint point:item.points){JSONArray pair=new JSONArray();pair.put(point.time);pair.put(point.midi);points.put(pair);}saved.put("points",points);sessions.put(saved);
            while(sessions.length()>30)sessions=removeFirst(sessions);
            getSharedPreferences("pitch_audio",MODE_PRIVATE).edit().putString("sessions",sessions.toString()).apply();
            pitchView.refreshRecordings();
        }catch(Exception ignored){pitchView.setMessage("录音信息保存失败");}
    }

    private ArrayList<AudioSession> loadAudioSessions() {
        ArrayList<AudioSession> result=new ArrayList<>();
        try{JSONArray sessions=new JSONArray(getSharedPreferences("pitch_audio",MODE_PRIVATE).getString("sessions","[]"));
            for(int i=sessions.length()-1;i>=0;i--){JSONObject o=sessions.getJSONObject(i);AudioSession item=new AudioSession(o.getLong("id"),o.getLong("duration"),o.getString("name"),o.getString("file"));JSONArray points=o.getJSONArray("points");for(int j=0;j<points.length();j++){JSONArray pair=points.getJSONArray(j);item.points.add(new TrackPoint(pair.getLong(0),(float)pair.getDouble(1)));}result.add(item);}
        }catch(Exception ignored){}return result;
    }

    private void renameAudioSession(long id,String name){try{SharedPreferences prefs=getSharedPreferences("pitch_audio",MODE_PRIVATE);JSONArray old=new JSONArray(prefs.getString("sessions","[]")),updated=new JSONArray();for(int i=0;i<old.length();i++){JSONObject item=old.getJSONObject(i);if(item.getLong("id")==id)item.put("name",name);updated.put(item);}prefs.edit().putString("sessions",updated.toString()).apply();pitchView.refreshRecordings();}catch(Exception ignored){}}
    private void deleteAudioSession(AudioSession target){try{SharedPreferences prefs=getSharedPreferences("pitch_audio",MODE_PRIVATE);JSONArray old=new JSONArray(prefs.getString("sessions","[]")),updated=new JSONArray();for(int i=0;i<old.length();i++){JSONObject item=old.getJSONObject(i);if(item.getLong("id")==target.id){new File(getFilesDir(),item.getString("file")).delete();continue;}updated.put(item);}prefs.edit().putString("sessions",updated.toString()).apply();pitchView.refreshRecordings();}catch(Exception ignored){}}
    private void deleteTrackSession(long id){try{SharedPreferences prefs=getSharedPreferences("pitch_history",MODE_PRIVATE);JSONArray old=new JSONArray(prefs.getString("sessions","[]")),updated=new JSONArray();for(int i=0;i<old.length();i++){JSONObject item=old.getJSONObject(i);if(item.getLong("started")!=id)updated.put(item);}prefs.edit().putString("sessions",updated.toString()).apply();pitchView.refreshTrackHistory();}catch(Exception ignored){}}

    private void stopPlayback(){main.removeCallbacks(playbackTicker);playbackPreparing=false;if(mediaPlayer!=null){try{mediaPlayer.stop();}catch(Exception ignored){}mediaPlayer.release();mediaPlayer=null;}pitchView.setPlaybackState(false,0,0);}
    private void togglePlayback(AudioSession item){
        try{
            if(mediaPlayer!=null){if(playbackPreparing)return;if(mediaPlayer.isPlaying()){mediaPlayer.pause();pitchView.setPlaybackState(false,mediaPlayer.getCurrentPosition(),mediaPlayer.getDuration());}else{if(mediaPlayer.getCurrentPosition()>=mediaPlayer.getDuration())mediaPlayer.seekTo(0);mediaPlayer.start();pitchView.setPlaybackState(true,mediaPlayer.getCurrentPosition(),mediaPlayer.getDuration());main.removeCallbacks(playbackTicker);main.post(playbackTicker);}return;}
            mediaPlayer=new MediaPlayer();mediaPlayer.setDataSource(new File(getFilesDir(),item.fileName).getAbsolutePath());
            playbackPreparing=true;mediaPlayer.setOnPreparedListener(player->{playbackPreparing=false;if(pitchView.playbackPosition>0)player.seekTo((int)pitchView.playbackPosition);player.start();pitchView.setPlaybackState(true,player.getCurrentPosition(),player.getDuration());main.removeCallbacks(playbackTicker);main.post(playbackTicker);});
            mediaPlayer.setOnCompletionListener(player->{pitchView.setPlaybackState(false,player.getDuration(),player.getDuration());});
            mediaPlayer.prepareAsync();
        }catch(Exception ex){stopPlayback();pitchView.setMessage("无法播放这段录音");}
    }
    private void seekPlayback(long position){if(mediaPlayer!=null){if(playbackPreparing){pitchView.setPlaybackState(false,position,pitchView.selectedRecording==null?pitchView.playbackDuration:pitchView.selectedRecording.duration);return;}try{mediaPlayer.seekTo((int)position);pitchView.setPlaybackState(mediaPlayer.isPlaying(),position,mediaPlayer.getDuration());}catch(IllegalStateException ignored){}}else if(pitchView.selectedRecording!=null)pitchView.setPlaybackState(false,position,pitchView.selectedRecording.duration);}
    private void seekAndPlay(AudioSession item,long position){seekPlayback(position);if(playbackPreparing)return;if(mediaPlayer==null||!mediaPlayer.isPlaying())togglePlayback(item);}
    private void confirmDeleteAudio(AudioSession item){new AlertDialog.Builder(this).setTitle("删除录音？").setMessage("音频和对应的音高轨迹都会删除。此操作无法撤销。") .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{if(pitchView.selectedRecording!=null&&pitchView.selectedRecording.id==item.id)stopPlayback();deleteAudioSession(item);pitchView.selectedRecording=null;}).show();}
    private void renameAudio(AudioSession item){EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_TEXT);input.setText(item.name);new AlertDialog.Builder(this).setTitle("重命名录音").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String name=input.getText().toString().trim();if(!name.isEmpty()){renameAudioSession(item.id,name);item.name=name;pitchView.invalidate();}}).show();}
    private void confirmDeleteTrack(TrackSession item){new AlertDialog.Builder(this).setTitle("删除音高轨迹？").setMessage("此记录将从历史列表中删除。") .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->deleteTrackSession(item.started)).show();}

    private static class TrackPoint { final long time; final float midi; TrackPoint(long t, float m) { time=t; midi=m; } }
    private static class TrackSession { final long started, duration; final ArrayList<TrackPoint> points = new ArrayList<>(); TrackSession(long s,long d){started=s;duration=d;} }
    private static class AudioSession { final long id,duration;String name;final String fileName;final ArrayList<TrackPoint> points=new ArrayList<>();AudioSession(long i,long d,String n,String f){id=i;duration=d;name=n;fileName=f;} }

    @Override protected void onDestroy() {
        if (recording) stopListening();
        recording = false;
        AudioRecord r = audioRecord;
        if (r != null) { try { r.stop(); } catch (Exception ignored) { } }
        stopPlayback();if(android.os.Build.VERSION.SDK_INT>=33&&systemBackCallback!=null)BackApi33.unregister(this,systemBackCallback);super.onDestroy();
    }

    private static final class BackApi33 {
        static Object register(Activity activity,Runnable action){OnBackInvokedCallback callback=action::run;activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);return callback;}
        static void unregister(Activity activity,Object callback){activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback)callback);}
    }

    private static class PitchResult { final float hz, confidence; PitchResult(float h, float c) { hz=h; confidence=c; } }

    /** YIN difference function with cumulative mean normalization and parabolic refinement. */
    private static class Yin {
        static PitchResult detect(float[] x, int sr) {
            int minTau = sr / 1000; // 1000 Hz
            int maxTau = sr / 75;   // 75 Hz
            float[] cmnd = new float[maxTau + 1];
            double running = 0;
            int usable = x.length - maxTau;
            for (int tau = 1; tau <= maxTau; tau++) {
                double diff = 0;
                for (int i = 0; i < usable; i += 2) {
                    double d = x[i] - x[i + tau];
                    diff += d * d;
                }
                running += diff;
                cmnd[tau] = running <= 1e-12 ? 1f : (float)(diff * tau / running);
            }
            int candidate = -1;
            for (int tau = minTau; tau <= maxTau; tau++) {
                if (cmnd[tau] < 0.16f) {
                    while (tau + 1 <= maxTau && cmnd[tau + 1] < cmnd[tau]) tau++;
                    candidate = tau;
                    break;
                }
            }
            if (candidate < 0) {
                float best = 0.24f;
                for (int tau = minTau; tau <= maxTau; tau++) if (cmnd[tau] < best) { best = cmnd[tau]; candidate = tau; }
            }
            if (candidate < 0 || candidate <= 1 || candidate >= maxTau) return null;
            float a=cmnd[candidate-1], b=cmnd[candidate], c=cmnd[candidate+1];
            float denom = a - 2*b + c;
            float shift = Math.abs(denom) < 1e-8f ? 0 : 0.5f * (a-c) / denom;
            float refinedTau = candidate + Math.max(-0.5f, Math.min(0.5f, shift));
            return new PitchResult(sr / refinedTau, Math.max(0, Math.min(1, 1-b)));
        }
    }

    private class PitchView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final ArrayDeque<TrackPoint> history = new ArrayDeque<>();
        private boolean listening;
        private int mode = 0;
        private float hz = -1, confidence;
        private String message = "点击开始，跟着自己的声音看音高";
        private long lastSample;
        private boolean showingHistory;
        private float historyScrollOffset, lastTouchY;
        private TrackSession selectedSession;
        private int selectedPhrase = -1;
        private ArrayList<TrackSession> savedSessions = new ArrayList<>();
        private ArrayList<AudioSession> recordings = new ArrayList<>();
        private AudioSession selectedRecording;
        private boolean playbackDetail;
        private boolean isPlaying;
        private long playbackPosition, playbackDuration;
        private float touchDownX,touchDownY;
        private boolean darkPalette;
        private int bg, surface, line, minorLine, ink, sub, green, mint, onAccent, danger, dangerSurface, recordingRed;

        PitchView(Activity a) {
            super(a); density=getResources().getDisplayMetrics().density;
            SharedPreferences appearance=a.getSharedPreferences("appearance", MODE_PRIVATE);
            darkPalette=appearance.contains("dark") ? appearance.getBoolean("dark", false) : (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
            applyPalette();
        }
        private void applyPalette(){
            bg=Color.parseColor(darkPalette?"#111A22":"#F7F6FD");
            surface=Color.parseColor(darkPalette?"#1B2933":"#FFFFFF");
            line=Color.parseColor(darkPalette?"#263D49":"#E7E4F6");
            minorLine=Color.parseColor(darkPalette?"#1B2933":"#F0EEF8");
            ink=Color.parseColor(darkPalette?"#F0F7F8":"#2F2B44");
            sub=Color.parseColor(darkPalette?"#9AADB5":"#706D84");
            green=Color.parseColor(darkPalette?"#53C8E8":"#6F63C4");
            onAccent=Color.parseColor(darkPalette?"#06141B":"#FFFFFF");
            mint=blend(green,surface,darkPalette?0.18f:0.12f);
            danger=Color.parseColor(darkPalette?"#F1A0A9":"#B34056");
            dangerSurface=blend(danger,surface,darkPalette?0.18f:0.10f);
            recordingRed=Color.parseColor(darkPalette?"#FF9A9A":"#B34056");
        }
        private int blend(int accent,int base,float amount){return Color.rgb((int)(Color.red(base)*(1-amount)+Color.red(accent)*amount),(int)(Color.green(base)*(1-amount)+Color.green(accent)*amount),(int)(Color.blue(base)*(1-amount)+Color.blue(accent)*amount));}
        void setListening(boolean value) { listening=value; if (!value) message="点击开始，跟着自己的声音看音高"; invalidate(); }
        void setMessage(String value) { message=value; invalidate(); }
        void beginSession() { history.clear(); selectedSession=null; showingHistory=false; lastSample=System.currentTimeMillis(); invalidate(); }
        void refreshRecordings(){recordings=((MainActivity)getContext()).loadAudioSessions();invalidate();}
        void refreshTrackHistory(){savedSessions=((MainActivity)getContext()).loadSessions();selectedSession=null;showingHistory=true;invalidate();}
        void setPlaybackState(boolean playing,long position,long duration){isPlaying=playing;playbackPosition=position;playbackDuration=duration;invalidate();}
        void setNoPitch(long time) {
            if (noPitchSince < 0) noPitchSince=time;
            if (!gapWritten && time-noPitchSince>=450) { TrackPoint gap=new TrackPoint(noPitchSince,-1);activeTrack.add(gap);history.addLast(gap);gapWritten=true; }
            if (System.currentTimeMillis()-lastSample>250) { message="保持上次音高"; invalidate(); }
        }
        void setPitch(float f, float c, long time) {
            hz=f; confidence=c; message=c>.86f?"音高稳定":"正在识别音高";
            float midi=frequencyToMidi(f);
            boolean longPause=noPitchSince>=0&&time-noPitchSince>=450;
            if(longPause&&!gapWritten){TrackPoint gap=new TrackPoint(noPitchSince,-1);activeTrack.add(gap);history.addLast(gap);}
            long writeInterval=captureAudio?46:100;
            if(time-lastTrackWrite>=writeInterval||longPause){TrackPoint point=new TrackPoint(time,midi);activeTrack.add(point);history.addLast(point);lastTrackWrite=time;}
            gapWritten=false;noPitchSince=-1;while(!history.isEmpty()&&time-history.peekFirst().time>12000)history.removeFirst();
            lastSample=System.currentTimeMillis();invalidate();
        }

        @Override protected void onDraw(Canvas raw) {
            super.onDraw(raw);
            Canvas c=raw; c.save(); c.scale(density,density);
            float w=getWidth()/density, h=getHeight()/density;
            c.drawColor(bg);
            if(!(mode==2&&playbackDetail)){text(c,"明音",24,39,16,ink,true);text(c,"· 实时音高",67,39,12,sub,false);round(c,new RectF(w-118,20,w-18,51),15,surface);textCenteredFit(c,darkPalette?"切换亮色":"切换暗色",w-68,39,11,9,88,green,true);}
            if(showingHistory){drawHistoryScreen(c,w,h);c.restore();return;}
            if(mode==2&&playbackDetail&&selectedRecording!=null){drawRecordingDetail(c,w,h);c.restore();return;}
            drawTabs(c,w);
            if(mode==2){drawRecordingPage(c,w,h);c.restore();return;}
            boolean has=hz>0;
            float midi=has?frequencyToMidi(hz):0;
            String note=has?noteName(midi):"—";
            if(mode==0) drawFocus(c,w,h,has,midi,note); else drawTrail(c,w,h,has,midi,note);
            drawBottom(c,w,h);
            c.restore();
        }

        private void drawTabs(Canvas c,float w) {
            float y=65, left=20, right=w-20, third=(right-left)/3f;
            round(c,new RectF(left,y,right,y+46),16,surface);
            float selectedLeft=left+third*mode;
            round(c,new RectF(selectedLeft+3,y+3,selectedLeft+third-3,y+43),13,green);
            textCenteredFit(c,"音高聚焦",left+third/2,y+29,11,9,third-18,mode==0?onAccent:sub,mode==0);
            textCenteredFit(c,"音高轨迹",left+third*1.5f,y+29,11,9,third-18,mode==1?onAccent:sub,mode==1);
            textCenteredFit(c,"录音回放",left+third*2.5f,y+29,11,9,third-18,mode==2?onAccent:sub,mode==2);
        }

        private void drawFocus(Canvas c,float w,float h,boolean has,float midi,String note) {
            textCentered(c,"当前音高",w/2,147,12,sub,false);
            textCentered(c,note,w/2,235,70,ink,true);
            textCentered(c,has?String.format(Locale.US,"%.1f Hz",hz):"等待声音",w/2,263,14,sub,false);
            float cents=has?(midi-Math.round(midi))*100:0;
            drawGauge(c,w,305,cents,has);
            round(c,new RectF(w/2-79,402,w/2+79,430),14,mint);
            String status=has?String.format(Locale.CHINA,"%s · %.0f%%",message.equals("正在识别音高")?"识别中":message,confidence*100):(listening?"等待声音":"点击开始监听");
            textCenteredFit(c,status,w/2,421,10,8,142,green,false);
            float top=h-236;
            round(c,new RectF(18,top,w-18,top+116),18,surface);
            text(c,"最近音高",34,top+24,13,ink,false); text(c,"近 8 秒走势",w-95,top+24,11,sub,false);
            drawMiniHistory(c,new RectF(32,top+40,w-32,top+102),midi,has);
        }

        private void drawGauge(Canvas c,float w,float y,float cents,boolean has) {
            text(c,"偏低",24,y,11,sub,false); textCentered(c,"标准音高",w/2,y,11,sub,false); textRight(c,"偏高",w-24,y,11,sub,false);
            float left=28,right=w-28,cy=y+39;
            p.setColor(line); p.setStrokeWidth(2); c.drawLine(left,cy,right,cy,p);
            for(int i=0;i<=10;i++){float x=left+(right-left)*i/10f; p.setColor(i==5?sub:line); p.setStrokeWidth(i==5?1.5f:1); c.drawLine(x,cy-7,x,cy+7,p);}
            p.setColor(green); p.setStrokeWidth(3); float x=has?w/2+Math.max(-1,Math.min(1,cents/50f))*(right-left)/2:w/2; c.drawLine(x,cy-20,x,cy+20,p); p.setStrokeWidth(1);
            textCentered(c,has?String.format(Locale.US,"%+.0f 音分",cents):"— 音分",w/2,y+82,14,has?green:sub,true);
        }

        private void drawMiniHistory(Canvas c,RectF r,float current,boolean has) {
            p.setColor(line); p.setStrokeWidth(1); c.drawLine(r.left,r.centerY(),r.right,r.centerY(),p);
            if(history.size()<2)return;
            float low=has?current-1.3f:58, high=has?current+1.3f:82;
            p.setColor(green); p.setStrokeWidth(2.3f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            TrackPoint prev=null; int i=0,n=history.size();
            for(TrackPoint point:history){if(point.midi<0){prev=null;i++;continue;}if(prev!=null)c.drawLine(r.left+(i-1)*(r.width()/(n-1)),map(prev.midi,low,high,r.bottom,r.top),r.left+i*(r.width()/(n-1)),map(point.midi,low,high,r.bottom,r.top),p);prev=point;i++;}
            p.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawTrail(Canvas c,float w,float h,boolean has,float midi,String note) {
            float top=138;
            text(c,has?note:"—",24,top+46,43,ink,true);
            text(c,has?String.format(Locale.US,"%.1f Hz",hz):"等待声音",26,top+70,13,sub,false);
            float cents=has?(midi-Math.round(midi))*100:0;
            textRight(c,has?String.format(Locale.US,"%+.0f 音分",cents):"— 音分",w-24,top+40,14,green,true); textRight(c,has?"相对标准音":message,w-24,top+63,11,sub,false);
            float chartTop=top+92, chartBottom=h-112;
            RectF box=new RectF(18,chartTop,w-18,chartBottom);
            round(c,box,18,surface);
            text(c,"音高走势",34,chartTop+24,13,ink,false); textRight(c,listening?"实时 · 历史":"历史记录",w-34,chartTop+24,11,green,false);
            float left=50,right=w-30,plotTop=chartTop+45,plotBottom=chartBottom-29;
            float low=has?Math.max(36,Math.min(84,midi-12)):60, high=low+24;
            for(int m=(int)Math.ceil(low/3)*3;m<=high;m+=3){float y=map(m,low,high,plotBottom,plotTop);p.setColor(line);p.setStrokeWidth(1);c.drawLine(left,y,right,y,p);text(c,noteName(m),25,y+4,10,sub,false);}
            if(history.size()>1){long now=System.currentTimeMillis()-sessionStarted,start=Math.max(0,now-8000);TrackPoint prev=null;p.setColor(green);p.setStrokeWidth(2.4f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);for(TrackPoint point:history){if(point.time<start){prev=point;continue;}if(point.midi<0){prev=null;continue;}if(prev!=null&&prev.midi>=0){float x1=left+(right-left)*(prev.time-start)/8000f,x2=left+(right-left)*(point.time-start)/8000f;c.drawLine(x1,map(prev.midi,low,high,plotBottom,plotTop),x2,map(point.midi,low,high,plotBottom,plotTop),p);}prev=point;}p.setStrokeCap(Paint.Cap.BUTT);}
            text(c,"最近 8 秒",left,chartBottom-9,10,sub,false);textRight(c,"现在",right,chartBottom-9,10,sub,false);
        }

        private void drawHistoryScreen(Canvas c,float w,float h) {
            text(c,"‹ 返回",24,83,13,green,true);textCentered(c,selectedSession==null?"历史轨迹":"整段音高轨迹",w/2,83,16,ink,true);
            if(selectedSession==null){
                savedSessions=((MainActivity)getContext()).loadSessions();
                if(savedSessions.isEmpty()){textCentered(c,"还没有记录",w/2,h/2,16,sub,false);textCentered(c,"停止监听后会自动保存本次音高轨迹",w/2,h/2+28,11,sub,false);return;}
                c.save();c.clipRect(12,112,w-12,h-20);float y=120-historyScrollOffset;for(TrackSession s:savedSessions){if(y>90&&y<h-10){RectF row=new RectF(20,y,w-20,y+66);round(c,row,14,surface);text(c,new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new java.util.Date(s.started)),34,y+27,13,ink,true);text(c,String.format(Locale.CHINA,"%.1f 秒 · %d 个采样",s.duration/1000f,s.points.size()),34,y+49,11,sub,false);textRight(c,"打开",w-66,y+39,9,green,false);textRight(c,"×",w-27,y+40,14,danger,true);}y+=76;}c.restore();
            }else{
                ArrayList<ArrayList<TrackPoint>> phrases=splitPhrases(selectedSession.points);
                ArrayList<TrackPoint> visible=selectedPhrase>=0&&selectedPhrase<phrases.size()?phrases.get(selectedPhrase):selectedSession.points;
                long firstTime=0,lastTime=Math.max(1000,selectedSession.duration);
                if(selectedPhrase>=0&&selectedPhrase<phrases.size()&&!visible.isEmpty()){firstTime=visible.get(0).time;lastTime=Math.max(firstTime+1000,visible.get(visible.size()-1).time);}
                long duration=Math.max(1000,lastTime-firstTime);RectF box=new RectF(18,130,w-18,h-160);round(c,box,18,surface);
                text(c,selectedPhrase<0?String.format(Locale.CHINA,"%.1f 秒 · 整段轨迹",selectedSession.duration/1000f):String.format(Locale.CHINA,"第 %d 段 · %.1f 秒",selectedPhrase+1,duration/1000f),32,158,13,ink,false);
                float minMidi=127,maxMidi=0;for(TrackPoint point:visible)if(point.midi>=0){minMidi=Math.min(minMidi,point.midi);maxMidi=Math.max(maxMidi,point.midi);}if(maxMidi<minMidi){minMidi=60;maxMidi=84;}
                float low=Math.max(24,(float)Math.floor(minMidi)-2),high=Math.min(108,(float)Math.ceil(maxMidi)+2);if(high-low<12){float center=(high+low)/2;low=center-6;high=center+6;}
                float left=52,right=w-30,top=185,bottom=box.bottom-35;
                for(int m=(int)Math.ceil(low/3)*3;m<=high;m+=3){float y=map(m,low,high,bottom,top);p.setColor(line);p.setStrokeWidth(1);c.drawLine(left,y,right,y,p);text(c,noteName(m),24,y+4,10,sub,false);}
                TrackPoint prev=null;p.setColor(green);p.setStrokeWidth(2.4f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
                for(TrackPoint point:visible){if(point.midi<0){prev=null;continue;}if(prev!=null){float x1=left+(right-left)*(prev.time-firstTime)/duration,x2=left+(right-left)*(point.time-firstTime)/duration;c.drawLine(x1,map(prev.midi,low,high,bottom,top),x2,map(point.midi,low,high,bottom,top),p);}prev=point;}
                p.setStrokeCap(Paint.Cap.BUTT);text(c,"开头",left,box.bottom-12,10,sub,false);textRight(c,"结束",right,box.bottom-12,10,sub,false);
                String phraseLabel=selectedPhrase<0?"整段 · 共 "+phrases.size()+" 段（点此切换分段）":"第 "+(selectedPhrase+1)+" 段 · 共 "+phrases.size()+" 段（点此切换）";
                text(c,phraseLabel,32,box.bottom+24,12,green,false);
            }
        }

        private void drawRecordingPage(Canvas c,float w,float h){
            if(listening&&((MainActivity)getContext()).captureAudio){
                long elapsed=System.currentTimeMillis()-sessionStarted;
                text(c,"录音中",24,145,22,recordingRed,true);textRight(c,String.format(Locale.US,"%02d:%02d",elapsed/60000,(elapsed/1000)%60),w-24,145,24,green,true);
                text(c,"实时音高",24,178,12,sub,false);textRight(c,hz>0?noteName(frequencyToMidi(hz)):"等待声音",w-24,179,18,ink,true);
                text(c,"频率",24,203,11,sub,false);textRight(c,hz>0?String.format(Locale.US,"%.1f Hz",hz):"— Hz",w-24,203,12,ink,true);
                text(c,"音高采样",24,225,11,sub,false);textRight(c,activeTrack.size()+" 点",w-24,225,12,ink,true);
                RectF card=new RectF(16,239,w-16,h-105);round(c,card,20,surface);
                drawLiveRecordingGraph(c,card,activeTrack,System.currentTimeMillis()-sessionStarted);
                RectF button=new RectF(20,h-82,w-20,h-18);round(c,button,20,dangerSurface);textCentered(c,"停止并保存录音",w/2,h-42,17,danger,true);return;
            }
            recordings=((MainActivity)getContext()).loadAudioSessions();
            textCentered(c,"录音回放",w/2,137,17,ink,true);
            RectF list=new RectF(14,153,w-14,h-112);round(c,list,18,surface);
            c.save();c.clipRect(list.left+4,list.top+4,list.right-4,list.bottom-4);
            if(recordings.isEmpty()){textCentered(c,"还没有录音",w/2,list.centerY()-10,16,sub,false);textCentered(c,"录制后会保存在这里",w/2,list.centerY()+18,11,sub,false);}
            else {float y=list.top+10-historyScrollOffset;
                for(AudioSession item:recordings){if(y+68>list.top&&y<list.bottom){RectF row=new RectF(20,y,w-20,y+68);round(c,row,16,bg);textEllipsized(c,item.name,32,y+25,12,ink,true,w-190);text(c,String.format(Locale.CHINA,"%s · %.1f 秒",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new java.util.Date(item.id)),item.duration/1000f),32,y+48,9,sub,false);round(c,new RectF(w-159,y+12,w-95,y+56),14,mint);textCenteredFit(c,"查看",w-127,y+39,11,9,54,green,true);round(c,new RectF(w-87,y+12,w-23,y+56),14,dangerSurface);textCenteredFit(c,"删除",w-55,y+39,11,9,56,danger,true);}y+=78;}}
            c.restore();
            RectF add=new RectF(20,h-88,w-20,h-20);round(c,add,20,green);textCentered(c,"＋ 新建录音",w/2,h-47,16,onAccent,true);
        }

        private void drawLiveRecordingGraph(Canvas c,RectF box,ArrayList<TrackPoint> points,long elapsed){
            long window=10000,start=Math.max(0,elapsed-window);float min=127,max=0;
            for(TrackPoint pt:points)if(pt.midi>=0&&pt.time>=start){min=Math.min(min,pt.midi);max=Math.max(max,pt.midi);}
            if(max<min){min=48;max=84;}
            float low=Math.max(24,(float)Math.floor(min)-2),high=Math.min(108,(float)Math.ceil(max)+2);if(high-low<12){float center=(high+low)/2;low=center-6;high=center+6;}
            float left=box.left+38,right=box.right-14,top=box.top+32,bottom=box.bottom-26;
            text(c,"音高走势",box.left+18,box.top+22,12,ink,true);textRight(c,"最近 10 秒 · 时间从左到右",box.right-16,box.top+22,9,sub,false);
            for(int n=(int)Math.ceil(low);n<=high;n++){float y=map(n,low,high,bottom,top);p.setColor(n%3==0?line:minorLine);p.setStrokeWidth(n%3==0?1.1f:0.6f);c.drawLine(left,y,right,y,p);if(n%3==0)text(c,noteName(n),box.left+7,y+3,8,sub,false);}
            Path path=new Path();boolean active=false;TrackPoint prev=null;float lastX=0,lastY=0;
            for(TrackPoint pt:points){if(pt.time<start)continue;if(pt.midi<0){if(active){path.lineTo(lastX,lastY);active=false;}prev=null;continue;}float x=left+(right-left)*Math.max(0,Math.min(1,(pt.time-start)/(float)window)),y=map(pt.midi,low,high,bottom,top);if(!active){path.moveTo(x,y);active=true;}else{float mx=(lastX+x)/2f,my=(lastY+y)/2f;path.quadTo(lastX,lastY,mx,my);path.lineTo(x,y);}prev=pt;lastX=x;lastY=y;}
            p.setColor(green);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);p.setStrokeCap(Paint.Cap.BUTT);
            text(c,"−10 秒",left,bottom+18,9,sub,false);textRight(c,"现在",right,bottom+18,9,sub,false);
        }

        private void textEllipsized(Canvas c,String value,float x,float y,float size,int color,boolean medium,float maxWidth){
            p.setTextSize(size);p.setTypeface(android.graphics.Typeface.create(medium?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));String shown=value;while(shown.length()>1&&p.measureText(shown)>maxWidth)shown=shown.substring(0,shown.length()-1);if(!shown.equals(value))shown=shown.substring(0,Math.max(1,shown.length()-1))+"…";text(c,shown,x,y,size,color,medium);
        }

        private void drawRecordingDetail(Canvas c,float w,float h){
            text(c,"‹ 录音列表",24,52,14,green,true);textCenteredFit(c,selectedRecording.name,w/2,52,15,12,w-180,ink,true);
            textCentered(c,new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new java.util.Date(selectedRecording.id)),w/2,77,12,sub,false);
            TrackPoint at=pitchAt(selectedRecording,playbackPosition);
            textCentered(c,"当前音高",w/2,104,11,sub,false);
            textCentered(c,at==null?"—":noteName(at.midi),w/2,141,32,ink,true);
            textCentered(c,at==null?"此处未检测到音高":String.format(Locale.US,"%.1f Hz",midiToHz(at.midi)),w/2,165,14,green,true);
            RectF box=new RectF(18,181,w-18,h-238);round(c,box,20,surface);
            float min=127,max=0;for(TrackPoint pt:selectedRecording.points)if(pt.midi>=0){min=Math.min(min,pt.midi);max=Math.max(max,pt.midi);}if(max<min){min=48;max=84;}
            float low=Math.max(24,(float)Math.floor(min)-2),high=Math.min(108,(float)Math.ceil(max)+2);if(high-low<12){float center=(high+low)/2;low=center-6;high=center+6;}
            float left=50,right=w-30,top=box.top+32,bottom=box.bottom-20;
            for(int n=(int)Math.ceil(low/3)*3;n<=high;n+=3){float yy=map(n,low,high,bottom,top);p.setColor(line);p.setStrokeWidth(1);c.drawLine(left,yy,right,yy,p);text(c,noteName(n),22,yy+4,9,sub,false);}
            Path curve=new Path();TrackPoint prev=null;float prevX=0,prevY=0;boolean segment=false;p.setColor(green);p.setStrokeWidth(3);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            long duration=Math.max(1,selectedRecording.duration);for(TrackPoint pt:selectedRecording.points){if(pt.midi<0){if(segment)curve.lineTo(prevX,prevY);segment=false;prev=null;continue;}float px=left+(right-left)*pt.time/duration,py=map(pt.midi,low,high,bottom,top);if(!segment){curve.moveTo(px,py);segment=true;}else{float mx=(prevX+px)/2f,my=(prevY+py)/2f;curve.quadTo(prevX,prevY,mx,my);curve.lineTo(px,py);}prev=pt;prevX=px;prevY=py;}
            p.setStyle(Paint.Style.STROKE);c.drawPath(curve,p);p.setStyle(Paint.Style.FILL);p.setStrokeCap(Paint.Cap.BUTT);float cursor=left+(right-left)*Math.min(playbackPosition,duration)/duration;p.setColor(green);p.setStrokeWidth(1.8f);c.drawLine(cursor,top,cursor,bottom,p);if(at!=null){float markerY=map(at.midi,low,high,bottom,top);circle(c,cursor,markerY,7,surface);circle(c,cursor,markerY,4,green);}
            float seekY=h-205,seekLeft=34,seekRight=w-34;p.setColor(line);p.setStrokeWidth(4);c.drawLine(seekLeft,seekY,seekRight,seekY,p);p.setColor(green);c.drawLine(seekLeft,seekY,seekLeft+(seekRight-seekLeft)*Math.min(1,playbackPosition/(float)Math.max(1,playbackDuration>0?playbackDuration:selectedRecording.duration)),seekY,p);circle(c,seekLeft+(seekRight-seekLeft)*Math.min(1,playbackPosition/(float)Math.max(1,playbackDuration>0?playbackDuration:selectedRecording.duration)),seekY,7,green);
            text(c,formatTime(playbackPosition),34,seekY+24,10,sub,false);textRight(c,formatTime(playbackDuration>0?playbackDuration:selectedRecording.duration),w-34,seekY+24,10,sub,false);
            float controlsY=h-106;round(c,new RectF(12,controlsY-20,w-12,controlsY+34),18,surface);textCenteredFit(c,"改名",w/6,controlsY+13,12,10,w/3-22,sub,false);round(c,new RectF(w/3+6,controlsY-14,w*2/3-6,controlsY+28),14,mint);textCenteredFit(c,isPlaying?"暂停":"播放",w/2,controlsY+13,13,10,w/3-24,green,true);textCenteredFit(c,"删除",w*5/6,controlsY+13,12,10,w/3-22,danger,false);
        }

        private TrackPoint pitchAt(AudioSession item,long time){TrackPoint previous=null;for(TrackPoint pt:item.points){if(pt.midi<0){if(pt.time<=time)previous=null;else return previous;continue;}if(pt.time>time){if(previous!=null&&pt.time-previous.time<300){float t=(time-previous.time)/(float)(pt.time-previous.time);return new TrackPoint(time,previous.midi+(pt.midi-previous.midi)*t);}return previous;}previous=pt;}return previous;}
        private String formatTime(long ms){return String.format(Locale.US,"%02d:%02d",ms/60000,(ms/1000)%60);}

        private ArrayList<ArrayList<TrackPoint>> splitPhrases(ArrayList<TrackPoint> points){
            ArrayList<ArrayList<TrackPoint>> result=new ArrayList<>();ArrayList<TrackPoint> current=null;
            for(TrackPoint point:points){if(point.midi<0){current=null;continue;}if(current==null){current=new ArrayList<>();result.add(current);}current.add(point);}
            return result;
        }

        private void drawBottom(Canvas c,float w,float h) {
            textCenteredFit(c,listening?"自由哼唱 · 实时显示":"点击开始监听",w/2,h-82,11,9,w-48,sub,false);
            RectF button=new RectF(w/2-82,h-68,w/2+82,h-16);
            round(c,button,16,listening?blend(green,bg,0.48f):green);
            p.setColor(onAccent);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);c.drawRoundRect(new RectF(w/2-56,h-53,w/2-47,h-40),5,5,p);c.drawArc(new RectF(w/2-60,h-48,w/2-43,h-33),0,180,false,p);p.setStyle(Paint.Style.FILL);
            textCentered(c,listening?"停止监听":"开始监听",w/2+12,h-35,14,onAccent,true);
        }

        private void round(Canvas c,RectF r,float rad,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRoundRect(r,rad,rad,p);}
        private void circle(Canvas c,float x,float y,float r,int color){p.setColor(color);c.drawCircle(x,y,r,p);}
        private void text(Canvas c,String s,float x,float y,float size,int color,boolean medium){p.setColor(color);p.setTextSize(size);p.setTypeface(android.graphics.Typeface.create(medium?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));p.setTextAlign(Paint.Align.LEFT);c.drawText(s,x,y,p);}
        private void textRight(Canvas c,String s,float x,float y,float size,int color,boolean medium){p.setColor(color);p.setTextSize(size);p.setTypeface(android.graphics.Typeface.create(medium?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));p.setTextAlign(Paint.Align.RIGHT);c.drawText(s,x,y,p);p.setTextAlign(Paint.Align.LEFT);}
        private void textCentered(Canvas c,String s,float x,float y,float size,int color,boolean medium){p.setColor(color);p.setTextSize(size);p.setTypeface(android.graphics.Typeface.create(medium?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y,p);p.setTextAlign(Paint.Align.LEFT);}
        private void textCenteredFit(Canvas c,String s,float x,float y,float size,float minSize,float maxWidth,int color,boolean medium){p.setTypeface(android.graphics.Typeface.create(medium?"sans-serif-medium":"sans-serif",android.graphics.Typeface.NORMAL));p.setTextSize(size);while(p.measureText(s)>maxWidth&&size>minSize){size-=0.5f;p.setTextSize(size);}textCentered(c,s,x,y,size,color,medium);}
        private float[] toArray(){float[] a=new float[history.size()];int i=0;for(TrackPoint f:history)a[i++]=f.midi;return a;}
        private float map(float v,float a,float b,float low,float high){float t=Math.max(0,Math.min(1,(v-a)/(b-a)));return low+(high-low)*t;}

        boolean handleBack(){
            if(showingHistory){if(selectedSession!=null){selectedSession=null;selectedPhrase=-1;}else showingHistory=false;invalidate();return true;}
            if(mode==2&&playbackDetail){leavePlaybackDetail();return true;}
            return false;
        }

        private void leavePlaybackDetail(){
            ((MainActivity)getContext()).stopPlayback();playbackDetail=false;selectedRecording=null;selectedPhrase=-1;invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x=e.getX()/density,y=e.getY()/density,w=getWidth()/density,h=getHeight()/density;
            if(e.getAction()==MotionEvent.ACTION_CANCEL&&seekingPlaybackGraph){seekingPlaybackGraph=false;return true;}
            if(e.getAction()==MotionEvent.ACTION_DOWN){touchDownX=x;touchDownY=y;}
            float dx=x-touchDownX,dy=y-touchDownY;
            if(mode==2&&playbackDetail&&selectedRecording!=null){
                float graphTop=181,graphBottom=h-238;
                if(e.getAction()==MotionEvent.ACTION_DOWN&&y>=graphTop&&y<=graphBottom){seekingPlaybackGraph=true;seekFromGraph(x,w);return true;}
                if(seekingPlaybackGraph&&e.getAction()==MotionEvent.ACTION_MOVE){seekFromGraph(x,w);return true;}
                if(seekingPlaybackGraph&&e.getAction()==MotionEvent.ACTION_UP){seekFromGraph(x,w);seekingPlaybackGraph=false;return true;}
            }
            if(mode==2&&playbackDetail&&selectedRecording!=null&&y>=h-225&&y<=h-175){float ratio=Math.max(0,Math.min(1,(x-34)/(w-68)));long duration=playbackDuration>0?playbackDuration:selectedRecording.duration;((MainActivity)getContext()).seekPlayback((long)(ratio*duration));return true;}
            if(mode==2&&selectedRecording==null&&!listening&&y>=153&&y<=h-112){
                if(e.getAction()==MotionEvent.ACTION_DOWN){lastTouchY=y;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){historyScrollOffset=Math.max(0,Math.min(Math.max(0,recordings.size()*78-(h-270)),historyScrollOffset+lastTouchY-y));lastTouchY=y;invalidate();return true;}
            }
            if(showingHistory&&selectedSession==null){
                if(e.getAction()==MotionEvent.ACTION_DOWN){lastTouchY=y;return true;}
                if(e.getAction()==MotionEvent.ACTION_MOVE){historyScrollOffset=Math.max(0,Math.min(Math.max(0,savedSessions.size()*76-(h-150)),historyScrollOffset+lastTouchY-y));lastTouchY=y;invalidate();return true;}
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE&&mode==2&&selectedRecording==null&&!listening&&touchDownY>=153&&touchDownY<=h-112){historyScrollOffset=Math.max(0,Math.min(Math.max(0,recordings.size()*78-(h-270)),historyScrollOffset+touchDownY-y));touchDownY=y;invalidate();return true;}
            if(e.getAction()!=MotionEvent.ACTION_UP)return true;
            if(!(mode==2&&playbackDetail)&&y>=20&&y<=51&&x>=w-122){((MainActivity)getContext()).togglePalette();return true;}
            if(showingHistory){
                if(y<105){if(selectedSession!=null){selectedSession=null;selectedPhrase=-1;}else showingHistory=false;invalidate();return true;}
                if(selectedSession==null){int index=(int)((y-120+historyScrollOffset)/76);savedSessions=((MainActivity)getContext()).loadSessions();if(index>=0&&index<savedSessions.size()){if(x>w-42)((MainActivity)getContext()).confirmDeleteTrack(savedSessions.get(index));else{selectedSession=savedSessions.get(index);invalidate();}}}
                else if(y>=h-190&&y<=h-120){int count=splitPhrases(selectedSession.points).size();if(count>0)selectedPhrase=selectedPhrase<0?0:(selectedPhrase+1)%count;invalidate();}
                return true;
            }
            if(!(listening&&captureAudio)&&!showingHistory&&!playbackDetail&&Math.abs(dx)>64&&Math.abs(dx)>Math.abs(dy)*1.25f){mode=Math.max(0,Math.min(2,mode+(dx<0?1:-1)));historyScrollOffset=0;invalidate();return true;}
            if(!playbackDetail&&y>=65&&y<=111){if(listening&&captureAudio)return true;mode=Math.max(0,Math.min(2,(int)((x-20)/((w-40)/3))));selectedRecording=null;historyScrollOffset=0;invalidate();return true;}
            if(mode==1&&y>=230&&y<=270&&x>w-140){showingHistory=true;selectedSession=null;invalidate();return true;}
            if(mode==2){
                MainActivity activity=(MainActivity)getContext();
                if(listening&&captureAudio){if(y>=h-100)activity.toggleListening();return true;}
                if(playbackDetail&&selectedRecording!=null){
                    if(y<90){leavePlaybackDetail();return true;}
                    if(y>=h-130&&y<=h-55){if(x<w/3)activity.renameAudio(selectedRecording);else if(x>w*2/3)activity.confirmDeleteAudio(selectedRecording);else activity.togglePlayback(selectedRecording);return true;}
                    return true;
                }
                if(y>=h-88&&y<=h-20){activity.toggleListening();return true;}
                if(y>=153&&y<=h-112){int index=(int)((y-163+historyScrollOffset)/78);if(index>=0&&index<recordings.size()){AudioSession item=recordings.get(index);float rowTop=163+index*78-historyScrollOffset;if(y>=rowTop+12&&y<=rowTop+56&&x>=w-159&&x<=w-95){selectedRecording=item;playbackDetail=true;selectedPhrase=-1;playbackPosition=0;playbackDuration=item.duration;invalidate();}else if(y>=rowTop+12&&y<=rowTop+56&&x>=w-87&&x<=w-23)activity.confirmDeleteAudio(item);return true;}}
                return true;
            }
            if(y>=h-78&&y<=h){((MainActivity)getContext()).toggleListening();return true;}
            return true;
        }
        private static float frequencyToMidi(float f){return (float)(69+12*Math.log(f/440.0)/Math.log(2));}
        private void seekFromGraph(float x,float w){float left=50,right=w-30;float ratio=Math.max(0,Math.min(1,(x-left)/(right-left)));long duration=Math.max(1,selectedRecording.duration);((MainActivity)getContext()).seekAndPlay(selectedRecording,(long)(ratio*duration));}
        private static float midiToHz(float midi){return (float)(440.0*Math.pow(2.0,(midi-69)/12.0));}
        private static String noteName(float midi){String[] names={"C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"};int n=Math.round(midi);return names[Math.floorMod(n,12)]+(Math.floorDiv(n,12)-1);}
    }
}
