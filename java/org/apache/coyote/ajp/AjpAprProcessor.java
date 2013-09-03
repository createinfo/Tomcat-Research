/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.ajp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.apache.coyote.ActionCode;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.util.net.AprEndpoint;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapper;

/**
 * Processes AJP requests.
 *
 * @author Remy Maucherat
 * @author Henri Gomez
 * @author Dan Milstein
 * @author Keith Wannamaker
 * @author Kevin Seguin
 * @author Costin Manolache
 * @author Bill Barker
 */
public class AjpAprProcessor extends AbstractAjpProcessor<Long> {

    private static final Log log = LogFactory.getLog(AjpAprProcessor.class);
    @Override
    protected Log getLog() {
        return log;
    }


    public AjpAprProcessor(int packetSize, AprEndpoint endpoint) {

        super(packetSize, endpoint);

        response.setOutputBuffer(new SocketOutputBuffer());

        // Allocate input and output buffers
        inputBuffer = ByteBuffer.allocateDirect(packetSize * 2);
        inputBuffer.limit(0);
        outputBuffer = ByteBuffer.allocateDirect(packetSize * 2);
    }


    /**
     * Direct buffer used for input.
     */
    protected final ByteBuffer inputBuffer;


    /**
     * Direct buffer used for output.
     */
    protected final ByteBuffer outputBuffer;


    /**
     * Send an action to the connector.
     *
     * @param actionCode Type of the action
     * @param param Action parameter
     */
    @Override
    protected void actionInternal(ActionCode actionCode, Object param) {

        if (actionCode == ActionCode.ASYNC_COMPLETE) {
            socketWrapper.clearDispatches();
            if (asyncStateMachine.asyncComplete()) {
                ((AprEndpoint)endpoint).processSocketAsync(this.socketWrapper,
                        SocketStatus.OPEN_READ);
            }

        } else if (actionCode == ActionCode.ASYNC_DISPATCH) {
            if (asyncStateMachine.asyncDispatch()) {
                ((AprEndpoint)endpoint).processSocketAsync(this.socketWrapper,
                        SocketStatus.OPEN_READ);
            }
        }
    }


    @Override
    protected void resetTimeouts() {
        // NO-OP. The AJP APR/native connector only uses the timeout value on
        //        time SocketWrapper for async timeouts.
    }


    @Override
    protected void setupSocket(SocketWrapper<Long> socketWrapper) {
        long socketRef = socketWrapper.getSocket().longValue();
        Socket.setrbb(socketRef, inputBuffer);
        Socket.setsbb(socketRef, outputBuffer);
    }


    @Override
    protected void setTimeout(SocketWrapper<Long> socketWrapper,
            int timeout) throws IOException {
        Socket.timeoutSet(
                socketWrapper.getSocket().longValue(), timeout * 1000);
    }


    @Override
    protected void output(byte[] src, int offset, int length)
            throws IOException {
        outputBuffer.put(src, offset, length);

        long socketRef = socketWrapper.getSocket().longValue();

        if (outputBuffer.position() > 0) {
            if ((socketRef != 0) &&
                    writeSocket(0, outputBuffer.position(), true) < 0) {
                // There are no re-tries so clear the buffer to prevent a
                // possible overflow if the buffer is used again. BZ53119.
                outputBuffer.clear();
                throw new IOException(sm.getString("ajpprocessor.failedsend"));
            }
            outputBuffer.clear();
        }
    }


    private int writeSocket(int pos, int len, boolean block) {

        Lock readLock = socketWrapper.getBlockingStatusReadLock();
        WriteLock writeLock = socketWrapper.getBlockingStatusWriteLock();
        long socket = socketWrapper.getSocket().longValue();

        boolean writeDone = false;
        int result = 0;
        try {
            readLock.lock();
            if (socketWrapper.getBlockingStatus() == block) {
                result = Socket.sendbb(socket, pos, len);
                writeDone = true;
            }
        } finally {
            readLock.unlock();
        }

        if (!writeDone) {
            try {
                writeLock.lock();
                socketWrapper.setBlockingStatus(block);
                // Set the current settings for this socket
                Socket.optSet(socket, Socket.APR_SO_NONBLOCK, (block ? 0 : 1));
                // Downgrade the lock
                try {
                    readLock.lock();
                    writeLock.unlock();
                    result = Socket.sendbb(socket, pos, len);
                } finally {
                    readLock.unlock();
                }
            } finally {
                // Should have been released above but may not have been on some
                // exception paths
                if (writeLock.isHeldByCurrentThread()) {
                    writeLock.unlock();
                }
            }
        }

        return result;
    }


    @Override
    protected boolean read(byte[] buf, int pos, int n, boolean block)
            throws IOException {

        boolean nextReadBlocks = block;

        if (!block && inputBuffer.remaining() > 0) {
            nextReadBlocks = true;
        }

        if (inputBuffer.capacity() - inputBuffer.limit() <=
                n - inputBuffer.remaining()) {
            inputBuffer.compact();
            inputBuffer.limit(inputBuffer.position());
            inputBuffer.position(0);
        }
        int nRead;
        while (inputBuffer.remaining() < n) {
            nRead = readSocket(inputBuffer.limit(),
                    inputBuffer.capacity() - inputBuffer.limit(),
                    nextReadBlocks);
            if (nRead == 0) {
                // Must be a non-blocking read
                return false;
            } else if (nRead > 0) {
                inputBuffer.limit(inputBuffer.limit() + nRead);
                nextReadBlocks = true;
            } else {
                throw new IOException(sm.getString("ajpprocessor.failedread"));
            }
        }

        inputBuffer.get(buf, pos, n);
        return true;
    }


    private int readSocket(int pos, int len, boolean block) {

        Lock readLock = socketWrapper.getBlockingStatusReadLock();
        WriteLock writeLock = socketWrapper.getBlockingStatusWriteLock();
        long socket = socketWrapper.getSocket().longValue();

        boolean readDone = false;
        int result = 0;
        try {
            readLock.lock();
            if (socketWrapper.getBlockingStatus() == block) {
                result = Socket.recvbb(socket, pos, len);
                readDone = true;
            }
        } finally {
            readLock.unlock();
        }

        if (!readDone) {
            try {
                writeLock.lock();
                socketWrapper.setBlockingStatus(block);
                // Set the current settings for this socket
                Socket.optSet(socket, Socket.APR_SO_NONBLOCK, (block ? 0 : 1));
                // Downgrade the lock
                try {
                    readLock.lock();
                    writeLock.unlock();
                    result = Socket.recvbb(socket, pos, len);
                } finally {
                    readLock.unlock();
                }
            } finally {
                // Should have been released above but may not have been on some
                // exception paths
                if (writeLock.isHeldByCurrentThread()) {
                    writeLock.unlock();
                }
            }
        }

        return result;
    }


    /**
     * Recycle the processor.
     */
    @Override
    public void recycle(boolean socketClosing) {
        super.recycle(socketClosing);

        inputBuffer.clear();
        inputBuffer.limit(0);
        outputBuffer.clear();

    }
}
