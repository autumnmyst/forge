package forge.gui.download;

import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardSplitType;
import forge.item.IPaperCard;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.ImageUtil;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bulk-downloads card and token images straight from the source, mirroring the
 * auto-downloader's Scryfall path so the images are actually available. Cards that
 * lack a collector number fall back to the cardforge hosted server.
 *
 * @see Mode
 */
public class GuiDownloadCardImages extends GuiDownloadService {
    /** Scryfall image version for standard (normal) resolution. */
    public static final String VERSION_STANDARD = "normal";
    /** Scryfall image version for the highest resolution available. */
    public static final String VERSION_MAX = "png";

    /** The five faces a Specialize card can take, as ImageUtil names them. */
    private static final String[] SPECIALIZE_FACES = { "white", "blue", "black", "red", "green" };

    public enum Mode {
        /** Fetch anything not cached yet, at normal resolution. */
        MISSING_STANDARD,
        /** Fetch anything not cached yet, at the highest resolution available. */
        MISSING_MAX,
        /** Re-fetch already-cached normal-resolution images as max-resolution PNGs. */
        UPGRADE_TO_MAX
    }

    private final Mode mode;
    private final String imageVersion;

    /** Max-resolution PNG destination -> the normal-resolution file it replaces. */
    private final Map<String, String> supersededJpgs = new HashMap<>();

    public GuiDownloadCardImages(boolean maxResolution) {
        this(maxResolution ? Mode.MISSING_MAX : Mode.MISSING_STANDARD);
    }

    public GuiDownloadCardImages(Mode mode) {
        this.mode = mode;
        this.imageVersion = mode == Mode.MISSING_STANDARD ? VERSION_STANDARD : VERSION_MAX;
    }

    @Override
    public String getTitle() {
        return switch (mode) {
            case MISSING_STANDARD -> "Download Missing Card Images";
            case MISSING_MAX -> "Download Missing Card Images (Max Resolution)";
            case UPGRADE_TO_MAX -> "Update Card Images to Max Resolution";
        };
    }

    /** Extension the downloaded file is stored under. */
    private String extension() {
        return mode == Mode.MISSING_STANDARD ? ".jpg" : ".png";
    }

    @Override
    protected Map<String, String> getNeededFiles() {
        final Map<String, String> downloads = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (final PaperCard c : Iterables.concat(
                FModel.getMagicDb().getCommonCards().getAllCards(),
                FModel.getMagicDb().getVariantCards().getAllCards())) {

            final String setCode3 = c.getEdition();
            if (StringUtils.isBlank(setCode3) || CardEdition.UNKNOWN_CODE.equals(setCode3)) { continue; }

            addCard(c, "", downloads);
            if (c.hasBackFace()) {
                addCard(c, "back", downloads);
            }
            // Specialize faces each have their own Scryfall entry and their own image
            // key, but hasBackFace() doesn't report them, so they need asking for
            // explicitly (see ImageFetcher's SPECFACE_* handling).
            if (c.getRules().getSplitType() == CardSplitType.Specialize) {
                for (final String face : SPECIALIZE_FACES) {
                    addCard(c, face, downloads);
                }
            }
        }

        // Tokens routed through the hosted token-images.txt list (mods / custom sets /
        // tokens without a collector number). Only one hosted resolution exists, so
        // there is nothing to upgrade there.
        if (mode != Mode.UPGRADE_TO_MAX) {
            addMissingItems(downloads, ForgeConstants.IMAGE_LIST_TOKENS_FILE, ForgeConstants.CACHE_TOKEN_PICS_DIR);
        }

        // The hosted list only covers a subset. Enumerate every edition's registered
        // tokens and pull them straight from Scryfall, mirroring the on-demand fetcher
        // (ImageFetcher) so tokens available on hover are also covered.
        for (final CardEdition edition : StaticData.instance().getEditions()) {
            addEditionTokens(edition, downloads);
        }

        return downloads;
    }

    @Override
    public void run() {
        super.run();
        if (mode == Mode.UPGRADE_TO_MAX) {
            removeSupersededImages();
        }
    }

    /**
     * Drops each normal-resolution file whose max-resolution replacement actually
     * made it to disk. Anything that failed to download keeps its existing image.
     */
    protected void removeSupersededImages() {
        int removed = 0;
        for (final Map.Entry<String, String> entry : supersededJpgs.entrySet()) {
            final File png = new File(entry.getKey());
            final File jpg = new File(entry.getValue());
            if (png.isFile() && png.length() > 0 && jpg.isFile() && jpg.delete()) {
                removed++;
            }
        }
        System.out.println("Replaced " + removed + " normal-resolution image(s) with max-resolution PNGs.");
    }

    // -------------------------------------------------------------------------

