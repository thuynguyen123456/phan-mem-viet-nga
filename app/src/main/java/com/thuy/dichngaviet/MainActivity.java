package com.thuy.dichngaviet;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Phiên dịch Nga <-> Việt bằng giọng nói, chạy offline.
 *  - Nhận dạng giọng nói: Vosk (mô hình small-ru, small-vn)
 *  - Dịch: Google ML Kit Translation (on-device)
 *  - Đọc kết quả: TextToSpeech của máy (cần cài giọng Nga + Việt để đọc offline)
 */
public class MainActivity extends Activity implements RecognitionListener, TextToSpeech.OnInitListener {

    private static final String URL_RU = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
    private static final String URL_VI = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip";
    private static final int REQ_MIC = 1;
    private static final float SAMPLE_RATE = 16000.0f;

    private TextView status;
    private TextView log;
    private ScrollView scroll;
    private EditText input;
    private Button btnVi;
    private Button btnRu;
    private Button btnSetup;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Model modelRu;
    private Model modelVi;
    private SpeechService speech;
    private Recognizer recognizer;
    private String listeningLang;          // "ru" | "vi" | null
    private final StringBuilder heard = new StringBuilder();
    private int session = 0;

    private Translator ruVi;
    private Translator viRu;
    private boolean translatorsReady = false;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean downloading = false;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        status = findViewById(R.id.status);
        log = findViewById(R.id.log);
        scroll = findViewById(R.id.scroll);
        input = findViewById(R.id.input);
        btnVi = findViewById(R.id.btnVi);
        btnRu = findViewById(R.id.btnRu);
        btnSetup = findViewById(R.id.btnSetup);

        btnVi.setOnClickListener(v -> toggleListen("vi"));
        btnRu.setOnClickListener(v -> toggleListen("ru"));
        btnSetup.setOnClickListener(v -> downloadAll());
        findViewById(R.id.btnVoice).setOnClickListener(v -> openTtsSettings());
        findViewById(R.id.btnClear).setOnClickListener(v -> log.setText(""));
        findViewById(R.id.btnTypeVi).setOnClickListener(v -> translateTyped("vi"));
        findViewById(R.id.btnTypeRu).setOnClickListener(v -> translateTyped("ru"));

