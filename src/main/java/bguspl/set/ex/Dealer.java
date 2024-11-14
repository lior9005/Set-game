package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Vector;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /** new
     * The thread representing the current player.
     */
    private Thread dealerThread;
    private volatile boolean tableReady = false;

    private Vector<Integer> waitingSets;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.waitingSets = new Vector<Integer>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {                                                                              
        dealerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        table.dealerLock();
        for(int i=0; i<players.length; i++){       
            Thread playerT = new Thread(players[i], "player " + (i));
            playerT.start();
        }
        updateTimerDisplay(true);
        while (!shouldFinish()) {
            placeCardsOnTable();
            tableReady = true;
            timerLoop();
            updateTimerDisplay(true);
            tableReady = false;
            removeAllCardsFromTable();
        }

        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {                                                                     
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            table.dealerUnlock();
            sleepUntilWokenOrTimeout();
            //lock access to the table
            table.dealerLock();
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }
//
    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        if (!terminate){
            terminate = true;
            for(int i = players.length-1; i>=0; i--){
                players[i].terminate();
            }
            dealerThread.interrupt();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them - including sets that were found.
     */
    private void removeCardsFromTable() {                                                     
        if(!waitingSets.isEmpty()){
            checkSetVector();
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private synchronized void placeCardsOnTable() {           
        int numOfCards = table.countCards();
        int slot = 0;
        Integer[] board = table.getSlotToCard();
        boolean cardsAdded = !deck.isEmpty() && numOfCards < board.length;
        while(!terminate && !deck.isEmpty() && numOfCards < board.length){
            if(board[slot] == null){
                Random random = new Random();
                int randomIndex = random.nextInt(deck.size());
                int randomCard = deck.remove(randomIndex);
                table.placeCard(randomCard, slot);
                numOfCards++;
            }
            slot++;
        }
        updateTimerDisplay(cardsAdded);
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try{
            if(reshuffleTime-System.currentTimeMillis()<env.config.turnTimeoutWarningMillis){
                Thread.sleep(10);
                updateTimerDisplay(false);
            }
            else{
                Thread.sleep(995);
                updateTimerDisplay(false);
            }
        }   catch (InterruptedException ignored) {}
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, env.config.turnTimeoutMillis<=env.config.turnTimeoutWarningMillis);                
        }
        else
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), reshuffleTime-System.currentTimeMillis()<=env.config.turnTimeoutWarningMillis);                
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        int slot = 0;
        while(!terminate && slot < env.config.tableSize){
            if(table.cardAtSlot(slot) != null){
                deck.add(table.cardAtSlot(slot));
                table.removeCard(slot);
            }
            slot++;
        }  
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = 0;
        int amount = 0;
        //closing the players threads
        terminate();
        for(Player player : players){
            if(player.score()> maxScore){
                maxScore = player.score();
                amount = 1;
            }
            else if(player.score() == maxScore)    
                amount++;
        }
        int[] winners = new int[amount];
        int index = 0;
        for(Player player : players){
            if(player.score()== maxScore){
                winners[index]= player.id;
                index++;
            }
        }
        env.ui.announceWinner(winners);
    }

    public void declareSet(int playerID){  
        //add the player set to the waitingSets vector                      
        waitingSets.add(playerID);
        try{
            //wait for the dealer to finish checking the set
            Object lock = players[playerID].getPlayerLock();
            synchronized(lock){
                dealerThread.interrupt();
                lock.wait();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void checkSetVector() {  
        while(!waitingSets.isEmpty()){
            int playerID = waitingSets.remove(0); 
            //check if the table had changed since the player declared the set
            if(table.numOfTokens(playerID) == 3){
                int[] set = new int[3];
                int index=0;
                for(int i = 0 ; i< env.config.tableSize; i++){
                    if(table.playerTokens[i][playerID]){
                        set[index] = table.slotToCard[i];
                        index++;
                    }
                }
                //test set
                if(env.util.testSet(set))
                    correctSet(set, playerID);
                else
                    incorrectSet(playerID);
            }
            else{ //notify the player that the set was not found
                Object lock = players[playerID].getPlayerLock();
                synchronized(lock){
                    lock.notifyAll();
                }
            }
        }
        for (Player player :players){
            Object lock = player.getPlayerLock();
            synchronized(lock){
                lock.notifyAll();
            }
        }
    }

    private void correctSet(int[] set, int playerID){  
        for(int card : set){
            table.removeCard(table.cardToSlot[card]);
        }
        //wake up the player and notify him that the set was correct   
        players[playerID].setStatus(1);
        Object lock = players[playerID].getPlayerLock();
        synchronized(lock){
            lock.notifyAll();
        }
    }

    private void incorrectSet(int playerID){
        //wake up the player and notify him that the set was incorrect   
        players[playerID].setStatus(-1);
        Object lock = players[playerID].getPlayerLock();
        synchronized(lock){
            lock.notifyAll();
        }
    }

    public boolean isTableReady(){
        return tableReady;
    }
}   