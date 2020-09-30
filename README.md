# Concensus-Algorithm
## Introduction
This program is an implementation of a consensus protocol that tolerates participant
failures. The protocol involves two types of processes: a coordinator, whose role is to initiate a run of
the consensus algorithm and collect the outcome of the vote; and a participant, which contributes a
vote and communicates with the other participants to agree on an outcome. The application can
consist of 1 coordinator process and N participant processes, out of which at most 1 participant may
fail during the run of the consensus algorithm. The actual consensus algorithm is ran among the
participant processes, with the coordinator only collecting outcomes from the participants.

## Instructions
* The Coordinator class file can be executed at the Unix/Linux/DOS command line as follows:

    `java Coordinator <port> <parts> [<option>]`

    Where \<port\> is the port number that the coordinator is listening on, and \<parts\> is the
number of participants that the coordinator expects, and [\<option\>] is a set (no duplicates) of
options separated by spaces. For example, if we want to start the coordinator that is expecting
4 participants listening on port 12345 where the options are A, B and C, then it will be
executed as:

    `java Coordinator 12345 4 A B C`

* The Participant class file can be executed at the Unix/Linux/DOS command line as follows:

    `java Participant <cport> <pport> <timeout> <failurecond>`

    Where \<cport\> is the port number that the coordinator is listening on, \<pport\> is the port
number that this participant will be listening on, \<timeout\> is a timeout in milliseconds and
\<failurecond\> is a flag indicating whether and at what stage the participant fails.
The values of \<failurecond\> are:

    * **0** The participant does not fail;
    
    * **1** The participant fails during step 4 (i.e. after it has shared its vote with some but not all other
    participants); and
    
    * **2** The participant fails after step 4 and before the end of step 5.
    For example, if we want to start a participant that does not fail, that operates with a timeout of
    5000 milliseconds (5 seconds) and that is listening on port 12346 with the coordinator listening
    on port 12345 as above, this will be executed as:
    
        `java Participant 12345 12346 5000 0`


## Protocol for Participant
1. Register with coordinator - The participant establishes a TCP connection with the coordinator
and sends the following byte stream:

    `JOIN <port>`

    Where \<port\> is the port number that this participant is listening on. This will be treated as the identifier of the participant. For example, the participant listening on port 12346 will send the message: 
    
    `JOIN 12346`

2. Get details of other participants from coordinator. The participant should wait to receive a message from the coordinator with the details of all other participants (i.e. read from the same socket connection):
    
    `DETAILS [<port>]`

    Where [\<port\>] is a list of the port numbers (aka. identifiers) of all other particpants. (Note that we do not want a participant sending its vote to itself.) For example, participant with identifier/port 12346 may receive information about two other participants:

    `DETAILS 12347 12348 1`
    
3. Get vote options from coordinator. The participant should wait again to receive a message from the coordinator with the details of the options for voting:

    `VOTE_OPTIONS [<option>]`
    
    Where [\<option\>] is the list of voting options for the consensus protocol. For example, there may be two options, A and B:
    
    `VOTE_OPTIONS A B`

4. Execute a number of rounds by exchanging messages directly with the other participants. Round 1 The participant will send and receive messages of the following structure in the first
round:

    `VOTE <port> <vote>`
    
    Where \<port\> is the sender’s port number/identifier, and hvotei is one of the vote options (i.e. that agent’s vote).
For example, if we have 3 participants listening on ports 12346, 12347 and 12348, and
their votes are A, B and A respectively, and there are no failures, then the messages
passed between participants will be:
    * 12346 to 12347: VOTE 12346 A
    * 12346 to 12348: VOTE 12346 A
    * 12347 to 12346: VOTE 12347 B
    * 12347 to 12348: VOTE 12347 B
    * 12348 to 12346: VOTE 12348 A
    * 12348 to 12347: VOTE 12348 A
    
    Round n > 1 The participant will send and receive messages of the following structure in all
subsequent rounds:

    `VOTE <port 1> <vote 1> <port 2> <vote 2> ...<port n> <vote n>`
    
    Where \<port i\> and \<vote i\> are the port (identifier) and vote of any new votes
received in the previous round.
5. Decide vote outcome using majority (null if no majority)
6. Inform coordinator of the outcome. The following message should be sent to the coordinator on
the same connection established during the intial stage:

    `OUTCOME <outcome> [<port>]`

    Where \<outcome\> is the option that this participant has decided is the outcome of the vote, and
\[<port\>] is the list of participants that were taken into account in settling the vote. For
example, participant 12346 in the above example should send this message to the coordinator:

    `OUTCOME A 12346 12347 12348`

    In other words, agent 12346 has taken into account its own vote and those of 12347 and 12348
and come to the conclusion that A is the outcome by majority vote.
## Protocol for the Coordinator
1. Wait for the number of participants specified join. The number of participants should be given
as a parameter to the main method of the coordinator (see below).
   
    `JOIN <port>`
    
    Where \<port\> is the port number of the participant. If not all participants join, then the
coordinator should abort.

2. Send participant details to each participant once all participants have joined:
    
    `DETAILS [<port>]`
    
    Where [\<port\>] is a list of the port numbers (aka. identifiers) of all other particpants.

3. Send request for votes to all participants:
    
    `VOTE_OPTIONS [<option>]`

    Where [\<option\>] is the list of voting options for the consensus protocol. The voting options
should be given as a parameter to the main method of the coordinator (see below).

4. Receive votes from participants:

    `OUTCOME <outcome> [<port>]`

    Where \<outcome\> is the option that this participant has decided is the outcome of the vote, and
[\<port\>] is the list of participants that were taken into account in settling the vote.

5. Send **FINISH** or **RESTART** message to participants based on the voted outcomes.
    
    **FINISH** tells the participants that their vote has been received, and the votes do not cause conflict or errors (e.g. ties, different outcomes) they can
    now shut down.

    **RESTART** tells the participant to get ready for another round of
    voting. After this has been sent, a new round of voting starts with the coordinator sending out
    the round details.