import java.io.*;
import java.net.*;
import java.util.*;

//Participant <Coord port> <Self port> <Timeout> <Flag>
public class Participant {

    private ServerSocket listener;
    private int timeOut, flag;
    private PrintWriter out;
    private BufferedReader in;
    private volatile String[] participants;                     // Participants received from coordinator
    private int ownPort;                                        // Port of this participant
    private volatile Set<String> participantsReceived;          // Set containing participants that has sent this participant a vote
    private volatile Map<String,Integer> votesCount;            // Map containing option and its count
    private volatile ArrayList<Socket> participantSockets;      // ArrayList containing all the participants' sockets
    private String option;                                      // Option this participant has chosen
    private volatile StringBuilder voteMessage;
    private Socket coordinatorSocket;
    private volatile ArrayList<String> failedParticipants;

    public static void main(String[] args) {

        Participant p = new Participant(Integer.parseInt(args[0]),
                Integer.parseInt(args[1]),
                Integer.parseInt(args[2]),
                Integer.parseInt(args[3]));

        // send join message to coordinator
        PrintWriter out = p.out;
        String message = String.format("JOIN %s",args[1]);
        out.println(message);
        out.flush();

        System.out.println(String.format("Participant %s: [INFO] sent %s to coordinator",p.ownPort,message));

        p.startListening();

    }

