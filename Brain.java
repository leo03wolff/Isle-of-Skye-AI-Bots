package org.lmu.dbs.gameLogic.AI;

import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lmu.dbs.gameLogic.gameObjects.Bag;
import org.lmu.dbs.gameLogic.gameObjects.Player;
import org.lmu.dbs.gameLogic.gameObjects.PlayerBoard;
import org.lmu.dbs.gameLogic.round.Roundphase;
import org.lmu.dbs.gameLogic.tile.ScoringTile;
import org.lmu.dbs.gameLogic.tile.Tile;
import org.lmu.dbs.network.message.Message;
import org.lmu.dbs.network.message.MessageType;
import org.lmu.dbs.network.message.messageBody.data.NetworkTile;
import org.lmu.dbs.network.message.messageBody.data.Scroll;
import org.lmu.dbs.network.message.messageBody.game.*;
import org.lmu.dbs.network.message.messageBody.lobby.*;

import java.awt.*;
import java.sql.Array;
import java.util.*;
import java.util.List;

/**
 * The brain class of the AI.
 * deals with all the logic of the AI
 *
 * @author Leo
 */
@Data
public class Brain {
    private static final Logger logger = LogManager.getLogger(Brain.class);
    //Attributes
    private AIMessageHandler messageHandler;
    private AIClient aiClient;
    private boolean myTurn = false;
    public CerebralCortex memory;
    private int ID;
    private boolean isAHumanInTheLobby = false;

    /**
     * Constructor for Brain.
     * @param name the name of the AI
     * @param aiClient the client instance used by this AI
     */
    public Brain(String name, AIClient aiClient) {
        this.memory = new CerebralCortex();
        this.memory.setName(name);
        this.aiClient = aiClient;
    }



    /**
     * Reacts to the welcome message and attempts to join the game.
     * Synchronizes the AI's internal memory with the current game state.
     * @param player the player object representing the AI
     * @param gameState the current state of the game
     */
    public void syncState(Player player, org.lmu.dbs.gameLogic.gameManager.GameState gameState) {
        this.ID = player.getId();
        
        // Create an independent copy of the player to isolate AI modifications
        Player botPlayer = new Player(player.getName(), player.getColor(), player.getBirthdate().toString(), player.getId(), true);
        botPlayer.setMoney(player.getMoney());
        botPlayer.setVPoints(player.getVPoints());
        botPlayer.setHand(new ArrayList<>(player.getHand()));
        botPlayer.setTiles(new ArrayList<>(player.getTiles()));
        
        // Copy the board
        PlayerBoard originalBoard = player.getPlayerBoard();
        PlayerBoard botBoard = new PlayerBoard(player.getId());
        for (int x = 0; x < 33; x++) {
            for (int y = 0; y < 33; y++) {
                Tile t = originalBoard.getTile(x, y);
                if (t != null) {
                    botBoard.addTile(t, x, y);
                }
            }
        }
        botPlayer.setPlayerBoard(botBoard);
        
        this.memory.setMe(botPlayer);
        this.memory.setRoundNumber(gameState.getRoundNr());
        this.memory.setPhase(gameState.getRoundState().getPhase());
        this.memory.setScoringIDs(gameState.getDrawnScoringTileIDs());
        this.memory.setStartPlayerID(gameState.getRoundState().getStartingPlayerID());
        this.memory.setCurrentPlayerID(gameState.getRoundState().getCurrentPlayerID());
        this.memory.setGameStarted(true);
        this.memory.setName(player.getName() + "_bot");
        this.memory.setColor(player.getColor());

        // Sync other players
        for (Player p : gameState.getPlayers()) {
            if (p.getId() != this.ID) {
                this.memory.getPlayerIDS().add(p.getId());
                this.memory.getColors().remove(p.getColor());
            }
        }
    }

    /**
     * Attempts to join the game
     */
    public void attemptToJoinGame() {
        String name = memory.getName();
        String color = memory.getCurrentColorAttempt();
        String birthday = "2025-12-12";
        Message message = new Message(MessageType.PlayerValues, new PlayerValues(name,color,birthday));
        aiClient.send(message);
    }