    private void addEditionTokens(CardEdition edition, Map<String, String> downloads) {
        // Custom/unknown sets have no Scryfall token code; they rely on the
        // hosted token-images.txt list (same as the on-demand fetcher).
        if (edition.getType() == CardEdition.Type.CUSTOM_SET) { return; }

        addEditionEntries(edition, edition.getTokens(), downloads);
        // [other] holds emblems, embalm/eternalize helper cards, monarch, blessing,
        // manifest, morph and friends. The game requests these exactly like tokens
        // (see StaticData.getOtherImageKey), so they download the same way.
        addEditionEntries(edition, edition.getOther(), downloads);
    }

    private void addEditionEntries(CardEdition edition, Multimap<String, CardEdition.EditionEntry> entries,
            Map<String, String> downloads) {
        if (entries.isEmpty()) { return; }

        final String editionCode = edition.getCode();
        final String tokensCode = edition.getTokensCode();
        final String scryfallCode = edition.getScryfallCode();
        if (StringUtils.isBlank(tokensCode) && StringUtils.isBlank(scryfallCode)) { return; }
        final String langCode = edition.getCardsLangCode();

        for (Map.Entry<String, CardEdition.EditionEntry> entry : entries.entries()) {
            final String tokenName = entry.getKey();
            final String collectorNumber = entry.getValue().collectorNumber();
            // Scryfall lookups need a collector number; those without one can
            // only come from the hosted list handled above.
            if (StringUtils.isBlank(collectorNumber)) { continue; }

            // Destination filename must match what the game requests on hover:
            // <editionCode>/<collectorNumber>_<tokenName> (see ImageFetcher).
            final String key = editionCode + "/" + collectorNumber + "_" + tokenName;

            final File dest;
            if (mode == Mode.UPGRADE_TO_MAX) {
                if (tokenFile(key, ".png").isFile()) { continue; } // already max resolution
                if (!tokenFile(key, ".jpg").isFile()) { continue; } // nothing cached to upgrade
                dest = tokenFile(key, ".png");
            } else {
                // Resolve the way the game does, not by exact path: an image cached under
                // any name ImageKeys accepts is already usable and must not be re-fetched.
                if (tokenImageExists(editionCode, collectorNumber, tokenName)) { continue; }
                dest = tokenFile(key, extension());
            }
            if (downloads.containsKey(dest.getAbsolutePath())) { continue; }

            final List<String> candidates = new ArrayList<>(4);
            addTokenUrl(candidates, collectorNumber, tokensCode, langCode);
            addTokenUrl(candidates, collectorNumber, tokensCode, "");
            // Some sets have no "T"-prefixed token set on Scryfall (PL26, PLG24, ...);
            // their tokens sit in the base set alongside the cards.
            addTokenUrl(candidates, collectorNumber, scryfallCode, langCode);
            addTokenUrl(candidates, collectorNumber, scryfallCode, "");
            if (candidates.isEmpty()) { continue; }

            downloads.put(dest.getAbsolutePath(), String.join(GuiDownloadService.URL_SEPARATOR, candidates));
            if (mode == Mode.UPGRADE_TO_MAX) {
                supersededJpgs.put(dest.getAbsolutePath(), tokenFile(key, ".jpg").getAbsolutePath());
            }
        }
    }

    private void addTokenUrl(List<String> candidates, String collectorNumber, String setCode, String langCode) {
        if (StringUtils.isBlank(setCode)) { return; }
        final String url = ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD
                + ImageUtil.getScryfallTokenDownloadUrl(collectorNumber, setCode, langCode, "", imageVersion);
        if (!candidates.contains(url)) { candidates.add(url); }
    }

    /** Mirrors ImageKeys.getImageFile()'s lookup order for a token image key. */
    private static boolean tokenImageExists(String editionCode, String collectorNumber, String tokenName) {
        final String[] keys = {
                tokenName + "_" + editionCode + "_" + collectorNumber,
                editionCode + "/" + collectorNumber + "_" + tokenName,
                editionCode + "/" + tokenName,
                tokenName };
        for (String k : keys) {
            if (tokenFile(k, ".jpg").isFile() || tokenFile(k, ".png").isFile()) { return true; }
        }
        return false;
    }

