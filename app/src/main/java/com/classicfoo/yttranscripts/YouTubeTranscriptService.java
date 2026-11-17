package com.classicfoo.yttranscripts;

import android.net.Uri;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class YouTubeTranscriptService {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    Transcript fetch(String inputUrl) throws Exception {
        String videoId = extractVideoId(inputUrl);
        String canonicalUrl = "https://www.youtube.com/watch?v=" + videoId;
        String page = request(canonicalUrl);
        String playerJson = extractPlayerResponse(page);
        JSONObject player = new JSONObject(playerJson);

        JSONObject playability = player.optJSONObject("playabilityStatus");
        if (playability != null) {
            String status = playability.optString("status", "");
            if (!status.isEmpty() && !"OK".equals(status)) {
                String reason = playability.optString("reason", "");
                if (reason.toLowerCase(Locale.ROOT).contains("private")) {
                    throw new TranscriptException("This video is private.");
                }
                if (reason.toLowerCase(Locale.ROOT).contains("age")) {
                    throw new TranscriptException("This video is age-restricted and YouTube won't show its captions here.");
                }
            }
        }

        JSONObject details = player.optJSONObject("videoDetails");
        String title = details == null ? "YouTube transcript" : details.optString("title", "YouTube transcript");
        JSONObject captions = player.optJSONObject("captions");
        JSONObject renderer = captions == null ? null : captions.optJSONObject("playerCaptionsTracklistRenderer");
        JSONArray tracks = renderer == null ? null : renderer.optJSONArray("captionTracks");
        if (tracks == null || tracks.length() == 0) {
            throw new TranscriptException("No captions are available for this video.");
        }

        JSONObject chosenTrack = chooseTrack(tracks);
        if (chosenTrack == null) throw new TranscriptException("No readable caption track was found.");
        String baseUrl = chosenTrack.optString("baseUrl", "");
        if (baseUrl.isEmpty()) throw new TranscriptException("YouTube did not provide a caption link.");

        String trackLanguage = chosenTrack.optString("languageCode", "");
        String trackName = captionTrackName(chosenTrack);
        boolean automatic = "asr".equals(chosenTrack.optString("kind", ""));
        String languageLabel = trackName.isEmpty() ? trackLanguage : trackName;
        if (languageLabel.isEmpty()) languageLabel = "Original captions";
        if (automatic) languageLabel += " · auto-generated";

        String text;
        try {
            text = parseJson3(request(withFormat(baseUrl, "json3")));
        } catch (Exception jsonError) {
            text = "";
        }
        if (text.isEmpty()) {
            try {
                text = parseSrv3(request(withFormat(baseUrl, "srv3")));
            } catch (Exception xmlError) {
                if (xmlError instanceof TranscriptException) throw xmlError;
                throw new TranscriptException("YouTube listed captions but wouldn't return their text. Please try again later.");
            }
        }
        if (text.trim().isEmpty()) throw new TranscriptException("The caption track was empty.");

        int words = countWords(text);
        return new Transcript(title, languageLabel, text, words);
    }

    private String extractVideoId(String value) throws TranscriptException {
        String candidate = value.trim();
        if (!candidate.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) candidate = "https://" + candidate;
        Uri uri;
        try {
            uri = Uri.parse(candidate);
        } catch (Exception error) {
            throw new TranscriptException("Enter a valid YouTube video link.");
        }
        String host = uri.getHost();
        if (host == null) throw new TranscriptException("Enter a valid YouTube video link.");
        host = host.toLowerCase(Locale.ROOT);
        boolean youtubeHost = host.equals("youtu.be") || host.equals("youtube.com")
                || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com")
                || host.endsWith(".youtube-nocookie.com");
        if (!youtubeHost) throw new TranscriptException("That link doesn't look like a YouTube video.");

        String id = uri.getQueryParameter("v");
        if (id == null || id.isEmpty()) {
            List<String> segments = uri.getPathSegments();
            if (host.equals("youtu.be")) {
                if (!segments.isEmpty()) id = segments.get(0);
            } else {
                for (int i = 0; i + 1 < segments.size(); i++) {
                    String marker = segments.get(i);
                    if (marker.equals("shorts") || marker.equals("embed") || marker.equals("live")
                            || marker.equals("v")) {
                        id = segments.get(i + 1);
                        break;
                    }
                }
            }
        }
        if (id == null || !VIDEO_ID.matcher(id).matches()) {
            throw new TranscriptException("Couldn't find an 11-character video ID in that link.");
        }
        return id;
    }

    private String extractPlayerResponse(String html) throws TranscriptException {
        String[] markers = {"ytInitialPlayerResponse =", "ytInitialPlayerResponse=", "\"ytInitialPlayerResponse\":"};
        for (String marker : markers) {
            int markerAt = html.indexOf(marker);
            if (markerAt < 0) continue;
            int start = html.indexOf('{', markerAt + marker.length());
            if (start < 0) continue;
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < html.length(); i++) {
                char c = html.charAt(i);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                } else {
                    if (c == '"') inString = true;
                    else if (c == '{') depth++;
                    else if (c == '}' && --depth == 0) return html.substring(start, i + 1);
                }
            }
        }
        if (html.contains("consent.youtube.com") || html.contains("consent.google.com")) {
            throw new TranscriptException("YouTube is showing a consent page. Try again after opening the video in YouTube.");
        }
        throw new TranscriptException("YouTube didn't return video details. It may be age-restricted or temporarily unavailable.");
    }

    private JSONObject chooseTrack(JSONArray tracks) {
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null || track.optString("baseUrl", "").isEmpty()) continue;
            String kind = track.optString("kind", "");
            String language = track.optString("languageCode", "").toLowerCase(Locale.ROOT);
            int score = "asr".equals(kind) ? 0 : 100;
            if (language.equals("en") || language.startsWith("en-")) score += 20;
            if (track.optBoolean("isDefault", false)) score += 10;
            if (score > bestScore) {
                best = track;
                bestScore = score;
            }
        }
        return best;
    }

    private String captionTrackName(JSONObject track) {
        JSONObject name = track.optJSONObject("name");
        if (name == null) return "";
        String simple = name.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = name.optJSONArray("runs");
        if (runs != null && runs.length() > 0) return runs.optJSONObject(0).optString("text", "");
        return "";
    }

    private String withFormat(String baseUrl, String format) {
        Uri original = Uri.parse(baseUrl);
        Uri.Builder builder = original.buildUpon().clearQuery();
        for (String name : original.getQueryParameterNames()) {
            if ("fmt".equals(name)) continue;
            for (String value : original.getQueryParameters(name)) builder.appendQueryParameter(name, value);
        }
        builder.appendQueryParameter("fmt", format);
        return builder.build().toString();
    }

    private String request(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Accept", "text/html,application/json,application/xml,text/xml,*/*;q=0.8");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : readLimited(stream, MAX_RESPONSE_BYTES);
            if (status == 429) throw new TranscriptException("YouTube is temporarily rate-limiting requests. Try again in a little while.");
            if (status >= 400) throw new TranscriptException("YouTube returned an error (HTTP " + status + ").");
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private String readLimited(InputStream stream, int maxBytes) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new TranscriptException("The response from YouTube was too large.");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String parseJson3(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        if (events == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            JSONArray segments = event.optJSONArray("segs");
            if (segments == null) continue;
            StringBuilder line = new StringBuilder();
            for (int j = 0; j < segments.length(); j++) {
                JSONObject segment = segments.optJSONObject(j);
                if (segment != null) line.append(segment.optString("utf8", ""));
            }
            appendLine(result, line.toString());
        }
        return result.toString().trim();
    }

    private String parseSrv3(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        StringBuilder result = new StringBuilder();
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "text".equalsIgnoreCase(parser.getName())) {
                appendLine(result, parser.nextText());
            }
            event = parser.next();
        }
        return result.toString().trim();
    }

    private void appendLine(StringBuilder result, String raw) {
        String line = raw.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (line.isEmpty()) return;
        if (result.length() > 0) result.append('\n');
        result.append(line);
    }

    private int countWords(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    static final class Transcript {
        final String title;
        final String languageLabel;
        final String text;
        final int wordCount;

        Transcript(String title, String languageLabel, String text, int wordCount) {
            this.title = title;
            this.languageLabel = languageLabel;
            this.text = text;
            this.wordCount = wordCount;
        }
    }

    private static final class TranscriptException extends Exception {
        TranscriptException(String message) { super(message); }
    }
}
