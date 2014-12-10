/*
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
 */

package org.apache.qpid.transport.network.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.thread.Threading;
import org.apache.qpid.transport.Receiver;
import org.apache.qpid.transport.Sender;
import org.apache.qpid.transport.SenderClosedException;
import org.apache.qpid.transport.SenderException;
import org.apache.qpid.transport.network.Ticker;
import org.apache.qpid.transport.network.TransportEncryption;
import org.apache.qpid.transport.network.security.ssl.SSLUtil;

public class NonBlockingSenderReceiver  implements Runnable, Sender<ByteBuffer>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(NonBlockingSenderReceiver.class);

    private final SocketChannel _socketChannel;
    private final Selector _selector;

    private final ConcurrentLinkedQueue<ByteBuffer> _buffers = new ConcurrentLinkedQueue<>();
    private final List<ByteBuffer> _encryptedOutput = new ArrayList<>();

    private final Thread _ioThread;
    private final String _remoteSocketAddress;
    private final AtomicBoolean _closed = new AtomicBoolean(false);
    private final Receiver<ByteBuffer> _receiver;
    private final int _receiveBufSize;
    private final Ticker _ticker;
    private final Set<TransportEncryption> _encryptionSet;
    private final SSLContext _sslContext;
    private ByteBuffer _netInputBuffer;
    private SSLEngine _sslEngine;

    private ByteBuffer _currentBuffer;

    private TransportEncryption _transportEncryption;
    private SSLEngineResult _status;


    public NonBlockingSenderReceiver(final SocketChannel socketChannel,
                                     Receiver<ByteBuffer> receiver,
                                     int receiveBufSize,
                                     Ticker ticker,
                                     final Set<TransportEncryption> encryptionSet,
                                     final SSLContext sslContext,
                                     final boolean wantClientAuth,
                                     final boolean needClientAuth)
    {
        _socketChannel = socketChannel;
        _receiver = receiver;
        _receiveBufSize = receiveBufSize;
        _ticker = ticker;
        _encryptionSet = encryptionSet;
        _sslContext = sslContext;


        if(encryptionSet.size() == 1)
        {
            _transportEncryption = _encryptionSet.iterator().next();
        }

        if(encryptionSet.contains(TransportEncryption.TLS))
        {
            _sslEngine = _sslContext.createSSLEngine();
            _sslEngine.setUseClientMode(false);
            SSLUtil.removeSSLv3Support(_sslEngine);
            if(needClientAuth)
            {
                _sslEngine.setNeedClientAuth(true);
            }
            else if(wantClientAuth)
            {
                _sslEngine.setWantClientAuth(true);
            }
            _netInputBuffer = ByteBuffer.allocate(_sslEngine.getSession().getPacketBufferSize());

        }

        try
        {
            _remoteSocketAddress = socketChannel.getRemoteAddress().toString();
            _socketChannel.configureBlocking(false);
            _selector = Selector.open();
            _socketChannel.register(_selector, SelectionKey.OP_READ);
        }
        catch (IOException e)
        {
            throw new SenderException("Unable to prepare the channel for non-blocking IO", e);
        }
        try
        {
            //Create but deliberately don't start the thread.
            _ioThread = Threading.getThreadFactory().createThread(this);
        }
        catch(Exception e)
        {
            throw new SenderException("Error creating SenderReceiver thread for " + _remoteSocketAddress, e);
        }

        _ioThread.setDaemon(true);
        _ioThread.setName(String.format("IoSenderReceiver - %s", _remoteSocketAddress));

    }

    public void initiate()
    {
        _ioThread.start();
    }

    @Override
    public void setIdleTimeout(final int i)
    {
        // Probably unused - dead code to be removed??
    }

    @Override
    public void send(final ByteBuffer msg)
    {
        if (_closed.get())
        {
            throw new SenderClosedException("I/O for thread " + _remoteSocketAddress + " is already closed");
        }
        // append to list and do selector wakeup
        _buffers.add(msg);
        _selector.wakeup();
    }

    @Override
    public void run()
    {

        LOGGER.debug("I/O for thread " + _remoteSocketAddress + " started");

        // never ending loop doing
        //  try to write all pending byte buffers, handle situation where zero bytes or part of a byte buffer is written
        //  read as much as you can
        //  try to write all pending byte buffers

        while (!_closed.get())
        {

            try
            {
                long currentTime = System.currentTimeMillis();
                int tick = _ticker.getTimeToNextTick(currentTime);
                if(tick <= 0)
                {
                    tick = _ticker.tick(currentTime);
                }

                LOGGER.debug("Tick " + tick);

                int numberReady = _selector.select(tick <= 0 ? 1 : tick);
                Set<SelectionKey> selectionKeys = _selector.selectedKeys();
                selectionKeys.clear();

                LOGGER.debug("Number Ready " +  numberReady);

                doWrite();
                doRead();
                boolean fullyWritten = doWrite();

                _socketChannel.register(_selector, fullyWritten ? SelectionKey.OP_READ : (SelectionKey.OP_WRITE | SelectionKey.OP_READ));
            }
            catch (IOException e)
            {
                LOGGER.info("Exception performing I/O for thread '" + _remoteSocketAddress + "': " + e);
                close();
            }
        }

        try(Selector selector = _selector; SocketChannel channel = _socketChannel)
        {
            while(!doWrite())
            {
            }

            _receiver.closed();
        }
        catch (IOException e)
        {
            LOGGER.info("Exception performing final output for thread '" + _remoteSocketAddress + "': " + e);
        }
        finally
        {
            LOGGER.info("Shutting down IO thread for " + _remoteSocketAddress);
        }
    }



    @Override
    public void flush()
    {
        // maybe just wakeup?

    }

    @Override
    public void close()
    {
        LOGGER.debug("Closing " +  _remoteSocketAddress);

        _closed.set(true);
        _selector.wakeup();

    }

    private boolean doWrite() throws IOException
    {

        ByteBuffer[] bufArray = new ByteBuffer[_buffers.size()];
        Iterator<ByteBuffer> bufferIterator = _buffers.iterator();
        for (int i = 0; i < bufArray.length; i++)
        {
            bufArray[i] = bufferIterator.next();
        }

        int byteBuffersWritten = 0;

        if(_transportEncryption == TransportEncryption.NONE)
        {


            _socketChannel.write(bufArray);

            for (ByteBuffer buf : bufArray)
            {
                if (buf.remaining() == 0)
                {
                    byteBuffersWritten++;
                    _buffers.poll();
                }
            }

            if (LOGGER.isDebugEnabled())
            {
                LOGGER.debug("Written " + byteBuffersWritten + " byte buffer(s) completely");
            }

            return bufArray.length == byteBuffersWritten;
        }
        else if(_transportEncryption == TransportEncryption.TLS)
        {
            int remaining = 0;

            do
            {
                LOGGER.debug("Handshake status: " + _sslEngine.getHandshakeStatus());
                if(_sslEngine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
                {
                    final ByteBuffer netBuffer = ByteBuffer.allocate(_sslEngine.getSession().getPacketBufferSize());
                    _status = _sslEngine.wrap(bufArray, netBuffer);
                    LOGGER.debug("Status: " + _status.getStatus() + " HandshakeStatus " + _status.getHandshakeStatus());
                    runSSLEngineTasks(_status);

                    netBuffer.flip();
                    LOGGER.debug("Encrypted " + netBuffer.remaining() + " bytes for output");
                    remaining = netBuffer.remaining();
                    if (remaining != 0)
                    {
                        _encryptedOutput.add(netBuffer);
                    }
                    for (ByteBuffer buf : bufArray)
                    {
                        if (buf.remaining() == 0)
                        {
                            byteBuffersWritten++;
                            _buffers.poll();
                        }
                    }
                }

            }
            while(remaining != 0 && _sslEngine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_UNWRAP);

            ByteBuffer[] encryptedBuffers = _encryptedOutput.toArray(new ByteBuffer[_encryptedOutput.size()]);
            long written  = _socketChannel.write(encryptedBuffers);
            LOGGER.debug("Written " + written + " encrypted bytes");

            ListIterator<ByteBuffer> iter = _encryptedOutput.listIterator();
            while(iter.hasNext())
            {
                ByteBuffer buf = iter.next();
                if(buf.remaining() == 0)
                {
                    iter.remove();
                }
                else
                {
                    break;
                }
            }

            return bufArray.length == byteBuffersWritten;

        }
        else
        {
            // TODO - actually implement
            return true;
        }
    }

    private void doRead() throws IOException
    {

        if(_transportEncryption == TransportEncryption.NONE)
        {
            int remaining = 0;
            while (remaining == 0 && !_closed.get())
            {
                if (_currentBuffer == null || _currentBuffer.remaining() == 0)
                {
                    _currentBuffer = ByteBuffer.allocate(_receiveBufSize);
                }
                _socketChannel.read(_currentBuffer);
                remaining = _currentBuffer.remaining();
                if (LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("Read " + _currentBuffer.position() + " byte(s)");
                }
                ByteBuffer dup = _currentBuffer.duplicate();
                dup.flip();
                _currentBuffer = _currentBuffer.slice();
                _receiver.received(dup);
            }
        }
        else if(_transportEncryption == TransportEncryption.TLS)
        {
            int read = 1;
            int unwrapped = 0;
            while(!_closed.get() && (read > 0 || unwrapped > 0) && _sslEngine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NEED_WRAP && (_status == null || _status.getStatus() != SSLEngineResult.Status.CLOSED))
            {
                read = _socketChannel.read(_netInputBuffer);
                LOGGER.debug("Read " + read + " encrypted bytes " + _netInputBuffer);
                _netInputBuffer.flip();
                ByteBuffer appInputBuffer =
                        ByteBuffer.allocate(_sslEngine.getSession().getApplicationBufferSize() + 50);

                _status = _sslEngine.unwrap(_netInputBuffer, appInputBuffer);
                LOGGER.debug("Status: " +_status.getStatus() + " HandshakeStatus " + _status.getHandshakeStatus());
                _netInputBuffer.compact();

                appInputBuffer.flip();
                unwrapped = appInputBuffer.remaining();
                LOGGER.debug("Unwrapped to " + unwrapped + " bytes");

                _receiver.received(appInputBuffer);

                runSSLEngineTasks(_status);
            }
        }
        else
        {
            int read = 1;
            while (!_closed.get() && read > 0)
            {

                read = _socketChannel.read(_netInputBuffer);
                LOGGER.debug("Read " + read + " possibly encrypted bytes " + _netInputBuffer);

                if (_netInputBuffer.position() >= 6)
                {
                    _netInputBuffer.flip();
                    final byte[] headerBytes = new byte[6];
                    ByteBuffer dup = _netInputBuffer.duplicate();
                    dup.get(headerBytes);

                    _transportEncryption =  looksLikeSSL(headerBytes) ? TransportEncryption.TLS : TransportEncryption.NONE;
                    LOGGER.debug("Identified transport encryption as " + _transportEncryption);

                    if (_transportEncryption == TransportEncryption.NONE)
                    {
                        _receiver.received(_netInputBuffer);
                    }
                    else
                    {
                        _netInputBuffer.compact();
                        doRead();
                    }
                    break;
                }
            }
        }
    }

    private void runSSLEngineTasks(final SSLEngineResult status)
    {
        if(status.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK)
        {
            Runnable task;
            while((task = _sslEngine.getDelegatedTask()) != null)
            {
                LOGGER.debug("Running task");
                task.run();
            }
        }
    }

    private boolean looksLikeSSL(byte[] headerBytes)
    {
        return looksLikeSSLv3ClientHello(headerBytes) || looksLikeSSLv2ClientHello(headerBytes);
    }

    private boolean looksLikeSSLv3ClientHello(byte[] headerBytes)
    {
        return headerBytes[0] == 22 && // SSL Handshake
               (headerBytes[1] == 3 && // SSL 3.0 / TLS 1.x
                (headerBytes[2] == 0 || // SSL 3.0
                 headerBytes[2] == 1 || // TLS 1.0
                 headerBytes[2] == 2 || // TLS 1.1
                 headerBytes[2] == 3)) && // TLS1.2
               (headerBytes[5] == 1); // client_hello
    }

    private boolean looksLikeSSLv2ClientHello(byte[] headerBytes)
    {
        return headerBytes[0] == -128 &&
               headerBytes[3] == 3 && // SSL 3.0 / TLS 1.x
               (headerBytes[4] == 0 || // SSL 3.0
                headerBytes[4] == 1 || // TLS 1.0
                headerBytes[4] == 2 || // TLS 1.1
                headerBytes[4] == 3);
    }

    public Principal getPeerPrincipal()
    {

        if (_sslEngine != null)
        {
            try
            {
                return _sslEngine.getSession().getPeerPrincipal();
            }
            catch (SSLPeerUnverifiedException e)
            {
                return null;
            }
        }

        return null;
    }
}
