package forge.adventure.data;

import com.badlogic.gdx.utils.Array;
import forge.ImageKeys;
import forge.StaticData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardType;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.IterableUtil;
import forge.util.StreamUtil;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for a "reward"
 * that can be a random card, gold or items.
 * Also used for deck generation and shops
 */
public class RewardData implements Serializable {
    @Serial
    private static final long serialVersionUID = 3158932532013393718L;
    public String type; // TODO convert to enum
    public float probability;
    public int count;
    public int addMaxCount;
    public String cardName;
    public String itemName;
    public String[] itemNames;
    public String[] editions;
    public String[] colors;
    public int startDate;
    public int endDate;
    public String[] rarity;
    public String[] subTypes;
    public String[] cardTypes;
    public String[] superTypes;
    public int[] manaCosts;
    public String[] keyWords;
    public String colorType;
    public String cardText;
    public boolean matchAllSubTypes;
    public boolean matchAllColors;
    public RewardData[] cardUnion;
    public String[] deckNeeds;
    public RewardData[] rotation;
    public Deck cardPack;
    public String sourceDeck;
    public String minDate;
    public boolean ignoreEditionRestrictions;

    public RewardData() { }

    public RewardData(RewardData rewardData) {
        if (rewardData == null)
            return;

        type             = rewardData.type;
        probability      = rewardData.probability;
        count            = rewardData.count;
        addMaxCount      = rewardData.addMaxCount;
        cardName         = rewardData.cardName;
        itemName         = rewardData.itemName;
        startDate        = rewardData.startDate;
        endDate          = rewardData.endDate;
        itemNames        = rewardData.itemNames == null ? null : rewardData.itemNames.clone();
        editions         = rewardData.editions == null ? null : rewardData.editions.clone();
        colors           = rewardData.colors == null ? null : rewardData.colors.clone();
        rarity           = rewardData.rarity == null ? null : rewardData.rarity.clone();
        subTypes         = rewardData.subTypes == null ? null : rewardData.subTypes.clone();
        cardTypes        = rewardData.cardTypes == null ? null : rewardData.cardTypes.clone();
        superTypes       = rewardData.superTypes == null ? null : rewardData.superTypes.clone();
        manaCosts        = rewardData.manaCosts == null ? null : rewardData.manaCosts.clone();
        keyWords         = rewardData.keyWords == null ? null : rewardData.keyWords.clone();
        colorType        = rewardData.colorType;
        cardText         = rewardData.cardText;
        matchAllSubTypes = rewardData.matchAllSubTypes;
        matchAllColors   = rewardData.matchAllColors;
        cardUnion        = rewardData.cardUnion == null ? null : rewardData.cardUnion.clone();
        rotation         = rewardData.rotation == null ? null : rewardData.rotation.clone();
        deckNeeds        = rewardData.deckNeeds == null ? null : rewardData.deckNeeds.clone();
        cardPack         = rewardData.cardPack;
        sourceDeck       = rewardData.sourceDeck;
        minDate          = rewardData.minDate;
        ignoreEditionRestrictions = rewardData.ignoreEditionRestrictions;
    }

    public static final int MIN_CARDS_FOR_NORMAL_POOL = 20;

    private static Iterable<PaperCard> allCards;
    private static Iterable<PaperCard> allEnemyCards;
    private static Iterable<PaperCard> allCardsNoEditionFilter;
    /**
     * Relaxed pool (no allowedEditions filter) minus the AI-removed cards.
     * Used as the "expanded" pool for enemy deck/reward generation.
     * NOTE: before this existed the relaxed branch returned {@link #allEnemyCards},
     * which is a *subset* of the normal pool — that is why shops kept showing
     * only the handful of cards left after allowedEditions filtering.
     */
    private static Iterable<PaperCard> allRelaxedEnemyCards;
    /** [DECK DEBUG] memo: filter signature -> matches in the relaxed pool (cleared with the pools). */
    private static final Map<String, Integer> relaxedMatchCache = new HashMap<>();

    public boolean shouldIgnoreEditionRestrictions() {
        if (ignoreEditionRestrictions)
            return true;
        if (cardTypes != null) {
            for (String t : cardTypes) {
                if (t != null && (t.equalsIgnoreCase("Land") || t.equalsIgnoreCase("Artifact")))
                    return true;
            }
        }
        return false;
    }

