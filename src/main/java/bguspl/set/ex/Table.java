package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    //new
    public volatile Boolean[][] playerTokens; //keep track of player tokens on the table

    //new - for reader-writer
    private int activePlayers;
    private int activeDealer;
    private int waitingDealer;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.playerTokens = new Boolean[env.config.tableSize][env.config.players];
        for (int i = 0; i < env.config.tableSize; i++) {
            Arrays.fill(playerTokens[i], false);
        }
        this.activePlayers = 0;
        this.activeDealer = 0;
        this.waitingDealer = 0;
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {
        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
        this.playerTokens = new Boolean[env.config.tableSize][env.config.players];
        for (int i = 0; i < env.config.tableSize; i++) {
            Arrays.fill(playerTokens[i], false);
        }
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                cards++;
        return cards;
    }

    public boolean keyPressed(int slot, int playerID) {
        if(slotToCard[slot] != null){
            if(playerTokens[slot][playerID])
                removeToken(playerID, slot);
            else{
                int numOfTokens = 0;
                for(int i=0; i<env.config.tableSize; i++){
                    if(playerTokens[i][playerID])
                        numOfTokens++;
                }
                if(numOfTokens < 3){
                    placeToken(playerID, slot);
                    if(numOfTokens == 2)
                        return true;
                }
            }
        }
        return false;
    }


    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
        cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null;
        removeTokens(slot);
        env.ui.removeCard(slot);
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        playerTokens[slot][player] = true;
        env.ui.placeToken(player, slot);
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public void removeToken(int player, int slot) {
        playerTokens[slot][player] = false;
        env.ui.removeToken(player, slot);
    }

    public void removeTokens(int slot) {
        for(int i=0; i<playerTokens[slot].length; i++){
            playerTokens[slot][i] = false;
        }
        env.ui.removeTokens(slot);
    }

    public Integer[] getSlotToCard(){
        return slotToCard;
    }

    public Integer cardAtSlot(int slot){
        return slotToCard[slot];
    }

    public synchronized void playerLock() {
        while(!allowPlayer() & !Thread.currentThread().isInterrupted()){
            try{    
                wait();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        activePlayers++;
    }
    public synchronized void playerUnlock() {
        activePlayers--;
        notifyAll();
    }
    public synchronized void dealerLock() {
        waitingDealer++;
        while(!allowDealer()) {
            try{    
                wait();
            } catch (InterruptedException ignored) {}
        }
        waitingDealer--;
        activeDealer++;
    }
    public synchronized void dealerUnlock() {
        activeDealer--;
        notifyAll();
    }
    protected boolean allowPlayer() {
        return activeDealer == 0 && waitingDealer == 0;
    }
    protected boolean allowDealer() {
        return activeDealer == 0 && activePlayers == 0;
    }

    public int numOfTokens(int playerID){
        int tokens = 0;
        for(int i = 0 ; i< env.config.tableSize; i++){
            if(playerTokens[i][playerID]){
                tokens++;
            }
        }
        return tokens;
    }
}