    /**
     * sends ready status to server
     */
    public void sendReadyStatus() {
        Message message = new Message(MessageType.SetStatus, new SetStatus(true));
        aiClient.send(message);
    }

    /**
     * saves the scoringIDs in a list when the game starts
     * @param gameStarted the GameStarted message
     */
    public void onGameStarted(GameStarted gameStarted) {
        memory.setScoringIDs(gameStarted.getScoringIDs());
        memory.setGameStarted(true);
        for(GameStarted.Castle castle : gameStarted.getCastles()) {
            if(castle.getClientID() == ID) {
                NetworkTile networkCastle = castle.getTile();
                Tile myCastle = Tile.toTile(networkCastle);

                if(memory.getMe() != null) {
                    memory.getMe().setCastleTile(myCastle);
                    break;
                }
            }
        }
    }

    private void initializePlayerMe(String name, String color, String birthdate, int id) {
        memory.setMe(new Player(name, color, birthdate, id, true));
    }

    /**
     * reacts to the player added message if the addedPlayer is the AI and initializes a player object for the AI
     * @param playerAdded the PlayerAdded message
     */
    public void onPlayerAdded(PlayerAdded playerAdded) {
        if(!playerAdded.isAI()) {
            isAHumanInTheLobby = true;

            if(memory.getMe() != null) {
                sendReadyStatus();
            }
        }

        if(playerAdded.getClientID() == ID) {
            if(memory.getMe() == null) {
                initializePlayerMe(playerAdded.getName(), playerAdded.getGamePiece(), playerAdded.getBirthDate(), playerAdded.getClientID());
            }
            if(isAHumanInTheLobby) {
                sendReadyStatus();
            }
            if (memory.isGameStarted()) {
                handlePhase();
            }
        }
        else {
            memory.getPlayerIDS().add(playerAdded.getClientID());
            memory.getColors().remove(playerAdded.getGamePiece());
        }
    }

    /**
     * Sends ready status to server
     * @param startAIGame
     */
    public void onStartAIGame(StartAIGame startAIGame){
        sendReadyStatus();
    }

    /**
     * Update the roundNumber and the round phase after receiving a RoundPhaseUpdate message
     * @param roundNumber current round number
     * @param phase current phase
     */
    public void onRoundPhaseUpdate(int roundNumber, Roundphase phase) {
        memory.setRoundNumber(roundNumber);
        memory.setPhase(phase);
        handlePhase();
    }

    /**
     * handles the actions for the AI for each phase
     */
    public void handlePhase() {
        if (memory.getPhase() == null) {
            return;
        }
        switch(memory.getPhase()) {
            case INCOME -> {
                //Happens automatically
            }
            case DRAW_TILES -> {
                //Happens automatically
                //nothing to do here
            }
            case PRICE_TILES -> {
                smartDecideAndSendPrices();
            }
            case BUY_TILES -> {
                if(memory.getCurrentPlayerID() == aiClient.getID()) {
                    smartDecideAndBuyTile();
                }
            }
            case PLACE_TILES -> {
                smartDecideAndPlaceTile();
            }
            case END_ROUND_SCORING -> {

            }
            case GAME_END_SCORE -> {

            }
            default -> {

            }

        }
    }

    /**
     * updates the currentPlayer in the AIs brain
     * @param currentPlayerID the ID of the current player
     */
    public void onCurrentPlayer(int currentPlayerID) {
        memory.setCurrentPlayerID(currentPlayerID);
        if(memory.getCurrentPlayerID() == aiClient.getID()) {
            handlePhase();
        }
    }

    /**
     * Handles the landScapeTileDrawn message for the AI
     * @param landscapeTileDrawn the LandscapeTileDrawn message
     */
    public void onLandscapeTileDrawn(LandscapeTileDrawn landscapeTileDrawn) {
        if(landscapeTileDrawn.getClientID() == aiClient.getID()) {
            Tile tile = Tile.toTile(landscapeTileDrawn.getTile());

            if(memory.getMe() != null) {
                this.memory.getMe().addTileToHand(tile);
            }
        }
    }

