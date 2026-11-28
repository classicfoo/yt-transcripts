package com.classicfoo.yttranscripts;

import android.app.Activity;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_WRITE_STORAGE = 4108;
    private static final String PENDING_EXPORT_FILE = "pending-transcript.txt";
    private static final String CHATGPT_PACKAGE = "com.openai.chatgpt";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private Button fetchButton;
    private ProgressBar progress;
    private LinearLayout resultCard;
    private TextView statusText;
    private TextView detailText;
    private TextView previewText;

    private YouTubeTranscriptService.Transcript transcript;
    private String sourceUrl;
    private String pendingFileName;
    private String lastClipboardUrl;
    private boolean fieldFilledFromClipboard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.url_input);
        fetchButton = findViewById(R.id.fetch_button);
        progress = findViewById(R.id.progress);
        resultCard = findViewById(R.id.result_card);
        statusText = findViewById(R.id.status_text);
        detailText = findViewById(R.id.detail_text);
        previewText = findViewById(R.id.preview_text);
        ScrollView previewScroll = findViewById(R.id.preview_scroll);
        previewScroll.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        fetchButton.setText("Download & open ChatGPT");

        fetchButton.setOnClickListener(view -> confirmAndRun());
        urlInput.setOnEditorActionListener((textView, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) {
                confirmAndRun();
                return true;
            }
            return false;
        });
        installSystemBarInsets();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && urlInput != null) pasteYouTubeLinkFromClipboard();
    }

    private void pasteYouTubeLinkFromClipboard() {
        String currentText = urlInput.getText().toString().trim();
        boolean canReplace = currentText.isEmpty()
                || (fieldFilledFromClipboard && currentText.equals(lastClipboardUrl));
        if (!canReplace) return;

        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            if (value == null) return;
            String candidate = value.toString().trim();
            if (candidate.isEmpty() || !isYouTubeLink(candidate)) return;

            urlInput.setText(candidate);
            lastClipboardUrl = candidate;
            fieldFilledFromClipboard = true;
        } catch (SecurityException ignored) {
            // Clipboard contents are available only while this app has focus on Android 10+.
        }
    }

    private boolean isYouTubeLink(String value) {
        String candidate = value;
        if (!candidate.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            candidate = "https://" + candidate;
        }
        String host = Uri.parse(candidate).getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("youtu.be") || host.equals("youtube.com")
                || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com")
                || host.endsWith(".youtube-nocookie.com");
    }

    private void confirmAndRun() {
        String input = urlInput.getText().toString().trim();
        if (transcript != null && input.equals(sourceUrl)) {
            saveTranscript();
        } else {
            fetchTranscript();
        }
    }

    private void installSystemBarInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        ScrollView root = findViewById(R.id.root_scroll);
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(initialLeft, initialTop + bars.top, initialRight,
                    initialBottom + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void fetchTranscript() {
        String input = urlInput.getText().toString().trim();
        if (input.isEmpty()) {
            urlInput.setError("Paste a YouTube video link");
            urlInput.requestFocus();
            return;
        }

        hideKeyboard();
        setBusy(true);
        resultCard.setVisibility(View.VISIBLE);
        statusText.setText("Looking for captions…");
        statusText.setTextColor(getColor(R.color.ink));
        detailText.setText("Connecting to YouTube");
        previewText.setText("");
        transcript = null;
        sourceUrl = input;

        executor.execute(() -> {
            try {
                YouTubeTranscriptService.Transcript fetched =
                        new YouTubeTranscriptService(MainActivity.this).fetch(input);
                runOnUiThread(() -> {
                    transcript = fetched;
                    statusText.setText(fetched.title);
                    detailText.setText(fetched.languageLabel + "  ·  "
                            + fetched.wordCount + " words");
                    previewText.setText(fetched.text);
                    detailText.setText("Saving to Downloads…  ·  "
                            + fetched.wordCount + " words");
                    saveTranscript();
                });
            } catch (Exception error) {
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = "Could not load this transcript. Try another public video.";
                }
                String shownMessage = message;
                runOnUiThread(() -> {
                    statusText.setText("Transcript unavailable");
                    statusText.setTextColor(getColor(R.color.error));
                    detailText.setText(shownMessage);
                    previewText.setText("");
                    setBusy(false);
                });
            }
        });
    }

    private void saveTranscript() {
        if (transcript == null) {
            setBusy(false);
            return;
        }

        setBusy(true);
        pendingFileName = uniqueFileName(transcript.title);
        detailText.setText("Preparing transcript for Downloads…");

        try (OutputStream output = openFileOutput(PENDING_EXPORT_FILE, MODE_PRIVATE);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write("Title: ");
            writer.write(transcript.title);
            writer.write("\nSource: ");
            writer.write(sourceUrl);
            writer.write("\nLanguage: ");
            writer.write(transcript.languageLabel);
            writer.write("\n\n");
            writer.write(transcript.text);
            writer.write('\n');
        } catch (Exception error) {
            finishExport(false, "Couldn't prepare the transcript: " + error.getMessage());
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(pendingFileName);
            return;
        }

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            saveToLegacyDownloads(pendingFileName);
            return;
        }

        try {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
        } catch (Exception error) {
            finishExport(false, "Storage permission is needed to save in Downloads: "
                    + error.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WRITE_STORAGE) return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && pendingFileName != null) {
            saveToLegacyDownloads(pendingFileName);
        } else {
            finishExport(false, "Storage permission is needed to save in Downloads.");
        }
    }

    private void saveToLegacyDownloads(String fileName) {
        File destination = null;
        boolean saved = false;
        String errorMessage = null;
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!downloads.isDirectory() && !downloads.mkdirs()) {
                throw new IllegalStateException("Android couldn't open the Downloads folder");
            }
            destination = uniqueFile(downloads, fileName);
            try (OutputStream output = new FileOutputStream(destination)) {
                copyPendingExport(output);
            }
            Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(destination));
            sendBroadcast(scan);
            saved = true;
        } catch (Exception error) {
            errorMessage = "Couldn't save to Downloads: " + error.getMessage();
        } finally {
            if (!saved && destination != null && destination.exists()) destination.delete();
        }
        finishExport(saved, errorMessage);
    }

    private File uniqueFile(File directory, String fileName) {
        String baseName = fileName.endsWith(".txt")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        File candidate = new File(directory, fileName);
        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(directory, baseName + " (" + suffix++ + ").txt");
        }
        return candidate;
    }

    private void saveToDownloads(String fileName) {
        Uri destination = null;
        boolean saved = false;
        String errorMessage = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values);
            if (destination == null) {
                throw new IllegalStateException("Android couldn't create a file in Downloads");
            }

            copyPendingExport(destination);
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (getContentResolver().update(destination, published, null, null) == 0) {
                throw new IllegalStateException("Android couldn't finish the Downloads file");
            }
            saved = true;
        } catch (Exception error) {
            errorMessage = "Couldn't save to Downloads: " + error.getMessage();
        } finally {
            if (!saved && destination != null) {
                try {
                    getContentResolver().delete(destination, null, null);
                } catch (Exception ignored) {
                    // Keep the original save error visible if cleanup also fails.
                }
            }
        }
        finishExport(saved, errorMessage);
    }

    private void copyPendingExport(Uri destination) throws Exception {
        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IllegalStateException("Could not open the Downloads file");
            copyPendingExport(output);
        }
    }

    private void copyPendingExport(OutputStream output) throws Exception {
        try (InputStream input = openFileInput(PENDING_EXPORT_FILE)) {
            byte[] buffer = new byte[8192];
            int totalBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                totalBytes += read;
            }
            output.flush();
            if (totalBytes == 0) throw new IllegalStateException("The transcript file was empty");
        }
    }

    private void finishExport(boolean saved, String errorMessage) {
        deleteFile(PENDING_EXPORT_FILE);
        pendingFileName = null;
        setBusy(false);
        if (!saved) {
            detailText.setText(errorMessage == null ? "Couldn't save the transcript." : errorMessage);
            Toast.makeText(this, detailText.getText(), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show();
        openChatGPT();
    }

    private String uniqueFileName(String title) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new Date());
        return safeFileName(title) + " (" + timestamp + ").txt";
    }

    private String safeFileName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ").trim()
                .replaceAll("[. ]+$", "");
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100).trim();
        return cleaned.isEmpty() ? "youtube-transcript" : cleaned;
    }

    private void setBusy(boolean busy) {
        fetchButton.setEnabled(!busy);
        fetchButton.setText(busy ? "" : "Download & open ChatGPT");
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void openChatGPT() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(CHATGPT_PACKAGE);
        if (launchIntent == null) {
            Toast.makeText(this, "Saved to Downloads. ChatGPT isn't installed.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            startActivity(launchIntent);
        } catch (Exception error) {
            Toast.makeText(this, "Saved to Downloads, but couldn't open ChatGPT.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        urlInput.clearFocus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
