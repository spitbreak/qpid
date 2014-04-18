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
package org.apache.qpid.server.queue;

import java.util.Map;

import org.apache.qpid.server.logging.LogMessage;
import org.apache.qpid.server.logging.messages.QueueMessages;
import org.apache.qpid.server.model.LifetimePolicy;
import org.apache.qpid.server.model.ManagedAttributeField;
import org.apache.qpid.server.virtualhost.VirtualHostImpl;

public class PriorityQueueImpl extends OutOfOrderQueue<PriorityQueueImpl> implements PriorityQueue<PriorityQueueImpl>
{

    private PriorityQueueList _entries;

    @ManagedAttributeField
    private int _priorities;

    public PriorityQueueImpl(VirtualHostImpl virtualHost,
                             Map<String, Object> attributes)
    {
        super(virtualHost, attributes);
    }

    @Override
    protected void onOpen()
    {
        super.onOpen();
        _entries = PriorityQueueList.newInstance(this);
    }

    @Override
    public int getPriorities()
    {
        return _priorities;
    }

    @Override
    PriorityQueueList getEntries()
    {
        return _entries;
    }

    protected LogMessage getCreatedLogMessage()
    {
        String ownerString = getOwner();
        return QueueMessages.CREATED(ownerString,
                                     getPriorities(),
                                     ownerString != null,
                                     getLifetimePolicy() != LifetimePolicy.PERMANENT,
                                     isDurable(),
                                     !isDurable(),
                                     true);
    }

}