    /**
     * Handles the coinUpdate message for the AI
     * @param coinUpdate the CoinUpdate message
     */
    public void onCoinUpdate(CoinUpdate coinUpdate) {
        if(coinUpdate.getClientID() == aiClient.getID()) {
            memory.getMe().setMoney(coinUpdate.getCoins());
        }
    }

    /**
     * Sets the start player ID
     * @param startPlayerUpdate
     */
    public void  onStartPlayerUpdate(StartPlayerUpdate startPlayerUpdate) {
        this.memory.setStartPlayerID(startPlayerUpdate.getClientID());
    }

    /**
     * Randomly selects a Tile to discard and bets a 4th of the money on each of the Tiles in the AI's hand
     */
    public void decideAndSendPrices() {
        List<Tile> myHand = new ArrayList<>(this.memory.getMe().getHand());
        int moneyToBetOnEachTile = (this.memory.getMe().getMoney())/4;
        Random random = new Random();
        int randomInt = random.nextInt(3);
        Tile tileToDiscard = myHand.get(randomInt);
        myHand.remove(tileToDiscard);
        this.memory.getMe().getHand().removeIf(t -> t.getId() == tileToDiscard.getId());
        List<SetTilePrices.TilePrice> prices = new ArrayList<>();
        prices.add(new SetTilePrices.TilePrice(tileToDiscard.getId(), null, true));

        for(Tile tile : myHand) {
            prices.add(new SetTilePrices.TilePrice(tile.getId(), moneyToBetOnEachTile, false));
        }
        Message message = new Message(MessageType.SetTilePrices, new SetTilePrices(prices));
        logger.debug("Sending SetTilePrices: tilePrices={}", prices);
        aiClient.send(message);
        this.memory.getMe().setMoney(this.memory.getMe().getMoney() - (moneyToBetOnEachTile*2));
    }

    /**
     * Uses the evaluateTile Method to decide whether a Tile should be discarded or priced higher or lower
     */
    public void smartDecideAndSendPrices() {
        List<Tile> myHand = new ArrayList<>(this.memory.getMe().getHand());
        Map<Tile,Integer> tileScores = new HashMap<>();
        List<SetTilePrices.TilePrice> prices = new ArrayList<>();
        int moneyToPriceMostValuableTile = 0;
        int moneyToPriceSecondMostValuableTile = 0;

        for(Tile tile : myHand) {
            tileScores.put(tile, evaluateTile(tile));
        }
        myHand.sort((tile1, tile2) -> tileScores.get(tile2).compareTo(tileScores.get(tile1)));
        Tile tileToDiscard = myHand.getLast();
        this.memory.getMe().getHand().removeIf(t -> t.getId() == tileToDiscard.getId());
        prices.add(new SetTilePrices.TilePrice(tileToDiscard.getId(), 0, true));
        int deltaTileScore = evaluateTile(myHand.getFirst()) - evaluateTile(myHand.get(1));
        if(evaluateTile(myHand.getFirst()) < 40) {
            moneyToPriceMostValuableTile = this.memory.getMe().getMoney() / 4;
            moneyToPriceSecondMostValuableTile = 1;
        }
        else {
            if(deltaTileScore > 10) {
                moneyToPriceMostValuableTile = this.memory.getMe().getMoney() / 2;
                moneyToPriceSecondMostValuableTile = 1;
            }
            else {
                moneyToPriceMostValuableTile = this.memory.getMe().getMoney() / 3;
                moneyToPriceSecondMostValuableTile = moneyToPriceMostValuableTile;
            }
        }
        prices.add(new SetTilePrices.TilePrice(myHand.get(0).getId(), moneyToPriceMostValuableTile, false));
        prices.add(new SetTilePrices.TilePrice(myHand.get(1).getId(), moneyToPriceSecondMostValuableTile, false));
        Message message = new Message(MessageType.SetTilePrices, new SetTilePrices(prices));
        logger.debug("Sending SetTilePrices: tilePrices={}", prices);
        aiClient.send(message);
        this.memory.getMe().setMoney(this.memory.getMe().getMoney() - (moneyToPriceMostValuableTile + moneyToPriceSecondMostValuableTile));
    }

