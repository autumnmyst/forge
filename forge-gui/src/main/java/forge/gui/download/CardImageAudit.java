package forge.gui.download;

import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Counts how many cached card and token images are stored as PNG (the maximum
 * resolution Scryfall offers) versus JPG (normal resolution), so the user can see
 * how much of their collection is already at full quality.
 */
public final class CardImageAudit {

    private CardImageAudit() { }

    /** PNG/JPG tally for one image directory. */
    public static final class Counts {
        private int png;
        private int jpg;
        /** Paths of the JPG (normal resolution) images, relative to their cache directory. */
        private final List<String> jpgNames = new ArrayList<>();

        public int getPng() { return png; }
        public int getJpg() { return jpg; }
        public int getTotal() { return png + jpg; }

        public double getPngPercent() { return getTotal() == 0 ? 0d : png * 100d / getTotal(); }
        public double getJpgPercent() { return getTotal() == 0 ? 0d : jpg * 100d / getTotal(); }

        /** The normal-resolution images, sorted. */
        public List<String> getJpgNames() {
            Collections.sort(jpgNames, String.CASE_INSENSITIVE_ORDER);
            return jpgNames;
        }

        void add(Counts other) {
            png += other.png;
            jpg += other.jpg;
            jpgNames.addAll(other.jpgNames);
        }
    }

    /** Separate tallies for cards and tokens, plus their combined total. */
    public static final class Result {
        private final Counts cards = new Counts();
        private final Counts tokens = new Counts();

        public Counts getCards() { return cards; }
        public Counts getTokens() { return tokens; }

        public Counts getCombined() {
            final Counts all = new Counts();
            all.add(cards);
            all.add(tokens);
            return all;
        }
    }

    /** Walks the card and token image caches. Safe to call off the EDT; it only reads. */
    public static Result scan() {
        final Result result = new Result();
        final File cards = new File(ForgeConstants.CACHE_CARD_PICS_DIR);
        final File tokens = new File(ForgeConstants.CACHE_TOKEN_PICS_DIR);
        count(cards, cards, result.cards);
        count(tokens, tokens, result.tokens);
        return result;
    }

    private static void count(final File root, final File dir, final Counts counts) {
        final File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (final File f : entries) {
            if (f.isDirectory()) {
                count(root, f, counts);
            } else {
                final String name = f.getName().toLowerCase();
                if (name.endsWith(".png")) {
                    counts.png++;
                } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                    counts.jpg++;
                    counts.jpgNames.add(relative(root, f));
                }
            }
        }
    }

    /** Path of {@code f} below {@code root}, so the listing reads like "SET/name.jpg". */
    private static String relative(final File root, final File f) {
        final String rootPath = root.getAbsolutePath();
        final String filePath = f.getAbsolutePath();
        if (filePath.startsWith(rootPath)) {
            String rel = filePath.substring(rootPath.length());
            while (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            return rel.replace('\\', '/');
        }
        return f.getName();
    }
}
