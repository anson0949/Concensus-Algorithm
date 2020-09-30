import java.io.*;
import java.net.*;
import java.util.*;

//  Coordinator <port> <parts> [<option>]
public class Coordinator {

    private volatile int MAX_PARTICIPANTS;
    private volatile int participantsCount;                         // Counts the number of participants that has been connected
    private Map<Socket,String> socketPortsMap = new HashMap<>();    // Maps the socket of participant with port of participant
    private String optionsString;                                   // String containing the options to be voted
    private ServerSocket listener;                                  // Listener socket for the coordinator
    private volatile Map<String,OutcomeToken> result;                     // Map of port to their voted option
    private volatile int resultCount;                               // Number of threads that has received a result
    private volatile String winner;                                 // winner of the votes
    private volatile ArrayList<String> tiedOptions;                 // Stores the options that were tied for every participant
    private volatile ArrayList<String> failedParticipants = new ArrayList<>();  // Stores a list of failed participants

    public static void main(String[] args) {

        try {

            int port = Integer.parseInt(args[0]);
            int parts = Integer.parseInt(args[1]);

            ArrayList<String> options = new ArrayList<>();
            for(int i = 2; i < args.length; i++){
                options.add(args[i]);
            }

            Coordinator c = new Coordinator(port, parts, options);
            c.startListening();

        } catch(IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Constructor for the coordinator
     * @param port          Port of this coordinator
     * @param max           Max number of participants
     * @param options       The options that are going to be voted
     * @throws IOException
     */
    private Coordinator(int port, int max, ArrayList<String> options) throws IOException {
        this.MAX_PARTICIPANTS = max;
        this.listener = new ServerSocket(port);

        StringBuilder voteOptions = new StringBuilder("VOTE_OPTIONS");
        for(String option : options) {
            voteOptions.append(" ").append(option);
        }
        optionsString = voteOptions.toString();
    }

    /**
     * Starts the coordinator loop
     * Sends values to participants
     * Collects the values and outputs the result if not a tie
     * Restarts if tie
     * @throws IOException
     */
    private void startListening() throws IOException {

        boolean initial = true;

        while(true) {

            resultCount = 0;
            participantsCount = 0;
            result = new HashMap<>();
            tiedOptions = new ArrayList<>();

            while(participantsCount + failedParticipants.size() < MAX_PARTICIPANTS) {
                if(initial) {
                    Socket part = listener.accept();
                    new ServerThread(part).start();
                    updateParticipantsCount();
                }
            }

            winner = "";

            if(initial) {
                System.out.println("Coordinator: [INFO] Max participants reached");
                initial = false;
            }

            // wait for all results to get back
            while(resultCount + failedParticipants.size() < MAX_PARTICIPANTS) {}

            // Remove bad votes
            Map.Entry<String,OutcomeToken> temp = null;
            ArrayList<Map.Entry<String,OutcomeToken>> badVotes = new ArrayList<>();

            for(Map.Entry<String,OutcomeToken> e:result.entrySet()) {
                if(temp == null) temp = e;
                else if(temp.getValue().participants.split(" ").length < e.getValue().participants.split(" ").length) {
                    badVotes.add(temp);
                    temp = e;
                }
            }

            result.entrySet().removeAll(badVotes);

            // check remaining votes to see if all votes are the same
            // if not re-vote
            Map.Entry<String,OutcomeToken> tempWinner = null;
            boolean revote = false;

            for(Map.Entry<String,OutcomeToken> vote:result.entrySet()){
                if(tempWinner == null) tempWinner = vote;
                else if(!tempWinner.getValue().outcome.equals(vote.getValue().outcome)) revote = true;

                // update tied options
                if(!vote.getValue().tiedOptions.equals("")) tiedOptions.add(vote.getValue().tiedOptions);
            }

            if(!tempWinner.getValue().outcome.equals("TIE") && !revote) {
                winner = tempWinner.getValue().outcome;
                System.out.println(String.format("\n\nCoordinator: [INFO] VOTED OUTCOME: %s\n\n",winner));
                return;
            } else if(tempWinner.getValue().outcome.equals("TIE")) {

                // Update options from tie
                String tempOptions = "";
                for(String s:tiedOptions) {
                    if(tempOptions.equals(""))
                        tempOptions = s;
                    else if(!tempOptions.equals(s))
                        System.out.println(String.format("Coordinator: [ERROR] Not the same tied options\n\t%s\n\t%s\n\tUsing outcome that involves the most participants",tempOptions,s));
                }
                optionsString = "VOTE_OPTIONS " + tempOptions;
            }

            winner = tempWinner.getValue().outcome;
        }
    }

    /**
     * Synchronized method to add socket and port to socketPort
     * @param socket    socket of the participant you want to add
     * @param port      port of the participant
     */
    synchronized private void updateSocketPortMap(Socket socket, String port) {

        socketPortsMap.put(socket,port);

    }

    /**
     * Synchronized method to update results received
     * @param port port of participant
     * @param token outcome token of the participant
     */
    synchronized private void updateResults(String port, OutcomeToken token){
        result.put(port, token);
    }

    /**
     * Adds one to resultCount
     */
    synchronized private void updateResultCount(){
        resultCount++;
    }

    /**
     * Adds one to participantsCount
     */
    synchronized private void updateParticipantsCount() {
        participantsCount++;
    }

    synchronized private void updateFailedParticipants(String port){
        failedParticipants.add(port);
        for(Map.Entry<Socket,String> e:socketPortsMap.entrySet()) {
            if(e.getValue().equals(port)) {
                socketPortsMap.remove(e.getKey());
                return;
            }
        }
    }

    /**
     * Thread to communicate with each participant
     */
    private class ServerThread extends Thread {

        private volatile BufferedReader partIn;
        private PrintWriter partOut;
        private String port;

        /**
         * Constructor of the thread
         * @param part The socket of the participant this thread is linked to
         * @throws IOException
         */
        ServerThread(Socket part) throws IOException {

            this.partIn = new BufferedReader( new InputStreamReader(part.getInputStream()));
            this.partOut = new PrintWriter( new OutputStreamWriter(part.getOutputStream()));

            // get join message
            Token token = new CoordinatorTokenizer().getToken(partIn.readLine());
            if(token instanceof JoinToken) port = ((JoinToken) token).port;


            updateSocketPortMap(part,port);

            System.out.println(String.format("Coordinator: [INFO] received '%s' from %s",token.request,port));

        }

        /**
         * Main communicator with the participant
         */
        public void run() {

            try {

                // send details to participants
                while(true) {

                    while(participantsCount + failedParticipants.size() < MAX_PARTICIPANTS) {}

                    StringBuilder detailsString = new StringBuilder("DETAILS");

                    for (Map.Entry<Socket, String> e : socketPortsMap.entrySet()) {
                        if (!e.getValue().equals(port)){
                            detailsString.append(" ").append(e.getValue());
                        }
                    }

                    partOut.println(detailsString);
                    partOut.flush();

                    System.out.println(String.format("Coordinator: [INFO] sent '%s' to %s", detailsString, port));

                    // send vote options to participants
                    partOut.println(optionsString);
                    partOut.flush();

                    System.out.println(String.format("Coordinator: [INFO] sent '%s' to %s", optionsString, port));

                    String outcomeLine = partIn.readLine();
                    if(outcomeLine == null) {
                        System.out.println(String.format("Coordinator: [ERROR] Could not get reply from %s",port));
                        updateFailedParticipants(port);
                        return;
                    }
                    Token token = new CoordinatorTokenizer().getToken(outcomeLine);

                    System.out.println(String.format("Coordinator: [INFO] received '%s' from %s", token.request, port));

                    if (token instanceof OutcomeToken) {
                        updateResults(port, (OutcomeToken) token);
                        updateResultCount();
                    }

                    while(winner.equals("")) {}

                    if(!winner.equals("TIE")) break;
                    else {
                        partOut.println("RESTART");
                        partOut.flush();
                        System.out.println(String.format("Coordinator: [INFO] Sent 'RESTART' to %s",port));
                        updateParticipantsCount();
                    }
                }

                // Send finish message to part
                partOut.println("FINISH");
                partOut.flush();
                System.out.println(String.format("Coordinator: [INFO] Sent 'FINISH' to %s",port));
            } catch(SocketTimeoutException e) {
                System.out.println(String.format("Coordinator: [ERROR] Could not get reply from %s",port));
                updateResultCount();
            } catch(IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * Tokenizer class to parse the received messages
     */
    private class CoordinatorTokenizer {

        Token getToken(String request) {

            StringTokenizer sTok = new StringTokenizer(request);
            if(!sTok.hasMoreTokens()) return null;

            String firstToken = sTok.nextToken();
            if(firstToken.equals("JOIN")) return new JoinToken(request,sTok.nextToken());
            else if(firstToken.equals("OUTCOME")) {

                String winner = sTok.nextToken();
                String tied = "";

                if(winner.contains("TIE")) {
                    tied = winner.split("TIE_")[1].replace("_"," ");
                    winner = "TIE";
                }

                StringBuilder participants = new StringBuilder(sTok.nextToken());

                while(sTok.hasMoreTokens()){
                    participants.append(" ").append(sTok.nextToken());
                }
                return new OutcomeToken(request, winner, tied, participants.toString());
            }
            return null;
        }

    }

    abstract class Token {
        String request;
    }

    class JoinToken extends Token {

        String port;

        JoinToken(String request, String port) {
            this.request = request;
            this.port = port;
        }

    }

    class OutcomeToken extends Token {

        String outcome;
        String participants;
        String tiedOptions;

        OutcomeToken(String request, String outcome, String tiedOptions, String participants) {
            this.request = request;
            this.outcome = outcome;
            this.participants = participants;
            this.tiedOptions = tiedOptions;
        }

    }
}