    /**
     * randomly selects a Tile and buys it if the AI has enough money
     */
    public void decideAndBuyTile() {
        List<CerebralCortex.TileOffer> affordableOffers = new ArrayList<>();
        for(CerebralCortex.TileOffer offer : memory.getCurrentOffers().values()) {
            if(offer.getSellerID() != aiClient.getID() && offer.getPrice() <= memory.getMe().getMoney()) {
                affordableOffers.add(offer);
            }
        }
        if(!affordableOffers.isEmpty()) {
            Random random = new Random();
            CerebralCortex.TileOffer offerToBuy = affordableOffers.get(random.nextInt(affordableOffers.size()));
            Message message = new Message(MessageType.BuyTile, new BuyTile(offerToBuy.getSellerID(),offerToBuy.getTileID()));
            logger.debug("Sending BuyTile: sellerId={} tileId={}", offerToBuy.getSellerID(), offerToBuy.getTileID());
            aiClient.send(message);
        } else {
            Message message = new Message(MessageType.BuyTile, new BuyTile(aiClient.getID(), -1));
            logger.debug("Sending BuyTile: sellerId={} tileId={}", aiClient.getID(), -1);
            aiClient.send(message);
        }
    }

    /**
     * refined method that looks at the scoring tiles and decides which tiles to buy
     */
    public void smartDecideAndBuyTile() {
        List<CerebralCortex.TileOffer> affordableOffers = new ArrayList<>();
        for(CerebralCortex.TileOffer offer : memory.getCurrentOffers().values()) {
            if(offer.getSellerID() != aiClient.getID() && offer.getPrice() <= memory.getMe().getMoney()) {
                affordableOffers.add(offer);
            }
        }
        CerebralCortex.TileOffer bestOffer = null;
        int bestScore = -1;
        if(affordableOffers.isEmpty()) {
            Message message = new Message(MessageType.BuyTile, new BuyTile(0,0));
            aiClient.send(message);
            return;
        }
        for (CerebralCortex.TileOffer offer : affordableOffers) {
            int score = evaluateTile(Bag.getTileById(offer.getTileID()));
            if(score > bestScore) {
                bestScore = score;
                bestOffer = offer;
            }
        }
        if(bestOffer != null) {
            Message message = new Message(MessageType.BuyTile, new BuyTile(bestOffer.getSellerID(),bestOffer.getTileID()));
            logger.debug("Sending BuyTile: sellerId={} tileId={}", bestOffer.getSellerID(), bestOffer.getTileID());
            aiClient.send(message);
        }
        else {
            decideAndBuyTile();
        }
    }

    /**
     * returns a list of the four scoringTiles
     * @return the list of scoring tiles for the current game
     */
    public List<ScoringTile> getScoringTiles() {
        List<ScoringTile> scoringTiles = new ArrayList<>();
        for(int scoringID : memory.getScoringIDs()) {
            ScoringTile scoringTile = ScoringTile.getScoringTileByID(scoringID);
            scoringTiles.add(scoringTile);
        }
        return scoringTiles;
    }

