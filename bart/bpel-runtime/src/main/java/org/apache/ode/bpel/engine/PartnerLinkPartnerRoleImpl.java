/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ode.bpel.engine;

import java.util.Set;

import javax.transaction.Transaction;
import javax.wsdl.Operation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.dao.MessageExchangeDAO;
import org.apache.ode.bpel.engine.PartnerRoleMessageExchangeImpl.State;
import org.apache.ode.bpel.iapi.BpelEngineException;
import org.apache.ode.bpel.iapi.Endpoint;
import org.apache.ode.bpel.iapi.EndpointReference;
import org.apache.ode.bpel.iapi.InvocationStyle;
import org.apache.ode.bpel.iapi.PartnerRoleChannel;
import org.apache.ode.bpel.iapi.MessageExchange.AckType;
import org.apache.ode.bpel.iapi.MessageExchange.FailureType;
import org.apache.ode.bpel.iapi.MessageExchange.MessageExchangePattern;
import org.apache.ode.bpel.iapi.MessageExchange.Status;
import org.apache.ode.bpel.o.OPartnerLink;
import org.w3c.dom.Element;

/**
 * 
 * Class providing a lot of the dirty work of IL invokes.
 * 
 * @author Matthieu Riou <mriou at apache dot org>
 * @author Maciej Szefler <mszefler at gmail dot com>
 */
class PartnerLinkPartnerRoleImpl extends PartnerLinkRoleImpl {
    static final Log __log = LogFactory.getLog(BpelProcess.class);

    Endpoint _initialPartner;

    public PartnerRoleChannel _channel;

    PartnerLinkPartnerRoleImpl(BpelProcess process, OPartnerLink plink, Endpoint initialPartner) {
        super(process, plink);
        _initialPartner = initialPartner;
    }

    PartnerRoleMessageExchangeImpl createPartnerRoleMex(MessageExchangeDAO mexdao) {
        InvocationStyle istyle = InvocationStyle.valueOf(mexdao.getInvocationStyle());
        PartnerRoleMessageExchangeImpl mex;
        Operation op = _plinkDef.getPartnerRoleOperation(mexdao.getOperation());
        EndpointReference myroleEPR = _plinkDef.hasMyRole() ? _process.getInitialMyRoleEPR(_plinkDef) : null;
        switch (istyle) {
        case UNRELIABLE:
            mex = new UnreliablePartnerRoleMessageExchangeImpl(_process, mexdao.getInstance().getInstanceId(), mexdao
                    .getMessageExchangeId(), _plinkDef, op, /* EPR todo */
            null, myroleEPR, _channel);
            break;
        case TRANSACTED:
            mex = new TransactedPartnerRoleMessageExchangeImpl(_process, mexdao.getInstance().getInstanceId(), mexdao
                    .getMessageExchangeId(), _plinkDef, op, /*
                                                             * EPR todo
                                                             */
            null, myroleEPR, _channel);
            break;
        case RELIABLE:
            mex = new ReliablePartnerRoleMessageExchangeImpl(_process, mexdao.getInstance().getInstanceId(), mexdao
                    .getMessageExchangeId(), _plinkDef, op, null, /* EPR todo */
            myroleEPR, _channel);
            break;

        default:
            throw new BpelEngineException("Unexpected InvocationStyle: " + istyle);

        }

        mex.load(mexdao);
        return mex;

    }

    /**
     * Invoke a partner through the integration layer.
     * 
     * @param mexDao
     */
    void invokeIL(MessageExchangeDAO mexDao) {

        Element partnerEprXml = mexDao.getEPR();
        EndpointReference partnerEpr = partnerEprXml == null ? _initialEPR : _contexts.eprContext
                .resolveEndpointReference(partnerEprXml);
        EndpointReference myRoleEpr = null; // TODO: fix?
        Operation operation = _plinkDef.getPartnerRoleOperation(mexDao.getOperation());
        Set<InvocationStyle> supportedStyles = _contexts.mexContext.getSupportedInvocationStyle(_channel, partnerEpr);

        boolean oneway = MessageExchangePattern.valueOf(mexDao.getPattern()) == MessageExchangePattern.REQUEST_ONLY;

        if (_process.isInMemory()) {
            invokeInMem(mexDao, partnerEpr, myRoleEpr, operation, supportedStyles, oneway);
        } else {
            invokePersisted(mexDao, partnerEpr, myRoleEpr, operation, supportedStyles);
        }

    }

