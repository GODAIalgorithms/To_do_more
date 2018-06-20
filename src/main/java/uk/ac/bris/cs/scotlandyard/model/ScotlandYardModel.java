package uk.ac.bris.cs.scotlandyard.model;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableCollection;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static uk.ac.bris.cs.scotlandyard.model.Colour.BLACK;
import static uk.ac.bris.cs.scotlandyard.model.Ticket.DOUBLE;
import static uk.ac.bris.cs.scotlandyard.model.Ticket.SECRET;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Iterator;
import java.util.function.Consumer;
import uk.ac.bris.cs.gamekit.graph.Edge;
import uk.ac.bris.cs.gamekit.graph.Node;
import uk.ac.bris.cs.gamekit.graph.Graph;
import uk.ac.bris.cs.gamekit.graph.ImmutableGraph;

public class ScotlandYardModel implements ScotlandYardGame, Consumer<Move>, MoveVisitor {

    private final List<Boolean> rounds;
    private final Graph<Integer, Transport> graph;
    private List<Spectator> spectators;
    private List<ScotlandYardPlayer> players;
    private Colour currentPlayerColour;
    private Set<Move> possibleMoves;
    private Integer mrXLastKnown;
    private int round;
    private Boolean reciveCallback;
    private Move lastMove;

    //////////////
    //Constructor
    //////////////
    public ScotlandYardModel(List<Boolean> rounds, Graph<Integer, Transport> graph,
            PlayerConfiguration mrX, PlayerConfiguration firstDetective,
            PlayerConfiguration... restOfTheDetectives) {

        //Check all players and fields are not null
        this.rounds = requireNonNull(rounds);
        this.graph = requireNonNull(graph);

        //Add player initial configs to array.
        final List<PlayerConfiguration> configurations = new ArrayList<>();
        configurations.add(requireNonNull(mrX));
        configurations.add(requireNonNull(firstDetective));
        for (PlayerConfiguration detective : restOfTheDetectives) {
            configurations.add(requireNonNull(detective));
        }

        //Check rounds and graphs are not empty and Mrx is black
        if (rounds.isEmpty()) {
            throw new IllegalArgumentException("Empty rounds");
        }
        if (graph.isEmpty()) {
            throw new IllegalArgumentException("Empty graph");
        }
        if (mrX.colour != BLACK) { // or mr.colour.isDetective()
            throw new IllegalArgumentException("MrX should be Black");
        }

        //Check all players have unique locations
        Set<Integer> playerLocations = new HashSet<>();
        for (PlayerConfiguration player : configurations) {
            if (playerLocations.contains(player.location)) {
                throw new IllegalArgumentException("A player has a duplicate location");
            }
            playerLocations.add(player.location);
        }

        //Check all players have different colours
        Set<Colour> playerColours = new HashSet<>();
        for (PlayerConfiguration player : configurations) {
            if (playerColours.contains(player.colour)) {
                throw new IllegalArgumentException("A player has a duplicate colour");
            }
            playerColours.add(player.colour);
        }

        //check all ticket types exist for all players
        Set<Ticket> ticketSet = new HashSet<>(Arrays.asList(Ticket.values()));
        for (PlayerConfiguration player : configurations) {
            if (!player.tickets.keySet().containsAll(ticketSet)) {
                throw new IllegalArgumentException("A player has missing ticket key(s)");
            }
        }

        //Check that detectives dont have secret or hidden tickets
        for (PlayerConfiguration player : configurations) {
            if (player.tickets.get(Ticket.SECRET) + player.tickets.get(Ticket.DOUBLE) > 0 && !mrX.equals(player)) {
                throw new IllegalArgumentException("A dective contains secret or double tickets");
            }
        }

        //Initialise players
        this.players = new ArrayList<>();
        for (PlayerConfiguration player : configurations) {
            this.players.add(initPlayer(player));
        }
        currentPlayerColour = mrX.colour;
        possibleMoves = new HashSet<>();

        //Initialise rounds
        round = 0;
        mrXLastKnown = 0;
        reciveCallback = false;
        lastMove = null;

        //Spectators
        spectators = new ArrayList<>();
    }