        ruVi = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.RUSSIAN)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build());
        viRu = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.VIETNAMESE)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build());

        tts = new TextToSpeech(this, this);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        // Nếu dữ liệu đã có trong máy -> nạp luôn (không cần mạng)
        if (voskReady(dirRu()) && voskReady(dirVi())) {
            loadVoskModels();
        } else {
            setStatus("Lần đầu dùng: bật mạng (Wi-Fi) rồi bấm \"⬇ Tải dữ liệu offline\" (~140 MB). Sau đó dùng không cần mạng.");
        }
        prepareTranslators(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speech != null) {
            speech.cancel();
            speech.shutdown();
        }
        if (recognizer != null) recognizer.close();
        if (modelRu != null) modelRu.close();
        if (modelVi != null) modelVi.close();
        ruVi.close();
        viRu.close();
        if (tts != null) tts.shutdown();
        bg.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            toast("Cần quyền micro để dịch bằng giọng nói");
        }
    }

    // ------------------------------------------------------------------ setup / download

    private File dirRu() { return new File(getFilesDir(), "vosk-ru"); }
    private File dirVi() { return new File(getFilesDir(), "vosk-vi"); }

    private boolean voskReady(File dir) { return new File(dir, ".ok").exists(); }

    private void prepareTranslators(boolean fromSetup) {
        // Nếu mô hình đã tải thì hàm này xong ngay, không cần mạng
        DownloadConditions cond = new DownloadConditions.Builder().build();
        ruVi.downloadModelIfNeeded(cond)
                .continueWithTask(t -> {
                    if (!t.isSuccessful()) throw t.getException();
                    return viRu.downloadModelIfNeeded(cond);
                })
                .addOnSuccessListener(x -> {
                    translatorsReady = true;
                    updateReadyStatus();
                })
                .addOnFailureListener(e -> {
                    translatorsReady = false;
                    if (fromSetup) setStatus("Lỗi tải mô hình dịch: " + e.getMessage() + ". Kiểm tra mạng rồi bấm tải lại.");
                    else updateReadyStatus();
                });
    }

    private void downloadAll() {
        if (downloading) return;
        downloading = true;
        btnSetup.setEnabled(false);
        setStatus("Đang tải mô hình dịch Nga/Việt...");
        prepareTranslators(true);
        bg.execute(() -> {
            try {
                if (!voskReady(dirRu())) downloadModel(URL_RU, dirRu(), "Giọng nói tiếng Nga");
                if (!voskReady(dirVi())) downloadModel(URL_VI, dirVi(), "Giọng nói tiếng Việt");
                ui.post(this::loadVoskModels);
            } catch (Exception e) {
                setStatus("Lỗi tải dữ liệu giọng nói: " + e.getMessage() + ". Kiểm tra mạng rồi bấm tải lại.");
            } finally {
                ui.post(() -> {
                    downloading = false;
                    btnSetup.setEnabled(true);
                });
            }
        });
    }

    /** Tải file zip mô hình Vosk và giải nén vào destDir (bỏ thư mục gốc trong zip). Chạy ở luồng nền. */
    private void downloadModel(String url, File destDir, String label) throws IOException {
        File tmp = new File(getCacheDir(), destDir.getName() + ".zip");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            long total = c.getContentLengthLong();
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            setStatus(label + ": đang tải " + pct + "%");
                        }
                    }
                }
            }
        } finally {
            c.disconnect();
        }

        setStatus(label + ": đang giải nén...");
        deleteRecursive(destDir);
        if (!destDir.mkdirs()) throw new IOException("Không tạo được thư mục");
        String canon = destDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(tmp)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                String rel = slash >= 0 ? name.substring(slash + 1) : name;
                if (rel.isEmpty()) continue;
                File f = new File(destDir, rel);
                if (!f.getCanonicalPath().startsWith(canon)) throw new IOException("File zip không hợp lệ");
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }
        tmp.delete();
        if (!new File(destDir, ".ok").createNewFile()) throw new IOException("Không ghi được dữ liệu");
    }

    private void deleteRecursive(File f) {
        if (!f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File ch : children) deleteRecursive(ch);
        f.delete();
    }

    private void loadVoskModels() {
        if (modelRu != null && modelVi != null) {
            updateReadyStatus();
            return;
        }
        setStatus("Đang nạp mô hình giọng nói...");
        bg.execute(() -> {
            try {
                Model ru = new Model(dirRu().getAbsolutePath());
                Model vi = new Model(dirVi().getAbsolutePath());
                ui.post(() -> {
                    modelRu = ru;
                    modelVi = vi;
                    updateReadyStatus();
                });
            } catch (Exception e) {
                setStatus("Lỗi nạp mô hình giọng nói: " + e.getMessage() + ". Bấm \"Tải dữ liệu offline\" để tải lại.");
                deleteRecursive(dirRu());
                deleteRecursive(dirVi());
            }
        });
    }

    private void updateReadyStatus() {
        boolean voskOk = modelRu != null && modelVi != null;
        if (voskOk && translatorsReady) {
            setStatus("✅ Sẵn sàng (offline). Bấm nút micro, nói xong bấm lại để dịch.");
        } else if (!voskOk && !(voskReady(dirRu()) && voskReady(dirVi()))) {
            setStatus("Chưa có dữ liệu giọng nói. Bật mạng rồi bấm \"⬇ Tải dữ liệu offline\".");
        } else if (!translatorsReady) {
            setStatus("Chưa có mô hình dịch. Bật mạng rồi bấm \"⬇ Tải dữ liệu offline\".");
        }
    }

    // ------------------------------------------------------------------ speech recognition (Vosk)

    private void toggleListen(String lang) {
        if (speech != null) {
            // đang nghe -> bấm lần nữa để dừng và dịch
            stopListening();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        Model m = "ru".equals(lang) ? modelRu : modelVi;
        if (m == null) {
            toast("Chưa có dữ liệu giọng nói. Bấm \"Tải dữ liệu offline\" trước.");
            return;
        }
        if (tts != null) tts.stop();
        try {
            recognizer = new Recognizer(m, SAMPLE_RATE);
            speech = new SpeechService(recognizer, SAMPLE_RATE);
            listeningLang = lang;
            heard.setLength(0);
            session++;
            speech.startListening(this);
            if ("ru".equals(lang)) {
                btnRu.setText("⏹ Стоп / Dừng");
                btnVi.setEnabled(false);
                setStatus("🎙 Говорите... (bấm Стоп khi nói xong)");
            } else {
                btnVi.setText("⏹ Dừng");
                btnRu.setEnabled(false);
                setStatus("🎙 Đang nghe tiếng Việt... (bấm Dừng khi nói xong)");
            }
        } catch (Exception e) {
            cleanupSpeech();
            toast("Không mở được micro: " + e.getMessage());
        }
    }

    private void stopListening() {
        if (speech == null) return;
        final int s = session;
        speech.stop(); // sẽ gọi onFinalResult
        // phòng hờ: nếu vì lý do nào đó không có onFinalResult thì vẫn kết thúc
        ui.postDelayed(() -> {
            if (speech != null && session == s) finishListening("");
        }, 2000);
    }

    @Override
    public void onPartialResult(String hypothesis) {
        String p = jsonText(hypothesis, "partial");
        if (!p.isEmpty()) setStatus("🎙 " + (heard.length() > 0 ? heard + " " : "") + p);
    }

    @Override
    public void onResult(String hypothesis) {
        String t = jsonText(hypothesis, "text");
        if (!t.isEmpty()) {
            if (heard.length() > 0) heard.append(' ');
            heard.append(t);
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        finishListening(jsonText(hypothesis, "text"));
    }

    @Override
    public void onError(Exception e) {
        cleanupSpeech();
        setStatus("Lỗi micro: " + e.getMessage());
    }

    @Override
    public void onTimeout() {
        finishListening("");
    }

    private void finishListening(String lastPart) {
        if (speech == null) return;
        if (!lastPart.isEmpty()) {
            if (heard.length() > 0) heard.append(' ');
            heard.append(lastPart);
        }
        String from = listeningLang;
        String text = heard.toString().trim();
        cleanupSpeech();
        if (text.isEmpty()) {
            setStatus("Không nghe rõ, hãy thử lại (nói gần micro hơn).");
            return;
        }
        translateAndSpeak(from, text);
    }

    private void cleanupSpeech() {
        if (speech != null) {
            speech.shutdown();
            speech = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        listeningLang = null;
        btnVi.setText("🎤 Nói tiếng Việt");
        btnRu.setText("🎤 Говорить по-русски");
        btnVi.setEnabled(true);
        btnRu.setEnabled(true);
    }

    private static String jsonText(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ translation + TTS

    private void translateTyped(String from) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            toast("Hãy gõ nội dung cần dịch");
            return;
        }
        input.setText("");
        translateAndSpeak(from, text);
    }

    private void translateAndSpeak(String from, String text) {
        if (!translatorsReady) {
            toast("Chưa có mô hình dịch. Bấm \"Tải dữ liệu offline\" khi có mạng.");
            return;
        }
        final boolean fromRu = "ru".equals(from);
        Translator t = fromRu ? ruVi : viRu;
        setStatus("Đang dịch...");
        t.translate(text)
                .addOnSuccessListener(result -> {
                    if (fromRu) {
                        addEntry("🇷🇺 " + text, "🇻🇳 " + result);
                        speak(result, "vi");
                    } else {
                        addEntry("🇻🇳 " + text, "🇷🇺 " + result);
                        speak(result, "ru");
                    }
                    updateReadyStatus();
                })
                .addOnFailureListener(e -> setStatus("Lỗi dịch: " + e.getMessage()));
    }

    private void addEntry(String original, String translated) {
        if (log.length() > 0) log.append("\n\n");
        log.append(original + "\n➜ " + translated);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onInit(int s) {
        ttsReady = (s == TextToSpeech.SUCCESS);
        if (!ttsReady) toast("Máy chưa có bộ đọc văn bản (TTS). Hãy cài \"Dịch vụ lời nói của Google\".");
    }

    private void speak(String text, String lang) {
        if (!ttsReady) return;
        Locale loc = "ru".equals(lang) ? Locale.forLanguageTag("ru-RU") : Locale.forLanguageTag("vi-VN");
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("Chưa có giọng đọc " + ("ru".equals(lang) ? "tiếng Nga" : "tiếng Việt")
                    + ". Bấm \"🔊 Giọng đọc\" để cài.");
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt-" + System.currentTimeMillis());
    }

    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
            } catch (Exception e2) {
                toast("Vào Cài đặt > Hỗ trợ tiếp cận > Chuyển văn bản thành giọng nói");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void setStatus(String s) {
        if (Looper.myLooper() == Looper.getMainLooper()) status.setText(s);
        else ui.post(() -> status.setText(s));
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }
}
