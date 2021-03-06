/*
 * Copyright 2009 Amit Levy, Jeff Prouty, Rylan Hawkins
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.washington.cs.cse490h.donut.server;

import java.util.List;
import java.util.logging.Logger;

import org.apache.thrift.TException;

import com.google.inject.Inject;

import edu.washington.cs.cse490h.donut.business.Node;
import edu.washington.cs.cse490h.donut.service.LocatorClientFactory;
import edu.washington.cs.cse490h.donut.service.RetryFailedException;
import edu.washington.cs.cse490h.donut.Constants;
import edu.washington.cs.cse490h.donut.business.KeyId;
import edu.washington.cs.cse490h.donut.service.NodeNotFoundException;
import edu.washington.cs.cse490h.donut.business.TNode;
import edu.washington.cs.cse490h.donut.service.KeyLocator.Iface;
import edu.washington.cs.cse490h.donut.util.KeyIdUtil;

/**
 * @author alevy
 */
public class DonutClient extends Thread {
    private static final Logger         LOGGER;

    private final Node                  node;
    private final LocatorClientFactory  clientFactory;

    private int                         nextFingerToUpdate;

    final private AbstractRunAtInterval stabilize;
    final private AbstractRunAtInterval checkPredecessor;
    final private AbstractRunAtInterval fixFingers;

    static {
        LOGGER = Logger.getLogger(DonutClient.class.getName());
    }

    @Inject
    public DonutClient(Node node, LocatorClientFactory clientFactory) {
        this.node = node;
        this.clientFactory = clientFactory;
        nextFingerToUpdate = 0;

        // Initialize the worker threads

        stabilize = new AbstractRunAtInterval(Constants.STABILIZE_INTERVAL) {
            public void runClosure() {
                stabilize();
            }
        };

        fixFingers = new AbstractRunAtInterval(Constants.FIX_FINGERS_INTERVAL) {
            public void runClosure() {
                fixFingers();
            }
        };

        checkPredecessor = new AbstractRunAtInterval(Constants.CHECK_PREDECESSOR_INTERVAL) {
            public void runClosure() {
                checkPredecessor();
            }
        };
    }

    public void join(TNode n) throws TException {
        try {
            if (!n.equals(node.getTNode())) {
                TNode found = clientFactory.get(n).findSuccessor(node.getNodeId());
                node.setSuccessor(found);
                clientFactory.release(n);
            }
        } catch (RetryFailedException e) {
            throw new TException(e);
        }
        LOGGER.info("Joined Donut [" + Node.TNodeToString(node.getTNode()) + "]: Known Node - "
                + Node.TNodeToString(n));
    }

    public boolean ping(TNode n) {
        try {
            clientFactory.get(n).ping();
            clientFactory.release(n);
            return true;
        } catch (RetryFailedException e) {
            return false;
        } catch (TException e) {
            // Thrift error. Take a look at the trace
            clientFactory.release(n);
            LOGGER.warning(e.toString());
            return false;
        }
    }

    /**
     * Called periodically. Checks whether the predecessor has failed.
     */
    public void checkPredecessor() {
        if (this.node.getPredecessor() != null && !ping(this.node.getPredecessor())) {
            // A predecessor is defined but could not be reached. Nullify the current predecessor
            LOGGER.warning("Lost Predecessor [" + Node.TNodeToString(node.getTNode())
                    + "]: Predecessor - " + Node.TNodeToString(node.getPredecessor()));

            this.node.setPredecessor(null);
        }
    }

    /**
     * Called periodically. Refreshes the finger table entries. nextFingerToUpdate stores the index
     * of the next finger to fix.
     */
    public void fixFingers() {
        fixFinger(nextFingerToUpdate);

        nextFingerToUpdate = (nextFingerToUpdate + 1) % node.getFingers().size();
    }

    public void fixFinger(int finger) {
        Iface iface;
        try {
            iface = clientFactory.get(node.getTNode());
        } catch (RetryFailedException e) {
            // We should always be able to connect to our own service. If not, its serious
            LOGGER.severe("FixFingers [" + Node.TNodeToString(node.getTNode())
                    + "] failed to get client to itself.");

            // Skip this finger. It will get updated on the next go throw the finger table.
            return;
        }

        // Keep as separate variable: Be careful of some weird java issues with overflowing ints
        long base = node.getNodeId().getId();
        long pow = 0x0000000000000001L << finger;
        long id = base + pow;

        KeyId keyId = new KeyId(id);
        try {
            TNode updatedFinger = iface.findSuccessor(keyId);
            this.node.setFinger(finger, updatedFinger);
        } catch (TException e1) {
            LOGGER.warning("Thrift Exception in findSuccessor ["
                    + Node.TNodeToString(node.getTNode()) + "]: keyId-" + keyId);
        }

        clientFactory.release(node.getTNode());
    }

    /**
     * Called periodically. Verify's immediate successor, and tell's successor about us.
     */
    public void stabilize() {
        TNode x = null;
        TNode successor = node.getSuccessor();
        Iface successorClient;

        try {
            successorClient = clientFactory.get(successor);
        } catch (RetryFailedException e) {
            LOGGER.info("Lost successor [" + Node.TNodeToString(node.getTNode()) + "]: Successor- "
                    + Node.TNodeToString(successor));

            clientFactory.release(successor);
            node.removeSuccessor();
            return;
        }

        try {
            x = successorClient.getPredecessor();
        } catch (NodeNotFoundException e) {
            // Successor's predecessor is null
        } catch (TException e) {
            LOGGER.info("Lost successor [" + Node.TNodeToString(node.getTNode())
                    + "]: Successor - " + Node.TNodeToString(node.getSuccessor()));

            node.removeSuccessor();
            clientFactory.release(successor);
            return;
        }

        if (x != null
                && KeyIdUtil.isAfterXButBeforeEqualY(x.getNodeId(), node.getNodeId(), successor
                        .getNodeId())) {
            clientFactory.release(successor);
            successor = x;

            try {
                successorClient = clientFactory.get(successor);
            } catch (RetryFailedException e) {
                LOGGER.info("Lost successor [" + Node.TNodeToString(node.getTNode())
                        + "]: Successor - " + Node.TNodeToString(node.getSuccessor()));

                clientFactory.release(successor);
                return;
            }
        }

        try {
            List<TNode> successorList = successorClient.notify(node.getTNode());

            // Set successor *MUST* be called after notify for replication
            // to be guaranteed
            node.updateSuccessorList(successorList);
            node.setSuccessor(successor);
        } catch (TException e) {
            LOGGER.info("Lost successor [" + Node.TNodeToString(node.getTNode())
                    + "]: Successor - " + Node.TNodeToString(node.getSuccessor()));
            return;
        } finally {
            clientFactory.release(successor);
        }
    }

    /**
     * Stops the worker threads.
     */
    public void kill() {
        stabilize.kill();
        checkPredecessor.kill();
        fixFingers.kill();
    }

    /**
     * Starts the worker threads.
     */
    @Override
    public void run() {
        super.run();

        stabilize.start();
        checkPredecessor.start();
        fixFingers.start();
    }
}