    //////////////
    //Util
    //////////////
    //Initialise a ScotlandYardPlayer from PlayerConfiguration
    private ScotlandYardPlayer initPlayer(PlayerConfiguration player) {
        return new ScotlandYardPlayer(player.player, player.colour, player.location, player.tickets);
    }

    private Set<TicketMove> ticketMoves(ScotlandYardPlayer player, int sourceLocation) {
        Set<TicketMove> moves = new HashSet<>();
        Collection<Edge<Integer, Transport>> nearbyMoves = graph.getEdgesFrom(graph.getNode(sourceLocation));
        Iterator<Edge<Integer, Transport>> iterator = nearbyMoves.iterator();
        //List of all player locations to exclude                         
        List<Integer> playerLocations = getPlayerLocations();
        playerLocations.removeAll(Collections.singleton(player.location()));
        //remove mrx location if current is detective(not mrx)
        if (!player.isMrX()) {
            playerLocations.removeAll(Collections.singleton(getPlayerData(Colour.BLACK).location()));
        }
        while (iterator.hasNext()) {
            Edge<Integer, Transport> location = iterator.next();
            //whether another one is located in the target destination
            if (!playerLocations.contains(location.destination().value())) {
                if (player.hasTickets(Ticket.SECRET)) {
                    moves.add(new TicketMove(player.colour(), Ticket.SECRET, location.destination().value()));
                }
                if (location.data().equals(Transport.TAXI) && player.hasTickets(Ticket.TAXI)) {
                    moves.add(new TicketMove(player.colour(), Ticket.TAXI, location.destination().value()));
                }
                if (location.data().equals(Transport.BUS) && player.hasTickets(Ticket.BUS)) {
                    moves.add(new TicketMove(player.colour(), Ticket.BUS, location.destination().value()));
                }
                if (location.data().equals(Transport.UNDERGROUND) && player.hasTickets(Ticket.UNDERGROUND)) {
                    moves.add(new TicketMove(player.colour(), Ticket.UNDERGROUND, location.destination().value()));
                }
            }
        }
      //*//  
                if (playerLocations.contains(player.location())) {
            moves.clear();
        }
        return moves;
    }

    //Compute a set of possible moves for the given player
    private Set<Move> possibleMoves(ScotlandYardPlayer player) {
        Set<Move> moves = new HashSet<>();
        Set<TicketMove> ticketMoves = ticketMoves(player, player.location());
        moves.addAll(ticketMoves);
//we should have at least 2 rounds for double moves
        if (player.hasTickets(Ticket.DOUBLE) && (round <= rounds.size() - 2)) {
            Iterator<TicketMove> eachFirstPath = ticketMoves.iterator();
            while (eachFirstPath.hasNext()) {//hasNext

                TicketMove firstPath = eachFirstPath.next();
                player.removeTicket(firstPath.ticket());
            Iterator<TicketMove> eachSecondPath = ticketMoves(player, firstPath.destination()).iterator();
                while (eachSecondPath.hasNext()) {
                    moves.add(new DoubleMove(player.colour(), firstPath, eachSecondPath.next()));
                }
                player.addTicket(firstPath.ticket());
            }
        }
        if (moves.isEmpty() && !player.isMrX()) {
            moves.add(new PassMove(player.colour()));
        }
        return moves;
    }

