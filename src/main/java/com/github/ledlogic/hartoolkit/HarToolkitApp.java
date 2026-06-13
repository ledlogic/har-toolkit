package com.github.ledlogic.hartoolkit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * HarToolkitApp - Combined HAR image extractor + PDF assembler
 *
 * Scans ./data/ for HAR files named YYYY-MM.har.
 * For each one:
 *   1. Derives output directory: G:\My Drive\STL\UNIT9\Cyberdreams\YYYY-MM-mmf
 *      (creates it if it does not already exist)
 *   2. Downloads large JPEG images from the HAR's request URLs (same logic as
 *      HarImageApp) into that directory, preserving HAR order.
 *   3. Assembles all downloaded images — in HAR URL order — into a PDF named
 *      YYYY-MM.pdf inside the same directory (same logic as ImageToPdfAssembler).
 *
 * Usage:
 *   java HarToolkitApp [options]
 *
 * Options:
 *   --data-dir   <dir>   Directory containing *.har files     (default: ./data)
 *   --output-base <dir>  Parent directory for YYYY-MM-mmf dirs
 *                        (default: G:\My Drive\STL\UNIT9\Cyberdreams)
 *   --threads    <n>     Parallel download threads             (default: 4)
 *   --timeout    <secs>  HTTP timeout per request              (default: 30)
 *   --also-response      Also extract base64 JPEGs from HAR response bodies
 *   --verbose            Print each URL, byte count, and PDF step
 *   --skip-pdf           Download images only; skip PDF assembly
 *   --skip-download      Skip download; assemble PDF from existing images only
 */
public class HarToolkitApp {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String DEFAULT_DATA_DIR   = "data";
    private static final String DEFAULT_OUTPUT_BASE =
            "G:\\My Drive\\STL\\UNIT9\\Cyberdreams";

    /** Pattern matching HAR filenames of the form YYYY-MM.har */
    private static final Pattern HAR_NAME_PAT =
            Pattern.compile("^(\\d{4}-\\d{2})\\.har$", Pattern.CASE_INSENSITIVE);

