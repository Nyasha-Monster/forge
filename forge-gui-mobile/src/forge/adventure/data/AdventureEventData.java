package forge.adventure.data;

import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.RewardScene;
import forge.adventure.util.AdventureEventController;
import forge.adventure.util.AdventureOverrides;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.Reward;
import forge.card.CardEdition;
import forge.card.PrintSheet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedDeckBuilder;
import forge.item.SealedTemplate;
import forge.item.generation.IUnOpenedProduct;
import forge.item.generation.UnOpenedProduct;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.util.Aggregates;
import forge.util.IterableUtil;
import forge.util.Localizer;
import forge.util.MyRandom;
import forge.util.StreamUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class AdventureEventData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final int JUMPSTART_TO_PICK_FROM = 6;
    public transient BoosterDraft draft;
    public AdventureEventParticipant[] participants;
    public int rounds;
    public int currentRound;
    public AdventureEventRules eventRules = new AdventureEventRules();
    public AdventureEventReward[] rewards;
    public int eventOrigin;
    public String sourceID;
    public long eventSeed;
    public AdventureEventController.EventStyle style;
    public AdventureEventController.EventStatus eventStatus;
    public AdventureEventController.EventFormat format;
    private transient Random random = new Random();
    public Deck registeredDeck;
    public Deck rewardDeck; //Copy of registered before basic lands are added for event reward purposes
    public List<Deck> jumpstartBoosters = new ArrayList<>();
    public boolean isDraftComplete = false;
    public String description = "";
    public String[] packConfiguration = new String[0];
    public transient CardBlock cardBlock;
    public String cardBlockName;
    public boolean playerWon;
    public int matchesWon, matchesLost;
    private Deck[] rewardPacks;

    public AdventureEventData(AdventureEventData other) {
        participants = other.participants.clone();
        rounds = other.rounds;
        eventRules = other.eventRules;
        rewards = other.rewards.clone();
        eventOrigin = other.eventOrigin;
        sourceID = other.sourceID;
        eventSeed = other.eventSeed;
        style = other.style;
        random.setSeed(eventSeed);
        eventStatus = other.eventStatus;
        format = other.format;
        registeredDeck = other.registeredDeck;
        isDraftComplete = other.isDraftComplete;
        description = other.description;
        cardBlockName = other.cardBlockName;
        packConfiguration = other.packConfiguration;
        playerWon = other.playerWon;
        matchesWon = other.matchesWon;
        matchesLost = other.matchesLost;
    }

    public Deck[] getRewardPacks(int count) {
        Deck[] ret = new Deck[count];
        for (int i = 0; i < count; i++) {
            ret[i] = AdventureEventController.instance().generateBooster(Aggregates.random(getCardBlock().getSets()).getCode());
        }
        return ret;
    }

    private Random getEventRandom() {
        if (random == null)
            random = (eventSeed > 0 ? new Random(eventSeed) : new Random());
        return random;
    }

    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat) {
        this(seed, selectedFormat, null, pickCardBlockByFormat(selectedFormat));
    }

    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat, CardBlock cardBlock) {
        this(seed, selectedFormat, null, cardBlock);
    }

    public AdventureEventData(Long seed, AdventureEventController.EventFormat selectedFormat, AdventureEventController.EventStyle style, CardBlock cardBlock) {
        setEventSeed(seed);
        eventStatus = AdventureEventController.EventStatus.Available;
        registeredDeck = new Deck();
        format = selectedFormat;
        this.cardBlock = cardBlock;
        if (cardBlock == null)
            return;
        cardBlockName = cardBlock.getName();
        if (format == AdventureEventController.EventFormat.Draft) {
            setupDraftRewards();
        } else if (format == AdventureEventController.EventFormat.Sealed) {
            setupSealedRewards();
        } else if (format == AdventureEventController.EventFormat.Jumpstart) {
            jumpstartBoosters = AdventureEventController.instance().getJumpstartBoosters(cardBlock, JUMPSTART_TO_PICK_FROM);
            packConfiguration = new String[]{cardBlock.getLandSet().getCode(), cardBlock.getLandSet().getCode(), cardBlock.getLandSet().getCode()};

            setupJumpstartRewards();
        }

        if(style == null) {
            // If the chosen event seed recommends a four-person pod, run it as a RoundRobin
            if (getRecommendedPodSize(cardBlock) == 4)
                style = AdventureEventController.EventStyle.RoundRobin;
            else
                style = AdventureEventController.EventStyle.Bracket;
        }
        this.style = style;
    }

    public void setEventSeed(long seed) {
        getEventRandom().setSeed(seed);
    }

    /**
     * Rerolls the booster pool every time a save is loaded for events that have been
     * entered but whose matches have not started yet. This gives the player a fresh set
     * of boosters after reloading — if they dislike the pool they can simply save &amp;
     * reload to get new cards. In-progress (Started+) and not-yet-entered (Available)
     * events are left untouched.
     */
    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        try {
            // Rebuild the transient card block from its name (transient field isn't serialized).
            if (cardBlock == null && cardBlockName != null) {
                getCardBlock();
            }
            // Only reroll pools for events whose matches have not started yet.
            if (cardBlock == null
                    || format == AdventureEventController.EventFormat.Constructed
                    || (eventStatus != AdventureEventController.EventStatus.Entered
                        && eventStatus != AdventureEventController.EventStatus.Ready)) {
                return;
            }
            // Draft: only reroll an in-progress draft (Entered). A finished draft (Ready)
            // keeps its locked-in deck — rerolling there would wipe the player's picks.
            if (format == AdventureEventController.EventFormat.Draft
                    && eventStatus != AdventureEventController.EventStatus.Entered) {
                return;
            }

            // New seed -> new boosters. Set both the persisted field and the transient RNG
            // so the fresh seed survives the next save too.
            eventSeed = MyRandom.getRandom().nextLong();
            random = new Random(eventSeed);

            switch (format) {
                case Sealed:
                    // A fresh empty deck makes generateSealedPool() rebuild on next "Create Deck".
                    registeredDeck = new Deck();
                    jumpstartBoosters.clear();
                    eventStatus = AdventureEventController.EventStatus.Entered;
                    break;
                case Jumpstart:
                    // Re-roll the offered packs so a new selection shows on next entry.
                    registeredDeck = new Deck();
                    jumpstartBoosters = AdventureEventController.instance()
                            .getJumpstartBoosters(getCardBlock(), JUMPSTART_TO_PICK_FROM);
                    eventStatus = AdventureEventController.EventStatus.Entered;
                    break;
                default:
                    return;
            }
            System.out.println("[Event] Rerolled pool on load for \"" + description + "\" (" + format + ")");
        } catch (Exception e) {
            // Never let a pool reroll break save loading.
            System.err.println("[Event] Pool reroll on load failed for \"" + description + "\": " + e.getMessage());
        }
    }

    public CardBlock getCardBlock() {
        if (cardBlock == null) {
            cardBlock = AdventureOverrides.instance().getBlock(cardBlockName);
            if (cardBlock == null) {
                //Single-set event blocks are generated rather than stored in blocks.txt;
                //rebuild one from the set code embedded in its name so saved events keep working.
                cardBlock = resolveSingletonBlock(cardBlockName);
            }
        }
        return cardBlock;
    }

    public BoosterDraft getDraft() {
        if (format != AdventureEventController.EventFormat.Draft)
            return null;

        Random placeholder = MyRandom.getRandom();
        MyRandom.setRandom(getEventRandom());
        if (draft == null && (eventStatus == AdventureEventController.EventStatus.Available || eventStatus == AdventureEventController.EventStatus.Entered)) {
            draft = BoosterDraft.createDraft(LimitedPoolType.Block, getCardBlock(), packConfiguration, participants.length);
            registeredDeck = draft.getHumanPlayer().getDeck();
            assignPlayerNames(draft);
        }
        if (packConfiguration == null) {
            packConfiguration = getBoosterConfiguration(getCardBlock());
        }
        MyRandom.setRandom(placeholder);
        return draft;
    }

    private static CardBlock pickCardBlockByFormat(AdventureEventController.EventFormat format) {
        return switch (format) {
            case Draft, Sealed -> pickWeightedCardBlock();
            case Jumpstart -> pickJumpstartCardBlock();
            case Constructed -> null;
        };
    }

    private static final Predicate<CardEdition> filterPioneer = FModel.getFormats().getPioneer().editionLegalPredicate;
    private static final Predicate<CardEdition> filterModern = FModel.getFormats().getModern().editionLegalPredicate;
    private static final Predicate<CardEdition> filterVintage = FModel.getFormats().getVintage().editionLegalPredicate;
    private static final Predicate<CardEdition> filterStandard = FModel.getFormats().getStandard().editionLegalPredicate;

    public static Predicate<CardEdition> selectSetPool() {
        if (Config.instance().getConfigData().vintageOnlyEditions) {
            return filterVintage;
        }

        final int rollD100 = MyRandom.getRandom().nextInt(100);
        Predicate<CardEdition> rolledFilter;
        if (rollD100 < 30) {
            rolledFilter = filterStandard;
        } else if (rollD100 < 60) {
            // Remove standard from older pools because its representation is already inflated.
            rolledFilter = filterPioneer.and(filterStandard.negate());
        } else if (rollD100 < 80) {
            rolledFilter = filterModern.and(filterStandard.negate());
        } else {
            rolledFilter = filterVintage.and(filterStandard.negate());
        }
        return rolledFilter;
    }

    private static final Set<String> POWER_NINE = Set.of("Black Lotus", "Mox Emerald", "Mox Pearl", "Mox Ruby", "Mox Sapphire", "Mox Jet", "Ancestral Recall", "Timetwister", "Time Walk");
    private static final int SINGLETON_DRAFT_BOOSTERS = 3;
    private static final int SINGLETON_SEALED_BOOSTERS = 6;

    /** Converts a config string array to a set; a HashSet tolerates duplicate entries that users edit in by hand. */
    private static Set<String> toStringSet(String[] values) {
        if (values == null || values.length == 0)
            return Collections.emptySet();
        return new HashSet<>(Arrays.asList(values));
    }

    private static CardBlock pickWeightedCardBlock() {
        CardEdition.Collection editions = FModel.getMagicDb().getEditions();
        ConfigData configData = Config.instance().getConfigData();
        Predicate<CardEdition> filter = CardEdition.Predicates.CAN_MAKE_BOOSTER;

        //Both world whitelists feed the event list: a set qualifies for draft/sealed
        //events when it is listed in allowedEvents OR allowedEditions.
        Set<String> eventAllowed = toStringSet(configData.allowedEvents);
        Set<String> editionAllowed = toStringSet(configData.allowedEditions);
        Set<String> eventRestricted = toStringSet(configData.restrictedEvents);
        Set<String> allowed = new LinkedHashSet<>(eventAllowed);
        allowed.addAll(editionAllowed);

        if (!allowed.isEmpty()) {
            //The whitelists beat the blacklists and the random set-pool roll below,
            //except that sets added only through allowedEditions still respect restrictedEvents.
            filter = filter.and(q -> allowed.contains(q.getCode())
                    && (eventAllowed.contains(q.getCode()) || !eventRestricted.contains(q.getCode())));

            if (eventAllowed.isEmpty()) {
                //No explicit event list: keep the random set-pool narrowing that
                //allowedEditions-only planes had before, exactly as it was.
                Predicate<CardEdition> setPoolFilter = selectSetPool();
                if (editions.stream().anyMatch(setPoolFilter))
                    filter = filter.and(setPoolFilter);
            }
        }
        else
        {
            if(configData.restrictedEvents != null) {
                //Temporary restriction until rewards are more diverse - don't want to award restricted cards so these editions need different rewards added.
                //Also includes sets that use conspiracy or commander drafts.
                filter = filter.and((q) -> !eventRestricted.contains(q.getCode()));
            }
            if (configData.restrictedEditions != null) {
                Set<String> restricted = toStringSet(configData.restrictedEditions);
                filter = filter.and(q -> !restricted.contains(q.getCode()));
            }

            Predicate<CardEdition> setPoolFilter = selectSetPool();
            if(editions.stream().anyMatch(setPoolFilter))
                filter = filter.and(setPoolFilter);
        }

        List<CardEdition> allEditions = new ArrayList<>();
        StreamUtil.stream(editions)
                .filter(filter)
                .filter(CardEdition::hasBoosterTemplate)
                .forEach(allEditions::add);

        List<CardBlock> legalBlocks = getValidDraftBlocks(allEditions);

        if (!allowed.isEmpty()) {
            //Whitelisted sets that no legal block fully covers still get a single-set event.
            appendSingletonBlocks(legalBlocks, allEditions, configData);

            Set<String> offered = new HashSet<>();
            for (CardEdition set : allEditions) {
                offered.add(set.getCode());
            }
            Set<String> notOffered = new LinkedHashSet<>(allowed);
            notOffered.removeAll(offered);
            List<String> blockNames = new ArrayList<>();
            for (CardBlock block : legalBlocks) {
                blockNames.add(block.getName());
            }
            System.out.println("[EVENT DEBUG] event whitelist=" + allowed
                    + " notOffered(no booster template or filtered out)=" + notOffered
                    + " event blocks=" + blockNames);
        }

        return legalBlocks.isEmpty() ? null : Aggregates.random(legalBlocks);
    }

    public static List<CardBlock> getValidDraftBlocks(List<CardEdition> validEditions) {
        List<CardBlock> legalBlocks = new ArrayList<>();
        for (CardBlock b : AdventureOverrides.instance().allBlocks()) { // for each block
            if (b.getSets().isEmpty() || (b.getCntBoostersDraft() < 1))
                continue;
            if (!isValidDraftBlock(b, validEditions))
                continue;
            legalBlocks.add(b);
        }
        return legalBlocks;
    }

    private static boolean isValidDraftBlock(CardBlock b, List<CardEdition> validEditions) {
        for (CardEdition c : b.getSets()) {
            if (!validEditions.contains(c))
                return false;
            if (!isValidDraftSet(c))
                return false;
        }
        return true;
    }

    /** Per-set draft/sealed viability: usable booster template, a real pool of cards, no P9 print sheets. */
    public static boolean isValidDraftSet(CardEdition c) {
        if (!c.hasBoosterTemplate())
            return false;
        if(c.getBoosterTemplate().getNumberOfCardsExpected() <= 7)
            return false;
        for (PrintSheet ps : c.getPrintSheetsBySection()) {
            //exclude sets containing P9 cards.
            if(!Collections.disjoint(ps.toNameLookup().keySet(), POWER_NINE))
                return false;
        }
        return true;
    }

    /**
     * Name of a generated single-set event block. The set code is embedded in the
     * name so the block can be rebuilt from a save file, where only the name survives
     * (the cardBlock field itself is transient).
     */
    public static String singletonBlockName(CardEdition set) {
        return set.getName() + " [" + set.getCode() + "]";
    }

    /** Builds a single-set draft/sealed block (3 draft / 6 sealed boosters, matching the blocks.txt defaults). */
    public static CardBlock singletonBlockFor(CardEdition set) {
        return new CardBlock(0, singletonBlockName(set), Collections.singletonList(set),
                Collections.emptyList(), set, SINGLETON_DRAFT_BOOSTERS, SINGLETON_SEALED_BOOSTERS);
    }

    /** Rebuilds a generated single-set block from its name, or returns null if the name isn't one. */
    private static CardBlock resolveSingletonBlock(String blockName) {
        if (blockName == null || !blockName.endsWith("]"))
            return null;
        int open = blockName.lastIndexOf('[');
        if (open <= 0)
            return null;
        CardEdition set = FModel.getMagicDb().getEditions().get(blockName.substring(open + 1, blockName.length() - 1));
        return set == null ? null : singletonBlockFor(set);
    }

    /**
     * Expands a whitelist-filtered draft block list with single-set events for eligible
     * sets that no legal block fully covers: a whitelisted set whose block siblings were
     * not whitelisted (e.g. ULG when USG/UDS are absent from the lists), or a set that
     * isn't part of any block at all.
     */
    private static void appendSingletonBlocks(List<CardBlock> legalBlocks, List<CardEdition> allEditions, ConfigData configData) {
        Set<String> covered = new HashSet<>();
        for (CardBlock block : legalBlocks) {
            for (CardEdition set : block.getSets()) {
                covered.add(set.getCode());
            }
        }

        //Sets of a plane-restricted upstream block don't get a private single-set event either;
        //restrictedBlocks has always beaten the event whitelists because allBlocks() hides them.
        Set<String> restrictedBlockNames = toStringSet(configData.restrictedBlocks);
        Set<String> inRestrictedBlock = new HashSet<>();
        if (!restrictedBlockNames.isEmpty()) {
            for (CardBlock block : FModel.getBlocks()) {
                if (restrictedBlockNames.contains(block.getName())) {
                    for (CardEdition set : block.getSets()) {
                        inRestrictedBlock.add(set.getCode());
                    }
                }
            }
        }

        for (CardEdition set : allEditions) {
            if (covered.contains(set.getCode()) || inRestrictedBlock.contains(set.getCode()))
                continue;
            if (!isValidDraftSet(set))
                continue;
            legalBlocks.add(singletonBlockFor(set));
        }
    }

    private static CardBlock pickJumpstartCardBlock() {
        Iterable<CardBlock> src = AdventureOverrides.instance().allBlocks(); //all blocks
        List<CardBlock> legalBlocks = new ArrayList<>();
        ConfigData configData = Config.instance().getConfigData();
        if (configData.allowedJumpstart != null) {
            Set<String> allowed = Set.of(configData.allowedJumpstart);
            for (CardBlock b : src) { // for each block
                if (allowed.contains(b.getName())) {
                    legalBlocks.add(b);
                }
            }
            for (CardBlock b : FModel.getFantasyBlocks()) {
                if (allowed.contains(b.getName())) {
                    legalBlocks.add(b);
                }
            }
        }
        else {
            for (CardBlock b : src) { // for each block
                //I hate doing this, but it seems like the simplest way to reliably filter out prereleases
                if (b.getName().toUpperCase().contains("JUMPSTART")) {
                    legalBlocks.add(b);
                }
            }
            if (configData.allowedEditions != null) {
                Set<String> allowed = Set.of(configData.allowedEditions);
                legalBlocks.removeIf(q -> !allowed.contains(q.getName()));
            } else {
                for (String restricted : configData.restrictedEditions) {
                    legalBlocks.removeIf(q -> q.getName().equals(restricted));
                }
            }
        }
        return legalBlocks.isEmpty() ? null : Aggregates.random(legalBlocks);
    }

    public void generateSealedPool() {
        if (cardBlock == null) return;

        try {
            // Open all boosters
            CardPool humanPool = new CardPool();

            for (String setCode : packConfiguration) {
                boolean isMetaSet = false;
                for (String metaSetName : cardBlock.getMetaSetNames()) {
                    if (metaSetName.equals(setCode)) {
                        isMetaSet = true;
                        break;
                    }
                }

                IUnOpenedProduct booster;
                if (isMetaSet) {
                    booster = cardBlock.getBooster(setCode);
                } else {
                    SealedTemplate template = AdventureOverrides.instance().getBoosterTemplate(setCode);
                    if (template == null) continue;
                    booster = new UnOpenedProduct(template);
                }

                humanPool.addAllFlat(booster.get());
            }

            if (humanPool.isEmpty()) return;

            // Store the pool in sideboard
            registeredDeck = new Deck();
            registeredDeck.getOrCreate(DeckSection.Sideboard).addAll(humanPool);
            registeredDeck.setName("Sealed Pool - " + cardBlockName);

            // Store the reward deck consisting of every card in the opened sealed boosters
            rewardDeck = new Deck();
            rewardDeck.getOrCreate(DeckSection.Main).addAll(humanPool);
            rewardDeck.setName("Sealed Pool Cards");

            // Generate AI opponents' decks
            for (AdventureEventParticipant participant : participants) {
                if (participant instanceof AdventureEventHuman) continue;

                CardPool aiPool = new CardPool();
                for (String setCode : packConfiguration) {
                    boolean isMetaSet = false;
                    for (String metaSetName : cardBlock.getMetaSetNames()) {
                        if (metaSetName.equals(setCode)) {
                            isMetaSet = true;
                            break;
                        }
                    }

                    IUnOpenedProduct booster;
                    if (isMetaSet) {
                        booster = cardBlock.getBooster(setCode);
                    } else {
                        SealedTemplate template = AdventureOverrides.instance().getBoosterTemplate(setCode);
                        if (template == null) continue;
                        booster = new UnOpenedProduct(template);
                    }

                    aiPool.addAllFlat(booster.get());
                }

                if (aiPool.isEmpty()) continue;

                SealedDeckBuilder deckBuilder = new SealedDeckBuilder(aiPool.toFlatList());
                String landSet = cardBlock.getLandSet() != null ? cardBlock.getLandSet().getCode() : null;
                Deck aiDeck = deckBuilder.buildDeck(landSet);
                participant.setDeck(aiDeck);
            }

            eventStatus = AdventureEventController.EventStatus.Entered;

        } catch (Exception e) {
            System.err.println("Error generating Sealed pool: " + e.getMessage());
        }
    }

    private void setupDraftRewards() {
        //Below all to be fully generated in later release
        rewardPacks = getRewardPacks(3);
        if (cardBlock != null) {
            packConfiguration = getBoosterConfiguration(cardBlock);

            rewards = new AdventureEventData.AdventureEventReward[4];
            AdventureEventData.AdventureEventReward r0 = new AdventureEventData.AdventureEventReward();
            AdventureEventData.AdventureEventReward r1 = new AdventureEventData.AdventureEventReward();
            AdventureEventData.AdventureEventReward r2 = new AdventureEventData.AdventureEventReward();
            AdventureEventData.AdventureEventReward r3 = new AdventureEventData.AdventureEventReward();
            r0.minWins = 0;
            r0.maxWins = 0;
            r0.cardRewards = new Deck[]{rewardPacks[0]};
            rewards[0] = r0;
            r1.minWins = 1;
            r1.maxWins = 3;
            r1.cardRewards = new Deck[]{rewardPacks[1], rewardPacks[2]};
            rewards[1] = r1;
            r2.minWins = 2;
            r2.maxWins = 3;
            r2.itemRewards = new String[]{"Challenge Coin"};
            rewards[2] = r2;
        }
    }

    private void setupSealedRewards() {
        packConfiguration = getBoosterConfiguration(cardBlock);

        rewardPacks = getRewardPacks(3);
        rewards = new AdventureEventReward[4];

        AdventureEventReward r0 = new AdventureEventReward();
        AdventureEventReward r1 = new AdventureEventReward();
        AdventureEventReward r2 = new AdventureEventReward();
        AdventureEventReward r3 = new AdventureEventReward();
        r0.minWins = 0;
        r0.maxWins = 0;
        r0.cardRewards = new Deck[]{rewardPacks[0]};
        rewards[0] = r0;
        r1.minWins = 1;
        r1.maxWins = 3;
        r1.cardRewards = new Deck[]{rewardPacks[1], rewardPacks[2]};
        rewards[1] = r1;
        r2.minWins = 2;
        r2.maxWins = 3;
        r2.itemRewards = new String[]{"Silver Challenge Coin"};
        rewards[2] = r2;
        r3.minWins = 3;
        r3.maxWins = 3;
        rewards[3] = r3;
    }

    private void setupJumpstartRewards() {
        rewards = new AdventureEventData.AdventureEventReward[4];
        AdventureEventData.AdventureEventReward r0 = new AdventureEventData.AdventureEventReward();
        AdventureEventData.AdventureEventReward r1 = new AdventureEventData.AdventureEventReward();
        AdventureEventData.AdventureEventReward r2 = new AdventureEventData.AdventureEventReward();
        AdventureEventData.AdventureEventReward r3 = new AdventureEventData.AdventureEventReward();

        RewardData r0gold = new RewardData();
        r0gold.count = 100;
        r0gold.type = "gold";
        r0.rewards = new RewardData[]{r0gold};
        r0.minWins = 1;
        r0.maxWins = 1;
        rewards[0] = r0;
        RewardData r1gold = new RewardData();
        r1gold.count = 200;
        r1gold.type = "gold";
        r1.rewards = new RewardData[]{r1gold};
        r1.minWins = 2;
        r1.maxWins = 2;
        rewards[1] = r1;
        r2.minWins = 3;
        r2.maxWins = 3;
        RewardData r2gold = new RewardData();
        r2gold.count = 500;
        r2gold.type = "gold";
        r2.rewards = new RewardData[]{r2gold};
        rewards[2] = r2;
        r3.minWins = 0;
        r3.maxWins = 3;
        rewards[3] = r3;
        //r3 will be the selected card packs
    }


    public String[] getBoosterConfiguration(CardBlock selectedBlock) {
        Random placeholder = MyRandom.getRandom();
        MyRandom.setRandom(getEventRandom());

        int numBoosters;
        String[] ret;

        if (format == AdventureEventController.EventFormat.Sealed) {
            numBoosters = selectedBlock.getCntBoostersSealed();
            ret = new String[numBoosters];

            for (int i = 0; i < numBoosters; i++) {
                ret[i] = selectedBlock.getSets().get(i % selectedBlock.getNumberSets()).getCode();
            }
        } else {
            numBoosters = selectedBlock.getCntBoostersDraft();
            ret = new String[numBoosters];

            for (int i = 0; i < numBoosters; i++) {
                if (i < selectedBlock.getNumberSets())
                    ret[i] = selectedBlock.getSets().get(i).getCode();
                else
                    ret[i] = Aggregates.random(selectedBlock.getSets()).getCode();
            }
        }

        MyRandom.setRandom(placeholder);
        return ret;
    }

    public void startEvent() {
        if (eventStatus == AdventureEventController.EventStatus.Ready) {
            currentRound = 1;
            eventStatus = AdventureEventController.EventStatus.Started;
        }
    }

    public void generateParticipants() {
        this.generateParticipants(getRecommendedPodSize(this.cardBlock) - 1); //-1 to account for the player
    }

    public void generateParticipants(int numberOfOpponents) {
        participants = new AdventureEventParticipant[numberOfOpponents + 1];

        Iterable<EnemyData> validParticipants = IterableUtil.filter(WorldData.getAllEnemies(), q -> q.nextEnemy == null);
        List<EnemyData> data = Aggregates.random(validParticipants, numberOfOpponents);
        for (int i = 0; i < numberOfOpponents; i++) {
            participants[i] = new AdventureEventParticipant().generate(data.get(i));
        }

        participants[numberOfOpponents] = getHumanPlayer();

        if (format == AdventureEventController.EventFormat.Jumpstart) {
            for (AdventureEventParticipant participant : participants) {
                List<Deck> availableOptions = AdventureEventController.instance().getJumpstartBoosters(cardBlock, JUMPSTART_TO_PICK_FROM);
                List<Deck> chosenPacks = new ArrayList<>();

                Map<String, List<Deck>> themeMap = new HashMap<>();

                //1. Search for matching themes from deck names, fill deck with them if possible
                for (Deck option : availableOptions) {
                    // This matches up theme for all except DMU - with only 2 per color the next part will handle that
                    String theme = option.getName().replaceAll("\\d$", "").trim();
                    if (!themeMap.containsKey(theme)) {
                        themeMap.put(theme, new ArrayList<>());
                    }
                    themeMap.get(theme).add(option);
                }

                String themeAdded = "";
                boolean done = false;
                while (!done) {
                    for (int i = packConfiguration.length - chosenPacks.size(); i > 1; i--) {
                        if (themeAdded.isEmpty()) {
                            for (String theme : themeMap.keySet()) {
                                if (themeMap.get(theme).size() >= i) {
                                    themeAdded = theme;
                                    break;
                                }
                            }
                        }
                    }
                    if (themeAdded.isEmpty()) {
                        done = true;
                    } else {
                        chosenPacks.addAll(themeMap.get(themeAdded).subList(0, Math.min(themeMap.get(themeAdded).size(), packConfiguration.length - chosenPacks.size())));
                        availableOptions.removeAll(themeMap.get(themeAdded));
                        themeMap.remove(themeAdded);
                        themeAdded = "";
                    }
                }

                //2. Fill remaining slots with colors already picked whenever possible
                Map<String, List<Deck>> colorMap = new HashMap<>();
                for (Deck option : availableOptions) {
                    if (option.getTags().contains("black"))
                        colorMap.computeIfAbsent("black", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("blue"))
                        colorMap.computeIfAbsent("blue", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("green"))
                        colorMap.computeIfAbsent("green", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("red"))
                        colorMap.computeIfAbsent("red", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("white"))
                        colorMap.computeIfAbsent("white", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("multicolor"))
                        colorMap.computeIfAbsent("multicolor", (k) -> new ArrayList<>()).add(option);
                    if (option.getTags().contains("colorless"))
                        colorMap.computeIfAbsent("colorless", (k) -> new ArrayList<>()).add(option);
                }

                done = false;
                String colorAdded = "";
                while (!done) {
                    List<String> colorsAlreadyPicked = new ArrayList<>();
                    for (Deck picked : chosenPacks) {
                        if (picked.getTags().contains("black")) colorsAlreadyPicked.add("black");
                        if (picked.getTags().contains("blue")) colorsAlreadyPicked.add("blue");
                        if (picked.getTags().contains("green")) colorsAlreadyPicked.add("green");
                        if (picked.getTags().contains("red")) colorsAlreadyPicked.add("red");
                        if (picked.getTags().contains("white")) colorsAlreadyPicked.add("white");
                        if (picked.getTags().contains("multicolor")) colorsAlreadyPicked.add("multicolor");
                        if (picked.getTags().contains("colorless")) colorsAlreadyPicked.add("colorless");
                    }

                    while (colorAdded.isEmpty() && !colorsAlreadyPicked.isEmpty()) {
                        String colorToTry = Aggregates.removeRandom(colorsAlreadyPicked);
                        for (Deck toCheck : availableOptions) {
                            if (toCheck.getTags().contains(colorToTry)) {
                                colorAdded = colorToTry;
                                chosenPacks.add(toCheck);
                                availableOptions.remove(toCheck);
                                break;
                            }
                        }
                    }
                    //3. If no matching color found and need more packs, add any available at random.
                    if (packConfiguration.length > chosenPacks.size() && colorAdded.isEmpty() && !availableOptions.isEmpty()) {
                        chosenPacks.add(Aggregates.removeRandom(availableOptions));
                    } else {
                        done = colorAdded.isEmpty() || packConfiguration.length <= chosenPacks.size();
                    }
                    colorAdded = "";
                }
                participant.registeredDeck = new Deck();
                for (Deck chosen : chosenPacks) {
                    participant.registeredDeck.getMain().addAllFlat(chosen.getMain().toFlatList());
                }
            }
        }

        switch (this.style) {
            case Swiss:
            case Bracket:
                this.rounds = (participants.length / 2) - 1;
                break;
            case RoundRobin:
                this.rounds = participants.length - 1;
                break;
        }
    }

    public static int getRecommendedPodSize(CardBlock cardBlock) {
        // Set can be null when it is only a meta set such as some Jumpstart events.
        CardEdition firstSet = cardBlock.getSets().isEmpty() ? null : cardBlock.getSets().get(0);
        return firstSet == null ? 8 : firstSet.getDraftOptions().getRecommendedPodSize();
    }

    private void assignPlayerNames(BoosterDraft draft) {
        if(participants == null)
            return;
        draft.getHumanPlayer().setName(getHumanPlayer().getName());
        LimitedPlayer[] opponents = draft.getOpposingPlayers();
        for(int i = 0; i < Math.min(participants.length, opponents.length); i++)
            opponents[i].setName(participants[i].getName());
    }

    private transient AdventureEventHuman humanPlayerInstance;

    public AdventureEventHuman getHumanPlayer() {
        if (humanPlayerInstance == null) {
            humanPlayerInstance = new AdventureEventHuman();
        }
        return humanPlayerInstance;
    }

    public AdventureEventParticipant nextOpponent = null;

    public List<AdventureEventMatch> getMatches(int round) {
        if (matches.containsKey(round)) {
            return matches.get(round);
        }

        List<AdventureEventParticipant> activePlayers = new ArrayList<>();
        if (style == AdventureEventController.EventStyle.Bracket) {
            if (round == 1) {
                activePlayers = Arrays.stream(participants).collect(Collectors.toList());
            } else {
                if (matches.get(round - 1) == null) {
                    return null;
                }
                for (int i = 0; i < matches.get(round - 1).size(); i++) {
                    AdventureEventParticipant w = matches.get(round - 1).get(i).winner;
                    if (w == null)
                        return null;
                    else
                        activePlayers.add(w);
                }
            }
            matches.put(round, new ArrayList<>());
            while (!activePlayers.isEmpty()) {
                AdventureEventMatch match = new AdventureEventMatch();
                match.p1 = activePlayers.remove(MyRandom.getRandom().nextInt(activePlayers.size()));
                if (!activePlayers.isEmpty()) {
                    match.p2 = activePlayers.remove(MyRandom.getRandom().nextInt(activePlayers.size()));
                }
                matches.get(round).add(match);
            }
        } else if (style == AdventureEventController.EventStyle.RoundRobin) {
            // In a roundrobin everyone plays everyone else once
            // We do have this logic already in ForgeTOurnament, we should see if we could reuse it
            matches.put(round, new ArrayList<>());
            activePlayers = Arrays.stream(participants).collect(Collectors.toList());

            if (round > 1) {
                AdventureEventParticipant pivot = activePlayers.remove(0);
                for(int i = 1; i < round; i++) {
                    // Rotate X amount of players, where X is the current round-1
                    AdventureEventParticipant rotate = activePlayers.remove(0);
                    activePlayers.add(rotate);
                }
                activePlayers.add(0, pivot);
            }

            int numPlayers = activePlayers.size();
            for (int i = 0; i < numPlayers / 2; i++) {
                AdventureEventMatch match = new AdventureEventMatch();
                match.p1 = activePlayers.get(i);
                match.p2 = activePlayers.get(numPlayers - i - 1);
                matches.get(round).add(match);
            }
        } else {
            System.out.println(style + " not yet implemented!!!");
        }
        return matches.get(currentRound);
    }

    public Map<Integer, List<AdventureEventMatch>> matches = new HashMap<>();

    public void giveRewards() {
        int wins = matchesWon;
        Array<Reward> ret = new Array<>();

        //Todo: this should be automatic... "somehow"

        if (format == AdventureEventController.EventFormat.Draft) {

            rewards[3] = new AdventureEventReward();
            rewards[3].minWins = 3;
            rewards[3].maxWins = 3;
            rewardDeck.setName("Drafted Deck");
            rewardDeck.setComment(Forge.getLocalizer().getMessage("advPrizeDraftFirst"));
            rewards[3].cardRewards = new Deck[]{rewardDeck};

        } else if (format == AdventureEventController.EventFormat.Sealed) {

            rewards[3] = new AdventureEventReward();
            rewards[3].minWins = 3;
            rewards[3].maxWins = 3;
            rewardDeck.setName("Sealed Card Pool");
            rewardDeck.setComment(Forge.getLocalizer().getMessage("advPrizeSealedFirst"));
            rewards[3].cardRewards = new Deck[]{rewardDeck};

        } else if (format == AdventureEventController.EventFormat.Jumpstart) {

            rewards[3] = new AdventureEventReward();
            rewards[3].minWins = 0;
            rewards[3].maxWins = 3;
            registeredDeck.setName("Jumpstart Event Packs");
            rewards[3].cardRewards = new Deck[]{registeredDeck};
            rewards[3].isNoSell = true;

        }

        //end todo

        for (AdventureEventReward r : rewards) {
            if (r.minWins > wins || r.maxWins < wins) {
                continue;
            }
            for (Deck pack : r.cardRewards) {
                RewardData data = new RewardData();
                data.type = "cardPack";
                data.count = 1;
                data.cardPack = pack;
                ret.addAll(data.generate(false, true, r.isNoSell));
            }
            for (String item : (r.itemRewards)) {
                RewardData data = new RewardData();
                data.type = "item";
                data.count = 1;
                data.itemName = item;
                ret.addAll(data.generate(false, true));
            }
            for (RewardData data : r.rewards) {
                ret.addAll(data.generate(false, true));
            }
        }
        if (ret.size > 0) {
            RewardScene.instance().loadRewards(ret, RewardScene.Type.EventReward, null);
            Forge.switchScene(RewardScene.instance());
        }

        //todo: more robust logic for event types that can be won without perfect record (Swiss w/cut, round robin)
        if (style == AdventureEventController.EventStyle.Bracket) {
            playerWon = matchesLost == 0 || matchesWon == rounds;
        } else if (style == AdventureEventController.EventStyle.RoundRobin) {
            if (matchesWon == rounds) {
                playerWon = true;
            } else {
                //If multiple players are tied for first, only the one with the best tiebreaker wins
                List<AdventureEventParticipant> topPlayers = new ArrayList<>();
                int bestRecord = 0;
                for (AdventureEventParticipant p : participants) {
                    if (p.wins > bestRecord) {
                        bestRecord = p.wins;
                        topPlayers.clear();
                        topPlayers.add(p);
                    } else if (p.wins == bestRecord) {
                        topPlayers.add(p);
                    }
                }
                if (topPlayers.size() == 1) {
                    playerWon = topPlayers.get(0).getName().equals(getHumanPlayer().getName());
                } else {
                    //multiple players tied for first, use tiebreaker
                    Map<AdventureEventParticipant, Integer> tiebreakers = new HashMap<>();
                    for (AdventureEventParticipant p : topPlayers) {
                        int tb = 0;
                        for (AdventureEventMatch m : matches.values().stream().flatMap(List::stream).collect(Collectors.toList())) {
                            if (m.p1 == p && m.winner != null && m.winner != p) {
                                tb += m.p2.wins;
                            } else if (m.p2 == p && m.winner != null && m.winner != p) {
                                tb += m.p1.wins;
                            }
                        }
                        tiebreakers.put(p, tb);
                    }
                    int bestTiebreaker = 0;
                    AdventureEventParticipant winner = null;
                    boolean tie = false;
                    for (AdventureEventParticipant p : tiebreakers.keySet()) {
                        if (tiebreakers.get(p) > bestTiebreaker) {
                            bestTiebreaker = tiebreakers.get(p);
                            winner = p;
                            tie = false;
                        } else if (tiebreakers.get(p) == bestTiebreaker) {
                            tie = true;
                        }
                    }
                    playerWon = !tie && winner != null && winner.getName().equals(getHumanPlayer().getName());
                }
            }
        } else {
            playerWon = false;
        }

        eventStatus = AdventureEventController.EventStatus.Awarded;
    }

    public String getDescription(PointOfInterestChanges changes) {
        Localizer localizer = Forge.getLocalizer();
        float townPriceModifier = changes == null ? 1f : changes.getTownPriceModifier();
        if (format == AdventureEventController.EventFormat.Draft) {
            description = localizer.getMessage("advEventTypeBoosterDraft");
            description += "Block: " + getCardBlock() + "\n";
            description += "Boosters: " + String.join(", ", packConfiguration) + "\n";
            description += "Competition Style: " + participants.length + " players, matches played as best of " + eventRules.gamesPerMatch + ", " + (eventRules.getPairingDescription()) + "\n\n";

            if (eventStatus == AdventureEventController.EventStatus.Available) {
                description += String.format("Pay 1 Entry Fee\n- Gold %d[][+Gold][BLACK]\n- Mana Shards %d[][+Shards][BLACK]\n", Math.round(eventRules.goldToEnter * townPriceModifier), Math.round(eventRules.shardsToEnter * townPriceModifier));
                if (eventRules.acceptsBronzeChallengeCoin) {
                    description += "- Bronze Challenge Coin [][+BronzeChallengeCoin][BLACK]\n\n";
                } else if (eventRules.acceptsSilverChallengeCoin) {
                    description += "- Silver Challenge Coin [][+SilverChallengeCoin][BLACK]\n\n";
                } else if (eventRules.acceptsChallengeCoin) {
                    description += "- Gold Challenge Coin [][+ChallengeCoin][BLACK]\n\n";
                } else {
                    description += "\n";
                }
            }
            description += String.format("Prizes\nChampion: Keep drafted deck\n2+ round wins: Challenge Coin \n1+ round wins: %s Booster, %s Booster\n0 round wins: %s Booster", rewardPacks[0].getComment(), rewardPacks[1].getComment(), rewardPacks[2].getComment());
        } else if (format == AdventureEventController.EventFormat.Jumpstart) {
            description = "Event Type: Jumpstart\n";
            description += "Block: " + getCardBlock() + "\n";
            description += "Competition Style: " + participants.length + " players, matches played as best of " + eventRules.gamesPerMatch + ", " + (eventRules.getPairingDescription()) + "\n\n";
            description += String.format("Pay 1 Entry Fee\n- Gold %d[][+Gold][BLACK]\n- Mana Shards %d[][+Shards][BLACK]\n", Math.round(eventRules.goldToEnter * townPriceModifier), Math.round(eventRules.shardsToEnter * townPriceModifier));
            if (eventRules.acceptsBronzeChallengeCoin) {
                description += "- Bronze Challenge Coin [][+BronzeChallengeCoin][BLACK]\n\n";
            } else if (eventRules.acceptsSilverChallengeCoin) {
                description += "- Silver Challenge Coin [][+SilverChallengeCoin][BLACK]\n\n";
            } else if (eventRules.acceptsChallengeCoin) {
                description += "- Gold Challenge Coin [][+ChallengeCoin][BLACK]\n\n";
            } else {
                description += "\n";
            }
            description += localizer.getMessage("advDraftPrizesDesc");
            description += localizer.getMessage("advJumpstartParticipationPrize");
        } else if (format == AdventureEventController.EventFormat.Sealed) {
            description = "Event Type: Sealed Deck\n";
            description += "Block: " + getCardBlock() + "\n";
            description += "Boosters: " + packConfiguration.length + " Packs (" +
                    String.join(", ", packConfiguration) + ")\n";
            description += "Competition Style: " + participants.length + " players, matches played as best of " +
                    eventRules.gamesPerMatch + ", " + (eventRules.getPairingDescription()) + "\n\n";

            if (eventStatus == AdventureEventController.EventStatus.Available) {
                description += String.format("Pay 1 Entry Fee\n- Gold %d[][+Gold][BLACK]\n- Mana Shards %d[][+Shards][BLACK]\n",
                        Math.round(eventRules.goldToEnter * townPriceModifier),
                        Math.round(eventRules.shardsToEnter * townPriceModifier));

                if (eventRules.acceptsSilverChallengeCoin) {
                    description += "- Silver Challenge Coin [][+SilverChallengeCoin][BLACK]\n\n";
                } else {
                    description += "\n";
                }
            }

            description += "Prizes\n";
            description += localizer.getMessage("advSealedChampionPrize", packConfiguration.length);
            description += "2+ wins: Silver Challenge Coin\n";
            description += String.format("1+ wins: %s Booster, %s Booster\n", rewardPacks[1].getComment(), rewardPacks[2].getComment());
            description += String.format("0 wins: %s Booster", rewardPacks[0].getComment());
        }
        return description;
    }


    public static class AdventureEventParticipant implements Serializable, Comparable<AdventureEventParticipant> {
        @Serial
        private static final long serialVersionUID = 1L;
        private transient EnemySprite sprite;
        String enemyDataName;
        Deck registeredDeck;

        public int wins;
        public int losses;

        public AdventureEventParticipant generate(EnemyData data) {
            AdventureEventParticipant ret = new AdventureEventParticipant();
            ret.enemyDataName = data.getName();
            ret.sprite = new EnemySprite(data);
            return ret;
        }

        public String getRecord() {
            return String.format("%d-%d", wins, losses);
        }

        private AdventureEventParticipant() {
        }

        public Deck getDeck() {
            return registeredDeck;
        }

        public String getName() {
            EnemyData enemyData = WorldData.getEnemy(enemyDataName);
            if (enemyData != null)
                return enemyData.getName();
            return "";
        }

        public Image getAvatar() {
            if (sprite == null) {
                EnemyData data = WorldData.getEnemy(enemyDataName);
                if (data == null) {
                    //enemyDataName was not found, replace with something valid.
                    enemyDataName = Aggregates.random(WorldData.getAllEnemies()).getName();
                }
                sprite = new EnemySprite(WorldData.getEnemy(enemyDataName));
            }
            return sprite.getAvatar() == null ? new Image() : new Image(sprite.getAvatar());
        }

        public String getAtlasPath() {
            return sprite == null ? "" : sprite.getAtlasPath();
        }

        public EnemySprite getSprite() {
            if (sprite == null) {
                sprite = new EnemySprite(WorldData.getEnemy(enemyDataName));
            }
            return sprite;
        }

        @Override
        public int compareTo(AdventureEventParticipant other) {
            if (this.wins != other.wins)
                return other.wins - this.wins;
            else
                return this.losses - other.losses;
        }

        public void setDeck(Deck deck) {
            registeredDeck = deck;
        }
    }

    public static class AdventureEventHuman extends AdventureEventParticipant {
        @Override
        public Deck getDeck() {
            return registeredDeck == null ? Current.player().getSelectedDeck() : registeredDeck;
        }

        @Override
        public String getName() {
            return Current.player().getName();
        }

        @Override
        public Image getAvatar() {
            return new Image(Current.player().avatar());
        }
    }

    public static class AdventureEventRules implements Serializable {
        @Serial
        private static final long serialVersionUID = -2902188278147984885L;
        public int goldToEnter;
        public int shardsToEnter;
        public boolean acceptsChallengeCoin = false;
        public boolean acceptsSilverChallengeCoin = false;
        public boolean acceptsBronzeChallengeCoin = false;
        public GameType gameType = GameType.AdventureEvent;
        public int startingLife = 20;
        public boolean allowsShards = false;
        public boolean allowsItems = false;
        public boolean allowsBlessings = false;
        public boolean allowsAddBasicLands = true;
        public int gamesPerMatch = 3;
        public PairingStyle pairingStyle = PairingStyle.SingleElimination;

        public AdventureEventRules() {
            this(AdventureEventController.EventFormat.Constructed, PairingStyle.SingleElimination, 1.0f);
        }

        public AdventureEventRules(AdventureEventController.EventFormat format, float localPriceModifier) {
            this(format, PairingStyle.SingleElimination, localPriceModifier);
        }

        public AdventureEventRules(AdventureEventController.EventFormat format, PairingStyle pairingStyle, float localPriceModifier) {
            int baseGoldEntry = 99999;
            int baseShardEntry = 9999;
            this.pairingStyle = pairingStyle;

            switch (format) {
                case Constructed:
                    acceptsSilverChallengeCoin = true;
                    acceptsChallengeCoin = false;
                    acceptsBronzeChallengeCoin = false;
                    baseGoldEntry = 1500;
                    baseShardEntry = 25;
                    allowsAddBasicLands = false;
                    break;
                case Draft:
                    acceptsChallengeCoin = true;
                    acceptsSilverChallengeCoin = false;
                    acceptsBronzeChallengeCoin = false;
                    baseGoldEntry = 3000;
                    baseShardEntry = 50;
                    startingLife = 20;
                    allowsAddBasicLands = true;
                    break;
                case Sealed:
                    acceptsChallengeCoin = false;
                    acceptsSilverChallengeCoin = true;
                    acceptsBronzeChallengeCoin = false;
                    baseGoldEntry = 6000;
                    baseShardEntry = 100;
                    startingLife = 20;
                    allowsAddBasicLands = true;
                    break;
                case Jumpstart:
                    acceptsChallengeCoin = false;
                    acceptsSilverChallengeCoin = false;
                    acceptsBronzeChallengeCoin = true;
                    baseGoldEntry = 200;
                    baseShardEntry = 5;
                    startingLife = 15;
                    allowsAddBasicLands = false;
                    break;
            }
            goldToEnter = baseGoldEntry;
            shardsToEnter = baseShardEntry;
        }

        public String getPairingDescription() {
            return switch (pairingStyle) {
                case Swiss -> "swiss";
                case SwissWithCut -> "swiss (with cut)";
                case RoundRobin -> "round robin";
                case SingleElimination -> "single elimination";
                case DoubleElimination -> "double elimination";
            };
        }
    }

    public static class AdventureEventMatch implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public AdventureEventParticipant p1;
        public AdventureEventParticipant p2;
        public AdventureEventParticipant winner;
        public int round;
    }

    public static class AdventureEventReward implements Serializable {
        @Serial
        private final static long serialVersionUID = -2605375040895115477L;
        public int minWins = -1;
        public int maxWins = -1;
        public Deck[] cardRewards = new Deck[0];
        public String[] itemRewards = new String[0];
        public RewardData[] rewards = new RewardData[0];
        public boolean isNoSell = false;
    }

    public enum PairingStyle {
        SingleElimination,
        DoubleElimination,
        Swiss,
        SwissWithCut,
        RoundRobin
    }
}
