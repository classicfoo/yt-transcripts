package com.classicfoo.yttranscripts;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Xml;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YouTubeTranscriptService {
    private static final String FALLBACK_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern API_KEY = Pattern.compile("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VISITOR_DATA = Pattern.compile("\"visitorData\"\\s*:\\s*\"([^\"]+)\"");
    private static final String ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
    private static final String DEFAULT_ANDROID_CLIENT_VERSION = "21.26.364";

    private final Context context;

    YouTubeTranscriptService(Context context) {
        this.context = context.getApplicationContext();
    }

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
        JSONObject chosenTrack = tracks == null ? null : chooseTrack(tracks);
        String text = "";
        String languageLabel = "Original captions";
        String webCaptionError = null;
        String androidCaptionError = null;
        if (chosenTrack != null) {
            languageLabel = captionLanguageLabel(chosenTrack);
            try {
                text = fetchTrackText(chosenTrack.optString("baseUrl", ""));
            } catch (Exception error) {
                webCaptionError = error.getMessage();
                // Fall back to the Android player when the WEB caption URL is blocked.
            }
        }

        if (text.trim().isEmpty()) {
            try {
                AndroidCaptions androidCaptions = fetchAndroidCaptions(videoId, page);
                if (androidCaptions.track != null) {
                    String androidText = fetchTrackText(
                            androidCaptions.track.optString("baseUrl", ""),
                            androidCaptions.userAgent, androidCaptions.clientVersion,
                            androidCaptions.visitorData);
                    if (!androidText.trim().isEmpty()) {
                        text = androidText;
                        languageLabel = captionLanguageLabel(androidCaptions.track);
                        if (details == null || "YouTube transcript".equals(title)) {
                            title = androidCaptions.title;
                        }
                    }
                }
            } catch (Exception error) {
                androidCaptionError = error.getMessage();
            }
        }
        if (text.trim().isEmpty()) {
            String reason = playability == null ? ""
                    : playability.optString("reason", "");
            String status = playability == null ? ""
                    : playability.optString("status", "");
            if (reason.toLowerCase(Locale.ROOT).contains("bot")
                    || "LOGIN_REQUIRED".equals(status)) {
                throw new TranscriptException("YouTube asked the app to verify the request, so it couldn't reach the captions. Try again later or on a different network.");
            }
            if (chosenTrack == null && (tracks == null || tracks.length() == 0)) {
                if (androidCaptionError != null && !androidCaptionError.trim().isEmpty()) {
                    throw new TranscriptException("YouTube didn't expose a caption track. "
                            + androidCaptionError);
                }
                throw new TranscriptException("YouTube didn't expose any captions for this video.");
            }
            String requestError = androidCaptionError != null
                    ? androidCaptionError : webCaptionError;
            if (requestError != null && !requestError.trim().isEmpty()) {
                throw new TranscriptException("YouTube listed captions but blocked the text request: "
                        + requestError);
            }
            throw new TranscriptException("YouTube listed captions, but returned no transcript text. Its caption service may require playback verification. Try again later or on a different network.");
        }

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

    private String fetchTrackText(String baseUrl) throws Exception {
        return fetchTrackText(baseUrl, defaultUserAgent(), null, null);
    }

    private String fetchTrackText(String baseUrl, String userAgent, String clientVersion,
                                  String visitorData) throws Exception {
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        Exception firstError = null;
        try {
            String json3 = parseJson3(request(withFormat(baseUrl, "json3"), userAgent,
                    clientVersion, visitorData));
            if (!json3.isEmpty()) return json3;
        } catch (Exception error) {
            firstError = error;
            // Some track URLs only support the XML caption format.
        }
        try {
            String srv3 = parseSrv3(request(withFormat(baseUrl, "srv3"), userAgent,
                    clientVersion, visitorData));
            if (!srv3.isEmpty()) return srv3;
        } catch (Exception error) {
            if (firstError != null) throw error;
        }
        return "";
    }

    private AndroidCaptions fetchAndroidCaptions(String videoId, String watchPage)
            throws Exception {
        String version = installedYouTubeVersion();
        String userAgent = "com.google.android.youtube/" + version + " (Linux; U; Android "
                + Build.VERSION.RELEASE + ") gzip";
        String language = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();

        JSONObject client = new JSONObject();
        client.put("clientName", "ANDROID");
        client.put("clientVersion", version);
        client.put("androidSdkVersion", Build.VERSION.SDK_INT);
        client.put("deviceMake", Build.MANUFACTURER);
        client.put("deviceModel", Build.MODEL);
        client.put("osName", "Android");
        client.put("osVersion", Build.VERSION.RELEASE);
        client.put("hl", language.isEmpty() ? "en" : language);
        client.put("gl", country.isEmpty() ? "US" : country);
        client.put("timeZone", TimeZone.getDefault().getID());
        client.put("userAgent", userAgent);
        Matcher visitorMatch = VISITOR_DATA.matcher(watchPage);
        String visitorData = visitorMatch.find() ? visitorMatch.group(1) : "";
        if (!visitorData.isEmpty()) client.put("visitorData", visitorData);

        JSONObject requestContext = new JSONObject();
        requestContext.put("client", client);
        JSONObject body = new JSONObject();
        body.put("videoId", videoId);
        body.put("context", requestContext);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        Matcher keyMatch = API_KEY.matcher(watchPage);
        String apiKey = keyMatch.find() ? keyMatch.group(1) : ANDROID_API_KEY;
        String endpoint = "https://www.youtube.com/youtubei/v1/player?key=" + apiKey
                + "&prettyPrint=false";
        JSONObject player = new JSONObject(postJson(endpoint, body, userAgent, version, visitorData));

        JSONObject playability = player.optJSONObject("playabilityStatus");
        if (playability != null && !"OK".equals(playability.optString("status", ""))) {
            throw new TranscriptException("YouTube blocked the Android caption request: "
                    + playability.optString("reason", "video unavailable"));
        }

        JSONObject details = player.optJSONObject("videoDetails");
        String title = details == null ? "YouTube transcript"
                : details.optString("title", "YouTube transcript");
        JSONObject captions = player.optJSONObject("captions");
        JSONObject renderer = captions == null ? null
                : captions.optJSONObject("playerCaptionsTracklistRenderer");
        JSONArray tracks = renderer == null ? null : renderer.optJSONArray("captionTracks");
        return new AndroidCaptions(title, tracks == null ? null : chooseTrack(tracks),
                version, userAgent, visitorData);
    }

    private String installedYouTubeVersion() {
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = context.getPackageManager().getPackageInfo("com.google.android.youtube",
                        PackageManager.PackageInfoFlags.of(0));
            } else {
                packageInfo = context.getPackageManager().getPackageInfo("com.google.android.youtube", 0);
            }
            if (packageInfo.versionName != null && !packageInfo.versionName.trim().isEmpty()) {
                return packageInfo.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep a recent fallback for devices without the YouTube app installed.
        }
        return DEFAULT_ANDROID_CLIENT_VERSION;
    }

    private String captionLanguageLabel(JSONObject track) {
        String language = track.optString("languageCode", "");
        String name = captionTrackName(track);
        String label = name.isEmpty() ? language : name;
        if (label.isEmpty()) label = "Original captions";
        if ("asr".equals(track.optString("kind", ""))) label += " · auto-generated";
        return label;
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
        return request(address, "GET", null, defaultUserAgent(), null, null);
    }

    private String defaultUserAgent() {
        try {
            String userAgent = WebSettings.getDefaultUserAgent(context);
            if (userAgent != null && !userAgent.trim().isEmpty()) return userAgent;
        } catch (Exception ignored) {
            // Keep the static fallback for devices where Android can't provide a WebView UA.
        }
        return FALLBACK_USER_AGENT;
    }

    private String request(String address, String userAgent, String clientVersion,
                           String visitorData) throws Exception {
        return request(address, "GET", null, userAgent, clientVersion, visitorData);
    }

    private String postJson(String address, JSONObject body, String userAgent, String clientVersion,
                            String visitorData) throws Exception {
        return request(address, "POST", body.toString().getBytes(StandardCharsets.UTF_8),
                userAgent, clientVersion, visitorData);
    }

    private String request(String address, String method, byte[] requestBody, String userAgent,
                           String clientVersion, String visitorData) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        connection.setRequestProperty("Accept", "text/html,application/json,application/xml,text/xml,*/*;q=0.8");
        if (clientVersion != null) {
            connection.setRequestProperty("X-YouTube-Client-Name", "3");
            connection.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            connection.setRequestProperty("X-YouTube-Bootstrap-Logged-In", "false");
        }
        if (visitorData != null && !visitorData.isEmpty()) {
            connection.setRequestProperty("X-Goog-Visitor-Id", visitorData);
        }
        if (requestBody != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBody);
            }
        }
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

    private static final class AndroidCaptions {
        final String title;
        final JSONObject track;
        final String clientVersion;
        final String userAgent;
        final String visitorData;

        AndroidCaptions(String title, JSONObject track, String clientVersion, String userAgent,
                        String visitorData) {
            this.title = title;
            this.track = track;
            this.clientVersion = clientVersion;
            this.userAgent = userAgent;
            this.visitorData = visitorData;
        }
    }

    private static final class TranscriptException extends Exception {
        TranscriptException(String message) { super(message); }
    }
}