    /**
     * [DECK DEBUG] Human readable dump of every active filter flag, so the log
     * shows *what* was asked for, e.g.:
     * [DECK DEBUG] selectPool: type=Creature, colors=RED, rarity=Uncommon -> matches: 9
     */
    public String debugFilters() {
        return "type=" + type
                + ", colors=" + Arrays.toString(colors)
                + ", colorType=" + colorType
                + ", rarity=" + Arrays.toString(rarity)
                + ", cardTypes=" + Arrays.toString(cardTypes)
                + ", superTypes=" + Arrays.toString(superTypes)
                + ", subTypes=" + Arrays.toString(subTypes)
                + ", matchAllSubTypes=" + matchAllSubTypes
                + ", keyWords=" + Arrays.toString(keyWords)
                + ", manaCosts=" + Arrays.toString(manaCosts)
                + ", editions=" + Arrays.toString(editions)
                + ", minDate=" + minDate
                + ", cardText=" + cardText
                + ", matchAllColors=" + matchAllColors
                + ", deckNeeds=" + Arrays.toString(deckNeeds)
                + ", allCardVariants=" + Config.instance().getSettingData().useAllCardVariants
                + ", ignoreEditionRestrictions=" + ignoreEditionRestrictions
                + ", count=" + count;
    }

    /** Cheap size of an iterable (no predicate work). */
    private static int countCards(Iterable<PaperCard> pool) {
        if (pool == null)
            return 0;
        int n = 0;
        for (PaperCard ignored : pool)
            n++;
        return n;
    }

    /**
     * Matches of this filter inside the relaxed pool, memoized per filter
     * signature because the relaxed pool holds tens of thousands of cards and a
     * shop evaluates many rewards with identical filters.
     */
    private int relaxedMatches() {
        String key = debugFilters();
        Integer cached = relaxedMatchCache.get(key);
        if (cached != null)
            return cached;
        int n = countMatches(getAllCardsNoEditionFilter(true));
        relaxedMatchCache.put(key, n);
        return n;
    }

    private static Iterable<PaperCard> getAllCardsNoEditionFilter(boolean isForEnemy) {
        if (allCardsNoEditionFilter == null)
            initializeAllCards();
        if (isForEnemy && allRelaxedEnemyCards != null)
            return allRelaxedEnemyCards;
        return allCardsNoEditionFilter;
    }

    private int countMatches(Iterable<PaperCard> pool) {
        if (pool == null)
            return 0;
        CardUtil.CardPredicate predicate;
        try {
            predicate = new CardUtil.CardPredicate(this, true);
        } catch (Exception e) {
            return 0;
        }
        int n = 0;
        for (PaperCard c : pool) {
            try {
                if (c != null && predicate.test(c))
                    n++;
            } catch (Exception ex) {
                // ignore bad cards during counting
            }
        }
        return n;
    }

    private Iterable<PaperCard> selectPoolForEnemy(Iterable<PaperCard> normalPool) {
        if (shouldIgnoreEditionRestrictions()) {
            Iterable<PaperCard> relaxed = getAllCardsNoEditionFilter(true);
            System.out.println("[DECK DEBUG] selectPool: " + debugFilters()
                    + " -> RELAXED (forced by flag/type), normal=" + countCards(normalPool)
                    + ", relaxed=" + countCards(relaxed));
            return relaxed;
        }
        int matches = countMatches(normalPool);
        if (matches < MIN_CARDS_FOR_NORMAL_POOL) {
            int relaxed = relaxedMatches();
            if (relaxed > matches) {
                System.out.println("[DECK DEBUG] selectPool: " + debugFilters()
                        + " -> matches: " + matches + " in normal pool -> RELAXED (matches: " + relaxed + ")");
                return getAllCardsNoEditionFilter(true);
            }
            System.out.println("[DECK DEBUG] selectPool: " + debugFilters()
                    + " -> matches: " + matches + " in normal pool, relaxed only " + relaxed + " -> KEEP NORMAL");
            return normalPool;
        }
        System.out.println("[DECK DEBUG] selectPool: " + debugFilters()
                + " -> matches: " + matches + " in normal pool -> NORMAL");
        return normalPool;
    }

