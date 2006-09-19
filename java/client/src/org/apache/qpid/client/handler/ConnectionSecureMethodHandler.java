/*
 *
 * Copyright (c) 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.qpid.client.handler;

import org.apache.qpid.AMQException;
import org.apache.qpid.framing.AMQFrame;
import org.apache.qpid.framing.ConnectionSecureOkBody;
import org.apache.qpid.framing.ConnectionSecureBody;
import org.apache.qpid.client.state.AMQStateManager;
import org.apache.qpid.client.state.StateAwareMethodListener;
import org.apache.qpid.client.protocol.AMQMethodEvent;

import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

public class ConnectionSecureMethodHandler implements StateAwareMethodListener
{
    private static final ConnectionSecureMethodHandler _instance = new ConnectionSecureMethodHandler();

    public static ConnectionSecureMethodHandler getInstance()
    {
        return _instance;
    }

    public void methodReceived(AMQStateManager stateManager, AMQMethodEvent evt) throws AMQException
    {
        SaslClient client = evt.getProtocolSession().getSaslClient();
        if (client == null)
        {
            throw new AMQException("No SASL client set up - cannot proceed with authentication");
        }

        ConnectionSecureBody body = (ConnectionSecureBody) evt.getMethod();

        try
        {
            // Evaluate server challenge
            byte[] response = client.evaluateChallenge(body.challenge);
            AMQFrame responseFrame = ConnectionSecureOkBody.createAMQFrame(evt.getChannelId(), response);
            evt.getProtocolSession().writeFrame(responseFrame);
        }
        catch (SaslException e)
        {
            throw new AMQException("Error processing SASL challenge: " + e, e);
        }


    }
}
