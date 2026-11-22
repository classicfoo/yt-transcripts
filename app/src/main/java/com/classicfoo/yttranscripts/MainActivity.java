package com.classicfoo.yttranscripts;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int CREATE_TEXT_FILE = 4107;
    private static final String PENDING_EXPORT_FILE = "pending-transcript.txt";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText urlInput;
    private Button fetchButton;
    private Button saveButton;
    private ProgressBar progress;
    private LinearLayout resultCard;
    private TextView statusText;
    private TextView detailText;
    private TextView previewText;

    private YouTubeTranscriptService.Transcript transcript;
    private String sourceUrl;
    private String suggestedFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.url_input);
        fetchButton = findViewById(R.id.fetch_button);
        saveButton = findViewById(R.id.save_button);
        progress = findViewById(R.id.progress);
        resultCard = findViewById(R.id.result_card);
        statusText = findViewById(R.id.status_text);
        detailText = findViewById(R.id.detail_text);
        previewText = findViewById(R.id.preview_text);

        fetchButton.setOnClickListener(view -> fetchTranscript());
        saveButton.setOnClickListener(view -> saveTranscript());
        urlInput.setOnEditorActionListener((textView, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) {
                fetchTranscript();
                return true;
            }
            return false;
        });
        installSystemBarInsets();
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
                    suggestedFileName = safeFileName(fetched.title) + ".txt";
                    statusText.setText(fetched.title);
                    detailText.setText(fetched.languageLabel + "  ·  "
                            + fetched.wordCount + " words");
                    previewText.setText(fetched.text);
                    setBusy(false);
                    saveButton.setEnabled(true);
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
        if (transcript == null) return;

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
            Toast.makeText(this, "Couldn't prepare the transcript: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, suggestedFileName);
        saveButton.setEnabled(false);
        try {
            startActivityForResult(intent, CREATE_TEXT_FILE);
        } catch (Exception error) {
            deleteFile(PENDING_EXPORT_FILE);
            saveButton.setEnabled(transcript != null);
            Toast.makeText(this, "Couldn't open the file picker: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_TEXT_FILE) return;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            deleteFile(PENDING_EXPORT_FILE);
            saveButton.setEnabled(transcript != null);
            return;
        }

        Uri destination = data.getData();
        boolean saved = false;
        try (InputStream input = openFileInput(PENDING_EXPORT_FILE);
             OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IllegalStateException("Could not open the selected file");
            byte[] buffer = new byte[8192];
            int totalBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                totalBytes += read;
            }
            output.flush();
            if (totalBytes == 0) throw new IllegalStateException("The transcript file was empty");
            saved = true;
        } catch (Exception error) {
            Toast.makeText(this, "Couldn't save the transcript: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        } finally {
            deleteFile(PENDING_EXPORT_FILE);
            saveButton.setEnabled(transcript != null);
        }
        if (saved) Toast.makeText(this, "Transcript saved", Toast.LENGTH_SHORT).show();
    }

    private String safeFileName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100).trim();
        return cleaned.isEmpty() ? "youtube-transcript" : cleaned;
    }

    private void setBusy(boolean busy) {
        fetchButton.setEnabled(!busy);
        fetchButton.setText(busy ? "" : "Get transcript");
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy && transcript != null);
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