    // PDF page size: letter portrait
    private static final PDRectangle LETTER = PDRectangle.LETTER; // 612 x 792 pt

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("--help") || args[0].equals("-h"))) {
            printHelp();
            System.exit(0);
        }

        String  dataDir      = DEFAULT_DATA_DIR;
        String  outputBase   = DEFAULT_OUTPUT_BASE;
        int     threads      = 4;
        int     timeoutSecs  = 30;
        boolean alsoResponse = false;
        boolean verbose      = false;
        boolean skipPdf      = false;
        boolean skipDownload = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir":      dataDir      = args[++i]; break;
                case "--output-base":   outputBase   = args[++i]; break;
                case "--threads":       threads      = Integer.parseInt(args[++i]); break;
                case "--timeout":       timeoutSecs  = Integer.parseInt(args[++i]); break;
                case "--also-response": alsoResponse = true;  break;
                case "--verbose":       verbose      = true;  break;
                case "--skip-pdf":      skipPdf      = true;  break;
                case "--skip-download": skipDownload = true;  break;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printHelp();
                    System.exit(1);
            }
        }

        dataDir    = cleanPath(dataDir);
        outputBase = cleanPath(outputBase);

        List<Path> harFiles = collectHarFiles(dataDir);
        if (harFiles.isEmpty()) {
            System.out.println("No YYYY-MM.har files found in: " + dataDir);
            System.exit(0);
        }
        System.out.printf("Found %d HAR file(s) to process.%n%n", harFiles.size());

        for (int i = 0; i < harFiles.size(); i++) {
            Path harFile = harFiles.get(i);
            if (harFiles.size() > 1) {
                System.out.printf("─── [%d/%d] %s ───%n%n",
                        i + 1, harFiles.size(), harFile.getFileName());
            }
            processHar(harFile, outputBase, threads, timeoutSecs,
                       alsoResponse, verbose, skipPdf, skipDownload);
        }

        if (harFiles.size() > 1) {
            System.out.printf("%n=== All done. Processed %d HAR file(s). ===%n", harFiles.size());
        }
    }

    // ── Collect HAR files ─────────────────────────────────────────────────────

    static List<Path> collectHarFiles(String dataDir) throws IOException {
        Path dir = Paths.get(dataDir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.err.println("Data directory not found: " + dataDir);
            System.exit(1);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(f -> HAR_NAME_PAT.matcher(f.getFileName().toString()).matches())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    // ── Derive names from a YYYY-MM.har path ─────────────────────────────────

    /** Extract the "YYYY-MM" stem from the HAR filename. */
    static String stemOf(Path harFile) {
        Matcher m = HAR_NAME_PAT.matcher(harFile.getFileName().toString());
        if (!m.matches()) throw new IllegalArgumentException("Not a YYYY-MM.har file: " + harFile);
        return m.group(1);   // e.g. "2022-02"
    }

    /** Output directory: <outputBase>\YYYY-MM-mmf */
    static Path outputDirFor(Path harFile, String outputBase) {
        return Paths.get(outputBase, stemOf(harFile) + "-mmf");
    }

    /** PDF path: <outputDir>\YYYY-MM.pdf */
    static Path pdfPathFor(Path outputDir, String stem) {
        return outputDir.resolve(stem + ".pdf");
    }

    // ── Process one HAR file ──────────────────────────────────────────────────

    static void processHar(Path harFile, String outputBase, int threads, int timeoutSecs,
                           boolean alsoResponse, boolean verbose,
                           boolean skipPdf, boolean skipDownload) throws Exception {

        String stem      = stemOf(harFile);
        Path   outputDir = outputDirFor(harFile, outputBase);
        Path   pdfPath   = pdfPathFor(outputDir, stem);

        System.out.println("=== HarToolkitApp ===");
        System.out.println("HAR file   : " + harFile.toAbsolutePath());
        System.out.println("Output dir : " + outputDir.toAbsolutePath());
        System.out.println("PDF output : " + pdfPath.toAbsolutePath());
        System.out.println();

        // Create output directory if it doesn't exist
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
            System.out.println("Created output directory: " + outputDir.toAbsolutePath());
        } else {
            System.out.println("Output directory already exists — skipping to next step.");
        }

        // ── Step 1: Download images ───────────────────────────────────────────

        // orderedFilenames preserves the HAR URL order; used later for PDF assembly.
        // Maps filename -> download status so we know the order even for already-existing files.
        List<String> harOrderedFilenames = new ArrayList<>();

        if (!skipDownload) {
            harOrderedFilenames = downloadImages(harFile, outputDir, threads, timeoutSecs,
                                                 alsoResponse, verbose);
        } else {
            System.out.println("[--skip-download] Skipping image download.");
            // Fall back to sorted directory listing for PDF order
        }

        // ── Step 2: Assemble PDF ──────────────────────────────────────────────

        if (!skipPdf) {
            if (Files.exists(pdfPath)) {
                System.out.printf("%nPDF already exists, skipping assembly: %s%n", pdfPath.getFileName());
            } else {
                assemblePdf(outputDir, harOrderedFilenames, pdfPath, verbose);
            }
        } else {
            System.out.println("[--skip-pdf] Skipping PDF assembly.");
        }

        System.out.println();
    }

    // ── Image download (from HarImageApp) ────────────────────────────────────

    /**
     * Downloads images from the HAR file into outputDir.
     * Returns the list of output filenames in the order they appeared in the HAR.
     */
    static List<String> downloadImages(Path harFile, Path outputDir, int threads,
                                       int timeoutSecs, boolean alsoResponse,
                                       boolean verbose) throws Exception {

        System.out.print("Reading HAR file... ");
        String harJson = Files.readString(harFile);
        System.out.printf("done (%.1f MB)%n", harJson.length() / 1024.0 / 1024.0);

        System.out.print("Extracting image URLs... ");
        List<String[]> allPairs = extractUrlMimePairs(harJson);
        List<String> jpegUrls = new ArrayList<>();
        for (String[] pair : allPairs) {
            if (!isJpegUrl(pair[0], pair[1])) continue;
            String url   = pair[0];
            String lower = url.toLowerCase();
            if (lower.contains("70x70")    || lower.contains("70X70"))    continue;
            if (lower.contains("230x230")  || lower.contains("230X230"))  continue;
            if (lower.contains("/object/"))                                continue;
            jpegUrls.add(url);
        }
        // Deduplicate while preserving HAR order
        LinkedHashSet<String> seen = new LinkedHashSet<>(jpegUrls);
        jpegUrls = new ArrayList<>(seen);

        long count720 = jpegUrls.stream().filter(HarToolkitApp::is720).count();
        System.out.printf("found %d unique JPEG URL(s) (%d are 720x720)%n",
                jpegUrls.size(), count720);

        // Map from URL index -> filename (HAR order)
        // We build this before spawning threads so order is deterministic.
        LinkedHashMap<Integer, String> indexToFilename = new LinkedHashMap<>();
        for (int i = 0; i < jpegUrls.size(); i++) {
            indexToFilename.put(i, urlToFilename(jpegUrls.get(i), i + 1));
        }

        int savedBase64 = 0;
        if (alsoResponse) {
            System.out.print("Extracting embedded base64 JPEG response bodies... ");
            List<String[]> b64list = extractBase64Images(harJson);
            System.out.println("found " + b64list.size());
            for (int i = 0; i < b64list.size(); i++) {
                String b64 = b64list.get(i)[1].replaceAll("\\s+", "");
                try {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    if (!isLargeEnough(bytes)) {
                        if (verbose) {
                            int[] d = getImageDimensions(bytes);
                            System.out.printf("  [base64] SMALL %dx%d, skipped entry %d%n", d[0], d[1], i);
                        }
                        continue;
                    }
                    Path dest = outputDir.resolve(String.format("b64_%04d.jpg", i + 1));
                    Files.write(dest, bytes);
                    savedBase64++;
                    if (verbose) System.out.printf("  [base64] saved -> %s%n", dest.getFileName());
                } catch (IllegalArgumentException ex) {
                    System.err.printf("  [base64] skipped entry %d (not valid base64)%n", i);
                }
            }
        }

        if (jpegUrls.isEmpty() && savedBase64 == 0) {
            System.out.println("\nNo JPEG images found in this HAR file.");
            return new ArrayList<>();
        }

        System.out.printf("%nDownloading %d image(s) — Pass 1: large images (>1000px)...%n%n",
                jpegUrls.size());

        final boolean finalVerbose   = verbose;
        final int     finalTimeout   = timeoutSecs;
        final List<String> finalUrls = jpegUrls;

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(finalTimeout))
                .build();

        ExecutorService pool    = Executors.newFixedThreadPool(threads);
        AtomicInteger ok        = new AtomicInteger();
        AtomicInteger failedC   = new AtomicInteger();
        AtomicInteger skipped   = new AtomicInteger();
        AtomicInteger filtered  = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        // Track which indices were actually saved (thread-safe)
        ConcurrentHashMap<Integer, Boolean> savedIndices = new ConcurrentHashMap<>();
        // Collect 720px fallback candidates
        ConcurrentHashMap<String, Object[]> smallImages  = new ConcurrentHashMap<>();

        for (int idx = 0; idx < finalUrls.size(); idx++) {
            final int    i        = idx;
            final String url      = finalUrls.get(i);
            final String filename = indexToFilename.get(i);

            futures.add(pool.submit(() -> {
                Path dest = outputDir.resolve(filename);
                if (Files.exists(dest)) {
                    if (finalVerbose)
                        System.out.printf("  [SKIP]  %s (already exists)%n", filename);
                    skipped.incrementAndGet();
                    savedIndices.put(i, true);   // count as present for PDF ordering
                    return;
                }
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(finalTimeout))
                            .header("User-Agent", "HarImageDownloader/1.0")
                            .GET().build();
                    HttpResponse<byte[]> resp =
                            client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (resp.statusCode() == 200) {
                        byte[] body  = resp.body();
                        int[]  dim   = getImageDimensions(body);
                        if (!isLargeEnough(body)) {
                            filtered.incrementAndGet();
                            if (finalVerbose)
                                System.out.printf("  [SMALL] %dx%d — %s%n", dim[0], dim[1], filename);
                            if (is720(url)) {
                                int maxDim = Math.max(dim[0], dim[1]);
                                if (maxDim <= 0) maxDim = body.length;
                                smallImages.put(url, new Object[]{body, maxDim, filename, i});
                            }
                            return;
                        }
                        Files.write(dest, body);
                        savedIndices.put(i, true);
                        int n = ok.incrementAndGet();
                        if (finalVerbose)
                            System.out.printf("  [OK]    %s (%,d bytes)%n", filename, body.length);
                        else
                            System.out.printf("  [%4d/%d] %s%n", n, finalUrls.size(), filename);
                    } else {
                        failedC.incrementAndGet();
                        System.out.printf("  [FAIL]  HTTP %d — %s%n", resp.statusCode(), url);
                    }
                } catch (Exception ex) {
                    failedC.incrementAndGet();
                    System.out.printf("  [ERR]   %s — %s%n", ex.getClass().getSimpleName(), url);
                    if (finalVerbose) ex.printStackTrace(System.err);
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (ExecutionException e) { /* already logged */ }
        }
        pool.shutdown();

        // ── Fallback pass ─────────────────────────────────────────────────────
        int fallbackSaved = 0;
        System.out.printf("%nPass 1 complete: %d large saved, %d small collected.%n",
                ok.get(), smallImages.size());
        if (ok.get() == 0 && !smallImages.isEmpty()) {
            int bestDim = smallImages.values().stream()
                    .mapToInt(v -> (int) v[1])
                    .max().orElse(0);
            System.out.printf("%nNo large images found. Falling back — saving all %d small image(s) (%dpx).%n%n",
                    smallImages.size(), bestDim);
            int fallbackIdx = 1;
            for (Object[] entry : smallImages.values()) {
                byte[]  body     = (byte[]) entry[0];
                String  filename = (String) entry[2];
                int     origIdx  = (int)    entry[3];
                Path    dest     = outputDir.resolve(filename);
                if (!Files.exists(dest)) {
                    Files.write(dest, body);
                    savedIndices.put(origIdx, true);
                    fallbackSaved++;
                    System.out.printf("  [FALLBACK %d] %s%n", fallbackIdx++, filename);
                }
            }
        }

        System.out.println("\n=== Download Summary ===");
        System.out.printf("  Downloaded   : %d%n", ok.get());
        if (fallbackSaved > 0)
            System.out.printf("  Fallback saved: %d%n", fallbackSaved);
        System.out.printf("  Too small    : %d%n", filtered.get());
        System.out.printf("  Failed       : %d%n", failedC.get());
        System.out.printf("  Skipped      : %d%n", skipped.get());
        if (alsoResponse)
            System.out.printf("  Base64 saved : %d%n", savedBase64);
        System.out.println("  Output dir   : " + outputDir.toAbsolutePath());

        // Return filenames in original HAR order, only for indices that were saved
        List<String> result = new ArrayList<>();
        for (Map.Entry<Integer, String> e : indexToFilename.entrySet()) {
            if (savedIndices.containsKey(e.getKey())) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    // ── PDF assembly (from ImageToPdfAssembler) ───────────────────────────────

    /**
     * Assembles images into a letter-sized PDF.
     *
     * @param outputDir           Directory containing the images.
     * @param harOrderedFilenames If non-empty, images are assembled in this order
     *                            (the order they appeared in the HAR file).
     *                            If empty (e.g. --skip-download), falls back to
     *                            natural-sort of all PNG/JPEG files in the directory.
     * @param pdfPath             Destination PDF file.
     * @param verbose             Print per-image progress.
     */
    static void assemblePdf(Path outputDir, List<String> harOrderedFilenames,
                            Path pdfPath, boolean verbose) throws IOException {

        System.out.printf("%n=== PDF Assembly ===%n");

        List<Path> imageFiles;
        if (!harOrderedFilenames.isEmpty()) {
            // Use HAR order — resolve each filename to its full path
            imageFiles = harOrderedFilenames.stream()
                    .map(name -> outputDir.resolve(name))
                    .filter(Files::exists)          // skip any that failed to download
                    .collect(Collectors.toList());
            System.out.printf("Assembling %d image(s) in HAR order...%n", imageFiles.size());
        } else {
            // Fallback: natural-sort all PNG/JPEG files in the directory
            Pattern imgPat = Pattern.compile("^.+\\.(png|jpe?g)$", Pattern.CASE_INSENSITIVE);
            try (Stream<Path> stream = Files.list(outputDir)) {
                imageFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> imgPat.matcher(p.getFileName().toString()).matches())
                        .sorted((a, b) -> naturalCompare(
                                a.getFileName().toString(), b.getFileName().toString()))
                        .collect(Collectors.toList());
            }
            System.out.printf("Assembling %d image(s) in natural-sort order...%n", imageFiles.size());
        }

        if (imageFiles.isEmpty()) {
            System.out.println("No images found — skipping PDF creation.");
            return;
        }

        float pageWidth  = LETTER.getWidth();   // 612 pt
        float pageHeight = LETTER.getHeight();  // 792 pt

        try (PDDocument doc = new PDDocument()) {
            for (Path imgPath : imageFiles) {
                if (verbose) System.out.println("  Adding: " + imgPath.getFileName());

                PDImageXObject image =
                        PDImageXObject.createFromFile(imgPath.toAbsolutePath().toString(), doc);

                float imgW = image.getWidth();
                float imgH = image.getHeight();

                // Rotate 90° CCW if it fills more of the page
                float areaNormal  = coveredArea(imgW, imgH, pageWidth, pageHeight);
                float areaRotated = coveredArea(imgH, imgW, pageWidth, pageHeight);
                boolean rotate    = areaRotated > areaNormal;

                float effectiveW   = rotate ? imgH : imgW;
                float effectiveH   = rotate ? imgW : imgH;
                float scale        = fitScale(effectiveW, effectiveH, pageWidth, pageHeight);
                float scaledWidth  = effectiveW * scale;
                float scaledHeight = effectiveH * scale;
                float x            = (pageWidth  - scaledWidth)  / 2;
                float y            = (pageHeight - scaledHeight) / 2;

                PDPage page = new PDPage(LETTER);
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    if (rotate) {
                        // 90° CCW affine transform: [0, scale, -scale, 0, tx, ty]
                        // tx = x + scaledWidth, ty = y
                        cs.transform(new org.apache.pdfbox.util.Matrix(
                                0,                 scale,
                                -scale,            0,
                                x + scaledWidth,   y
                        ));
                        cs.drawImage(image, 0, 0, imgW, imgH);
                        if (verbose) System.out.println("    → Rotated 90° CCW");
                    } else {
                        cs.drawImage(image, x, y, scaledWidth, scaledHeight);
                    }
                }
            }
            doc.save(pdfPath.toFile());
        }

        System.out.printf("PDF created (%d pages): %s%n", imageFiles.size(), pdfPath.toAbsolutePath());
    }

    // ── HAR parsing helpers (from HarImageApp) ────────────────────────────────

    static List<String[]> extractUrlMimePairs(String json) {
        List<String[]> pairs = new ArrayList<>();
        Pattern urlPat  = Pattern.compile("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern mimePat = Pattern.compile("\"mimeType\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher um = urlPat.matcher(json);
        while (um.find()) {
            String url  = unescape(um.group(1));
            int    end  = Math.min(um.end() + 2000, json.length());
            String slice = json.substring(um.end(), end);
            Matcher mm  = mimePat.matcher(slice);
            String mime = mm.find() ? unescape(mm.group(1)) : "";
            pairs.add(new String[]{url, mime});
        }
        return pairs;
    }

    static List<String[]> extractBase64Images(String json) {
        List<String[]> results = new ArrayList<>();
        Pattern p = Pattern.compile(
            "\"mimeType\"\\s*:\\s*\"(image/jpe?g)\"[^}]{0,500}?\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);
        Matcher m = p.matcher(json);
        while (m.find()) results.add(new String[]{m.group(1), m.group(2)});
        return results;
    }

    static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    static boolean isJpegUrl(String url, String mime) {
        String lower = url.toLowerCase();
        return lower.contains(".jpg") || lower.contains(".jpeg")
            || mime.toLowerCase().contains("image/jpeg")
            || mime.toLowerCase().contains("image/jpg");
    }

    static String urlToFilename(String rawUrl, int index) {
        try {
            URI    uri  = new URI(rawUrl);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/image";
            String name = Paths.get(path).getFileName().toString();
            name = name.replaceAll("[?#&=].*", "").replaceAll("[^a-zA-Z0-9._\\-]", "_");
            if (!name.toLowerCase().endsWith(".jpg") && !name.toLowerCase().endsWith(".jpeg"))
                name += ".jpg";
            return String.format("%04d_%s", index, name);
        } catch (Exception e) {
            return String.format("%04d_image.jpg", index);
        }
    }

    static boolean is720(String url) {
        return url.contains("720X720") || url.contains("720x720");
    }

    static int[] getImageDimensions(byte[] bytes) {
        try (MemoryCacheImageInputStream iis =
                new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
            reader.setInput(iis, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            reader.dispose();
            return new int[]{w, h};
        } catch (IOException | java.util.NoSuchElementException e) {
            return new int[]{-1, -1};
        }
    }

    static boolean isLargeEnough(byte[] bytes) {
        int[] dim = getImageDimensions(bytes);
        return dim[0] > 1000 || dim[1] > 1000;
    }

    // ── PDF helpers (from ImageToPdfAssembler) ────────────────────────────────

    private static float fitScale(float imgW, float imgH, float pageW, float pageH) {
        return Math.min(pageW / imgW, pageH / imgH);
    }

    private static float coveredArea(float imgW, float imgH, float pageW, float pageH) {
        float s = fitScale(imgW, imgH, pageW, pageH);
        return (imgW * s) * (imgH * s);
    }

    // ── Natural sort (from ImageToPdfAssembler) ───────────────────────────────

    private static int naturalCompare(String a, String b) {
        String[] pa = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] pb = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int cmp = pa[i].matches("\\d+") && pb[i].matches("\\d+")
                ? Integer.compare(Integer.parseInt(pa[i]), Integer.parseInt(pb[i]))
                : pa[i].compareTo(pb[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(pa.length, pb.length);
    }

    // ── Misc utils ────────────────────────────────────────────────────────────

    static String cleanPath(String s) {
        if (s == null) return s;
        s = s.replace("\"", "").trim();
        while (s.endsWith("\\") || s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        return s;
    }

    static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java HarToolkitApp [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --data-dir    <dir>   HAR file directory        (default: ./data)");
        System.out.println("  --output-base <dir>   Parent for YYYY-MM-mmf dirs");
        System.out.println("                        (default: G:\\My Drive\\STL\\UNIT9\\Cyberdreams)");
        System.out.println("  --threads     <n>     Download threads           (default: 4)");
        System.out.println("  --timeout     <secs>  HTTP timeout per request   (default: 30)");
        System.out.println("  --also-response       Extract base64 JPEGs from response bodies");
        System.out.println("  --verbose             Print per-image detail");
        System.out.println("  --skip-pdf            Download only; skip PDF assembly");
        System.out.println("  --skip-download       Assemble PDF from existing images only");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java HarToolkitApp");
        System.out.println("  java HarToolkitApp --data-dir C:\\hars --threads 8 --verbose");
        System.out.println("  java HarToolkitApp --skip-download   # re-assemble PDFs only");
    }
}