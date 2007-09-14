/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.client;

import org.apache.qpid.AMQException;
import org.apache.qpid.AMQInvalidArgumentException;
import org.apache.qpid.AMQInvalidRoutingKeyException;
import org.apache.qpid.AMQUndeliveredException;
import org.apache.qpid.client.failover.FailoverException;
import org.apache.qpid.client.failover.FailoverNoopSupport;
import org.apache.qpid.client.failover.FailoverProtectedOperation;
import org.apache.qpid.client.failover.FailoverRetrySupport;
import org.apache.qpid.client.message.AbstractJMSMessage;
import org.apache.qpid.client.message.JMSBytesMessage;
import org.apache.qpid.client.message.JMSMapMessage;
import org.apache.qpid.client.message.JMSObjectMessage;
import org.apache.qpid.client.message.JMSStreamMessage;
import org.apache.qpid.client.message.JMSTextMessage;
import org.apache.qpid.client.message.MessageFactoryRegistry;
import org.apache.qpid.client.message.UnprocessedMessage;
import org.apache.qpid.client.protocol.AMQProtocolHandler;
import org.apache.qpid.client.util.FlowControllingBlockingQueue;
import org.apache.qpid.common.AMQPFilterTypes;
import org.apache.qpid.framing.AMQFrame;
import org.apache.qpid.framing.AMQShortString;
import org.apache.qpid.framing.BasicAckBody;
import org.apache.qpid.framing.BasicConsumeBody;
import org.apache.qpid.framing.BasicConsumeOkBody;
import org.apache.qpid.framing.BasicRecoverBody;
import org.apache.qpid.framing.BasicRecoverOkBody;
import org.apache.qpid.framing.BasicRejectBody;
import org.apache.qpid.framing.ChannelCloseBody;
import org.apache.qpid.framing.ChannelCloseOkBody;
import org.apache.qpid.framing.ChannelFlowBody;
import org.apache.qpid.framing.ChannelFlowOkBody;
import org.apache.qpid.framing.ExchangeBoundBody;
import org.apache.qpid.framing.ExchangeBoundOkBody;
import org.apache.qpid.framing.ExchangeDeclareBody;
import org.apache.qpid.framing.ExchangeDeclareOkBody;
import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.framing.FieldTableFactory;
import org.apache.qpid.framing.QueueBindBody;
import org.apache.qpid.framing.QueueBindOkBody;
import org.apache.qpid.framing.QueueDeclareBody;
import org.apache.qpid.framing.QueueDeclareOkBody;
import org.apache.qpid.framing.QueueDeleteBody;
import org.apache.qpid.framing.QueueDeleteOkBody;
import org.apache.qpid.framing.TxCommitBody;
import org.apache.qpid.framing.TxCommitOkBody;
import org.apache.qpid.framing.TxRollbackBody;
import org.apache.qpid.framing.TxRollbackOkBody;
import org.apache.qpid.jms.Session;
import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.protocol.AMQMethodEvent;
import org.apache.qpid.url.AMQBindingURL;
import org.apache.qpid.url.URLSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.IllegalStateException;
import javax.jms.InvalidDestinationException;
import javax.jms.InvalidSelectorException;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.QueueReceiver;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.jms.StreamMessage;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;
import java.io.Serializable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * <p/><table id="crc"><caption>CRC Card</caption>
 * <tr><th> Responsibilities <th> Collaborations
 * <tr><td>
 * </table>
 *
 * @todo Different FailoverSupport implementation are needed on the same method call, in different situations. For
 *       example, when failing-over and reestablishing the bindings, the bind cannot be interrupted by a second
 *       fail-over, if it fails with an exception, the fail-over process should also fail. When binding outside of
 *       the fail-over process, the retry handler could be used to automatically retry the operation once the connection
 *       has been reestablished. All fail-over protected operations should be placed in private methods, with
 *       FailoverSupport passed in by the caller to provide the correct support for the calling context. Sometimes the
 *       fail-over process sets a nowait flag and uses an async method call instead.
 *
 * @todo Two new objects created on every failover supported method call. Consider more efficient ways of doing this,
 *       after looking at worse bottlenecks first.
 */
public class AMQSession extends Closeable implements Session, QueueSession, TopicSession
{
    /** Used for debugging. */
    private static final Logger _logger = LoggerFactory.getLogger(AMQSession.class);

    /** Used for debugging in the dispatcher. */
    private static final Logger _dispatcherLogger = LoggerFactory.getLogger(Dispatcher.class);

    /** The default maximum number of prefetched message at which to suspend the channel. */
    public static final int DEFAULT_PREFETCH_HIGH_MARK = 5000;

    /** The default minimum number of prefetched messages at which to resume the channel. */
    public static final int DEFAULT_PREFETCH_LOW_MARK = 2500;

    /**
     * The default value for immediate flag used by producers created by this session is false. That is, a consumer does
     * not need to be attached to a queue.
     */
    protected static final boolean DEFAULT_IMMEDIATE = false;

    /**
     * The default value for mandatory flag used by producers created by this session is true. That is, server will not
     * silently drop messages where no queue is connected to the exchange for the message.
     */
    protected static final boolean DEFAULT_MANDATORY = true;

    /** System property to enable strict AMQP compliance. */
    public static final String STRICT_AMQP = "STRICT_AMQP";

    /** Strict AMQP default setting. */
    public static final String STRICT_AMQP_DEFAULT = "false";

    /** System property to enable failure if strict AMQP compliance is violated. */
    public static final String STRICT_AMQP_FATAL = "STRICT_AMQP_FATAL";

    /** Strickt AMQP failure default. */
    public static final String STRICT_AMQP_FATAL_DEFAULT = "true";

    /** System property to enable immediate message prefetching. */
    public static final String IMMEDIATE_PREFETCH = "IMMEDIATE_PREFETCH";

    /** Immediate message prefetch default. */
    public static final String IMMEDIATE_PREFETCH_DEFAULT = "false";

    /** The connection to which this session belongs. */
    private AMQConnection _connection;

    /** Used to indicate whether or not this is a transactional session. */
    private boolean _transacted;

    /** Holds the sessions acknowledgement mode. */
    private int _acknowledgeMode;

    /** Holds this session unique identifier, used to distinguish it from other sessions. */
    private int _channelId;

    /** @todo This does not appear to be set? */
    private int _ticket;

    /** Holds the high mark for prefetched message, at which the session is suspended. */
    private int _defaultPrefetchHighMark = DEFAULT_PREFETCH_HIGH_MARK;

    /** Holds the low mark for prefetched messages, below which the session is resumed. */
    private int _defaultPrefetchLowMark = DEFAULT_PREFETCH_LOW_MARK;

    /** Holds the message listener, if any, which is attached to this session. */
    private MessageListener _messageListener = null;

    /** Used to indicate that this session has been started at least once. */
    private AtomicBoolean _startedAtLeastOnce = new AtomicBoolean(false);

    /**
     * Used to reference durable subscribers so that requests for unsubscribe can be handled correctly.  Note this only
     * keeps a record of subscriptions which have been created in the current instance. It does not remember
     * subscriptions between executions of the client.
     */
    private final ConcurrentHashMap<String, TopicSubscriberAdaptor> _subscriptions =
            new ConcurrentHashMap<String, TopicSubscriberAdaptor>();

    /**
     * Holds a mapping from message consumers to their identifying names, so that their subscriptions may be looked
     * up in the {@link #_subscriptions} map.
     */
    private final ConcurrentHashMap<BasicMessageConsumer, String> _reverseSubscriptionMap =
            new ConcurrentHashMap<BasicMessageConsumer, String>();

    /**
     * Used to hold incoming messages.
     *
     * @todo Weaken the type once {@link FlowControllingBlockingQueue} implements Queue.
     */
    private final FlowControllingBlockingQueue _queue;

    /**
     * Holds the highest received delivery tag.
     */
    private final AtomicLong _highestDeliveryTag = new AtomicLong(-1);

    /** Holds the dispatcher thread for this session. */
    private Dispatcher _dispatcher;

    /** Holds the message factory factory for this session. */
    private MessageFactoryRegistry _messageFactoryRegistry;

    /** Holds all of the producers created by this session, keyed by their unique identifiers. */
    private Map<Long, MessageProducer> _producers = new ConcurrentHashMap<Long, MessageProducer>();

    /**
     * Used as a source of unique identifiers so that the consumers can be tagged to match them to BasicConsume methods.
     */
    private int _nextTag = 1;

    /**
     * Maps from identifying tags to message consumers, in order to pass dispatch incoming messages to the right
     * consumer.
     */
    private Map<AMQShortString, BasicMessageConsumer> _consumers =
            new ConcurrentHashMap<AMQShortString, BasicMessageConsumer>();

    /** Provides a count of consumers on destinations, in order to be able to know if a destination has consumers. */
    private ConcurrentHashMap<Destination, AtomicInteger> _destinationConsumerCount =
            new ConcurrentHashMap<Destination, AtomicInteger>();

    /**
     * Used as a source of unique identifiers for producers within the session.
     *
     * <p/> Access to this id does not require to be synchronized since according to the JMS specification only one
     * thread of control is allowed to create producers for any given session instance.
     */
    private long _nextProducerId;

    /**
     * Set when recover is called. This is to handle the case where recover() is called by application code during
     * onMessage() processing to enure that an auto ack is not sent.
     */
    private boolean _inRecovery;

    /** Used to indicates that the connection to which this session belongs, has been stopped. */
    private boolean _connectionStopped;

    /** Used to indicate that this session has a message listener attached to it. */
    private boolean _hasMessageListeners;

    /** Used to indicate that this session has been suspended. */
    private boolean _suspended;

    /**
     * Used to protect the suspension of this session, so that critical code can be executed during suspension,
     * without the session being resumed by other threads.
     */
    private final Object _suspensionLock = new Object();

    /**
     * Used to ensure that onlt the first call to start the dispatcher can unsuspend the channel.
     *
     * @todo This is accessed only within a synchronized method, so does not need to be atomic.
     */
    private final AtomicBoolean _firstDispatcher = new AtomicBoolean(true);

    /** Used to indicate that the session should start pre-fetching messages as soon as it is started. */
    private final boolean _immediatePrefetch;

    /** Indicates that warnings should be generated on violations of the strict AMQP. */
    private final boolean _strictAMQP;

