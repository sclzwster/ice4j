/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal. Copyright @ 2015 Atlassian Pty Ltd Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or
 * agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under the License.
 */
package org.ice4j.ice.harvest;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ice4j.StackProperties;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.Component;
import org.ice4j.ice.HostCandidate;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.security.LongTermCredential;
import org.ice4j.socket.IceTcpSocketWrapper;
import org.ice4j.stack.StunStack;

/**
 * Implements a CandidateHarvester which gathers Candidates
 * for a specified {@link Component} using STUN as defined in RFC 5389 "Session
 * Traversal Utilities for NAT (STUN)" only.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
public class StunCandidateHarvester extends AbstractCandidateHarvester {

    /**
     * The Logger used by the StunCandidateHarvester class and
     * its instances for logging output.
     */
    private static final Logger logger = Logger.getLogger(StunCandidateHarvester.class.getName());

    /**
     * The list of StunCandidateHarvests which have been successfully
     * completed i.e. have harvested Candidates.
     */
    private final List<StunCandidateHarvest> completedHarvests = new LinkedList<>();

    /**
     * The username used by this StunCandidateHarvester for the
     * purposes of the STUN short-term credential mechanism.
     */
    private final String shortTermCredentialUsername;

    /**
     * The list of StunCandidateHarvests which have been started to
     * harvest Candidates for HostCandidates and which have
     * not completed yet so {@link #harvest(Component)} has to wait for them.
     */
    private final List<StunCandidateHarvest> startedHarvests = new LinkedList<>();

    /**
     * The address of the STUN server that we will be sending our requests to.
     */
    public final TransportAddress stunServer;

    /**
     * The StunStack used by this instance for the purposes of STUN
     * communication.
     */
    private StunStack stunStack;

    /**
     * Creates a new STUN harvester that will be running against the specified
     * stunServer using a specific username for the purposes of the
     * STUN short-term credential mechanism.
     *
     * @param stunServer the address of the STUN server that we will be querying
     * for our public bindings
     */
    public StunCandidateHarvester(TransportAddress stunServer) {
        this(stunServer, null);
    }

    /**
     * Creates a new STUN harvester that will be running against the specified
     * stunServer using a specific username for the purposes of the
     * STUN short-term credential mechanism.
     *
     * @param stunServer the address of the STUN server that we will be querying
     * for our public bindings
     * @param shortTermCredentialUsername the username to be used by the new
     * instance for the purposes of the STUN short-term credential mechanism or
     * null if the use of the STUN short-term credential mechanism is
     * not determined at the time of the construction of the new instance
     */
    public StunCandidateHarvester(TransportAddress stunServer, String shortTermCredentialUsername) {
        this.stunServer = stunServer;
        this.shortTermCredentialUsername = shortTermCredentialUsername;

        //these should be configurable.
        if (System.getProperty(StackProperties.MAX_CTRAN_RETRANS_TIMER) == null)
            System.setProperty(StackProperties.MAX_CTRAN_RETRANS_TIMER, "400");
        if (System.getProperty(StackProperties.MAX_CTRAN_RETRANSMISSIONS) == null)
            System.setProperty(StackProperties.MAX_CTRAN_RETRANSMISSIONS, "3");
    }

    /**
     * Notifies this StunCandidateHarvester that a specific
     * StunCandidateHarvest has been completed. If the specified
     * harvest has harvested Candidates, it is moved from
     * {@link #startedHarvests} to {@link #completedHarvests}. Otherwise, it is
     * just removed from {@link #startedHarvests}.
     *
     * @param harvest the StunCandidateHarvest which has been completed
     */
    void completedResolvingCandidate(StunCandidateHarvest harvest) {
        boolean doNotify = false;

        synchronized (startedHarvests) {
            startedHarvests.remove(harvest);

            // If this was the last candidate, we are done with the STUN
            // resolution and need to notify the waiters.
            if (startedHarvests.isEmpty())
                doNotify = true;
        }

        synchronized (completedHarvests) {
            if (harvest.getCandidateCount() < 1)
                completedHarvests.remove(harvest);
            else if (!completedHarvests.contains(harvest))
                completedHarvests.add(harvest);
        }

        synchronized (startedHarvests) {
            if (doNotify)
                startedHarvests.notify();
        }
    }

    /**
     * Creates a new StunCandidateHarvest instance which is to perform
     * STUN harvesting of a specific HostCandidate.
     *
     * @param hostCandidate the HostCandidate for which harvesting is
     * to be performed by the new StunCandidateHarvest instance
     * @return a new StunCandidateHarvest instance which is to perform
     * STUN harvesting of the specified hostCandidate
     */
    protected StunCandidateHarvest createHarvest(HostCandidate hostCandidate) {
        return new StunCandidateHarvest(this, hostCandidate);
    }

    /**
     * Creates a LongTermCredential to be used by a specific
     * StunCandidateHarvest for the purposes of the long-term
     * credential mechanism in a specific realm of the STUN server
     * associated with this StunCandidateHarvester. The default
     * implementation returns null and allows extenders to override in
     * order to support the long-term credential mechanism.
     *
     * @param harvest the StunCandidateHarvest which asks for the
     * LongTermCredential
     * @param realm the realm of the STUN server associated with this
     * StunCandidateHarvester in which harvest will use the
     * returned LongTermCredential
     * @return a LongTermCredential to be used by harvest for
     * the purposes of the long-term credential mechanism in the specified
     * realm of the STUN server associated with this
     * StunCandidateHarvester
     */
    protected LongTermCredential createLongTermCredential(StunCandidateHarvest harvest, byte[] realm) {
        // The long-term credential mechanism is not utilized by default.
        return null;
    }

    /**
     * Gets the username to be used by this StunCandidateHarvester for
     * the purposes of the STUN short-term credential mechanism.
     *
     * @return the username to be used by this StunCandidateHarvester
     * for the purposes of the STUN short-term credential mechanism or
     * null if the STUN short-term credential mechanism is not to be
     * utilized
     */
    protected String getShortTermCredentialUsername() {
        return shortTermCredentialUsername;
    }

    /**
     * Gets the StunStack used by this CandidateHarvester for
     * the purposes of STUN communication. It is guaranteed to be available only
     * during the execution of {@link CandidateHarvester#harvest(Component)}.
     *
     * @return the StunStack used by this CandidateHarvester
     * for the purposes of STUN communication
     * @see CandidateHarvester#harvest(Component)
     */
    public StunStack getStunStack() {
        return stunStack;
    }

    /**
     * Gathers STUN candidates for all host Candidates that are already
     * present in the specified component. This method relies on the
     * specified component to already contain all its host candidates
     * so that it would resolve them.
     *
     * @param component the {@link Component} that we'd like to gather candidate
     * STUN Candidates for
     * @return  the LocalCandidates gathered by this
     * CandidateHarvester
     */
    @Override
    public Collection<LocalCandidate> harvest(Component component) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("starting " + component.toShortString() + " harvest for: " + toString());
        }
        stunStack = component.getParentStream().getParentAgent().getStunStack();

        for (Candidate<?> cand : component.getLocalCandidates()) {
            if ((cand instanceof HostCandidate) && (cand.getTransport() == stunServer.getTransport())) {
                startResolvingCandidate((HostCandidate) cand);
            }
        }

        waitForResolutionEnd();

        /*
         * Report the LocalCandidates gathered by this CandidateHarvester so that the harvest is sure to be considered successful.
         */
        Collection<LocalCandidate> candidates = new HashSet<>();

        synchronized (completedHarvests) {
            for (StunCandidateHarvest completedHarvest : completedHarvests) {
                LocalCandidate[] completedHarvestCandidates = completedHarvest.getCandidates();

                if ((completedHarvestCandidates != null) && (completedHarvestCandidates.length != 0)) {
                    candidates.addAll(Arrays.asList(completedHarvestCandidates));
                }
            }

            completedHarvests.clear();
        }

        logger.finest("Completed " + component.toShortString() + " harvest: " + toString() + ". Found " + candidates.size() + " candidates: " + listCandidates(candidates));

        return candidates;
    }

    private String listCandidates(Collection<? extends Candidate<?>> candidates) {
        StringBuilder retval = new StringBuilder();
        for (Candidate<?> candidate : candidates) {
            retval.append(candidate.toShortString());
        }
        return retval.toString();
    }

    /**
     * Sends a binding request to our stun server through the specified
     * hostCand candidate and adds it to the list of addresses still
     * waiting for resolution.
     *
     * @param hostCand the HostCandidate that we'd like to resolve.
     */
    private void startResolvingCandidate(HostCandidate hostCand) {
        //first of all, make sure that the STUN server and the Candidate
        //address are of the same type and that they can communicate.
        if (!hostCand.getTransportAddress().canReach(stunServer))
            return;

        HostCandidate cand = getHostCandidate(hostCand);

        if (cand == null) {
            logger.info("server/candidate address type mismatch," + " skipping candidate in this harvester");
            return;
        }

        StunCandidateHarvest harvest = createHarvest(cand);

        if (harvest == null) {
            logger.warning("failed to create harvest");
            return;
        }

        synchronized (startedHarvests) {
            startedHarvests.add(harvest);

            boolean started = false;

            try {
                started = harvest.startResolvingCandidate();
            } catch (Exception ex) {
                started = false;
                if (logger.isLoggable(Level.INFO)) {
                    logger.log(Level.INFO, "Failed to start resolving host candidate " + hostCand, ex);
                }
            } finally {
                if (!started) {
                    try {
                        startedHarvests.remove(harvest);
                        logger.warning("harvest did not start, removed: " + harvest);
                    } finally {
                        /*
                         * For the sake of completeness, explicitly close the harvest.
                         */
                        try {
                            harvest.close();
                        } catch (Exception ex) {
                        }
                    }
                }
            }
        }
    }

    /**
     * Blocks the current thread until all resolutions in this harvester
     * have terminated one way or another.
     */
    private void waitForResolutionEnd() {
        synchronized (startedHarvests) {
            boolean interrupted = false;

            // Handle spurious wakeups.
            while (!startedHarvests.isEmpty()) {
                try {
                    startedHarvests.wait();
                } catch (InterruptedException iex) {
                    logger.info("interrupted waiting for harvests to complete," + " no. startedHarvests = " + startedHarvests.size());
                    interrupted = true;
                }
            }
            // Restore the interrupted status.
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns a String representation of this harvester containing its
     * type and server address.
     *
     * @return a String representation of this harvester containing its
     * type and server address.
     */
    @Override
    public String toString() {
        String proto = (this instanceof TurnCandidateHarvester) ? "TURN" : "STUN";
        return proto + " harvester(srvr: " + this.stunServer + ")";
    }

    /**
     * Returns the host candidate.
     * For UDP it simply returns the candidate passed as a parameter.
     *
     * However for TCP, we cannot return the same hostCandidate because in Java
     * a  "server" socket cannot connect to a destination with the same local
     * address/port (i.e. a Java Socket cannot act as both server/client).
     *
     * @param hostCand HostCandidate
     * @return HostCandidate
     */
    protected HostCandidate getHostCandidate(HostCandidate hostCand) {
        HostCandidate cand;
        // create a new TCP HostCandidate
        if (hostCand.getTransport() == Transport.TCP) {
            try {
                SocketChannel socketChannel = SocketChannel.open();
                socketChannel.bind(new InetSocketAddress(stunServer.getAddress(), stunServer.getPort()));
                IceTcpSocketWrapper sock = new IceTcpSocketWrapper(socketChannel);
                cand = new HostCandidate(sock, hostCand.getParentComponent(), Transport.TCP);
                hostCand.getParentComponent().getParentStream().getParentAgent().getStunStack().addSocket(cand.getStunSocket(null));
                hostCand.getParentComponent().getComponentSocket().setSocket(sock);
            } catch (Exception io) {
                logger.info("Exception TCP client connect: " + io);
                return null;
            }
        } else {
            cand = hostCand;
        }
        return cand;
    }

}