    static private void initializeAllCards() {
        System.out.println("[DECK DEBUG] === initializeAllCards() START ===");
        ConfigData configData = Config.instance().getConfigData();
        System.out.println("[DECK DEBUG] allowedEditions = " + Arrays.toString(configData.allowedEditions));
        System.out.println("[DECK DEBUG] restrictedEditions = " + Arrays.toString(configData.restrictedEditions));
        // Collection flags that also narrow the pools.
        System.out.println("[DECK DEBUG] restrictedCards = " + Arrays.toString(configData.restrictedCards)
                + ", legalCards=" + (configData.legalCards != null)
                + ", useAllCardVariants=" + Config.instance().getSettingData().useAllCardVariants
                + ", excludeAlchemyVariants=" + Config.instance().getSettingData().excludeAlchemyVariants
                + ", anteAllowed=" + FModel.getPreferences().getPrefBoolean(FPref.UI_ANTE)
                + ", commanderMode=" + AdventurePlayer.current().isCommanderMode());
        RewardData legals = configData.legalCards;

        List<Predicate<PaperCard>> filters = new ArrayList<>();

        if (legals != null)
            filters.add(new CardUtil.CardPredicate(legals, true));

        // Filter out by editions and obtainability (normal pool respects allowedEditions).
        if (configData.allowedEditions != null && configData.allowedEditions.length > 0)
            filters.add(PaperCardPredicates.printedInAnyEditions(configData.allowedEditions));
        else if (configData.restrictedEditions != null && configData.restrictedEditions.length > 0)
            filters.add(PaperCardPredicates.isObtainableNotRestricted(configData.restrictedEditions));
        else
            filters.add(PaperCardPredicates.isObtainableAnyEdition());

        if (Config.instance().getSettingData().excludeAlchemyVariants)
            filters.add(PaperCardPredicates.IS_REBALANCED.negate());

        if (!FModel.getPreferences().getPrefBoolean(FPref.UI_ANTE))
            filters.add(pc -> !pc.getRules().hasKeyword("Remove CARDNAME from your deck before playing if you're not playing for ante."));

        if (!AdventurePlayer.current().isCommanderMode())
            filters.add(pc -> !pc.getRules().getAiHints().getRemNonCommanderDecks());

        filters.add(pc -> !(pc.getRules().isCustom() && pc.getImageKey(false).startsWith(ImageKeys.ADVENTURECARD_PREFIX)));

        Set<String> restrictedCards = configData.restrictedCards == null
                ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(configData.restrictedCards));
        filters.add(pc -> !restrictedCards.contains(pc.getName()));

        // Filter out specific cards.
        allCards = CardUtil.getFullCardPool(false).stream()
                .filter(IterableUtil.and(filters))
                .collect(Collectors.toList());

        //Filter AI cards for enemies.
        allEnemyCards = IterableUtil.filter(allCards, input -> {
            if (input == null) return false;
            return !input.getRules().getAiHints().getRemAIDecks();
        });

        // Relaxed pool: directly from card DB, NO allowedEditions filter,
        // but still respecting restrictedEditions and restrictedCards.
        List<Predicate<PaperCard>> relaxedFilters = new ArrayList<>();
        if (legals != null)
            relaxedFilters.add(new CardUtil.CardPredicate(legals, true));
        if (configData.restrictedEditions != null && configData.restrictedEditions.length > 0)
            relaxedFilters.add(PaperCardPredicates.isObtainableNotRestricted(configData.restrictedEditions));
        else
            relaxedFilters.add(PaperCardPredicates.isObtainableAnyEdition());
        if (Config.instance().getSettingData().excludeAlchemyVariants)
            relaxedFilters.add(PaperCardPredicates.IS_REBALANCED.negate());
        if (!FModel.getPreferences().getPrefBoolean(FPref.UI_ANTE))
            relaxedFilters.add(pc -> !pc.getRules().hasKeyword("Remove CARDNAME from your deck before playing if you're not playing for ante."));
        if (!AdventurePlayer.current().isCommanderMode())
            relaxedFilters.add(pc -> !pc.getRules().getAiHints().getRemNonCommanderDecks());
        relaxedFilters.add(pc -> !(pc.getRules().isCustom() && pc.getImageKey(false).startsWith(ImageKeys.ADVENTURECARD_PREFIX)));
        relaxedFilters.add(pc -> !restrictedCards.contains(pc.getName()));

        allCardsNoEditionFilter = FModel.getMagicDb().getCommonCards().getUniqueCards().stream()
                .filter(IterableUtil.and(relaxedFilters))
                .collect(Collectors.toList());

        // Same AI-removal as allEnemyCards, but on top of the relaxed pool,
        // so enemy decks/shops get the *expanded* pool and not the normal one.
        allRelaxedEnemyCards = IterableUtil.filter(allCardsNoEditionFilter, input -> {
            if (input == null) return false;
            return !input.getRules().getAiHints().getRemAIDecks();
        });

        relaxedMatchCache.clear();