    /**
     * calculates the weights of the scoring tiles. The lower the number the less this scoringTile is worth
     * @return the weights of the scoring tiles
     */
    private int [] calculateScoringTileWeights() {
        int [] weights = {4,4,4,4};
        int playerAmount = getMemory().getPlayerIDS().size();
        if(playerAmount < 5) {
            for(int roundNumber = getMemory().getRoundNumber(); roundNumber <= 6; roundNumber++) {
                switch(roundNumber) {
                    case 1:
                        weights[0]--;
                        break;

                    case 2:
                        weights[1]--;
                        break;

                    case 3:
                        weights[0]--;
                        weights[2]--;
                        break;

                    case 4:
                        weights[1]--;
                        weights[3]--;
                        break;

                    case 5:
                        weights[0]--;
                        weights[2]--;
                        weights[3]--;
                        break;

                    case 6:
                        weights[1]--;
                        weights[2]--;
                        weights[3]--;
                        break;
                }
            }
        }
        else {
            for (int roundNumber = getMemory().getRoundNumber(); roundNumber <= 5; roundNumber++) {
                switch (roundNumber) {
                    case 1:
                        weights[0]--;
                        weights[1]--;
                        break;

                    case 2:
                        weights[0]--;
                        weights[2]--;
                        break;

                    case 3:
                        weights[1]--;
                        weights[3]--;
                        break;

                    case 4:
                        weights[0]--;
                        weights[2]--;
                        weights[3]--;
                        break;

                    case 5:
                        weights[1]--;
                        weights[2]--;
                        weights[3]--;
                        break;
                }
            }
        }
        return weights;
    }

    /**
     * returns the scoringTile with the highest weight as it is the most valuable
     */
    private int getMostValuableScoringTileID() {
        int[] scoringTileWeights = calculateScoringTileWeights();
        int position = 0;
        for(int i = 0; i < scoringTileWeights.length; i++) {
            if(scoringTileWeights[i] > scoringTileWeights[position]) {
                position = i;
            }
        }
        return getMemory().getScoringIDs().get(position);
    }

    /**
     * returns a list of the important scoringTile elements
     */
    private List<String> getScoringTileElements(int scoringTileID){
        List<String> elements = new ArrayList<>();
        switch(scoringTileID) {
            case 0:
                break;

            case 1:
                elements.add("sheep");
                break;

            case 2:
                elements.add("tower");
                elements.add("mountain");
                break;

            case 3:
                elements.add("whiskey");
                break;

            case 4:
                elements.add("sheep");
                elements.add("ox");
                elements.add("farm");
                break;

            case 5:
                elements.add("ox");
                break;

            case 6:
                break;

            case 7:
                elements.add("water");
                break;

            case 8:
                break;

            case 9:
                break;

            case 10:
                elements.add("tower");
                elements.add("lighthouse");
                elements.add("farm");
                break;

            case 11:
                elements.add("ship");
                break;

            case 12:
                break;

            case 13:
                elements.add("ship");
                elements.add("water");
                elements.add("lighthouse");
                break;

            case 14:
                break;

            case 15:
                elements.add("mountain");
                break;

        }
        return  elements;
    }

    /**
     * evaluates a score for a tile in regard of all four scoringTiles
     */
    private int evaluateTile(Tile tile) {
        int score = 0;
        int [] weights = calculateScoringTileWeights();
        List<Integer> scoringTileIDs = memory.getScoringIDs();

        for(int i = 0; i < 4; i++) {
            int weight = weights[i];
            int scoringTileID = scoringTileIDs.get(i);
            int additionalScorePoints = evaluateScoringTileScore(scoringTileID);
            List<String> scoringElements = getScoringTileElements(scoringTileID);
            List<String> tileElements = getAllElements(tile);
            List<String> commonElements = getSameElements(scoringElements, tileElements);
            if(scoringTileID == getMostValuableScoringTileID()) {
                score += 30;
            }
            if(commonElements.isEmpty()) {
                continue;
            }
            if(scoringElements.size() == commonElements.size()) {
                score += (20+additionalScorePoints) * weight;
            }
            else if (scoringElements.size() == (commonElements.size() + 1)) {
                score += (15+additionalScorePoints) * weight;
            }
            else {
                score += (5+additionalScorePoints) * weight;
            }
        }
        return score;
    }

