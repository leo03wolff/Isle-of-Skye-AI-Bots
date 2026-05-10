package org.lmu.dbs.gameLogic.AI;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.lmu.dbs.gameLogic.gameObjects.Player;
import org.lmu.dbs.gameLogic.round.Roundphase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class that has all the information for the brain class
 *
 * @author Leo
 * @author XN
 */
@Getter
@Setter
public class CerebralCortex {

    private Roundphase currentRoundphase;
    private int currentPlayerID;
    private String name;
    private String color;
    private int colorAttemptIndex = 0;
    private List<String> colors = new ArrayList<>(Arrays.asList("red", "blue", "green", "yellow", "white"));
    private List<Integer> playerIDS;
    private int money = 0;
    private int roundNumber;
    private Roundphase phase;
    public boolean gameStarted;
    private List<Integer> scoringIDs;
    private Player me;
    private int startPlayerID;
    private Map<Integer, TileOffer> currentOffers;

    /**
     * Constructor for CerebralCortex.
     * Initializes the player IDs, scoring IDs, and current offers collections.
     */
    public CerebralCortex(){
        this.playerIDS = new ArrayList<>();
        this.scoringIDs = new ArrayList<>();
        this.currentOffers = new HashMap<>();
    }

    /**
     * @return the current color attempt
     */
    public String getCurrentColorAttempt() {
        return colors.get(colorAttemptIndex);
    }

    /**
     * Sets the next color attempt
     */
    public void nextColorAttempt() {
        this.colorAttemptIndex = (colorAttemptIndex + 1) % colors.size();
    }


    /**
     * Class to save tile offers, Important data for the BuyTiles phase
     */
    @Getter
    @Setter
    public static class TileOffer {
        private int tileID;
        private int price;
        private int sellerID;

        /**
         * Constructor for TileOffer.
         * @param tileID the ID of the offered tile
         * @param price the price of the tile
         * @param sellerID the ID of the player selling the tile
         */
        public TileOffer(int tileID, int price, int sellerID) {
            this.tileID = tileID;
            this.price = price;
            this.sellerID = sellerID;
        }

    }


}