    private void invokePersisted(MessageExchangeDAO mexDao, EndpointReference partnerEpr, EndpointReference myRoleEpr,
            Operation operation, Set<InvocationStyle> supportedStyles) {
        if (supportedStyles.contains(InvocationStyle.TRANSACTED)) {
            invokeTransacted(mexDao, partnerEpr, myRoleEpr, operation);
        } else if (supportedStyles.contains(InvocationStyle.RELIABLE)) {
            invokeReliable(mexDao, partnerEpr, myRoleEpr, operation);
        } else if (supportedStyles.contains(InvocationStyle.UNRELIABLE)) {
            invokeUnreliable(mexDao, partnerEpr, myRoleEpr, operation);
        } else {
            // This really should not happen, indicates IL is screwy.
            __log.error("Integration Layer did not agree to any known invocation style for EPR " + partnerEpr);
            mexDao.setFailureType(FailureType.COMMUNICATION_ERROR.toString());
            mexDao.setStatus(Status.ACK.toString());
            mexDao.setAckType(AckType.FAILURE);
            mexDao.setFaultExplanation("NoMatchingStyle");
        }
    }

    private void invokeUnreliable(MessageExchangeDAO mexDao, EndpointReference partnerEpr, EndpointReference myRoleEpr,
            Operation operation) {
        // For BLOCKING invocation, we defer the call until after commit (unless idempotent).
        UnreliablePartnerRoleMessageExchangeImpl blockingMex = new UnreliablePartnerRoleMessageExchangeImpl(_process, mexDao
                .getInstance().getInstanceId(), mexDao.getMessageExchangeId(), _plinkDef, operation, partnerEpr, myRoleEpr,
                _channel);
        // We schedule in-memory (no db) to guarantee "at most once" semantics.
        blockingMex.setState(State.INVOKE_XXX);
        _process.scheduleInstanceWork(mexDao.getInstance().getInstanceId(), new UnreliableInvoker(blockingMex));
    }

    /**
     * Invoke an in-memory process. In-memory processes are a bit different, we're never going to do any scheduling for them, so
     * we'd prefer to have TRANSACTED invocation style. If that is not available we have to fake it.
     * 
     * @param mexDao
     * @param partnerEpr
     * @param myRoleEpr
     * @param operation
     * @param supportedStyles
     * @param oneway
     */
    private void invokeInMem(MessageExchangeDAO mexDao, EndpointReference partnerEpr, EndpointReference myRoleEpr,
            Operation operation, Set<InvocationStyle> supportedStyles, boolean oneway) {
        // In-memory processes are a bit different, we're never going to do any scheduling for them, so we'd
        // prefer to have TRANSACTED invocation style.
        if (supportedStyles.contains(InvocationStyle.TRANSACTED)) {
            invokeTransacted(mexDao, partnerEpr, myRoleEpr, operation);
        } else if (supportedStyles.contains(InvocationStyle.RELIABLE) && oneway) {
            invokeReliable(mexDao, partnerEpr, myRoleEpr, operation);
        } else if (supportedStyles.contains(InvocationStyle.UNRELIABLE)) {
            UnreliablePartnerRoleMessageExchangeImpl unreliableMex = new UnreliablePartnerRoleMessageExchangeImpl(_process, mexDao
                    .getInstance().getInstanceId(), mexDao.getMessageExchangeId(), _plinkDef, operation, partnerEpr, myRoleEpr,
                    _channel);

            // Need to cheat a little bit for in-memory processes; do the invoke in-line, but first suspend
            // the transaction so that the IL does not get confused.
            Transaction tx;
            try {
                tx = _contexts.txManager.suspend();
            } catch (Exception ex) {
                throw new BpelEngineException("TxManager Error: cannot suspend!", ex);
            }

            try {
                unreliableMex.setState(State.INVOKE_XXX);
                _contexts.mexContext.invokePartnerBlocking(unreliableMex);
                try {
                    unreliableMex.waitForAck(mexDao.getTimeout());
                } catch (InterruptedException ie) {
                    ;
                    ; // ignore
                }

            } finally {
                unreliableMex.setState(State.DEAD);
                try {
                    _contexts.txManager.resume(tx);
                } catch (Exception e) {
                    throw new BpelEngineException("TxManager Error: cannot resume!", e);
                }
            }

            if (unreliableMex.getStatus() != Status.ACK) {
                MexDaoUtil.setFailed(mexDao, FailureType.NO_RESPONSE, "No Response");
            } else {
                unreliableMex.save(mexDao);
            }
        } else /* non-supported in-mem style */{
            MexDaoUtil.setFailed(mexDao, FailureType.OTHER, "Unsupported invocation style for in-mem process.");
        }
    }

    private void invokeReliable(MessageExchangeDAO mexDao, EndpointReference partnerEpr, EndpointReference myRoleEpr,
            Operation operation) {
        // We can do RELIABLE for in-mem, but only if they are one way.
        ReliablePartnerRoleMessageExchangeImpl reliableMex = new ReliablePartnerRoleMessageExchangeImpl(_process, mexDao
                .getInstance().getInstanceId(), mexDao.getMessageExchangeId(), _plinkDef, operation, partnerEpr, myRoleEpr,
                _channel);
        reliableMex.setState(State.INVOKE_XXX);
        Throwable err = null;
        try {
            _contexts.mexContext.invokePartnerReliable(reliableMex);
        } catch (Throwable t) {
            err = t;
        }

        reliableMex.setState(State.HOLD);

        if (err != null) {
            MexDaoUtil.setFailed(mexDao,FailureType.COMMUNICATION_ERROR, err.toString());
            reliableMex.setState(State.DEAD);
        } else {
            if (reliableMex.getStatus() == Status.ACK) {
                reliableMex.save(mexDao);
                reliableMex.setState(State.DEAD);
            } else 
                reliableMex.setState(State.ASYNC);
        }
                    
    }