    /**
     * evaluates the scoring Tiles based on if the player will receive more VPs for this scoring Tile
     */
    private int evaluateScoringTileScore (int scoringTileID){
        int additionalScorePoints = 1;
        switch (scoringTileID) {
            case 0:
                break;

            case 1:
                additionalScorePoints = 2;
                break;

            case 2:
                additionalScorePoints = 2;
                break;

            case 3:
                additionalScorePoints = 2;
                break;

            case 4:
                additionalScorePoints = 2;
                break;

            case 5:
                additionalScorePoints = 2;
                break;

            case 6:
                break;

            case 7:
                break;

            case 8:
                break;

            case 9:
                break;

            case 10:
                additionalScorePoints = 2;
                break;

            case 11:
                additionalScorePoints = 2;
                break;

            case 12:
                break;

            case 13:
                additionalScorePoints = 2;
                break;

            case 14:
                break;

            case 15:
                break;

        }
        return additionalScorePoints;
    }

    /**
     * gets the same elements from the scoring Tile and a Tile
     */
    private List<String> getSameElements(List<String> scoringElements, List<String> tileElements) {
        List<String> sameElements = new ArrayList<>();

        for(String element : scoringElements){
            if(tileElements.contains(element) && !sameElements.contains(element)) {
                sameElements.add(element);
            }
        }
        return sameElements;
    }

    /**
     * returns a list of all the elements and area types of a Tile
     */
    private List<String> getAllElements(Tile tile) {
        Set<String> areas = new HashSet<>();
        areas.add(tile.getTop());
        areas.add(tile.getRight());
        areas.add(tile.getDown());
        areas.add(tile.getLeft());
        areas.addAll(tile.getElements());
        return new ArrayList<>(areas);
    }

    /**
     * Saves the offers and the client IDs in a Hashmap
     * @param revealTilePrices
     */
    public void onRevealTilePrices(RevealTilePrices revealTilePrices) {
        memory.getCurrentOffers().clear();

        for(RevealTilePrices.ClientTilePrices clientTilePrices : revealTilePrices.getTilePrices()) {
            int sellerID = clientTilePrices.getClientID();

            for(RevealTilePrices.TilePrice tilePrice : clientTilePrices.getPrices()) {

                if(!tilePrice.isDiscard()) {
                    int tileID = tilePrice.getTileID();
                    int price = tilePrice.getPrice();
                    CerebralCortex.TileOffer offer = new CerebralCortex.TileOffer(tileID, price, sellerID);
                    memory.getCurrentOffers().put(tileID, offer);
                }
            }
        }
    }

    /**
     * This method uses Tile.Placement and getAllValidPlacements from the PlayerBoard Class to decide
     * where to place the Tile randomly
     */
    public void decideAndPlaceTiles() {
        Player me = this.memory.getMe();

        if(me == null || me.getHand().isEmpty()) {
           //error
            return;
        }
        List <Tile> tilesToBuild = new ArrayList<>(me.getHand());

        for(Tile tile : tilesToBuild) {
            List<Tile.Placement> placements = me.getPlayerBoard().getAllValidPlacements(tile);

            if(placements != null && !placements.isEmpty()) {
                Random random = new Random();
                Tile.Placement chosenPlacement = placements.get(random.nextInt(placements.size()));
                int x = chosenPlacement.getX();
                int y = chosenPlacement.getY();
                int rotation = 0;

                for (int r : new int[]{0, 90, 180, 270}) {
                    if (tile.rotateTile(r).equals(chosenPlacement.getTile())) {
                        rotation = r;
                        break;
                    }
                }

                Message message = new Message(MessageType.BuildTile, new BuildTile(tile.getId(), x, y, rotation));
                logger.debug("Sending BuildTile: tileId={} x={} y={} rotation={}", tile.getId(), x, y, rotation);
                aiClient.send(message);
                me.getPlayerBoard().placeTile(chosenPlacement);
                me.getHand().remove(tile);
            }
            else {
                Message message = new Message(MessageType.TileReturned, new TileReturned(aiClient.getID(), tile.getId()));
                logger.debug("Sending TileReturned: clientId={} tileId={}", aiClient.getID(), tile.getId());
                aiClient.send(message);
            }
        }
    }


