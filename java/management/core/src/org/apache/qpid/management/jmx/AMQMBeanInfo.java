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
package org.apache.qpid.management.jmx;

import javax.management.openmbean.OpenMBeanAttributeInfo;
import java.util.Map;
import java.util.HashMap;

public class AMQMBeanInfo
{
    private Map<String, OpenMBeanAttributeInfo> _name2AttributeInfoMap = new HashMap<String, OpenMBeanAttributeInfo>();

    public AMQMBeanInfo(OpenMBeanAttributeInfo[] attributeInfos)
    {
        for (OpenMBeanAttributeInfo attributeInfo: attributeInfos)
        {
            _name2AttributeInfoMap.put(attributeInfo.getName(), attributeInfo);
        }
    }

    public OpenMBeanAttributeInfo getAttributeInfo(String name)
    {
        return _name2AttributeInfoMap.get(name);
    }
}