    private void addCard(PaperCard c, String face, Map<String, String> downloads) {
        final String imageKey = ImageUtil.getImageKey(c, face, true);
        if (imageKey == null) { return; }

        // The fullborder variant that the image fetcher produces from Scryfall
        final String fbKey = TextUtil.fastReplace(imageKey, ".full", ".fullborder") +
                (!imageKey.contains(".full") ? ".fullborder" : "");

        final File dest;
        String replaces = null;
        if (mode == Mode.UPGRADE_TO_MAX) {
            // Already at max resolution under either name?
            if (cardFile(imageKey, ".png").isFile() || cardFile(fbKey, ".png").isFile()) { return; }
            // Upgrade whichever normal-resolution file is actually cached; if neither
            // is, the card is missing rather than outdated, so leave it alone.
            final String cached = cardFile(imageKey, ".jpg").isFile() ? imageKey
                    : cardFile(fbKey, ".jpg").isFile() ? fbKey : null;
            if (cached == null) { return; }
            dest = cardFile(cached, ".png");
            replaces = cardFile(cached, ".jpg").getAbsolutePath();
        } else {
            // Skip anything already cached, regardless of extension (a card fetched
            // earlier as .jpg is not "missing" and shouldn't be pulled again).
            if (existsAnyExt(imageKey) || existsAnyExt(fbKey)) { return; }
            dest = cardFile(imageKey, extension());
        }

        if (downloads.containsKey(dest.getAbsolutePath())) { return; }

        final String url = buildUrl(c, face);
        if (url == null) { return; }

        downloads.put(dest.getAbsolutePath(), url);
        if (replaces != null) {
            supersededJpgs.put(dest.getAbsolutePath(), replaces);
        }
    }

    private static File cardFile(String key, String ext) {
        return new File(ForgeConstants.CACHE_CARD_PICS_DIR, key + ext);
    }

    private static File tokenFile(String key, String ext) {
        return new File(ForgeConstants.CACHE_TOKEN_PICS_DIR, key + ext);
    }

    private static boolean existsAnyExt(String key) {
        return cardFile(key, ".jpg").isFile() || cardFile(key, ".png").isFile();
    }

    /**
     * Builds an ordered list of candidate download URLs for one card face, packed
     * into a single {@link GuiDownloadService#URL_SEPARATOR}-delimited string.
     * Scryfall is tried first (matching resolution); the cardforge hosted server is
     * added as a fallback so alternate arts whose Scryfall collector-number lookup
     * 404s still resolve, exactly as the on-demand fetcher does on hover.
     */
    private String buildUrl(PaperCard c, String face) {
        final List<String> candidates = new ArrayList<>(2);

        final String collectorNum = c.getCollectorNumber();
        final boolean hasCollectorNum = !IPaperCard.NO_COLLECTOR_NUMBER.equals(collectorNum)
                && !"0".equals(collectorNum)
                && !StringUtils.isBlank(collectorNum);

        if (hasCollectorNum) {
            CardEdition edition = StaticData.instance().getEditions().get(c.getEdition());
            if (edition != null) {
                String scryfallCode = edition.getScryfallCode();
                if (!StringUtils.isBlank(scryfallCode)) {
                    String langCode = edition.getCardsLangCode();
                    addScryfall(candidates, c, face, scryfallCode, langCode, false);
                    // Some sets (e.g. HOC) have no per-language Scryfall entry, so
                    // the langCode lookup 404s while the language-less one resolves.
                    // The on-demand fetcher tries both; match that here.
                    if (!StringUtils.isBlank(langCode)) {
                        addScryfall(candidates, c, face, scryfallCode, "", false);
                    }
                    // A meld result is numbered after one half of the pair, but which half
                    // differs by set: EMN/INR number Chittering Host after Midnight
                    // Scavengers, SIR numbers it after Graf Rats. Try this card's own
                    // number too rather than only its partner's.
                    if ("back".equals(face) && c.getRules().getSplitType() == CardSplitType.Meld) {
                        addScryfall(candidates, c, face, scryfallCode, langCode, true);
                        if (!StringUtils.isBlank(langCode)) {
                            addScryfall(candidates, c, face, scryfallCode, "", true);
                        }
                    }
                }
            }
        }

        // Fallback: cardforge hosted server. It only serves normal resolution, so it is
        // used solely by the standard-resolution mode. Letting a max-resolution run fall
        // back to it would write JPG bytes into a .png file - not the requested quality,
        // counted as a PNG by the audit, and never retried because the file now exists.
        if (mode == Mode.MISSING_STANDARD) {
            String cardforgeUrl = ImageUtil.getDownloadUrl(c, face);
            if (cardforgeUrl != null) {
                candidates.add(ForgeConstants.URL_PIC_DOWNLOAD + cardforgeUrl);
            }
        }

        return candidates.isEmpty() ? null : String.join(GuiDownloadService.URL_SEPARATOR, candidates);
    }

    private void addScryfall(List<String> candidates, PaperCard c, String face, String scryfallCode, String langCode,
            boolean meldUseOwnNumber) {
        String path = ImageUtil.getScryfallDownloadUrl(c, face, scryfallCode, langCode, false, imageVersion,
                meldUseOwnNumber);
        if (path != null) {
            String url = ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD + path;
            if (!candidates.contains(url)) {
                candidates.add(url);
            }
        }
    }
}