    /** Indicates that runtime exceptions should be generated on vilations of the strict AMQP. */
    private final boolean _strictAMQPFATAL;
    private final Object _messageDeliveryLock = new Object();

    /**
     * Creates a new session on a connection.
     *
     * @param con                     The connection on which to create the session.
     * @param channelId               The unique identifier for the session.
     * @param transacted              Indicates whether or not the session is transactional.
     * @param acknowledgeMode         The acknoledgement mode for the session.
     * @param messageFactoryRegistry  The message factory factory for the session.
     * @param defaultPrefetchHighMark The maximum number of messages to prefetched before suspending the session.
     * @param defaultPrefetchLowMark  The number of prefetched messages at which to resume the session.
     */
    AMQSession(AMQConnection con, int channelId, boolean transacted, int acknowledgeMode,
               MessageFactoryRegistry messageFactoryRegistry, int defaultPrefetchHighMark, int defaultPrefetchLowMark)
    {

        _strictAMQP = Boolean.parseBoolean(System.getProperties().getProperty(STRICT_AMQP, STRICT_AMQP_DEFAULT));
        _strictAMQPFATAL =
                Boolean.parseBoolean(System.getProperties().getProperty(STRICT_AMQP_FATAL, STRICT_AMQP_FATAL_DEFAULT));
        _immediatePrefetch =
                _strictAMQP
                || Boolean.parseBoolean(System.getProperties().getProperty(IMMEDIATE_PREFETCH, IMMEDIATE_PREFETCH_DEFAULT));

        _connection = con;
        _transacted = transacted;
        if (transacted)
        {
            _acknowledgeMode = javax.jms.Session.SESSION_TRANSACTED;
        }
        else
        {
            _acknowledgeMode = acknowledgeMode;
        }

        _channelId = channelId;
        _messageFactoryRegistry = messageFactoryRegistry;
        _defaultPrefetchHighMark = defaultPrefetchHighMark;
        _defaultPrefetchLowMark = defaultPrefetchLowMark;

        if (_acknowledgeMode == NO_ACKNOWLEDGE)
        {
            _queue =
                    new FlowControllingBlockingQueue(_defaultPrefetchHighMark, _defaultPrefetchLowMark,
                                                     new FlowControllingBlockingQueue.ThresholdListener()
                                                     {
                                                         public void aboveThreshold(int currentValue)
                                                         {
                                                             if (_acknowledgeMode == NO_ACKNOWLEDGE)
                                                             {
                                                                 _logger.debug(
                                                                         "Above threshold(" + _defaultPrefetchHighMark
                                                                         + ") so suspending channel. Current value is " + currentValue);
                                                                 new Thread(new SuspenderRunner(true)).start();
                                                             }
                                                         }

                                                         public void underThreshold(int currentValue)
                                                         {
                                                             if (_acknowledgeMode == NO_ACKNOWLEDGE)
                                                             {
                                                                 _logger.debug(
                                                                         "Below threshold(" + _defaultPrefetchLowMark
                                                                         + ") so unsuspending channel. Current value is " + currentValue);
                                                                 new Thread(new SuspenderRunner(false)).start();
                                                             }
                                                         }
                                                     });
        }
        else
        {
            _queue = new FlowControllingBlockingQueue(_defaultPrefetchHighMark, null);
        }
    }

    /**
     * Creates a new session on a connection with the default message factory factory.
     *
     * @param con                     The connection on which to create the session.
     * @param channelId               The unique identifier for the session.
     * @param transacted              Indicates whether or not the session is transactional.
     * @param acknowledgeMode         The acknoledgement mode for the session.
     * @param defaultPrefetchHigh     The maximum number of messages to prefetched before suspending the session.
     * @param defaultPrefetchLow      The number of prefetched messages at which to resume the session.
     */
    AMQSession(AMQConnection con, int channelId, boolean transacted, int acknowledgeMode, int defaultPrefetchHigh,
               int defaultPrefetchLow)
    {
        this(con, channelId, transacted, acknowledgeMode, MessageFactoryRegistry.newDefaultRegistry(), defaultPrefetchHigh,
             defaultPrefetchLow);
    }

    // ===== JMS Session methods.

    /**
     * Closes the session with no timeout.
     *
     * @throws JMSException If the JMS provider fails to close the session due to some internal error.
     */
    public void close() throws JMSException
    {
        close(-1);
    }

    public BytesMessage createBytesMessage() throws JMSException
    {
        synchronized (_connection.getFailoverMutex())
        {
            checkNotClosed();

            return new JMSBytesMessage();
        }
    }

    /**
     * Acknowledges all unacknowledged messages on the session, for all message consumers on the session.
     *
     * @throws IllegalStateException If the session is closed.
     */
    public void acknowledge() throws IllegalStateException
    {
        if (isClosed())
        {
            throw new IllegalStateException("Session is already closed");
        }

        for (BasicMessageConsumer consumer : _consumers.values())
        {
            consumer.acknowledge();
        }
    }

