package app;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.jar.JarFile;

/**
 * Talks to Uranium's GitHub: the latest release (its build jar and its notes) and the news
 * line in news.txt on the main branch. A release is newer when its build jar isn't the one
 * you run and it came out after yours. Downloads go to the builds folder next to
 * Uranium.exe, where the launcher picks the newest build on the next start. Nothing about
 * you is sent: it only reads public pages.
 */
final class Updater {
    static final String REPO = "issa-un/uranium-client";
    private static final String AGENT = "Uranium-updater";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** The latest release, as far as the updater needs it. */
    record Release(String tag, String name, String notes, Instant published, String jarName, String jarUrl,
            long jarSize) {}

    private Updater() {}

    /** The latest release on GitHub; throws with a plain reason if it can't be read. */
    static Release latest() throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", AGENT)
                .timeout(Duration.ofSeconds(12))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException("No releases yet");
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) new Json(response.body()).value();
        String jarName = null;
        String jarUrl = null;
        long jarSize = 0;
        Object assets = json.get("assets");
        if (assets instanceof List<?> list) {
            for (Object asset : list) {
                if (asset instanceof Map<?, ?> map && map.get("name") instanceof String name
                        && name.startsWith("minecraft-gui-injector-") && name.endsWith(".jar")) {
                    jarName = name;
                    jarUrl = (String) map.get("browser_download_url");
                    jarSize = map.get("size") instanceof Number size ? size.longValue() : 0;
                }
            }
        }
        String published = (String) json.get("published_at");
        return new Release(String.valueOf(json.get("tag_name")), String.valueOf(json.getOrDefault("name", "")),
                String.valueOf(json.getOrDefault("body", "")), published == null ? Instant.EPOCH : Instant.parse(published),
                jarName, jarUrl, jarSize);
    }

    /** The news line from news.txt on the main branch, or null if there is none. */
    static String news() {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                            URI.create("https://raw.githubusercontent.com/" + REPO + "/main/news.txt"))
                    .header("User-Agent", AGENT)
                    .timeout(Duration.ofSeconds(8))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            String text = response.body().strip();
            return text.isEmpty() ? null : text.lines().filter(line -> !line.startsWith("#")).findFirst().orElse(null);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            return null;
        }
    }

    /** Downloads the release's jar into the builds folder; the path of the new jar. */
    static Path download(Release release, Path buildsFolder, LongConsumer progress) throws IOException,
            InterruptedException {
        Files.createDirectories(buildsFolder);
        Path part = buildsFolder.resolve(release.jarName() + ".part");
        HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(release.jarUrl()))
                .header("User-Agent", AGENT)
                .timeout(Duration.ofMinutes(2))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed (" + response.statusCode() + ")");
        }
        try (InputStream in = response.body(); var out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, read);
                total += read;
                progress.accept(total);
            }
        }
        // a real jar with a main class, or it isn't kept
        try (JarFile jar = new JarFile(part.toFile())) {
            if (jar.getManifest() == null || jar.getManifest().getMainAttributes().getValue("Main-Class") == null) {
                throw new IOException("The download isn't a Uranium build");
            }
        } catch (IOException exception) {
            Files.deleteIfExists(part);
            throw exception;
        }
        Path jar = buildsFolder.resolve(release.jarName());
        Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    /** When this build was made (its manifest's Build-Time, else the jar file's time). */
    static Instant builtAt(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            String time = file.getManifest().getMainAttributes().getValue("Build-Time");
            if (time != null) {
                return Instant.parse(time);
            }
        } catch (Exception ignored) {
            // fall back to the file's time
        }
        try {
            return Files.getLastModifiedTime(jar).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    /** Starts Uranium again the way it was started (the exe, or java with its arguments) and quits. */
    static void restart() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String command = info.command().orElseThrow(() -> new IOException("Can't tell how Uranium was started"));
        List<String> line = new ArrayList<>();
        line.add(command);
        info.arguments().ifPresent(arguments -> line.addAll(List.of(arguments)));
        new ProcessBuilder(line).inheritIO().start();
        System.exit(0);
    }

    /** Just enough JSON for GitHub's answers: objects, arrays, strings, numbers, true/false/null. */
    private static final class Json {
        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object value() {
            skip();
            char c = text.charAt(at);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (text.startsWith("true", at)) {
                at += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", at)) {
                at += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", at)) {
                at += 4;
                return null;
            }
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            return Double.parseDouble(text.substring(start, at));
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            skip();
            if (text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                at++; // :
                map.put(key, value());
                skip();
                if (text.charAt(at++) == '}') {
                    return map;
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            skip();
            if (text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                skip();
                if (text.charAt(at++) == ']') {
                    return list;
                }
            }
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            at++;
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(e);
                }
            }
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
