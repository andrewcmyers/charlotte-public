package com.xinwenwang.hetcons;

import com.isaacsheff.charlotte.node.CharlotteNodeService;
import com.isaacsheff.charlotte.node.HashUtil;
import com.isaacsheff.charlotte.node.SignatureUtil;
import com.isaacsheff.charlotte.proto.*;
import io.netty.util.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class HetconsObserverStatus {

    /*for which this observer */
    private HetconsObserver observer;

    /* map from proposal's consensus id to HetconsProposalStatus object. If a set of proposals are conflicting, then they should point to the same status */
    private HashMap<String, HetconsProposalStatus> proposalStatus;

    /* map from a chain slot to a status object which is shared by all proposals related to that slot. */
    private HashMap<String, HetconsSlotStatus> slotStatus;

    /* The service used to send and receive blocks */
    private HetconsParticipantService service;

    /* The number of decided proposals for logging purposes */
    private Integer numOfDecidedProposals = 0;

    /* this stores the quorum configuration for chains, the key is the name of the chain */
    private Map<String, HetconsQuorumStatus> quorums;

    /* Observer's name */
    private String name;

    /* 1b and 2b Blocks waiting for 1a */
    private Map<String,Deque<Block>> waitingBlockQueue;

    private static final Logger logger = Logger.getLogger(CharlotteNodeService.class.getName());


    public HetconsObserverStatus(HetconsObserver observer, HetconsParticipantService service, String name) {
        this.name = name;
        this.observer = observer;
        this.service = service;
        proposalStatus = new HashMap<>();
        slotStatus = new HashMap<>();
        quorums = new HashMap<>();
        waitingBlockQueue = new ConcurrentHashMap<>();
    }


    public boolean receive1a(Block block,
                             long timeout,
                             List<HetconsObserverQuorum> observerQuorums,
                             String chainName) {

        HetconsMessage1a m1a = block.getHetconsBlock().getHetconsMessage().getM1A();
        List<CryptoId> obs = service.getBlock(block.getHetconsBlock().getHetconsMessage().getObserverGroupReferecne()).getHetconsBlock().getHetconsMessage().getObserverGroup().getObserversList().stream().map(o -> o.getId()).collect(Collectors.toList());

        HetconsProposal proposal = m1a.getProposal();

        for (IntegrityAttestation.ChainSlot chainSlot : proposal.getSlotsList()) {
            for (CryptoId ob : obs) {
                if (service.hasAttestation(chainSlot, ob))
                    return false;
            }
        }

        String proposalStatusID = HetconsUtil.buildConsensusId(proposal.getSlotsList());

        ArrayList<String> chainIDs = new ArrayList<>();

        for (IntegrityAttestation.ChainSlot slot : proposal.getSlotsList()) {
            chainIDs.add(HetconsUtil.buildChainSlotID(slot));
        }

        if (proposalStatus.containsKey(proposalStatusID)) {
            if (!(proposal.getBallot().getBallotSequence().compareTo(
                    proposalStatus.get(proposalStatusID).getCurrentProposal().getBallot().getBallotSequence()) > 0)) {
                System.err.println(name+":Receive Restart but haven't pass ballot test on "+ proposalStatusID);
                return false;
            }
            System.err.println(name+":Receive Restart on "+ proposalStatusID);
        }

        quorums.putIfAbsent(chainName, new HetconsQuorumStatus(observerQuorums, chainName));

        HetconsProposalStatus incomingStatus = new HetconsProposalStatus(HetconsConsensusStage.Proposed,
                proposal,
                quorums.get(chainName),
                block.getHetconsBlock().getHetconsMessage().getObserverGroupReferecne(),
                quorums,
                service);
        incomingStatus.setChainIDs(chainIDs);
        HetconsProposalStatus currentStatus;
        synchronized (proposalStatus) {
            boolean hasPrev = null != proposalStatus.putIfAbsent(proposalStatusID, incomingStatus);
            currentStatus = proposalStatus.get(proposalStatusID);
            if (!hasPrev) {
                currentStatus.setProposer(timeout != 0);
                currentStatus.setConsensuTimeout(timeout);
                if (currentStatus.getProposer())
                    logger.info("I am the proposer for " + proposalStatusID);
            }
        }

        HetconsProposal freshProposal = incomingStatus.getCurrentProposal();


//        if (incomingStatus != currentStatus) {
//            /* proposal with larger ballot number should be saved and use that number in the future */
//            if (!(incomingStatus.getCurrentProposal().getBallot().getBallotSequence().compareTo(
//                    currentStatus.getCurrentProposal().getBallot().getBallotSequence()) >= 0)) {
//                System.err.println("Receive Restart but haven't pass ballot test 2 on "+ proposalStatusID);
//                return false;
//            }
//        }


        synchronized (currentStatus.getGeneralLock()) {
            /* we still want this round of proposal proceed because it might come from other observer who has not received enough 2b to decide */
            if (currentStatus.getHasDecided())
                logger.info("Duplicated Request: Slot " + proposal.getSlotsList() + " has been decided\nvalue is ");
        }

        List<HetconsSlotStatus> slotStatuses = new ArrayList<>();
        synchronized (slotStatus) {
            // Init status objects for chain ids
            for (String slot : chainIDs) {
                slotStatus.putIfAbsent(slot, new HetconsSlotStatus(slot));
                HetconsSlotStatus status = slotStatus.get(slot);
                slotStatuses.add(status);
            }
        }


        Block freshBlock = block;
        synchronized (slotStatus) {

            /* See if we already have 2as from another independent proposals */
            // FIXME: update ballot number instead of discarding the proposal
            for (HetconsSlotStatus status : slotStatuses) {
//                synchronized (status) {
                String m2aId = status.has2aFromOtherProposal(proposalStatusID, this);
                    if (m2aId != null && !proposalStatusID.equals(m2aId)) {
                        System.err.println(name+":"+status.getSlot()+":Receive Restart but already have 2a from other proposal on " + m2aId +" instead of "+ proposalStatusID);
                        service.storeNewBlock(freshBlock);
                        return true;
//                        status.updateBallot(proposal.getBallot());
//                        currentStatus =  proposalStatus.get(status.getActiveProposal());
//                        freshProposal = HetconsProposal.newBuilder(currentStatus.getCurrentProposal())
//                                .setValue(currentStatus.getCurrentProposal().getValue())
//                                .setBallot(proposal.getBallot()).build();
//                        proposalStatusID = status.getActiveProposal();
//                        HetconsMessage1a _m1a = HetconsMessage1a.newBuilder(m1a).setProposal(freshProposal).build();
//                        HetconsMessage _message = HetconsMessage.newBuilder(block.getHetconsBlock().getHetconsMessage()).setM1A(_m1a)
//                                .setIdentity(service.getConfig().getCryptoId())
//                                .setObserverGroupReferecne(currentStatus.getObserverGroupReference())
//                                .build();
//                        HetconsBlock _hetblock = HetconsBlock.newBuilder(block.getHetconsBlock()).setHetconsMessage(_message)
//                                .setSig(SignatureUtil.signBytes(service.getConfig().getKeyPair(), _message))
//                                .build();
//                        freshBlock = Block.newBuilder(block).setHetconsBlock(_hetblock).build();
//                        break;
//                        return false;
                    }
//                }
            }

        // See if all slots have smaller ballot number
            for (HetconsSlotStatus status : slotStatuses) {
//                synchronized (status) {
                    if (status.hasLargerBallot(proposal.getBallot())) {
                        System.err.println("Receive Restart for proposal "+proposalStatusID+" but slot "+ status.getSlot() + " has larger ballot number!");
                        return false;
                    }
//                }
            }

            // Update ballot number for all slots
            for (HetconsSlotStatus status : slotStatuses) {
//                synchronized (status) {
                    status.updateBallot(proposal.getBallot());
//                }
            }
        }

        currentStatus.updateProposal(freshProposal);

        // Save valid block
        // Echo 1a to all participants
        service.storeNewBlock(freshBlock);
//        service.storeNewBlock(block);
        broadcastToParticipants(freshBlock, currentStatus.getParticipants());


//        logger.info(name + ": Echo 1as value is " + proposal.getValue());
//        System.err.println(name + ": Echo 1as value is " + proposal.getValue());

        // Send 1b
        // FIXME: use reference
        Reference m1aRef = Reference.newBuilder().setHash(
                HashUtil.sha3Hash(freshBlock)
        ).build();

        HetconsMessage1b m1b = prepareM1b(m1a, m1aRef, proposalStatusID);

        if (m1b == null)
            return false;

        HetconsMessage m = HetconsMessage.newBuilder()
                .setType(HetconsMessageType.M1b)
                .setM1B(m1b)
                .setObserverGroupReferecne(freshBlock.getHetconsBlock().getHetconsMessage().getObserverGroupReferecne())
                .setIdentity(service.getConfig().getCryptoId())
                .build();

        HetconsBlock b = HetconsBlock.newBuilder()
                .setHetconsMessage(m)
                .build();

        broadcastToParticipants(Block.newBuilder().setHetconsBlock(b).build(), currentStatus.getParticipants());
        currentStatus.setStage(HetconsConsensusStage.M1BSent);

//        logger.info("Sent 1Bs value is " + HetconsUtil.get1bValue(m1b, service) + " " + proposalStatusID);
//        logger.info("ballot is " + proposal.getBallot().getBallotSequence());
//        System.err.println(name + ": Echo 1b") ;

        String _proposalID = proposalStatusID;

        /* Consume all waiting blocks here, not decided yet whether this should be submitted to other threads */
        service.getExecutorService().submit(() -> {
            while(!waitingBlockQueue.get(_proposalID).isEmpty()) {
                Block waitingBlock = waitingBlockQueue.get(_proposalID).pollFirst();
                if (waitingBlock == null)
                    continue;
                if (waitingBlock.getHetconsBlock().getHetconsMessage().getType() == HetconsMessageType.M1b)
                    receive1b(waitingBlock);
                else
                    receive2b(waitingBlock);
            }
        });

        if (!currentStatus.getProposer())
            return true;

        setupTimerForRestart(currentStatus, proposalStatusID, null, 0);
        return true;
    }


    public void receive1b(Block block) {
        HetconsMessage1a message1a = getM1aFromReference(block.getHetconsBlock().getHetconsMessage().getM1B().getM1ARef());
        if (message1a == null) {
            return;
        }
        HetconsProposal proposal = message1a.getProposal();
        String proposalID = HetconsUtil.buildConsensusId(proposal.getSlotsList());
        HetconsProposalStatus status = proposalStatus.get(proposalID);

        if (status == null) {
            waitingBlockQueue.putIfAbsent(proposalID, new ConcurrentLinkedDeque<>());
            /* 1bs have higher priority than 2bs, so 1bs always put in the front of the waiting queue */
            waitingBlockQueue.get(proposalID).addFirst(block);
            logger.info("No proposal status available for proposalID: "+proposalID);
            return;
        }

        if (status.getCurrentProposal().getBallot().getBallotSequence().compareTo(
                proposal.getBallot().getBallotSequence()
        ) > 0) {
            logger.info(name + ":" + "M1B discard due to lower ballot for value "+HetconsUtil.get1bValue(block.getHetconsBlock().getHetconsMessage().getM1B(), service));
            return;
        }
//        service.storeNewBlock(block);
        Reference refm1b = Reference.newBuilder().setHash(HashUtil.sha3Hash(block)).build();
        List<Reference> q = status.receive1b(block.getHetconsBlock().getHetconsMessage().getIdentity(), refm1b);

        logger.info(name + ":" + "M1B value is " + HetconsUtil.get1bValue(block.getHetconsBlock().getHetconsMessage().getM1B(), service));

        status.updateRecent1b(block.getHetconsBlock().getHetconsMessage().getM1B().hasM2A(), HetconsUtil.get1bValue(block.getHetconsBlock().getHetconsMessage().getM1B(), service), message1a.getProposal().getBallot());
        if (q == null) {
            logger.info("M1B: No quorum is satisfied");
            return;
        }

        Collections.sort(q, Comparator.comparing(e -> e.getHash().getSha3().toStringUtf8()));

        HetconsBallot ballot = null;
        HetconsValue value = null;
        HetconsMessage1a m1a = null;
        Reference m1aRef = null;

        // CHeck if the return quorum is valid: same value, same ballot number
        for (Reference r: q) {
            Block b1b = service.getBlock(r);
            HetconsMessage1b m1b = b1b.getHetconsBlock().getHetconsMessage().getM1B();
            m1a = getM1aFromReference(m1b.getM1ARef());
            m1aRef = m1b.getM1ARef();
            if (ballot == null && value == null) {
                ballot = m1a.getProposal().getBallot();
                value = get1bValue(m1b);
            } else {
                if (HetconsUtil.ballotCompare(
                        m1a.getProposal().getBallot(),
                        ballot) != 0 || !value.equals(get1bValue(m1b))) {
                    logger.info("Inconsistent value or ballot number");
                    return;
                }
            }
        }

        logger.info(name + ": found a quorum");

        // Save 2a to slot

        HetconsQuorumRefs refs = HetconsQuorumRefs.newBuilder().addAllBlockHashes(q).build();
        HetconsMessage2ab m2a = HetconsMessage2ab.newBuilder()
                .setM1ARef(m1aRef)
                .setQuorumOf1Bs(refs)
                .build();


        int decidedCount = 0;
        for (String slot: status.getChainIDs()) {
            HetconsSlotStatus sstatus = this.slotStatus.get(slot);
            synchronized (sstatus) {
                if (sstatus.isDecided() && !sstatus.getActiveProposal().equals(proposalID))
                    return;
                if (sstatus.isDecided())
                    decidedCount++;
            }
        }

        // If there is any decided slot but not all of them, then return
        if (decidedCount > 0 && decidedCount != status.getChainIDs().size())
            return;

        synchronized (slotStatus) {
            for (String slot: status.getChainIDs()) {
                HetconsSlotStatus sstatus = this.slotStatus.get(slot);
//                synchronized (sstatus) {
                    HetconsMessage2ab slot2a = sstatus.getM2a();
                    if (slot2a != null && HetconsUtil.ballotCompare(getM1aFromReference(slot2a.getM1ARef()).getProposal().getBallot(),
                            getM1aFromReference(m2a.getM1ARef()).getProposal().getBallot()) > 0) {
                        return;
                    }
//                }
            }

            for (String slot: status.getChainIDs()) {
                HetconsSlotStatus slotStatus = this.slotStatus.get(slot);
                // only do updates if the slot has not decided yet.
//                synchronized (slotStatus) {
                    if (!slotStatus.isDecided()) {
                        slotStatus.setM2a(m2a, this);
                        slotStatus.setActiveProposal(proposalID);
                    }
//                }
            }
        }


        logger.info(name + "Wrote to m2a");

        // broadcast 2a
        HetconsMessage m2b = HetconsMessage.newBuilder()
                .setType(HetconsMessageType.M2b)
                .setM2B(m2a)
                .setObserverGroupReferecne(block.getHetconsBlock().getHetconsMessage().getObserverGroupReferecne())
                .setIdentity(service.getConfig().getCryptoId())
                .build();
        HetconsBlock m2bBlock = HetconsBlock.newBuilder().setHetconsMessage(m2b)
//                .setSig(SignatureUtil.signBytes(service.getConfig().getKeyPair(), m2b))
                .build();

        broadcastToParticipants(Block.newBuilder().setHetconsBlock(m2bBlock).build(), status.getParticipants());
        status.setStage(HetconsConsensusStage.M2BSent);

//        status.setRoundStatusM2a(HetconsUtil.get2bValue(m2a, service));
        if (!status.getProposer())
            return;

        /** -------------------- Timer for Restart ----------------------------- */
        // set timer for 2b, if we didn't receive enough 1bs after the timeout, we restart the consensus.

        logger.info(name + ":" + "Sent M2B value is "+ HetconsUtil.get2bValue(m2a, service));
        setupTimerForRestart(status, proposalID, null, 1);
    }


    public void receive2b(Block block) {
        HetconsMessage1a message1a = getM1aFromReference(block.getHetconsBlock().getHetconsMessage().getM2B().getM1ARef());
        if (message1a == null)
            return;
        HetconsProposal proposal = message1a.getProposal();
        String proposalID = HetconsUtil.buildConsensusId(proposal.getSlotsList());
        HetconsProposalStatus status = proposalStatus.get(proposalID);

        if (status == null) {
            waitingBlockQueue.putIfAbsent(proposalID, new ConcurrentLinkedDeque<>());
            waitingBlockQueue.get(proposalID).add(block);
            return;
        }

        if (status.getCurrentProposal().getBallot().getBallotSequence().compareTo(
                proposal.getBallot().getBallotSequence()
        ) > 0) {
            logger.info(name + ":" + "M2B discard because of lower ballot number value is " + HetconsUtil.get2bValue(block.getHetconsBlock().getHetconsMessage().getM2B(), service));
            System.err.println(name+":Ballot too lower for 2b to proceed");
            return;
        }
//        if (status == null || status.getStage() == HetconsConsensusStage.ConsensusDecided)

        for (Reference reference : block.getHetconsBlock().getHetconsMessage().getM2B().getQuorumOf1Bs().getBlockHashesList()) {
            if (service.getBlockMap().get(reference.getHash()) == null) {
                return;
            }
        }

        if (!status.verify2b(block.getHetconsBlock().getHetconsMessage().getM2B()))
            return;

        logger.info(name + ":"+ "Got M2B: value is " + HetconsUtil.get2bValue(block.getHetconsBlock().getHetconsMessage().getM2B(), service));

        Reference refm2b = Reference.newBuilder().setHash(HashUtil.sha3Hash(block)).build();
        HashMap m = status.receive2b(block.getHetconsBlock().getHetconsMessage().getIdentity(), refm2b);
        status.updateRecent2b(HetconsUtil.get2bValue(block.getHetconsBlock().getHetconsMessage().getM2B(), service), message1a.getProposal().getBallot());

        if (m == null) {
            logger.info("No quorum is satisfied");
            return;
        }

        List<Reference> q = (List<Reference>)m.get("references");
        List<CryptoId> p = (List<CryptoId>) m.get("participants");
        if (q == null)
            return;

        HetconsBallot ballot = null;
        HetconsValue value = null;

        // CHeck if the return quorum is valid: same value, same ballot number
        for (Reference r: q) {
            Block b2b = service.getBlock(r);
            HetconsMessage2ab m2b = b2b.getHetconsBlock().getHetconsMessage().getM2B();
            HetconsValue temp = get2bValue(m2b);
            if (ballot == null && value == null) {
                ballot = getM1aFromReference(m2b.getM1ARef()).getProposal().getBallot();
                value = get2bValue(m2b);
            } else {
                if (HetconsUtil.ballotCompare(
                        getM1aFromReference(m2b.getM1ARef()).getProposal().getBallot(),
                        ballot) != 0 || (temp != null && !value.equals(temp)))
                    return;
            }
        }

        // Now we can decided on this 2b value for that slot.
        synchronized (status.getGeneralLock()) {


            synchronized (slotStatus) {
                for (String slotid: status.getChainIDs()) {
                    HetconsSlotStatus slot = slotStatus.get(slotid);
                    if (slot.isDecided()) {
                        logger.info("Slot has been decided on value ");
                        return;
                    }
                    if (HetconsUtil.ballotCompare(ballot, slot.getBallot()) < 0) {
                        logger.info("Ballot number is smaller than the one slot has");
                        System.err.println(name+":Ballot too low to pass status ballot check");
                        return;
                    }
                }
                for (String slotid: status.getChainIDs()) {
                    slotStatus.get(slotid).decide(ballot, q, proposalID);
                }
            }

            numOfDecidedProposals ++;

            status.setStage(HetconsConsensusStage.ConsensusDecided);
            status.setHasDecided(true);
            status.setDecidedQuorum(q);
            status.setDecidedValue(HetconsUtil.get2bValue(block.getHetconsBlock().getHetconsMessage().getM2B(), service));
        }


        logger.info(formatConsensus(q));

        logger.info(name + ":" + "Thread: " + Thread.currentThread().getId() + " There are " + numOfDecidedProposals + " proposals have been decided");



        HetconsObserverQuorum observerQuorum = HetconsObserverQuorum.newBuilder().setOwner(observer.getId())
                .addAllMembers(p)
                .build();
        waitingBlockQueue.remove(proposalID);
        status.getRestartStatus().decided(this.getObserver().getId());
//        status.getRestartStatus().shutdown();
        service.onDecision(observerQuorum, q);
    }

    public List<CryptoId> getParticipants() {
        ArrayList<CryptoId> participants = new ArrayList<>();
//        this.quorum.forEach(participants::addAll);
        return participants;
    }

    /**
     * Given a 1a message, if there is no 2a messages from other proposal then generate a 1b messages.
     * @param m1a
     * @param proposalID
     * @return
     */
    public HetconsMessage1b prepareM1b(HetconsMessage1a m1a, Reference m1aRef, String proposalID) {

        HetconsMessage2ab max2a = null;
        for (String slotID : proposalStatus.get(proposalID).getChainIDs()) {
            HetconsSlotStatus status = this.slotStatus.get(slotID);
            HetconsMessage2ab message2ab = status.getM2a();
            if (message2ab != null && // If m2a exists then m1a must exist
                    !proposalID.equals(HetconsUtil.buildConsensusId(getM1aFromReference(message2ab.getM1ARef()).getProposal().getSlotsList()))) {
                return null;
            } else {
                if (max2a == null)
                    max2a = message2ab;
                else {
                    if (message2ab != null) {
                        max2a = getM1aFromReference(max2a.getM1ARef()).getProposal().getBallot().getBallotSequence().compareTo(
                                getM1aFromReference(message2ab.getM1ARef()).getProposal().getBallot().getBallotSequence()
                        ) >= 0 ? max2a : message2ab;
                    }
                }
            }
        }

        HetconsMessage1b.Builder m1bBuilder = HetconsMessage1b.newBuilder()
                .setM1ARef(m1aRef);

        if (max2a != null) {
            m1bBuilder.setM2A(max2a);
            logger.info(name+"("+proposalID+"): build 1b has 2a " + HetconsUtil.get2bValue(max2a, service));
            proposalStatus.get(proposalID).setRoundStatusM2a(HetconsUtil.get2bValue(max2a, service));
        }

        return m1bBuilder.build();
    }

    private HetconsValue get1bValue(HetconsMessage1b m1b) {
        return HetconsUtil.get1bValue(m1b, service);
    }

    private HetconsValue get2bValue(HetconsMessage2ab m2b) {
        return HetconsUtil.get2bValue(m2b, service);
    }

    /**
     * Re-submit a new proposal with for given value and proposal id.
     * Called when the previous round was timeout.
     * @param consensusId
     * @param value
     */
    private void restartProposal(String consensusId, HetconsValue value) {
        HetconsProposalStatus status = proposalStatus.get(consensusId);

        status.setStage(HetconsConsensusStage.ConsensusRestart);
        logger.info(name + "("+consensusId+"):Restarting...");

        HetconsProposal current = status.getCurrentProposal();
        HetconsProposal proposal = HetconsUtil.buildProposal(current.getSlotsList(),
                value,
                HetconsUtil.buildBallot(value),
                0);

//        status.updateProposal(proposal);

        HetconsMessage1a message1a = HetconsMessage1a.newBuilder()
                .setProposal(proposal).build();

        HetconsMessage message = HetconsMessage.newBuilder()
                .setM1A(message1a).setType(HetconsMessageType.M1a)
                .setIdentity(service.getConfig().getCryptoId())
                .setObserverGroupReferecne(status.getObserverGroupReference())
                .build();

        HetconsBlock hetconsBlock = HetconsBlock.newBuilder()
                .setHetconsMessage(message)
                .setSig(SignatureUtil.signBytes(service.getConfig().getKeyPair(), message))
                .build();

        Block block = Block.newBuilder().setHetconsBlock(hetconsBlock).build();
        logger.info(name + ":("+consensusId+") about to broadcast");
        Logger.getLogger(HetconsParticipantService.class.getName()).info("Restart " + consensusId + " on value "+value.getNum());
        broadcastToParticipants(block, status.getParticipants());

        status.setStage(HetconsConsensusStage.M1ASent);

        /** -------------------- Timer for Restart ----------------------------- */
        // set timer for restart 1a, if we didn't receive 1a after the timeout, we restart the consensus.
        setupTimerForRestart(status, consensusId, value, -1);
    }

    private void broadcastToParticipants(Block block, Set<CryptoId> participants) {
        new HashSet<>(participants).forEach(p -> {
            service.sendBlock(p, block);
            logger.info("Sent " + block.getHetconsBlock().getHetconsMessage().getType() + " to " + HetconsUtil.cryptoIdToString(p));
        });
    }

    private void setupTimerForRestart(HetconsProposalStatus status, String consensusId, HetconsValue value, int opcode) {

        synchronized (status.getRestartStatus().getLock()) {
            status.getRestartStatus().cancelTimers();
            Future<?> timer = status.getTimer().submit(() -> {
                logger.info(name + ":RESTART TIMER("+consensusId+"): Will sleep for " + status.getConsensuTimeout() + " milliseconds for timeout");
                try {
                    TimeUnit.MILLISECONDS.sleep(status.getConsensuTimeout());
                } catch (InterruptedException ex) {
                    logger.info(name + ": "+consensusId+" Restart Timer Cancelled");
                    return;
                }
                if (Thread.interrupted())
                    return;
                synchronized (status.getRestartStatus().getLock()) {
                    if (status.getRestartStatus().getLeftObserversSize() > 0) {
                        for (String chainID : status.getChainIDs()) {
                            if (slotStatus.get(chainID).isDecided()) {
                                return;
                            }
                        }

                        logger.info(name +": Timer ("+consensusId+"): Restart consensus on " + status.getStage().toString() + " for value "
                                + status.getCurrentProposal().getValue());
//                        status.setStage(HetconsConsensusStage.HetconsTimeout);
                        service.getExecutorServiceRestart().submit(() -> {
                                    if (opcode < 0) {
                                        restartProposal(consensusId, value);
                                    } else if (opcode == 0) {
                                        restartProposal(consensusId, status.getRecent1b());
                                    } else {
                                        restartProposal(consensusId, status.getRecent2b());
                                    }
                                }
                        );

                    }
                }
            });
            if (opcode < 0) {
                status.getRestartStatus().setRestartTimer(timer);
            } else if (opcode == 0) {
                status.getRestartStatus().setM1bTimer(timer);
            } else {
                status.getRestartStatus().setM2bTimer(timer);
            }
        }
        logger.info(name+": Timer for " + consensusId+ " has been set to " + status.getConsensuTimeout());
    }

    public HetconsObserver getObserver() {
        return observer;
    }

    public HashMap<String, HetconsProposalStatus> getProposalStatus() {
        return proposalStatus;
    }

    private String formatConsensus(List<Reference> m2bs) {
        StringBuilder stringBuilder = new StringBuilder();
        HetconsMessage2ab m2b = service.getBlock(m2bs.get(0)).getHetconsBlock().getHetconsMessage().getM2B();
        stringBuilder.append(String.format("A quorum of %d messages for Observer %s have been received\n\nDecided on value: %s\nForChain: %s\n\nBallot:%s\n\n",
                m2bs.size(), HetconsUtil.cryptoIdToString(observer.getId()),
                get2bValue(m2b), HetconsUtil.buildConsensusId(getM1aFromReference(m2b.getM1ARef()).getProposal().getSlotsList()),getM1aFromReference(m2b.getM1ARef()).getProposal().getBallot().getBallotSequence()));
        for (int i = 0; i < m2bs.size(); i++) {
            Reference r = m2bs.get(i);
            stringBuilder.append(String.format("\t%s\n", HetconsUtil.bytes2Hex(r.getHash().getSha3().toStringUtf8().getBytes())));
        }
        return stringBuilder.toString();
    }

    public HetconsMessage1a getM1aFromReference(Reference m1aRef) {
        try {
            Block block = service.getBlock(m1aRef);
            return block.getHetconsBlock().getHetconsMessage().getM1A();
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    public void decideSlots(IntegrityAttestation.HetconsAttestation attestation) {
        String proposalID = HetconsUtil.buildConsensusId(attestation.getSlotsList());
        attestation.getSlotsList().forEach(s -> {
            String slotid = HetconsUtil.buildChainSlotID(s);
            HetconsSlotStatus status = slotStatus.get(slotid);
            if (status == null) {
                status = new HetconsSlotStatus(slotid);
                slotStatus.putIfAbsent(slotid, status);
            }
            slotStatus.get(slotid).decide(attestation.getMessage2BList(), proposalID);
        });
    }


}