        int normalSize = countCards(allCards);
        int normalEnemySize = countCards(allEnemyCards);
        int relaxedSize = countCards(allCardsNoEditionFilter);
        int relaxedEnemySize = countCards(allRelaxedEnemyCards);
        System.out.println("[DECK DEBUG] pool#1 (normal/allowedEditions) size=" + normalSize);
        System.out.println("[DECK DEBUG] pool#1b (normal, AI filter) size=" + normalEnemySize);
        System.out.println("[DECK DEBUG] pool#2 (relaxed/no edition filter) size=" + relaxedSize);
        System.out.println("[DECK DEBUG] pool#2b (relaxed, AI filter) size=" + relaxedEnemySize);
        System.out.println("[DECK DEBUG] === initializeAllCards() END ===");
    }

    static public Iterable<PaperCard> getAllCards() {
        if (allCards == null)
            initializeAllCards();
        return allCards;
    }

    public static void invalidateCardPool() {
        allCards = null;
        allEnemyCards = null;
        allCardsNoEditionFilter = null;
        allRelaxedEnemyCards = null;
        relaxedMatchCache.clear();
        CardUtil.invalidateRelaxedPool();
    }

    public Array<Reward> generate(boolean isForEnemy, boolean useSeedlessRandom) {
        return generate(isForEnemy, null, useSeedlessRandom);
    }

    public Array<Reward> generate(boolean isForEnemy, boolean useSeedlessRandom, boolean isNoSell) {
        return generate(isForEnemy, null, useSeedlessRandom, isNoSell);
    }

    public Array<Reward> generate(boolean isForEnemy, Iterable<PaperCard> cards, boolean useSeedlessRandom){
        return generate(isForEnemy, cards, useSeedlessRandom, false);
    }

    public Array<Reward> generate(boolean isForEnemy, Iterable<PaperCard> cards, boolean useSeedlessRandom, boolean isNoSell) {
        boolean allCardVariants = Config.instance().getSettingData().useAllCardVariants;
        Random rewardRandom = useSeedlessRandom ? new Random() : WorldSave.getCurrentSave().getWorld().getRandom();
        //Keep using same generation method for shop rewards, but fully randomize loot drops by not using the instance pre-seeded by the map

        if (allCards==null)
            initializeAllCards();
        Array<Reward> ret=new Array<>();

        if (probability == 0 || rewardRandom.nextFloat() <= probability) {
            if(type == null || type.isEmpty())
                type="randomCard";
            int maxCount = Math.round(addMaxCount * Current.player().getDifficulty().rewardMaxFactor);
            int addedCount = (maxCount > 0 ? rewardRandom.nextInt(maxCount) : 0);

            switch(type) {
                case "Union":
                    HashSet<PaperCard> pool = new HashSet<>();
                    HashSet<String> unionNames = new HashSet<>();
                    for (RewardData r : cardUnion) {
                        if (r.cardName != null && !r.cardName.isEmpty() ) {
                            PaperCard pc;
                            if (allCardVariants) {
                                CardDb.CardRequest req = CardDb.CardRequest.fromString(r.cardName);
                                pc = (req.edition != null)
                                    ? CardUtil.getCardByNameAndEditionOrNull(req.cardName, req.edition)
                                    : CardUtil.getCardByNameOrNull(req.cardName);
                                if (pc == null) {
                                    // Restricted plane: no allowed printing of this card.
                                    // Deal a real printing instead of dropping the strict
                                    // lookup's "Wastes" placeholder into the shop stock.
                                    pc = StaticData.instance().getCommonCards().getCard(req.cardName);
                                }
                            } else {
                                pc = StaticData.instance().getCommonCards().getCard(r.cardName);
                            }
                            if (pc != null && unionNames.add(pc.getName()))
                                pool.add(pc);
                        } else if (r.sourceDeck != null && !r.sourceDeck.isEmpty() ) {
                            for (PaperCard pc : CardUtil.getDeck(r.sourceDeck, false, false, "", false, false).getAllCardsInASinglePool().toFlatList()) {
                                if (pc != null && unionNames.add(pc.getName()))
                                    pool.add(pc);
                            }
                        } else {
                            // Vehicle-fix: each union branch resolves via relaxed pool
                            // (3 matches in normal -> 925 in relaxed), so mixing
                            // subTypes=[Vehicle] + cardText=Vehicle + Pilot works.
                            // Land branches are sanitized so a union shop/reward never
                            // offers basic lands or common lands.
                            RewardData branch = isShopLandFilter(r) ? withShopLandRarity(r) : r;
                            for (PaperCard pc : CardUtil.getPredicateResult(
                                    branch.selectPoolForEnemy(isForEnemy ? allEnemyCards : allCards), branch)) {
                                if (pc != null && unionNames.add(pc.getName()))
                                    pool.add(pc);
                            }
                        }
                    }
                    ArrayList<PaperCard> finalPool = new ArrayList<>(pool);
                    if (finalPool.isEmpty()) {
                        System.out.println("[DECK DEBUG] union: " + cardUnion.length
                                + " branches -> 0 distinct cards (isForEnemy=" + isForEnemy + ")");
                    } else {
                        System.out.println("[DECK DEBUG] union: " + cardUnion.length + " branches -> "
                                + finalPool.size() + " distinct cards (isForEnemy=" + isForEnemy
                                + "), dealing " + Math.min(count + addedCount, finalPool.size()));
                    }

                    if (finalPool.size() > 0){
                        // Strict no-repeat deal: shuffle once, take distinct cards.
                        // Fixes Saga shop handing out copies of 2 identical cards.
                        Collections.shuffle(finalPool, rewardRandom);
                        // Same count contract as every other branch (gold/life/card/...):
                        // count + a random 0..addMaxCount-1 bonus. Matches the log above;
                        // current Union configs never set addMaxCount, so stock is unchanged.
                        int deal = Math.min(count + addedCount, finalPool.size());
                        for (int i = 0; i < deal; i++) {
                            PaperCard cardTemplate = finalPool.get(i);
                            if (cardTemplate == null)
                                continue;
                            if (allCardVariants) {
                                // Random variant printing when the config allows one, otherwise
                                // the exact card the pool dealt. Never the "Wastes" placeholder:
                                // the relaxed pool ignores allowedEditions by design, so the
                                // strict lookup can fail and would blank out the whole shop.
                                PaperCard finalCard = CardUtil.getCardByNameOrNull(cardTemplate.getCardName());
                                if (finalCard == null)
                                    finalCard = cardTemplate;
                                if (finalCard != null)
                                    ret.add(new Reward(finalCard, isNoSell));
                            } else {
                                ret.add(new Reward(cardTemplate, isNoSell));
                            }
                        }
                    } else {
                        // EMPTY POOL FIX (tribe shops on a restricted plane): when the
                        // plane's legalCards whitelist holds no member of a tribe, every
                        // union branch comes up empty and the shop renders unsellable.
                        // Top up from the relaxed pool, same as the randomCard branch.
                        RewardData anyCard = new RewardData();
                        anyCard.type = "randomCard";
                        List<PaperCard> topUp = fallbackShopCards(anyCard, count + addedCount, rewardRandom);
                        System.out.println("[DECK DEBUG] union: no branch matched -> fallback: +"
                                + topUp.size() + " cards (isForEnemy=" + isForEnemy + ")");
                        for (PaperCard pc : topUp) {
                            if (pc == null)
                                continue;
                            if (allCardVariants) {
                                PaperCard resolved = CardUtil.getCardByNameOrNull(pc.getCardName());
                                if (resolved == null)
                                    resolved = pc;
                                ret.add(new Reward(resolved, isNoSell));
                            } else {
                                ret.add(new Reward(pc, isNoSell));
                            }
                        }
                    }
                    break;
                case "card":
                case "randomCard":
                    if (cardName != null && !cardName.isEmpty()) {
                        if (allCardVariants) {
                            CardDb.CardRequest request = CardDb.CardRequest.fromString(cardName);
                            PaperCard card = (request.edition != null)
                                ? CardUtil.getCardByNameAndEditionOrNull(request.cardName, request.edition)
                                : CardUtil.getCardByNameOrNull(request.cardName);
                            if (card == null) {
                                // Restricted plane: no allowed printing of this card.
                                // Deal a real printing (any set) rather than the strict
                                // lookup's "Wastes" placeholder — same relaxed-pool
                                // philosophy as the shop stock above.
                                card = StaticData.instance().getCommonCards().getCard(request.cardName);
                            }
                            if (card == null) {
                                // Truly unknown name: keep the legacy loud placeholder
                                // so broken JSON stays visible in the log.
                                card = CardUtil.getCardByName(request.cardName);
                            }
                            if (card != null) {
                                for (int i = 0; i < count + addedCount; i++) {
                                    PaperCard finalCard = (request.edition != null)
                                        ? CardUtil.getCardByNameAndEditionOrNull(request.cardName, card.getEdition())
                                        : CardUtil.getCardByNameOrNull(request.cardName);
                                    if (finalCard == null)
                                        finalCard = card;
                                    ret.add(new Reward(finalCard, isNoSell));
                                }
                            }
                        } else {
                            for (int i = 0; i < count + addedCount; i++) {
                                PaperCard card = StaticData.instance().getCommonCards().getCard(cardName);
                                if (card != null)
                                    ret.add(new Reward(card, isNoSell));
                                else
                                    System.err.println("Missing card: " + cardName);
                            }
                        }
                    } else if (sourceDeck != null && !sourceDeck.isEmpty()) {
                        for( PaperCard card : CardUtil.generateCards(CardUtil.getDeck(sourceDeck, false, false, "", false, false).getAllCardsInASinglePool().toFlatList() ,this, count+addedCount, rewardRandom)) {
                            if (card != null)
                                ret.add(new Reward(card, isNoSell));
                        }
                    } else {
                        RewardData effective = isShopLandFilter(this) ? withShopLandRarity(this) : this;
                        Iterable<PaperCard> basePool = isForEnemy ? allEnemyCards : allCards;
                        Iterable<PaperCard> effectivePool = effective.selectPoolForEnemy(basePool);
                        System.out.println("[DECK DEBUG] randomCard: base=" + countCards(basePool)
                                + ", selected=" + countCards(effectivePool)
                                + (effective != this ? " (land-sanitized)" : "")
                                + ", requested=" + (count + addedCount));
                        List<PaperCard> generated = CardUtil.generateCards(effectivePool, effective, count + addedCount, rewardRandom);
                        if (generated.isEmpty()) {
                            // EMPTY POOL FIX (Vehicle/Saga shops): top up from any
                            // distinct cards of the relaxed pool so shop never renders empty.
                            generated = fallbackShopCards(effective, count + addedCount, rewardRandom);
                        }
                        for (PaperCard card : generated) {
                            if (card != null)
                                ret.add(new Reward(card, isNoSell));
                        }
                    }
                    break;
                case "item":
                    if(itemNames!=null) {
                        for (int i = 0; i < count + addedCount; i++) {
                            String itemName = itemNames[rewardRandom.nextInt(itemNames.length)];
                            ItemData itemData = ItemListData.getItem(itemName);
                            if (itemData != null)
                                ret.add(new Reward(itemData));
                            else
                                System.err.println("Missing item: " + itemName);
                        }
                    } else if (itemName != null && !itemName.isEmpty()) {
                        for (int i = 0; i < count + addedCount; i++) {
                            ItemData itemData = ItemListData.getItem(itemName);
                            if (itemData != null)
                                ret.add(new Reward(itemData));
                            else
                                System.err.println("Missing item: " + itemName);
                        }
                    }
                    break;
                case "cardPackShop": {
                    if (colors == null) {
                        CardEdition.Collection editions = FModel.getMagicDb().getEditions();
                        Predicate<CardEdition> filter = CardEdition.Predicates.CAN_MAKE_BOOSTER;
                        List<CardEdition> allEditions = new ArrayList<>();
                        StreamUtil.stream(editions)
                            .filter(filter)
                            .filter(CardEdition::hasBoosterTemplate)
                            .forEach(allEditions::add);
                        ConfigData configData = Config.instance().getConfigData();

                        if (this.editions != null && this.editions.length > 0) {
                            Set<String> allowed = new HashSet<>(Arrays.asList(this.editions));
                            allEditions.removeIf(q -> !allowed.contains(q.getCode()));
                        } else {
                            for (String restricted : configData.restrictedEditions) {
                                allEditions.removeIf(q -> q.getCode().equals(restricted));
                            }
                            for (String restrictedCard : configData.restrictedCards) {
                                allEditions.removeIf(cardEdition -> cardEdition.getObtainableCards().stream().anyMatch(
                                    o -> o.name().equals(restrictedCard)));
                            }
                            endDate = endDate == 0 ? 9999 : endDate;
                            allEditions.removeIf(q -> q.getDate().getYear()+1900 < startDate || q.getDate().getYear()+1900 > endDate);
                        }
                        for (int i = 0; i < count + addedCount; i++) {
                            ret.add(new Reward(AdventureEventController.instance().generateBooster(
                                allEditions.get(rewardRandom.nextInt(allEditions.size())).getCode())));
                        }
                    } else {
                        for (int i = 0; i < count + addedCount; i++) {
                            ret.add(new Reward(AdventureEventController.instance().generateBoosterByColor(colors[0])));
                        }
                    }
                    break;
                }
                case "landSketchbookShop":
                    Array<ItemData> sketchbookItems = ItemListData.getSketchBooks();
                    for (int i = 0; i < count + addedCount; i++) {
                        ItemData item = sketchbookItems.get(rewardRandom.nextInt(sketchbookItems.size));
                        if (item != null)
                            ret.add(new Reward(item));
                    }
                    break;
                case "cardPack":
                    if (cardPack!=null) {
                        if (isNoSell) {
                            cardPack.getTags().add("noSell");
                        }
                        ret.add(new Reward(cardPack, isNoSell));
                    }
                    break;
                case "deckCard":
                    if (cards == null)
                        return ret;
                    for (PaperCard card : CardUtil.generateCards(cards,this, count + addedCount + Current.player().bonusDeckCards(), rewardRandom)) {
                        if (card != null)
                            ret.add(new Reward(card, isNoSell));
                    }
                    break;
                case "gold":
                    ret.add(new Reward(count + addedCount));
                    break;
                case "life":
                    ret.add(new Reward(Reward.Type.Life, count + addedCount));
                    break;
                case "mana": //backwards compatibility for reward data
                case "shards":
                    ret.add(new Reward(Reward.Type.Shards, count + addedCount));
                    break;
            }
        }
        return ret;
    }

    /**
     * Guaranteed victory bonus for a won match/duel: exactly one rare/mythic
     * artifact and one rare/mythic land, drawn from the relaxed pool
     * (allowedEditions ignored, restrictedEditions/restrictedCards respected).
     * Called once from EnemySprite.getRewards(), never for shops/packs/quests.
     */
    public static Array<Reward> generateVictoryBonusRewards(boolean isNoSell) {
        Array<Reward> bonus = new Array<>();
        try {
            Random rnd;
            try {
                rnd = WorldSave.getCurrentSave().getWorld().getRandom();
            } catch (Exception e) {
                rnd = new Random();
            }
            if (allCardsNoEditionFilter == null)
                initializeAllCards();
            RewardData artifactFilter = new RewardData();
            artifactFilter.type = "randomCard";
            artifactFilter.cardTypes = new String[] { "Artifact" };
            artifactFilter.rarity = new String[] { "Rare", "MythicRare" };
            artifactFilter.ignoreEditionRestrictions = true;
            List<PaperCard> artifactCards = CardUtil.generateCards(allCardsNoEditionFilter, artifactFilter, 1, rnd);
            if (artifactCards.isEmpty()) {
                // FALLBACK: relaxed pool probe failed (e.g. pool not ready) —
                // retry directly via CardUtil relaxed pool.
                artifactCards = CardUtil.generateCards(CardUtil.getRelaxedPool(), artifactFilter, 1, rnd);
            }
            for (PaperCard card : artifactCards) {
                if (card != null)
                    bonus.add(new Reward(card, isNoSell));
            }
            RewardData landFilter = new RewardData();
            landFilter.type = "randomCard";
            landFilter.cardTypes = new String[] { "Land" };
            landFilter.rarity = new String[] { "Rare", "MythicRare" };
            landFilter.ignoreEditionRestrictions = true;
            List<PaperCard> landCards = CardUtil.generateCards(allCardsNoEditionFilter, landFilter, 1, rnd);
            if (landCards.isEmpty()) {
                landCards = CardUtil.generateCards(CardUtil.getRelaxedPool(), landFilter, 1, rnd);
            }
            for (PaperCard card : landCards) {
                if (card != null)
                    bonus.add(new Reward(card, isNoSell));
            }
            System.out.println("[DECK DEBUG] victory bonus: +" + bonus.size + " (artifact+land Rare/Mythic)");
        } catch (Exception e) {
            System.err.println("[DECK DEBUG] victory bonus failed: " + e.getMessage());
        }
        return bonus;
    }

    /** Shop lands: only Uncommon/Rare/Mythic, never Common/BasicLand. */
    private static final String[] SHOP_LAND_RARITY = { "Uncommon", "Rare", "MythicRare" };

    private static boolean isLandOnly(RewardData data) {
        if (data == null || data.cardTypes == null || data.cardTypes.length == 0)
            return false;
        for (String t : data.cardTypes) {
            if (t != null && t.equalsIgnoreCase("Land"))
                return true;
        }
        return false;
    }

    private static boolean isShopLandFilter(RewardData data) {
        // Shop/reward land offers (RewardScene/MapStage call generate(false,false),
        // victory loot calls generate(...,true)) are sanitized so no basic land or
        // common land is ever dealt. The guaranteed Rare/Mythic victory land is
        // produced separately by generateVictoryBonusRewards.
        if (data == null)
            return false;
        // "card", "randomCard" and an unset type (the switch defaults an empty
        // type to randomCard) are the pool-drawing branches; those are the only
        // ones that can ever hand out a land, so only those get sanitized.
        if (data.type != null && !data.type.isEmpty()
                && !data.type.equalsIgnoreCase("randomCard")
                && !data.type.equalsIgnoreCase("card"))
            return false;
        return isLandOnly(data);
    }

    /**
     * Never sell Basic or Common lands in shops. When the shop offers no
     * explicit rarity, restrict to Uncommon/Rare/Mythic. When it does specify
     * a rarity, strip BasicLand/Common out of it (falling back to the default
     * Uncommon+ set if nothing remains) so basic/common lands can never appear.
     */
    private static RewardData withShopLandRarity(RewardData data) {
        RewardData copy = new RewardData(data);
        if (data.rarity == null || data.rarity.length == 0) {
            copy.rarity = SHOP_LAND_RARITY.clone();
            return copy;
        }
        List<String> kept = new ArrayList<>();
        for (String r : data.rarity) {
            if (r == null)
                continue;
            CardRarity parsed = CardRarity.smartValueOf(r);
            if (parsed == CardRarity.BasicLand || parsed == CardRarity.Common)
                continue; // never sell basic/common lands
            kept.add(r);
        }
        copy.rarity = kept.isEmpty() ? SHOP_LAND_RARITY.clone() : kept.toArray(new String[0]);
        return copy;
    }

    private static List<PaperCard> fallbackShopCards(RewardData effective, int need, Random rnd) {
        List<PaperCard> out = new ArrayList<>();
        try {
            Iterable<PaperCard> relaxed = CardUtil.getRelaxedPool();
            if (relaxed == null)
                return out;
            List<PaperCard> matches = CardUtil.getPredicateResult(relaxed, effective);
            // If even relaxed yields nothing (over-constrained colors etc.),
            // top up with any distinct relaxed cards so the shop is not empty.
            if (matches.isEmpty()) {
                RewardData anyColor = new RewardData(effective);
                anyColor.colors = null;
                anyColor.matchAllColors = false;
                matches = CardUtil.getPredicateResult(relaxed, anyColor);
            }
            Collections.shuffle(matches, rnd);
            HashSet<String> seen = new HashSet<>();
            for (PaperCard pc : matches) {
                if (out.size() >= need)
                    break;
                if (pc == null || !seen.add(pc.getName()))
                    continue;
                // Shops never deal Basic or Common lands, not even via the fallback:
                // the neutral top-up filter has no rarity restriction of its own.
                if (pc.getRules() != null && pc.getRules().getType().hasType(CardType.CoreType.Land)) {
                    CardRarity r = pc.getRarity();
                    if (r == CardRarity.BasicLand || r == CardRarity.Common)
                        continue;
                }
                out.add(pc);
            }
            if (!matches.isEmpty())
                System.out.println("[DECK DEBUG] shop fallback: +" + out.size() + " distinct cards");
        } catch (Exception e) {
            System.err.println("[DECK DEBUG] shop fallback failed: " + e.getMessage());
        }
        return out;
    }

    static public List<PaperCard> generateAllCards(Iterable<RewardData> dataList, boolean isForEnemy) {
        return rewardsToCards(generateAll(dataList, isForEnemy));
    }
    static public Iterable<Reward> generateAll(Iterable<RewardData> dataList, boolean isForEnemy) {
        Array<Reward> ret = new Array<Reward>();
        for (RewardData data : dataList)
            ret.addAll(data.generate(isForEnemy, false));
        return ret;
    }
    static public List<PaperCard> rewardsToCards(Iterable<Reward> dataList) {
        ArrayList<PaperCard> ret = new ArrayList<PaperCard>();

        boolean allCardVariants = Config.instance().getSettingData().useAllCardVariants;

        if (allCardVariants) {
            String basicLandEdition = "";
            for (Reward data : dataList) {
                PaperCard card = data.getCard();
                if (card.isVeryBasicLand()) {
                    // ensure that all basic lands share the same edition so the deck doesn't look odd
                    if (basicLandEdition.isEmpty()) {
                        basicLandEdition = card.getEdition();
                    }
                    ret.add(CardUtil.getCardByNameAndEdition(card.getName(), basicLandEdition));
                } else {
                    ret.add(card);
                }
            }
        } else {
            for (Reward data : dataList) {
                ret.add(data.getCard());
            }
        }
        return ret;
    }
}
