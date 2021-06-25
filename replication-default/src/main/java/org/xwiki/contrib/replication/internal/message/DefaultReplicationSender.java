/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.replication.internal.message;

import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.DisposePriority;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.contrib.replication.ReplicationException;
import org.xwiki.contrib.replication.ReplicationInstance;
import org.xwiki.contrib.replication.ReplicationInstanceManager;
import org.xwiki.contrib.replication.ReplicationSender;
import org.xwiki.contrib.replication.ReplicationSenderMessage;
import org.xwiki.contrib.replication.internal.ReplicationClient;
import org.xwiki.contrib.replication.internal.message.ReplicationSenderMessageStore.FileReplicationSenderMessage;

/**
 * @version $Id$
 */
@Component
@Singleton
// Make sure the component is disposed at the end in case some data still need saving
@DisposePriority(10000)
public class DefaultReplicationSender implements ReplicationSender, Initializable, Disposable
{
    private static final QueueEntry STOP = new QueueEntry(null, (Collection<ReplicationInstance>) null);

    @Inject
    private ReplicationInstanceManager instances;

    @Inject
    private ReplicationClient client;

    @Inject
    private ReplicationSenderMessageStore store;

    @Inject
    private Logger logger;

    private boolean disposed;

    private Thread storeThread;

    private Thread sendThread;

    private final BlockingQueue<QueueEntry> storeQueue = new LinkedBlockingQueue<>(1000);

    private final BlockingQueue<QueueEntry> sendingQueue = new LinkedBlockingQueue<>(1000);

    private static final class QueueEntry
    {
        private final Collection<ReplicationInstance> targets;

        private final ReplicationSenderMessage data;

        private QueueEntry(ReplicationSenderMessage data, ReplicationInstance target)
        {
            this(data, Collections.singletonList(target));
        }

        private QueueEntry(ReplicationSenderMessage data, Collection<ReplicationInstance> targets)
        {
            this.targets = targets;
            this.data = data;
        }
    }

    @Override
    public void initialize() throws InitializationException
    {
        // Create a thread in charge of locally serializing data to send to other instances
        this.storeThread = new Thread(this::store);
        this.storeThread.setName("Replication serializing");
        this.storeThread.setPriority(Thread.NORM_PRIORITY - 1);
        this.storeThread.start();

        // Create a thread in charge of dispatching data to other instances
        this.sendThread = new Thread(this::send);
        this.sendThread.setName("Replication sending");
        this.sendThread.setPriority(Thread.NORM_PRIORITY - 2);
        // That thread can be stopped any time without really loosing anything
        this.sendThread.setDaemon(true);
        this.sendThread.start();

        // Load the queue from disk
        Queue<ReplicationSenderMessage> messages = this.store.load();
        for (ReplicationSenderMessage message : messages) {
            FileReplicationSenderMessage fileMessage = (FileReplicationSenderMessage) message;

            this.sendingQueue.add(new QueueEntry(fileMessage, fileMessage.getTargets()));
        }
    }

    private void store()
    {
        while (true) {
            QueueEntry entry;
            try {
                entry = this.sendingQueue.take();

                // Stop the loop when asked to
                if (entry == STOP) {
                    break;
                }

                syncStore(entry.data, entry.targets);
            } catch (InterruptedException e) {
                this.logger.warn("The replication storing thread has been interrupted");

                // Mark back the thread as interrupted
                this.storeThread.interrupt();

                // Stop thread
                return;
            }
        }
    }

    private void syncStore(ReplicationSenderMessage message, Collection<ReplicationInstance> targets)
    {
        // Get the instances to send the data to
        Collection<ReplicationInstance> finalTargets = targets;
        if (CollectionUtils.isEmpty(targets)) {
            finalTargets = this.instances.getInstances();
        }

        try {
            FileReplicationSenderMessage fileMessage = this.store.store(message, finalTargets);

            // Put the stored message in the sending queue
            this.sendingQueue.add(new QueueEntry(message, fileMessage.getTargets()));
        } catch (ReplicationException e) {
            this.logger.error("Failed to store the message with id [" + message.getId() + "] on disk."
                + " Might be lost if it cannot be sent to the target instance before next restart.", e);

            // Put the initial message in the sending queue and hope it's reusable
            this.sendingQueue.add(new QueueEntry(message, finalTargets));
        }
    }

    private void send()
    {
        while (!this.disposed) {
            QueueEntry entry;
            try {
                entry = this.sendingQueue.take();

                syncSend(entry.data, entry.targets);
            } catch (InterruptedException e) {
                this.logger.warn("The replication sending thread has been interrupted");

                // Mark the thread as interrupted
                this.storeThread.interrupt();

                // Stop the loop
                break;
            }
        }
    }

    private void syncSend(ReplicationSenderMessage data, Collection<ReplicationInstance> targets)
    {
        // Send the data to each instance
        for (ReplicationInstance target : targets) {
            syncSend(data, target);
        }
    }

    private void syncSend(ReplicationSenderMessage message, ReplicationInstance target)
    {
        // Send the data to the instance
        try {
            this.client.sendMessage(message, target);
        } catch (Exception e) {
            this.logger.error("Failed to send data. Reinjecting it in the queue.", e);

            // Put back the data in the queue
            this.sendingQueue.add(new QueueEntry(message, target));

            return;
        }

        // Remove this target (and data if it's the last target) from disk
        // TODO: put the remove in an async queue
        try {
            this.store.removeTarget(message, target);
        } catch (ReplicationException e) {
            this.logger.error("Failed to remove sent message with id [{}] for target instance [{}]", message.getId(),
                target.getURI(), e);
        }
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        this.disposed = true;

        disposeSerialize();
    }

    private void disposeSerialize()
    {
        try {
            this.storeQueue.put(STOP);

            // Wait for the processing to be over but not more than 60s in case it's stuck for some reason
            this.storeThread.join(60000);

            // Stop the thread if it's still running
            if (this.storeThread.isAlive()) {
                this.logger.warn("The replication serialization thread is still running, killing it");

                this.storeThread.interrupt();
            }
        } catch (InterruptedException e) {
            this.logger.warn("The replication serialization thread has been interrupted: {}",
                ExceptionUtils.getRootCauseMessage(e));

            this.storeThread.interrupt();
        }
    }

    @Override
    public void send(ReplicationSenderMessage data) throws ReplicationException
    {
        try {
            this.storeQueue.put(new QueueEntry(data, (Collection<ReplicationInstance>) null));
        } catch (InterruptedException e) {
            // Mark the thread as interrupted
            this.storeThread.interrupt();

            throw new ReplicationException(String.format("Failed to queue the data [%s]", data), e);
        }
    }

    @Override
    public void send(ReplicationSenderMessage data, Collection<ReplicationInstance> targets) throws ReplicationException
    {
        try {
            this.storeQueue.put(new QueueEntry(data, targets));
        } catch (InterruptedException e) {
            // Mark the thread as interrupted
            this.storeThread.interrupt();

            throw new ReplicationException(
                String.format("Failed to queue the data [%s] targetting instances %s", data, targets), e);
        }
    }
}