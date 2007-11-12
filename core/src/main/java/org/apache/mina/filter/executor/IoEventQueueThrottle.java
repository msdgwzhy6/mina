/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.filter.executor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.mina.common.IoEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Throttles incoming events into {@link OrderedThreadPoolExecutor} or
 * {@link UnorderedThreadPoolExecutor}.
 * 
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev$, $Date$
 */
public class IoEventQueueThrottle implements IoEventQueueHandler {
    
    private final Logger logger = LoggerFactory.getLogger(getClass());
    
    private final IoEventSizeEstimator eventSizeEstimator;
    private volatile int threshold;
    
    private final Object lock = new Object();
    private final AtomicInteger counter = new AtomicInteger();
    private int waiters;

    public IoEventQueueThrottle() {
        this(new DefaultIoEventSizeEstimator(), 65536);
    }
    
    public IoEventQueueThrottle(int threshold) {
        this(new DefaultIoEventSizeEstimator(), threshold);
    }
    
    public IoEventQueueThrottle(IoEventSizeEstimator eventSizeEstimator, int threshold) {
        if (eventSizeEstimator == null) {
            throw new NullPointerException("eventSizeEstimator");
        }
        this.eventSizeEstimator = eventSizeEstimator;
        
        setThreshold(threshold);
    }
    
    public IoEventSizeEstimator getEventSizeEstimator() {
        return eventSizeEstimator;
    }
    
    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold: " + threshold);
        }
        this.threshold = threshold;
    }

    public boolean accept(ThreadPoolExecutor executor, IoEvent event) {
        return true;
    }
    
    public void offered(ThreadPoolExecutor executor, IoEvent event) {
        int eventSize = estimateSize(event);
        int currentCounter = counter.addAndGet(eventSize);
        logState();

        if (currentCounter >= threshold) {
            block();
        }
    }

    public void polled(ThreadPoolExecutor executor, IoEvent event) {
        int eventSize = estimateSize(event);
        int currentCounter = counter.addAndGet(-eventSize);

        logState();

        if (currentCounter < threshold) {
            unblock();
        }
    }

    private int estimateSize(IoEvent event) {
        int size = getEventSizeEstimator().estimateSize(event);
        if (size < 0) {
            throw new IllegalStateException(
                    IoEventSizeEstimator.class.getSimpleName() + " returned " +
                    "a negative value (" + size + "): " + event);
        }
        return size;
    }
    
    private void logState() {
        if (logger.isDebugEnabled()) {
            logger.debug(Thread.currentThread().getName() + " state: " + counter.get() + " / " + getThreshold());
        }
    }
    
    protected void block() {
        if (logger.isDebugEnabled()) {
            logger.debug(Thread.currentThread().getName() + " blocked: " + counter.get() + " >= " + threshold);
        }
        
        synchronized (lock) {
            while (counter.get() >= threshold) {
                waiters ++;
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    // Wait uninterruptably.
                } finally {
                    waiters --;
                }
            }
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug(Thread.currentThread().getName() + " unblocked: " + counter.get() + " < " + threshold);
        }
    }
    
    protected void unblock() {
        synchronized (lock) {
            if (waiters > 0) {
                lock.notify();
            }
        }
    }
}