#ifndef QPID_BROKER_SELECTOR_H
#define QPID_BROKER_SELECTOR_H

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

#include <string>

#include <boost/scoped_ptr.hpp>
#include <boost/shared_ptr.hpp>

namespace qpid {
namespace broker {

class Message;

/**
 * Interface to provide values to a Selector evaluation
 * (For the moment just use string values)
 * TODO: allow more complex values.
 */
class SelectorEnv {
public:
    virtual ~SelectorEnv() {};

    virtual bool present(const std::string&) const = 0;
    virtual std::string value(const std::string&) const = 0;
};

class MessageSelectorEnv : public SelectorEnv {
    const Message& msg;

    bool present(const std::string&) const;
    std::string value(const std::string&) const;

public:
    MessageSelectorEnv(const Message&);
};

class Selector {
    const std::string expression;

public:
    Selector(const std::string&);
    ~Selector();

    /**
     * Evaluate parsed expression with a given environment
     */
    bool eval(const SelectorEnv& env);

    /**
     * Apply selector to message
     * @param msg message to filter against selector
     * @return true if msg meets the selector specification
     */
    bool filter(const Message& msg);
};

/**
 * Return a Selector as specified by the string:
 * - Structured like this so that we can move to caching Selectors with the same
 *   specifications and just returning an existing one
 */
boost::shared_ptr<Selector> returnSelector(const std::string&);

}}

#endif

