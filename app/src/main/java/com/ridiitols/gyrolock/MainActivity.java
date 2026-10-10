package com.ridiitols.gyrolock;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final int REQ = 41;
    private PreviewView previewView;
    private TextView status;
    private Button recordButton;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ExecutorService cameraExecutor;
    private SensorManager sensorManager;
    private Sensor gyro;
    private final List<GyroSample> gyroSamples = new ArrayList<>();
    private long gyroStartNs = 0L;
    private long lastSavedNs = 0L;
    private File sessionDir;
    private File pendingRawFile;
    private File pendingCsvFile;

    static class GyroSample {
        // Sensor timestamps use Android's monotonic clock, which lets us align samples
        // to the recording start rather than relying on callback arrival time.
        final long timestampNs;
        final double x, y, z;
        GyroSample(long timestampNs, double x, double y, double z) {
            this.timestampNs = timestampNs; this.x = x; this.y = y; this.z = z;
        }
        double secondsFrom(long startNs) { return (timestampNs - startNs) / 1_000_000_000.0; }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF171717);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(previewView, new LinearLayout.LayoutParams(-1, 0, 1));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(16, 14, 16, 14);
        status.setText("Kamera • Siap merekam");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        recordButton = new Button(this);
        recordButton.setText("MULAI REKAM");
        recordButton.setTextSize(16);
        recordButton.setOnClickListener(v -> toggleRecording());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(24, 8, 24, 20);
        root.addView(recordButton, bp);
        setContentView(root);

        if (hasPermissions()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);
                status.setText(gyro == null ? "Kamera siap • Sensor gyro tidak tersedia" : "Kamera siap • Stabilisasi gyro aktif");
            } catch (Exception e) {
                status.setText("Kamera gagal dibuka: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleRecording() {
        // A second tap after recording has finished starts processing the saved video.
        if (recording == null && pendingRawFile != null && pendingCsvFile != null) {
            File raw = pendingRawFile;
            File csv = pendingCsvFile;
            pendingRawFile = null;
            pendingCsvFile = null;
            recordButton.setEnabled(false);
            recordButton.setText("MEMPROSES...");
            stabilize(raw, csv);
            return;
        }

        if (recording != null) {
            recordButton.setEnabled(false);
            recordButton.setText("MENYIMPAN...");
            status.setText("Menghentikan rekaman dan menyimpan video asli...");
            try {
                recording.stop();
            } catch (Exception e) {
                recordButton.setEnabled(true);
                recordButton.setText("BERHENTI & STABILKAN");
                status.setText("Gagal menghentikan rekaman: " + e.getMessage());
            }
            return;
        }

        if (videoCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }

        sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            status.setText("Tidak bisa membuat folder penyimpanan.");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File rawFile = new File(sessionDir, "raw_" + stamp + ".mp4");
        gyroSamples.clear();
        // Set a provisional start immediately. The absolute sensor timestamps are kept,
        // and VideoRecordEvent.Start refines this to the actual CameraX recording start.
        gyroStartNs = SystemClock.elapsedRealtimeNanos();
        lastSavedNs = 0L;
        if (gyro != null) sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST);

        FileOutputOptions output = new FileOutputOptions.Builder(rawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }

        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    gyroStartNs = SystemClock.elapsedRealtimeNanos();
                    recordButton.setEnabled(true);
                    recordButton.setText("BERHENTI & STABILKAN");
                    status.setText("Merekam video + data gyro...");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    recording = null;
                    if (sensorManager != null) sensorManager.unregisterListener(this);
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;

                    if (fin.hasError() || !rawFile.exists() || rawFile.length() == 0) {
                        status.setText("Rekaman gagal disimpan (kode " + fin.getError() + ").");
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                        gyroStartNs = 0L;
                        return;
                    }

                    try {
                        File csv = new File(sessionDir, rawFile.getName().replace(".mp4", "_gyro.csv"));
                        writeGyroCsv(csv);
                        pendingRawFile = rawFile;
                        pendingCsvFile = csv;
                        status.setText("Video asli tersimpan. Tekan STABILKAN VIDEO untuk memprosesnya.");
                        recordButton.setEnabled(true);
                        recordButton.setText("STABILKAN VIDEO");
                        // Keep gyroStartNs until the stabilization button is pressed; it is
                        // needed to align absolute sensor timestamps to this video.
                    } catch (Exception e) {
                        status.setText("Video asli tersimpan, tetapi data gyro gagal: " + e.getMessage());
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                    }
                }
            });
        } catch (Exception e) {
            recording = null;
            if (sensorManager != null) sensorManager.unregisterListener(this);
            recordButton.setEnabled(true);
            recordButton.setText("MULAI REKAM");
            status.setText("Gagal memulai rekaman: " + e.getMessage());
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (gyroStartNs == 0L) return;
        long now = event.timestamp;
        // Keep about 100 Hz: enough for handheld motion while limiting CSV and memory use.
        if (lastSavedNs != 0L && now - lastSavedNs < 10_000_000L) return;
        lastSavedNs = now;
        synchronized (gyroSamples) {
            gyroSamples.add(new GyroSample(now, event.values[0], event.values[1], event.values[2]));
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void writeGyroCsv(File csv) throws IOException {
        try (FileWriter w = new FileWriter(csv)) {
            w.write("t,gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s\n");
            synchronized (gyroSamples) {
                for (GyroSample s : gyroSamples) {
                    double t = s.secondsFrom(gyroStartNs);
                    if (t >= 0) w.write(String.format(Locale.US, "%.6f,%.7f,%.7f,%.7f\n", t, s.x, s.y, s.z));
                }
            }
        }
    }

    // Gyro-based stabilization: integrate all three angular-rate axes, smooth the
    // camera path, and apply inverse path corrections per video timestamp. This is
    // software EIS (not optical stabilization); the crop margin limits edge exposure.
    private void stabilize(File input, File csv) {
        status.setText("Menyiapkan stabilisasi gyro XYZ... video asli tetap disimpan.");
        new Thread(() -> {
            try {
                final long startNs = gyroStartNs;
                List<GyroSample> all;
                synchronized (gyroSamples) { all = new ArrayList<>(gyroSamples); }
                List<GyroSample> samples = new ArrayList<>();
                for (GyroSample sample : all) {
                    double t = sample.secondsFrom(startNs);
                    if (t >= 0) samples.add(new GyroSample((long)(t * 1_000_000_000L), sample.x, sample.y, sample.z));
                }
                // In this processing copy, timestampNs represents nanoseconds from video start.
                if (samples.size() < 8) {
                    runOnUiThread(() -> status.setText("Data gyro terlalu sedikit; tidak membuat hasil gyro palsu. Video asli tetap aman."));
                    runOnUiThread(() -> { recordButton.setEnabled(true); recordButton.setText("MULAI REKAM"); });
                    return;
                }

                int n = samples.size();
                double[] t = new double[n], ax = new double[n], ay = new double[n], az = new double[n];
                for (int i = 0; i < n; i++) t[i] = samples.get(i).timestampNs / 1_000_000_000.0;
                // Estimate sensor bias from the first 250 ms, when users normally hold
                // the phone steady before moving it. Then apply a small dead-band and
                // low-pass the angular rates before integration; this prevents sensor
                // noise in a nearly static clip from being amplified into visible jitter.
                double[] bias = estimateInitialGyroBias(samples, t, 0.25);
                double fx = 0, fy = 0, fz = 0;
                double prevFx = 0, prevFy = 0, prevFz = 0;
                // A slightly stronger dead-band suppresses stationary sensor noise.
                // Integrate filtered values on both sides of each interval; mixing a
                // filtered current sample with a raw previous sample can create jitter.
                final double deadBandThreshold = 0.018;
                for (int i = 1; i < n; i++) {
                    double dt = Math.max(0, Math.min(0.05, t[i] - t[i - 1]));
                    GyroSample cur = samples.get(i);
                    double rx = deadBand(cur.x - bias[0], deadBandThreshold);
                    double ry = deadBand(cur.y - bias[1], deadBandThreshold);
                    double rz = deadBand(cur.z - bias[2], deadBandThreshold);
                    double alpha = dt <= 0 ? 0 : dt / (0.070 + dt);
                    prevFx = fx; prevFy = fy; prevFz = fz;
                    fx += alpha * (rx - fx);
                    fy += alpha * (ry - fy);
                    fz += alpha * (rz - fz);
                    ax[i] = ax[i - 1] + 0.5 * (fx + prevFx) * dt;
                    ay[i] = ay[i - 1] + 0.5 * (fy + prevFy) * dt;
                    az[i] = az[i - 1] + 0.5 * (fz + prevFz) * dt;
                }

                // Use a longer camera-path smoothing window to distinguish slow,
                // intentional pans from faster hand shake. A modestly stronger
                // correction reduces larger shakes without amplifying static noise.
                double[] sx = smoothTrajectory(ax, t), sy = smoothTrajectory(ay, t), sz = smoothTrajectory(az, t);
                double[] cx = new double[n], cy = new double[n], cz = new double[n];
                final double correctionGain = 0.84;
                for (int i = 0; i < n; i++) {
                    cx[i] = (ax[i] - sx[i]) * correctionGain;
                    cy[i] = (ay[i] - sy[i]) * correctionGain;
                    cz[i] = (az[i] - sz[i]) * correctionGain;
                }

                // Limit the expression size on Android. 80 points across the clip are
                // interpolated by FFmpeg between actual frame timestamps.
                List<Integer> picked = pickIndices(n, 120);
                String rollExpr = buildExpression(t, cz, picked);
                // Approximate angular-to-pixel conversion. Crop margin is 1/1.12,
                // so translations are clamped to that safe range in the filter.
                String xExpr = buildExpression(t, cy, picked);
                String yExpr = buildExpression(t, cx, picked);
                File out = new File(sessionDir, input.getName().replace("raw_", "stabilized_"));
                // Keep the same 1.12 safety zoom as the previous build (no additional crop).
                // The crop is required to hide empty edges during frame rotation/translation.
                String filter = "rotate=angle='(" + rollExpr + ")':ow=iw:oh=ih:c=black," +
                        "crop=w=iw/1.12:h=ih/1.12:" +
                        "x='max(0\\,min(iw-ow\\,(iw-ow)/2+(" + xExpr + ")*iw*0.35))':" +
                        "y='max(0\\,min(ih-oh\\,(ih-oh)/2+(-1)*(" + yExpr + ")*ih*0.35))'," +
                        "scale=trunc(iw*1.12/2)*2:trunc(ih*1.12/2)*2:flags=lanczos";
                String cmd = "-y -i " + quote(input.getAbsolutePath())
                        + " -vf \"" + filter + "\" -c:v mpeg4 -q:v 4 -threads 2 -r 30 -vsync cfr"
                        + " -c:a aac -b:a 128k -movflags +faststart " + quote(out.getAbsolutePath());

                runOnUiThread(() -> status.setText("Stabilisasi gyro XYZ berjalan... jangan tutup aplikasi."));
                FFmpegKit.executeAsync(cmd, session -> runOnUiThread(() -> {
                    if (ReturnCode.isSuccess(session.getReturnCode()) && out.exists() && out.length() > 0) {
                        try {
                            publishVideo(out);
                            status.setText("Stabilisasi gyro XYZ selesai! Hasil tersimpan di Movies/GyroLock. Video asli tetap aman.");
                            Toast.makeText(this, "Hasil stabilisasi tersimpan di Movies/GyroLock", Toast.LENGTH_LONG).show();
                        } catch (Exception copyError) {
                            status.setText("Stabilisasi selesai, tetapi gagal menyalin hasil ke Galeri: " + copyError.getMessage());
                        }
                    } else {
                        String detail = session.getFailStackTrace();
                        if (detail == null || detail.trim().isEmpty()) detail = String.valueOf(session.getReturnCode());
                        status.setText("Stabilisasi gyro gagal; video asli tetap aman. " + detail);
                    }
                    recordButton.setEnabled(true);
                    recordButton.setText("MULAI REKAM");
                }));
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    status.setText("Stabilisasi gagal; video asli tetap aman: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " — " + e.getMessage()));
                    recordButton.setEnabled(true);
                    recordButton.setText("MULAI REKAM");
                });
            }
        }, "GyroLock-Stabilizer").start();
    }

    private double deadBand(double value, double threshold) {
        return Math.abs(value) < threshold ? 0.0 : value;
    }

    private double[] estimateInitialGyroBias(List<GyroSample> samples, double[] times, double windowSeconds) {
        double[] sum = new double[3];
        int count = 0;
        for (int i = 0; i < samples.size() && times[i] <= windowSeconds; i++) {
            sum[0] += samples.get(i).x;
            sum[1] += samples.get(i).y;
            sum[2] += samples.get(i).z;
            count++;
        }
        if (count == 0) return new double[]{0, 0, 0};
        return new double[]{sum[0] / count, sum[1] / count, sum[2] / count};
    }

    private double[] smoothTrajectory(double[] values, double[] times) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double sum = 0; int count = 0;
            for (int j = i; j >= 0 && times[i] - times[j] <= 1.25; j--) { sum += values[j]; count++; }
            for (int j = i + 1; j < values.length && times[j] - times[i] <= 1.25; j++) { sum += values[j]; count++; }
            result[i] = count == 0 ? values[i] : sum / count;
        }
        return result;
    }

    private List<Integer> pickIndices(int n, int maxPoints) {
        List<Integer> picked = new ArrayList<>();
        int count = Math.min(maxPoints, n);
        for (int k = 0; k < count; k++) {
            int index = count == 1 ? 0 : (int)Math.round(k * (n - 1.0) / (count - 1.0));
            if (picked.isEmpty() || picked.get(picked.size() - 1) != index) picked.add(index);
        }
        return picked;
    }

    private String buildExpression(double[] times, double[] values, List<Integer> idx) {
        if (idx == null || idx.isEmpty()) return "0";
        int last = idx.get(idx.size() - 1);
        String expression = String.format(Locale.US, "%.7f", values[last]);
        for (int k = idx.size() - 2; k >= 0; k--) {
            int ia = idx.get(k), ib = idx.get(k + 1);
            double ta = times[ia], tb = times[ib], va = values[ia], vb = values[ib];
            double dt = tb - ta;
            if (dt <= 0.0001) continue;
            String interpolation = String.format(Locale.US, "(%.7f+(%.7f)*(t-%.6f)/%.7f)", va, vb - va, ta, dt);
            expression = String.format(Locale.US, "if(lt(t\\,%.6f)\\,%s\\,%s)", tb, interpolation, expression);
        }
        return expression;
    }

    /**
     * Publishes the processed MP4 into the public Movies/GyroLock folder so it
     * appears in common file managers and gallery apps. The working/original
     * files stay in the app's private external-files directory.
     */
    private Uri publishVideo(File source) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GyroLock");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("Gagal membuat file video di Movies/GyroLock");
            boolean success = false;
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Tidak bisa menulis file video");
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
                success = true;
            } finally {
                if (success) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
            return uri;
        } else {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File folder = new File(movies, "GyroLock");
            if (!folder.exists() && !folder.mkdirs()) throw new IOException("Gagal membuat folder Movies/GyroLock");
            File dest = new File(folder, source.getName());
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            }
            return Uri.fromFile(dest);
        }
    }

    private String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ && hasPermissions()) startCamera();
        else if (requestCode == REQ) status.setText("Izin kamera dan mikrofon diperlukan.");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
