package forge.gui.download;

import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.util.FileUtil;
import org.tinylog.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Downloads every missing card and token image at full-resolution PNG, like
 * {@link GuiDownloadCardImages.Mode#MISSING_MAX}, but resolves the image URLs from
 * Scryfall's bulk data file instead of one rate-limited API call per image, and
 * downloads them in parallel.
 * <p>
 * {@code api.scryfall.com/...?format=image} is only a redirect service: it looks up
 * the card's {@code image_uris} and 302s to {@code cards.scryfall.io}. The bulk file
 * contains those same {@code image_uris}, so one download replaces ~100k API calls
 * and yields byte-identical files. Scryfall's docs require bulk data rather than the
 * API when resolving a large number of card images.
 * <p>
 * Which images are missing is decided by the parent, unchanged. If the bulk index
 * cannot be loaded the API URLs it built are kept, so this degrades to the behaviour
 * of the regular missing-images button rather than failing.
 */
public class GuiDownloadCardImagesBulk extends GuiDownloadCardImages {

    private static final String BULK_INFO_URL = "https://api.scryfall.com/bulk-data/default-cards";
    /** Kept modest: the CDN is not the rate-limited host, but there is no need to hammer it. */
    private static final int THREADS = 8;

    private Map<String, String> queued;
    private int directCount;

    public GuiDownloadCardImagesBulk() {
        super(Mode.MISSING_MAX);
    }

    @Override
    public String getTitle() {
        return "Download Missing Card Images (Max Resolution, Fast)";
    }

    @Override
    protected Map<String, String> getNeededFiles() {
        final Map<String, String> files = super.getNeededFiles();

        progressDescription("Downloading Scryfall bulk data...");
        final Map<String, String[]> index = loadBulkIndex();
        if (index != null) {
            progressDescription("Matching " + files.size() + " images...");
            for (final Map.Entry<String, String> entry : files.entrySet()) {
                final String direct = resolveDirect(index, entry.getValue());
                if (direct != null) {
                    // Direct CDN URL first, original API URL kept as a fallback.
                    entry.setValue(direct + URL_SEPARATOR + entry.getValue());
                    directCount++;
                }
            }
            System.out.println("Bulk index resolved " + directCount + " of " + files.size() + " images directly.");
        } else {
            System.out.println("Bulk index unavailable; falling back to per-image Scryfall API lookups.");
        }

        queued = files;
        return files;
    }

    // ------------------------------------------------------------------ bulk index

    /** @return "set|collectorNumber" -> { frontPngUrl, backPngUrl }, or null if unavailable. */
    private static Map<String, String[]> loadBulkIndex() {
        try {
            final String info = readAll(BULK_INFO_URL);
            final String uri = extract(info, "\"download_uri\":\"");
            if (uri == null) {
                return null;
            }
            final Map<String, String[]> index = new HashMap<>(150000);
            try (BufferedReader reader = openReader(uri)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // The bulk file is a JSON array with exactly one card object per line.
                    if (line.length() < 32 || line.charAt(0) != '{') {
                        continue;
                    }
                    parseCard(line, index);
                }
            }
            return index.isEmpty() ? null : index;
        } catch (final Exception e) {
            Logger.error(e, "Failed to load Scryfall bulk data");
            return null;
        }
    }

    private static void parseCard(final String line, final Map<String, String[]> index) {
        final String set = extract(line, "\"set\":\"");
        final String collector = extract(line, "\"collector_number\":\"");
        if (set == null || collector == null) {
            return;
        }

        // Faces carry their own image_uris; anything before card_faces is the card-level set.
        final int facesAt = line.indexOf("\"card_faces\":[");
        final List<String> cardLevel = new ArrayList<>(1);
        final List<String> faceLevel = new ArrayList<>(2);
        int i = 0;
        while ((i = line.indexOf("\"png\":\"", i)) >= 0) {
            final int start = i + 7;
            final int end = line.indexOf('"', start);
            if (end < 0) {
                break;
            }
            final String url = line.substring(start, end);
            if (facesAt >= 0 && i > facesAt) {
                faceLevel.add(url);
            } else {
                cardLevel.add(url);
            }
            i = end;
        }

        // Split/adventure/flip layouts have card_faces without images - use the card-level one.
        final String front = !faceLevel.isEmpty() ? faceLevel.get(0)
                : (!cardLevel.isEmpty() ? cardLevel.get(0) : null);
        final String back = faceLevel.size() > 1 ? faceLevel.get(1) : null;
        if (front == null && back == null) {
            return;
        }
        index.put(key(set, collector), new String[] { front, back });
    }

    /**
     * Maps one of the parent's Scryfall API URLs onto its direct CDN equivalent.
     * The API path is {@code <set>/<collectorNumber>/<lang>?...}, which is exactly the
     * key the bulk index is built on - so all of the parent's collector-number special
     * cases (meld, specialize, planechase) carry over for free.
     */
    private static String resolveDirect(final Map<String, String[]> index, final String candidates) {
        for (final String url : candidates.split(URL_SEPARATOR)) {
            if (!url.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD)) {
                continue;
            }
            String path = url.substring(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD.length());
            final boolean back = path.contains("face=back");
            final int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            final String[] parts = path.split("/");
            if (parts.length < 2) {
                continue;
            }
            final String[] urls = index.get(key(parts[0], urlDecode(parts[1])));
            if (urls == null) {
                continue;
            }
            final String direct = back ? urls[1] : urls[0];
            if (direct != null) {
                return direct;
            }
        }
        return null;
    }

    private static String key(final String set, final String collectorNumber) {
        return set.toLowerCase() + "|" + collectorNumber.toLowerCase();
    }

    @SuppressWarnings("deprecation")
    private static String urlDecode(final String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (final Exception e) {
            return s;
        }
    }

    private static String extract(final String source, final String token) {
        if (source == null) {
            return null;
        }
        final int start = source.indexOf(token);
        if (start < 0) {
            return null;
        }
        final int from = start + token.length();
        final int end = source.indexOf('"', from);
        return end < 0 ? null : source.substring(from, end);
    }

    private static String readAll(final String url) throws IOException {
        try (BufferedReader reader = openReader(url)) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static BufferedReader openReader(final String url) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        identify(conn);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        InputStream in = conn.getInputStream();
        if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20);
    }

    // ------------------------------------------------------------------ parallel download

    @Override
    public void run() {
        final Map<String, String> files = queued;
        if (files == null || files.isEmpty()) {
            FThreads.invokeInEdtLater(this::finish);
            return;
        }

        GuiBase.getInterface().preventSystemSleep(true);
        final int total = files.size();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (final Map.Entry<String, String> entry : files.entrySet()) {
                pool.submit(() -> {
                    if (!cancel && !download(entry.getKey(), entry.getValue())) {
                        failed.incrementAndGet();
                    }
                    report(done.incrementAndGet(), total, failed.get());
                });
            }
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.DAYS);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        } finally {
            GuiBase.getInterface().preventSystemSleep(false);
        }

        FThreads.invokeInEdtLater(this::finish);
    }

    private boolean download(final String destPath, final String candidates) {
        final File dest = new File(destPath);
        for (final String url : candidates.split(URL_SEPARATOR)) {
            if (cancel) {
                return false;
            }
            if (url.isEmpty()) {
                continue;
            }
            // Write to a temp file first: a half-written PNG would look like a successful
            // upgrade and let the old JPG be deleted.
            final File part = new File(destPath + ".part");
            try {
                if (!FileUtil.ensureDirectoryExists(dest.getParentFile())) {
                    return false;
                }
                final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(getProxy());
                conn.setInstanceFollowRedirects(true);
                identify(conn);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(180000);
                final int code = conn.getResponseCode();
                if (code == HTTP_TOO_MANY_REQUESTS) {
                    // Only the API host rate-limits, so this should not fire against the
                    // CDN - but honour it rather than retrying straight away.
                    conn.disconnect();
                    Thread.sleep(RATE_LIMIT_BACKOFF_MS);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect();
                    continue;
                }
                try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    final byte[] buffer = new byte[1 << 14];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                if (part.length() > 0) {
                    Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return true;
                }
            } catch (final Exception e) {
                // fall through and try the next candidate
            } finally {
                if (part.exists()) {
                    part.delete();
                }
            }
        }
        return false;
    }

    private void report(final int done, final int total, final int failed) {
        // Throttle EDT traffic; the pool finishes items far faster than the bar can redraw.
        if (done != total && done % 25 != 0) {
            return;
        }
        if (progressBar == null) {
            return;
        }
        FThreads.invokeInEdtLater(() -> {
            progressBar.setValue(done);
            progressBar.setDescription(done + "/" + total
                    + (failed > 0 ? "  (" + failed + " failed)" : "")
                    + (done == total ? " - finished, please close." : ""));
        });
    }

    private void progressDescription(final String text) {
        if (progressBar == null) {
            return;
        }
        FThreads.invokeInEdtLater(() -> progressBar.setDescription(text));
    }
}