    /**
     * Constructor for participant
     * @param coordinatorPort   Port of the coordinator
     * @param ownPort           Port of this participant
     * @param timeOut           Timeout for ServerSocket
     * @param flag              Error flag
     */
    private Participant(int coordinatorPort, int ownPort, int timeOut, int flag) {

        try {
            Socket coordinatorSocket = new Socket(InetAddress.getLocalHost(), coordinatorPort);
            this.timeOut = timeOut;
            this.flag = flag;
            this.coordinatorSocket = coordinatorSocket;
            this.out = new PrintWriter(new OutputStreamWriter(coordinatorSocket.getOutputStream()));
            this.in = new BufferedReader(new InputStreamReader(coordinatorSocket.getInputStream()));
            this.ownPort = ownPort;
        } catch(UnknownHostException e) {
            System.out.println(String.format("Participant %s: [ERROR] Unknown host",ownPort));
            e.printStackTrace();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts the participant loop
     * Gets values from coordinator
     * Vote with other participants
     * Share answer with coordinator
     */
    private void startListening() {

        try {

            while(true) {

                // Reset the values for a new loop
                participants = new String[0];
                String[] options = new String[0];
                participantsReceived = new HashSet<>();
                votesCount = new HashMap<>();
                participantSockets = new ArrayList<>();
                listener = new ServerSocket(ownPort);
                voteMessage = new StringBuilder("VOTE");
                failedParticipants = new ArrayList<>();

                // get details and options from coordinator
                while (participants.length == 0 || options.length == 0) {

                    Token token = new ParticipantTokenizer().getToken(in.readLine());
                    if (token instanceof DetailsToken) {
                        String detailsString = ((DetailsToken) token).details;
                        participants = detailsString.split(" ");

                        System.out.println(String.format("Participant %s: [INFO] Received '%s' from coordinator",ownPort,token.request));

                    } else if (token instanceof OptionsToken) {
                        String optionsString = ((OptionsToken) token).voteOptions;
                        options = optionsString.split(" ");

                        System.out.println(String.format("Participant %s: [INFO] Received '%s' from coordinator",ownPort,token.request));
                    }
                }

                listener.setSoTimeout(timeOut);

                option = options[new Random().nextInt(options.length)];
                updateVotes(option);

                addVoteMessage(String.valueOf(ownPort),option);

                String message = voteMessage.toString();        // variable to hold vote message for this round

                new ServerThread().start();

                // communicate with other participants
                int count = 0;
                int limit = 0;
                if(flag == 1 && participants.length > 1)
                    limit = (new Random().nextInt(participants.length-1)) + 1;
                for (String p : participants) {
                    // kill participant from flag 1
                    if(flag == 1 && count == limit) {
                        return;
                    }
                    new SendingThread(p,message).start();
                    count++;
                }

                // wait for all the votes to be received
                while (participantsReceived.size() + failedParticipants.size()< participants.length) { }

                if(failedParticipants.size() > 0) {
                    String fails = createParticipantsString(failedParticipants.iterator());
                    System.out.println(String.format("Participant %s: [ERROR] Failed to receive votes from %s",ownPort,fails));
                }

                String winner = getWinner();

                participantsReceived.add(String.valueOf(ownPort));
                String participantsReceivedString = createParticipantsString(participantsReceived.iterator());

                // Kill participant from flag 2
                if(flag == 2) {
                    coordinatorSocket.close();
                    return;
                }

                // send winner to coordinator
                String outcomeString = String.format("OUTCOME %s %s", winner, participantsReceivedString);
                out.println(outcomeString);
                out.flush();

                System.out.println(String.format("Participant %s: [INFO] Sent '%s' to coordinator",ownPort,outcomeString));

                Token token = new ParticipantTokenizer().getToken(in.readLine());
                System.out.println(String.format("Participant %s: [INFO] Received '%s' from coordinator",ownPort,token.request));
                if(token instanceof FinishToken) break;
                else if(!(token instanceof RestartToken)) System.out.println(String.format("Participant (%s): [ERROR] Unknown token",ownPort));

            }
        }catch(IOException e) {
            e.printStackTrace();
        }

        System.exit(0);
    }

    private String createParticipantsString(Iterator<String> it) {

        StringBuilder s = new StringBuilder(it.next());
        while(it.hasNext()) {
            s.append(" ").append(it.next());
        }
        return s.toString();

    }

    /**
     * Calculates the winner of the round after all the votes have been handed in
     * @return returns the winner calculated
     */
    private String getWinner() {
        Map.Entry<String, Integer> maxCount = null;
        String winner = "";
        StringBuilder tiedOptions = new StringBuilder();

        // get winner from results
        for (Map.Entry<String, Integer> vote : votesCount.entrySet()) {
            if (maxCount == null || vote.getValue() > maxCount.getValue()) {
                maxCount = vote;
                winner = vote.getKey();
                tiedOptions = new StringBuilder(vote.getKey());
            } else if(vote.getValue().equals(maxCount.getValue())) {
                winner = "TIE";
                tiedOptions.append("_").append(vote.getKey());
            }
        }

        if(winner.equals("TIE")) winner += "_" + tiedOptions.toString();

        return winner;
    }

    /**
     * Synchronized method to update the vote count
     * @param vote the option that is being updated
     */
    synchronized private void updateVotes(String vote) {

        if(votesCount.containsKey(vote)) votesCount.put(vote,votesCount.get(vote)+1);
        else votesCount.put(vote,1);

    }

    /**
     * Synchronized method to update the participants received
     * @param part  port of the participant
     */
    synchronized private void updateParticipantsReceived(String part) {

        participantsReceived.add(part);

    }

    synchronized private void addVoteMessage(String port, String option) {
        voteMessage.append(" ").append(port).append(" ").append(option);
    }

    /**
     * Thread for sending the option this participant has chosen to the other participants
     */
    private class SendingThread extends Thread {

        private Socket socket;
        private PrintWriter out;
        private String destinationPort;
        private String message;

        /**
         * Constructor this thread
         * @param destinationPort   the participant's port this thread is meant to send the vote to
         * @throws IOException
         */
        private SendingThread(String destinationPort, String message) throws IOException {

            this.socket = new Socket(InetAddress.getLocalHost(), Integer.parseInt(destinationPort));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.destinationPort = destinationPort;
            this.message = message;

        }

        /**
         * Sends the vote to the participant
         */
        public void run() {

            // choose option
            out.println(message);
            out.flush();

            System.out.println(String.format("Participant %s: [INFO] Sent '%s' to %s",ownPort,message,destinationPort));

        }

    }

    /**
     * Thread for receiving votes from the other participants
     */
    private class ReceivingThread extends Thread {

        private Socket threadSocket;
        private BufferedReader in;

        /**
         * Constructor for the thread
         * @param socket        Socket from the sender participant
         * @throws IOException
         */
        private ReceivingThread(Socket socket) throws IOException {

            this.threadSocket = socket;
            this.in = new BufferedReader(new InputStreamReader(threadSocket.getInputStream()));

        }

        /**
         * parses the message received and stores the result
         */
        public void run() {

            try {
                Token token = new ParticipantTokenizer().getToken(in.readLine());

                if (token instanceof VoteToken) {
                    String part = ((VoteToken) token).port;
                    String option = ((VoteToken) token).option;
                    updateVotes(option);
                    updateParticipantsReceived(part);
                    addVoteMessage(part,option);        // Adds received votes into a string builder
                    System.out.println(String.format("Participant %s: [INFO] Received '%s' from %s",ownPort,token.request,part));
                }
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Collects all the sockets from the server socket and launches a ReceivingThread for each socket
     */
    private class ServerThread extends Thread {

        public void run(){

            int count = 0;

            try {
                while(participantSockets.size() < participants.length) {
                    Socket socket = listener.accept();
                    participantSockets.add(socket);
                    new ReceivingThread(socket).start();
                    count++;
                }
                listener.close();
            } catch(SocketTimeoutException e){

                // wait for all sockets to receive
                while(count > participantsReceived.size()) {}

                // add the participants which did not send this participant a reply
                for(String p:participants){
                    boolean included = false;
                    for(String q:participantsReceived){
                        if(p.equals(q)) included = true;
                    }
                    if(!included) failedParticipants.add(p);
                }

                try {
                    listener.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }

            } catch(IOException e) {
                e.printStackTrace();
            }

        }

    }

    /**
     * Tokenizer to parse messages received
     */
    private class ParticipantTokenizer {

        Token getToken(String request) {

            StringTokenizer sTok = new StringTokenizer(request);
            if(!sTok.hasMoreTokens()) return null;
            String firstToken = sTok.nextToken();
            switch (firstToken){
                case("DETAILS"):
                    StringBuilder details = new StringBuilder(sTok.nextToken());
                    while(sTok.hasMoreTokens()) {
                        details.append(" ").append(sTok.nextToken());
                    }
                    return new DetailsToken(request,details.toString());
                case("VOTE_OPTIONS"):
                    StringBuilder options = new StringBuilder(sTok.nextToken());
                    while(sTok.hasMoreTokens()) {
                        options.append(" ").append(sTok.nextToken());
                    }
                    return new OptionsToken(request, options.toString());
                case("VOTE"):
                    String[] votes = request.split(" ");
                    String port = votes[votes.length-2];
                    String option = votes[votes.length-1];
                    return new VoteToken(request, port, option);
                case("FINISH"):
                    return new FinishToken(request);
                case("RESTART"):
                    return new RestartToken(request);
            }
            return null;

        }

    }

    abstract class Token {
        String request;
    }

    private class DetailsToken extends Token {

        String details;

        DetailsToken(String request, String details) {
            this.request = request;
            this.details = details;
        }

    }

    private class OptionsToken extends Token {

        String voteOptions;

        OptionsToken(String request, String voteOptions) {
            this.request = request;
            this.voteOptions = voteOptions;
        }

    }

    private class VoteToken extends Token {

        String port;
        String option;

        VoteToken(String request, String port, String option) {
            this.request = request;
            this.port = port;
            this.option = option;
        }
    }

    private class FinishToken extends Token {

        FinishToken(String request) {
            this.request = request;
        }
    }

    private class RestartToken extends Token {

        RestartToken(String request) {
            this.request = request;
        }

    }

}