    //Get the player locations
    private List<Integer> getPlayerLocations() {
        List<Integer> playerLocations = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            playerLocations.add(players.get(i).location());
        }
        return playerLocations;
    }

    //check if a player is stuck（false）
    private Boolean isStuck(ScotlandYardPlayer player) {
        Set<Move> moves = possibleMoves(player);
        Iterator<Move> movesIt = moves.iterator();
        Boolean movable = false;
        while (movesIt.hasNext()) {
            Move move = movesIt.next();
            if (!(move instanceof PassMove)) {
                movable = true;
            }
        }
        if (!movable) {
            return true;
        }
        return false;
    }

    //check if mrx caught
    private Set<Colour> MrXCaught() {
        Integer mrXLoc = getPlayerData(Colour.BLACK).location();
        Set<Colour> winners = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).location() == mrXLoc && !players.get(i).isMrX()) {
                winners.addAll(getPlayers());
                winners.remove(BLACK);
            }
        }
        return winners;
    }

    //Get the player object from colour.
    private ScotlandYardPlayer getPlayerData(Colour colour) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).colour().equals(colour)) {
                return players.get(i);
            }
        }
        throw new IllegalArgumentException("Player colour is not in the game.");
    }
    
    //Get the player object from colour(default to current player).
    private ScotlandYardPlayer getPlayerData() {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).colour().equals(currentPlayerColour)) {
                return players.get(i);
            }
        }
        throw new IllegalArgumentException("Player colour is not in the game.");
    }

    //check if mr is hidden
    private Boolean MrXHidden() {
        if (round >= rounds.size()) {
            return false;
        }
        return !rounds.get(round);
    }

    //check if mr is hidden on next round
    private Boolean MrXHiddenNext() {
        if (round >= rounds.size()) {
            return false;
        }
        return !rounds.get(round + 1);
    }

    //check if mr is hidden on te previous
    private Boolean MrXHiddenPrev() {
        if (round <= 0) {
            return true;
        }
        return !rounds.get(round - 1);
    }

    //////////////
    //Spectators
    //////////////
    @Override
    public void registerSpectator(Spectator spectator) {
        if (spectator == null) {
            throw new NullPointerException("can register null spectator");
        }
        if (spectators.contains(spectator)) {
            throw new IllegalArgumentException("Cant register same spectator");
        }
        spectators.add(spectator);
    }

    @Override
    public void unregisterSpectator(Spectator spectator) {
        if (spectator == null) {
            throw new NullPointerException("can unregister null spectator");
        }
        if (!spectators.contains(spectator)) {
            throw new IllegalArgumentException("can unregister nonexistant spectator");
        }
        spectators.remove(spectator);
    }

    private void OnRoundStarted() {
        for (int i = 0; i < spectators.size(); i++) {
            spectators.get(i).onRoundStarted(this, round);
        }
    }

    private void OnMoveMade(Move move) {
        for (int i = 0; i < spectators.size(); i++) {
            spectators.get(i).onMoveMade(this, move);
        }
    }

    private void OnRotationComplete() {
        for (int i = 0; i < spectators.size(); i++) {
            spectators.get(i).onRotationComplete(this);
        }
    }

    private void OnGameOver() {
        for (int i = 0; i < spectators.size(); i++) {
            spectators.get(i).onGameOver(this, getWinningPlayers());
        }
    }
    
    //////////////
    //Rounds
    //////////////
    //Advance to the next player
    private Boolean advancePlayer(Boolean advanceRound) {
        Boolean firstRound = false;
        if (getPlayerData().isMrX()) {// start here at first
            if (advanceRound) {
                round++;
            }
            firstRound = true;
        }
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).equals(getPlayerData())) {
                if (i >= players.size() - 1) {
                    currentPlayerColour = players.get(0).colour();
                    break;
                } else {
                    currentPlayerColour = players.get(i + 1).colour();
                    break;
                }
            }
        }
        System.out.println("--------------Changed");
        System.out.println("Player:" + currentPlayerColour);
        System.out.println("Round:" + round);
        return firstRound;
    }

    //Asks current player what move they want to make
    private void makeMove() {
        System.out.println("--------------makeMove");
        System.out.println("Player:" + currentPlayerColour);
        System.out.println("Location:" + getPlayerData().location());
        possibleMoves.clear();
        possibleMoves.addAll(possibleMoves(getPlayerData()));
        reciveCallback = true; //set a callback for accept for the players response  
        getPlayerData().player().makeMove(this, getPlayerData().location(), possibleMoves, andThen(this));
    }
    
    //////////////
    //Callback
    //////////////
    @Override
    public void accept(Move move) {
        if (reciveCallback && (!move.equals(lastMove))) {
            reciveCallback = false;
            lastMove = move;
            System.out.println("--------------Accept");
            if (move == null) {
                throw new NullPointerException("Chosen move is null");
            }
            if (!possibleMoves.contains(move)) {
                System.out.println("Chosen move:" + move + " is not valid");
                throw new IllegalArgumentException("Chosen move is not valid");
            }
            move.visit(this);
        } else {
            System.out.println("######dummy:" + move);
        }
    }

    @Override
    public void startRotate() {
        if (isGameOver()) {
            throw new IllegalStateException("Game is allready over");
        }
        System.out.println("------------------------Start Rotate");
        System.out.println("--------------Status");
        System.out.println("Player:" + currentPlayerColour);
        System.out.println("Round:" + round);
        makeMove();
    }

    //////////////
    //Visitors
    //////////////
    public void visit(PassMove move) {
        System.out.println("Asked:" + move);
        advancePlayer(true);
        if (getPlayerData().isMrX()) {
            OnMoveMade(move);
            OnRotationComplete();
            System.out.println("------------------------End of rotation");
        } else {
            OnMoveMade(move);
            makeMove();
        }
    }

    @Override
    public void visit(TicketMove move) {
        System.out.println("Asked:" + move);
        getPlayerData().location(move.destination());//get the location
        getPlayerData().removeTicket(move.ticket());// get the ticket
        TicketMove hiddenMove = new TicketMove(Colour.BLACK, move.ticket(), mrXLastKnown); //particulat hidden ticket
        //decide whether to add tickets or hide move
        if (currentPlayerColour.isDetective()) {
            getPlayerData(Colour.BLACK).addTicket(move.ticket());// let detective make move and get the mrx data
        } else {
            if (MrXHidden()) {
                move = hiddenMove;
            }
        }
        if (advancePlayer(true)) {
            //first turn on round
            OnRoundStarted();
            OnMoveMade(move);
            makeMove();
        } else {
            //any other turn
            OnMoveMade(move);
            if (isGameOver()) {
                OnGameOver();
                if (!MrXCaught().isEmpty() || getPlayerData().isMrX()) {
                    return;
                }
            }
            
            //decide wheather to end round or continue
            if (getPlayerData().isMrX()) {
                OnRotationComplete();
                System.out.println("------------------------End of rotation");
            } else {
                makeMove();
            }
        }

    }

    @Override
    public void visit(DoubleMove move) {
        System.out.println("Asked:" + move);
        getPlayerData().removeTicket(Ticket.DOUBLE);
        DoubleMove originalMove = move;
        TicketMove hiddenMove1 = new TicketMove(Colour.BLACK, move.firstMove().ticket(), mrXLastKnown);
        TicketMove hiddenMove2 = new TicketMove(Colour.BLACK, move.secondMove().ticket(), mrXLastKnown);
        DoubleMove hiddenMove = new DoubleMove(Colour.BLACK, hiddenMove1, hiddenMove2);
        System.out.println("[");
        for (int i = 0; i < rounds.size(); i++) {   // that particular round
            if (!rounds.get(i)) {
                System.out.print("hidden, ");
            } else {
                System.out.print("revealed, ");
            }
        }
        System.out.println("]");
        System.out.println("hidden: " + MrXHidden());
        System.out.println("hidden next: " + MrXHiddenNext());
        System.out.println("hidden Prev: " + MrXHiddenPrev());
        System.out.println("round " + round);;
        System.out.println("------------");
        if (MrXHidden() && MrXHiddenNext()) {
            move = hiddenMove;
        } else if (!MrXHidden() && MrXHiddenNext()) {                                    //hidden now but revealed on the next round
            TicketMove partialMove2 = new TicketMove(Colour.BLACK, move.secondMove().ticket(), move.firstMove().destination());
            DoubleMove partialMove = new DoubleMove(Colour.BLACK, move.firstMove(), partialMove2);  //for doublemove
            move = partialMove;
        } else if (MrXHidden() && !MrXHiddenNext()) {
            //revealed but hidden ont he next round
        
            TicketMove partialMove1 = new TicketMove(Colour.BLACK, move.firstMove().ticket(), 0);
            DoubleMove partialMove = new DoubleMove(Colour.BLACK, partialMove1, move.secondMove());
            move = partialMove;
        }
        
        advancePlayer(false);
        OnMoveMade(move);
        round++;
        getPlayerData(BLACK).removeTicket(move.firstMove().ticket());
        getPlayerData(BLACK).location(originalMove.firstMove().destination());
        OnRoundStarted();
        OnMoveMade(move.firstMove());
        round++;
        getPlayerData(BLACK).removeTicket(move.secondMove().ticket());
        getPlayerData(BLACK).location(originalMove.finalDestination());
        OnRoundStarted();
        OnMoveMade(move.secondMove());
        makeMove();

    }

    //////////////////////
    //Setters and getters
    //////////////////////
    @Override
    public Collection<Spectator> getSpectators() {    
        return Collections.unmodifiableList(spectators); 
        //return an immutable list of all the spectators
    }

    @Override
    public List<Colour> getPlayers() {
        List<Colour> colours = new ArrayList<>();
        for (ScotlandYardPlayer player : players) {
            colours.add(player.colour());
        }
        return Collections.unmodifiableList(colours);
    }

    @Override
    public Set<Colour> getWinningPlayers() {
        Set<Colour> winners = new HashSet<>();
        Integer mrXLoc = getPlayerData(Colour.BLACK).location();
        //check if mrx caught
        winners.addAll(MrXCaught());
        //check if mrx is cornered
        Iterator<Edge<Integer, Transport>> paths = graph.getEdgesFrom(graph.getNode(mrXLoc)).iterator();
        List<Integer> neighbours = new ArrayList<>();
        while (paths.hasNext()) {
            neighbours.add(paths.next().destination().value());
        }
        if (getPlayerLocations().containsAll(neighbours)) { //all neighbouring nodes are detective
            System.out.println("mrx cornered");
            for (int i = 0; i < players.size(); i++) {
                if (neighbours.contains(players.get(i).location())) {
                    winners.add(players.get(i).colour());
                }
            }
        }
        //check if mrx is stuck(no tickets)
        if (isStuck(getPlayerData(Colour.BLACK))) {
            winners.addAll(getPlayers());
            winners.remove(BLACK);
        }
        //check if all detectives stuck(no tickets)
        Iterator<Colour> playersIt = getPlayers().iterator();
        Boolean freeDetective = false;
        while (playersIt.hasNext()) {
            Colour player = playersIt.next();
            if (player.isDetective() && !isStuck(getPlayerData(player))) {
                freeDetective = true;// state this line at first
            }
        }
        if (!freeDetective) {
            winners.add(BLACK);
            System.out.println("detectives stuck");
        }
        //Check if rounds are over
        if (round >= rounds.size() && (currentPlayerColour == BLACK)) {
            winners.add(BLACK);
        }
        return Collections.unmodifiableSet(winners);
    }

    @Override
    public Optional<Integer> getPlayerLocation(Colour colour) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).colour().equals(colour) && !(players.get(i).colour().isMrX() && MrXHiddenPrev())) {
                if (players.get(i).colour().isMrX()) {
                    mrXLastKnown = players.get(i).location();
                }
                return Optional.of(players.get(i).location());
            }
            if (players.get(i).colour().equals(colour) && players.get(i).colour().isMrX() && MrXHiddenPrev()) {
                return Optional.of(mrXLastKnown);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Integer> getPlayerTickets(Colour colour, Ticket ticket) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).colour().equals(colour)) {
                return Optional.of(players.get(i).tickets().get(ticket));
            }
        }
        return Optional.empty();
    }// takes in the current player color and map key and return an integer of remaining tickets

    @Override
    public boolean isGameOver() {
        if (getWinningPlayers().size() == 0) {
            return false;// no winning player
        }
        return true;
    }

    @Override
    public Colour getCurrentPlayer() {
        return currentPlayerColour;
    }

    @Override
    public int getCurrentRound() {//integer meaning the current round
        return round;
    }

    @Override
    public List<Boolean> getRounds() { //return the round list of which round should be hidden as booleans
        return Collections.unmodifiableList(rounds);
    }

    @Override
    public Graph<Integer, Transport> getGraph() {
        return new ImmutableGraph<>(graph);
    }

}