    /**
     * A smart Algorithm to decide where to place the tiles
     */
    public void smartDecideAndPlaceTile() {
        Player me = this.memory.getMe();
        if(me == null || me.getHand().isEmpty()) {
            return;
        }
        List <Tile> tilesToBuild = new ArrayList<>(me.getHand());

        for(Tile tile : tilesToBuild) {
            List<Tile.Placement> placements = me.getPlayerBoard().getAllValidPlacements(tile);

            if(placements != null && !placements.isEmpty()) {
                Tile.Placement bestPlacement = null;
                int bestScore = -1;

                for(Tile.Placement placement : placements) {
                    int x = placement.getX();
                    int y = placement.getY();
                    int score = 0;
                    Tile placementTile = placement.getTile();
                    PlayerBoard playerBoard = me.getPlayerBoard();

                    boolean connectsToRoad = false;
                    if(hasValidRoadConnection(placementTile, playerBoard, x, y)) {
                        connectsToRoad = true;
                    }

                    if(connectsToRoad) {
                        score += 100;
                    }

                    if(checkConnectionToCastle(placementTile, x, y, playerBoard)) {
                        score += 200;
                    }

                    int neighbors = 0;
                    if(playerBoard.getTile(x-1, y) != null){
                        neighbors++;
                    }
                    if(playerBoard.getTile(x+1, y) != null){
                        neighbors++;
                    }
                    if(playerBoard.getTile(x, y-1) != null){
                        neighbors++;
                    }
                    if(playerBoard.getTile(x, y+1) != null){
                        neighbors++;
                    }
                    score += neighbors * 10;

                    if(score > bestScore) {
                        bestScore = score;
                        bestPlacement = placement;
                    }
                }

                if(bestPlacement == null) {
                    bestPlacement = placements.getFirst();
                }
                int x = bestPlacement.getX();
                int y = bestPlacement.getY();
                int rotation = 0;

                for (int r : new int[]{0, 90, 180, 270}) {

                    if(tile.rotateTile(r).equals(bestPlacement.getTile())) {
                        rotation = r;
                        break;
                    }
                }
                Message message = new Message(MessageType.BuildTile, new BuildTile(tile.getId(), x, y, rotation));
                logger.debug("Sending BuildTile: tileId={} x={} y={} rotation={}", tile.getId(), x, y, rotation);
                aiClient.send(message);
                me.getPlayerBoard().placeTile(bestPlacement);
                me.getHand().remove(tile);
            }
            else {
                Message message = new Message(MessageType.TileReturned, new TileReturned(aiClient.getID(), tile.getId()));
                logger.debug("Sending TileReturned: clientId={} tileId={}", aiClient.getID(), tile.getId());
                aiClient.send(message);
                me.getHand().remove(tile);
            }
        }
    }

    /**
     * Checks if the Tile has a valid Placement where it also connects roads
     */
    private boolean hasValidRoadConnection(Tile tile, PlayerBoard playerBoard, int x, int y) {
        if(checkConnection(tile, playerBoard.getTile(x-1, y), "left")) return  true;
        if(checkConnection(tile, playerBoard.getTile(x+1, y), "right")) return  true;
        if(checkConnection(tile, playerBoard.getTile(x, y-1), "top")) return  true;
        if(checkConnection(tile, playerBoard.getTile(x, y+1), "down")) return  true;

        return false;
    }

    private boolean checkConnection (Tile myTile, Tile neighboringTile, String neighborOnSide) {
        if(neighboringTile == null) return false;

        List<List<String>> roads = neighboringTile.getRoads();
        List<List<String>> myRoads = myTile.getRoads();

        switch(neighborOnSide) {

            case "top":
                if (roads.stream().flatMap(List::stream).anyMatch(s -> s.equals("down")) && myRoads.stream().flatMap(List::stream).anyMatch(s -> s.equals("top"))) {
                    return true;
                }
                break;

            case "down":
                if (roads.stream().flatMap(List::stream).anyMatch(s -> s.equals("top")) && myRoads.stream().flatMap(List::stream).anyMatch(s -> s.equals("down"))) {
                    return true;
                }
                break;

            case "left":
                if (roads.stream().flatMap(List::stream).anyMatch(s -> s.equals("right")) && myRoads.stream().flatMap(List::stream).anyMatch(s -> s.equals("left"))) {
                    return true;
                }
                break;

            case "right":
                if (roads.stream().flatMap(List::stream).anyMatch(s -> s.equals("left")) && myRoads.stream().flatMap(List::stream).anyMatch(s -> s.equals("right"))) {
                    return true;
                }
                break;
        }
        return false;
    }