    /**
     * Acknowledge one or many messages.
     *
     * @param deliveryTag The tag of the last message to be acknowledged.
     * @param multiple    <tt>true</tt> to acknowledge all messages up to and including the one specified by the
     *                    delivery tag, <tt>false</tt> to just acknowledge that message.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    public void acknowledgeMessage(long deliveryTag, boolean multiple)
    {
        final AMQFrame ackFrame =
                BasicAckBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(), deliveryTag,
                                            multiple);

        if (_logger.isDebugEnabled())
        {
            _logger.debug("Sending ack for delivery tag " + deliveryTag + " on channel " + _channelId);
        }

        getProtocolHandler().writeFrame(ackFrame);
    }

    /**
     * Binds the named queue, with the specified routing key, to the named exchange.
     *
     * <p/>Note that this operation automatically retries in the event of fail-over.
     *
     * @param queueName    The name of the queue to bind.
     * @param routingKey   The routing key to bind the queue with.
     * @param arguments    Additional arguments.
     * @param exchangeName The exchange to bind the queue on.
     *
     * @throws AMQException If the queue cannot be bound for any reason.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     *
     * @todo Document the additional arguments that may be passed in the field table. Are these for headers exchanges?
     */
    public void bindQueue(final AMQShortString queueName, final AMQShortString routingKey, final FieldTable arguments,
                          final AMQShortString exchangeName) throws AMQException
    {
        /*new FailoverRetrySupport<Object, AMQException>(new FailoverProtectedOperation<Object, AMQException>()*/
        new FailoverNoopSupport<Object, AMQException>(new FailoverProtectedOperation<Object, AMQException>()
        {
            public Object execute() throws AMQException, FailoverException
            {
                AMQFrame queueBind =
                        QueueBindBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(),
                                                     arguments, // arguments
                                                     exchangeName, // exchange
                                                     false, // nowait
                                                     queueName, // queue
                                                     routingKey, // routingKey
                                                     getTicket()); // ticket

                getProtocolHandler().syncWrite(queueBind, QueueBindOkBody.class);

                return null;
            }
        }, _connection).execute();
    }

    /**
     * Closes the session.
     *
     * <p/>Note that this operation succeeds automatically if a fail-over interupts the sycnronous request to close
     * the channel. This is because the channel is marked as closed before the request to close it is made, so the
     * fail-over should not re-open it.
     *
     * @param timeout The timeout in milliseconds to wait for the session close acknoledgement from the broker.
     *
     * @throws JMSException If the JMS provider fails to close the session due to some internal error.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     *
     * @todo Not certain about the logic of ignoring the failover exception, because the channel won't be
     *       re-opened. May need to examine this more carefully.
     *
     * @todo Note that taking the failover mutex doesn't prevent this operation being interrupted by a failover,
     *       because the failover process sends the failover event before acquiring the mutex itself.
     */
    public void close(long timeout) throws JMSException
    {
        if (_logger.isInfoEnabled())
        {
            _logger.info("Closing session: " + this + ":"
                         + Arrays.asList(Thread.currentThread().getStackTrace()).subList(3, 6));
        }

        synchronized (_messageDeliveryLock)
        {

            // We must close down all producers and consumers in an orderly fashion. This is the only method
            // that can be called from a different thread of control from the one controlling the session.
            synchronized (_connection.getFailoverMutex())
            {
                // Ensure we only try and close an open session.
                if (!_closed.getAndSet(true))
                {
                    // we pass null since this is not an error case
                    closeProducersAndConsumers(null);

                    try
                    {

                        getProtocolHandler().closeSession(this);

                        final AMQFrame frame =
                                ChannelCloseBody.createAMQFrame(getChannelId(), getProtocolMajorVersion(), getProtocolMinorVersion(),
                                                                0, // classId
                                                                0, // methodId
                                                                AMQConstant.REPLY_SUCCESS.getCode(), // replyCode
                                                                new AMQShortString("JMS client closing channel")); // replyText

                        getProtocolHandler().syncWrite(frame, ChannelCloseOkBody.class, timeout);

                        // When control resumes at this point, a reply will have been received that
                        // indicates the broker has closed the channel successfully.
                    }
                    catch (AMQException e)
                    {
                        JMSException jmse = new JMSException("Error closing session: " + e);
                        jmse.setLinkedException(e);
                        throw jmse;
                    }
                    // This is ignored because the channel is already marked as closed so the fail-over process will
                    // not re-open it.
                    catch (FailoverException e)
                    {
                        _logger.debug(
                                "Got FailoverException during channel close, ignored as channel already marked as closed.");
                    }
                    finally
                    {
                        _connection.deregisterSession(_channelId);
                    }
                }
            }
        }
    }

    /**
     * Called when the server initiates the closure of the session unilaterally.
     *
     * @param e the exception that caused this session to be closed. Null causes the
     */
    public void closed(Throwable e) throws JMSException
    {
        synchronized (_messageDeliveryLock)
        {
            synchronized (_connection.getFailoverMutex())
            {
                // An AMQException has an error code and message already and will be passed in when closure occurs as a
                // result of a channel close request
                _closed.set(true);
                AMQException amqe;
                if (e instanceof AMQException)
                {
                    amqe = (AMQException) e;
                }
                else
                {
                    amqe = new AMQException("Closing session forcibly", e);
                }

                _connection.deregisterSession(_channelId);
                closeProducersAndConsumers(amqe);
            }
        }
    }

    /**
     * Commits all messages done in this transaction and releases any locks currently held.
     *
     * <p/>If the commit fails, because the commit itself is interrupted by a fail-over between requesting that the
     * commit be done, and receiving an acknowledgement that it has been done, then a JMSException will be thrown.
     * The client will be unable to determine whether or not the commit actually happened on the broker in this case.
     *
     * @throws JMSException If the JMS provider fails to commit the transaction due to some internal error. This does
     *                      not mean that the commit is known to have failed, merely that it is not known whether it
     *                      failed or not.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    public void commit() throws JMSException
    {
        checkTransacted();

        try
        {
            // Acknowledge up to message last delivered (if any) for each consumer.
            // need to send ack for messages delivered to consumers so far
            for (Iterator<BasicMessageConsumer> i = _consumers.values().iterator(); i.hasNext();)
            {
                // Sends acknowledgement to server
                i.next().acknowledgeLastDelivered();
            }

            // Commits outstanding messages sent and outstanding acknowledgements.
            final AMQProtocolHandler handler = getProtocolHandler();

            handler.syncWrite(TxCommitBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion()),
                              TxCommitOkBody.class);
        }
        catch (AMQException e)
        {
            throw new JMSAMQException("Failed to commit: " + e.getMessage(), e);
        }
        catch (FailoverException e)
        {
            throw new JMSAMQException("Fail-over interrupted commit. Status of the commit is uncertain.", e);
        }
    }

    public void confirmConsumerCancelled(AMQShortString consumerTag)
    {

        // Remove the consumer from the map
        BasicMessageConsumer consumer = (BasicMessageConsumer) _consumers.get(consumerTag);
        if (consumer != null)
        {
            // fixme this isn't right.. needs to check if _queue contains data for this consumer
            if (consumer.isAutoClose()) // && _queue.isEmpty())
            {
                consumer.closeWhenNoMessages(true);
            }

            if (!consumer.isNoConsume())
            {
                // Clean the Maps up first
                // Flush any pending messages for this consumerTag
                if (_dispatcher != null)
                {
                    _logger.info("Dispatcher is not null");
                }
                else
                {
                    _logger.info("Dispatcher is null so created stopped dispatcher");

                    startDistpatcherIfNecessary(true);
                }

                _dispatcher.rejectPending(consumer);
            }
            else
            {
                // Just close the consumer
                // fixme  the CancelOK is being processed before the arriving messages..
                // The dispatcher is still to process them so the server sent in order but the client
                // has yet to receive before the close comes in.

                // consumer.markClosed();
            }
        }
        else
        {
            _logger.warn("Unable to confirm cancellation of consumer (" + consumerTag + "). Not found in consumer map.");
        }

    }

    public QueueBrowser createBrowser(Queue queue) throws JMSException
    {
        if (isStrictAMQP())
        {
            throw new UnsupportedOperationException();
        }

        return createBrowser(queue, null);
    }

    public QueueBrowser createBrowser(Queue queue, String messageSelector) throws JMSException
    {
        if (isStrictAMQP())
        {
            throw new UnsupportedOperationException();
        }

        checkNotClosed();
        checkValidQueue(queue);

        return new AMQQueueBrowser(this, (AMQQueue) queue, messageSelector);
    }

    public MessageConsumer createBrowserConsumer(Destination destination, String messageSelector, boolean noLocal)
            throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, _defaultPrefetchHighMark, _defaultPrefetchLowMark, noLocal, false,
                                  messageSelector, null, true, true);
    }

    public MessageConsumer createConsumer(Destination destination) throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, _defaultPrefetchHighMark, _defaultPrefetchLowMark, false, false, null, null,
                                  false, false);
    }

    public MessageConsumer createConsumer(Destination destination, String messageSelector) throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, _defaultPrefetchHighMark, _defaultPrefetchLowMark, false, false,
                                  messageSelector, null, false, false);
    }

    public MessageConsumer createConsumer(Destination destination, String messageSelector, boolean noLocal)
            throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, _defaultPrefetchHighMark, _defaultPrefetchLowMark, noLocal, false,
                                  messageSelector, null, false, false);
    }

    public MessageConsumer createConsumer(Destination destination, int prefetch, boolean noLocal, boolean exclusive,
                                          String selector) throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, prefetch, prefetch, noLocal, exclusive, selector, null, false, false);
    }

    public MessageConsumer createConsumer(Destination destination, int prefetchHigh, int prefetchLow, boolean noLocal,
                                          boolean exclusive, String selector) throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, prefetchHigh, prefetchLow, noLocal, exclusive, selector, null, false, false);
    }

    public MessageConsumer createConsumer(Destination destination, int prefetch, boolean noLocal, boolean exclusive,
                                          String selector, FieldTable rawSelector) throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, prefetch, prefetch, noLocal, exclusive, selector, rawSelector, false, false);
    }

    public MessageConsumer createConsumer(Destination destination, int prefetchHigh, int prefetchLow, boolean noLocal,
                                          boolean exclusive, String selector, FieldTable rawSelector) throws JMSException
    {
        checkValidDestination(destination);

        return createConsumerImpl(destination, prefetchHigh, prefetchLow, noLocal, exclusive, selector, rawSelector, false,
                                  false);
    }

    public TopicSubscriber createDurableSubscriber(Topic topic, String name) throws JMSException
    {

        checkNotClosed();
        AMQTopic origTopic = checkValidTopic(topic);
        AMQTopic dest = AMQTopic.createDurableTopic(origTopic, name, _connection);
        TopicSubscriberAdaptor subscriber = _subscriptions.get(name);
        if (subscriber != null)
        {
            if (subscriber.getTopic().equals(topic))
            {
                throw new IllegalStateException("Already subscribed to topic " + topic + " with subscription exchange "
                                                + name);
            }
            else
            {
                unsubscribe(name);
            }
        }
        else
        {
            AMQShortString topicName;
            if (topic instanceof AMQTopic)
            {
                topicName = ((AMQTopic) topic).getDestinationName();
            }
            else
            {
                topicName = new AMQShortString(topic.getTopicName());
            }

            if (_strictAMQP)
            {
                if (_strictAMQPFATAL)
                {
                    throw new UnsupportedOperationException("JMS Durable not currently supported by AMQP.");
                }
                else
                {
                    _logger.warn("Unable to determine if subscription already exists for '" + topicName + "' "
                                 + "for creation durableSubscriber. Requesting queue deletion regardless.");
                }

                deleteQueue(dest.getAMQQueueName());
            }
            else
            {
                // if the queue is bound to the exchange but NOT for this topic, then the JMS spec
                // says we must trash the subscription.
                if (isQueueBound(dest.getExchangeName(), dest.getAMQQueueName())
                    && !isQueueBound(dest.getExchangeName(), dest.getAMQQueueName(), topicName))
                {
                    deleteQueue(dest.getAMQQueueName());
                }
            }
        }

        subscriber = new TopicSubscriberAdaptor(dest, (BasicMessageConsumer) createConsumer(dest));

        _subscriptions.put(name, subscriber);
        _reverseSubscriptionMap.put(subscriber.getMessageConsumer(), name);

        return subscriber;
    }

    /** Note, currently this does not handle reuse of the same name with different topics correctly. */
    public TopicSubscriber createDurableSubscriber(Topic topic, String name, String messageSelector, boolean noLocal)
            throws JMSException
    {
        checkNotClosed();
        checkValidTopic(topic);
        AMQTopic dest = AMQTopic.createDurableTopic((AMQTopic) topic, name, _connection);
        BasicMessageConsumer consumer = (BasicMessageConsumer) createConsumer(dest, messageSelector, noLocal);
        TopicSubscriberAdaptor subscriber = new TopicSubscriberAdaptor(dest, consumer);
        _subscriptions.put(name, subscriber);
        _reverseSubscriptionMap.put(subscriber.getMessageConsumer(), name);

        return subscriber;
    }

    public MapMessage createMapMessage() throws JMSException
    {
        synchronized (_connection.getFailoverMutex())
        {
            checkNotClosed();

            return new JMSMapMessage();
        }
    }

    public javax.jms.Message createMessage() throws JMSException
    {
        return createBytesMessage();
    }

    public ObjectMessage createObjectMessage() throws JMSException
    {
        synchronized (_connection.getFailoverMutex())
        {
            checkNotClosed();

            return (ObjectMessage) new JMSObjectMessage();
        }
    }

    public ObjectMessage createObjectMessage(Serializable object) throws JMSException
    {
        ObjectMessage msg = createObjectMessage();
        msg.setObject(object);

        return msg;
    }

    public BasicMessageProducer createProducer(Destination destination) throws JMSException
    {
        return createProducerImpl(destination, DEFAULT_MANDATORY, DEFAULT_IMMEDIATE);
    }

    public BasicMessageProducer createProducer(Destination destination, boolean immediate) throws JMSException
    {
        return createProducerImpl(destination, DEFAULT_MANDATORY, immediate);
    }

    public BasicMessageProducer createProducer(Destination destination, boolean mandatory, boolean immediate)
            throws JMSException
    {
        return createProducerImpl(destination, mandatory, immediate);
    }

    public BasicMessageProducer createProducer(Destination destination, boolean mandatory, boolean immediate,
                                               boolean waitUntilSent) throws JMSException
    {
        return createProducerImpl(destination, mandatory, immediate, waitUntilSent);
    }

    public TopicPublisher createPublisher(Topic topic) throws JMSException
    {
        checkNotClosed();

        return new TopicPublisherAdapter((BasicMessageProducer) createProducer(topic), topic);
    }

    public Queue createQueue(String queueName) throws JMSException
    {
        checkNotClosed();
        if (queueName.indexOf('/') == -1)
        {
            return new AMQQueue(getDefaultQueueExchangeName(), new AMQShortString(queueName));
        }
        else
        {
            try
            {
                return new AMQQueue(new AMQBindingURL(queueName));
            }
            catch (URLSyntaxException urlse)
            {
                JMSException jmse = new JMSException(urlse.getReason());
                jmse.setLinkedException(urlse);

                throw jmse;
            }
        }
    }

    /**
     * Declares the named queue.
     *
     * <p/>Note that this operation automatically retries in the event of fail-over.
     *
     * @param name       The name of the queue to declare.
     * @param autoDelete
     * @param durable    Flag to indicate that the queue is durable.
     * @param exclusive  Flag to indicate that the queue is exclusive to this client.
     *
     * @throws AMQException If the queue cannot be declared for any reason.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    public void createQueue(final AMQShortString name, final boolean autoDelete, final boolean durable,
                            final boolean exclusive) throws AMQException
    {
        new FailoverRetrySupport<Object, AMQException>(new FailoverProtectedOperation<Object, AMQException>()
        {
            public Object execute() throws AMQException, FailoverException
            {
                AMQFrame queueDeclare =
                        QueueDeclareBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(),
                                                        null, // arguments
                                                        autoDelete, // autoDelete
                                                        durable, // durable
                                                        exclusive, // exclusive
                                                        false, // nowait
                                                        false, // passive
                                                        name, // queue
                                                        getTicket()); // ticket

                getProtocolHandler().syncWrite(queueDeclare, QueueDeclareOkBody.class);

                return null;
            }
        }, _connection).execute();
    }

    /**
     * Creates a QueueReceiver
     *
     * @param destination
     *
     * @return QueueReceiver - a wrapper around our MessageConsumer
     *
     * @throws JMSException
     */
    public QueueReceiver createQueueReceiver(Destination destination) throws JMSException
    {
        checkValidDestination(destination);
        AMQQueue dest = (AMQQueue) destination;
        BasicMessageConsumer consumer = (BasicMessageConsumer) createConsumer(destination);

        return new QueueReceiverAdaptor(dest, consumer);
    }

    /**
     * Creates a QueueReceiver using a message selector
     *
     * @param destination
     * @param messageSelector
     *
     * @return QueueReceiver - a wrapper around our MessageConsumer
     *
     * @throws JMSException
     */
    public QueueReceiver createQueueReceiver(Destination destination, String messageSelector) throws JMSException
    {
        checkValidDestination(destination);
        AMQQueue dest = (AMQQueue) destination;
        BasicMessageConsumer consumer = (BasicMessageConsumer) createConsumer(destination, messageSelector);

        return new QueueReceiverAdaptor(dest, consumer);
    }

    /**
     * Creates a QueueReceiver wrapping a MessageConsumer
     *
     * @param queue
     *
     * @return QueueReceiver
     *
     * @throws JMSException
     */
    public QueueReceiver createReceiver(Queue queue) throws JMSException
    {
        checkNotClosed();
        AMQQueue dest = (AMQQueue) queue;
        BasicMessageConsumer consumer = (BasicMessageConsumer) createConsumer(dest);

        return new QueueReceiverAdaptor(dest, consumer);
    }

    /**
     * Creates a QueueReceiver wrapping a MessageConsumer using a message selector
     *
     * @param queue
     * @param messageSelector
     *
     * @return QueueReceiver
     *
     * @throws JMSException
     */
    public QueueReceiver createReceiver(Queue queue, String messageSelector) throws JMSException
    {
        checkNotClosed();
        AMQQueue dest = (AMQQueue) queue;
        BasicMessageConsumer consumer = (BasicMessageConsumer) createConsumer(dest, messageSelector);

        return new QueueReceiverAdaptor(dest, consumer);
    }

    public QueueSender createSender(Queue queue) throws JMSException
    {
        checkNotClosed();

        // return (QueueSender) createProducer(queue);
        return new QueueSenderAdapter(createProducer(queue), queue);
    }

    public StreamMessage createStreamMessage() throws JMSException
    {
        synchronized (_connection.getFailoverMutex())
        {
            checkNotClosed();

            return new JMSStreamMessage();
        }
    }

    /**
     * Creates a non-durable subscriber
     *
     * @param topic
     *
     * @return TopicSubscriber - a wrapper round our MessageConsumer
     *
     * @throws JMSException
     */
    public TopicSubscriber createSubscriber(Topic topic) throws JMSException
    {
        checkNotClosed();
        AMQTopic dest = checkValidTopic(topic);

        // AMQTopic dest = new AMQTopic(topic.getTopicName());
        return new TopicSubscriberAdaptor(dest, (BasicMessageConsumer) createConsumer(dest));
    }

    /**
     * Creates a non-durable subscriber with a message selector
     *
     * @param topic
     * @param messageSelector
     * @param noLocal
     *
     * @return TopicSubscriber - a wrapper round our MessageConsumer
     *
     * @throws JMSException
     */
    public TopicSubscriber createSubscriber(Topic topic, String messageSelector, boolean noLocal) throws JMSException
    {
        checkNotClosed();
        AMQTopic dest = checkValidTopic(topic);

        // AMQTopic dest = new AMQTopic(topic.getTopicName());
        return new TopicSubscriberAdaptor(dest, (BasicMessageConsumer) createConsumer(dest, messageSelector, noLocal));
    }

    public TemporaryQueue createTemporaryQueue() throws JMSException
    {
        checkNotClosed();

        return new AMQTemporaryQueue(this);
    }

    public TemporaryTopic createTemporaryTopic() throws JMSException
    {
        checkNotClosed();

        return new AMQTemporaryTopic(this);
    }

    public TextMessage createTextMessage() throws JMSException
    {
        synchronized (_connection.getFailoverMutex())
        {
            checkNotClosed();

            return new JMSTextMessage();
        }
    }

    public TextMessage createTextMessage(String text) throws JMSException
    {

        TextMessage msg = createTextMessage();
        msg.setText(text);

        return msg;
    }

    public Topic createTopic(String topicName) throws JMSException
    {
        checkNotClosed();

        if (topicName.indexOf('/') == -1)
        {
            return new AMQTopic(getDefaultTopicExchangeName(), new AMQShortString(topicName));
        }
        else
        {
            try
            {
                return new AMQTopic(new AMQBindingURL(topicName));
            }
            catch (URLSyntaxException urlse)
            {
                JMSException jmse = new JMSException(urlse.getReason());
                jmse.setLinkedException(urlse);

                throw jmse;
            }
        }
    }

    public void declareExchange(AMQShortString name, AMQShortString type, boolean nowait) throws AMQException
    {
        declareExchange(name, type, getProtocolHandler(), nowait);
    }

    public int getAcknowledgeMode() throws JMSException
    {
        checkNotClosed();

        return _acknowledgeMode;
    }

    public AMQConnection getAMQConnection()
    {
        return _connection;
    }

    public int getChannelId()
    {
        return _channelId;
    }

    public int getDefaultPrefetch()
    {
        return _defaultPrefetchHighMark;
    }

    public int getDefaultPrefetchHigh()
    {
        return _defaultPrefetchHighMark;
    }

    public int getDefaultPrefetchLow()
    {
        return _defaultPrefetchLowMark;
    }

    public AMQShortString getDefaultQueueExchangeName()
    {
        return _connection.getDefaultQueueExchangeName();
    }

    public AMQShortString getDefaultTopicExchangeName()
    {
        return _connection.getDefaultTopicExchangeName();
    }

    public MessageListener getMessageListener() throws JMSException
    {
        // checkNotClosed();
        return _messageListener;
    }

    public AMQShortString getTemporaryQueueExchangeName()
    {
        return _connection.getTemporaryQueueExchangeName();
    }

    public AMQShortString getTemporaryTopicExchangeName()
    {
        return _connection.getTemporaryTopicExchangeName();
    }

    public int getTicket()
    {
        return _ticket;
    }

    public boolean getTransacted() throws JMSException
    {
        checkNotClosed();

        return _transacted;
    }

    public boolean hasConsumer(Destination destination)
    {
        AtomicInteger counter = _destinationConsumerCount.get(destination);

        return (counter != null) && (counter.get() != 0);
    }

    public boolean isStrictAMQP()
    {
        return _strictAMQP;
    }

    public boolean isSuspended()
    {
        return _suspended;
    }

    /**
     * Invoked by the MINA IO thread (indirectly) when a message is received from the transport. Puts the message onto
     * the queue read by the dispatcher.
     *
     * @param message the message that has been received
     */
    public void messageReceived(UnprocessedMessage message)
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug("Message["
                          + ((message.getDeliverBody() == null) ? ("B:" + message.getBounceBody()) : ("D:" + message.getDeliverBody()))
                          + "] received in session with channel id " + _channelId);
        }

        if (message.getDeliverBody() == null)
        {
            // Return of the bounced message.
            returnBouncedMessage(message);
        }
        else
        {
            _highestDeliveryTag.set(message.getDeliverBody().deliveryTag);
            _queue.add(message);
        }
    }

    /**
     * Stops message delivery in this session, and restarts message delivery with the oldest unacknowledged message.
     *
     * <p/>All consumers deliver messages in a serial order. Acknowledging a received message automatically acknowledges all
     * messages that have been delivered to the client.
     *
     * <p/>Restarting a session causes it to take the following actions:
     *
     * <ul>
     * <li>Stop message delivery.</li>
     * <li>Mark all messages that might have been delivered but not acknowledged as "redelivered".
     * <li>Restart the delivery sequence including all unacknowledged messages that had been previously delivered.
     *     Redelivered messages do not have to be delivered in exactly their original delivery order.</li>
     * </ul>
     *
     * <p/>If the recover operation is interrupted by a fail-over, between asking that the broker begin recovery and
     * receiving acknolwedgement that it hasm then a JMSException will be thrown. In this case it will not be possible
     * for the client to determine whether the broker is going to recover the session or not.
     *
     * @throws JMSException If the JMS provider fails to stop and restart message delivery due to some internal error.
     *                      Not that this does not necessarily mean that the recovery has failed, but simply that it
     *                      is not possible to tell if it has or not.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    public void recover() throws JMSException
    {
        // Ensure that the session is open.
        checkNotClosed();

        // Ensure that the session is not transacted.
        checkNotTransacted();

        // this is set only here, and the before the consumer's onMessage is called it is set to false
        _inRecovery = true;
        try
        {

            boolean isSuspended = isSuspended();

            if (!isSuspended)
            {
                suspendChannel(true);
            }

            for (BasicMessageConsumer consumer : _consumers.values())
            {
                consumer.clearUnackedMessages();
            }

            if (_dispatcher != null)
            {
                _dispatcher.rollback();
            }

            if (isStrictAMQP())
            {
                // We can't use the BasicRecoverBody-OK method as it isn't part of the spec.
                _connection.getProtocolHandler().writeFrame(BasicRecoverBody.createAMQFrame(_channelId,
                                                                                            getProtocolMajorVersion(), getProtocolMinorVersion(), false)); // requeue
                _logger.warn("Session Recover cannot be guaranteed with STRICT_AMQP. Messages may arrive out of order.");
            }
            else
            {

                _connection.getProtocolHandler().syncWrite(
                        BasicRecoverBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(), false) // requeue
                        , BasicRecoverOkBody.class);
            }

            if (!isSuspended)
            {
                suspendChannel(false);
            }
        }
        catch (AMQException e)
        {
            throw new JMSAMQException("Recover failed: " + e.getMessage(), e);
        }
        catch (FailoverException e)
        {
            throw new JMSAMQException("Recovery was interrupted by fail-over. Recovery status is not known.", e);
        }
    }

    public void rejectMessage(UnprocessedMessage message, boolean requeue)
    {

        if (_logger.isTraceEnabled())
        {
            _logger.trace("Rejecting Unacked message:" + message.getDeliverBody().deliveryTag);
        }

        rejectMessage(message.getDeliverBody().deliveryTag, requeue);
    }

    public void rejectMessage(AbstractJMSMessage message, boolean requeue)
    {
        if (_logger.isTraceEnabled())
        {
            _logger.trace("Rejecting Abstract message:" + message.getDeliveryTag());
        }

        rejectMessage(message.getDeliveryTag(), requeue);

    }

    public void rejectMessage(long deliveryTag, boolean requeue)
    {
        if ((_acknowledgeMode == CLIENT_ACKNOWLEDGE) || (_acknowledgeMode == SESSION_TRANSACTED))
        {
            if (_logger.isDebugEnabled())
            {
                _logger.debug("Rejecting delivery tag:" + deliveryTag);
            }

            AMQFrame basicRejectBody =
                    BasicRejectBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(), deliveryTag,
                                                   requeue);

            _connection.getProtocolHandler().writeFrame(basicRejectBody);
        }
    }

    /**
     * Commits all messages done in this transaction and releases any locks currently held.
     *
     * <p/>If the rollback fails, because the rollback itself is interrupted by a fail-over between requesting that the
     * rollback be done, and receiving an acknowledgement that it has been done, then a JMSException will be thrown.
     * The client will be unable to determine whether or not the rollback actually happened on the broker in this case.
     *
     * @throws JMSException If the JMS provider fails to rollback the transaction due to some internal error. This does
     *                      not mean that the rollback is known to have failed, merely that it is not known whether it
     *                      failed or not.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    public void rollback() throws JMSException
    {
        synchronized (_suspensionLock)
        {
            checkTransacted();

            try
            {
                boolean isSuspended = isSuspended();

                if (!isSuspended)
                {
                    suspendChannel(true);
                }

                if (_dispatcher != null)
                {
                    _dispatcher.rollback();
                }

                _connection.getProtocolHandler().syncWrite(TxRollbackBody.createAMQFrame(_channelId,
                                                                                         getProtocolMajorVersion(), getProtocolMinorVersion()), TxRollbackOkBody.class);

                if (!isSuspended)
                {
                    suspendChannel(false);
                }
            }
            catch (AMQException e)
            {
                throw new JMSAMQException("Failed to rollback: " + e, e);
            }
            catch (FailoverException e)
            {
                throw new JMSAMQException("Fail-over interrupted rollback. Status of the rollback is uncertain.", e);
            }
        }
    }

    public void run()
    {
        throw new java.lang.UnsupportedOperationException();
    }

    public void setMessageListener(MessageListener listener) throws JMSException
    {
        // checkNotClosed();
        //
        // if (_dispatcher != null && !_dispatcher.connectionStopped())
        // {
        // throw new javax.jms.IllegalStateException("Attempt to set listener while session is started.");
        // }
        //
        // // We are stopped
        // for (Iterator<BasicMessageConsumer> i = _consumers.values().iterator(); i.hasNext();)
        // {
        // BasicMessageConsumer consumer = i.next();
        //
        // if (consumer.isReceiving())
        // {
        // throw new javax.jms.IllegalStateException("Another thread is already receiving synchronously.");
        // }
        // }
        //
        // _messageListener = listener;
        //
        // for (Iterator<BasicMessageConsumer> i = _consumers.values().iterator(); i.hasNext();)
        // {
        // i.next().setMessageListener(_messageListener);
        // }

    }

    /*public void setTicket(int ticket)
    {
        _ticket = ticket;
    }*/

    public void unsubscribe(String name) throws JMSException
    {
        checkNotClosed();
        TopicSubscriberAdaptor subscriber = _subscriptions.get(name);
        if (subscriber != null)
        {
            // send a queue.delete for the subscription
            deleteQueue(AMQTopic.getDurableTopicQueueName(name, _connection));
            _subscriptions.remove(name);
            _reverseSubscriptionMap.remove(subscriber);
        }
        else
        {
            if (_strictAMQP)
            {
                if (_strictAMQPFATAL)
                {
                    throw new UnsupportedOperationException("JMS Durable not currently supported by AMQP.");
                }
                else
                {
                    _logger.warn("Unable to determine if subscription already exists for '" + name + "' for unsubscribe."
                                 + " Requesting queue deletion regardless.");
                }

                deleteQueue(AMQTopic.getDurableTopicQueueName(name, _connection));
            }
            else
            {

                if (isQueueBound(getDefaultTopicExchangeName(), AMQTopic.getDurableTopicQueueName(name, _connection)))
                {
                    deleteQueue(AMQTopic.getDurableTopicQueueName(name, _connection));
                }
                else
                {
                    throw new InvalidDestinationException("Unknown subscription exchange:" + name);
                }
            }
        }
    }

    protected MessageConsumer createConsumerImpl(final Destination destination, final int prefetchHigh,
                                                 final int prefetchLow, final boolean noLocal, final boolean exclusive, String selector, final FieldTable rawSelector,
                                                 final boolean noConsume, final boolean autoClose) throws JMSException
    {
        checkTemporaryDestination(destination);

        final String messageSelector;

        if (_strictAMQP && !((selector == null) || selector.equals("")))
        {
            if (_strictAMQPFATAL)
            {
                throw new UnsupportedOperationException("Selectors not currently supported by AMQP.");
            }
            else
            {
                messageSelector = null;
            }
        }
        else
        {
            messageSelector = selector;
        }

        return new FailoverRetrySupport<MessageConsumer, JMSException>(
                new FailoverProtectedOperation<MessageConsumer, JMSException>()
                {
                    public MessageConsumer execute() throws JMSException, FailoverException
                    {
                        checkNotClosed();

                        AMQDestination amqd = (AMQDestination) destination;

                        final AMQProtocolHandler protocolHandler = getProtocolHandler();
                        // TODO: Define selectors in AMQP
                        // TODO: construct the rawSelector from the selector string if rawSelector == null
                        final FieldTable ft = FieldTableFactory.newFieldTable();
                        // if (rawSelector != null)
                        // ft.put("headers", rawSelector.getDataAsBytes());
                        if (rawSelector != null)
                        {
                            ft.addAll(rawSelector);
                        }

                        BasicMessageConsumer consumer =
                                new BasicMessageConsumer(_channelId, _connection, amqd, messageSelector, noLocal,
                                                         _messageFactoryRegistry, AMQSession.this, protocolHandler, ft, prefetchHigh, prefetchLow,
                                                         exclusive, _acknowledgeMode, noConsume, autoClose);

                        if (_messageListener != null)
                        {
                            consumer.setMessageListener(_messageListener);
                        }

                        try
                        {
                            registerConsumer(consumer, false);
                        }
                        catch (AMQInvalidArgumentException ise)
                        {
                            JMSException ex = new InvalidSelectorException(ise.getMessage());
                            ex.setLinkedException(ise);
                            throw ex;
                        }
                        catch (AMQInvalidRoutingKeyException e)
                        {
                            JMSException ide =
                                    new InvalidDestinationException("Invalid routing key:" + amqd.getRoutingKey().toString());
                            ide.setLinkedException(e);
                            throw ide;
                        }
                        catch (AMQException e)
                        {
                            JMSException ex = new JMSException("Error registering consumer: " + e);

                            if (_logger.isDebugEnabled())
                            {
                                e.printStackTrace();
                            }

                            ex.setLinkedException(e);
                            throw ex;
                        }

                        synchronized (destination)
                        {
                            _destinationConsumerCount.putIfAbsent(destination, new AtomicInteger());
                            _destinationConsumerCount.get(destination).incrementAndGet();
                        }

                        return consumer;
                    }
                }, _connection).execute();
    }

    /**
     * Called by the MessageConsumer when closing, to deregister the consumer from the map from consumerTag to consumer
     * instance.
     *
     * @param consumer the consum
     */
    void deregisterConsumer(BasicMessageConsumer consumer)
    {
        if (_consumers.remove(consumer.getConsumerTag()) != null)
        {
            String subscriptionName = _reverseSubscriptionMap.remove(consumer);
            if (subscriptionName != null)
            {
                _subscriptions.remove(subscriptionName);
            }

            Destination dest = consumer.getDestination();
            synchronized (dest)
            {
                if (_destinationConsumerCount.get(dest).decrementAndGet() == 0)
                {
                    _destinationConsumerCount.remove(dest);
                }
            }
        }
    }

    void deregisterProducer(long producerId)
    {
        _producers.remove(new Long(producerId));
    }

    boolean isInRecovery()
    {
        return _inRecovery;
    }

    boolean isQueueBound(AMQShortString exchangeName, AMQShortString queueName) throws JMSException
    {
        return isQueueBound(exchangeName, queueName, null);
    }

    /**
     * Tests whether or not the specified queue is bound to the specified exchange under a particular routing key.
     *
     * <p/>Note that this operation automatically retries in the event of fail-over.
     *
     * @param exchangeName The exchange name to test for binding against.
     * @param queueName    The queue name to check if bound.
     * @param routingKey   The routing key to check if the queue is bound under.
     *
     * @return <tt>true</tt> if the queue is bound to the exchange and routing key, <tt>false</tt> if not.
     *
     * @throws JMSException If the query fails for any reason.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    boolean isQueueBound(final AMQShortString exchangeName, final AMQShortString queueName, final AMQShortString routingKey)
            throws JMSException
    {
        try
        {
            AMQMethodEvent response =
                    new FailoverRetrySupport<AMQMethodEvent, AMQException>(
                            new FailoverProtectedOperation<AMQMethodEvent, AMQException>()
                            {
                                public AMQMethodEvent execute() throws AMQException, FailoverException
                                {
                                    AMQFrame boundFrame =
                                            ExchangeBoundBody.createAMQFrame(_channelId, getProtocolMajorVersion(),
                                                                             getProtocolMinorVersion(), exchangeName, // exchange
                                                                             queueName, // queue
                                                                             routingKey); // routingKey

                                    return getProtocolHandler().syncWrite(boundFrame, ExchangeBoundOkBody.class);

                                }
                            }, _connection).execute();

            // Extract and return the response code from the query.
            ExchangeBoundOkBody responseBody = (ExchangeBoundOkBody) response.getMethod();

            return (responseBody.replyCode == 0);
        }
        catch (AMQException e)
        {
            throw new JMSAMQException("Queue bound query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Called to mark the session as being closed. Useful when the session needs to be made invalid, e.g. after failover
     * when the client has veoted resubscription. <p/> The caller of this method must already hold the failover mutex.
     */
    void markClosed()
    {
        _closed.set(true);
        _connection.deregisterSession(_channelId);
        markClosedProducersAndConsumers();

    }

    /**
     * Resubscribes all producers and consumers. This is called when performing failover.
     *
     * @throws AMQException
     */
    void resubscribe() throws AMQException
    {
        resubscribeProducers();
        resubscribeConsumers();
    }

    void setHasMessageListeners()
    {
        _hasMessageListeners = true;
    }

    void setInRecovery(boolean inRecovery)
    {
        _inRecovery = inRecovery;
    }

    /**
     * Starts the session, which ensures that it is not suspended and that its event dispatcher is running.
     *
     * @throws AMQException If the session cannot be started for any reason.
     *
     * @todo This should be controlled by _stopped as it pairs with the stop method fixme or check the
     *       FlowControlledBlockingQueue _queue to see if we have flow controlled. will result in sending Flow messages
     *       for each subsequent call to flow.. only need to do this if we have called stop.
     */
    void start() throws AMQException
    {
        // Check if the session has perviously been started and suspended, in which case it must be unsuspended.
        if (_startedAtLeastOnce.getAndSet(true))
        {
            suspendChannel(false);
        }

        // If the event dispatcher is not running then start it too.
        if (hasMessageListeners())
        {
            startDistpatcherIfNecessary();
        }
    }

    void startDistpatcherIfNecessary()
    {
        //If we are the dispatcher then we don't need to check we are started
        if (Thread.currentThread() == _dispatcher)
        {
            return;
        }

        // If IMMEDIATE_PREFETCH is not set then we need to start fetching
        // This is final per session so will be multi-thread safe.
        if (!_immediatePrefetch)
        {
            // We do this now if this is the first call on a started connection
            if (isSuspended() && _startedAtLeastOnce.get() && _firstDispatcher.getAndSet(false))
            {
                try
                {
                    suspendChannel(false);
                }
                catch (AMQException e)
                {
                    _logger.info("Unsuspending channel threw an exception:" + e);
                }
            }
        }

        startDistpatcherIfNecessary(false);
    }

    synchronized void startDistpatcherIfNecessary(boolean initiallyStopped)
    {
        if (_dispatcher == null)
        {
            _dispatcher = new Dispatcher();
            _dispatcher.setDaemon(true);
            _dispatcher.setConnectionStopped(initiallyStopped);
            _dispatcher.start();
        }
        else
        {
            _dispatcher.setConnectionStopped(initiallyStopped);
        }
    }

    void stop() throws AMQException
    {
        // Stop the server delivering messages to this session.
        suspendChannel(true);

        if (_dispatcher != null)
        {
            _dispatcher.setConnectionStopped(true);
        }
    }

    /*
     * Binds the named queue, with the specified routing key, to the named exchange.
     *
     * <p/>Note that this operation automatically retries in the event of fail-over.
     *
     * @param queueName    The name of the queue to bind.
     * @param routingKey   The routing key to bind the queue with.
     * @param arguments    Additional arguments.
     * @param exchangeName The exchange to bind the queue on.
     *
     * @throws AMQException If the queue cannot be bound for any reason.
     */
    /*private void bindQueue(AMQDestination amqd, AMQShortString queueName, AMQProtocolHandler protocolHandler, FieldTable ft)
        throws AMQException, FailoverException
    {
        AMQFrame queueBind =
            QueueBindBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(), ft, // arguments
                amqd.getExchangeName(), // exchange
                false, // nowait
                queueName, // queue
                amqd.getRoutingKey(), // routingKey
                getTicket()); // ticket

        protocolHandler.syncWrite(queueBind, QueueBindOkBody.class);
    }*/

    private void checkNotTransacted() throws JMSException
    {
        if (getTransacted())
        {
            throw new IllegalStateException("Session is transacted");
        }
    }

    private void checkTemporaryDestination(Destination destination) throws JMSException
    {
        if ((destination instanceof TemporaryDestination))
        {
            _logger.debug("destination is temporary");
            final TemporaryDestination tempDest = (TemporaryDestination) destination;
            if (tempDest.getSession() != this)
            {
                _logger.debug("destination is on different session");
                throw new JMSException("Cannot consume from a temporary destination created onanother session");
            }

            if (tempDest.isDeleted())
            {
                _logger.debug("destination is deleted");
                throw new JMSException("Cannot consume from a deleted destination");
            }
        }
    }

    private void checkTransacted() throws JMSException
    {
        if (!getTransacted())
        {
            throw new IllegalStateException("Session is not transacted");
        }
    }

    private void checkValidDestination(Destination destination) throws InvalidDestinationException
    {
        if (destination == null)
        {
            throw new javax.jms.InvalidDestinationException("Invalid Queue");
        }
    }

    private void checkValidQueue(Queue queue) throws InvalidDestinationException
    {
        if (queue == null)
        {
            throw new javax.jms.InvalidDestinationException("Invalid Queue");
        }
    }

    /*
     * I could have combined the last 3 methods, but this way it improves readability
     */
    private AMQTopic checkValidTopic(Topic topic) throws JMSException
    {
        if (topic == null)
        {
            throw new javax.jms.InvalidDestinationException("Invalid Topic");
        }

        if ((topic instanceof TemporaryDestination) && (((TemporaryDestination) topic).getSession() != this))
        {
            throw new javax.jms.InvalidDestinationException(
                    "Cannot create a subscription on a temporary topic created in another session");
        }

        if (!(topic instanceof AMQTopic))
        {
            throw new javax.jms.InvalidDestinationException(
                    "Cannot create a subscription on topic created for another JMS Provider, class of topic provided is: "
                    + topic.getClass().getName());
        }

        return (AMQTopic) topic;
    }

    /**
     * Called to close message consumers cleanly. This may or may <b>not</b> be as a result of an error.
     *
     * @param error not null if this is a result of an error occurring at the connection level
     */
    private void closeConsumers(Throwable error) throws JMSException
    {
        if (_dispatcher != null)
        {
            _dispatcher.close();
            _dispatcher = null;
        }
        // we need to clone the list of consumers since the close() method updates the _consumers collection
        // which would result in a concurrent modification exception
        final ArrayList<BasicMessageConsumer> clonedConsumers = new ArrayList<BasicMessageConsumer>(_consumers.values());

        final Iterator<BasicMessageConsumer> it = clonedConsumers.iterator();
        while (it.hasNext())
        {
            final BasicMessageConsumer con = it.next();
            if (error != null)
            {
                con.notifyError(error);
            }
            else
            {
                con.close();
            }
        }
        // at this point the _consumers map will be empty
    }

    /**
     * Called to close message producers cleanly. This may or may <b>not</b> be as a result of an error. There is
     * currently no way of propagating errors to message producers (this is a JMS limitation).
     */
    private void closeProducers() throws JMSException
    {
        // we need to clone the list of producers since the close() method updates the _producers collection
        // which would result in a concurrent modification exception
        final ArrayList clonedProducers = new ArrayList(_producers.values());

        final Iterator it = clonedProducers.iterator();
        while (it.hasNext())
        {
            final BasicMessageProducer prod = (BasicMessageProducer) it.next();
            prod.close();
        }
        // at this point the _producers map is empty
    }

    /**
     * Close all producers or consumers. This is called either in the error case or when closing the session normally.
     *
     * @param amqe the exception, may be null to indicate no error has occurred
     */
    private void closeProducersAndConsumers(AMQException amqe) throws JMSException
    {
        JMSException jmse = null;
        try
        {
            closeProducers();
        }
        catch (JMSException e)
        {
            _logger.error("Error closing session: " + e, e);
            jmse = e;
        }

        try
        {
            closeConsumers(amqe);
        }
        catch (JMSException e)
        {
            _logger.error("Error closing session: " + e, e);
            if (jmse == null)
            {
                jmse = e;
            }
        }

        if (jmse != null)
        {
            throw jmse;
        }
    }

    /**
     * Register to consume from the queue.
     *
     * @param queueName
     */
    private void consumeFromQueue(BasicMessageConsumer consumer, AMQShortString queueName,
                                  AMQProtocolHandler protocolHandler, boolean nowait, String messageSelector) throws AMQException, FailoverException
    {
        // need to generate a consumer tag on the client so we can exploit the nowait flag
        AMQShortString tag = new AMQShortString(Integer.toString(_nextTag++));

        FieldTable arguments = FieldTableFactory.newFieldTable();
        if ((messageSelector != null) && !messageSelector.equals(""))
        {
            arguments.put(AMQPFilterTypes.JMS_SELECTOR.getValue(), messageSelector);
        }

        if (consumer.isAutoClose())
        {
            arguments.put(AMQPFilterTypes.AUTO_CLOSE.getValue(), Boolean.TRUE);
        }

        if (consumer.isNoConsume())
        {
            arguments.put(AMQPFilterTypes.NO_CONSUME.getValue(), Boolean.TRUE);
        }

        consumer.setConsumerTag(tag);
        // we must register the consumer in the map before we actually start listening
        _consumers.put(tag, consumer);

        try
        {
            // TODO: Be aware of possible changes to parameter order as versions change.
            AMQFrame jmsConsume =
                    BasicConsumeBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(), arguments, // arguments
                                                    tag, // consumerTag
                                                    consumer.isExclusive(), // exclusive
                                                    consumer.getAcknowledgeMode() == Session.NO_ACKNOWLEDGE, // noAck
                                                    consumer.isNoLocal(), // noLocal
                                                    nowait, // nowait
                                                    queueName, // queue
                                                    getTicket()); // ticket

            if (nowait)
            {
                protocolHandler.writeFrame(jmsConsume);
            }
            else
            {
                protocolHandler.syncWrite(jmsConsume, BasicConsumeOkBody.class);
            }
        }
        catch (AMQException e)
        {
            // clean-up the map in the event of an error
            _consumers.remove(tag);
            throw e;
        }
    }

    private BasicMessageProducer createProducerImpl(Destination destination, boolean mandatory, boolean immediate)
            throws JMSException
    {
        return createProducerImpl(destination, mandatory, immediate, false);
    }

    private BasicMessageProducer createProducerImpl(final Destination destination, final boolean mandatory,
                                                    final boolean immediate, final boolean waitUntilSent) throws JMSException
    {
        return new FailoverRetrySupport<BasicMessageProducer, JMSException>(
                new FailoverProtectedOperation<BasicMessageProducer, JMSException>()
                {
                    public BasicMessageProducer execute() throws JMSException, FailoverException
                    {
                        checkNotClosed();
                        long producerId = getNextProducerId();
                        BasicMessageProducer producer =
                                new BasicMessageProducer(_connection, (AMQDestination) destination, _transacted, _channelId,
                                                         AMQSession.this, getProtocolHandler(), producerId, immediate, mandatory, waitUntilSent);
                        registerProducer(producerId, producer);

                        return producer;
                    }
                }, _connection).execute();
    }

    private void declareExchange(AMQDestination amqd, AMQProtocolHandler protocolHandler, boolean nowait) throws AMQException
    {
        declareExchange(amqd.getExchangeName(), amqd.getExchangeClass(), protocolHandler, nowait);
    }

    /**
     * Declares the named exchange and type of exchange.
     *
     * <p/>Note that this operation automatically retries in the event of fail-over.
     *
     * @param name            The name of the exchange to declare.
     * @param type            The type of the exchange to declare.
     * @param protocolHandler The protocol handler to process the communication through.
     * @param nowait
     *
     * @throws AMQException If the exchange cannot be declared for any reason.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    private void declareExchange(final AMQShortString name, final AMQShortString type,
                                 final AMQProtocolHandler protocolHandler, final boolean nowait) throws AMQException
    {
        new FailoverNoopSupport<Object, AMQException>(new FailoverProtectedOperation<Object, AMQException>()
        {
            public Object execute() throws AMQException, FailoverException
            {
                AMQFrame exchangeDeclare =
                        ExchangeDeclareBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(),
                                                           null, // arguments
                                                           false, // autoDelete
                                                           false, // durable
                                                           name, // exchange
                                                           false, // internal
                                                           nowait, // nowait
                                                           false, // passive
                                                           getTicket(), // ticket
                                                           type); // type

                protocolHandler.syncWrite(exchangeDeclare, ExchangeDeclareOkBody.class);

                return null;
            }
        }, _connection).execute();
    }

    /**
     * Declares a queue for a JMS destination.
     *
     * <p/>Note that for queues but not topics the name is generated in the client rather than the server. This allows
     * the name to be reused on failover if required. In general, the destination indicates whether it wants a name
     * generated or not.
     *
     * <p/>Note that this operation automatically retries in the event of fail-over.
     *
     * @param amqd            The destination to declare as a queue.
     * @param protocolHandler The protocol handler to communicate through.
     *
     * @return The name of the decalred queue. This is useful where the broker is generating a queue name on behalf of
     *         the client.
     *
     * @throws AMQException If the queue cannot be declared for any reason.
     *
     * @todo Verify the destiation is valid or throw an exception.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    private AMQShortString declareQueue(final AMQDestination amqd, final AMQProtocolHandler protocolHandler)
            throws AMQException
    {
        /*return new FailoverRetrySupport<AMQShortString, AMQException>(*/
        return new FailoverNoopSupport<AMQShortString, AMQException>(
                new FailoverProtectedOperation<AMQShortString, AMQException>()
                {
                    public AMQShortString execute() throws AMQException, FailoverException
                    {
                        // Generate the queue name if the destination indicates that a client generated name is to be used.
                        if (amqd.isNameRequired())
                        {
                            amqd.setQueueName(protocolHandler.generateQueueName());
                        }

                        AMQFrame queueDeclare =
                                QueueDeclareBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(),
                                                                null, // arguments
                                                                amqd.isAutoDelete(), // autoDelete
                                                                amqd.isDurable(), // durable
                                                                amqd.isExclusive(), // exclusive
                                                                false, // nowait
                                                                false, // passive
                                                                amqd.getAMQQueueName(), // queue
                                                                getTicket()); // ticket

                        protocolHandler.syncWrite(queueDeclare, QueueDeclareOkBody.class);

                        return amqd.getAMQQueueName();
                    }
                }, _connection).execute();
    }

    /**
     * Undeclares the specified queue.
     *
     * <p/>Note that this operation automatically retries in the event of fail-over.
     *
     * @param queueName The name of the queue to delete.
     *
     * @throws JMSException If the queue could not be deleted for any reason.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    private void deleteQueue(final AMQShortString queueName) throws JMSException
    {
        try
        {
            new FailoverRetrySupport<Object, AMQException>(new FailoverProtectedOperation<Object, AMQException>()
            {
                public Object execute() throws AMQException, FailoverException
                {
                    AMQFrame queueDeleteFrame =
                            QueueDeleteBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(),
                                                           false, // ifEmpty
                                                           false, // ifUnused
                                                           true, // nowait
                                                           queueName, // queue
                                                           getTicket()); // ticket

                    getProtocolHandler().syncWrite(queueDeleteFrame, QueueDeleteOkBody.class);

                    return null;
                }
            }, _connection).execute();
        }
        catch (AMQException e)
        {
            throw new JMSAMQException("The queue deletion failed: " + e.getMessage(), e);
        }
    }

    private long getNextProducerId()
    {
        return ++_nextProducerId;
    }

    private AMQProtocolHandler getProtocolHandler()
    {
        return _connection.getProtocolHandler();
    }

    private byte getProtocolMajorVersion()
    {
        return getProtocolHandler().getProtocolMajorVersion();
    }

    private byte getProtocolMinorVersion()
    {
        return getProtocolHandler().getProtocolMinorVersion();
    }

    private boolean hasMessageListeners()
    {
        return _hasMessageListeners;
    }

    private void markClosedConsumers() throws JMSException
    {
        if (_dispatcher != null)
        {
            _dispatcher.close();
            _dispatcher = null;
        }
        // we need to clone the list of consumers since the close() method updates the _consumers collection
        // which would result in a concurrent modification exception
        final ArrayList<BasicMessageConsumer> clonedConsumers = new ArrayList<BasicMessageConsumer>(_consumers.values());

        final Iterator<BasicMessageConsumer> it = clonedConsumers.iterator();
        while (it.hasNext())
        {
            final BasicMessageConsumer con = it.next();
            con.markClosed();
        }
        // at this point the _consumers map will be empty
    }

    private void markClosedProducersAndConsumers()
    {
        try
        {
            // no need for a markClosed* method in this case since there is no protocol traffic closing a producer
            closeProducers();
        }
        catch (JMSException e)
        {
            _logger.error("Error closing session: " + e, e);
        }

        try
        {
            markClosedConsumers();
        }
        catch (JMSException e)
        {
            _logger.error("Error closing session: " + e, e);
        }
    }

    public void declareAndBind(AMQDestination amqd)
            throws
            AMQException
    {
        AMQProtocolHandler protocolHandler = getProtocolHandler();
        declareExchange(amqd, protocolHandler, false);
        AMQShortString queueName = declareQueue(amqd, protocolHandler);
        bindQueue(queueName, amqd.getRoutingKey(), new FieldTable(), amqd.getExchangeName());
    }

    /**
     * Callers must hold the failover mutex before calling this method.
     *
     * @param consumer
     *
     * @throws AMQException
     */
    private void registerConsumer(BasicMessageConsumer consumer, boolean nowait) throws AMQException // , FailoverException
    {
        AMQDestination amqd = consumer.getDestination();

        AMQProtocolHandler protocolHandler = getProtocolHandler();

        declareExchange(amqd, protocolHandler, false);

        AMQShortString queueName = declareQueue(amqd, protocolHandler);

        // bindQueue(amqd, queueName, protocolHandler, consumer.getRawSelectorFieldTable());
        bindQueue(queueName, amqd.getRoutingKey(), consumer.getRawSelectorFieldTable(), amqd.getExchangeName());

        // If IMMEDIATE_PREFETCH is not required then suspsend the channel to delay prefetch
        if (!_immediatePrefetch)
        {
            // The dispatcher will be null if we have just created this session
            // so suspend the channel before we register our consumer so that we don't
            // start prefetching until a receive/mListener is set.
            if (_dispatcher == null)
            {
                if (!isSuspended())
                {
                    try
                    {
                        suspendChannel(true);
                        _logger.info(
                                "Prefetching delayed existing messages will not flow until requested via receive*() or setML().");
                    }
                    catch (AMQException e)
                    {
                        _logger.info("Suspending channel threw an exception:" + e);
                    }
                }
            }
        }
        else
        {
            _logger.info("Immediately prefetching existing messages to new consumer.");
        }

        try
        {
            consumeFromQueue(consumer, queueName, protocolHandler, nowait, consumer.getMessageSelector());
        }
        catch (JMSException e) // thrown by getMessageSelector
        {
            throw new AMQException(e.getMessage(), e);
        }
        catch (FailoverException e)
        {
            throw new AMQException("Fail-over exception interrupted basic consume.", e);
        }
    }

    private void registerProducer(long producerId, MessageProducer producer)
    {
        _producers.put(new Long(producerId), producer);
    }

    private void rejectAllMessages(boolean requeue)
    {
        rejectMessagesForConsumerTag(null, requeue);
    }

    /**
     * @param consumerTag The consumerTag to prune from queue or all if null
     * @param requeue     Should the removed messages be requeued (or discarded. Possibly to DLQ)
     */

    private void rejectMessagesForConsumerTag(AMQShortString consumerTag, boolean requeue)
    {
        Iterator messages = _queue.iterator();
        if (_logger.isInfoEnabled())
        {
            _logger.info("Rejecting messages from _queue for Consumer tag(" + consumerTag + ") (PDispatchQ) requeue:"
                         + requeue);

            if (messages.hasNext())
            {
                _logger.info("Checking all messages in _queue for Consumer tag(" + consumerTag + ")");
            }
            else
            {
                _logger.info("No messages in _queue to reject");
            }
        }
        while (messages.hasNext())
        {
            UnprocessedMessage message = (UnprocessedMessage) messages.next();

            if ((consumerTag == null) || message.getDeliverBody().consumerTag.equals(consumerTag))
            {
                if (_logger.isDebugEnabled())
                {
                    _logger.debug("Removing message(" + System.identityHashCode(message) + ") from _queue DT:"
                                  + message.getDeliverBody().deliveryTag);
                }

                messages.remove();

                rejectMessage(message, requeue);

                if (_logger.isDebugEnabled())
                {
                    _logger.debug("Rejected the message(" + message.getDeliverBody() + ") for consumer :" + consumerTag);
                }
            }
        }
    }

    private void resubscribeConsumers() throws AMQException
    {
        ArrayList consumers = new ArrayList(_consumers.values());
        _consumers.clear();

        for (Iterator it = consumers.iterator(); it.hasNext();)
        {
            BasicMessageConsumer consumer = (BasicMessageConsumer) it.next();
            registerConsumer(consumer, true);
        }
    }

    private void resubscribeProducers() throws AMQException
    {
        ArrayList producers = new ArrayList(_producers.values());
        _logger.info(MessageFormat.format("Resubscribing producers = {0} producers.size={1}", producers, producers.size())); // FIXME: removeKey
        for (Iterator it = producers.iterator(); it.hasNext();)
        {
            BasicMessageProducer producer = (BasicMessageProducer) it.next();
            producer.resubscribe();
        }
    }

    private void returnBouncedMessage(final UnprocessedMessage message)
    {
        _connection.performConnectionTask(new Runnable()
        {
            public void run()
            {
                try
                {
                    // Bounced message is processed here, away from the mina thread
                    AbstractJMSMessage bouncedMessage =
                            _messageFactoryRegistry.createMessage(0, false, message.getBounceBody().exchange,
                                                                  message.getBounceBody().routingKey, message.getContentHeader(), message.getBodies());

                    AMQConstant errorCode = AMQConstant.getConstant(message.getBounceBody().replyCode);
                    AMQShortString reason = message.getBounceBody().replyText;
                    _logger.debug("Message returned with error code " + errorCode + " (" + reason + ")");

                    // @TODO should this be moved to an exception handler of sorts. Somewhere errors are converted to correct execeptions.
                    if (errorCode == AMQConstant.NO_CONSUMERS)
                    {
                        _connection.exceptionReceived(new AMQNoConsumersException("Error: " + reason, bouncedMessage));
                    }
                    else if (errorCode == AMQConstant.NO_ROUTE)
                    {
                        _connection.exceptionReceived(new AMQNoRouteException("Error: " + reason, bouncedMessage));
                    }
                    else
                    {
                        _connection.exceptionReceived(
                                new AMQUndeliveredException(errorCode, "Error: " + reason, bouncedMessage));
                    }

                }
                catch (Exception e)
                {
                    _logger.error(
                            "Caught exception trying to raise undelivered message exception (dump follows) - ignoring...",
                            e);
                }
            }
        });
    }

    /**
     * Suspends or unsuspends this session.
     *
     * @param suspend <tt>true</tt> indicates that the session should be suspended, <tt>false<tt> indicates that it
     *                should be unsuspended.
     *
     * @throws AMQException If the session cannot be suspended for any reason.
     *
     * @todo Be aware of possible changes to parameter order as versions change.
     */
    private void suspendChannel(boolean suspend) throws AMQException // , FailoverException
    {
        synchronized (_suspensionLock)
        {
            try
            {
                if (_logger.isDebugEnabled())
                {
                    _logger.debug("Setting channel flow : " + (suspend ? "suspended" : "unsuspended"));
                }

                _suspended = suspend;

                AMQFrame channelFlowFrame =
                        ChannelFlowBody.createAMQFrame(_channelId, getProtocolMajorVersion(), getProtocolMinorVersion(),
                                                       !suspend);

                _connection.getProtocolHandler().syncWrite(channelFlowFrame, ChannelFlowOkBody.class);
            }
            catch (FailoverException e)
            {
                throw new AMQException("Fail-over interrupted suspend/unsuspend channel.", e);
            }
        }
    }

    /** Responsible for decoding a message fragment and passing it to the appropriate message consumer. */
    private class Dispatcher extends Thread
    {

        /** Track the 'stopped' state of the dispatcher, a session starts in the stopped state. */
        private final AtomicBoolean _closed = new AtomicBoolean(false);

        private final Object _lock = new Object();
        private final AtomicLong _rollbackMark = new AtomicLong(-1);

        public Dispatcher()
        {
            super("Dispatcher-Channel-" + _channelId);
            if (_dispatcherLogger.isInfoEnabled())
            {
                _dispatcherLogger.info(getName() + " created");
            }
        }

        public void close()
        {
            _closed.set(true);
            interrupt();

            // fixme awaitTermination

        }

        public void rejectPending(BasicMessageConsumer consumer)
        {
            synchronized (_lock)
            {
                boolean stopped = _dispatcher.connectionStopped();

                if (!stopped)
                {
                    _dispatcher.setConnectionStopped(true);
                }

                // Reject messages on pre-receive queue
                consumer.rollback();

                // Reject messages on pre-dispatch queue
                rejectMessagesForConsumerTag(consumer.getConsumerTag(), true);

                // closeConsumer
                consumer.markClosed();

                _dispatcher.setConnectionStopped(stopped);

            }
        }

        public void rollback()
        {

            synchronized (_lock)
            {
                boolean isStopped = connectionStopped();

                if (!isStopped)
                {
                    setConnectionStopped(true);
                }

                _rollbackMark.set(_highestDeliveryTag.get());

                _dispatcherLogger.debug("Session Pre Dispatch Queue cleared");

                for (BasicMessageConsumer consumer : _consumers.values())
                {
                    if (!consumer.isNoConsume())
                    {
                        consumer.rollback();
                    }
                    else
                    {
                        // should perhaps clear the _SQ here.
                        // consumer._synchronousQueue.clear();
                        consumer.clearReceiveQueue();
                    }

                }

                setConnectionStopped(isStopped);
            }

        }

        public void run()
        {
            if (_dispatcherLogger.isInfoEnabled())
            {
                _dispatcherLogger.info(getName() + " started");
            }

            UnprocessedMessage message;

            // Allow disptacher to start stopped
            synchronized (_lock)
            {
                while (!_closed.get() && connectionStopped())
                {
                    try
                    {
                        _lock.wait();
                    }
                    catch (InterruptedException e)
                    {
                        // ignore
                    }
                }
            }

            try
            {
                while (!_closed.get() && ((message = (UnprocessedMessage) _queue.take()) != null))
                {
                    synchronized (_lock)
                    {

                        while (connectionStopped())
                        {
                            _lock.wait();
                        }

                        if (message.getDeliverBody().deliveryTag <= _rollbackMark.get())
                        {
                            rejectMessage(message, true);
                        }
                        else
                        {
                            synchronized (_messageDeliveryLock)
                            {
                                dispatchMessage(message);
                            }
                        }

                    }

                }
            }
            catch (InterruptedException e)
            {
                // ignore
            }

            if (_dispatcherLogger.isInfoEnabled())
            {
                _dispatcherLogger.info(getName() + " thread terminating for channel " + _channelId);
            }
        }

        // only call while holding lock
        final boolean connectionStopped()
        {
            return _connectionStopped;
        }

        boolean setConnectionStopped(boolean connectionStopped)
        {
            boolean currently;
            synchronized (_lock)
            {
                currently = _connectionStopped;
                _connectionStopped = connectionStopped;
                _lock.notify();

                if (_dispatcherLogger.isDebugEnabled())
                {
                    _dispatcherLogger.debug("Set Dispatcher Connection " + (connectionStopped ? "Stopped" : "Started")
                                            + ": Currently " + (currently ? "Stopped" : "Started"));
                }
            }

            return currently;
        }

        private void dispatchMessage(UnprocessedMessage message)
        {
            if (message.getDeliverBody() != null)
            {
                final BasicMessageConsumer consumer =
                        (BasicMessageConsumer) _consumers.get(message.getDeliverBody().consumerTag);

                if ((consumer == null) || consumer.isClosed())
                {
                    if (_dispatcherLogger.isInfoEnabled())
                    {
                        if (consumer == null)
                        {
                            _dispatcherLogger.info("Received a message(" + System.identityHashCode(message) + ")" + "["
                                                   + message.getDeliverBody().deliveryTag + "] from queue "
                                                   + message.getDeliverBody().consumerTag + " )without a handler - rejecting(requeue)...");
                        }
                        else
                        {
                            _dispatcherLogger.info("Received a message(" + System.identityHashCode(message) + ")" + "["
                                                   + message.getDeliverBody().deliveryTag + "] from queue " + " consumer("
                                                   + consumer.debugIdentity() + ") is closed rejecting(requeue)...");
                        }
                    }
                    // Don't reject if we're already closing
                    if (!_closed.get())
                    {
                        rejectMessage(message, true);
                    }
                }
                else
                {
                    consumer.notifyMessage(message, _channelId);
                }
            }
        }
    }

    /*public void requestAccess(AMQShortString realm, boolean exclusive, boolean passive, boolean active, boolean write,
        boolean read) throws AMQException
    {
        getProtocolHandler().writeCommandFrameAndWaitForReply(AccessRequestBody.createAMQFrame(getChannelId(),
                getProtocolMajorVersion(), getProtocolMinorVersion(), active, exclusive, passive, read, realm, write),
            new BlockingMethodFrameListener(_channelId)
            {

                public boolean processMethod(int channelId, AMQMethodBody frame) // throws AMQException
                {
                    if (frame instanceof AccessRequestOkBody)
                    {
                        setTicket(((AccessRequestOkBody) frame).getTicket());

                        return true;
                    }
                    else
                    {
                        return false;
                    }
                }
            });
    }*/

    private class SuspenderRunner implements Runnable
    {
        private boolean _suspend;

        public SuspenderRunner(boolean suspend)
        {
            _suspend = suspend;
        }

        public void run()
        {
            try
            {
                suspendChannel(_suspend);
            }
            catch (AMQException e)
            {
                _logger.warn("Unable to suspend channel");
            }
        }
    }
}