    private void invokeTransacted(MessageExchangeDAO mexDao, EndpointReference partnerEpr, EndpointReference myRoleEpr,
            Operation operation) {
        // If TRANSACTED is supported, this is again easy, do it in-line.
        TransactedPartnerRoleMessageExchangeImpl transactedMex = new TransactedPartnerRoleMessageExchangeImpl(_process, mexDao
                .getInstance().getInstanceId(), mexDao.getMessageExchangeId(), _plinkDef, operation, partnerEpr, myRoleEpr,
                _channel);
        transactedMex.setState(State.INVOKE_XXX);
        try {
            _contexts.mexContext.invokePartnerTransacted(transactedMex);
        } catch (Throwable t) {
            __log.error("Transacted partner invoke threw an exception; rolling back.");
            try {
                _contexts.txManager.setRollbackOnly();
            } catch (Exception ex) {
                __log.fatal("TransactionManager error, could not setRollbackOnly()",ex);
            }
            throw new BpelEngineException("Rollback required.",t);
        } finally {
            transactedMex.setState(State.DEAD);
        }
        
        if (transactedMex.getStatus() != Status.ACK) {
            MexDaoUtil.setFailed(mexDao, FailureType.NO_RESPONSE, "Integration Layer did not provide required ACK.");
        } else {
            transactedMex.save(mexDao);
        }
        
    }

    /**
     * Runnable that actually performs UNRELIABLE invokes on the partner.
     * 
     * @author Maciej Szefler <mszefler at gmail dot com>
     * 
     */
    class UnreliableInvoker implements Runnable {

        UnreliablePartnerRoleMessageExchangeImpl _unreliableMex;

        BpelInstanceWorker _iworker;

        /** Keep a copy of the last BpelRuntimeContextImpl; this is used to optimize away a DB read. */
        BpelRuntimeContextImpl _lastBRC;

        /**
         * 
         * @param blockingMex
         *            the MEX we're invoking on the partner
         * @param iworker
         *            instance worker (for scheduling continuation)
         * @param lastBpelRuntimeContextImpl
         *            the BRC that initiated this invoke
         */
        public UnreliableInvoker(UnreliablePartnerRoleMessageExchangeImpl blockingMex) {
            _unreliableMex = blockingMex;
        }

        public void run() {
            assert !_contexts.isTransacted();

            // Do the unreliable invoke (outside of tx context). A system failure here will result in the mex going
            // into an unknown state requiring manual intervention.
            Throwable err = null;
            Status status;
            _unreliableMex.setState(State.INVOKE_XXX);
            try {
                _contexts.mexContext.invokePartnerBlocking(_unreliableMex);
                _unreliableMex.setState(State.HOLD);
            } catch (Throwable t) {
                _unreliableMex.setState(State.DEAD);
                err = t;
            }

            final Throwable ferr = err;

            // We proceed handling the response in a transaction. Note that if for some reason the following transaction
            // fails, the unreliable invoke will be in an "unknown" state, and will require manual intervention to either
            // retry or force fail.
            try {

                _contexts.execTransaction(new Runnable() {
                    public void run() {

                        MessageExchangeDAO mexdao = _process.loadMexDao(_unreliableMex.getMessageExchangeId());
                        if (ferr != null) {
                            MexDaoUtil.setFailed(mexdao, FailureType.OTHER, ferr.toString());
                            _unreliableMex.setState(State.DEAD);
                        } else if (_unreliableMex.getStatus() == Status.ACK) {
                            _unreliableMex.save(mexdao);
                            _unreliableMex.setState(State.DEAD);
                        } else if (_unreliableMex.getStatus() == Status.REQ && !_unreliableMex._asyncReply) {
                            MexDaoUtil.setFailed(mexdao, FailureType.NO_RESPONSE, "No Response");
                            _unreliableMex.setState(State.DEAD);
                        } else if (_unreliableMex._asyncReply) {
                            _unreliableMex.setState(State.ASYNC);
                            return;
                        } else {
                            // We should have exhausted the possibilities.
                            throw new BpelEngineException("InternalError: Unexpected message exchange state!");
                        }

                        _process.executeContinueInstancePartnerRoleResponseReceived(mexdao);

                    }

                });
            } catch (Throwable t) {
                _unreliableMex.setState(State.DEAD);
                __log.error("Transaction Failed (TODO!!!!): Need to mark instance for user action", t);
                // TODO: Schedule something to pick up the job (we cant just retry bc the invoke is complete!
            }

        }

    }
}