    /**
     * Checks if a Tile is connected to the castle via roads
     * @param startTile this is the tile of the position the bot wants to place the tile
     * @param startX x coordinate of the tile
     * @param startY y coordinate of the tile
     * @param playerBoard the bots own playerboard
     * @return true if the tile is connected to the castle via roads, false otherwise
     */
    private boolean checkConnectionToCastle(Tile startTile, int startX, int startY, PlayerBoard playerBoard) {
        Queue<Point> queue = new LinkedList<>();
        Set<Point> visited = new HashSet<>();

        Point currentPosition = new Point(startX, startY);
        queue.add(currentPosition);
        visited.add(currentPosition);

        while (!queue.isEmpty()) {
            Point currentPoint = queue.poll();
            Tile currentTile;

            if(currentPoint.x == startX && currentPoint.y == startY) {
                currentTile = startTile;
            }
            else {
                currentTile = playerBoard.getTile(currentPoint.x, currentPoint.y);
            }
            if(currentTile == null) {
                continue;
            }
            if(currentTile.getElements().contains("castle")) {
                return true;
            }
            addNeighbors(currentPoint, currentTile, playerBoard, queue, visited);
        }
        return false;
    }

    private void addNeighbors(Point currentPoint, Tile currentTile, PlayerBoard playerBoard, Queue<Point> queue, Set<Point> visited) {
        int x = currentPoint.x;
        int y = currentPoint.y;

        if(checkConnection(currentTile, playerBoard.getTile(x, y -1), "top")) {
            addIfUnvisited(new Point(x, y-1), queue, visited);
        }
        if(checkConnection(currentTile, playerBoard.getTile(x, y +1), "down")) {
            addIfUnvisited(new Point(x, y+1), queue, visited);
        }
        if(checkConnection(currentTile, playerBoard.getTile(x -1, y), "left")) {
            addIfUnvisited(new Point(x-1, y), queue, visited);
        }
        if(checkConnection(currentTile, playerBoard.getTile(x +1, y), "right")) {
            addIfUnvisited(new Point(x+1, y), queue, visited);
        }
    }


    private void addIfUnvisited(Point point, Queue<Point> queue, Set<Point> visited) {
        if(!visited.contains(point)) {
            queue.add(point);
            visited.add(point);
        }
    }


    /**
     * Handles the tilePurchased message
     * @param tilePurchased the TilePurchased message
     */
    public void onTilePurchased(TilePurchased tilePurchased) {
        if(tilePurchased.getBuyerID() == aiClient.getID()) {
            int purchasedTileID = tilePurchased.getTileID();
            Tile purchasedTile = Bag.getTileById(purchasedTileID);
            this.memory.getMe().addTileToHand(purchasedTile);
        }
        if(tilePurchased.getSellerID() == aiClient.getID()) {
            this.memory.getMe().getHand().removeIf(tile -> tile.getId() == tilePurchased.getTileID());
        }
        memory.getCurrentOffers().remove(tilePurchased.getTileID());
    }


    /**
     * If the Tile is returned to the client it will be added back to the clients hand
     * @param tileReturned the TileReturned message
     */
    public void onTileReturned(TileReturned tileReturned){
        if(tileReturned.getClientID() == aiClient.getID()) {
            Tile returnedTile = Bag.getTileById(tileReturned.getTileID());
            this.memory.getMe().addTileToHand(returnedTile);
        }
    }

}
