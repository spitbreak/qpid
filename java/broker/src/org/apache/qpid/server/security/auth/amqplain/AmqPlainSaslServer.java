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
package org.apache.qpid.server.security.auth.amqplain;

import org.apache.qpid.framing.FieldTable;
import org.apache.qpid.framing.AMQFrameDecodingException;
import org.apache.mina.common.ByteBuffer;

import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.auth.callback.*;
import java.io.IOException;

public class AmqPlainSaslServer implements SaslServer
{
    public static final String MECHANISM = "AMQPLAIN";

    private CallbackHandler _cbh;

    private String _authorizationId;

    private boolean _complete = false;

    public AmqPlainSaslServer(CallbackHandler cbh)
    {
        _cbh = cbh;
    }

    public String getMechanismName()
    {
        return MECHANISM;
    }

    public byte[] evaluateResponse(byte[] response) throws SaslException
    {
        try
        {
            final FieldTable ft = new FieldTable(ByteBuffer.wrap(response), response.length);
            String username = (String) ft.get("LOGIN");
            // we do not care about the prompt but it throws if null
            NameCallback nameCb = new NameCallback("prompt", username);
            // we do not care about the prompt but it throws if null
            PasswordCallback passwordCb = new PasswordCallback("prompt", false);
            // TODO: should not get pwd as a String but as a char array...
            String pwd = (String) ft.get("PASSWORD");
            passwordCb.setPassword(pwd.toCharArray());
            AuthorizeCallback authzCb = new AuthorizeCallback(username, username);
            Callback[] callbacks = new Callback[]{nameCb, passwordCb, authzCb};
            _cbh.handle(callbacks);
            _complete = true;
            if (authzCb.isAuthorized())
            {
                _authorizationId = authzCb.getAuthenticationID();
                return null;
            }
            else
            {
                throw new SaslException("Authentication failed");
            }
        }
        catch (AMQFrameDecodingException e)
        {
            throw new SaslException("Unable to decode response: " + e, e);
        }
        catch (IOException e)
        {
            throw new SaslException("Error processing data: " + e, e);
        }
        catch (UnsupportedCallbackException e)
        {
            throw new SaslException("Unable to obtain data from callback handler: " + e, e);
        }
    }

    public boolean isComplete()
    {
        return _complete;
    }

    public String getAuthorizationID()
    {
        return _authorizationId;
    }

    public byte[] unwrap(byte[] incoming, int offset, int len) throws SaslException
    {
        throw new SaslException("Unsupported operation");
    }

    public byte[] wrap(byte[] outgoing, int offset, int len) throws SaslException
    {
        throw new SaslException("Unsupported operation");
    }

    public Object getNegotiatedProperty(String propName)
    {
        return null;
    }

    public void dispose() throws SaslException
    {
        _cbh = null;
    }
}
